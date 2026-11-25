package dev.stealthcompanion.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFrameQueueTest {
    @Test
    fun sendsOneFramePerAcknowledgmentAndNeverInterleavesBatches() {
        val queue = BleFrameQueue<String>(10, 40) { it.length }
        assertTrue(queue.offerBatch(listOf("T1", "T2", "T3")))
        assertEquals("T1", queue.launchNext())
        assertTrue(queue.offerBatch(listOf("B1", "B2")))
        assertNull(queue.launchNext())
        assertEquals("T1", queue.complete())
        val remaining = buildList {
            while (true) {
                add(queue.launchNext() ?: break)
                queue.complete()
            }
        }
        assertEquals(listOf("T2", "T3", "B1", "B2"), remaining)
        assertNull(queue.complete())
    }

    @Test
    fun capacityIncludesFrameAlreadyHandedToAndroid() {
        val queue = BleFrameQueue<String>(3, 6) { it.length }
        assertFalse(queue.offerBatch(emptyList()))
        assertTrue(queue.offerBatch(listOf("aa", "bb")))
        assertEquals("aa", queue.launchNext())
        assertFalse(queue.offerBatch(listOf("too long")))
        assertFalse(queue.offerBatch(listOf("c", "d")))
        assertTrue(queue.offerBatch(listOf("cc")))
        queue.complete()
        assertEquals("bb", queue.launchNext())
        queue.complete()
        assertEquals("cc", queue.launchNext())
        queue.complete()
        assertNull(queue.launchNext())
    }

    @Test
    fun privacyDiscardsQueuedFramesWithoutConfusingInFlightCallback() {
        val queue = BleFrameQueue<String>(4, 40) { it.length }
        assertTrue(queue.offerBatch(listOf("private1")))
        assertTrue(queue.offerBatch(listOf("private2")))
        assertTrue(queue.offerBatch(listOf("synthetic")))
        assertEquals("private1", queue.launchNext())
        assertFalse(queue.discardQueued { it.startsWith("private") })
        assertTrue(queue.any { it == "private1" })
        assertFalse(queue.any { it == "private2" })
        assertNull(queue.launchNext())
        assertEquals("private1", queue.complete())
        assertEquals("synthetic", queue.launchNext())
        queue.clear()
        assertNull(queue.complete())
    }

    @Test
    fun removingContinuationAfterAcknowledgmentBlocksNewResponsesUntilReset() {
        val queue = BleFrameQueue<String>(5, 40) { it.length }
        assertTrue(queue.offerBatch(listOf("T1", "T2", "T3")))
        assertTrue(queue.offerBatch(listOf("B1")))
        assertEquals("T1", queue.launchNext())
        assertEquals("T1", queue.complete())
        assertNull(queue.inFlight)
        assertTrue(queue.discardQueued { it.startsWith("T") })
        assertNull(queue.launchNext())
        assertFalse(queue.offerBatch(listOf("new")))
        queue.clear()
        assertTrue(queue.offerBatch(listOf("new")))
        assertEquals("new", queue.launchNext())
    }

    @Test
    fun removingUnsentsDoesNotResetWhenLatestInFlightFrameAlreadyCompletesResponse() {
        val queue = BleFrameQueue<String>(5, 40) { it.length }
        assertTrue(queue.offerBatch(listOf("T1", "T2")))
        assertTrue(queue.offerBatch(listOf("future1", "future2")))
        assertEquals("T1", queue.launchNext())
        queue.complete()
        assertEquals("T2", queue.launchNext())
        assertFalse(queue.discardQueued { it.startsWith("T") || it.startsWith("future") })
        assertTrue(queue.offerBatch(listOf("new")))
        assertEquals("T2", queue.complete())
        assertEquals("new", queue.launchNext())
    }

    @Test
    fun removingContinuationWhileNonterminalFrameIsInFlightAlsoRequiresReset() {
        val queue = BleFrameQueue<String>(5, 40) { it.length }
        assertTrue(queue.offerBatch(listOf("T1", "T2")))
        assertTrue(queue.offerBatch(listOf("other")))
        assertEquals("T1", queue.launchNext())
        assertTrue(queue.discardQueued { it.startsWith("T") })
        assertEquals("T1", queue.complete())
        assertNull(queue.launchNext())
    }
}
