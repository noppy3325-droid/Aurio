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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.noppy.lumamusic.data.*
import java.text.DateFormat
import java.util.Date

/** Favor unheard tracks by artists in the latest history, then known tracks, then the library. */
internal fun recommendTracks(tracks: List<Track>, recentUris: List<String>): List<Track> {
    val byUri = tracks.associateBy { it.uri }
    val recent = recentUris.mapNotNull(byUri::get)
    val artists = recent.map { it.artist }.filter { it.isNotBlank() && it != "不明なアーティスト" }.toSet()
    val listened = recent.map { it.uri }.toSet()
    return tracks.sortedBy { track ->
        when {
            track.artist in artists && track.uri !in listened -> 0
            track.uri in listened -> 1
            else -> 2
        }
    }
}

@Composable
internal fun HomeScreen(vm: LumaViewModel, tracks: List<Track>, users: List<UserTrack>, playlists: List<MusicPlaylist>, addFolder: () -> Unit, openPlayer: () -> Unit, bottomContentPadding: Dp, active: Boolean) {
    val history by vm.history.collectAsStateWithLifecycle()
    val playback by vm.playbackSummary.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(active && showHistory) { showHistory = false }
    val byUri = remember(tracks) { tracks.associateBy { it.uri } }
    val availableHistory = remember(history, byUri) { history.mapNotNull { entry -> byUri[entry.trackUri]?.let { entry to it } } }
    val recommendations = remember(tracks, history) { recommendTracks(tracks, history.map { it.trackUri }) }
    val favorites = remember(users) { users.filter { it.favorite }.map { it.uri }.toSet() }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LazyColumn(contentPadding = PaddingValues(bottom = bottomContentPadding + 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showHistory) IconButton({ showHistory = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "戻る") }
                Text(if (showHistory) "再生履歴" else "ホーム", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                if (showHistory && history.isNotEmpty()) TextButton({ confirmClear = true }) { Text("消去") }
            }
        }
        if (showHistory) {
            if (availableHistory.isEmpty()) item { EmptyHome("まだ再生履歴はありません", "曲を聴くと、ここに表示されます。") }
            items(availableHistory, key = { it.first.id }) { (entry, track) ->
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(dateFormat.format(Date(entry.playedAt)), Modifier.padding(start = 4.dp, top = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TrackRow(track, track.uri in favorites, { vm.browseTrack(listOf(track), 0, openPlayer) }, { vm.favorite(track.uri) }, vm, playlists, playback.mediaId == track.uri, playback.playing)
                }
            }
        } else if (tracks.isEmpty()) {
            item {
                EmptyHome("音楽をはじめよう", "音楽フォルダーを追加すると、ここに曲が並びます。")
                TextButton(addFolder, Modifier.padding(horizontal = 24.dp)) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("フォルダーを追加") }
            }
        } else {
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(if (availableHistory.isEmpty()) "あなたの音楽から" else "あなたへのおすすめ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (availableHistory.isEmpty()) "ライブラリから再生してみましょう" else "最近聴いた曲をもとに", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(recommendations.take(12), key = { _, track -> track.uri }) { index, track ->
                        LaunchedEffect(track.uri, track.detailState) { vm.ensureDetails(track.uri) }
                        Column(Modifier.width(190.dp).clip(RoundedCornerShape(16.dp)).clickable { vm.browseTrack(recommendations, index, openPlayer) }) {
                            Artwork(track.artworkThumbnail ?: track.artwork, track.album, Modifier.fillMaxWidth().aspectRatio(1f), 16)
                            Text(track.title, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近再生した曲", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton({ showHistory = true }) { Text("すべて見る") }
                }
            }
            if (availableHistory.isEmpty()) item { Text("再生履歴はここに表示されます。", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(availableHistory.take(8), key = { it.first.id }) { (_, track) ->
                Box(Modifier.padding(horizontal = 24.dp)) { TrackRow(track, track.uri in favorites, { vm.browseTrack(listOf(track), 0, openPlayer) }, { vm.favorite(track.uri) }, vm, playlists, playback.mediaId == track.uri, playback.playing) }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("再生履歴を消去") }, text = { Text("すべての再生履歴を消去しますか？") }, confirmButton = { TextButton({ vm.action { vm.library.dao.clearHistory() }; confirmClear = false }) { Text("消去") } }, dismissButton = { TextButton({ confirmClear = false }) { Text("キャンセル") } })
}

@Composable private fun EmptyHome(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
