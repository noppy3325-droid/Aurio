package jp.noppy.lumamusic.ui

import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.data.TrackPlaybackStats
import org.junit.Assert.*
import org.junit.Test

class HomeRecommendationsTest {
    private val now = 200 * 86_400_000L
    private fun track(id: String, artist: String = id) = Track(id, id, artist)
    private fun stats(id: String, plays: Long, daysAgo: Long) =
        TrackPlaybackStats(id, plays, plays / 2, now - daysAgo * 86_400_000L, now / 2, plays * 90_000)

    @Test fun ranksLifetimeFavoritesAndRediscoveriesSeparately() {
        val tracks = listOf(track("A"), track("B"), track("C"), track("D"), track("E"))
        val stats = listOf(stats("A", 60, 20), stats("B", 45, 1), stats("C", 2, 0), stats("D", 28, 90))
        val feed = recommendHome(tracks, stats, emptyMap(), emptySet(), emptySet(), now) { it }

        assertEquals("A", feed.frequent.first())
        assertEquals(setOf("A", "D"), feed.rediscover.toSet())
        assertFalse("C" in feed.featured)
        assertEquals(feed.featured.size, feed.featured.distinct().size)
        assertTrue(feed.featured.containsAll(listOf("A", "B", "D")))
    }

    @Test fun avoidsArtistRunsWhenAlternativesExist() {
        val tracks = (1..5).map { track("a$it", "Same") } + listOf(track("b"), track("c"), track("d"))
        val stats = tracks.mapIndexed { i, track -> stats(track.uri, 100L - i * 5, 30) }
        val feed = recommendHome(tracks, stats, emptyMap(), emptySet(), emptySet(), now) { it }

        assertTrue(feed.featured.take(4).count { it.startsWith('a') } <= 2)
        assertTrue(feed.rediscover.take(4).count { it.startsWith('a') } <= 2)
    }

    @Test fun legacyHistoryAndFutureTimesKeepTracksOutOfRediscovery() {
        val feed = recommendHome(
            listOf(track("old"), track("future")),
            listOf(stats("old", 50, 90), stats("future", 500, -10)),
            mapOf("old" to now - 1000), emptySet(), emptySet(), now
        ) { it }

        assertTrue(feed.rediscover.isEmpty())
        assertEquals(setOf("old", "future"), feed.frequent.toSet())
    }

    @Test fun dormantTrackBecomesEligibleAtFourteenDays() {
        val track = track("A")
        val stat = stats("A", 5, 13)
        assertTrue(recommendHome(listOf(track), listOf(stat), emptyMap(), emptySet(), emptySet(), now) { it }.rediscover.isEmpty())
        assertEquals(
            listOf("A"),
            recommendHome(listOf(track), listOf(stat), emptyMap(), emptySet(), emptySet(), now + 86_400_000) { it }.rediscover
        )
    }

    @Test fun usesSavedMusicWhenNoStatsAndExcludesUnavailableTracks() {
        val tracks = listOf(track("library"), track("playlist"), track("favorite"))
        val feed = recommendHome(
            tracks, listOf(stats("removed", 500, 90)), emptyMap(),
            setOf("favorite", "removed"), setOf("playlist"), now
        ) { it }
        assertTrue(feed.frequent.isEmpty())
        assertTrue(feed.rediscover.isEmpty())
        assertEquals(listOf("favorite", "playlist", "library"), feed.featured)
        assertFalse(feed.personalized)
    }
}
