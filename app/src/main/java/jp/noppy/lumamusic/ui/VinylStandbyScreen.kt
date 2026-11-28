package jp.noppy.lumamusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
    val currentTrack = track?.takeIf { it.uri == playback.mediaId }
    val artwork = rememberPlayerArtwork(currentTrack?.artwork ?: playback.artwork)
    val colors = artworkPlayerColors(artwork)
    val reduced = LocalReducedMotion.current
    val base by animateColorAsState(
        colors.background, animationSpec = if (reduced) snap() else tween(300), label = "standby artwork color",
    )
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
    val brightness = when {
        dimmed -> .02f
        prefs.standbyFixedBrightness -> prefs.brightness
        else -> null
    }
    StandbyWindow(prefs.keepAwake, brightness)

    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            LiquidBackdropHost(
                Modifier.fillMaxSize(),
                content = { StandbyArtworkBackground(artwork, base) },
                overlay = {
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val leftWeight = if (maxWidth < 570.dp) .42f else .49f
                        val gap = if (maxWidth < 570.dp) 12.dp else 24.dp
                        Row(
                            Modifier.fillMaxSize().offset(x = (minute % 3 - 1).dp, y = ((minute / 3) % 3 - 1).dp),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(leftWeight).fillMaxHeight().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                when (panel) {
                                    "lyrics" -> LiveLyricsPanel(vm, currentTrack, Modifier.fillMaxSize())
                                    "queue" -> QueuePanel(vm, playback, Modifier.fillMaxSize())
                                    else -> VinylTurntable(
                                        artwork = artwork, title = currentTrack?.title ?: playback.title,
                                        playback = playback, resumed = resumed,
                                        toggle = vm.playback::toggle,
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
                                        vm, playback, currentTrack, favorite, compact = true, bitPerfect,
                                        panel, setPanel = { panel = it }, output, sound,
                                    )
                                    if (prefs.standbyLyrics && panel != "lyrics") StandbyLyric(vm, currentTrack, live)
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
private fun StandbyLyric(vm: LumaViewModel, track: Track?, live: State<PlaybackUi>) {
    var lyrics by remember(track?.uri) { mutableStateOf("") }
    LaunchedEffect(track?.uri) { if (track != null) lyrics = runCatching { vm.library.lyrics(track) }.getOrDefault("") }
    val lines = remember(lyrics) { parseLrc(lyrics) }
    val position = live.value.position
    val line = lines.lastOrNull { (it.timeMs ?: Long.MAX_VALUE) <= position } ?: lines.firstOrNull()
    if (line != null) Text(line.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
