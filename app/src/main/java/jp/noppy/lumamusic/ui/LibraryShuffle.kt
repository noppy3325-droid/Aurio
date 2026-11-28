package jp.noppy.lumamusic.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.data.Track

internal val LibraryShuffleTouchSize = 48.dp
private val ShuffleLensDiameter = 38.dp
private val ShuffleLensRise = 5.dp
private const val ShuffleHoldMs = 3_000L

/** The list supplies the anchor and queue. The app overlays the lens outside the captured backdrop. */
@Stable
internal class LibraryShuffleState {
    var anchor by mutableStateOf<Offset?>(null)
    var tracks by mutableStateOf<List<Track>>(emptyList(), referentialEqualityPolicy())
}

@Composable
internal fun LibraryShuffleButton(enabled: Boolean, modifier: Modifier = Modifier, onShuffle: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val window = LocalWindowInfo.current
    val haptics = LocalHapticFeedback.current
    val latestShuffle by rememberUpdatedState(onShuffle)
    var heldSince by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    var confirming by remember(enabled) { mutableStateOf(false) }
    val lensVisible by remember { derivedStateOf { heldSince != null || progress > 0f || focused } }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(heldSince) {
        val start = heldSince
        if (start == null) {
            if (progress > 0f) animate(progress, 0f, animationSpec = tween(210, easing = FastOutSlowInEasing)) { value, _ ->
                progress = value
            }
        } else {
            do {
                withFrameNanos { progress = ((SystemClock.uptimeMillis() - start) / ShuffleHoldMs.toFloat()).coerceIn(0f, 1f) }
            } while (progress < 1f)
        }
    }
    val holdGesture = if (enabled && window.isWindowFocused && !confirming) Modifier.pointerInput(window) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val slop = viewConfiguration.touchSlop
            down.consume()
            heldSince = down.uptimeMillis
            try {
                val remaining = (down.uptimeMillis + ShuffleHoldMs - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                val interrupted = withTimeoutOrNull(remaining) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed ||
                            (change.position - down.position).getDistance() > slop ||
                            event.changes.any { it.id != down.id && it.pressed }
                        ) break
                        change.consume()
                    }
                    true
                }
                if (interrupted == null && window.isWindowFocused) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    latestShuffle()
                }
            } finally {
                heldSince = null
            }
        }
    } else Modifier

    Box(
        modifier.size(LibraryShuffleTouchSize)
            .then(holdGesture)
            .semantics {
                contentDescription = "全曲シャッフル"
                role = Role.Button
                if (enabled) onClick(label = "再生を確認") { confirming = true; true } else disabled()
            }
            .onKeyEvent { event ->
                val key = event.key
                if (!enabled || (key != Key.Enter && key != Key.DirectionCenter && key != Key.Spacebar)) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) confirming = true
                true
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(ShuffleLensDiameter)
                .graphicsLayer {
                    val lift = progress * progress * (3f - 2f * progress)
                    scaleX = if (reduced) 1f else 1f + .24f * lift
                    scaleY = if (reduced) 1f else 1f + .30f * lift
                    translationY = if (reduced) 0f else -ShuffleLensRise.toPx() * lift
                    alpha = if (enabled) 1f else .44f
                },
            contentAlignment = Alignment.Center,
        ) {
            if (lensVisible) Box(
                Modifier.matchParentSize()
                    .graphicsLayer { alpha = if (focused) 1f else (progress * 10f).coerceIn(0f, 1f) }
                    .liquidSurface(selectionLens = true)
                    .drawWithCache {
                        val rim = Brush.linearGradient(listOf(Color.White.copy(alpha = .64f), scheme.onSurface.copy(alpha = .18f)))
                        val stroke = Stroke(1.dp.toPx())
                        val arc = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                        val inset = 3.dp.toPx()
                        val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                        onDrawWithContent {
                            drawContent()
                            drawCircle(rim, radius = size.minDimension / 2f - stroke.width / 2f, style = stroke)
                            if (focused) drawCircle(scheme.primary, radius = size.minDimension / 2f - inset, style = arc)
                            if (progress > 0f) drawArc(scheme.primary, -90f, progress * 360f, false,
                                topLeft = Offset(inset, inset), size = arcSize, style = arc)
                        }
                    },
            )
            Icon(Icons.Rounded.Shuffle, contentDescription = null, Modifier.size(24.dp), tint = scheme.onSurfaceVariant)
        }
    }

    if (enabled && confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("全曲をシャッフルしますか？") },
        confirmButton = {
            TextButton({ confirming = false; latestShuffle() }) { Text("再生") }
        },
        dismissButton = { TextButton({ confirming = false }) { Text("キャンセル") } },
    )
}

@Composable
internal fun LibraryShuffleRipple(origin: Offset, modifier: Modifier = Modifier, onFinished: () -> Unit) {
    val wave = remember(origin) { Animatable(0f) }
    val latestFinished by rememberUpdatedState(onFinished)
    val accent = MaterialTheme.colorScheme.primary
    LaunchedEffect(wave) {
        wave.animateTo(1f, tween(630))
        latestFinished()
    }
    Canvas(modifier) {
        val t = wave.value
        val push = EaseOutCubic.transform(t)
        val center = origin - Offset(0f, ShuffleLensRise.toPx())
        val outer = 24.dp.toPx() + 126.dp.toPx() * push
        val tail = ((t - .11f) / .89f).coerceIn(0f, 1f)
        val inner = 24.dp.toPx() + 91.dp.toPx() * EaseOutCubic.transform(tail)
        val fade = (1f - t) * (1f - t)
        drawCircle(accent.copy(alpha = .23f * fade), outer, center, style = Stroke((6f - 3f * push).dp.toPx()))
        drawCircle(Color.White.copy(alpha = .75f * fade), outer, center, style = Stroke(1.4.dp.toPx()))
        if (t > .11f) drawCircle(accent.copy(alpha = .43f * (1f - tail)), inner, center, style = Stroke(1.5.dp.toPx()))
    }
}
