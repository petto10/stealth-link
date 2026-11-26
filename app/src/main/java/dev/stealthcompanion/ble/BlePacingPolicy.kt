package dev.stealthcompanion.ble

import dev.stealthcompanion.core.protocol.LengthProbe

internal object BlePacingPolicy {
    const val DEFAULT_EXTENDED_DELAY_MS = 25

    fun normalizeRegularDelay(delayMs: Int): Int =
        delayMs.takeIf { it in LengthProbe.SUPPORTED_DELAYS } ?: DEFAULT_EXTENDED_DELAY_MS

    fun delayMillis(
        extended: Boolean,
        synthetic: Boolean,
        regularExtendedDelayMs: Int,
        probe: BleProbe?,
    ): Long = when {
        synthetic -> probe?.fragmentDelayMs?.toLong() ?: 0L
        extended -> normalizeRegularDelay(regularExtendedDelayMs).toLong()
        else -> 0L
    }
}
