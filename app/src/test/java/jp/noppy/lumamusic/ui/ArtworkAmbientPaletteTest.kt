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

    @Test fun `very bright artwork is dimmed for white text while dark art needs no scrim`() {
        val bright = Color(0xFFFFEAE1)
        val warm = Color(0xFFD03B22)
        val scrim = nowPlayingScrim(bright, warm, .12f)
        val composite = scrim.compositeOver(bright)

        assertTrue((Color.White.luminance() + .05f) / (composite.luminance() + .05f) >= 4.5f)
        assertTrue(composite.red > composite.blue)
        assertEquals(0f, nowPlayingScrim(Color(0xFF082044), Color.Blue, .12f).alpha, 0f)
    }
}
