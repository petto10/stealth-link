package dev.stealthcompanion

import dev.stealthcompanion.core.protocol.ControllerSimulator
import dev.stealthcompanion.core.protocol.DisplayTextPolicy
import dev.stealthcompanion.core.protocol.SocialPayload
import dev.stealthcompanion.core.protocol.SocialSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPageDeliveryTest {
    private val pages = DisplayTextPolicy(bodyLimitBytes = 64, paged = true).render(
        SocialPayload("Partita · Chat", "Nuovo invito 🎮 ".repeat(10)), sender = "Lia",
    ).pages

    @Test
    fun receiverThatPrependsEntriesShowsAscendingPartNumbersWithReverseDelivery() = runBlocking {
        val session = SocialSession()
        val receiver = ControllerSimulator(mtu = 23, requestedMessageBytes = 64)

        assertTrue(pages.size > 1)
        assertTrue(deliverInFeedOrder(pages, newestFirst = true) { index, page ->
            val event = requireNotNull(session.publish("chat-part-$index", page, 0, allowLongResponse = true))
            receiver.receive(event) { request, mtu -> session.respond(request, mtu, 0) } != null
        })
        assertEquals(pages.size, receiver.displayed.map { it.uid }.toSet().size)
        assertEquals(pages.map { it.title }, receiver.displayed.asReversed().map { it.title })
        assertEquals(pages.map { it.text }, receiver.displayed.asReversed().map { it.text })
    }

    @Test
    fun ambiguousFailureStopsTheRestOfTheLogicalMessageWithoutRepeatingAPage() = runBlocking {
        val attempted = mutableListOf<Int>()
        assertTrue(pages.size > 2)
        assertFalse(deliverInFeedOrder(pages, newestFirst = false) { index, _ ->
            attempted += index
            index == 0
        })
        assertEquals(listOf(0, 1), attempted)
    }
}
