package jp.noppy.lumamusic.ui

import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.data.TrackPlaybackStats

internal data class ListeningTrack(val track: Track, val stats: TrackPlaybackStats)

internal data class ListeningOverview(
    val plays: Long = 0,
    val audibleMs: Long = 0,
    val trackCount: Int = 0,
    val mostPlayed: List<ListeningTrack> = emptyList(),
    val longestListened: List<ListeningTrack> = emptyList(),
)

internal fun listeningOverview(
    tracks: List<Track>,
    stats: List<TrackPlaybackStats>,
    trackId: (String) -> String,
): ListeningOverview {
    if (stats.isEmpty()) return ListeningOverview()
    val library = buildMap { tracks.forEach { putIfAbsent(trackId(it.uri), it) } }
    val ranked = stats.mapNotNull { stat -> library[stat.stableTrackId]?.let { ListeningTrack(it, stat) } }
    return ListeningOverview(
        plays = stats.sumOf(TrackPlaybackStats::validPlayCount),
        audibleMs = stats.sumOf(TrackPlaybackStats::totalAudibleMs),
        trackCount = stats.size,
        mostPlayed = ranked.sortedWith(compareByDescending<ListeningTrack> { it.stats.validPlayCount }
            .thenByDescending { it.stats.totalAudibleMs }.thenBy { it.track.title }.thenBy { it.track.uri }),
        longestListened = ranked.sortedWith(compareByDescending<ListeningTrack> { it.stats.totalAudibleMs }
            .thenByDescending { it.stats.validPlayCount }.thenBy { it.track.title }.thenBy { it.track.uri }),
    )
}
