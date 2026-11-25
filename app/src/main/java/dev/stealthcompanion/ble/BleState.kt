package dev.stealthcompanion.ble

enum class BlePhase {
    IDLE,
    STARTING,
    CONNECTING,
    WAITING_FOR_SUBSCRIPTION,
    READY,
    RETRYING,
    BLUETOOTH_OFF,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
}

enum class BleDeviceRole { CONFIGURATION, SOCIAL, BOTH }

data class BleDeviceChoice(val address: String, val displayName: String, val role: BleDeviceRole)

data class ReadOnlySystem(
    val powerMode: Int,
    val screenBrightness: Int,
    val ledBrightness: Int,
    val screenTimeoutMinutes: Int,
    val sleepTimeoutMinutes: Int,
    val chargeLedBrightness: Int,
)

data class ReadOnlySettings(
    val activeSlot: Int? = null,
    val profiles: Map<Int, String> = emptyMap(),
    val system: ReadOnlySystem? = null,
    val reading: Boolean = false,
    val complete: Boolean = false,
)

data class BleState(
    val phase: BlePhase = BlePhase.IDLE,
    val socialReady: Boolean = false,
    val encryptedByPolicy: Boolean = false,
    val mtu: Int = 23,
    val settingsConnected: Boolean = false,
    val settings: ReadOnlySettings = ReadOnlySettings(),
    val syntheticOnly: Boolean = false,
)

enum class BleDiagnosticEvent {
    SESSION_STARTED,
    SESSION_STOPPED,
    PERMISSION_MISSING,
    BLUETOOTH_DISABLED,
    DEVICE_UNAVAILABLE,
    FOREGROUND_FAILED,
    SERVER_OPENED,
    SERVER_FAILED,
    PEER_REJECTED,
    PEER_DISCONNECTED,
    SUBSCRIPTION_CHANGED,
    SOCIAL_READY,
    CONNECTION_STARTED,
    CONNECTION_FAILED,
    CONNECTION_CONNECTED,
    CONNECTION_DISCONNECTED,
    RETRY_SCHEDULED,
    MTU_CHANGED,
    CONTROL_REQUEST_REJECTED,
    NOTIFICATION_SENT,
    NOTIFICATION_FAILED,
    NOTIFICATION_TIMED_OUT,
    SETTINGS_CHANNEL_READY,
    SETTINGS_CHANNEL_MISSING,
    SETTINGS_READ_REQUESTED,
    SETTINGS_READ_COMPLETED,
    SETTINGS_READ_PARTIAL,
    PROBE_CHANGED,
}

data class BleDiagnostic(
    val elapsedMs: Long,
    val event: BleDiagnosticEvent,
    val status: Int? = null,
)
