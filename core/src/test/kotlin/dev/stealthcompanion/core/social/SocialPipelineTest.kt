package dev.stealthcompanion.core.social

import dev.stealthcompanion.core.protocol.ControllerSimulator
import dev.stealthcompanion.core.protocol.ProtocolOptions
import dev.stealthcompanion.core.protocol.SocialPayload
import dev.stealthcompanion.core.protocol.SocialSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialPipelineTest {
    @Test fun arbitraryOptedInPackagesReachTheSimulatedDisplayInOrder() {
        val alpha = IncomingNotification(
            key = "a",
            packageName = "lab.example.alpha",
            appName = "Alpha",
            title = "Caffè",
            text = "Primo 🎮",
        )
        val beta = IncomingNotification(
            key = "b",
            packageName = "lab.example.beta",
            appName = "Beta",
            conversation = "Gruppo",
            messages = listOf(
                ConversationMessage("Anna", "Ciao", 1),
                ConversationMessage("Ivo", "Escluso", 2),
                ConversationMessage("Lia", "Alle nove", 3),
            ),
        )
        val appRule = AppRule(enabled = true, blockedContacts = setOf("Ivo"), includeAppName = false)
        val queue = SocialQueue(coalesceMs = 3)
        val session = SocialSession(ProtocolOptions(allowFragmentation = true))
        val display = ControllerSimulator(mtu = 23)

        assertNull(SocialPolicy.render(alpha, AppRule()))
        val first = requireNotNull(SocialPolicy.render(alpha, appRule))
        val second = requireNotNull(SocialPolicy.render(beta, appRule))
        assertTrue(queue.offer(first, 1))
        assertFalse(queue.offer(first, 2))
        assertTrue(queue.offer(second, 3))
        queue.offer(first.copy(body = "Aggiornato 🎮"), 4)

        assertEquals(2, queue.size)
        repeat(2) {
            val message = requireNotNull(queue.poll(6))
            val event = requireNotNull(session.publish(message.key, SocialPayload(message.title, message.body), 6))
            requireNotNull(display.receive(event) { request, mtu -> session.respond(request, mtu, 6) })
        }
        assertEquals(listOf("Caffè", "Gruppo"), display.displayed.map { it.title })
        assertEquals("Aggiornato 🎮", display.displayed.first().text)
        assertFalse(display.displayed.last().text.contains("Escluso"))
        assertTrue(display.displayed.last().text.contains("Alle nove — Lia"))
        assertNull(queue.poll(6))
    }
}
