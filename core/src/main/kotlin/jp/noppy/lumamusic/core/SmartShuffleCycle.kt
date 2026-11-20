package jp.noppy.lumamusic.core

import kotlin.random.Random

data class SmartShuffleCheckpoint(
    val visited: List<Int> = emptyList(),
    val inactiveSinceMillis: Long? = null,
    val roundReady: Boolean = false,
    val previousRoundTail: Int? = null,
    val playNext: List<Int> = emptyList(),
)

/** Preserves queue entries while selecting each track once per automatic round. */
class SmartShuffleCycle(private val random: Random = Random.Default) {
    private val visited = linkedSetOf<Int>()
    private val playNext = linkedSetOf<Int>()
    private var currentEntry: Int? = null
    private var previousRoundTail: Int? = null
    private var inactiveSince: Long? = null
    private var advancing = false
    private var restoredOrderNeedsReconcile = false
    var roundReady: Boolean = false
        private set

    fun restore(checkpoint: SmartShuffleCheckpoint, current: Int, reconcileOrder: Boolean = false) {
        clearRound()
        visited.addAll(checkpoint.visited)
        playNext.clear()
        playNext.addAll(checkpoint.playNext)
        roundReady = checkpoint.roundReady
        previousRoundTail = checkpoint.previousRoundTail
        inactiveSince = checkpoint.inactiveSinceMillis
        currentEntry = current
        restoredOrderNeedsReconcile = reconcileOrder
    }

    fun checkpoint(nowMillis: Long): SmartShuffleCheckpoint = SmartShuffleCheckpoint(
        visited = visited.toList(),
        // If the process is killed mid-song, resume from the last playback checkpoint.
        inactiveSinceMillis = if (advancing) nowMillis else inactiveSince,
        roundReady = roundReady,
        previousRoundTail = previousRoundTail,
        playNext = playNext.toList(),
    )

    fun clearRound() {
        visited.clear()
        currentEntry = null
        previousRoundTail = null
        inactiveSince = null
        advancing = false
        restoredOrderNeedsReconcile = false
        roundReady = false
    }

    fun playNext(index: Int) {
        val existing = playNext.filter { it != index }
        playNext.clear()
        playNext.add(index)
        playNext.addAll(existing)
    }

    fun consumePlayNext(index: Int) { playNext.remove(index) }

    fun remap(oldToNew: Map<Int, Int>) {
        val newVisited = visited.mapNotNull(oldToNew::get)
        val newPlayNext = playNext.mapNotNull(oldToNew::get)
        visited.clear()
        visited.addAll(newVisited)
        playNext.clear()
        playNext.addAll(newPlayNext)
        currentEntry = currentEntry?.let(oldToNew::get)
        previousRoundTail = previousRoundTail?.let(oldToNew::get)
        if (currentEntry == null) roundReady = false
    }

    fun update(
        order: List<Int>,
        tracks: List<String>,
        current: Int,
        playbackAdvancing: Boolean,
        nowMillis: Long,
        queueChanged: Boolean = false,
    ): List<Int> {
        if (current !in tracks.indices) return order
        val reconcile = queueChanged || restoredOrderNeedsReconcile
        restoredOrderNeedsReconcile = false
        val timedReset = updateClock(playbackAdvancing, nowMillis)
        val before = currentEntry
        currentEntry = current
        playNext.remove(current)
        if (visited.isEmpty() || timedReset) {
            visited.clear()
            visited.add(current)
            roundReady = false
            previousRoundTail = null
            if (!advancing) inactiveSince = nowMillis
            return placeUpcoming(order, current, upcoming(order.shuffled(random), tracks))
        }

        // Keep the previous round's tail directly before its successor for Previous.
        if (roundReady && before == order.lastOrNull() && current != before && current == order.firstOrNull()) {
            visited.clear()
            visited.add(current)
            roundReady = false
            previousRoundTail = before
            return placeUpcoming(order, current, upcoming(order, tracks))
        }

        // Going back through the queue is an intentional revisit. Leave its predecessors intact.
        if (!visited.add(current) && !reconcile) return order
        val remaining = upcoming(order, tracks)
        if (remaining.isEmpty() && order.size > 1) {
            if (roundReady) return order
            roundReady = true
            previousRoundTail = null
            val candidates = order.filter { it != current }
            val predecessor = order.getOrNull(order.indexOf(current) - 1)
            val differentTracks = candidates.filter { tracks[it] != tracks[current] }.ifEmpty { candidates }
            val newHead = differentTracks.filter { it != predecessor }.ifEmpty { differentTracks }.random(random)
            val previous = predecessor?.takeIf { it != newHead }
            return listOf(newHead) + candidates.filter { it != newHead && it != previous } + listOfNotNull(previous) + current
        }
        roundReady = false
        val nextEntries = if (previousRoundTail != null && current != before && remaining.isNotEmpty()) {
            previousRoundTail = null
            playNext.filter { it in remaining } + remaining.filter { it !in playNext }.shuffled(random)
        } else {
            remaining
        }
        return placeUpcoming(order, current, nextEntries)
    }

    private fun upcoming(order: List<Int>, tracks: List<String>): List<Int> {
        val seenTracks = visited.mapTo(HashSet()) { tracks[it] }
        // Explicit Play Next can deliberately replay a song already heard this round.
        val priority = playNext.filter { it !in visited }.onEach { seenTracks.add(tracks[it]) }
        return priority + order.filter { seenTracks.add(tracks[it]) }
    }

    private fun placeUpcoming(order: List<Int>, current: Int, upcoming: List<Int>): List<Int> {
        val upcomingEntries = upcoming.toHashSet()
        return order.filter { it != current && it !in upcomingEntries } + current + upcoming
    }

    private fun updateClock(playbackAdvancing: Boolean, now: Long): Boolean {
        if (advancing == playbackAdvancing) return false
        val previousStop = inactiveSince
        advancing = playbackAdvancing
        inactiveSince = if (advancing) null else now
        return advancing && previousStop != null && now >= previousStop && now - previousStop >= RESET_AFTER_MILLIS
    }

    companion object {
        private const val RESET_AFTER_MILLIS = 6 * 60 * 60 * 1_000L
    }
}
