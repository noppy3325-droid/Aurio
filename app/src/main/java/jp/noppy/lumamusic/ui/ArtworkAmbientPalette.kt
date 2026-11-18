package jp.noppy.lumamusic.ui

import androidx.compose.ui.graphics.Color

internal data class ArtworkAmbientPalette(
    val topLeft: Color,
    val topRight: Color,
    val bottomLeft: Color,
    val bottomRight: Color,
    val dominant: Color,
) {
    val accent: Color get() = listOf(dominant, topLeft, topRight, bottomLeft, bottomRight)
        .maxBy { it.chroma }

    companion object {
        val Default = ArtworkAmbientPalette(
            topLeft = Color(0xFF322338),
            topRight = Color(0xFF392634),
            bottomLeft = Color(0xFF2C2135),
            bottomRight = Color(0xFF3B2632),
            dominant = Color(0xFF40304A),
        )

        fun fromPixels(pixels: IntArray, width: Int, height: Int, dominant: Color): ArtworkAmbientPalette {
            val quarter = (height / 4).coerceAtLeast(1)
            val middle = (width / 2).coerceAtLeast(1)
            val rightStart = middle.coerceAtMost(width - 1)
            return ArtworkAmbientPalette(
                topLeft = regionColor(pixels, width, 0, 0, middle, quarter, dominant),
                topRight = regionColor(pixels, width, rightStart, 0, width, quarter, dominant),
                bottomLeft = regionColor(pixels, width, 0, height - quarter, middle, height, dominant),
                bottomRight = regionColor(pixels, width, rightStart, height - quarter, width, height, dominant),
                dominant = dominant,
            )
        }
    }
}

/** Average in sRGB, ignoring RGB values in transparent texels. */
internal fun regionColor(
    pixels: IntArray, stride: Int, left: Int, top: Int, right: Int, bottom: Int, fallback: Color,
): Color {
    var red = 0L
    var green = 0L
    var blue = 0L
    var weight = 0L
    for (y in top until bottom) {
        for (x in left until right) {
            val argb = pixels[y * stride + x]
            val alpha = argb ushr 24
            red += ((argb ushr 16) and 255) * alpha
            green += ((argb ushr 8) and 255) * alpha
            blue += (argb and 255) * alpha
            weight += alpha
        }
    }
    if (weight == 0L) return fallback
    val scale = 255f * weight
    return Color(red / scale, green / scale, blue / scale)
}

private val Color.peak: Float get() = maxOf(red, green, blue)
private val Color.floor: Float get() = minOf(red, green, blue)
private val Color.chroma: Float get() = peak - floor

/**
 * Grade the canvas colors before drawing them. Every channel stays within the
 * ceiling for the control region, so white controls don't need a second scrim.
 */
internal fun playerAmbientTone(sample: Color, accent: Color, ceiling: Float): Color {
    val source = when {
        sample.chroma >= .065f -> sample
        accent.chroma >= .065f -> accent
        else -> ArtworkAmbientPalette.Default.dominant
    }
    val floor = source.floor
    val chroma = source.chroma
    val saturation = (chroma / source.peak).coerceIn(.58f, .86f)
    val darkestChannel = ceiling * (1f - saturation)
    fun channel(value: Float) = darkestChannel + (value - floor) / chroma * (ceiling - darkestChannel)
    return Color(channel(source.red), channel(source.green), channel(source.blue))
}
