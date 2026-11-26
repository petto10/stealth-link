package dev.stealthcompanion.ble

import dev.stealthcompanion.configuration.PowerManagementRead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadOnlySettingsCodecTest {
    private val empty = ReadOnlySettings()
    private val activeSlot = SettingsQuery(SettingsQueryType.ACTIVE_SLOT)
    private val system = SettingsQuery(SettingsQueryType.SYSTEM)
    private val power = SettingsQuery(SettingsQueryType.POWER_MANAGEMENT)
    private val charge = SettingsQuery(SettingsQueryType.CHARGE_LED)

    @Test
    fun onlyDocumentedReadQueriesAreGeneratedWithChecksum() {
        assertArrayEquals(bytes(0x11, 0xbb, 0, 0, 0, 0xcc), activeSlot.bytes())
        assertArrayEquals(bytes(0xd1, 0x06, 0, 0, 0, 0xd7), system.bytes())
        assertArrayEquals(bytes(0xc2, 0, 0, 0, 0, 0xc2), power.bytes())
        assertArrayEquals(bytes(0xc4, 0, 0, 0, 0, 0xc4), charge.bytes())
        for (slot in 0..9) {
            val name = SettingsQuery(SettingsQueryType.PROFILE_NAME, slot)
            assertArrayEquals(bytes(0xd1, 0x09, slot, 0, 0, 0xda + slot), name.bytes())
        }
        assertThrows(IllegalArgumentException::class.java) { SettingsQuery(SettingsQueryType.PROFILE_NAME, 10) }
        assertThrows(IllegalArgumentException::class.java) { SettingsQuery(SettingsQueryType.SYSTEM, 1) }
        assertThrows(IllegalArgumentException::class.java) { SettingsQuery(SettingsQueryType.POWER_MANAGEMENT, 1) }
        assertThrows(IllegalArgumentException::class.java) { SettingsQuery(SettingsQueryType.CHARGE_LED, 1) }
    }

    @Test
    fun acceptsValidSlotAndRejectsWrongOpcodeStatusOrPosition() {
        assertEquals(10, ReadOnlySettingsCodec.decode(activeSlot, bytes(0x11, 0xbb, 0, 9), empty)?.activeSlot)
        assertNull(ReadOnlySettingsCodec.decode(activeSlot, bytes(0x11, 0xbb, 0), empty))
        assertNull(ReadOnlySettingsCodec.decode(activeSlot, bytes(0x11, 0xbb, 0, 10), empty))
        assertNull(ReadOnlySettingsCodec.decode(activeSlot, bytes(0x11, 0xbb, 1, 1), empty))
        assertNull(ReadOnlySettingsCodec.decode(activeSlot, bytes(0x11, 0xaa, 0, 1), empty))
    }

    @Test
    fun matchesProfileSlotAndReadsUtf8WithoutPadding() {
        val query = SettingsQuery(SettingsQueryType.PROFILE_NAME, 3)
        val frame = profileFrame(3, "Città 🎮".toByteArray(Charsets.UTF_8))
        assertEquals(mapOf(4 to "Città 🎮"), ReadOnlySettingsCodec.decode(query, frame, empty)?.profiles)
        assertNull(ReadOnlySettingsCodec.decode(query, profileFrame(2, "Wrong".toByteArray()), empty))
        assertNull(ReadOnlySettingsCodec.decode(query, frame.copyOfRange(0, 20), empty))
    }

    @Test
    fun excludesFinalByteOfProfileFrameAndRejectsMalformedUtf8() {
        val query = SettingsQuery(SettingsQueryType.PROFILE_NAME, 0)
        val frame = profileFrame(0, "1234567890abcdef".toByteArray())
        frame[20] = 71
        assertEquals(mapOf(1 to "1234567890abcdef"), ReadOnlySettingsCodec.decode(query, frame, empty)?.profiles)
        assertNull(ReadOnlySettingsCodec.decode(query, profileFrame(0, bytes(0xf0, 0x9f)), empty))
    }

    @Test
    fun readsUnsignedSystemFieldsAndPreservesOtherResults() {
        val previous = ReadOnlySettings(activeSlot = 2, profiles = mapOf(2 to "Sample"))
        val frame = bytes(0xd1, 0x06, 0, 3, 5, 120, 2, 200, 8)
        val expected = previous.copy(system = ReadOnlySystem(3, 5, 12, 2, 200, 8))
        assertEquals(expected, ReadOnlySettingsCodec.decode(system, frame, previous))
        assertNull(ReadOnlySettingsCodec.decode(system, frame.copyOfRange(0, 8), previous))
    }

    @Test
    fun decodesOptionalPowerAndChargeResponsesIndependentlyFromBaseline() {
        val previous = ReadOnlySettings(system = ReadOnlySystem(2, 5, 12, 2, 200, 8))
        val powerFrame = bytes(0xc2, 0, 3, 2, 5, 0xee, 200, 0, 0, 0)
        val powerExpected = previous.copy(powerManagement = PowerManagementRead(3, 2, 5, 200))
        assertFalse(power.type.required)
        assertFalse(charge.type.required)
        assertEquals(powerExpected, ReadOnlySettingsCodec.decode(power, powerFrame, previous))
        assertEquals(powerExpected.copy(chargeLedBrightness = 9),
            ReadOnlySettingsCodec.decode(charge, bytes(0xc4, 0, 9, 0, 0, 0), powerExpected))
        assertNull(ReadOnlySettingsCodec.decode(power, powerFrame.copyOf(9), previous))
        assertNull(ReadOnlySettingsCodec.decode(power, powerFrame.copyOf(11), previous))
        assertNull(ReadOnlySettingsCodec.decode(power, powerFrame.copyOf().apply { this[1] = 1 }, previous))
        assertNull(ReadOnlySettingsCodec.decode(charge, bytes(0xc4, 1, 9, 0, 0, 0), previous))
        assertNull(ReadOnlySettingsCodec.decode(charge, bytes(0xc4, 0, 9), previous))
        assertNull(ReadOnlySettingsCodec.decode(charge, bytes(0xc3, 0, 9, 0, 0, 0), previous))
        assertNull(ReadOnlySettingsCodec.decode(charge, powerFrame, previous))
    }

    @Test
    fun optionalStatusRejectsValuesOutsideEvidencedUltraModesAndBrightness() {
        val powerFrame = bytes(0xc2, 0, 3, 2, 10, 0, 3, 0, 0, 0)
        assertEquals(10, ReadOnlySettingsCodec.decode(power, powerFrame, empty)?.powerManagement?.screenBrightness)
        assertNull(ReadOnlySettingsCodec.decode(power, powerFrame.copyOf().apply { this[2] = 4 }, empty))
        assertNull(ReadOnlySettingsCodec.decode(power, powerFrame.copyOf().apply { this[4] = 11 }, empty))
        assertEquals(0, ReadOnlySettingsCodec.decode(charge, bytes(0xc4, 0, 0, 0, 0, 0), empty)?.chargeLedBrightness)
        assertNull(ReadOnlySettingsCodec.decode(charge, bytes(0xc4, 0, 11, 0, 0, 0), empty))
    }

    private fun profileFrame(slot: Int, name: ByteArray): ByteArray = ByteArray(21).apply {
        bytes(0xd1, 0x09, 0, slot).copyInto(this)
        name.copyInto(this, 4)
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
}
