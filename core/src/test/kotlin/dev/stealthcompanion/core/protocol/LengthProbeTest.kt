package dev.stealthcompanion.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LengthProbeTest {
    @Test
    fun everyLabSampleHasAnExactByteLengthNumberingAndMarker() {
        for (bytes in LengthProbe.SUPPORTED_SIZES) {
            for (text in ProbeText.entries) {
                for (delay in LengthProbe.SUPPORTED_DELAYS) {
                    val probe = LengthProbe(bytes, delay, text)
                    val body = probe.body()
                    val description = "$bytes bytes / $text / $delay ms"
                    assertEquals(description, bytes, body.toByteArray(Charsets.UTF_8).size)
                    assertTrue(description, body.startsWith("01"))
                    assertTrue(description, body.endsWith(probe.finalMarker))
                    assertEquals(description, "#$bytes", probe.finalMarker)
                    if (bytes >= 32) assertTrue(description, "02" in body)
                    when (text) {
                        ProbeText.ASCII -> assertTrue(description, body.all { it.code in 32..126 })
                        ProbeText.ACCENTS -> assertTrue(description, 'è' in body)
                        ProbeText.EMOJI -> assertTrue(description, "🙂" in body)
                        ProbeText.MULTILINE -> {
                            assertTrue(description, '\n' in body)
                            assertFalse(description, '\r' in body)
                        }
                    }
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { LengthProbe(bytes = 254) }
        assertThrows(IllegalArgumentException::class.java) { LengthProbe(delayMs = 30) }
    }

    @Test
    fun paginationPreservesBodyWithCompleteGraphemesAndMarker() {
        for (bytes in LengthProbe.SUPPORTED_SIZES) {
            for (text in ProbeText.entries) {
                val probe = LengthProbe(bytes, text = text, paged = true)
                val body = probe.body()
                val pages = probe.pages("Città 🙂 Laboratorio", body)
                assertEquals(body, pages.joinToString("") { it.text })
                assertTrue(pages.last().text.endsWith(probe.finalMarker))
                val boundaries = SocialCodec.graphemes(body).runningFold(0) { total, grapheme -> total + grapheme.length }.toSet()
                var offset = 0
                for ((index, page) in pages.withIndex()) {
                    assertTrue(page.title.startsWith("${index + 1}/${pages.size} "))
                    assertTrue(page.title.toByteArray().size <= 12)
                    assertTrue(page.text.toByteArray().size in 1..12)
                    offset += page.text.length
                    assertTrue(offset in boundaries)
                }
            }
        }
    }

    @Test
    fun pagesRemainCompleteThroughLegacyCodecAndSimulator() {
        val probe = LengthProbe(255, text = ProbeText.EMOJI, paged = true)
        val body = probe.body()
        val pages = probe.pages("Laboratorio", body)
        val session = SocialSession()
        val simulator = ControllerSimulator()
        val results = pages.mapIndexed { index, payload ->
            val event = requireNotNull(session.publish("page-$index", payload, index.toLong()))
            requireNotNull(simulator.receive(event) { request, mtu -> session.respond(request, mtu, index.toLong()) })
        }
        assertEquals(body, results.joinToString("") { it.text })
        assertEquals(pages.map { it.title }, results.map { it.title })
        assertTrue(results.last().text.endsWith(probe.finalMarker))
    }

    @Test
    fun exact254And255ByteMessagesTake14ExtendedAttValuesAtMtu23() {
        val probe = LengthProbe(255)
        val baseline = probe.body()
        val payloads = listOf(baseline.removePrefix("0"), baseline)
        for ((index, body) in payloads.withIndex()) {
            val expected = 254 + index
            assertEquals(expected, body.toByteArray().size)
            val session = SocialSession()
            val legacy = requireNotNull(session.publish("legacy", SocialPayload("Lab", body), 0))
            val extended = requireNotNull(session.publish("long", SocialPayload("Lab", body), 0, allowLongResponse = true))
            val legacyReply = session.respond(bodyRequest(legacy, expected), 23, 0)
            val extendedReply = session.respond(bodyRequest(extended, expected), 23, 0)
            assertEquals(listOf(20), legacyReply.map { it.size })
            assertEquals(body.take(12), decodeBody(legacyReply))
            assertEquals(List(13) { 20 } + (2 + index), extendedReply.map { it.size })
            assertEquals(body, decodeBody(extendedReply))
        }
    }

    @Test
    fun longerRetentionIsSelectiveAndRepeatedRequestsStayIdentical() {
        val session = SocialSession()
        val payload = SocialPayload("Lab", LengthProbe(255, text = ProbeText.ACCENTS).body())
        val personal = requireNotNull(session.publish("ordinary", payload, 100, allowLongResponse = true))
        val synthetic = requireNotNull(
            session.publish("synthetic", payload, 100, allowLongResponse = true, retainMillis = 120_000),
        )
        val initial = session.respond(bodyRequest(synthetic, 20), 23, 101)
        val late = session.respond(bodyRequest(synthetic, 20), 23, 30_100)
        assertEquals("01caffè 02 città 0", decodeBody(late))
        assertTrue(decodeBody(session.respond(bodyRequest(synthetic, 255), 23, 30_100)).endsWith("#255"))
        assertTrue(session.respond(bodyRequest(personal, 255), 23, 30_100).isEmpty())
        val beforeExpiry = session.respond(bodyRequest(synthetic, 20), 23, 120_099)
        assertEquals(initial.size, late.size)
        assertEquals(initial.size, beforeExpiry.size)
        initial.indices.forEach {
            assertArrayEquals(initial[it], late[it])
            assertArrayEquals(initial[it], beforeExpiry[it])
        }
        assertTrue(session.respond(bodyRequest(synthetic, 255), 23, 120_100).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { SocialSession(ttlMillis = 30_001) }
        assertThrows(IllegalArgumentException::class.java) { session.publish("x", payload, 100, retainMillis = 120_001) }
    }

    @Test
    fun legacyUidReservationSurvivesShorterModificationAndEviction() {
        val session = SocialSession(ProtocolOptions(allowUpdates = true), maxRecords = 1, ttlMillis = 5)
        val fixture = SocialPayload("Lab", "sample")
        val long = requireNotNull(session.publish("probe", fixture, 0, retainMillis = 50))
        val modified = requireNotNull(session.publish("probe", fixture.copy(text = "updated"), 1))
        assertEquals(uid(long), uid(modified))
        repeat(255) { requireNotNull(session.publish("ordinary-$it", fixture, 1)) }
        assertTrue(session.respond(bodyRequest(long, 255), 23, 6).isEmpty())
        val new = requireNotNull(session.publish("new", fixture, 6))
        assertNotEquals(uid(long), uid(new))
    }

    private fun uid(frame: ByteArray): Int = requireNotNull(SocialCodec.decodeEvent(frame)).uid

    private fun bodyRequest(event: ByteArray, maximum: Int): ByteArray =
        SocialCodec.encodeRequest(AttributeRequest(uid(event), listOf(RequestedAttribute(SocialAttribute.MESSAGE, maximum))))

    private fun decodeBody(frames: List<ByteArray>): String =
        requireNotNull(SocialCodec.decodeResponse(frames.reduce(ByteArray::plus))).attributes.single().value
}
