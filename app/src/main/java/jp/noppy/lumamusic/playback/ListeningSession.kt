package jp.noppy.lumamusic.playback

import jp.noppy.lumamusic.data.PlaybackUpdate

/** One timeline item (or repeat iteration), with a monotonic clock for durations. */
internal class ListeningSession(val trackUri: String, positionMs: Long, var durationMs: Long) {
    private var position = positionMs
    private var elapsed: Long? = null
    private var wallTime = 0L
    private var audible = false
    private var speed = 1f
    private var firstPlayedAt: Long? = null
    private var lastPlayedAt = 0L
    private var totalAudible = 0L
    private var audibleContent = 0L
    private var persistedAudible = 0L
    private var qualified = false
    private var forwardSeek = false

    fun sample(
        elapsedMs: Long,
        positionMs: Long,
        wallMs: Long,
        isAudible: Boolean,
        playbackSpeed: Float,
        isOutputEnabled: Boolean = true,
    ): PlaybackUpdate? {
        val previousElapsed = elapsed
        // A system volume change can occur between ticks. Discard the ambiguous preceding interval
        // on first observing a mute rather than crediting time that may have been silent.
        if (audible && previousElapsed != null && isOutputEnabled) {
            // Use both clocks. Stalls consume no media time and a position jump cannot create elapsed time.
            val advanced = (positionMs - position).coerceIn(0, ((elapsedMs - previousElapsed).coerceAtLeast(0) * speed).toLong())
            val heard = (advanced / speed).toLong()
            if (heard > 0) {
                if (firstPlayedAt == null) firstPlayedAt = wallTime
                lastPlayedAt = wallMs
                totalAudible += heard
                audibleContent += advanced
            }
        }
        elapsed = elapsedMs
        position = positionMs
        wallTime = wallMs
        audible = isAudible && isOutputEnabled
        speed = playbackSpeed
        return checkpoint(force = false, completed = false)
    }

    fun seek(positionMs: Long, previousPositionMs: Long) {
        if (positionMs > previousPositionMs + 1_000) forwardSeek = true
        position = positionMs
    }

    fun finish(wallMs: Long, naturalEnd: Boolean): PlaybackUpdate? {
        if (audible) lastPlayedAt = wallMs
        val completed = naturalEnd && !forwardSeek && durationMs > 0 && audibleContent >= durationMs * 0.85
        return checkpoint(force = true, completed = completed)
    }

    private fun checkpoint(force: Boolean, completed: Boolean): PlaybackUpdate? {
        val began = firstPlayedAt ?: return null
        val threshold = if (durationMs > 0) minOf(45_000L, (durationMs / 2).coerceAtLeast(1)) else 45_000L
        val newPlay = !qualified && totalAudible >= threshold
        val delta = totalAudible - persistedAudible
        if (!newPlay && (!qualified || !completed && delta < if (force) 1L else 15_000L)) return null
        qualified = true
        persistedAudible = totalAudible
        return PlaybackUpdate(trackUri, delta, newPlay, completed, lastPlayedAt, began)
    }
}
