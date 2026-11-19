package jp.noppy.lumamusic.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkAmbientPaletteTest {
    @Test fun `edge colors follow their own corners on split artwork`() {
        val pixels = IntArray(64) { index ->
            val column = index % 8
            when (index / 8) {
                0, 1 -> if (column < 4) 0xFFFF0000.toInt() else 0xFF00FF00.toInt()
                6, 7 -> if (column < 4) 0xFF0000FF.toInt() else 0xFFFFFF00.toInt()
                else -> 0xFF111111.toInt()
            }
        }
        val palette = ArtworkAmbientPalette.fromPixels(pixels, 8, 8, Color(0xFF111111))

        assertEquals(Color.Red, palette.topLeft)
        assertEquals(Color.Green, palette.topRight)
        assertEquals(Color.Blue, palette.bottomLeft)
        assertEquals(Color.Yellow, palette.bottomRight)
    }

    @Test fun `transparent edges use the visible artwork color`() {
        val rose = Color(0xFFAB5678)
        val palette = ArtworkAmbientPalette.fromPixels(IntArray(16), 4, 4, rose)

        assertEquals(rose, palette.topLeft)
        assertEquals(rose, palette.bottomRight)
    }

    @Test fun `neutral artwork keeps separate highlights and shadows`() {
        val highlight = playerAmbientTone(Color.White, Color.LightGray, .39f)
        val shadow = playerAmbientTone(Color.Black, Color.Gray, .39f)

        assertTrue(highlight.red > highlight.blue)
        assertTrue(shadow.blue > shadow.red)
        assertTrue(highlight.luminance() > shadow.luminance())
    }

    @Test fun `gray edges can pick up color from elsewhere in the artwork`() {
        val blue = playerAmbientTone(Color.LightGray, Color(0xFF4254C9), .39f)

        assertTrue(blue.blue > blue.red + .15f)
    }

    @Test fun `bright colors keep their hue and contrast under the player controls`() {
        val blue = playerAmbientTone(Color(0xFF5A81C4), Color.Red, .39f)
        val coral = playerAmbientTone(Color(0xFFF19779), Color.Blue, .31f)
        val brightBackground = playerAmbientTone(Color.Yellow, Color.Yellow, .39f)
        val panelBackground = Color.White.copy(alpha = .12f).compositeOver(brightBackground)
        val contrast = (Color.White.luminance() + .05f) / (panelBackground.luminance() + .05f)

        assertTrue(blue.blue > blue.green && blue.green > blue.red)
        assertTrue(coral.red > coral.green && coral.green > coral.blue)
        assertTrue(contrast >= 4.5f)
    }
}
