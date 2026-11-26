package dev.stealthcompanion.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDelayedActionTest {
    private val queued = mutableListOf<Pair<Runnable, Long>>()
    private val removed = mutableListOf<Runnable>()
    private val timer = BleDelayedAction({ task, delay -> queued.add(task to delay) }, { removed.add(it) })

    @Test
    fun replacingAnOperationMakesAnAlreadyDispatchedOldCallbackHarmless() {
        val results = mutableListOf<String>()
        timer.replace(400) { results.add("old link") }
        val old = queued.single().first
        timer.replace(1_000) { results.add("new link") }
        val latest = queued.last()
        assertEquals(1_000L, latest.second)
        assertEquals(listOf(old), removed)
        old.run()
        assertTrue(timer.isPending)
        assertTrue(results.isEmpty())
        latest.first.run()
        latest.first.run()
        assertEquals(listOf("new link"), results)
        assertFalse(timer.isPending)
    }

    @Test
    fun cancellationDoesNotPreventASeparateTransportFromRunning() {
        val other = BleDelayedAction({ task, delay -> queued.add(task to delay) }, { removed.add(it) })
        val results = mutableListOf<String>()
        timer.replace(2_000) { results.add("settings") }
        other.replace(1_000) { results.add("social") }
        timer.cancel()
        queued.forEach { (task, _) -> task.run() }
        assertEquals(listOf("social"), results)
        assertFalse(timer.isPending)
        assertFalse(other.isPending)
    }

    @Test
    fun callbackCanScheduleItsSuccessorWithoutBeingInvalidatedByOldDelivery() {
        var deliveries = 0
        timer.replace(25) {
            deliveries++
            timer.replace(25) { deliveries++ }
        }
        val old = queued.single().first
        old.run()
        old.run()
        assertTrue(timer.isPending)
        queued.last().first.run()
        assertEquals(2, deliveries)
    }
}
