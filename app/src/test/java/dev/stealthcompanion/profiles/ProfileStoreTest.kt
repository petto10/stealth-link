package dev.stealthcompanion.profiles

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class ProfileStoreTest {
    @Test
    fun unicodeNameAndAllSettingsSurviveExportAndImport() {
        val source = ProfileStore(MemoryProfileStorage())
        val original = source.create("  Équipe 🎮 東京  ").copy(
            settings = ProfileSettings(
                mapping = MappingSettings(ButtonAction.A, ButtonAction.PRO_AIM, ButtonAction.DPAD_UP,
                    ButtonAction.UNASSIGNED, proAimLevel = 10),
                sticks = StickSettings(StickResponse.FAST, StickResponse.PRECISION, 12, 21, 0, 100),
                audio = AudioSettings(AudioPreset.SUPERHUMAN_HEARING, 100, 40, 75, false),
                vibration = VibrationSettings(0, 50, 75, 100),
                rgb = RgbSettings(listOf(0x000000, 0x12ABCD, 0xFFFFFF), LightingEffect.BREATHING, LightingSpeed.FAST, 80),
                system = SystemSettings(PowerMode.CUSTOM, 2, 5, 30, 40, 50),
            ),
        )
        assertTrue(source.replace(original))
        assertEquals("Équipe 🎮 東京", original.name)
        val external = ByteArrayOutputStream()
        assertEquals(1, source.exportProfiles(external))

        val target = ProfileStore(MemoryProfileStorage())
        assertEquals(ProfileImportResult(1, 0, 0), target.importProfiles(external.toByteArray().inputStream()))
        assertEquals(listOf(original), target.list())
        assertEquals(ProfileImportResult(0, 1, 0), target.importProfiles(external.toByteArray().inputStream()))
        assertEquals(listOf(original), target.list())
    }

    @Test
    fun conflictingIdPreservesBothProfilesAndRepeatedImportIsIdempotent() {
        val store = ProfileStore(MemoryProfileStorage())
        val original = store.create("Original")
        val incoming = original.copy(name = "Different")
        val external = ProfileArchive.encode(listOf(incoming))

        assertEquals(ProfileImportResult(1, 0, 1), store.importProfiles(external.inputStream()))
        val current = store.list()
        assertEquals(original, current[0])
        assertEquals("Different", current[1].name)
        assertNotEquals(original.id, current[1].id)
        assertEquals(ProfileImportResult(0, 1, 0), store.importProfiles(external.inputStream()))
        assertEquals(current, store.list())
    }

    @Test
    fun invalidArchiveLeavesCurrentProfilesIntact() {
        val store = ProfileStore(MemoryProfileStorage())
        val original = store.create("Keep me")
        val incoming = LocalProfile(UUID.randomUUID().toString(), "Import me")
        val valid = JSONObject(ProfileArchive.encode(listOf(incoming)).decodeToString())
        val entry = valid.getJSONArray("profiles").getJSONObject(0)
        val duplicate = JSONObject(entry.toString())
        valid.getJSONArray("profiles").put(duplicate)
        assertThrows(Exception::class.java) { store.importProfiles(valid.toString().byteInputStream()) }
        assertEquals(listOf(original), store.list())

        duplicate.put("id", UUID.randomUUID().toString())
        duplicate.getJSONObject("settings").put("audio", JSONObject().put("gameVolumePercent", 101))
        assertThrows(Exception::class.java) { store.importProfiles(valid.toString().byteInputStream()) }
        assertEquals(listOf(original), store.list())
    }

    @Test
    fun rejectsMalformedOrUnsupportedDocuments() {
        val valid = ProfileArchive.encode(emptyList()).decodeToString()
        val unsupported = JSONObject(valid).put("version", 2).toString()
        val wrongType = JSONObject(valid).put("version", "1").toString()
        val tooDeep = "[".repeat(32) + "]".repeat(32)
        val inputs = listOf(
            unsupported.encodeToByteArray(),
            wrongType.encodeToByteArray(),
            (valid + "null").encodeToByteArray(),
            tooDeep.encodeToByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28),
            ByteArray(512 * 1024 + 1),
        )
        inputs.forEach { bytes ->
            assertThrows(Exception::class.java) { ProfileArchive.decode(bytes.inputStream()) }
        }
    }

    @Test
    fun enforcesCapacityAndKeepsDataWhenStorageFails() {
        val backing = MemoryProfileStorage()
        val store = ProfileStore(backing)
        val profile = store.create("Keep me")
        backing.failWrites = true
        assertThrows(IOException::class.java) { store.delete(profile.id) }
        assertEquals(listOf(profile), store.list())
        backing.failWrites = false

        val full = List(ProfileArchive.MAX_PROFILES) { index ->
            LocalProfile(UUID.randomUUID().toString(), "Profile $index")
        }
        val input = ByteArrayInputStream(ProfileArchive.encode(full))
        assertThrows(IllegalArgumentException::class.java) { store.importProfiles(input) }
        assertEquals(listOf(profile), store.list())
        assertTrue(store.delete(profile.id))
        assertFalse(store.delete(profile.id))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun validatesNamesAndRgbBoundsWithoutChangingProfiles() {
        val store = ProfileStore(MemoryProfileStorage())
        assertThrows(IllegalArgumentException::class.java) { store.create("🎮".repeat(65)) }
        assertThrows(IllegalArgumentException::class.java) { store.create("One\nTwo") }
        assertThrows(IllegalArgumentException::class.java) { store.create("\uD83C") }
        assertThrows(IllegalArgumentException::class.java) { RgbSettings(colors = listOf(0x1000000)) }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun renamingKeepsSettingsAndSelectionAcrossStoreInstances() {
        val backing = MemoryProfileStorage()
        val first = ProfileStore(backing)
        val profile = first.create("Before").copy(settings = ProfileSettings(mapping = MappingSettings(p1 = ButtonAction.A)))
        assertTrue(first.replace(profile))
        assertTrue(first.setActive(profile.id))
        assertTrue(first.rename(profile.id, "  Après 🎮  "))
        assertFalse(first.rename(UUID.randomUUID().toString(), "Unknown"))
        val second = ProfileStore(backing)
        assertEquals(LocalProfileCatalog(listOf(profile.copy(name = "Après 🎮")), profile.id), second.snapshot())
        assertTrue(second.setActive(null))
        assertNull(second.snapshot().activeProfileId)
    }

    @Test
    fun loadsOlderLocalFilesAndKeepsExternalArchiveCompatible() {
        val backing = MemoryProfileStorage()
        val legacy = LocalProfile(UUID.randomUUID().toString(), "Legacy")
        backing.write(ProfileArchive.encode(listOf(legacy)))
        val store = ProfileStore(backing)
        assertEquals(LocalProfileCatalog(listOf(legacy)), store.snapshot())
        assertTrue(store.setActive(legacy.id))

        val external = ByteArrayOutputStream()
        assertEquals(1, store.exportProfiles(external))
        val json = JSONObject(external.toString("UTF-8"))
        assertEquals("stealth-companion-profiles", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertFalse(json.has("activeProfileId"))
        val target = ProfileStore(MemoryProfileStorage())
        assertEquals(ProfileImportResult(1, 0, 0), target.importProfiles(external.toByteArray().inputStream()))
        assertEquals(LocalProfileCatalog(listOf(legacy)), target.snapshot())
    }

    @Test
    fun clearingActiveProfileAndDeletingItCommitTogether() {
        val backing = MemoryProfileStorage()
        val store = ProfileStore(backing)
        val chosen = store.create("Active")
        val remaining = store.create("Other")
        assertTrue(store.setActive(chosen.id))
        assertFalse(store.setActive(UUID.randomUUID().toString()))
        backing.failWrites = true
        assertThrows(IOException::class.java) { store.delete(chosen.id) }
        assertEquals(LocalProfileCatalog(listOf(chosen, remaining), chosen.id), store.snapshot())
        backing.failWrites = false
        assertTrue(store.delete(chosen.id))
        assertEquals(LocalProfileCatalog(listOf(remaining)), store.snapshot())
    }


    private class MemoryProfileStorage : ProfileStorage {
        private var payload: ByteArray? = null
        var failWrites = false

        override fun open(): InputStream? = payload?.inputStream()

        override fun write(bytes: ByteArray) {
            if (failWrites) throw IOException("Simulated storage failure")
            payload = bytes.copyOf()
        }
    }
}
