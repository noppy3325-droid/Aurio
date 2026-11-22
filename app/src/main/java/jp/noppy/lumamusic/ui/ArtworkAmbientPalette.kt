package jp.noppy.lumamusic.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.pow

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

/** Keep each patch's hue while limiting its brightness for white text. */
internal fun playerAmbientTone(sample: Color, accent: Color): Color {
    val maxLuminance = .16f
    val peak = .30f + .58f * sample.peak.pow(.80f)

    fun grade(source: Color, minSaturation: Float): Color {
        val chroma = source.chroma
        if (chroma == 0f) return Color(peak, peak, peak)
        val saturation = (chroma / source.peak).coerceIn(minSaturation, .90f)
        val floor = source.floor
        fun channel(value: Float) = peak * (1f - saturation * (source.peak - value) / (source.peak - floor))
        return Color(channel(source.red), channel(source.green), channel(source.blue))
    }

    // Give neutral shadows and highlights their own tones, even on grayscale art.
    val softInk = Color(0xFF536883)
    val softPaper = Color(0xFFAA8268)
    val neutral = lerp(grade(softInk, .38f), grade(softPaper, .38f), sample.peak)
    val fromAccent = ((accent.chroma - .075f) / .10f).coerceIn(0f, 1f) * .80f
    val fromSample = ((sample.chroma - .025f) / .09f).coerceIn(0f, 1f)
    val surrounding = lerp(neutral, grade(accent, .54f), fromAccent)
    val tone = lerp(surrounding, grade(sample, .52f), fromSample)
    if (tone.luminance() <= maxLuminance) return tone

    // Scale all three channels together. Yellow and white need more correction
    // than red or blue, even when they start at the same channel level.
    fun darken(factor: Float) = Color(tone.red * factor, tone.green * factor, tone.blue * factor)
    var darker = 0f
    var lighter = 1f
    repeat(8) {
        val middle = (darker + lighter) / 2f
        if (darken(middle).luminance() <= maxLuminance) darker = middle else lighter = middle
    }
    return darken(darker)
}

/** Find the least tint needed to keep the white status icons legible. */
internal fun playerHeaderShade(samples: List<Color>, tint: Color): Color {
    val lightLimit = .17f
    if (samples.none { it.luminance() > lightLimit }) return Color.Transparent

    val shade = Color(tint.red * .38f, tint.green * .38f, tint.blue * .38f)
    var lower = 0f
    var upper = 1f
    repeat(9) {
        val alpha = (lower + upper) / 2f
        val candidate = shade.copy(alpha = alpha)
        if (samples.any { candidate.compositeOver(it).luminance() > lightLimit }) lower = alpha
        else upper = alpha
    }
    return shade.copy(alpha = upper)
}
