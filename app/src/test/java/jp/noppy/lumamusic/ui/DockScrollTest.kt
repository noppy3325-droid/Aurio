package jp.noppy.lumamusic.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.*
import org.junit.Test

class DockScrollTest {
    @Test fun ignoresSideEffectsAndHorizontalGestures() {
        val dock = DockScroll(30f, 18f)
        dock.onPostScroll(Offset(0f, -80f), Offset.Zero, NestedScrollSource.SideEffect)
        dock.onPostScroll(Offset(175f, -80f), Offset.Zero, NestedScrollSource.UserInput)
        assertFalse(dock.compact)
    }

    @Test fun collapseAndExpandRequireTravelInTheSameDirection() {
        val dock = DockScroll(30f, 18f)
        fun scroll(y: Float) = dock.onPostScroll(Offset(0f, y), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(Offset.Zero, scroll(-22f))
        scroll(2f)
        scroll(-10f)
        assertFalse(dock.compact)
        scroll(-25f)
        assertTrue(dock.compact)
        scroll(12f)
        assertTrue(dock.compact)
        dock.enabled = false
        scroll(18f)
        assertTrue(dock.compact)
        dock.enabled = true
        scroll(7f)
        assertFalse(dock.compact)
    }
}
