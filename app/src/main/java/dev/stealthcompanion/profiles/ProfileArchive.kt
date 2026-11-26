package dev.stealthcompanion.profiles

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal object ProfileArchive {
    const val MAX_PROFILES = 100
    private const val MAX_BYTES = 512 * 1024
    private const val FORMAT = "stealth-companion-profiles"
    private const val LOCAL_FORMAT = "stealth-companion-local-profiles"
    private const val VERSION = 1

    fun encode(profiles: List<LocalProfile>): ByteArray = serialize(encodeProfiles(profiles, FORMAT))

    fun encodeLocal(catalog: LocalProfileCatalog): ByteArray {
        val root = encodeProfiles(catalog.profiles, LOCAL_FORMAT)
        val selected = catalog.activeProfileId
        if (selected != null) {
            require(catalog.profiles.any { it.id == selected }) { "Unknown active local profile" }
            root.put("activeProfileId", selected)
        }
        return serialize(root)
    }

    private fun encodeProfiles(profiles: List<LocalProfile>, format: String): JSONObject {
        validateList(profiles)
        val entries = JSONArray()
        profiles.forEach { entries.put(entry(it)) }
        return JSONObject().put("format", format).put("version", VERSION).put("profiles", entries)
    }

    private fun serialize(root: JSONObject): ByteArray = (root.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8)

    fun decode(input: InputStream): List<LocalProfile> = decode(readBounded(input))

    fun decode(bytes: ByteArray): List<LocalProfile> = decodeProfiles(parse(bytes), FORMAT)

    fun decodeLocal(input: InputStream): LocalProfileCatalog {
        val root = parse(readBounded(input))
        return when (root.string("format")) {
            FORMAT -> LocalProfileCatalog(decodeProfiles(root, FORMAT))
            LOCAL_FORMAT -> {
                val profiles = decodeProfiles(root, LOCAL_FORMAT)
                val selected = root.string("activeProfileId")?.takeIf { id -> profiles.any { it.id == id } }
                LocalProfileCatalog(profiles, selected)
            }
            else -> error("Unrecognized local profile format")
        }
    }

    private fun parse(bytes: ByteArray): JSONObject {
        require(bytes.size <= MAX_BYTES) { "Profile backup is too large" }
        val text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        checkNesting(text)
        val tokens = JSONTokener(text)
        val root = tokens.nextValue() as? JSONObject ?: error("Missing profile backup object")
        require(tokens.nextClean() == '\u0000') { "Unexpected content after profile backup" }
        return root
    }

    private fun decodeProfiles(root: JSONObject, format: String): List<LocalProfile> {
        if (format == LOCAL_FORMAT) root.requireKeys("format", "version", "profiles", "activeProfileId")
        else root.requireKeys("format", "version", "profiles")
        require(root.string("format") == format) { "Unrecognized profile backup format" }
        require(root.integer("version") == VERSION) { "Unsupported profile backup version" }
        val values = root.getJSONArray("profiles")
        require(values.length() <= MAX_PROFILES) { "Too many profiles" }
        return List(values.length()) { index -> decodeEntry(values.getJSONObject(index)) }
            .also(::validateList)
    }

    private fun entry(profile: LocalProfile): JSONObject = json(
        "id" to profile.id,
        "name" to profile.name,
        "settings" to encodeSettings(profile.settings),
    )

    private fun encodeSettings(settings: ProfileSettings): JSONObject = json(
        "mapping" to settings.mapping?.let { mapping ->
            json("p1" to mapping.p1?.name, "p2" to mapping.p2?.name,
                "p3" to mapping.p3?.name, "p4" to mapping.p4?.name,
                "proAimLevel" to mapping.proAimLevel)
        },
        "sticks" to settings.sticks?.let { sticks ->
            json("leftResponse" to sticks.leftResponse?.name, "rightResponse" to sticks.rightResponse?.name,
                "leftStickDeadZonePercent" to sticks.leftStickDeadZonePercent,
                "rightStickDeadZonePercent" to sticks.rightStickDeadZonePercent,
                "leftTriggerDeadZonePercent" to sticks.leftTriggerDeadZonePercent,
                "rightTriggerDeadZonePercent" to sticks.rightTriggerDeadZonePercent)
        },
        "audio" to settings.audio?.let { audio ->
            json("preset" to audio.preset?.name, "gameVolumePercent" to audio.gameVolumePercent,
                "chatMixPercent" to audio.chatMixPercent,
                "microphoneMonitoringPercent" to audio.microphoneMonitoringPercent,
                "microphoneMuted" to audio.microphoneMuted)
        },
        "vibration" to settings.vibration?.let { vibration ->
            json("leftGripPercent" to vibration.leftGripPercent, "rightGripPercent" to vibration.rightGripPercent,
                "leftTriggerPercent" to vibration.leftTriggerPercent,
                "rightTriggerPercent" to vibration.rightTriggerPercent)
        },
        "rgb" to settings.rgb?.let { rgb ->
            json("colors" to JSONArray(rgb.colors), "effect" to rgb.effect?.name,
                "speed" to rgb.speed?.name, "brightnessPercent" to rgb.brightnessPercent)
        },
        "system" to settings.system?.let { system ->
            json("powerMode" to system.powerMode?.name, "screenTimeoutMinutes" to system.screenTimeoutMinutes,
                "sleepTimeoutMinutes" to system.sleepTimeoutMinutes,
                "screenBrightnessPercent" to system.screenBrightnessPercent,
                "chargingLedBrightnessPercent" to system.chargingLedBrightnessPercent,
                "lightingBrightnessPercent" to system.lightingBrightnessPercent)
        },
    )

    private fun decodeEntry(value: JSONObject): LocalProfile {
        value.requireKeys("id", "name", "settings")
        return LocalProfile(
            id = requireNotNull(value.string("id")),
            name = requireNotNull(value.string("name")),
            settings = decodeSettings(value.getJSONObject("settings")),
        )
    }

    private fun decodeSettings(value: JSONObject): ProfileSettings {
        value.requireKeys("mapping", "sticks", "audio", "vibration", "rgb", "system")
        return ProfileSettings(
            mapping = value.section("mapping")?.let {
                it.requireKeys("p1", "p2", "p3", "p4", "proAimLevel")
                MappingSettings(
                    p1 = it.enum<ButtonAction>("p1"), p2 = it.enum<ButtonAction>("p2"),
                    p3 = it.enum<ButtonAction>("p3"), p4 = it.enum<ButtonAction>("p4"),
                    proAimLevel = it.integer("proAimLevel"),
                )
            },
            sticks = value.section("sticks")?.let {
                it.requireKeys("leftResponse", "rightResponse", "leftStickDeadZonePercent", "rightStickDeadZonePercent",
                    "leftTriggerDeadZonePercent", "rightTriggerDeadZonePercent")
                StickSettings(
                    leftResponse = it.enum<StickResponse>("leftResponse"),
                    rightResponse = it.enum<StickResponse>("rightResponse"),
                    leftStickDeadZonePercent = it.integer("leftStickDeadZonePercent"),
                    rightStickDeadZonePercent = it.integer("rightStickDeadZonePercent"),
                    leftTriggerDeadZonePercent = it.integer("leftTriggerDeadZonePercent"),
                    rightTriggerDeadZonePercent = it.integer("rightTriggerDeadZonePercent"),
                )
            },
            audio = value.section("audio")?.let {
                it.requireKeys("preset", "gameVolumePercent", "chatMixPercent", "microphoneMonitoringPercent", "microphoneMuted")
                AudioSettings(
                    preset = it.enum<AudioPreset>("preset"), gameVolumePercent = it.integer("gameVolumePercent"),
                    chatMixPercent = it.integer("chatMixPercent"),
                    microphoneMonitoringPercent = it.integer("microphoneMonitoringPercent"),
                    microphoneMuted = it.optional<Boolean>("microphoneMuted"),
                )
            },
            vibration = value.section("vibration")?.let {
                it.requireKeys("leftGripPercent", "rightGripPercent", "leftTriggerPercent", "rightTriggerPercent")
                VibrationSettings(it.integer("leftGripPercent"), it.integer("rightGripPercent"),
                    it.integer("leftTriggerPercent"), it.integer("rightTriggerPercent"))
            },
            rgb = value.section("rgb")?.let {
                it.requireKeys("colors", "effect", "speed", "brightnessPercent")
                val colors = it.optional<JSONArray>("colors")
                require(colors == null || colors.length() <= 11) { "Too many RGB colors" }
                RgbSettings(
                    colors = colors?.let { array -> List(array.length()) { index -> asInt(array.get(index)) } } ?: emptyList(),
                    effect = it.enum<LightingEffect>("effect"), speed = it.enum<LightingSpeed>("speed"),
                    brightnessPercent = it.integer("brightnessPercent"),
                )
            },
            system = value.section("system")?.let {
                it.requireKeys("powerMode", "screenTimeoutMinutes", "sleepTimeoutMinutes", "screenBrightnessPercent",
                    "chargingLedBrightnessPercent", "lightingBrightnessPercent")
                SystemSettings(
                    powerMode = it.enum<PowerMode>("powerMode"), screenTimeoutMinutes = it.integer("screenTimeoutMinutes"),
                    sleepTimeoutMinutes = it.integer("sleepTimeoutMinutes"),
                    screenBrightnessPercent = it.integer("screenBrightnessPercent"),
                    chargingLedBrightnessPercent = it.integer("chargingLedBrightnessPercent"),
                    lightingBrightnessPercent = it.integer("lightingBrightnessPercent"),
                )
            },
        )
    }

    private fun validateList(profiles: List<LocalProfile>) {
        require(profiles.size <= MAX_PROFILES) { "Too many profiles" }
        require(profiles.map { it.id }.toSet().size == profiles.size) { "Duplicate profile identifier" }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(count > 0 && count <= MAX_BYTES - output.size()) { "Profile backup is too large or unreadable" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    // Android's JSON parser can descend recursively. Bound nesting before processing untrusted documents.
    private fun checkNesting(text: String) {
        var nesting = 0
        var inString = false
        var escaped = false
        for (character in text) {
            if (inString) {
                require(character >= ' ') { "Invalid character in profile backup string" }
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '[', '{' -> require(++nesting <= 12) { "Profile backup is nested too deeply" }
                ']', '}' -> require(--nesting >= 0) { "Invalid profile backup structure" }
                else -> require(character in " \t\r\n,:-+0123456789.eEtruefalsenull") { "Invalid profile backup character" }
            }
        }
        require(nesting == 0 && !inString) { "Incomplete profile backup" }
    }

    private fun json(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
        fields.forEach { (key, value) -> if (value != null) put(key, value) }
    }

    private fun JSONObject.requireKeys(vararg accepted: String) {
        require(keys().asSequence().all { it in accepted }) { "Unrecognized profile backup field" }
    }

    private fun JSONObject.section(key: String): JSONObject? = optional(key)
    private fun JSONObject.string(key: String): String? = optional(key)
    private fun JSONObject.integer(key: String): Int? = opt(key)?.takeUnless { it === JSONObject.NULL }?.let(::asInt)
    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String): T? = string(key)?.let { enumValueOf<T>(it) }

    private inline fun <reified T> JSONObject.optional(key: String): T? {
        val value = opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        require(value is T) { "Invalid profile backup value type" }
        return value
    }

    private fun asInt(value: Any): Int = when (value) {
        is Int -> value
        is Long -> {
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Profile backup integer out of range" }
            value.toInt()
        }
        else -> error("Expected a profile backup integer")
    }
}
