package jp.noppy.lumamusic.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LensDragTest {
    private fun target(origin: Int, delta: Float, velocity: Float = 0f) =
        lensDragTarget(origin, 3, delta, 100f, velocity, 620f)

    @Test fun lensMovesInThePhysicalDragDirectionOneTabAtATime() {
        assertEquals(2, target(1, 220f))
        assertEquals(0, target(1, -48f))
        assertEquals(3, target(3, 70f))
        assertEquals(0, target(0, -70f))
    }

    @Test fun shortAndReversedDragsStayAtTheSettledTab() {
        assertEquals(1, target(1, 18f))
        assertEquals(1, target(1, 18f, -1200f))
        assertEquals(2, target(1, 18f, 1200f))
    }
}
