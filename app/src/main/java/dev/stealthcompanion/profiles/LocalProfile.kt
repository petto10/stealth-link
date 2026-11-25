package dev.stealthcompanion.profiles

import java.nio.charset.StandardCharsets
import java.util.UUID

data class LocalProfile(
    val id: String,
    val name: String,
    val settings: ProfileSettings = ProfileSettings(),
) {
    init {
        require(UUID.fromString(id).toString() == id) { "Invalid profile identifier" }
        require(
            name.isNotBlank() && name == name.trim() && name.codePointCount(0, name.length) <= 64 &&
                name.none(Char::isISOControl) && StandardCharsets.UTF_8.newEncoder().canEncode(name),
        ) { "Invalid profile name" }
    }
}

// These are local values in user-facing units. A future device adapter maps them to firmware values.
data class ProfileSettings(
    val mapping: MappingSettings? = null,
    val sticks: StickSettings? = null,
    val audio: AudioSettings? = null,
    val vibration: VibrationSettings? = null,
    val rgb: RgbSettings? = null,
    val system: SystemSettings? = null,
)

enum class ButtonAction {
    A, B, X, Y, LEFT_BUMPER, RIGHT_BUMPER, LEFT_TRIGGER, RIGHT_TRIGGER,
    LEFT_STICK_CLICK, RIGHT_STICK_CLICK, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    VIEW, MENU, PRO_AIM, UNASSIGNED,
}

data class MappingSettings(
    val p1: ButtonAction? = null,
    val p2: ButtonAction? = null,
    val p3: ButtonAction? = null,
    val p4: ButtonAction? = null,
    val proAimLevel: Int? = null,
) {
    init { requireInRange(proAimLevel, 0..10) }
}

enum class StickResponse { STANDARD, PRECISION, FAST }

data class StickSettings(
    val leftResponse: StickResponse? = null,
    val rightResponse: StickResponse? = null,
    val leftStickDeadZonePercent: Int? = null,
    val rightStickDeadZonePercent: Int? = null,
    val leftTriggerDeadZonePercent: Int? = null,
    val rightTriggerDeadZonePercent: Int? = null,
) {
    init {
        requireInRange(leftStickDeadZonePercent, 0..100)
        requireInRange(rightStickDeadZonePercent, 0..100)
        requireInRange(leftTriggerDeadZonePercent, 0..100)
        requireInRange(rightTriggerDeadZonePercent, 0..100)
    }
}

enum class AudioPreset { SIGNATURE, BASS, BASS_AND_TREBLE, VOICE, SUPERHUMAN_HEARING }

data class AudioSettings(
    val preset: AudioPreset? = null,
    val gameVolumePercent: Int? = null,
    val chatMixPercent: Int? = null,
    val microphoneMonitoringPercent: Int? = null,
    val microphoneMuted: Boolean? = null,
) {
    init {
        requireInRange(gameVolumePercent, 0..100)
        requireInRange(chatMixPercent, 0..100)
        requireInRange(microphoneMonitoringPercent, 0..100)
    }
}

data class VibrationSettings(
    val leftGripPercent: Int? = null,
    val rightGripPercent: Int? = null,
    val leftTriggerPercent: Int? = null,
    val rightTriggerPercent: Int? = null,
) {
    init {
        requireInRange(leftGripPercent, 0..100)
        requireInRange(rightGripPercent, 0..100)
        requireInRange(leftTriggerPercent, 0..100)
        requireInRange(rightTriggerPercent, 0..100)
    }
}

enum class LightingEffect { STATIC, BREATHING, COLOR_CYCLE, LINEAR_PULSE, DYNAMIC }
enum class LightingSpeed { SLOW, MEDIUM, FAST }

data class RgbSettings(
    val colors: List<Int> = emptyList(),
    val effect: LightingEffect? = null,
    val speed: LightingSpeed? = null,
    val brightnessPercent: Int? = null,
) {
    init {
        require(colors.size <= 11 && colors.all { it in 0..0xFFFFFF }) { "Invalid RGB colors" }
        requireInRange(brightnessPercent, 0..100)
    }
}

enum class PowerMode { ECO, BALANCED, FULL, CUSTOM }

data class SystemSettings(
    val powerMode: PowerMode? = null,
    val screenTimeoutMinutes: Int? = null,
    val sleepTimeoutMinutes: Int? = null,
    val screenBrightnessPercent: Int? = null,
    val chargingLedBrightnessPercent: Int? = null,
    val lightingBrightnessPercent: Int? = null,
) {
    init {
        requireInRange(screenTimeoutMinutes, 0..255)
        requireInRange(sleepTimeoutMinutes, 0..255)
        requireInRange(screenBrightnessPercent, 0..100)
        requireInRange(chargingLedBrightnessPercent, 0..100)
        requireInRange(lightingBrightnessPercent, 0..100)
    }
}

private fun requireInRange(value: Int?, range: IntRange) {
    require(value == null || value in range) { "Profile value out of range" }
}
