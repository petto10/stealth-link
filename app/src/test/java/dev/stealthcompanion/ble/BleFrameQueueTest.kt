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

    @Test
    fun pacingStartsAtThePreviousSuccessCallbackAndIsOnlyWithinItsResponse() {
        val queue = BleFrameQueue<String>(8, 40) { it.length }
        assertTrue(queue.offerBatch(listOf("t1", "t2", "t3"), spacingMillis = 50))
        assertTrue(queue.offerBatch(listOf("body"), spacingMillis = 300))
        assertTrue(queue.offerBatch(listOf("notice")))
        assertEquals("t1", queue.launchNext(100))
        assertEquals(0L, queue.remainingDelayMillis(150))
        assertNull(queue.launchNext(200))
        assertEquals("t1", queue.complete(215))
        assertEquals(50L, queue.remainingDelayMillis(215))
        assertEquals(1L, queue.remainingDelayMillis(264))
        assertNull(queue.launchNext(264))
        assertEquals("t2", queue.launchNext(265))
        assertEquals("t2", queue.complete(267))
        assertNull(queue.launchNext(316))
        assertEquals("t3", queue.launchNext(317))
        assertEquals("t3", queue.complete(318))
        assertEquals("body", queue.launchNext(318))
        assertEquals("body", queue.complete(319))
        assertEquals("notice", queue.launchNext(319))
    }

    @Test
    fun abandoningAPacedContinuationBlocksLaterResponsesEvenWithoutAnInFlightFrame() {
        val queue = BleFrameQueue<String>(5, 40) { it.length }
        assertTrue(queue.offerBatch(listOf("body1", "body2"), spacingMillis = 300))
        assertTrue(queue.offerBatch(listOf("title")))
        assertEquals("body1", queue.launchNext(0))
        assertEquals("body1", queue.complete(100))
        assertTrue(queue.hasStartedBatch)
        assertNull(queue.inFlight)
        assertNull(queue.launchNext(200))
        assertTrue(queue.discardQueued { it == "body2" })
        assertNull(queue.launchNext(401))
        queue.clear()
        assertFalse(queue.hasStartedBatch)
        assertTrue(queue.offerBatch(listOf("new")))
        assertEquals("new", queue.launchNext(401))
    }

    @Test
    fun aQueueResetClearsPacingEvenWhenTheNextResponseHasTheSameDelay() {
        val queue = BleFrameQueue<String>(4, 20) { it.length }
        assertTrue(queue.offerBatch(listOf("old1", "old2"), spacingMillis = 100))
        assertEquals("old1", queue.launchNext(10))
        assertEquals("old1", queue.complete(20))
        assertEquals(90L, queue.remainingDelayMillis(30))
        queue.clear()
        assertTrue(queue.offerBatch(listOf("new1", "new2"), spacingMillis = 100))
        assertEquals("new1", queue.launchNext(30))
        assertEquals("new1", queue.complete(40))
        assertNull(queue.launchNext(139))
        assertEquals("new2", queue.launchNext(140))
    }
}
