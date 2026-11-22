package jp.noppy.lumamusic.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LensDragTest {
    private val anchors = LensAnchors(listOf(50f, 150f, 275f, 400f))

    @Test fun lensMovesAcrossEveryCenterInOneGesture() {
        assertEquals(3, anchors.snapTarget(400f, 0f))
        assertEquals(0, anchors.snapTarget(50f, 0f))
        assertEquals(3, anchors.snapTarget(390f, -25f))
        assertEquals(0, anchors.snapTarget(-70f, -3_000f))
        assertEquals(3, anchors.snapTarget(600f, 3_000f))
    }

    @Test fun intermediatePositionFollowsMeasuredUnequalCenters() {
        assertEquals(.4f, anchors.positionAt(90f), .001f)
        assertEquals(1.5f, anchors.positionAt(212.5f), .001f)
        assertEquals(2.6f, anchors.positionAt(350f), .001f)
        assertEquals(212.5f, anchors.xAt(1.5f), .001f)
    }

    @Test fun slowShortDragAndReversalChooseNearestCenter() {
        assertEquals(1, anchors.snapTarget(167f, 0f))
        assertEquals(1, anchors.snapTarget(180f, -1_250f))
        assertEquals(0, anchors.snapTarget(63f, -1_250f))
    }

    @Test fun fastReleasePredictsOnlyAShortAdditionalDistance() {
        assertEquals(2, anchors.snapTarget(180f, 1_250f))
        assertEquals(1, anchors.snapTarget(158f, 9_000f))
        assertEquals(2, anchors.snapTarget(199f, 9_000f))
        assertEquals(0f, anchors.pageVelocity(50f, -1_000f), .001f)
        assertEquals(8f, anchors.pageVelocity(200f, 1_000f), .001f)
    }

    @Test fun descendingTabCoordinatesKeepPhysicalDirection() {
        val reversed = LensAnchors(listOf(400f, 275f, 150f, 50f))
        assertEquals(1.5f, reversed.positionAt(212.5f), .001f)
        assertEquals(3, reversed.snapTarget(50f, -1_000f))
        assertEquals(0, reversed.snapTarget(400f, 1_000f))
        assertEquals(-8f, reversed.pageVelocity(200f, 1_000f), .001f)
    }
}
