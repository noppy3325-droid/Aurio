package jp.noppy.lumamusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.noppy.lumamusic.data.*
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HomeScreen(vm: LumaViewModel, tracks: List<Track>, users: List<UserTrack>, playlists: List<MusicPlaylist>, addFolder: () -> Unit, openPlayer: () -> Unit, bottomContentPadding: Dp, active: Boolean) {
    val history by vm.history.collectAsStateWithLifecycle()
    val playback by vm.playbackSummary.collectAsStateWithLifecycle()
    val recommendations by vm.homeFeed.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val homeList = rememberLazyListState()
    val historyList = rememberLazyListState()
    val featuredList = rememberLazyListState()
    val frequentList = rememberLazyListState()
    val rediscoverList = rememberLazyListState()
    var touchInProgress by remember { mutableStateOf(false) }
    var displayedFeed by remember { mutableStateOf(recommendations) }
    LaunchedEffect(recommendations, active, showHistory) {
        if (active && !showHistory) {
            snapshotFlow {
                !touchInProgress && !homeList.isScrollInProgress && !featuredList.isScrollInProgress &&
                    !frequentList.isScrollInProgress && !rediscoverList.isScrollInProgress
            }.first { it }
            displayedFeed = recommendations
        }
    }
    BackHandler(active && showHistory) { showHistory = false }
    val byUri = remember(tracks) { tracks.associateBy { it.uri } }
    val availableHistory = remember(history, byUri) {
        val byStableId by lazy(LazyThreadSafetyMode.NONE) { tracks.associateBy { stableTrackId(it.uri) } }
        history.mapNotNull { entry ->
            val track = byUri[entry.trackUri] ?: byStableId[stableTrackId(entry.trackUri)]
            track?.let { entry to it }
        }
    }
    val recent = remember(availableHistory) { availableHistory.distinctBy { stableTrackId(it.second.uri) }.take(6) }
    val featured = remember(displayedFeed.featured, byUri) { displayedFeed.featured.mapNotNull(byUri::get) }
    val frequent = remember(displayedFeed.frequent, byUri) { displayedFeed.frequent.mapNotNull(byUri::get) }
    val rediscover = remember(displayedFeed.rediscover, byUri) { displayedFeed.rediscover.mapNotNull(byUri::get) }
    val favorites = remember(users) { users.filter { it.favorite }.map { it.uri }.toSet() }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            // Observe the gesture without consuming it or competing with horizontal tab navigation.
            try {
                awaitPointerEventScope {
                    while (true) {
                        touchInProgress = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                    }
                }
            } finally {
                touchInProgress = false
            }
        },
        state = if (showHistory) historyList else homeList,
        contentPadding = PaddingValues(bottom = bottomContentPadding + 20.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showHistory) IconButton({ showHistory = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "戻る") }
                Text(if (showHistory) "再生履歴" else "ホーム", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                if (showHistory && history.isNotEmpty()) TextButton({ confirmClear = true }) { Text("消去") }
            }
        }
        if (showHistory) {
            if (availableHistory.isEmpty()) item {
                if (history.isEmpty()) EmptyHome("まだ再生履歴はありません", "曲を聴くと、ここに表示されます。")
                else EmptyHome("再生できる曲がありません", "音楽フォルダーへのアクセスを確認してください。")
            }
            items(availableHistory, key = { it.first.id }) { (entry, track) ->
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(dateFormat.format(Date(entry.playedAt)), Modifier.padding(start = 4.dp, top = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TrackRow(track, track.uri in favorites, { vm.browseTrack(listOf(track), 0, openPlayer) }, { vm.favorite(track.uri) }, vm, playlists, playback.mediaId == track.uri, playback.playing)
                }
            }
        } else {
            if (tracks.isEmpty() || displayedFeed.ready && featured.isEmpty()) {
                item(key = "empty-home") {
                    EmptyHome("音楽をはじめよう", "音楽フォルダーを追加すると曲が並びます。")
                    TextButton(addFolder, Modifier.padding(horizontal = 24.dp)) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("フォルダーを追加") }
                }
            }
            if (featured.isNotEmpty()) {
                item(key = "featured") {
                    HomeShelf(if (displayedFeed.personalized) "あなたへのおすすめ" else "あなたの音楽から", featured, featuredList, 190.dp, vm, openPlayer)
                }
            }
            if (frequent.isNotEmpty()) {
                item(key = "frequent") {
                    HomeShelf("よく聴く曲", frequent, frequentList, 134.dp, vm, openPlayer)
                }
            }
            if (rediscover.isNotEmpty()) {
                item(key = "rediscover") {
                    HomeShelf("久しぶりに聴きたい曲", rediscover, rediscoverList, 134.dp, vm, openPlayer)
                }
            }
            if (tracks.isNotEmpty() || history.isNotEmpty()) {
                item(key = "recent") {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近再生した曲", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton({ showHistory = true }) { Text("すべて見る") }
                    }
                }
                if (history.isEmpty()) item {
                    Text("再生履歴はここに表示されます。", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(recent, key = { it.first.id }) { (_, track) ->
                    Box(Modifier.padding(horizontal = 24.dp)) { TrackRow(track, track.uri in favorites, { vm.browseTrack(listOf(track), 0, openPlayer) }, { vm.favorite(track.uri) }, vm, playlists, playback.mediaId == track.uri, playback.playing) }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("再生履歴を消去") },
            text = { Text("再生履歴と、おすすめに使う再生統計を消去しますか？") },
            confirmButton = { TextButton({ vm.clearHistory(); confirmClear = false }) { Text("消去") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun HomeShelf(
    title: String,
    tracks: List<Track>,
    state: LazyListState,
    cardWidth: Dp,
    vm: LumaViewModel,
    openPlayer: () -> Unit,
) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
                LaunchedEffect(track.uri, track.detailState) { vm.ensureDetails(track.uri) }
                Column(
                    Modifier.width(cardWidth).clip(RoundedCornerShape(16.dp))
                        .clickable { vm.browseRecommendation(tracks, index, openPlayer) }
                ) {
                    Artwork(track.artworkThumbnail ?: track.artwork, track.album, Modifier.fillMaxWidth().aspectRatio(1f), 16)
                    Text(track.title, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable private fun EmptyHome(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
