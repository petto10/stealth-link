package dev.stealthcompanion.profiles

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class ProfileImportResult(val added: Int, val skipped: Int, val renamedIds: Int)

data class LocalProfileCatalog(val profiles: List<LocalProfile>, val activeProfileId: String? = null)

class ProfileStore internal constructor(private val storage: ProfileStorage) {
    constructor(context: Context) : this(
        AtomicProfileStorage(File(context.noBackupFilesDir, "local-profiles.json")),
    )

    fun list(): List<LocalProfile> = snapshot().profiles

    fun snapshot(): LocalProfileCatalog = synchronized(lock) { read() }

    fun create(name: String): LocalProfile = synchronized(lock) {
        val catalog = read()
        var id: String
        do {
            id = UUID.randomUUID().toString()
        } while (catalog.profiles.any { it.id == id })

        val created = LocalProfile(id, name.trim())
        write(catalog.copy(profiles = catalog.profiles + created))
        created
    }

    fun replace(profile: LocalProfile): Boolean = updateProfile(profile.id) { profile }

    fun rename(id: String, name: String): Boolean = updateProfile(id) { it.copy(name = name.trim()) }

    private fun updateProfile(id: String, transform: (LocalProfile) -> LocalProfile): Boolean = synchronized(lock) {
        val catalog = read()
        val profiles = catalog.profiles
        val index = profiles.indexOfFirst { it.id == id }
        if (index == -1) return@synchronized false
        val profile = transform(profiles[index])
        if (profiles[index] != profile) {
            val updated = profiles.toMutableList()
            updated[index] = profile
            write(catalog.copy(profiles = updated))
        }
        true
    }

    fun setActive(id: String?): Boolean = synchronized(lock) {
        val catalog = read()
        if (id != null && catalog.profiles.none { it.id == id }) return@synchronized false
        if (catalog.activeProfileId != id) write(catalog.copy(activeProfileId = id))
        true
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val catalog = read()
        val remaining = catalog.profiles.filterNot { it.id == id }
        if (remaining.size == catalog.profiles.size) return@synchronized false
        write(LocalProfileCatalog(remaining, catalog.activeProfileId.takeUnless { it == id }))
        true
    }

    fun exportProfiles(output: OutputStream): Int {
        val profiles = list()
        output.write(ProfileArchive.encode(profiles))
        return profiles.size
    }

    fun importProfiles(input: InputStream): ProfileImportResult {
        val incoming = ProfileArchive.decode(input)
        return synchronized(lock) {
            val catalog = read()
            val profiles = catalog.profiles.associateByTo(linkedMapOf()) { it.id }
            var added = 0
            var skipped = 0
            var renamedIds = 0
            for (profile in incoming) {
                val resolved = resolveConflict(profile, profiles)
                if (profiles[resolved.id] == resolved) {
                    skipped++
                } else {
                    profiles[resolved.id] = resolved
                    added++
                    if (resolved.id != profile.id) renamedIds++
                }
            }
            if (added > 0) write(catalog.copy(profiles = profiles.values.toList()))
            ProfileImportResult(added, skipped, renamedIds)
        }
    }

    private fun resolveConflict(profile: LocalProfile, existing: Map<String, LocalProfile>): LocalProfile {
        val ownId = existing[profile.id]
        if (ownId == null || ownId == profile) return profile

        // Derive the replacement from the original entry so re-importing the same backup is idempotent.
        val seed = ProfileArchive.encode(listOf(profile))
        var ordinal = 0
        while (true) {
            val id = UUID.nameUUIDFromBytes(seed + ordinal.toString().encodeToByteArray()).toString()
            val replacement = profile.copy(id = id)
            val previous = existing[id]
            if (previous == null || previous == replacement) return replacement
            ordinal++
        }
    }

    private fun read(): LocalProfileCatalog = storage.open()?.use(ProfileArchive::decodeLocal) ?: LocalProfileCatalog(emptyList())

    private fun write(catalog: LocalProfileCatalog) {
        storage.write(ProfileArchive.encodeLocal(catalog))
    }

    private companion object {
        val lock = Any()
    }
}

internal interface ProfileStorage {
    fun open(): InputStream?
    fun write(bytes: ByteArray)
}

private class AtomicProfileStorage(file: File) : ProfileStorage {
    private val file = AtomicFile(file)

    override fun open(): InputStream? = try {
        file.openRead()
    } catch (failure: FileNotFoundException) {
        if (file.baseFile.exists()) throw failure
        null
    }

    override fun write(bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}
