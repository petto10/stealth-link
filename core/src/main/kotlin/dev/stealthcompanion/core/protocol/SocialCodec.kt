package dev.stealthcompanion.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.UUID
import java.util.regex.Pattern

enum class SocialEventId(val id: Int) {
    ADDED(0), MODIFIED(1), REMOVED(2),
}

data class SocialEvent(
    val event: SocialEventId,
    val flags: Int,
    val category: Int,
    val categoryCount: Int,
    val uid: Int,
)

enum class SocialAttribute(val id: Int, val hasMaximum: Boolean) {
    APP_IDENTIFIER(0, false),
    TITLE(1, true),
    MESSAGE(3, true),
}

data class RequestedAttribute(val attribute: SocialAttribute, val maximumLength: Int? = null)
data class AttributeRequest(val uid: Int, val attributes: List<RequestedAttribute>)
data class AttributeValue(val attribute: SocialAttribute, val value: String)
data class AttributeResponse(val uid: Int, val attributes: List<AttributeValue>)

data class SocialPayload(
    val title: String,
    val text: String,
    val appIdentifier: String? = null,
    val applicationName: String? = null,
)

/** Defaults describe the subset observed in the original Android app. */
data class ProtocolOptions(
    val allowFragmentation: Boolean = false,
    val allowMultipleAttributes: Boolean = false,
    val useFullUid: Boolean = false,
    val allowUpdates: Boolean = false,
    val allowRemovals: Boolean = false,
    val allowCustomCategories: Boolean = false,
    val allowApplicationIdentifier: Boolean = false,
    val maxAttributeBytes: Int = 255,
) {
    init {
        require(maxAttributeBytes in 1..4096)
    }
}

object SocialCodec {
    val SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val SOCIAL_CATEGORY = 4
    const val LEGACY_EVENT_FLAGS = 0x10
    const val DEFAULT_MTU = 23
    private const val GET_NOTIFICATION_ATTRIBUTES = 0
    private const val GET_APP_ATTRIBUTES = 1
    private const val ATTRIBUTE_HEADER_SIZE = 3
    private const val RESPONSE_HEADER_SIZE = 5
    private const val MAX_GATT_VALUE = 512
    private const val MAX_IDENTIFIER_BYTES = 128
    private val grapheme = Pattern.compile("\\X")

    fun encodeEvent(event: SocialEvent): ByteArray {
        require(event.flags in 0..255 && event.category in 0..255 && event.categoryCount in 0..255)
        return ByteArrayOutputStream(8).apply {
            write(event.event.id)
            write(event.flags)
            write(event.category)
            write(event.categoryCount)
            writeLittleEndian(event.uid, 4)
        }.toByteArray()
    }

    fun decodeEvent(data: ByteArray): SocialEvent? {
        if (data.size != 8) return null
        val event = SocialEventId.entries.firstOrNull { it.id == data.unsigned(0) } ?: return null
        return SocialEvent(event, data.unsigned(1), data.unsigned(2), data.unsigned(3), data.littleEndian(4, 4))
    }

    fun encodeRequest(request: AttributeRequest): ByteArray {
        require(request.attributes.isNotEmpty() && request.attributes.distinctBy { it.attribute } == request.attributes)
        return ByteArrayOutputStream().apply {
            write(GET_NOTIFICATION_ATTRIBUTES)
            writeLittleEndian(request.uid, 4)
            for (requested in request.attributes) {
                write(requested.attribute.id)
                if (requested.attribute.hasMaximum) {
                    val maximum = requireNotNull(requested.maximumLength)
                    require(maximum in 0..65535)
                    writeLittleEndian(maximum, 2)
                } else {
                    require(requested.maximumLength == null)
                }
            }
        }.toByteArray()
    }

    fun decodeRequest(data: ByteArray): AttributeRequest? {
        if (data.size !in 6..12 || data.unsigned(0) != GET_NOTIFICATION_ATTRIBUTES) return null
        val items = mutableListOf<RequestedAttribute>()
        var offset = 5
        while (offset < data.size) {
            val attribute = attribute(data.unsigned(offset++)) ?: return null
            if (items.any { it.attribute == attribute }) return null
            if (attribute.hasMaximum && offset + 2 > data.size) return null
            val maximum = if (attribute.hasMaximum) data.littleEndian(offset, 2) else null
            items.add(RequestedAttribute(attribute, maximum))
            if (maximum != null) offset += 2
        }
        return AttributeRequest(data.littleEndian(1, 4), items)
    }

