package dev.stealthcompanion.ble

internal class BleReconnectBackoff {
    private var failures = 0

    fun reset() {
        failures = 0
    }

    fun nextDelayMillis(now: Long, stableSince: Long?, jitterMillis: Long): Long {
        if (stableSince != null && now - stableSince >= STABILITY_WINDOW_MS) reset()
        val baseDelay = minOf(INITIAL_DELAY_MS shl failures, MAXIMUM_DELAY_MS)
        failures = (failures + 1).coerceAtMost(MAXIMUM_SHIFT)
        return baseDelay + jitterMillis.coerceIn(0, MAXIMUM_JITTER_MS)
    }

    companion object {
        private const val INITIAL_DELAY_MS = 1_000L
        private const val MAXIMUM_DELAY_MS = 30_000L
        private const val MAXIMUM_JITTER_MS = 250L
        private const val MAXIMUM_SHIFT = 5
        private const val STABILITY_WINDOW_MS = 20_000L
    }
}
