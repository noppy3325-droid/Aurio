package jp.noppy.lumamusic.ui

import android.provider.Settings

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

private const val LpDegreesPerSecond = 200f

private class VinylRotation {
    var angle by mutableFloatStateOf(0f)
    var speed = 0f
}

@Composable
internal fun VinylTurntable(
    artwork: PlayerArtworkScene?,
    title: String,
    playback: PlaybackUi,
    live: State<PlaybackUi>,
    preview: MutableState<Long?>,
    resumed: Boolean,
    toggle: () -> Unit,
    seek: (Long) -> Boolean,
) {
    val rotation = remember { VinylRotation() }
    val view = LocalView.current
    val systemReduced = remember(view, resumed) {
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val reduced = LocalReducedMotion.current || systemReduced
    val hasTrack = playback.mediaId != null
    val canSeek = playback.canSeek
    val currentSeek by rememberUpdatedState(seek)
    val currentToggle by rememberUpdatedState(toggle)
    val dragging by remember(preview) { derivedStateOf { preview.value != null } }
    val spinning = resumed && playback.playing && !reduced && !dragging
    LaunchedEffect(spinning, resumed, reduced, dragging) {
        if (!resumed || reduced || dragging) {
            rotation.speed = 0f
            return@LaunchedEffect
        }
        val target = if (spinning) LpDegreesPerSecond else 0f
        val ramp = if (spinning) .32f else .42f
        var last = withFrameNanos { it }
        while (spinning || rotation.speed > 0f) {
            withFrameNanos { frame ->
                val elapsed = ((frame - last) / 1_000_000_000f).coerceIn(0f, .1f)
                last = frame
                val step = LpDegreesPerSecond * elapsed / ramp
                val old = rotation.speed
                rotation.speed = target.coerceIn(old - step, old + step)
                rotation.angle = wrapDegrees(rotation.angle + (old + rotation.speed) * .5f * elapsed)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(
            Modifier.size(side).pointerInput(playback.mediaId, playback.index, canSeek, resumed) {
                if (!hasTrack || !resumed) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val deck = VinylGeometry(size.width.toFloat())
                    val start = down.position
                    val radius = (start - deck.center).getDistance()
                    val onDisc = radius <= deck.radius
                    val onRim = onDisc && radius >= deck.radius * .59f
                    val initial = live.value
                    val onArm = canSeek && deck.hitsArm(start, fraction(initial.position, initial.duration), 21.dp.toPx())
                    if (!onDisc && !onArm) return@awaitEachGesture
                    down.consume()
                    var dragging = false
                    var settled = false
                    var position = initial.position.toDouble()
                    var previousAngle = deck.discAngle(start).takeIf { onRim }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (pointer.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                            if (pointer.changedToUp()) {
                                pointer.consume()
                                settled = dragging || pointer.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                                break
                            }
                            if (!dragging && (pointer.position - start).getDistance() > viewConfiguration.touchSlop) {
                                if (!canSeek || (!onArm && !onRim)) break
                                dragging = true
                                position = live.value.position.toDouble()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            if (dragging) {
                                val duration = live.value.duration
                                if (duration <= 0) break
                                if (onArm) {
                                    position = tonearmFraction(deck.armAngle(pointer.position)).toDouble() * duration
                                } else {
                                    val angle = deck.discAngle(pointer.position)
                                        .takeIf { (pointer.position - deck.center).getDistance() >= deck.radius * .38f }
                                    val previous = previousAngle
                                    if (angle != null && previous != null) {
                                        val delta = angleDelta(previous, angle)
                                        rotation.angle = wrapDegrees(rotation.angle + delta)
                                        position = vinylPosition(position, delta, duration)
                                    }
                                    previousAngle = angle
                                }
                                preview.value = position.roundToLong()
                            }
                            pointer.consume()
                        }
                        if (settled) {
                            if (dragging) {
                                val target = preview.value
                                if (target != null && currentSeek(target)) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            } else if (onDisc) currentToggle()
                        }
                    } finally {
                        preview.value = null
                    }
                }
            },
        ) {
            Platter(Modifier.matchParentSize())
            Box(
                Modifier.offset(x = side * .05f, y = side * .11f)
                    .size(side * .80f)
                    .semantics {
                        contentDescription = "レコード盤"
                        role = Role.Button
                        if (hasTrack) onClick(if (playback.playing) "一時停止" else "再生") { currentToggle(); true }
                        if (canSeek) customActions = listOf(
                            CustomAccessibilityAction("10秒戻す") { currentSeek(live.value.position - 10_000) },
                            CustomAccessibilityAction("10秒進める") { currentSeek(live.value.position + 10_000) },
                        )
                    },
            ) {
                Box(Modifier.matchParentSize().graphicsLayer {
                    rotationZ = rotation.angle
                    clip = true
                    shape = CircleShape
                }) {
                    PlayerArtworkTransition(artwork) { scene, modifier ->
                        val layer = Modifier.matchParentSize().then(modifier)
                        val image = scene.hero
                        if (image != null) {
                            Image(image, null, layer.background(Color(0xFF17151A)), contentScale = ContentScale.Crop)
                        } else {
                            Artwork(null, title, layer, radius = 0)
                        }
                    }
                    Box(Modifier.matchParentSize().drawWithCache {
                        val radius = size.minDimension / 2f
                        val grooves = (1..34).map { radius * (.20f + it * .022f) }
                        onDrawBehind {
                            grooves.forEachIndexed { i, ring ->
                                val ink = if (i % 3 == 0) Color.White.copy(alpha = .13f) else Color.Black.copy(alpha = .17f)
                                drawCircle(ink, ring, style = Stroke(.6.dp.toPx()))
                            }
                            drawCircle(Color(0xFF141317), radius - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
                        }
                    })
                }
            }
            Tonearm(Modifier.matchParentSize(), live, preview)
            if (canSeek) {
                Box(Modifier.align(Alignment.TopEnd).size(48.dp).semantics {
                    contentDescription = "トーンアーム"
                    val state = live.value
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction(preview.value ?: state.position, state.duration), 0f..1f)
                    setProgress { currentSeek((it.coerceIn(0f, 1f).toDouble() * state.duration).roundToLong()) }
                })
            }
        }
    }
}

private class VinylGeometry(side: Float) {
    val center = Offset(side * .45f, side * .51f)
    val radius = side * .40f
    val pivot = Offset(side * .86f, side * .15f)
    private val armLength = side * .50f

    fun discAngle(pointer: Offset) = pointerAngle(pointer.x - center.x, pointer.y - center.y)
    fun armAngle(pointer: Offset) = pointerAngle(pointer.x - pivot.x, pointer.y - pivot.y)

    fun stylus(progress: Float): Offset {
        val radians = Math.toRadians((TonearmStart + progress * (TonearmEnd - TonearmStart)).toDouble())
        return pivot + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * armLength
    }

    fun hitsArm(pointer: Offset, progress: Float, tolerance: Float): Boolean {
        val arm = stylus(progress) - pivot
        val relative = pointer - pivot
        val part = ((relative.x * arm.x + relative.y * arm.y) / (armLength * armLength)).coerceIn(0f, 1f)
        return (relative - arm * part).getDistance() <= tolerance
    }
}

@Composable
private fun Platter(modifier: Modifier) {
    Box(modifier.drawWithCache {
        val deck = VinylGeometry(size.width)
        val material = Brush.linearGradient(listOf(Color(0xFF514D54), Color(0xFF0F0E12), Color(0xFF353239)))
        onDrawBehind {
            drawCircle(Color.Black.copy(alpha = .36f), deck.radius + 11.dp.toPx(), deck.center + Offset(0f, 5.dp.toPx()))
            drawCircle(material, deck.radius + 5.dp.toPx(), deck.center)
            drawCircle(Color.White.copy(alpha = .19f), deck.radius + 4.dp.toPx(), deck.center, style = Stroke(1.dp.toPx()))
        }
    })
}

@Composable
private fun Tonearm(modifier: Modifier, live: State<PlaybackUi>, preview: State<Long?>) {
    Box(modifier.drawWithCache {
        val deck = VinylGeometry(size.width)
        val highlight = Brush.linearGradient(
            listOf(Color.White.copy(alpha = .15f), Color.Transparent, Color.Black.copy(alpha = .12f)),
            start = Offset(0f, size.height * .18f), end = Offset(size.width, size.height * .84f),
        )
        onDrawBehind {
            val state = live.value
            val tip = deck.stylus(fraction(preview.value ?: state.position, state.duration))
            drawCircle(highlight, deck.radius - 3.dp.toPx(), deck.center)
            drawCircle(Color(0xFF252228), 6.dp.toPx(), deck.center)
            drawCircle(Color(0xFFD8D2CE), 3.5.dp.toPx(), deck.center)
            drawCircle(Color(0xFF4C464B), 1.5.dp.toPx(), deck.center)
            drawTonearm(deck.pivot, tip)
        }
    })
}

private fun DrawScope.drawTonearm(pivot: Offset, tip: Offset) {
    val shadow = Offset(0f, 3.dp.toPx())
    val body = Color(0xFFAAA5AB)
    drawCircle(Color(0xFF252227), 12.dp.toPx(), pivot)
    drawCircle(Color.White.copy(alpha = .22f), 11.dp.toPx(), pivot, style = Stroke(1.dp.toPx()))
    drawLine(Color.Black.copy(alpha = .44f), pivot + shadow, tip + shadow, 7.dp.toPx())
    drawLine(body, pivot, tip, 4.dp.toPx())
    drawCircle(Color(0xFFD4CFD1), 5.dp.toPx(), pivot)
    drawLine(Color(0xFF28252A), tip - (tip - pivot) * .13f, tip, 9.dp.toPx())
    drawCircle(Color(0xFFE1DAD5), 2.dp.toPx(), tip)
}

private fun fraction(position: Long, duration: Long): Float =
    if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
