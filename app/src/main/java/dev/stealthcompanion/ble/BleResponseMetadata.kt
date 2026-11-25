package dev.stealthcompanion.ble

/** Reads only protocol headers. The export never receives any attribute bytes, UIDs, or identifiers. */
internal object BleResponseMetadata {
    fun isAppNameRequest(request: ByteArray): Boolean = request.size >= 4 &&
        request[0].unsigned() == APPLICATION_ATTRIBUTES && request[request.lastIndex].unsigned() == DISPLAY_NAME_ATTRIBUTE &&
        request.indexOf(0) == request.lastIndex - 1

    fun payloadSizes(
        frames: List<ByteArray>,
        attributes: List<BleAttributeDiagnostic>,
        command: Int? = NOTIFICATION_ATTRIBUTES,
    ): List<BleAttributeDiagnostic> {
        if (attributes.isEmpty()) return emptyList()
        val joined = ByteArray(frames.sumOf(ByteArray::size))
        var position = 0
        for (frame in frames) {
            frame.copyInto(joined, position)
            position += frame.size
        }
        if (joined.firstOrNull()?.unsigned() != command || command == null) return attributes
        position = when (command) {
            NOTIFICATION_ATTRIBUTES -> COMMAND_AND_UID_BYTES
            APPLICATION_ATTRIBUTES -> {
                val separator = joined.indexOf(0)
                if (separator < 2) return attributes
                separator + 1
            }
            else -> return attributes
        }
        val lengths = HashMap<Int, Int>(attributes.size)
        for (expected in attributes) {
            if (joined.size - position < ATTRIBUTE_HEADER_BYTES || joined[position].unsigned() != expected.id) {
                return attributes
            }
            val size = joined[position + 1].unsigned() or (joined[position + 2].unsigned() shl 8)
            position += ATTRIBUTE_HEADER_BYTES
            if (size > joined.size - position) return attributes
            lengths[expected.id] = size
            position += size
        }
        if (position != joined.size) return attributes
        return attributes.map { it.copy(payloadBytes = lengths[it.id]) }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff

    const val NOTIFICATION_ATTRIBUTES = 0
    const val APPLICATION_ATTRIBUTES = 1
    const val DISPLAY_NAME_ATTRIBUTE = 0

    private const val COMMAND_AND_UID_BYTES = 5
    private const val ATTRIBUTE_HEADER_BYTES = 3
}
