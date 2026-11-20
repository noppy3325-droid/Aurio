package jp.noppy.lumamusic.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import jp.noppy.lumamusic.core.SmartShuffleCheckpoint
import jp.noppy.lumamusic.core.SmartShuffleCycle
import kotlin.random.Random

/** Keeps cycle policy in the service and uses the native timeline for playback and controller navigation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackShuffle(private val player: ExoPlayer) {
    private val cycle = SmartShuffleCycle()
    private var timeline: Timeline = Timeline.EMPTY
    private var windows = emptyList<Any>()
    private var mediaIds = emptyList<String>()
    private var advancing = false
    private var enabled = false

    private fun updateTimeline(): Boolean {
        val next = player.currentTimeline
        if (timeline === next) return false
        timeline = next
        val window = Timeline.Window()
        val nextWindows = List(next.windowCount) { index -> next.getWindow(index, window).uid }
        if (windows == nextWindows) return false
        val indices = nextWindows.withIndex().associate { (index, uid) -> uid to index }
        val retained = buildMap {
            windows.forEachIndexed { old, uid -> indices[uid]?.let { put(old, it) } }
        }
        cycle.remap(retained)
        if (retained.isEmpty()) {
            cycle.clearRound()
            advancing = false
        }
        windows = nextWindows
        mediaIds = List(next.windowCount) { index -> player.getMediaItemAt(index).mediaId }
        return true
    }

    fun update(nowMillis: Long = System.currentTimeMillis()) {
        val queueChanged = updateTimeline()
        val index = player.currentMediaItemIndex
        if (!player.shuffleModeEnabled) {
            if (enabled) cycle.clearRound()
            enabled = false
            advancing = false
            cycle.consumePlayNext(index)
            return
        }
        enabled = true
        if (index !in windows.indices) return
        // A brief buffer underrun does not end an ongoing listening session.
        advancing = player.isPlaying || (advancing && player.playWhenReady &&
            player.playbackState == Player.STATE_BUFFERING &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        val order = timeline.shuffledOrder()
        if (!order.isWindowPermutation(windows.size)) return
        val updated = cycle.update(order, mediaIds, index, advancing, nowMillis, queueChanged)
        if (updated != order) player.setShuffleOrder(DefaultShuffleOrder(updated.toIntArray(), Random.nextLong()))
    }

    fun restore(checkpoint: SmartShuffleCheckpoint, originalIndex: Int, remappedIndices: Map<Int, Int>, reconcileOrder: Boolean) {
        updateTimeline()
        cycle.restore(checkpoint, originalIndex, reconcileOrder)
        cycle.remap(remappedIndices)
        enabled = player.shuffleModeEnabled
    }

    fun rememberPlayNext(index: Int) {
        updateTimeline()
        cycle.playNext(index)
        update()
    }

    fun checkpoint(nowMillis: Long): SmartShuffleCheckpoint = cycle.checkpoint(nowMillis)

    fun beforePlay() {
        if (player.shuffleModeEnabled && player.playbackState == Player.STATE_ENDED) {
            val next = player.currentTimeline.getFirstWindowIndex(true)
            if (cycle.roundReady && next in 0 until player.mediaItemCount && next != player.currentMediaItemIndex) {
                player.seekToDefaultPosition(next)
            } else {
                player.seekToDefaultPosition()
            }
        }
    }
}
