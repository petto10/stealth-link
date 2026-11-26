package dev.stealthcompanion.configuration

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.UUID

data class ControllerDeviceStatus(
    val firmwareRevision: String? = null,
    val batteryPercent: Int? = null,
    val hardwareRevision: String? = null,
    val softwareRevision: String? = null,
)

data class PowerManagementRead(
    val powerMode: Int,
    val screenTimeoutMinutes: Int,
    val screenBrightness: Int,
    val sleepTimeoutMinutes: Int,
)

internal enum class ControllerStatusField(serviceId: String, characteristicId: String) {
    FIRMWARE("180a", "2a26"),
    BATTERY("180f", "2a19"),
    HARDWARE("180a", "2a27"),
    SOFTWARE("180a", "2a28");

    val serviceUuid: UUID = UUID.fromString("0000$serviceId-0000-1000-8000-00805f9b34fb")
    val characteristicUuid: UUID = UUID.fromString("0000$characteristicId-0000-1000-8000-00805f9b34fb")
}

internal object ControllerStatusCodec {
    /** An invalid or unsupported value does not change the existing snapshot. */
    fun decode(
        field: ControllerStatusField,
        payload: ByteArray,
        previous: ControllerDeviceStatus,
    ): ControllerDeviceStatus? = when (field) {
        ControllerStatusField.BATTERY -> payload.singleOrNull()?.toInt()?.and(0xff)
            ?.takeIf { it in 0..100 }?.let { previous.copy(batteryPercent = it) }
        ControllerStatusField.FIRMWARE -> revision(payload)?.let { previous.copy(firmwareRevision = it) }
        ControllerStatusField.HARDWARE -> revision(payload)?.let { previous.copy(hardwareRevision = it) }
        ControllerStatusField.SOFTWARE -> revision(payload)?.let { previous.copy(softwareRevision = it) }
    }

    private fun revision(payload: ByteArray): String? {
        if (payload.size !in 1..MAX_GATT_VALUE_BYTES) return null
        val name = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString().trim()
        } catch (_: CharacterCodingException) {
            return null
        }
        return name.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
    }

    private const val MAX_GATT_VALUE_BYTES = 512
}
