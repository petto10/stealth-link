package dev.stealthcompanion.core.protocol

/**
 * Records belong to one GATT session. The transport owns this object from one serialized
 * execution context and serializes all outbound characteristic notifications.
 */
class SocialSession(
    private val options: ProtocolOptions = ProtocolOptions(),
    private val maxRecords: Int = 32,
    private val ttlMillis: Long = 30_000,
) {
    init {
        require(maxRecords in 1..255)
        require(ttlMillis in 1..MAX_PERSONAL_RETENTION_MS)
    }

    private data class Record(
        val key: String,
        val payload: SocialPayload,
        val event: SocialEvent,
        val expiresAt: Long,
        val retainMillis: Long,
        val allowLongResponse: Boolean,
    )

    private val records = LinkedHashMap<Int, Record>()
    private val legacyReservedUntil = LongArray(256) { Long.MIN_VALUE }
    private val longResponseOptions = options.copy(
        allowFragmentation = true,
        allowMultipleAttributes = true,
        maxAttributeBytes = if (options.allowFragmentation) options.maxAttributeBytes else 1024,
    )
    private var sequence = 0

    /**
     * The long response preference is captured per record without changing its event or UID.
     * Callers may reserve a record beyond the normal lifetime for an explicitly synthetic probe.
     */
    fun publish(
        key: String,
        payload: SocialPayload,
        nowMs: Long,
        category: Int = SocialCodec.SOCIAL_CATEGORY,
        allowLongResponse: Boolean = false,
        retainMillis: Long = ttlMillis,
    ): ByteArray? {
        require(category in 0..11)
        require(retainMillis in 1..MAX_PROBE_RETENTION_MS)
        expire(nowMs)
        val recordedCategory = if (options.allowCustomCategories) category else SocialCodec.SOCIAL_CATEGORY
        val existing = records.values.lastOrNull { it.key == key }
        if (existing?.payload == payload && existing.event.category == recordedCategory &&
            existing.allowLongResponse == allowLongResponse && existing.retainMillis == retainMillis) return null
        val event = if (existing != null && options.allowUpdates) {
            existing.event.copy(event = SocialEventId.MODIFIED, category = recordedCategory)
        } else {
            val uid = allocateUid(nowMs) ?: return null
            val count = if (options.useFullUid) {
                (records.values.count { it.event.category == recordedCategory } + 1).coerceAtMost(255)
            } else {
                uid ushr 24
            }
            SocialEvent(SocialEventId.ADDED, SocialCodec.LEGACY_EVENT_FLAGS, recordedCategory, count, uid)
        }
        records.remove(event.uid)
        if (records.size >= maxRecords) records.remove(records.keys.first())
        val expiresAt = deadline(nowMs, retainMillis)
        records[event.uid] = Record(key, payload, event, expiresAt, retainMillis, allowLongResponse)
        reserveLegacyUid(event.uid, expiresAt)
        return SocialCodec.encodeEvent(event)
    }

    fun remove(key: String, nowMs: Long): ByteArray? {
        expire(nowMs)
        val recent = records.values.lastOrNull { it.key == key }
        records.entries.removeAll { it.value.key == key }
        return if (recent != null && options.allowRemovals) {
            reserveLegacyUid(recent.event.uid, deadline(nowMs, recent.retainMillis))
            SocialCodec.encodeEvent(recent.event.copy(event = SocialEventId.REMOVED))
        } else {
            null
        }
    }

    fun forget(key: String, nowMs: Long) {
        expire(nowMs)
        records.entries.removeAll { it.value.key == key }
    }

    fun respond(request: ByteArray, mtu: Int, nowMs: Long): List<ByteArray> {
        expire(nowMs)
        val parsed = SocialCodec.decodeRequest(request)
        if (parsed != null) {
            val record = records[parsed.uid] ?: return emptyList()
            val responseOptions = if (record.allowLongResponse) longResponseOptions else options
            return SocialCodec.encodeResponse(parsed, record.payload, mtu, responseOptions)
        }
        if (!options.allowApplicationIdentifier) return emptyList()
        val identifier = SocialCodec.decodeAppIdentifier(request) ?: return emptyList()
        val record = records.values.lastOrNull { it.payload.appIdentifier == identifier } ?: return emptyList()
        return SocialCodec.encodeAppName(identifier, record.payload.applicationName.orEmpty(), mtu, options)
    }

    fun reset() {
        records.clear()
        legacyReservedUntil.fill(Long.MIN_VALUE)
        sequence = 0
    }

    fun expire(nowMs: Long) {
        records.entries.removeAll { nowMs >= it.value.expiresAt }
    }

    private fun allocateUid(nowMs: Long): Int? {
        if (options.useFullUid) {
            do {
                sequence++
            } while (records.containsKey(sequence))
            return sequence
        }
        repeat(256) {
            sequence = (sequence + 1) and 255
            if (nowMs >= legacyReservedUntil[sequence]) {
                val uid = sequence shl 24
                if (uid !in records) return uid
            }
        }
        // A late request must not be confused with one of the most recent 256 events.
        return null
    }

    private fun reserveLegacyUid(uid: Int, until: Long) {
        if (options.useFullUid) return
        val index = uid ushr 24
        legacyReservedUntil[index] = maxOf(legacyReservedUntil[index], until)
    }

    private fun deadline(nowMs: Long, interval: Long): Long =
        if (nowMs > Long.MAX_VALUE - interval) Long.MAX_VALUE else nowMs + interval

    companion object {
        const val MAX_PERSONAL_RETENTION_MS = 30_000L
        const val MAX_PROBE_RETENTION_MS = 120_000L
    }
}
