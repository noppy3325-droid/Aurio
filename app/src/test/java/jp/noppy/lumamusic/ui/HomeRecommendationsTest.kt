package jp.noppy.lumamusic.ui

import jp.noppy.lumamusic.data.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRecommendationsTest {
    @Test fun prioritizesArtistsFromRecentListeningWithoutRepeatingTracks() {
        val tracks = listOf(Track("a", "A", "One"), Track("b", "B", "Two"), Track("c", "C", "One"))
        assertEquals(listOf("c", "a", "b"), recommendTracks(tracks, listOf("a", "a")).map { it.uri })
    }

    @Test fun missingHistoryFallsBackToLibraryOrder() {
        val tracks = listOf(Track("a", "A"), Track("b", "B"))
        assertEquals(tracks, recommendTracks(tracks, listOf("removed")))
    }
}
