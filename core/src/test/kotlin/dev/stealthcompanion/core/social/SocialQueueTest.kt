package dev.stealthcompanion.core.social

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SocialQueueTest {
    private fun note(key: String, body: String = "Ciao") = SocialMessage(key, "Test", body)

    @Test
    fun coalescesUpdatesWithoutMovingTheFirstEventOrPostponingDispatch() {
        val queue = SocialQueue(capacity = 3, ttlMs = 100, coalesceMs = 10)
        assertTrue(queue.offer(note("a"), 0))
        assertTrue(queue.offer(note("b"), 2))
        assertTrue(queue.offer(note("a", "aggiornata"), 6))
        assertNull(queue.poll(9))
        assertEquals(10L, queue.nextReadyAt(9))
        assertEquals(note("a", "aggiornata"), queue.poll(10))
        assertNull(queue.poll(11))
        assertEquals(note("b"), queue.poll(12))
        assertEquals(1L, queue.stats.coalesced)
    }

    @Test
    fun boundsQueueAndDropsStaleEvents() {
        val queue = SocialQueue(capacity = 2, ttlMs = 100, coalesceMs = 10)
        queue.offer(note("first"), 0)
        queue.offer(note("second"), 1)
        queue.offer(note("third"), 2)
        assertEquals(2, queue.size)
        assertEquals(1L, queue.stats.overflow)
        assertEquals(note("second"), queue.poll(11))
        queue.expire(102)
        assertEquals(0, queue.size)
        assertEquals(1L, queue.stats.expired)
    }

    @Test
    fun deduplicatesBothPendingAndDispatchedEventsUntilTtl() {
        val queue = SocialQueue(ttlMs = 100, coalesceMs = 0)
        assertTrue(queue.offer(note("a"), 0))
        assertFalse(queue.offer(note("a"), 1))
        assertEquals(note("a"), queue.poll(2))
        assertFalse(queue.offer(note("a"), 101))
        assertNull(queue.poll(101))
        assertTrue(queue.offer(note("a"), 102))
        assertEquals(2L, queue.stats.duplicates)
    }

    @Test
    fun updatesThatRevertToDispatchedContentCancelStalePendingState() {
        val queue = SocialQueue(ttlMs = 100, coalesceMs = 1)
        queue.offer(note("a"), 0)
        queue.poll(1)
        queue.offer(note("a", "new"), 2)
        assertFalse(queue.offer(note("a"), 3))
        assertNull(queue.poll(3))
    }

    @Test
    fun acceptsIdenticalMessagesFromDifferentNotifications() {
        val queue = SocialQueue(coalesceMs = 0)
        assertTrue(queue.offer(note("a"), 0))
        assertTrue(queue.offer(note("b"), 1))
        assertEquals(note("a"), queue.poll(2))
        assertEquals(note("b"), queue.poll(2))
    }

    @Test
    fun removalClearsPendingAndAllowsRealReposts() {
        val queue = SocialQueue(coalesceMs = 0)
        queue.offer(note("a"), 0)
        assertTrue(queue.remove("a"))
        assertNull(queue.poll(0))
        assertTrue(queue.offer(note("a"), 1))
        assertEquals(note("a"), queue.poll(1))
        queue.remove("a")
        assertTrue(queue.offer(note("a"), 2))
        queue.clear()
        assertNull(queue.nextReadyAt(2))
    }

    @Test
    fun wrapsNoIdsAndHandlesMoreThan256UniqueNotifications() {
        val queue = SocialQueue(coalesceMs = 0)
        repeat(300) { index ->
            val value = note("note-$index")
            assertTrue(queue.offer(value, index.toLong()))
            assertEquals(value, queue.poll(index.toLong()))
        }
        assertEquals(300L, queue.stats.accepted)
    }
}
