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
        val highlight = playerAmbientTone(Color.White, Color.LightGray)
        val shadow = playerAmbientTone(Color.Black, Color.Gray)

        assertTrue(highlight.red > highlight.blue)
        assertTrue(shadow.blue > shadow.red)
        assertTrue(highlight.luminance() > shadow.luminance())
    }

    @Test fun `gray edges can pick up color from elsewhere in the artwork`() {
        val blue = playerAmbientTone(Color.LightGray, Color(0xFF4254C9))

        assertTrue(blue.blue > blue.red + .15f)
    }

    @Test fun `bright colors keep their hue under the player controls`() {
        val blue = playerAmbientTone(Color(0xFF5A81C4), Color.Red)
        val coral = playerAmbientTone(Color(0xFFF19779), Color.Blue)

        assertTrue(blue.blue > blue.green && blue.green > blue.red)
        assertTrue(coral.red > coral.green && coral.green > coral.blue)
    }

    @Test fun `control contrast preserves red and blue pools instead of flattening their channels`() {
        val red = playerAmbientTone(Color(0xFFC9283F), Color.Blue)
        val blue = playerAmbientTone(Color(0xFF263DE0), Color.Red)
        val yellow = playerAmbientTone(Color.Yellow, Color.Red)

        assertTrue(red.red > .65f && red.red - red.green > .45f)
        assertTrue(blue.blue > .70f && blue.blue - blue.red > .45f)
        for (background in listOf(red, blue, yellow)) {
            val label = Color.White.copy(alpha = .96f).compositeOver(background)
            assertTrue((label.luminance() + .05f) / (background.luminance() + .05f) >= 4.5f)
        }
    }

    @Test fun `header keeps dark artwork unshaded and limits highlights for status icons`() {
        val tint = Color(0xFFD34664)
        val dark = Color(0xFF17274D)
        assertEquals(0f, playerHeaderShade(listOf(dark), tint).alpha, .001f)

        val samples = listOf(Color.White, Color(0xFFEAC464), Color(0xFF61A1E8))
        val shade = playerHeaderShade(samples, tint)
        assertTrue(shade.red > shade.green)
        assertTrue(samples.all { shade.compositeOver(it).luminance() <= .175f })
        assertTrue(shade.compositeOver(Color.White).luminance() >= .15f)
    }
}
