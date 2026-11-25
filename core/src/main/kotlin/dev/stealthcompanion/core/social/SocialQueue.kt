package dev.stealthcompanion.core.social

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
    private val conversationHistoryTtlMs: Long = 300_000,
) {
    init {
        require(capacity > 0)
        require(ttlMs > 0 && coalesceMs in 0 until ttlMs)
        require(conversationHistoryTtlMs > 0)
    }

    private data class Pending(val message: SocialMessage, val digest: String, val readyAt: Long, val expiresAt: Long)
    private data class Dispatched(
        val digest: String,
        val expiresAt: Long,
        val conversationIdentities: Set<String>,
        val conversationExpiresAt: Long,
    ) {
        fun conversationIdentitiesAt(now: Long): Set<String> =
            conversationIdentities.takeIf { conversationExpiresAt > now }.orEmpty()
    }

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
        val sent = dispatched[message.key]
        val update = message.conversationUpdate
        val sentIdentities = sent?.conversationIdentitiesAt(now).orEmpty()
        val unseen = update?.entries?.filterNot { it.identity in sentIdentities }
        if (unseen != null && unseen.isEmpty()) {
            pending.remove(message.key)
            return duplicate()
        }
        val candidate = unseen?.let(message::renderConversation) ?: message
        val digest = fingerprint(candidate)
        if (previous?.digest == digest) return duplicate()
        if (update == null && sent != null && sent.expiresAt > now && sent.digest == digest) {
            // The source can revert to an alert already seen while an update waits in the queue.
            pending.remove(message.key)
            return duplicate()
        }
        if (previous == null && pending.size == capacity) {
            pending.remove(pending.keys.first())
            counts = counts.copy(overflow = counts.overflow + 1)
        }
        pending[message.key] = Pending(candidate, digest, previous?.readyAt ?: now + coalesceMs, now + ttlMs)
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
        val previous = dispatched.remove(next.message.key)
        val update = next.message.conversationUpdate
        val previousIdentities = previous?.conversationIdentitiesAt(now).orEmpty()
        // Consume every fresh identity, including the older context omitted by the display mode.
        val identities = (previousIdentities.toList() + update?.entries.orEmpty().map { it.identity })
            .takeLast(MAX_CONVERSATION_HISTORY).toSet()
        val historyExpiresAt = when {
            update != null -> now + conversationHistoryTtlMs
            identities.isNotEmpty() -> requireNotNull(previous).conversationExpiresAt
            else -> now
        }
        dispatched[next.message.key] = Dispatched(next.digest, now + ttlMs, identities, historyExpiresAt)
        if (dispatched.size > dispatchHistoryLimit) dispatched.remove(dispatched.keys.first())
        return if (update == null) next.message else next.message.copy(
            conversationUpdate = null,
            deliveryKey = "${next.message.key}:${next.digest}",
        )
    }

    @Synchronized
    fun nextReadyAt(now: Long): Long? {
        prune(now)
        return pending.values.firstOrNull()?.readyAt
    }

    @Synchronized
    fun nextExpirationAt(now: Long): Long? {
        prune(now)
        return pending.values.minOfOrNull { it.expiresAt }
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
        dispatched.values.removeAll { it.expiresAt <= now && it.conversationExpiresAt <= now }
        val expired = previousSize - pending.size
        if (expired > 0) counts = counts.copy(expired = counts.expired + expired)
    }

    private fun duplicate(): Boolean {
        counts = counts.copy(duplicates = counts.duplicates + 1)
        return false
    }

    private fun fingerprint(message: SocialMessage): String = socialDigest(
        message.title,
        message.body,
        message.packageName.orEmpty(),
        *message.conversationUpdate?.entries.orEmpty().map { it.identity }.toTypedArray(),
    )

    private companion object {
        const val MAX_CONVERSATION_HISTORY = 48
    }
}
