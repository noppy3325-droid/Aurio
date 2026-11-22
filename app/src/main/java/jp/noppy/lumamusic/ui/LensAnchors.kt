package jp.noppy.lumamusic.ui

import kotlin.math.roundToInt

/** The visual position is expressed in tab units so resizing the dock keeps it on the same track. */
internal class LensAnchors(private val centers: List<Float>) {
    private val last = centers.lastIndex
    private val direction = if (centers.last() >= centers.first()) 1f else -1f

    fun positionAt(x: Float): Float {
        val projected = x * direction
        if (projected <= centers.first() * direction) return 0f
        for (index in 0 until last) {
            val start = centers[index] * direction
            val end = centers[index + 1] * direction
            if (projected <= end) return index + (projected - start) / (end - start).coerceAtLeast(.001f)
        }
        return last.toFloat()
    }

    fun xAt(position: Float): Float {
        val clamped = position.coerceIn(0f, last.toFloat())
        val first = clamped.toInt()
        val next = (first + 1).coerceAtMost(last)
        return centers[first] + (centers[next] - centers[first]) * (clamped - first)
    }

    fun snapTarget(x: Float, velocityX: Float): Int {
        val position = positionAt(x)
        val projected = positionAt(x + velocityX * .075f)
        // Limit inertia to part of one interval, even after a full-width fling.
        val bias = (projected - position).coerceIn(-.28f, .28f)
        return (position + bias).roundToInt().coerceIn(0, last)
    }

    fun pageVelocity(x: Float, velocityX: Float): Float {
        val sampleSeconds = .01f
        return (positionAt(x + velocityX * sampleSeconds) - positionAt(x)) / sampleSeconds
    }
}
