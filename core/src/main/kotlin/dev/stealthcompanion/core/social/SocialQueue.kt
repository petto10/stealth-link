package dev.stealthcompanion.core.social

import java.nio.ByteBuffer
import java.security.MessageDigest

data class SocialQueueStats(
    val accepted: Long = 0,
    val coalesced: Long = 0,
    val duplicates: Long = 0,
    val expired: Long = 0,
    val overflow: Long = 0,
)

/** Holds only a short in-memory backlog. All time arguments use the same monotonic clock. */
class SocialQueue(
    private val capacity: Int = 32,
    private val ttlMs: Long = 30_000,
    private val coalesceMs: Long = 350,
) {
    init {
        require(capacity > 0)
        require(ttlMs > 0 && coalesceMs in 0 until ttlMs)
    }

    private data class Pending(val message: SocialMessage, val digest: ByteArray, val readyAt: Long, val expiresAt: Long)
    private data class Dispatched(val digest: ByteArray, val expiresAt: Long)

    private val pending = LinkedHashMap<String, Pending>()
    private val dispatched = LinkedHashMap<String, Dispatched>()
    private val dispatchHistoryLimit = maxOf(capacity, 128)
    private var counts = SocialQueueStats()

    val size: Int @Synchronized get() = pending.size
    val stats: SocialQueueStats @Synchronized get() = counts

    /** Enqueues an addition or replaces an update in place, preserving its original position and ready time. */
    @Synchronized
    fun offer(message: SocialMessage, now: Long): Boolean {
        prune(now)
        val previous = pending[message.key]
        val digest = fingerprint(message)
        if (previous?.digest?.contentEquals(digest) == true) {
            counts = counts.copy(duplicates = counts.duplicates + 1)
            return false
        }
        if (dispatched[message.key]?.digest?.contentEquals(digest) == true) {
            // Android can revert an update while it is waiting to be sent.
            pending.remove(message.key)
            counts = counts.copy(duplicates = counts.duplicates + 1)
            return false
        }
        if (previous == null && pending.size == capacity) {
            pending.remove(pending.keys.first())
            counts = counts.copy(overflow = counts.overflow + 1)
        }
        pending[message.key] = Pending(message, digest, previous?.readyAt ?: now + coalesceMs, now + ttlMs)
        counts = if (previous == null) counts.copy(accepted = counts.accepted + 1)
        else counts.copy(coalesced = counts.coalesced + 1)
        return true
    }

    /** Marks the event as dispatched even if a later BLE callback has an ambiguous result. */
    @Synchronized
    fun poll(now: Long): SocialMessage? {
        prune(now)
        val next = pending.values.firstOrNull()?.takeIf { it.readyAt <= now } ?: return null
        pending.remove(next.message.key)
        dispatched.remove(next.message.key)
        dispatched[next.message.key] = Dispatched(next.digest, now + ttlMs)
        if (dispatched.size > dispatchHistoryLimit) dispatched.remove(dispatched.keys.first())
        return next.message
    }

    @Synchronized
    fun nextReadyAt(now: Long): Long? {
        prune(now)
        return pending.values.firstOrNull()?.readyAt
    }

    @Synchronized
    fun remove(key: String): Boolean {
        dispatched.remove(key)
        return pending.remove(key) != null
    }

    @Synchronized
    fun clear() {
        pending.clear()
        dispatched.clear()
    }

    @Synchronized
    fun expire(now: Long) {
        prune(now)
    }

    private fun prune(now: Long) {
        val previousSize = pending.size
        pending.values.removeAll { it.expiresAt <= now }
        dispatched.values.removeAll { it.expiresAt <= now }
        val expired = previousSize - pending.size
        if (expired > 0) counts = counts.copy(expired = counts.expired + expired)
    }

    private fun fingerprint(message: SocialMessage): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in listOf(message.title, message.body, message.packageName.orEmpty())) {
            val value = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
            digest.update(value)
        }
        return digest.digest()
    }
}
