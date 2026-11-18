package jp.noppy.lumamusic.ui

import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.data.TrackPlaybackStats
import kotlin.math.ln1p

private const val DAY_MS = 86_400_000L
private const val FREQUENT_PLAYS = 3L

internal data class HomeRecommendations(
    val featured: List<String> = emptyList(),
    val frequent: List<String> = emptyList(),
    val rediscover: List<String> = emptyList(),
    val personalized: Boolean = false,
    val ready: Boolean = false,
)

internal fun recommendHome(
    tracks: List<Track>,
    stats: List<TrackPlaybackStats>,
    lastFromHistory: Map<String, Long>,
    favorites: Set<String>,
    playlistTracks: Set<String>,
    now: Long,
    isAvailable: (Track) -> Boolean = { true },
    trackId: (String) -> String,
): HomeRecommendations {
    data class Ranked(val track: Track, val affinity: Double, val dormancy: Double)
    val byId = stats.associateBy { it.stableTrackId }
    val identities = tracks.associate { it.uri to trackId(it.uri) }
    val maxPlays = identities.values.maxOfOrNull { byId[it]?.validPlayCount ?: 0 } ?: 0
    val maximumAffinity = ln1p(maxPlays.coerceAtLeast(1).toDouble())
    val ranked = tracks.mapNotNull { track ->
        val id = identities.getValue(track.uri)
        val stat = byId[id]?.takeIf { it.validPlayCount >= FREQUENT_PLAYS } ?: return@mapNotNull null
        val lastPlayedAt = maxOf(stat.lastPlayedAt, lastFromHistory[id] ?: 0L)
        val daysIdle = if (lastPlayedAt in 1..now) (now - lastPlayedAt) / DAY_MS else 0
        val dormant = if (daysIdle >= 14) ((daysIdle.coerceAtMost(60) - 13) / 47.0) else 0.0
        val completion = stat.completedPlayCount.toDouble() / (stat.validPlayCount + 4.0)
        val audible = if (track.duration <= 0) 0.0 else
            (stat.totalAudibleMs / (track.duration.toDouble() * stat.validPlayCount)).coerceIn(0.0, 1.0)
        val lifetime = ln1p(stat.validPlayCount.toDouble()) / maximumAffinity
        val recent = if (daysIdle < 7 && lastPlayedAt in 1..now) (7 - daysIdle) / 7.0 else 0.0
        Ranked(track, 0.72 * lifetime + 0.16 * completion + 0.09 * audible + 0.03 * recent, dormant)
    }
    val frequent = ranked.sortedWith(compareByDescending<Ranked> { it.affinity }.thenBy { it.track.uri })
        .map { it.track }
    val rediscover = ranked.filter { it.dormancy > 0 }
        .sortedWith(compareByDescending<Ranked> { 0.72 * it.affinity + 0.28 * it.dormancy }.thenBy { it.track.uri })
        .map { it.track }
    val savedAndLibrary = tracks.sortedWith(
        compareByDescending<Track> { it.uri in favorites }
            .thenByDescending { it.uri in playlistTracks }
            .thenByDescending { it.added }
            .thenBy { it.uri }
    )
    val discovery = savedAndLibrary.filter { identities.getValue(it.uri).let { id -> id !in byId && id !in lastFromHistory } }
    val availability = mutableMapOf<String, Boolean>()
    fun pickFrom(pools: List<List<Track>>, limit: Int): List<String> {
        val selection = ShelfSelection(
            { track -> availability.getOrPut(track.uri) { isAvailable(track) } },
            { track -> identities.getValue(track.uri) }
        )
        repeat(limit) { index ->
            val preferredPool = index % pools.size
            val inOrder = pools.drop(preferredPool) + pools.take(preferredPool)
            if (!inOrder.any { selection.addFrom(it, diversify = true, avoidRuns = true) } &&
                !inOrder.any { selection.addFrom(it, diversify = true) } &&
                !inOrder.any { selection.addFrom(it, diversify = false) }
            ) return selection.uris
        }
        return selection.uris
    }
    val frequentShelf = pickFrom(listOf(frequent), 6)
    val rediscoverShelf = pickFrom(listOf(rediscover), 6)
    val personalized = frequentShelf.isNotEmpty()
    val featured = if (personalized) pickFrom(listOf(frequent, rediscover, frequent, rediscover, discovery), 10)
        else pickFrom(listOf(savedAndLibrary), 10)
    return HomeRecommendations(featured, frequentShelf, rediscoverShelf, personalized, ready = true)
}

private class ShelfSelection(
    private val isAvailable: (Track) -> Boolean,
    private val trackId: (Track) -> String,
) {
    private val selected = mutableListOf<Track>()
    private val ids = mutableSetOf<String>()
    private val artists = mutableMapOf<String, Int>()
    private val albums = mutableMapOf<Pair<String, String>, Int>()
    val uris get() = selected.map { it.uri }

    fun addFrom(candidates: List<Track>, diversify: Boolean, avoidRuns: Boolean = false): Boolean {
        val track = candidates.firstOrNull { candidate ->
            trackId(candidate) !in ids &&
                (!diversify || artistCount(candidate) < 2 && albumCount(candidate) < 2) &&
                (!avoidRuns || selected.lastOrNull()?.let { previous -> sharesArtistOrAlbum(previous, candidate) } != true) &&
                isAvailable(candidate)
        } ?: return false
        selected.add(track)
        ids.add(trackId(track))
        artist(track)?.let { artists[it] = artistCount(track) + 1 }
        album(track)?.let { albums[it] = albumCount(track) + 1 }
        return true
    }

    private fun artist(track: Track) = track.artist.takeUnless { it.isBlank() || it == "不明なアーティスト" }
    private fun album(track: Track) = track.album.takeUnless { it.isBlank() || it == "不明なアルバム" }
        ?.let { track.albumArtist.ifBlank { track.artist } to it }
    private fun artistCount(track: Track) = artist(track)?.let(artists::get) ?: 0
    private fun albumCount(track: Track) = album(track)?.let(albums::get) ?: 0
    private fun sharesArtistOrAlbum(first: Track, second: Track) =
        artist(first)?.let { it == artist(second) } == true || album(first)?.let { it == album(second) } == true
}
