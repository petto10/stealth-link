package dev.stealthcompanion.profiles

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class ProfileImportResult(val added: Int, val skipped: Int, val renamedIds: Int)

class ProfileStore internal constructor(private val storage: ProfileStorage) {
    constructor(context: Context) : this(
        AtomicProfileStorage(File(context.noBackupFilesDir, "local-profiles.json")),
    )

    fun list(): List<LocalProfile> = synchronized(lock) { read() }

    fun create(name: String): LocalProfile = synchronized(lock) {
        val profiles = read()
        var id: String
        do {
            id = UUID.randomUUID().toString()
        } while (profiles.any { it.id == id })

        val created = LocalProfile(id, name.trim())
        write(profiles + created)
        created
    }

    fun replace(profile: LocalProfile): Boolean = synchronized(lock) {
        val profiles = read()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index == -1) return@synchronized false
        if (profiles[index] != profile) {
            val updated = profiles.toMutableList()
            updated[index] = profile
            write(updated)
        }
        true
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val profiles = read()
        val remaining = profiles.filterNot { it.id == id }
        if (remaining.size == profiles.size) return@synchronized false
        write(remaining)
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
            val profiles = read().associateByTo(linkedMapOf()) { it.id }
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
            if (added > 0) write(profiles.values.toList())
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

    private fun read(): List<LocalProfile> = storage.open()?.use { ProfileArchive.decode(it) } ?: emptyList()

    private fun write(profiles: List<LocalProfile>) {
        storage.write(ProfileArchive.encode(profiles))
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
