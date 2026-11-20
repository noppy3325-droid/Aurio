package jp.noppy.lumamusic.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartShuffleCycleTest {
    private val tracks = listOf("a", "b", "c", "d", "e")
    private val baseOrder = tracks.indices.toList()

    @Test fun playsEveryEntryBeforeStartingAnotherRound() {
        val cycle = SmartShuffleCycle(Random(41))
        var order = cycle.update(baseOrder, tracks, 2, true, 1_000)
        assertEquals(2, order.first())

        val heard = mutableListOf(2)
        for (next in order.drop(1)) {
            order = cycle.update(order, tracks, next, true, 2_000)
            heard += next
        }
        assertEquals(baseOrder.toSet(), heard.toSet())
        assertEquals(heard.last(), order.last())
        assertTrue(cycle.roundReady)
        assertNotEquals(heard.last(), order.first())

        var current = order.first()
        order = cycle.update(order, tracks, current, true, 3_000)
        assertEquals(listOf(current), cycle.checkpoint(3_000).visited)
        val nextRound = mutableListOf(current)
        repeat(4) {
            current = order[order.indexOf(current) + 1]
            order = cycle.update(order, tracks, current, true, 4_000)
            nextRound += current
        }
        assertEquals(baseOrder.toSet(), nextRound.toSet())
    }

    @Test fun restoresLogBeforeSixHoursAndResetsAtSixHoursWhileKeepingCurrentEntry() {
        val cycle = SmartShuffleCycle(Random(12))
        val start = 500_000L
        var order = cycle.update(baseOrder, tracks, 0, true, start)
        val current = order[1]
        order = cycle.update(order, tracks, current, true, start + 10_000)
        val pause = start + 60_000
        cycle.update(order, tracks, current, false, pause)
        val persisted = cycle.checkpoint(pause + 1_000)
        assertEquals(pause, persisted.inactiveSinceMillis)

        val shortBreak = SmartShuffleCycle(Random(9)).apply { restore(persisted, current) }
        val shortResume = shortBreak.update(order, tracks, current, true, pause + 6 * 3_600_000 - 1)
        assertEquals(order, shortResume)
        assertEquals(listOf(0, current), shortBreak.checkpoint(pause).visited)

        val longBreak = SmartShuffleCycle(Random(9)).apply { restore(persisted, current) }
        val longResume = longBreak.update(order, tracks, current, true, pause + 6 * 3_600_000)
        assertEquals(current, longResume.first())
        assertEquals(listOf(current), longBreak.checkpoint(pause).visited)
    }

    @Test fun ongoingPlaybackDoesNotUseElapsedTimeSinceTheStart() {
        val cycle = SmartShuffleCycle(Random(3))
        val order = cycle.update(baseOrder, tracks, 1, true, 10_000)
        val next = order[1]
        val late = 12 * 3_600_000L
        assertEquals(order, cycle.update(order, tracks, next, true, late))
        assertEquals(listOf(1, next), cycle.checkpoint(late).visited)
        assertEquals(late, cycle.checkpoint(late).inactiveSinceMillis)
    }

    @Test fun enablingAgainKeepsCurrentAndPendingPlayNextButForgetsVisitedEntries() {
        val cycle = SmartShuffleCycle(Random(5))
        var order = cycle.update(baseOrder, tracks, 1, true, 100)
        val next = order[1]
        order = cycle.update(order, tracks, next, true, 200)
        val pinned = order.last()
        cycle.playNext(pinned)
        cycle.clearRound()
        order = cycle.update(order, tracks, next, false, 300)
        assertEquals(listOf(next, pinned), order.take(2))
        assertEquals(listOf(next), cycle.checkpoint(300).visited)
        assertFalse(cycle.roundReady)
    }

    @Test fun movingOrRemovingQueueEntriesRemapsTheExistingCycle() {
        val cycle = SmartShuffleCycle(Random(2))
        val original = cycle.update(baseOrder, tracks, 0, true, 10)
        val current = original[1]
        cycle.update(original, tracks, current, true, 20)
        val inverse = baseOrder.reversed().filter { it != original.last() }
        cycle.remap(inverse.withIndex().associate { (next, old) -> old to next })
        val updatedOrder = original.mapNotNull { index -> inverse.indexOf(index).takeIf { it >= 0 } }
        val mappedCurrent = inverse.indexOf(current)
        val remapped = cycle.update(updatedOrder, inverse.map(tracks::get), mappedCurrent, true, 40, queueChanged = true)
        assertEquals(listOf(inverse.indexOf(0), mappedCurrent), remapped.take(2))
        assertEquals(inverse.indices.toSet(), remapped.toSet())
    }

    @Test fun duplicateTracksOnlyPlayOncePerRoundButRemainInTheQueue() {
        val cycle = SmartShuffleCycle(Random(27))
        val duplicateTracks = listOf("same", "other", "same")
        val order = cycle.update(listOf(0, 1, 2), duplicateTracks, 0, true, 100)
        assertEquals(setOf(0, 1, 2), order.toSet())
        assertEquals(listOf(1), order.dropWhile { it != 0 }.drop(1))
        val nextRound = cycle.update(order, duplicateTracks, 1, true, 200)
        assertTrue(cycle.roundReady)
        assertEquals("same", duplicateTracks[nextRound.first()])
    }

    @Test fun migratedFullPrefixPreparesTheNextRoundOnItsFirstRestoredTick() {
        val cycle = SmartShuffleCycle(Random(6)).apply {
            restore(SmartShuffleCheckpoint(visited = baseOrder), current = 4, reconcileOrder = true)
        }

        val updated = cycle.update(baseOrder, tracks, 4, false, 1_000)

        assertTrue(cycle.roundReady)
        assertEquals(4, updated.last())
        assertEquals(3, updated[updated.lastIndex - 1])
        assertNotEquals(4, updated.first())
    }

    @Test fun restoringAPreparedRoundKeepsTheAdvertisedNextAndPreviousItems() {
        val prepared = listOf(2, 0, 1, 3, 4)
        val cycle = SmartShuffleCycle(Random(1)).apply {
            restore(SmartShuffleCheckpoint(visited = baseOrder, roundReady = true), current = 4, reconcileOrder = true)
        }
        assertEquals(prepared, cycle.update(prepared, tracks, 4, false, 1_000))
        val wrapped = cycle.update(prepared, tracks, 2, true, 2_000)
        assertEquals(2, wrapped.first())
        assertEquals(4, wrapped.last())
    }
}
