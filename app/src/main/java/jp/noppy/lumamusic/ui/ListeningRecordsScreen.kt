package jp.noppy.lumamusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.noppy.lumamusic.data.MusicPlaylist
import jp.noppy.lumamusic.data.PlayHistory
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.playback.PlaybackUi
import java.text.DateFormat
import java.util.Date

internal enum class RecordsTab(val label: String) { Summary("まとめ"), History("履歴") }

@Composable
internal fun ListeningRecordsScreen(
    vm: LumaViewModel,
    tab: RecordsTab,
    onTab: (RecordsTab) -> Unit,
    onBack: () -> Unit,
    hasHistory: Boolean,
    history: List<Pair<PlayHistory, Track>>,
    favorites: Set<String>,
    playlists: List<MusicPlaylist>,
    playback: PlaybackUi,
    bottomPadding: Dp,
    play: () -> Unit,
) {
    val overview by vm.listening.collectAsStateWithLifecycle()
    val summaryList = rememberLazyListState()
    val historyList = rememberLazyListState()
    var sortByTime by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "戻る") }
            Text("再生記録", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            if (hasHistory || overview.plays > 0) TextButton({ confirmClear = true }) { Text("消去") }
        }
        Row(Modifier.padding(horizontal = 24.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordsTab.entries.forEach { option ->
                FilterChip(selected = tab == option, onClick = { onTab(option) }, label = { Text(option.label) }, shape = LumaShapes.Pill)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = if (tab == RecordsTab.Summary) summaryList else historyList,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = bottomPadding + 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tab == RecordsTab.History) {
                if (history.isEmpty()) item {
                    Text(if (hasHistory) "ライブラリにある曲の履歴がありません。" else "まだ再生履歴はありません。")
                }
                items(history, key = { it.first.id }) { (entry, track) ->
                    Column {
                        Text(date.format(Date(entry.playedAt)), Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        TrackRow(track, track.uri in favorites, { vm.browseTrack(listOf(track), 0, play) },
                            { vm.favorite(track.uri) }, vm, playlists, playback.mediaId == track.uri, playback.playing)
                    }
                }
            } else {
                item { ListeningTotals(overview) }
                if (overview.plays == 0L) item {
                    Text("再生記録がたまると、よく聴く曲がここに表示されます。", color = colors.onSurfaceVariant)
                }
                val ranking = if (sortByTime) overview.longestListened else overview.mostPlayed
                if (ranking.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(top = 8.dp)) {
                            Text("曲別ランキング", style = MaterialTheme.typography.titleLarge)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!sortByTime, { sortByTime = false }, label = { Text("再生回数順") }, shape = LumaShapes.Pill)
                                FilterChip(sortByTime, { sortByTime = true }, label = { Text("再生時間順") }, shape = LumaShapes.Pill)
                            }
                        }
                    }
                    itemsIndexed(ranking, key = { _, item -> item.stats.stableTrackId }) { index, item ->
                        ListeningRankRow(index + 1, item) { vm.browseTrack(listOf(item.track), 0, play) }
                    }
                } else if (overview.plays > 0) item {
                    Text("曲別ランキングにはライブラリにある曲が表示されます。", color = colors.onSurfaceVariant)
                }
                item {
                    Text(
                        "一定時間聴いた再生を集計します。時間は記録がある分を表示します。",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("再生記録を消去") },
        text = { Text("再生履歴と集計を消去します。おすすめもこの記録を使っているため、内容が変わります。") },
        confirmButton = { TextButton({ vm.clearHistory(); confirmClear = false }) { Text("消去") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("キャンセル") } },
    )
}

@Composable
private fun ListeningTotals(overview: ListeningOverview) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text("合計再生時間", style = MaterialTheme.typography.labelLarge)
                Text(listeningTime(overview.audibleMs, overview.plays), style = MaterialTheme.typography.headlineLarge)
            }
            HorizontalDivider(color = colors.onSecondaryContainer.copy(alpha = .18f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("再生回数", style = MaterialTheme.typography.labelLarge)
                    Text("${overview.plays}回", style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f)) {
                    Text("聴いた曲数", style = MaterialTheme.typography.labelLarge)
                    Text("${overview.trackCount}曲", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun ListeningRankRow(rank: Int, item: ListeningTrack, play: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val track = item.track
    Row(
        Modifier.fillMaxWidth().clip(LumaShapes.Artwork).clickable(onClick = play).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${rank}位", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        Artwork(track.artworkThumbnail ?: track.artwork, track.album, Modifier.size(48.dp), 10)
        Column(Modifier.weight(1f)) {
            TrackText(track.title)
            TrackText(track.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("${item.stats.validPlayCount}回 · ${listeningTime(item.stats.totalAudibleMs, item.stats.validPlayCount)}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun listeningTime(milliseconds: Long, plays: Long): String {
    val seconds = milliseconds / 1_000
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        hours > 0 -> "${hours}時間${minutes}分"
        minutes > 0 -> "${minutes}分${seconds % 60}秒"
        seconds > 0 -> "${seconds}秒"
        milliseconds > 0 -> "1秒未満"
        plays > 0 -> "時間未記録"
        else -> "0秒"
    }
}
