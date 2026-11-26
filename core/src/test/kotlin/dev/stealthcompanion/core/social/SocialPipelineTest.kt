package dev.stealthcompanion.core.social

import dev.stealthcompanion.core.protocol.ControllerSimulator
import dev.stealthcompanion.core.protocol.DisplayTextPolicy
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

    @Test fun repeatedConversationTextCanReachTheDisplayAsTwoDifferentEvents() {
        val entry = ConversationMessage("Lia", "Ciao", 1)
        val input = IncomingNotification(
            key = "chat",
            packageName = "lab.example.chat",
            appName = "Chat",
            conversation = "Squadra",
        )
        val rule = AppRule(enabled = true)
        val queue = SocialQueue(coalesceMs = 0)
        val session = SocialSession(ProtocolOptions())
        val display = ControllerSimulator(mtu = 64)

        listOf(listOf(entry), listOf(entry, entry.copy(timestamp = 2))).forEachIndexed { index, messages ->
            val time = index.toLong()
            assertTrue(queue.offer(requireNotNull(SocialPolicy.render(input.copy(messages = messages), rule)), time))
            val outgoing = requireNotNull(queue.poll(time))
            val event = requireNotNull(session.publish(outgoing.deliveryKey, SocialPayload(outgoing.title, outgoing.body), time))
            requireNotNull(display.receive(event) { request, mtu -> session.respond(request, mtu, time) })
        }

        assertEquals(listOf("Ciao — Lia", "Ciao — Lia"), display.displayed.map { it.text })
    }

    @Test fun oneGroupUpdateCanReachTheDisplayAsOrderedPagesWithoutReplayingTheUpdate() {
        val input = IncomingNotification(
            key = "group",
            packageName = "lab.example.chat",
            appName = "Chat",
            conversation = "Squadra",
            messages = listOf(
                ConversationMessage("Anna", "Caffè e una nuova partita 🎮. ".repeat(3), 1),
                ConversationMessage("Lia", "Pronti alle nove\nPortate le cuffie 🎧.", 2),
            ),
        )
        val rendered = requireNotNull(SocialPolicy.render(input, AppRule(enabled = true), format = SocialFormat.EXTENDED))
        val queue = SocialQueue(coalesceMs = 0)
        assertTrue(queue.offer(rendered, 0))
        val message = requireNotNull(queue.poll(0))
        val plan = DisplayTextPolicy(bodyLimitBytes = 64, paged = true).render(SocialPayload(message.title, message.body))
        val display = ControllerSimulator(mtu = 23, requestedMessageBytes = 64)
        val session = SocialSession()

        assertFalse(plan.truncated)
        assertTrue(plan.pages.size > 1)
        plan.pages.forEachIndexed { index, page ->
            val event = requireNotNull(session.publish("${message.deliveryKey}:$index", page, 0, allowLongResponse = true))
            requireNotNull(display.receive(event) { request, mtu -> session.respond(request, mtu, 0) })
        }
        assertEquals(plan.pages.map { it.title }, display.displayed.map { it.title })
        assertEquals(message.body, display.displayed.joinToString("") { it.text })
        assertFalse(queue.offer(rendered, 1))
    }
}
