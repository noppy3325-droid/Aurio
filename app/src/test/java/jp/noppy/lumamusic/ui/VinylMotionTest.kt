package jp.noppy.lumamusic.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VinylMotionTest {
    @Test fun aRevolutionTakesOnePointEightSeconds() {
        assertEquals(0f, advanceVinylRotation(0f, 1_800_000_000L), .001f)
        assertEquals(90f, advanceVinylRotation(0f, 450_000_000L), .001f)
    }

    @Test fun stoppedTimeLeavesTheAngleIntact() {
        val beforePause = advanceVinylRotation(340f, 50_000_000L)
        assertEquals(350f, beforePause, .001f)
        assertEquals(350f, advanceVinylRotation(beforePause, 0L), .001f)
        assertEquals(10f, advanceVinylRotation(beforePause, 100_000_000L), .001f)
    }

    @Test fun artworkLoadAndSameIdRepeatKeepTheDiscInPlace() {
        val initial = VinylExchange(VinylRecord("a", "placeholder"))
        val artwork = initial.show(VinylRecord("a", "cover"), animate = true)
        assertEquals("cover", artwork.current.content)
        assertNull(artwork.outgoing)
        assertSame(artwork, artwork.show(artwork.current, animate = true))
    }

    @Test fun rapidTrackChangesKeepTheOutgoingDiscAndLatestIncomingCover() {
        val initial = VinylExchange(VinylRecord("a", "cover A"))
        val swapping = initial.show(VinylRecord("b", "cover B"), animate = true)
        val skipping = swapping.show(VinylRecord("c", "placeholder"), animate = true)
        val loaded = skipping.show(VinylRecord("c", "cover C"), animate = true)
        assertSame(initial.current, loaded.outgoing)
        assertEquals(VinylRecord("c", "cover C"), loaded.current)
        val finished = loaded.finish()
        assertNull(finished.outgoing)
        assertSame(loaded.current, finished.current)
    }

    @Test fun reducedMotionAndEmptyPlaybackSkipTheExchange() {
        val initial = VinylExchange(VinylRecord("a", "cover A"))
        val swapping = initial.show(VinylRecord("b", "cover B"), animate = true)
        val replacement = swapping.show(VinylRecord("c", "cover C"), animate = false)
        assertNull(replacement.outgoing)
        assertEquals("c", replacement.current.mediaId)
        val empty = replacement.show(VinylRecord(null, "placeholder"), animate = true)
        assertNull(empty.outgoing)
        assertNull(empty.show(initial.current, animate = true).outgoing)
    }
}
