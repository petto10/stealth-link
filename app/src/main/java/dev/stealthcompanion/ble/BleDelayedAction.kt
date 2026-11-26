package dev.stealthcompanion.ble

/** A replaceable one-shot on a single event loop. Invalidated callbacks are harmless if delivered late. */
internal class BleDelayedAction(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private var pending: Runnable? = null

    val isPending: Boolean get() = pending != null

    fun replace(delayMs: Long, action: () -> Unit) {
        cancel()
        val task = object : Runnable {
            override fun run() {
                if (pending !== this) return
                pending = null
                action()
            }
        }
        pending = task
        post(task, delayMs)
    }

    fun cancel() {
        val task = pending
        pending = null
        task?.let(remove)
    }
}
