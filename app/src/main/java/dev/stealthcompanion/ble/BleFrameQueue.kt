package dev.stealthcompanion.ble

/**
 * Android gives each server only the status of its last notification.
 * Keep exactly one packet in flight, and append each response as a contiguous batch.
 */
internal class BleFrameQueue<T : Any>(
    private val maxFrames: Int,
    private val maxBytes: Int,
    private val bytes: (T) -> Int,
) {
    private data class Entry<T>(val frame: T, val batch: Any, val final: Boolean, val spacingMillis: Long)

    private val queued = ArrayDeque<Entry<T>>()
    private var retainedBytes = 0
    private var activeBatch: Any? = null
    private var activeEntry: Entry<T>? = null
    private var continuationAtMillis = 0L
    private var resetRequired = false

    val inFlight: T? get() = activeEntry?.frame
    val hasStartedBatch: Boolean get() = activeBatch != null
    val startedFrame: T? get() = inFlight ?: queued.firstOrNull()?.takeIf { it.batch === activeBatch }?.frame

    fun offerBatch(frames: List<T>, spacingMillis: Long = 0): Boolean {
        require(spacingMillis >= 0)
        val byteCount = frames.sumOf(bytes)
        val retainedFrames = queued.size + if (activeEntry == null) 0 else 1
        if (resetRequired || frames.isEmpty() || retainedFrames + frames.size > maxFrames ||
            byteCount > maxBytes - retainedBytes
        ) {
            return false
        }
        val batch = Any()
        frames.forEachIndexed { index, frame ->
            queued.addLast(Entry(frame, batch, index == frames.lastIndex, spacingMillis))
        }
        retainedBytes += byteCount
        return true
    }

    fun remainingDelayMillis(nowMillis: Long): Long {
        if (resetRequired || activeEntry != null || activeBatch == null || queued.firstOrNull()?.batch !== activeBatch) {
            return 0
        }
        return (continuationAtMillis - nowMillis).coerceAtLeast(0)
    }

    fun launchNext(nowMillis: Long = 0): T? {
        if (resetRequired || activeEntry != null) return null
        if (remainingDelayMillis(nowMillis) > 0) return null
        val entry = queued.removeFirstOrNull() ?: return null
        activeBatch = entry.batch
        activeEntry = entry
        return entry.frame
    }

    fun complete(callbackAtMillis: Long = 0): T? {
        val entry = activeEntry ?: return null
        retainedBytes -= bytes(entry.frame)
        activeEntry = null
        if (entry.final) {
            activeBatch = null
            continuationAtMillis = 0
        } else {
            continuationAtMillis = callbackAtMillis + entry.spacingMillis
        }
        return entry.frame
    }

    fun any(predicate: (T) -> Boolean): Boolean =
        inFlight?.let(predicate) == true || queued.any { predicate(it.frame) }

    /** Returns true if a continuation of a started batch was discarded. */
    fun discardQueued(predicate: (T) -> Boolean): Boolean {
        var interrupted = false
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.frame)) {
                retainedBytes -= bytes(entry.frame)
                iterator.remove()
                if (entry.batch === activeBatch) interrupted = true
            }
        }
        if (interrupted) resetRequired = true
        return interrupted
    }

    fun clear() {
        queued.clear()
        activeEntry = null
        activeBatch = null
        continuationAtMillis = 0
        resetRequired = false
        retainedBytes = 0
    }
}