    fun encodeResponse(
        request: AttributeRequest,
        payload: SocialPayload,
        mtu: Int,
        options: ProtocolOptions = ProtocolOptions(),
    ): List<ByteArray> {
        val frameLimit = notificationValueLimit(mtu) ?: return emptyList()
        if (request.attributes.isEmpty() || request.attributes.distinctBy { it.attribute } != request.attributes ||
            (!options.allowMultipleAttributes && request.attributes.size != 1)) return emptyList()
        val limit = if (options.allowFragmentation) options.maxAttributeBytes else options.maxAttributeBytes.coerceAtMost(255)
        val stream = ByteArrayOutputStream()
        stream.write(GET_NOTIFICATION_ATTRIBUTES)
        stream.writeLittleEndian(request.uid, 4)
        for ((index, item) in request.attributes.withIndex()) {
            val value = when (item.attribute) {
                SocialAttribute.TITLE -> payload.title
                SocialAttribute.MESSAGE -> payload.text
                SocialAttribute.APP_IDENTIFIER -> {
                    if (!options.allowApplicationIdentifier) return emptyList()
                    payload.appIdentifier.orEmpty()
                }
            }
            val remainingHeaders = (request.attributes.size - index) * ATTRIBUTE_HEADER_SIZE
            val budget = if (options.allowFragmentation) limit else {
                if (frameLimit < stream.size() + remainingHeaders) return emptyList()
                (frameLimit - stream.size() - remainingHeaders).coerceAtMost(limit)
            }
            val requestedLimit = if (item.attribute.hasMaximum) {
                item.maximumLength?.takeIf { it in 0..65535 } ?: return emptyList()
            } else {
                if (item.maximumLength != null) return emptyList()
                MAX_IDENTIFIER_BYTES
            }
            // An application identifier is opaque. A partial identifier would point to a different app.
            val bytes = if (item.attribute == SocialAttribute.APP_IDENTIFIER) {
                value.takeUnless { '\u0000' in it }?.toByteArray(Charsets.UTF_8)
                    ?.takeIf { it.size <= minOf(budget, requestedLimit) }
                    ?: ByteArray(0)
            } else {
                encodeUtf8Within(value, minOf(budget, requestedLimit))
            }
            stream.write(item.attribute.id)
            stream.writeLittleEndian(bytes.size, 2)
            stream.write(bytes)
        }
        // ANCS splits one logical response. Only its first ATT value starts with the command and UID.
        return splitFrames(stream.toByteArray(), frameLimit)
    }

    /** Decode a complete response after any ATT fragments have been reassembled. */
    fun decodeResponse(data: ByteArray): AttributeResponse? {
        if (data.size < RESPONSE_HEADER_SIZE + ATTRIBUTE_HEADER_SIZE ||
            data.unsigned(0) != GET_NOTIFICATION_ATTRIBUTES) return null
        val items = mutableListOf<AttributeValue>()
        var offset = RESPONSE_HEADER_SIZE
        while (offset < data.size) {
            if (offset + ATTRIBUTE_HEADER_SIZE > data.size) return null
            val attribute = attribute(data.unsigned(offset)) ?: return null
            if (items.any { it.attribute == attribute }) return null
            val length = data.littleEndian(offset + 1, 2)
            offset += ATTRIBUTE_HEADER_SIZE
            if (length > data.size - offset) return null
            val value = decodeUtf8(data, offset, length) ?: return null
            items.add(AttributeValue(attribute, value))
            offset += length
        }
        return AttributeResponse(data.littleEndian(1, 4), items)
    }

    internal fun decodeAppIdentifier(data: ByteArray): String? {
        if (data.size !in 4..MAX_IDENTIFIER_BYTES + 3 || data.unsigned(0) != GET_APP_ATTRIBUTES ||
            data[data.lastIndex - 1] != 0.toByte() || data[data.lastIndex] != 0.toByte()) return null
        val identifier = decodeUtf8(data, 1, data.size - 3) ?: return null
        return identifier.takeIf { '\u0000' !in it }
    }

    internal fun encodeAppName(identifier: String, name: String, mtu: Int, options: ProtocolOptions): List<ByteArray> {
        val frameLimit = notificationValueLimit(mtu) ?: return emptyList()
        val identifierBytes = identifier.toByteArray(Charsets.UTF_8)
        val headerSize = identifierBytes.size + 5
        val budget = if (options.allowFragmentation) options.maxAttributeBytes else frameLimit - headerSize
        if (budget < 0) return emptyList()
        val nameBytes = encodeUtf8Within(name, minOf(budget, options.maxAttributeBytes))
        val response = ByteArrayOutputStream().apply {
            write(GET_APP_ATTRIBUTES)
            write(identifierBytes)
            write(0) // NUL terminates the application identifier.
            write(0) // Display name attribute.
            writeLittleEndian(nameBytes.size, 2)
            write(nameBytes)
        }.toByteArray()
        return splitFrames(response, frameLimit)
    }

    /** ATT Handle Value Notification consumes one opcode byte and two attribute handle bytes. */
    fun notificationValueLimit(mtu: Int): Int? =
        mtu.takeIf { it in DEFAULT_MTU..517 }?.let { (it - 3).coerceAtMost(MAX_GATT_VALUE) }

    private fun splitFrames(bytes: ByteArray, maximum: Int): List<ByteArray> =
        (0 until bytes.size step maximum).map { bytes.copyOfRange(it, minOf(it + maximum, bytes.size)) }

    private fun attribute(id: Int): SocialAttribute? = SocialAttribute.entries.firstOrNull { it.id == id }

    private fun ByteArray.unsigned(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.littleEndian(start: Int, length: Int): Int {
        var result = 0
        for (index in 0 until length) result = result or (unsigned(start + index) shl (8 * index))
        return result
    }

    private fun ByteArrayOutputStream.writeLittleEndian(value: Int, length: Int) {
        for (index in 0 until length) write(value ushr (index * 8))
    }

    private fun decodeUtf8(bytes: ByteArray, offset: Int, length: Int): String? = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, offset, length)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Encode a bounded prefix without splitting extended grapheme clusters. */
    fun encodeUtf8Within(value: String, maximumBytes: Int): ByteArray {
        if (maximumBytes <= 0 || value.isEmpty()) return ByteArray(0)
        val characters = grapheme.matcher(value)
        val result = ByteArrayOutputStream(minOf(maximumBytes, 512))
        while (characters.find()) {
            // UTF-8 encoding needs at least as many bytes as the number of UTF-16 units.
            if (characters.end() - characters.start() > maximumBytes - result.size()) break
            val segment = characters.group().toByteArray(Charsets.UTF_8)
            if (segment.size > maximumBytes - result.size()) break
            result.write(segment)
        }
        return result.toByteArray()
    }
}
