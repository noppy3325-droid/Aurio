package jp.noppy.lumamusic.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.noppy.lumamusic.LumaApp
import jp.noppy.lumamusic.core.OutputMode
import jp.noppy.lumamusic.data.*
import jp.noppy.lumamusic.playback.PlaybackController
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LumaViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LumaApp
    val library = app.library
    val settings = app.settings
    val playback = PlaybackController(app, viewModelScope)
    val playbackSummary = playback.state.map { it.copy(position = 0L) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackUi())
    val tracks = library.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val folders = library.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val users = library.userTracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = library.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = library.dao.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val refreshHomeAt = MutableStateFlow(0L)
    private val recommendationInputs = combine(
        library.tracks.distinctUntilChanged(::sameRankingTracks),
        library.dao.trackFolders(),
        library.userTracks.map { rows -> rows.filter { it.favorite }.map { it.uri }.toSet() }.distinctUntilChanged(),
        library.entries.map { rows -> rows.map { it.trackUri }.toSet() }.distinctUntilChanged(),
        ::RecommendationInputs,
    )
    private val lastHistoryById = library.dao.history().map { rows ->
        // The recent list is ordered newest first, including rows from the old DB.
        buildMap { rows.forEach { putIfAbsent(stableTrackId(it.trackUri), it.playedAt) } }
    }
    private val homeRefresh = merge(refreshHomeAt, flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(5 * 60_000L)
        }
    })
    internal val homeFeed = combine(
        recommendationInputs, library.dao.playbackStats(), lastHistoryById, homeRefresh
    ) { (tracks, refs, favorites, playlistTracks), stats, lastHistory, refresh ->
        val canRead = app.contentResolver.persistedUriPermissions.asSequence()
            .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val availableUris = refs.asSequence().filter { it.folderUri in canRead }.map { it.trackUri }.toSet()
        val candidates = tracks.filter { it.uri in availableUris }
        recommendHome(
            candidates, stats, lastHistory, favorites, playlistTracks, System.currentTimeMillis(),
            isAvailable = { hasReadableDocument(it, refresh) }, trackId = ::stableTrackId
        )
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeRecommendations())
    internal val searchIndex = combine(tracks, playlists, ::SearchIndex)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())
    val output = app.output.state
    val scan = library.scan
    private val mutableMessage = MutableStateFlow<String?>(null)
    private var lastScan = 0L
    val message = mutableMessage.asStateFlow()
    private val feedbackEvents = Channel<String>(Channel.CONFLATED)
    val feedback = feedbackEvents.receiveAsFlow()
    init {
        rescan(false)
        viewModelScope.launch {
            playback.state.map { it.mediaId }.distinctUntilChanged().filterNotNull().collect { uri ->
                action { library.ensureDetails(uri) }
            }
        }
    }
    fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; mutableMessage.value = e.localizedMessage ?: "操作に失敗しました" } } }
    fun dismissMessage() { mutableMessage.value = null }
    fun addFolder(uri: Uri) { action { library.addFolder(uri) } }
    fun removeFolder(folder: MusicFolder) { playback.pause(); action { library.removeFolder(folder) } }
    fun rescan(force: Boolean = true) { lastScan = android.os.SystemClock.elapsedRealtime(); action { library.rescan(force) } }
    fun refreshIfStale() {
        refreshHomeAt.value = SystemClock.elapsedRealtime()
        if (!scan.value.running && SystemClock.elapsedRealtime() - lastScan > 60_000) rescan(false)
    }
    fun favorite(uri: String) { action { library.toggleFavorite(uri) } }
    fun clearHistory() = action { app.playbackHistory.clear() }

    private data class DocumentCheck(val checkedAt: Long, val modified: Long, val size: Long, val readable: Boolean)
    // Only ranked candidates are checked. This never runs in the scanner or opens the audio stream.
    private val documentChecks = LinkedHashMap<String, DocumentCheck>(96, .75f, true)
    private fun hasReadableDocument(track: Track, refresh: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val known = documentChecks[track.uri]
        if (known != null && known.checkedAt >= refresh && now - known.checkedAt < 60_000 &&
            known.modified == track.modified && known.size == track.size
        ) return known.readable
        val readable = canReadDocument(track.uri)
        if (documentChecks.size >= 96) documentChecks.remove(documentChecks.keys.first())
        documentChecks[track.uri] = DocumentCheck(now, track.modified, track.size, readable)
        return readable
    }

    private fun canReadDocument(uri: String): Boolean = try {
        app.contentResolver.query(
            Uri.parse(uri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
        )?.use { it.moveToFirst() } == true
    } catch (_: Exception) {
        false
    }

    fun browseRecommendation(tracks: List<Track>, index: Int, openPlayer: () -> Unit) = action {
        val chosen = tracks.getOrNull(index) ?: return@action
        if (withContext(Dispatchers.IO) { canReadDocument(chosen.uri) } && this.tracks.value.any { it.uri == chosen.uri }) {
            browseTrack(tracks, index, openPlayer)
        } else {
            refreshHomeAt.value = SystemClock.elapsedRealtime()
            feedbackEvents.trySend("この曲を読み込めません。音楽フォルダーを確認してください")
        }
    }

    fun browseTrack(tracks: List<Track>, index: Int, openPlayer: () -> Unit) {
        if (playback.playFromBrowse(tracks, index)) openPlayer()
        else feedbackEvents.trySend("再生できません。接続後にもう一度お試しください")
    }
    fun playGroup(tracks: List<Track>, shuffle: Boolean?, openPlayer: () -> Unit) {
        if (playback.play(tracks, shuffle = shuffle)) openPlayer()
        else feedbackEvents.trySend("再生できません。接続後にもう一度お試しください")
    }
    fun enqueue(track: Track, next: Boolean) {
        playback.enqueue(track, next) { added ->
            feedbackEvents.trySend(when {
                !added -> "キューに追加できませんでした"
                next -> "次に再生します"
                else -> "キューの最後に追加しました"
            })
        }
    }
    fun addToPlaylist(track: Track, playlist: MusicPlaylist) = action {
        library.addToPlaylist(playlist.id, track.uri)
        feedbackEvents.trySend("「${playlist.name}」に追加しました")
    }
    fun createPlaylistWithTrack(name: String, track: Track) = action {
        library.createPlaylistWithTrack(name, track.uri)
        feedbackEvents.trySend("「${name.trim()}」を作成して曲を追加しました")
    }
    suspend fun ensureDetails(uri: String) { library.ensureDetails(uri) }
    fun mode(mode: OutputMode) { action { settings.mode(mode) } }
    override fun onCleared() { playback.close(); super.onCleared() }
}

private data class RecommendationInputs(
    val tracks: List<Track>,
    val refs: List<TrackFolder>,
    val favorites: Set<String>,
    val playlistTracks: Set<String>,
)

private fun sameRankingTracks(old: List<Track>, current: List<Track>): Boolean =
    old.size == current.size && old.indices.all { index ->
        val before = old[index]
        val after = current[index]
        before.uri == after.uri && before.artist == after.artist && before.album == after.album &&
            before.albumArtist == after.albumArtist && before.duration == after.duration &&
            before.added == after.added && before.modified == after.modified && before.size == after.size
    }
