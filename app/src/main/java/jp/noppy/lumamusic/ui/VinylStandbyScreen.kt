package jp.noppy.lumamusic.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import jp.noppy.lumamusic.core.parseLrc
import jp.noppy.lumamusic.data.Preferences
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
internal fun VinylStandbyScreen(
    vm: LumaViewModel,
    playback: PlaybackUi,
    track: Track?,
    favorite: Boolean,
    prefs: Preferences,
    standby: StandbyController,
    output: () -> Unit,
    sound: () -> Unit,
    bitPerfect: Boolean,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var dimmed by remember { mutableStateOf(false) }
    var minute by remember { mutableIntStateOf(0) }
    var time by remember { mutableStateOf(LocalTime.now()) }
    var panel by rememberSaveable { mutableStateOf("player") }
    var menu by remember { mutableStateOf(false) }
    val live = vm.playback.state.collectAsStateWithLifecycle()
    val preview = remember(playback.mediaId, playback.index) { mutableStateOf<Long?>(null) }
    val artwork = rememberPlayerArtwork(track?.artwork ?: playback.artwork)
    val colors = artworkPlayerColors(artwork)
    BackHandler(panel != "player") { panel = "player" }

    LaunchedEffect(resumed, prefs.dimAfter) {
        dimmed = false
        if (resumed && prefs.dimAfter) {
            delay(30_000)
            dimmed = true
        }
    }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            time = LocalTime.now()
            delay(60_000)
            minute++
        }
    }
    StandbyWindow(resumed, prefs.keepAwake, if (dimmed) .02f else prefs.brightness)

    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            LiquidBackdropHost(
                Modifier.fillMaxSize(),
                content = { StandbyArtworkBackground(artwork, colors.background) },
                overlay = {
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val leftWeight = if (maxWidth < 570.dp) .46f else .49f
                        val gap = if (maxWidth < 570.dp) 12.dp else 24.dp
                        Row(
                            Modifier.fillMaxSize().offset(x = (minute % 3 - 1).dp, y = ((minute / 3) % 3 - 1).dp),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(leftWeight).fillMaxHeight().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                when (panel) {
                                    "lyrics" -> LiveLyricsPanel(vm, track, Modifier.fillMaxSize())
                                    "queue" -> QueuePanel(vm, playback, Modifier.fillMaxSize())
                                    else -> VinylTurntable(
                                        artwork = artwork, title = track?.title ?: playback.title,
                                        playback = playback, live = live, preview = preview, resumed = resumed,
                                        toggle = vm.playback::toggle,
                                        seek = { vm.playback.seek(it, playback.mediaId, playback.index) },
                                    )
                                }
                            }
                            Column(Modifier.weight(1f - leftWeight).fillMaxHeight()) {
                                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PlayerGlassButton(Icons.Rounded.Close, "スタンバイを終了", standby::exit)
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        if (prefs.clock) {
                                            val pattern = remember { DateTimeFormatter.ofPattern("HH:mm") }
                                            Text(time.format(pattern), style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                    Box {
                                        PlayerGlassButton(Icons.Rounded.MoreHoriz, "スタンバイメニュー", { menu = true }, active = menu)
                                        AurioMenu(menu, { menu = false }) {
                                            DropdownMenuItem(
                                                text = { Text("明るさ切替") },
                                                leadingIcon = { Icon(Icons.Rounded.Brightness4, null) },
                                                onClick = { dimmed = !dimmed; menu = false },
                                            )
                                        }
                                    }
                                }
                                Column(
                                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    SharedPlayerControls(
                                        vm, playback, track, favorite, compact = true, bitPerfect,
                                        panel, setPanel = { panel = it }, output, sound, seekPreview = preview,
                                    )
                                    if (prefs.standbyLyrics && panel != "lyrics") StandbyLyric(vm, track, live)
                                }
                            }
                        }
                    }
                    if (prefs.darkStandby || dimmed) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (dimmed) .5f else .12f)))
                    }
                },
            )
        }
    }
}

@Composable
private fun StandbyWindow(resumed: Boolean, keepAwake: Boolean, brightness: Float) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, resumed, keepAwake, brightness) {
        val oldBrightness = window.attributes.screenBrightness
        val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val wasKeptOn = window.attributes.flags and keepOn != 0
        if (resumed) {
            if (keepAwake) window.addFlags(keepOn)
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
        onDispose {
            if (resumed) {
                if (!wasKeptOn) window.clearFlags(keepOn)
                window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            }
        }
    }
}

@Composable
private fun StandbyLyric(vm: LumaViewModel, track: Track?, live: State<PlaybackUi>) {
    var lyrics by remember(track?.uri) { mutableStateOf("") }
    LaunchedEffect(track?.uri) { if (track != null) lyrics = runCatching { vm.library.lyrics(track) }.getOrDefault("") }
    val lines = remember(lyrics) { parseLrc(lyrics) }
    val position = live.value.position
    val line = lines.lastOrNull { (it.timeMs ?: Long.MAX_VALUE) <= position } ?: lines.firstOrNull()
    if (line != null) Text(line.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
