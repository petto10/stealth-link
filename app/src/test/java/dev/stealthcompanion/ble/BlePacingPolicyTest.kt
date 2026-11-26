package dev.stealthcompanion.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BlePacingPolicyTest {
    @Test
    fun regularExtendedDefaultsTo25msAndCanUseAnAllowedSetting() {
        assertEquals(25L, BlePacingPolicy.delayMillis(true, false, BlePacingPolicy.DEFAULT_EXTENDED_DELAY_MS, null))
        assertEquals(50L, BlePacingPolicy.delayMillis(true, false, 50, null))
        assertEquals(25L, BlePacingPolicy.delayMillis(true, false, 57, null))
        assertEquals(0L, BlePacingPolicy.delayMillis(true, false, 0, null))
    }

    @Test
    fun compactAndOrdinarySyntheticTrafficDoNotInheritRegularExtendedPacing() {
        assertEquals(0L, BlePacingPolicy.delayMillis(false, false, 25, null))
        assertEquals(0L, BlePacingPolicy.delayMillis(true, true, 25, null))
    }

    @Test
    fun labProbeKeepsItsOwnDelayAndCannotDriveRegularTraffic() {
        val probe = BleProbe(id = 10, fragmentDelayMs = 300)
        assertEquals(300L, BlePacingPolicy.delayMillis(true, true, 25, probe))
        assertEquals(25L, BlePacingPolicy.delayMillis(true, false, 25, probe))
    }
}
