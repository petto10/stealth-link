package dev.stealthcompanion.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialProtocolTest {
    private val payload = SocialPayload("Titolo", "Corpo 🌊")

    @Test
    fun legacyEventMatchesObservedBytesAndUsesStableUid() {
        val session = SocialSession()
        val first = requireNotNull(session.publish("first", payload, 0, category = 8))
        val second = requireNotNull(session.publish("second", payload, 1))
        assertArrayEquals(bytes(0, 0x10, 4, 1, 0, 0, 0, 1), first)
        assertArrayEquals(bytes(0, 0x10, 4, 2, 0, 0, 0, 2), second)
        assertEquals(0x01000000, SocialCodec.decodeEvent(first)?.uid)
        assertNull(SocialCodec.decodeEvent(first.copyOf(7)))
        assertNull(SocialCodec.decodeEvent(first.copyOf().apply { this[0] = 3 }))
    }

    @Test
    fun controlPointUsesFourUidBytesAndTwoLittleEndianLengthBytes() {
        val request = AttributeRequest(0xFEDCBA98.toInt(), listOf(RequestedAttribute(SocialAttribute.MESSAGE, 256)))
        val encoded = SocialCodec.encodeRequest(request)
        assertArrayEquals(bytes(0, 0x98, 0xBA, 0xDC, 0xFE, 3, 0, 1), encoded)
        assertEquals(request, SocialCodec.decodeRequest(encoded))
        assertEquals(0, SocialCodec.decodeRequest(request(1, SocialAttribute.TITLE, 0))?.attributes?.single()?.maximumLength)
    }

    @Test
    fun malformedControlPointRequestsAreRejected() {
        for (malformed in listOf(
            ByteArray(0),
            bytes(0, 0, 0, 0, 1, 1),
            bytes(0, 0, 0, 0, 1, 3, 1),
            bytes(0, 0, 0, 0, 1, 9),
            bytes(2, 0, 0, 0, 1, 1, 8, 0),
            bytes(0, 0, 0, 0, 1, 1, 8, 0, 1, 12, 0),
            ByteArray(50),
        )) {
            assertNull(SocialCodec.decodeRequest(malformed))
        }
    }

    @Test
    fun responseHandlesReverseOrderAndRepeatsWithOriginalUid() {
        val session = SocialSession()
        val event = event(session, "one", payload)
        val bodyRequest = request(event.uid, SocialAttribute.MESSAGE, 60)
        val body = session.respond(bodyRequest, 23, 1).single()
        assertArrayEquals(
            bytes(0, 0, 0, 0, 1, 3, 10, 0) + "Corpo 🌊".toByteArray(),
            body,
        )
        assertArrayEquals(body, session.respond(bodyRequest, 23, 2).single())
        val title = session.respond(request(event.uid, SocialAttribute.TITLE, 60), 23, 3).single()
        assertEquals("Titolo", SocialCodec.decodeResponse(title)?.attributes?.single()?.value)
        val alteredUid = bodyRequest.copyOf().apply { this[1] = 1 }
        assertTrue(session.respond(alteredUid, 23, 4).isEmpty())
    }

    @Test
    fun responseHonorsEveryByteBudgetAndPreservesUnicode() {
        val session = SocialSession()
        val event = event(session, "utf8", SocialPayload("è", "èaa🌊👾z"))
        for ((maximum, expected) in listOf(0 to "", 1 to "", 2 to "è", 7 to "èaa", 8 to "èaa🌊", 500 to "èaa🌊👾")) {
            val reply = session.respond(request(event.uid, SocialAttribute.MESSAGE, maximum), 23, 0).single()
            assertEquals(expected, SocialCodec.decodeResponse(reply)?.attributes?.single()?.value)
            assertTrue(reply.size <= 20)
            assertTrue(reply.size - 8 <= maximum)
        }
        assertEquals("A", String(SocialCodec.encodeUtf8Within("Ae\u0301", 3), Charsets.UTF_8))
        assertEquals("Ae\u0301", String(SocialCodec.encodeUtf8Within("Ae\u0301", 4), Charsets.UTF_8))
        assertTrue(session.respond(request(event.uid, SocialAttribute.TITLE, 255), 22, 0).isEmpty())
        assertTrue(session.respond(request(event.uid, SocialAttribute.TITLE, 255), 518, 0).isEmpty())
    }

    @Test
    fun legacyCapAndLittleEndianLengthBoundaryDifferOnlyWhenOptedIn() {
        val text = "x".repeat(256)
        val input = AttributeRequest(1, listOf(RequestedAttribute(SocialAttribute.MESSAGE, 256)))
        val reply = SocialCodec.encodeResponse(input, SocialPayload("", text), 517).single()
        assertEquals(255, reply[6].toInt() and 255)
        assertEquals(0, reply[7].toInt())

        val fragments = SocialCodec.encodeResponse(
            input, SocialPayload("", text), 23,
            ProtocolOptions(allowFragmentation = true, maxAttributeBytes = 256),
        )
        assertTrue(fragments.size > 1)
        assertTrue(fragments.all { it.size <= 20 })
        val assembled = fragments.reduce(ByteArray::plus)
        assertEquals(0, assembled[6].toInt())
        assertEquals(1, assembled[7].toInt())
        assertEquals(text, SocialCodec.decodeResponse(assembled)?.attributes?.single()?.value)
    }

    @Test
    fun rejectsMalformedResponsesAndUnsupportedAttributes() {
        assertNull(SocialCodec.decodeResponse(bytes(0, 0, 0, 0, 1, 1, 2, 0, 0xC3)))
        assertNull(SocialCodec.decodeResponse(bytes(0, 0, 0, 0, 1, 1, 1, 0, 0xC3)))
        assertNull(SocialCodec.decodeResponse(bytes(0, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0)))
        val session = SocialSession()
        val event = event(session, "one", payload)
        assertTrue(session.respond(
            SocialCodec.encodeRequest(AttributeRequest(event.uid, listOf(RequestedAttribute(SocialAttribute.APP_IDENTIFIER)))),
            23, 1,
        ).isEmpty())
    }

    @Test
    fun legacySessionExpiresRecordsAndAvoidsAmbiguousUidReuseDuringTtl() {
        val session = SocialSession(maxRecords = 2, ttlMillis = 100)
        val first = event(session, "0", payload)
        for (index in 1..255) event(session, "$index", payload)
        assertNull(session.publish("extra", payload, 99))
        assertTrue(session.respond(request(first.uid, SocialAttribute.MESSAGE, 255), 23, 99).isEmpty())
        val next = event(session, "extra", payload, nowMs = 100)
        assertEquals(first.uid, next.uid)
        assertTrue(session.respond(request(next.uid, SocialAttribute.MESSAGE, 255), 23, 200).isEmpty())
        session.reset()
        assertEquals(first.uid, event(session, "new", payload, nowMs = 200).uid)
    }

    @Test
    fun repeatedContentIsSuppressedAndDefaultUpdatesAreNewEvents() {
        val session = SocialSession()
        val first = event(session, "same", payload)
        assertNull(session.publish("same", payload, 1))
        val second = event(session, "same", payload.copy(text = "New"), 2)
        assertEquals(SocialEventId.ADDED, second.event)
        assertFalse(first.uid == second.uid)
        assertNull(session.remove("same", 3))
        assertTrue(session.respond(request(second.uid, SocialAttribute.MESSAGE, 20), 23, 3).isEmpty())
    }

    @Test
    fun simulatorShowsLegacyTruncationAndSupportsReverseRequests() {
        val session = SocialSession()
        val simulator = ControllerSimulator(reverseOrder = true, requestedTitleBytes = 4)
        val body = "1234567890😀"
        val frame = requireNotNull(session.publish("one", SocialPayload("Città", body), 0))
        val result = simulator.receive(frame) { request, mtu -> session.respond(request, mtu, 1) }
        assertEquals("Citt", result?.title)
        assertEquals("1234567890", result?.text)
        assertEquals(listOf(result), simulator.displayed)
        assertNull(simulator.receive(frame) { request, mtu ->
            session.respond(request.copyOf().apply { this[1] = 9 }, mtu, 2)
        })
    }

    @Test
    fun experimentalSimulatorExercisesCategoriesFragmentsAppIdUpdateAndRemove() {
        val options = ProtocolOptions(
            allowFragmentation = true,
            allowMultipleAttributes = true,
            useFullUid = true,
            allowUpdates = true,
            allowRemovals = true,
            allowCustomCategories = true,
            allowApplicationIdentifier = true,
            maxAttributeBytes = 1024,
        )
        val session = SocialSession(options)
        val simulator = ControllerSimulator(
            requestedMessageBytes = 1024,
            requestTogether = true,
            requestApplicationIdentifier = true,
            reverseOrder = true,
        )
        val long = "È 🌊".repeat(50)
        val first = requireNotNull(session.publish("one", SocialPayload("Titolo", long, "dev.sample", "Demo"), 0, 7))
        val delivered = simulator.receive(first) { request, mtu -> session.respond(request, mtu, 1) }
        assertNotNull(delivered)
        assertEquals(1, delivered?.uid)
        assertEquals(7, delivered?.category)
        assertEquals(long, delivered?.text)
        assertEquals("dev.sample", delivered?.appIdentifier)

        val appRequest = bytes(1) + "dev.sample".toByteArray() + bytes(0, 0)
        val appResponse = session.respond(appRequest, 23, 1).single()
        assertArrayEquals(bytes(1) + "dev.sample".toByteArray() + bytes(0, 0, 4, 0) + "Demo".toByteArray(), appResponse)
        val unknownRequest = bytes(1) + "dev.other".toByteArray() + bytes(0, 0)
        assertTrue(session.respond(unknownRequest, 23, 1).isEmpty())

        val update = requireNotNull(session.publish("one", SocialPayload("Nuovo", "Contenuto"), 2, 7))
        assertEquals(SocialEventId.MODIFIED, SocialCodec.decodeEvent(update)?.event)
        assertEquals(delivered?.uid, SocialCodec.decodeEvent(update)?.uid)
        simulator.receive(update) { request, mtu -> session.respond(request, mtu, 3) }
        assertEquals("Nuovo", simulator.displayed.single().title)

        val remove = requireNotNull(session.remove("one", 4))
        assertEquals(SocialEventId.REMOVED, SocialCodec.decodeEvent(remove)?.event)
        simulator.receive(remove) { _, _ -> error("Removal requires no attribute request") }
        assertTrue(simulator.displayed.isEmpty())
    }

    private fun event(session: SocialSession, key: String, payload: SocialPayload, nowMs: Long = 0): SocialEvent =
        requireNotNull(SocialCodec.decodeEvent(requireNotNull(session.publish(key, payload, nowMs))))

    private fun request(uid: Int, attribute: SocialAttribute, max: Int): ByteArray =
        SocialCodec.encodeRequest(AttributeRequest(uid, listOf(RequestedAttribute(attribute, max))))

    private fun bytes(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }
}
