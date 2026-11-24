package jp.noppy.lumamusic.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VinylSeekTest {
    @Test fun rotationCrossesTheAngleBoundaryInBothDirections() {
        assertEquals(20f, angleDelta(170f, -170f), .001f)
        assertEquals(-20f, angleDelta(-170f, 170f), .001f)
        assertEquals(270f, wrapDegrees(-810f), .001f)
    }

    @Test fun rotationSeeksInEitherDirectionAndStopsAtTheEnds() {
        assertEquals(15_000.0, vinylPosition(5_000.0, 360f, 30_000), .01)
        assertEquals(2_500.0, vinylPosition(5_000.0, -90f, 30_000), .01)
        assertEquals(0.0, vinylPosition(1_000.0, -90f, 30_000), .01)
        assertEquals(30_000.0, vinylPosition(29_500.0, 90f, 30_000), .01)
        assertEquals(0.0, vinylPosition(3_000.0, 60f, 0), .01)
    }

    @Test fun tonearmMapsTheSweepToTheTrackDuration() {
        assertEquals(0f, tonearmFraction(90f), .001f)
        assertEquals(.5f, tonearmFraction((TonearmStart + TonearmEnd) / 2f), .001f)
        assertEquals(1f, tonearmFraction(145f), .001f)
    }
}
