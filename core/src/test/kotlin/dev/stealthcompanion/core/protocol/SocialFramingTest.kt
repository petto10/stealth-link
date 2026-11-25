package dev.stealthcompanion.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialFramingTest {
    private val title = RequestedAttribute(SocialAttribute.TITLE, 65535)
    private val message = RequestedAttribute(SocialAttribute.MESSAGE, 65535)
    private val extended = ProtocolOptions(allowFragmentation = true, maxAttributeBytes = 1024)

    @Test
    fun mtu23LegacyFramesHaveTwelveBytesOfVisibleContentEach() {
        val payload = SocialPayload("Notifica di prova", "Caffè pronto dopo l'allenamento")
        val titleFrame = SocialCodec.encodeResponse(AttributeRequest(1, listOf(title)), payload, 23).single()
        val bodyFrame = SocialCodec.encodeResponse(AttributeRequest(1, listOf(message)), payload, 23).single()
        assertEquals(20, titleFrame.size)
        assertEquals(20, bodyFrame.size)
        assertEquals("Notifica di ", decode(listOf(titleFrame)).attributes.single().value)
        assertEquals("Caffè pront", decode(listOf(bodyFrame)).attributes.single().value)
        assertEquals(12, bodyFrame[6].toInt())
        assertEquals(0, bodyFrame[7].toInt())
    }

    @Test
    fun notificationValueBudgetsAccountForAttOverheadAndGattMaximum() {
        for ((mtu, budget) in listOf(23 to 20, 64 to 61, 185 to 182, 247 to 244, 514 to 511, 515 to 512, 517 to 512)) {
            assertEquals("MTU $mtu", budget, SocialCodec.notificationValueLimit(mtu))
        }
        assertNull(SocialCodec.notificationValueLimit(22))
        assertNull(SocialCodec.notificationValueLimit(518))

        val longPayload = SocialPayload("", "a".repeat(1024))
        val request = AttributeRequest(1, listOf(message))
        val frames = SocialCodec.encodeResponse(request, longPayload, 517, extended)
        assertEquals(listOf(512, 512, 8), frames.map(ByteArray::size))
        assertEquals(longPayload.text, decode(frames).attributes.single().value)
    }

    @Test
    fun requestedMaximumIsEnforcedInBothFramingModes() {
        val payload = SocialPayload("", "a".repeat(90) + "è" + "b".repeat(500))
        for (options in listOf(ProtocolOptions(), extended)) {
            for ((max, expected) in listOf(0 to "", 40 to "a".repeat(40), 91 to "a".repeat(90), 92 to "a".repeat(90) + "è")) {
                val request = AttributeRequest(1, listOf(message.copy(maximumLength = max)))
                val frames = SocialCodec.encodeResponse(request, payload, 185, options)
                assertEquals(expected, decode(frames).attributes.single().value)
            }
        }
        val maxRequest = AttributeRequest(1, listOf(message))
        assertEquals(174, decode(SocialCodec.encodeResponse(maxRequest, payload, 185)).attributes.single().value.toByteArray().size)
        assertEquals(244, SocialCodec.encodeResponse(maxRequest, payload, 247).single().size)
    }

    @Test
    fun extendedFramesReassembleOneHeaderEvenWhenAttCutsAnUtf8Sequence() {
        val prefix = "a".repeat(11)
        val body = prefix + "è ✅ 👩🏽‍💻 " + "ciao!".repeat(30)
        val request = AttributeRequest(0x01020304, listOf(message))
        val frames = SocialCodec.encodeResponse(request, SocialPayload("", body), 23, extended)
        assertEquals(20, frames.first().size)
        assertEquals(0xC3, frames.first().last().toInt() and 0xFF)
        assertEquals(0xA8, frames[1].first().toInt() and 0xFF)
        assertEquals(0x04, frames.first()[1].toInt())
        assertEquals(body, decode(frames).attributes.single().value)
        assertEquals(0x01020304, decode(frames).uid)
        assertTrue(frames.all { it.size <= 20 })
        assertNull(SocialCodec.decodeResponse(frames.dropLast(1).reduce(ByteArray::plus)))
    }

    @Test
    fun combinedAttributesFollowRequestOrderAndSeparateMaxima() {
        val options = extended.copy(allowMultipleAttributes = true)
        val payload = SocialPayload("Tè in compagnia", "👩🏽‍💻 ".repeat(30))
        val order = listOf(message.copy(maximumLength = 270), title.copy(maximumLength = 3))
        val request = AttributeRequest(7, order)
        val frames = SocialCodec.encodeResponse(request, payload, 64, options)
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.size <= 61 })
        val decoded = decode(frames)
        assertEquals(listOf(SocialAttribute.MESSAGE, SocialAttribute.TITLE), decoded.attributes.map(AttributeValue::attribute))
        assertEquals("👩🏽‍💻 ".repeat(16), decoded.attributes.first().value)
        assertEquals("Tè", decoded.attributes.last().value)
        assertTrue(SocialCodec.encodeResponse(request, payload, 64).isEmpty())
    }

    @Test
    fun truncationRetainsWholeExtendedGraphemes() {
        for (cluster in listOf("e\u0301", "👩🏽‍💻", "🏳️‍🌈", "🇮🇹", "1️⃣", "👍🏽")) {
            val clusterBytes = cluster.toByteArray().size
            val text = "Ab" + cluster + "z"
            assertArrayEquals("Ab".toByteArray(), SocialCodec.encodeUtf8Within(text, 2 + clusterBytes - 1))
            assertArrayEquals(("Ab" + cluster).toByteArray(), SocialCodec.encodeUtf8Within(text, 2 + clusterBytes))
        }
    }

    @Test
    fun extendedModeIsBoundToItsRecordAndPreservesLegacyEventAndUid() {
        val payload = SocialPayload("Test", "test ".repeat(400))
        val session = SocialSession()
        val firstEvent = requireNotNull(session.publish("old", payload, 0))
        val longEvent = requireNotNull(session.publish("extended", payload, 1, allowLongResponse = true))
        val lastEvent = requireNotNull(session.publish("legacy", payload, 2))
        assertArrayEquals(byteArrayOf(0, 16, 4, 2, 0, 0, 0, 2), longEvent)

        fun respond(event: ByteArray, attributes: List<RequestedAttribute> = listOf(message)): List<ByteArray> {
            val uid = requireNotNull(SocialCodec.decodeEvent(event)).uid
            val request = SocialCodec.encodeRequest(AttributeRequest(uid, attributes))
            return session.respond(request, 23, 3)
        }

        assertEquals(20, respond(firstEvent).single().size)
        assertEquals(52, respond(longEvent).size)
        assertEquals(1024, decode(respond(longEvent)).attributes.single().value.toByteArray().size)
        assertEquals(20, respond(lastEvent).single().size)

        val batched = listOf(title, message.copy(maximumLength = 45))
        assertTrue(respond(firstEvent, batched).isEmpty())
        assertEquals(
            listOf(AttributeValue(SocialAttribute.TITLE, "Test"), AttributeValue(SocialAttribute.MESSAGE, "test ".repeat(9))),
            decode(respond(longEvent, batched)).attributes,
        )
        assertTrue(respond(lastEvent, batched).isEmpty())
    }

    @Test
    fun configuredProbeLimitStillAppliesToAnExplicitlyExtendedRecord() {
        val payload = SocialPayload("Test", "✦".repeat(400))
        val session = SocialSession(extended.copy(maxAttributeBytes = 33))
        val event = requireNotNull(session.publish("probe", payload, 0, allowLongResponse = true))
        val uid = requireNotNull(SocialCodec.decodeEvent(event)).uid
        val frames = session.respond(SocialCodec.encodeRequest(AttributeRequest(uid, listOf(message))), 23, 1)
        assertEquals("✦".repeat(11), decode(frames).attributes.single().value)
    }

    private fun decode(frames: List<ByteArray>): AttributeResponse =
        requireNotNull(SocialCodec.decodeResponse(frames.reduce(ByteArray::plus)))
}
