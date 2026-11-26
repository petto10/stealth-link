package dev.stealthcompanion.ble

import dev.stealthcompanion.configuration.PowerManagementRead
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

internal enum class SettingsQueryType(val required: Boolean = true) {
    ACTIVE_SLOT, PROFILE_NAME, SYSTEM, POWER_MANAGEMENT(false), CHARGE_LED(false),
}

internal data class SettingsQuery(val type: SettingsQueryType, val slot: Int = 0) {
    init {
        require(if (type == SettingsQueryType.PROFILE_NAME) slot in 0 until ReadOnlySettingsCodec.SLOT_COUNT else slot == 0)
    }

    fun bytes(): ByteArray {
        val frame = when (type) {
            SettingsQueryType.ACTIVE_SLOT -> byteArrayOf(0x11, 0xbb.toByte(), 0, 0, 0, 0)
            SettingsQueryType.PROFILE_NAME -> byteArrayOf(0xd1.toByte(), 0x09, slot.toByte(), 0, 0, 0)
            SettingsQueryType.SYSTEM -> byteArrayOf(0xd1.toByte(), 0x06, 0, 0, 0, 0)
            SettingsQueryType.POWER_MANAGEMENT -> byteArrayOf(0xc2.toByte(), 0, 0, 0, 0, 0)
            SettingsQueryType.CHARGE_LED -> byteArrayOf(0xc4.toByte(), 0, 0, 0, 0, 0)
        }
        frame[frame.lastIndex] = frame.sum().toByte()
        return frame
    }
}

internal object ReadOnlySettingsCodec {
    const val SLOT_COUNT = 10

    fun decode(query: SettingsQuery, frame: ByteArray, snapshot: ReadOnlySettings): ReadOnlySettings? =
        when (query.type) {
            SettingsQueryType.ACTIVE_SLOT -> readActiveSlot(frame, snapshot)
            SettingsQueryType.PROFILE_NAME -> readProfile(frame, query.slot, snapshot)
            SettingsQueryType.SYSTEM -> readSystem(frame, snapshot)
            SettingsQueryType.POWER_MANAGEMENT -> readPowerManagement(frame, snapshot)
            SettingsQueryType.CHARGE_LED -> readChargeLed(frame, snapshot)
        }

    private fun readActiveSlot(frame: ByteArray, snapshot: ReadOnlySettings): ReadOnlySettings? {
        if (frame.size < 4 || !frame.hasHeader(0x11, 0xbb)) return null
        val slot = frame.valueAt(3)
        return snapshot.copy(activeSlot = slot + 1).takeIf { slot in 0 until SLOT_COUNT }
    }

    private fun readProfile(frame: ByteArray, expectedSlot: Int, snapshot: ReadOnlySettings): ReadOnlySettings? {
        if (frame.size != 21 || !frame.hasHeader(0xd1, 0x09) || frame.valueAt(3) != expectedSlot) return null
        val end = (4 until 20).firstOrNull { frame[it] == 0.toByte() } ?: 20
        val name = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(frame, 4, end - 4)).toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return snapshot.copy(profiles = snapshot.profiles + ((expectedSlot + 1) to name))
    }

    private fun readSystem(frame: ByteArray, snapshot: ReadOnlySettings): ReadOnlySettings? {
        if (frame.size < 9 || !frame.hasHeader(0xd1, 0x06)) return null
        val system = ReadOnlySystem(
            powerMode = frame.valueAt(3),
            screenBrightness = frame.valueAt(4),
            ledBrightness = frame.valueAt(5) / 10,
            screenTimeoutMinutes = frame.valueAt(6),
            sleepTimeoutMinutes = frame.valueAt(7),
            chargeLedBrightness = frame.valueAt(8),
        )
        return snapshot.copy(system = system)
    }

    private fun readPowerManagement(frame: ByteArray, snapshot: ReadOnlySettings): ReadOnlySettings? {
        if (frame.size != 10 || frame.valueAt(0) != 0xc2 || frame.valueAt(1) != 0) return null
        val mode = frame.valueAt(2)
        val brightness = frame.valueAt(4)
        if (mode !in 0..3 || brightness !in 0..10) return null
        return snapshot.copy(powerManagement = PowerManagementRead(
            powerMode = mode,
            screenTimeoutMinutes = frame.valueAt(3),
            screenBrightness = brightness,
            sleepTimeoutMinutes = frame.valueAt(6),
        ))
    }

    private fun readChargeLed(frame: ByteArray, snapshot: ReadOnlySettings): ReadOnlySettings? {
        if (frame.size != 6 || frame.valueAt(0) != 0xc4 || frame.valueAt(1) != 0) return null
        val brightness = frame.valueAt(2)
        return snapshot.copy(chargeLedBrightness = brightness).takeIf { brightness in 0..10 }
    }

    private fun ByteArray.hasHeader(first: Int, second: Int): Boolean =
        valueAt(0) == first && valueAt(1) == second && valueAt(2) == 0

    private fun ByteArray.valueAt(index: Int): Int = this[index].toInt() and 0xff
}
