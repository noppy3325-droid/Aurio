package jp.noppy.lumamusic.ui

import kotlin.math.atan2

internal const val VinylTurnMs = 10_000.0
internal const val TonearmStart = 99f
internal const val TonearmEnd = 132f

internal fun wrapDegrees(angle: Float): Float = (angle % 360f + 360f) % 360f

internal fun angleDelta(previous: Float, next: Float): Float = wrapDegrees(next - previous + 180f) - 180f

internal fun pointerAngle(x: Float, y: Float): Float = Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()

internal fun vinylPosition(position: Double, degrees: Float, duration: Long): Double =
    (position + degrees / 360.0 * VinylTurnMs).coerceIn(0.0, duration.coerceAtLeast(0).toDouble())

internal fun tonearmFraction(angle: Float): Float =
    (angleDelta(TonearmStart, angle) / (TonearmEnd - TonearmStart)).coerceIn(0f, 1f)
