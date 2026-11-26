package dev.stealthcompanion.ble

import dev.stealthcompanion.core.protocol.LengthProbe

/** Identifies a synthetic length test without carrying the sample text. */
internal data class BleProbe(val id: Long, val fragmentDelayMs: Int, val part: Int = 1, val parts: Int = 1) {
    init {
        require(id > 0 && fragmentDelayMs in LengthProbe.SUPPORTED_DELAYS && part in 1..parts)
    }
}
