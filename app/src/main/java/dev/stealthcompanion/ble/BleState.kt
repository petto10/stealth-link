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
    val clientMtu: Int? = null,
    val samePeerForMtu: Boolean = false,
    val longTextEnabled: Boolean = false,
    val socialMtuKnown: Boolean = false,
    val attributeRequests: Int = 0,
    val fragmentsSent: Int = 0,
    val lastRequestedTitleBytes: Int? = null,
    val lastRequestedBodyBytes: Int? = null,
    val lastSentTitleBytes: Int? = null,
    val lastSentBodyBytes: Int? = null,
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
    CLIENT_MTU_REQUESTED,
    CLIENT_MTU_SKIPPED,
    CLIENT_MTU_CHANGED,
    CLIENT_MTU_FAILED,
    SOCIAL_MTU_CHANGED,
    CONTROL_REQUEST_RECEIVED,
    CONTROL_REQUEST_REJECTED,
    CONTROL_RESPONSE_QUEUED,
    CONTROL_RESPONSE_COMPLETED,
    SOCIAL_FRAGMENT_SENT,
    SOCIAL_DELIVERY_COMPLETED,
    SOCIAL_DELIVERY_ABORTED,
    PARTIAL_RESPONSE_RESET,
    NOTIFICATION_SENT,
    NOTIFICATION_FAILED,
    NOTIFICATION_TIMED_OUT,
    SETTINGS_CHANNEL_READY,
    SETTINGS_CHANNEL_MISSING,
    SETTINGS_RETRY_SCHEDULED,
    SETTINGS_READ_REQUESTED,
    SETTINGS_READ_COMPLETED,
    SETTINGS_READ_PARTIAL,
    PROBE_CHANGED,
    LONG_TEXT_CHANGED,
}

enum class BleDiagnosticCause {
    DIFFERENT_PEERS,
    CONFIGURATION_CHANNEL_UNAVAILABLE,
    SOCIAL_SUBSCRIPTION_NOT_READY,
    ALREADY_NEGOTIATED,
    REQUEST_NOT_QUEUED,
    CALLBACK_FAILURE,
    CALLBACK_TIMEOUT,
    INVALID_MTU,
    DISCONNECTED,
    SUBSCRIPTIONS_LOST,
    TRANSPORT_CLOSED,
    NOT_READY,
    REJECTED_ATTRIBUTE,
    UNKNOWN_OR_EXPIRED_RECORD,
    DUPLICATE_RESPONSE_PENDING,
    QUEUE_LIMIT,
    ATT_WRITE_RESPONSE_FAILED,
    FRAGMENT_EXCEEDS_MTU,
    RECORD_EXPIRED,
    NO_ATTRIBUTE_REQUEST,
    ATTRIBUTE_RESPONSE_TIMEOUT,
    CANCELLED,
    PRIVACY,
    MODE_CHANGED,
}

data class BleAttributeDiagnostic(
    val id: Int,
    val requestedMaxBytes: Int? = null,
    val payloadBytes: Int? = null,
)

data class BleDiagnostic(
    val elapsedMs: Long,
    val event: BleDiagnosticEvent,
    val status: Int? = null,
    val cause: BleDiagnosticCause? = null,
    val attributes: List<BleAttributeDiagnostic> = emptyList(),
    val frameBytes: List<Int> = emptyList(),
    val responseNumber: Int? = null,
    val command: Int? = null,
)
