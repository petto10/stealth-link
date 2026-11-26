package dev.stealthcompanion.core.social

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SocialQueueTest {
    private fun note(key: String, body: String = "Ciao") = SocialMessage(key, "Test", body)

    private fun conversation(vararg entries: ConversationMessage, key: String = "chat"): SocialMessage =
        requireNotNull(SocialPolicy.render(
            IncomingNotification(key, "test.app", "Social App", conversation = "Squadra", messages = entries.toList()),
            AppRule(enabled = true),
            format = SocialFormat.EXTENDED,
        ))

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
    fun nextExpirationFollowsEarliestDeadlineAfterCoalescing() {
        val queue = SocialQueue(ttlMs = 100, coalesceMs = 10)
        queue.offer(note("a"), 0)
        queue.offer(note("b"), 4)
        queue.offer(note("a", "revised"), 7)
        assertEquals(104L, queue.nextExpirationAt(100))
        assertEquals(107L, queue.nextExpirationAt(104))
        assertNull(queue.nextExpirationAt(107))
        assertEquals(2L, queue.stats.expired)
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

    @Test
    fun remembersGroupMessagesBeyondPendingLifetimeAndForwardsOnlyFreshLines() {
        val queue = SocialQueue(coalesceMs = 0)
        val first = ConversationMessage("Ada", "Prima", 1)
        val second = ConversationMessage("Lea", "Seconda", 2)
        val third = ConversationMessage("Ada", "Terza", 3)
        assertTrue(queue.offer(conversation(first, second), 0))
        assertEquals("Seconda — Lea · Prima — Ada", queue.poll(0)?.body)
        assertTrue(queue.offer(conversation(first, second, third), 60_000))
        val next = requireNotNull(queue.poll(60_000))
        assertEquals("Terza — Ada", next.body)
        assertNull(next.conversationUpdate)
        assertFalse(queue.offer(conversation(first, second, third), 60_001))
    }

    @Test
    fun intentionallyRepeatedTextReceivesADifferentDeliveryKey() {
        val queue = SocialQueue(coalesceMs = 0)
        val first = ConversationMessage("Ada", "Ciao", 1)
        val nextMillisecond = first.copy(timestamp = 2)
        queue.offer(conversation(first), 0)
        val a = requireNotNull(queue.poll(0))
        assertTrue(queue.offer(conversation(first, nextMillisecond), 1))
        val b = requireNotNull(queue.poll(1))
        assertEquals(a.body, b.body)
        assertTrue(a.deliveryKey != b.deliveryKey)
        assertEquals(a.key, b.key)
        assertFalse(queue.offer(conversation(first, nextMillisecond), 2))
    }

    @Test
    fun preservesAdditionalOccurrencesWhenSourceLacksStableIdsAndTimesAreEqual() {
        val queue = SocialQueue(coalesceMs = 0)
        val repeated = ConversationMessage("Ada", "Ciao", 1)
        queue.offer(conversation(repeated), 0)
        queue.poll(0)
        assertTrue(queue.offer(conversation(repeated, repeated), 1))
        assertEquals("Ciao — Ada", queue.poll(1)?.body)
        assertFalse(queue.offer(conversation(repeated, repeated), 2))
    }

    @Test
    fun coalescesUnsentGroupMessagesWithoutReplayingDispatchedHistory() {
        val queue = SocialQueue(coalesceMs = 10)
        val a = ConversationMessage("Ada", "Prima", 1)
        val b = ConversationMessage("Lea", "Seconda", 2)
        val c = ConversationMessage("Ada", "Terza", 3)
        queue.offer(conversation(a), 0)
        queue.poll(10)
        assertTrue(queue.offer(conversation(a, b), 11))
        assertTrue(queue.offer(conversation(a, b, c), 12))
        assertEquals("Terza — Ada · Seconda — Lea", queue.poll(21)?.body)
        assertNull(queue.poll(22))
    }

    @Test
    fun sameGroupContentUnderDifferentKeysRemainsIndependent() {
        val queue = SocialQueue(coalesceMs = 0)
        val a = ConversationMessage("Ada", "Prima", 1)
        queue.offer(conversation(a), 0)
        queue.poll(0)
        assertTrue(queue.offer(conversation(a, key = "different-account"), 1))
        assertEquals("different-account", queue.poll(1)?.key)
    }

    @Test
    fun groupHistoryExpiresAndRemovalForgetsIdentifiers() {
        val queue = SocialQueue(ttlMs = 100, coalesceMs = 0, conversationHistoryTtlMs = 300)
        val message = conversation(ConversationMessage("Ada", "Prima", 1))
        queue.offer(message, 0)
        queue.poll(0)
        assertFalse(queue.offer(message, 299))
        assertTrue(queue.offer(message, 300))
        queue.poll(300)
        queue.remove(message.key)
        assertTrue(queue.offer(message, 301))
    }

    @Test
    fun groupLayoutOnlyChangesDoNotReplayDispatchedExtendedText() {
        val queue = SocialQueue(coalesceMs = 0)
        val source = ConversationMessage("Lia Rossi", "Prima\nSeconda", 1)
        assertTrue(queue.offer(conversation(source), 0))
        assertEquals("Prima — Lia Rossi\nSeconda", queue.poll(0)?.body)
        assertFalse(queue.offer(conversation(source.copy(sender = "Lia  Rossi", text = "Prima \n \n Seconda")), 1))
        assertNull(queue.poll(1))
    }

    @Test
    fun dispatchedGroupHintBelongsToNewestMessageAmongTheFreshAllowedEntries() {
        val queue = SocialQueue(coalesceMs = 0)
        val latest = ConversationMessage("Lia", "Già inviato", 4)
        val retained = conversation(latest)
        assertTrue(queue.offer(retained, 0))
        assertEquals("Lia", queue.poll(0)?.sender)

        val input = IncomingNotification(
            retained.key,
            "test.app",
            "Social App",
            conversation = "Squadra",
            messages = listOf(
                ConversationMessage("Ada", "Nuovo nel feed", 3),
                latest,
                ConversationMessage("Blocked", "Bloccato", 5),
            ),
        )
        val rule = AppRule(enabled = true, blockedContacts = setOf("Blocked"))
        val fullGroup = requireNotNull(SocialPolicy.render(input, rule, format = SocialFormat.EXTENDED))
        assertEquals("Lia", fullGroup.sender)
        assertTrue(queue.offer(fullGroup, 1))
        val fresh = queue.poll(1)
        assertEquals("Nuovo nel feed — Ada", fresh?.body)
        assertEquals("Ada", fresh?.sender)
    }

    @Test
    fun coalescedIdenticalContentUsesTheNewestSenderHintWithoutResending() {
        val queue = SocialQueue(coalesceMs = 10)
        val plain = note("a").copy(sender = "Ada")
        assertTrue(queue.offer(plain, 0))
        assertFalse(queue.offer(plain.copy(sender = null), 3))
        assertEquals(10L, queue.nextReadyAt(3))
        val dispatched = requireNotNull(queue.poll(10))
        assertNull(dispatched.sender)
        assertFalse(queue.offer(plain, 11))
    }
}
