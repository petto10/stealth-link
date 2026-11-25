package dev.stealthcompanion.core.protocol

import java.io.ByteArrayOutputStream

data class SimulatedNotification(
    val uid: Int,
    val title: String,
    val text: String,
    val appIdentifier: String?,
    val category: Int,
)

/**
 * An in-process GATT peer for testing the data exchange, not the physical Command Display.
 * The limits and attribute request order are adjustable test inputs.
 */
class ControllerSimulator(
    private val mtu: Int = SocialCodec.DEFAULT_MTU,
    private val requestedTitleBytes: Int = 255,
    private val requestedMessageBytes: Int = 255,
    private val requestTogether: Boolean = false,
    private val requestApplicationIdentifier: Boolean = false,
    private val reverseOrder: Boolean = false,
    private val feedCapacity: Int = 8,
) {
    init {
        require(mtu in 23..517)
        require(requestedTitleBytes in 0..65535 && requestedMessageBytes in 0..65535)
        require(feedCapacity > 0)
    }

    private val feed = LinkedHashMap<Int, SimulatedNotification>()

    val displayed: List<SimulatedNotification>
        get() = feed.values.toList()

    fun receive(
        eventFrame: ByteArray,
        respond: (ByteArray, Int) -> List<ByteArray>,
    ): SimulatedNotification? {
        val event = SocialCodec.decodeEvent(eventFrame) ?: return null
        if (event.event == SocialEventId.REMOVED) {
            feed.remove(event.uid)
            return null
        }
        if (event.event == SocialEventId.MODIFIED && event.uid !in feed) return null
        val requested = buildList {
            add(RequestedAttribute(SocialAttribute.TITLE, requestedTitleBytes))
            add(RequestedAttribute(SocialAttribute.MESSAGE, requestedMessageBytes))
            if (requestApplicationIdentifier) add(RequestedAttribute(SocialAttribute.APP_IDENTIFIER))
        }.let { if (reverseOrder) it.reversed() else it }
        val requests = if (requestTogether) listOf(requested) else requested.map(::listOf)
        val received = mutableMapOf<SocialAttribute, String>()
        for (attributes in requests) {
            val request = SocialCodec.encodeRequest(AttributeRequest(event.uid, attributes))
            val packets = respond(request, mtu)
            if (packets.isEmpty() || packets.any { it.isEmpty() || it.size > minOf(mtu - 3, 512) }) return null
            val data = ByteArrayOutputStream()
            packets.forEach { data.write(it) }
            val answer = SocialCodec.decodeResponse(data.toByteArray()) ?: return null
            if (answer.uid != event.uid || answer.attributes.map { it.attribute } != attributes.map { it.attribute }) {
                return null
            }
            for ((attribute, value) in answer.attributes) received[attribute] = value
        }
        val notification = SimulatedNotification(
            event.uid,
            received.getValue(SocialAttribute.TITLE),
            received.getValue(SocialAttribute.MESSAGE),
            received[SocialAttribute.APP_IDENTIFIER],
            event.category,
        )
        if (event.uid !in feed && feed.size == feedCapacity) feed.remove(feed.keys.first())
        feed[event.uid] = notification
        return notification
    }

    fun reset() {
        feed.clear()
    }
}
