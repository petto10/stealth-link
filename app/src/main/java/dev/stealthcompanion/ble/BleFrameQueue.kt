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
    private data class Entry<T>(val frame: T, val batch: Any, val final: Boolean)

    private val queued = ArrayDeque<Entry<T>>()
    private var retainedBytes = 0
    private var activeBatch: Any? = null
    private var activeEntry: Entry<T>? = null
    private var resetRequired = false

    val inFlight: T? get() = activeEntry?.frame

    fun offerBatch(frames: List<T>): Boolean {
        val byteCount = frames.sumOf(bytes)
        val retainedFrames = queued.size + if (activeEntry == null) 0 else 1
        if (resetRequired || frames.isEmpty() || retainedFrames + frames.size > maxFrames ||
            byteCount > maxBytes - retainedBytes
        ) {
            return false
        }
        val batch = Any()
        frames.forEachIndexed { index, frame -> queued.addLast(Entry(frame, batch, index == frames.lastIndex)) }
        retainedBytes += byteCount
        return true
    }

    fun launchNext(): T? {
        if (resetRequired || activeEntry != null) return null
        val entry = queued.removeFirstOrNull() ?: return null
        activeBatch = entry.batch
        activeEntry = entry
        return entry.frame
    }

    fun complete(): T? {
        val entry = activeEntry ?: return null
        retainedBytes -= bytes(entry.frame)
        activeEntry = null
        if (entry.final) activeBatch = null
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
        resetRequired = false
        retainedBytes = 0
    }
}
