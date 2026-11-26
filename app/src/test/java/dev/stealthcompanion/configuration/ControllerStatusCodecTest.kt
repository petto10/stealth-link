package dev.stealthcompanion.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ControllerStatusCodecTest {
    @Test
    fun optionalStatusUsesStandardReadUuids() {
        assertEquals(UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"), ControllerStatusField.FIRMWARE.serviceUuid)
        assertEquals(UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"), ControllerStatusField.FIRMWARE.characteristicUuid)
        assertEquals(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), ControllerStatusField.BATTERY.serviceUuid)
        assertEquals(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"), ControllerStatusField.BATTERY.characteristicUuid)
    }

    @Test
    fun acceptsOneUnsignedBatteryPercentage() {
        val previous = ControllerDeviceStatus(firmwareRevision = "1.2.3")
        assertEquals(previous.copy(batteryPercent = 0), ControllerStatusCodec.decode(ControllerStatusField.BATTERY, byteArrayOf(0), previous))
        assertEquals(previous.copy(batteryPercent = 100), ControllerStatusCodec.decode(ControllerStatusField.BATTERY, byteArrayOf(100), previous))
        for (frame in listOf(byteArrayOf(), byteArrayOf(0, 1), byteArrayOf(101), byteArrayOf(-1))) {
            assertNull(ControllerStatusCodec.decode(ControllerStatusField.BATTERY, frame, previous))
        }
    }

    @Test
    fun revisionMustBeBoundedUtf8WithoutEmbeddedControls() {
        val previous = ControllerDeviceStatus(batteryPercent = 50)
        assertEquals(previous.copy(firmwareRevision = "v2.3-bèta"),
            ControllerStatusCodec.decode(ControllerStatusField.FIRMWARE, "v2.3-bèta".encodeToByteArray(), previous))
        assertEquals(previous.copy(hardwareRevision = "A"),
            ControllerStatusCodec.decode(ControllerStatusField.HARDWARE, "A".encodeToByteArray(), previous))
        assertEquals(previous.copy(softwareRevision = "B"),
            ControllerStatusCodec.decode(ControllerStatusField.SOFTWARE, "B".encodeToByteArray(), previous))
        for (frame in listOf(byteArrayOf(), byteArrayOf(-61, 45), byteArrayOf(65, 0, 66), " ".encodeToByteArray(), ByteArray(513))) {
            assertNull(ControllerStatusCodec.decode(ControllerStatusField.FIRMWARE, frame, previous))
        }
    }
}
