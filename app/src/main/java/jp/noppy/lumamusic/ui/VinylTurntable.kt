package jp.noppy.lumamusic.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.isActive

private const val ExchangeMillis = 525
private const val CueMillis = 125
private const val ParkedAngle = 83f
private const val PlayingAngle = 106f

private class VinylRotation {
    var angle by mutableFloatStateOf(0f)
}

private data class VinylPrint(val hero: Painter?, val title: String, val rotation: VinylRotation)

@Composable
internal fun VinylTurntable(
    artwork: PlayerArtworkScene?,
    title: String,
    playback: PlaybackUi,
    resumed: Boolean,
    toggle: () -> Unit,
) {
    val context = LocalContext.current
    val systemReduced = remember(context, resumed) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val reduced = LocalReducedMotion.current || systemReduced
    val mediaId = playback.mediaId
    val rotation = remember(mediaId) { VinylRotation() }
    val target = VinylRecord(mediaId, VinylPrint(artwork?.hero, title, rotation))
    var displayed by remember { mutableStateOf(VinylExchange(target)) }
    val previous = displayed
    val exchange = previous.show(target, animate = resumed && !reduced)
    SideEffect {
        if (displayed === previous && exchange !== previous) displayed = exchange
    }

    val departure = exchange.outgoing
    val motion = remember(departure) { Animatable(0f) }
    LaunchedEffect(departure) {
        if (departure != null) {
            motion.animateTo(1f, tween(ExchangeMillis, easing = LinearEasing))
            if (displayed.outgoing === departure) displayed = displayed.finish()
        }
    }

    val playingOnDeck = resumed && mediaId != null && playback.playing && departure == null
    val stylus = animateFloatAsState(
        targetValue = if (playingOnDeck) PlayingAngle else ParkedAngle,
        animationSpec = if (reduced || !resumed) snap() else tween(CueMillis),
        label = "tonearm cue",
    )
    val spinning = playingOnDeck && !reduced
    LaunchedEffect(rotation, spinning) {
        if (!spinning) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { frame ->
                rotation.angle = advanceVinylRotation(rotation.angle, frame - last)
                last = frame
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(Modifier.size(side).clipToBounds()) {
            Platter(Modifier.matchParentSize())
            val discBounds = Modifier.offset(x = side * .05f, y = side * .11f).size(side * .80f)
            if (departure != null) {
                VinylDisc(
                    departure.content,
                    discBounds.graphicsLayer {
                        val phase = motion.value
                        val exit = easedPhase(phase, .24f, .71f)
                        val lift = easedPhase(phase, .16f, .36f)
                        translationX = -size.width * 1.12f * exit
                        translationY = -size.height * .06f * lift
                        scaleX = 1f + .045f * lift
                        scaleY = scaleX
                        alpha = 1f - easedPhase(phase, .52f, .71f)
                        shadowElevation = 14.dp.toPx() * lift
                        shape = CircleShape
                    },
                )
            }
            val arrival = if (departure == null) Modifier else Modifier.graphicsLayer {
                val phase = motion.value
                val enter = easedPhase(phase, .40f, 1f)
                val lift = 1f - easedPhase(phase, .70f, 1f)
                translationX = size.width * 1.12f * (1f - enter)
                translationY = -size.height * .06f * lift
                scaleX = 1f + .045f * lift
                scaleY = scaleX
                alpha = easedPhase(phase, .40f, .65f)
                shadowElevation = 14.dp.toPx() * lift
                shape = CircleShape
            }
            val control = if (mediaId != null && resumed) {
                Modifier.semantics { contentDescription = "レコード盤" }.cancelDiscTapOnDrag().clickable(
                    onClickLabel = if (playback.playing) "一時停止" else "再生",
                    role = Role.Button,
                    onClick = toggle,
                )
            } else Modifier
            VinylDisc(exchange.current.content, discBounds.then(arrival).clip(CircleShape).then(control))
            Tonearm(Modifier.matchParentSize()) { stylus.value }
        }
    }
}

/** Cancel a tap once the pointer travels past tap slop, even if it remains on the record. */
private fun Modifier.cancelDiscTapOnDrag(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > slop ||
                event.changes.any { it.id != down.id && it.pressed }
            ) {
                change.consume()
                break
            }
            if (!change.pressed) break
        }
    }
}

private fun easedPhase(progress: Float, start: Float, end: Float): Float =
    FastOutSlowInEasing.transform(((progress - start) / (end - start)).coerceIn(0f, 1f))

@Composable
private fun VinylDisc(print: VinylPrint, modifier: Modifier) {
    Box(modifier) {
        Box(Modifier.matchParentSize().graphicsLayer {
            rotationZ = print.rotation.angle
            clip = true
            shape = CircleShape
        }) {
            val image = print.hero
            val surface = Modifier.matchParentSize().background(Color(0xFF17151A))
            if (image != null) {
                Image(image, null, surface, contentScale = ContentScale.Crop)
            } else {
                Artwork(null, print.title, surface, radius = 0)
            }
            Box(Modifier.matchParentSize().drawWithCache {
                val radius = size.minDimension / 2f
                val grooves = (1..34).map { radius * (.20f + it * .022f) }
                val light = Color.White.copy(alpha = .11f)
                val dark = Color.Black.copy(alpha = .16f)
                val stroke = Stroke(.6.dp.toPx())
                onDrawBehind {
                    grooves.forEachIndexed { i, ring ->
                        drawCircle(if (i % 3 == 0) light else dark, ring, style = stroke)
                    }
                    drawCircle(Color(0xFF141317), radius * .983f, style = Stroke(radius * .035f))
                }
            })
        }
        Box(Modifier.matchParentSize().drawWithCache {
            val radius = size.minDimension / 2f
            val highlight = Brush.linearGradient(
                listOf(Color.White.copy(alpha = .15f), Color.Transparent, Color.Black.copy(alpha = .12f)),
                start = Offset(0f, size.height * .18f), end = Offset(size.width, size.height * .84f),
            )
            onDrawBehind {
                drawCircle(highlight, radius * .97f)
                drawCircle(Color(0xFF252228), radius * .046f)
                drawCircle(Color(0xFFD8D2CE), radius * .027f)
                drawCircle(Color(0xFF4C464B), radius * .012f)
            }
        })
    }
}

@Composable
private fun Platter(modifier: Modifier) {
    Box(modifier.drawWithCache {
        val center = Offset(size.width * .45f, size.width * .51f)
        val radius = size.width * .40f
        val material = Brush.linearGradient(listOf(Color(0xFF514D54), Color(0xFF0F0E12), Color(0xFF353239)))
        onDrawBehind {
            drawCircle(Color.Black.copy(alpha = .36f), radius + 11.dp.toPx(), center + Offset(0f, 5.dp.toPx()))
            drawCircle(material, radius + 5.dp.toPx(), center)
            drawCircle(Color.White.copy(alpha = .19f), radius + 4.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            drawCircle(Color(0xFFD8D2CE), radius * .024f, center)
        }
    })
}

@Composable
private fun Tonearm(modifier: Modifier, cueAngle: () -> Float) {
    Box(modifier.drawWithCache {
        val pivot = Offset(size.width * .86f, size.width * .15f)
        val armLength = size.width * .50f
        onDrawBehind {
            val radians = Math.toRadians(cueAngle().toDouble())
            val tip = pivot + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * armLength
            drawTonearm(pivot, tip)
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
