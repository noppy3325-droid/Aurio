package jp.noppy.lumamusic.playback

import org.junit.Assert.*
import org.junit.Test

class ListeningSessionTest {
    @Test fun pauseAndSeekDoNotCountAsHeardAndResumeDoesNotCountTwice() {
        val session = ListeningSession("song", 0, 90_000)
        session.sample(0, 0, 100_000, true, 1f)
        assertNull(session.sample(10_000, 10_000, 110_000, false, 1f))
        session.seek(70_000, 10_000)
        assertNull(session.sample(100_000, 70_000, 200_000, true, 1f))
        assertNull(session.sample(120_000, 90_000, 220_000, false, 1f))
        session.seek(0, 90_000)
        session.sample(121_000, 0, 221_000, true, 1f)
        val valid = session.sample(136_000, 15_000, 236_000, true, 1f)!!
        assertTrue(valid.validPlay)
        assertEquals(45_000L, valid.audibleMs)
        assertEquals(100_000L, valid.firstPlayedAt)
        assertFalse(session.finish(236_000, true)?.completed ?: false)
    }

    @Test fun shortTracksQualifyOnceAndNaturalEndRecordsTheirCompletion() {
        repeat(2) {
            val session = ListeningSession("song", 0, 8_000)
            session.sample(0, 0, 20_000, true, 1f)
            val counted = session.sample(4_000, 4_000, 24_000, true, 1f)!!
            assertTrue(counted.validPlay)
            assertFalse(counted.completed)
            session.sample(8_000, 8_000, 28_000, false, 1f)
            val ended = session.finish(28_000, naturalEnd = true)!!
            assertTrue(ended.completed)
            assertFalse(ended.validPlay)
            assertEquals(4_000L, ended.audibleMs)
        }
    }

    @Test fun partialPlaybackIsNeverInventedFromPositionOrBuffering() {
        val session = ListeningSession("song", 80_000, 84_000)
        session.sample(0, 80_000, 1_000, true, 1f)
        session.sample(10_000, 80_000, 11_000, true, 1f)
        session.sample(14_000, 84_000, 15_000, false, 1f)
        assertNull(session.finish(15_000, naturalEnd = true))
    }

    @Test fun mutedOutputNeverQualifiesDespiteAdvancingMediaClock() {
        val session = ListeningSession("song", 0, 12_000)
        session.sample(0, 0, 10_000, true, 1f)
        assertNull(session.sample(5_000, 5_000, 15_000, true, 1f, isOutputEnabled = false))
        assertNull(session.sample(11_000, 11_000, 21_000, true, 1f, isOutputEnabled = false))
        session.sample(11_000, 11_000, 21_000, true, 1f)
        assertNull(session.sample(12_000, 12_000, 22_000, false, 1f))
        assertNull(session.finish(22_000, naturalEnd = true))
    }
}
