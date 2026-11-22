package jp.noppy.lumamusic.ui

import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.data.TrackPlaybackStats
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningOverviewTest {
    @Test fun `totals retain removed tracks and rankings use current library entries once`() {
        val tracks = listOf(Track("first", "First"), Track("first-copy", "Duplicate"), Track("second", "Second"))
        val stats = listOf(
            TrackPlaybackStats("first", 8, 0, 0, 0, 140_000),
            TrackPlaybackStats("second", 3, 0, 0, 0, 420_000),
            TrackPlaybackStats("removed", 2, 0, 0, 0, 90_000),
        )
        val result = listeningOverview(tracks, stats) { it.substringBefore('-') }

        assertEquals(13L, result.plays)
        assertEquals(650_000L, result.audibleMs)
        assertEquals(3, result.trackCount)
        assertEquals(listOf("First", "Second"), result.mostPlayed.map { it.track.title })
        assertEquals(listOf("Second", "First"), result.longestListened.map { it.track.title })
    }
}
