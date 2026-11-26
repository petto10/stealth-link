package dev.stealthcompanion.ble

import dev.stealthcompanion.core.protocol.SocialAttribute

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
    val notificationsOnly: Boolean = false,
    val socialRegistered: Boolean = false,
    val socialPeerConnected: Boolean = false,
    val notificationSourceSubscribed: Boolean = false,
    val dataSourceSubscribed: Boolean = false,
    val settingsConnecting: Boolean = false,
    val settingsRetryDelayMs: Long? = null,
    val socialRetryDelayMs: Long? = null,
    val socialConnections: Long = 0,
    val socialDisconnects: Long = 0,
    val settingsConnections: Long = 0,
    val settingsConnectionAttempts: Long = 0,
    val settingsDisconnects: Long = 0,
    val settingsRetries: Long = 0,
    val socialServerRetries: Long = 0,
    val socialDeliveriesCompleted: Long = 0,
    val socialDeliveriesAborted: Long = 0,
    val requestedBytesTotal: Long = 0,
    val declaredBytesTotal: Long = 0,
    val sentBytesTotal: Long = 0,
    val wireBytesSentTotal: Long = 0,
    val lastDeclaredTitleBytes: Int? = null,
    val lastDeclaredBodyBytes: Int? = null,
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
    PEER_CONNECTED,
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
    SOCIAL_FRAGMENT_DISPATCHED,
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
    SETTINGS_ACL_DISCONNECTED,
    SOCIAL_ACL_DISCONNECTED,
    NOTIFICATIONS_ONLY_CHANGED,
    PROBE_CHANGED,
    PROBE_DETAIL_MARKED,
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
    REPEAT_LIMIT,
    LINK_UNAVAILABLE,
    SETTINGS_READ_FAILED,
    SERVICE_NOT_FOUND,
    SUBSCRIPTION_NOT_QUEUED,
    ACL_DISCONNECTED,
}

enum class BleSubscription { NOTIFICATION_SOURCE, DATA_SOURCE }

data class BleDiagnosticState(
    val phase: BlePhase,
    val notificationsOnly: Boolean,
    val socialRegistered: Boolean,
    val socialPeerConnected: Boolean,
    val notificationSourceSubscribed: Boolean,
    val dataSourceSubscribed: Boolean,
    val settingsConnected: Boolean,
    val settingsConnecting: Boolean,
    val socialMtu: Int,
    val clientMtu: Int?,
)

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
    val frameIndex: Int? = null,
    val frameCount: Int? = null,
    val pacingMillis: Long? = null,
    val dispatchToCallbackMillis: Long? = null,
    val gapFromPreviousCallbackMillis: Long? = null,
    val responseBytes: Int? = null,
    val completedBytes: Int? = null,
    val repeatCount: Int? = null,
    val subscription: BleSubscription? = null,
    val subscriptionEnabled: Boolean? = null,
    val probeId: Long? = null,
    val probePart: Int? = null,
    val probeParts: Int? = null,
    val retryDelayMs: Long? = null,
    val stateAtEvent: BleDiagnosticState? = null,
)

internal fun BleState.recordTransportEvent(entry: BleDiagnostic): BleState {
    val title = entry.attributes.firstOrNull { it.id == SocialAttribute.TITLE.id }
    val body = entry.attributes.firstOrNull { it.id == SocialAttribute.MESSAGE.id }
    return when (entry.event) {
        BleDiagnosticEvent.PEER_CONNECTED -> copy(socialConnections = socialConnections + 1)
        BleDiagnosticEvent.PEER_DISCONNECTED -> copy(socialDisconnects = socialDisconnects + 1)
        BleDiagnosticEvent.CONNECTION_STARTED -> copy(settingsConnectionAttempts = settingsConnectionAttempts + 1)
        BleDiagnosticEvent.CONNECTION_CONNECTED -> copy(settingsConnections = settingsConnections + 1)
        BleDiagnosticEvent.CONNECTION_DISCONNECTED -> copy(settingsDisconnects = settingsDisconnects + 1)
        BleDiagnosticEvent.SETTINGS_RETRY_SCHEDULED -> copy(settingsRetries = settingsRetries + 1)
        BleDiagnosticEvent.RETRY_SCHEDULED -> copy(socialServerRetries = socialServerRetries + 1)
        BleDiagnosticEvent.SOCIAL_DELIVERY_COMPLETED -> copy(socialDeliveriesCompleted = socialDeliveriesCompleted + 1)
        BleDiagnosticEvent.SOCIAL_DELIVERY_ABORTED -> copy(socialDeliveriesAborted = socialDeliveriesAborted + 1)
        BleDiagnosticEvent.CONTROL_REQUEST_RECEIVED -> copy(
            attributeRequests = attributeRequests + entry.attributes.size,
            requestedBytesTotal = requestedBytesTotal + entry.attributes.sumOf { (it.requestedMaxBytes ?: 0).toLong() },
            lastRequestedTitleBytes = title?.requestedMaxBytes ?: lastRequestedTitleBytes,
            lastRequestedBodyBytes = body?.requestedMaxBytes ?: lastRequestedBodyBytes,
        )
        BleDiagnosticEvent.CONTROL_RESPONSE_QUEUED -> copy(
            declaredBytesTotal = declaredBytesTotal + entry.attributes.sumOf { (it.payloadBytes ?: 0).toLong() },
            lastDeclaredTitleBytes = title?.payloadBytes ?: lastDeclaredTitleBytes,
            lastDeclaredBodyBytes = body?.payloadBytes ?: lastDeclaredBodyBytes,
        )
        BleDiagnosticEvent.CONTROL_RESPONSE_COMPLETED -> copy(
            sentBytesTotal = sentBytesTotal + entry.attributes.sumOf { (it.payloadBytes ?: 0).toLong() },
            lastSentTitleBytes = title?.payloadBytes ?: lastSentTitleBytes,
            lastSentBodyBytes = body?.payloadBytes ?: lastSentBodyBytes,
        )
        BleDiagnosticEvent.SOCIAL_FRAGMENT_SENT -> copy(
            fragmentsSent = fragmentsSent + 1,
            wireBytesSentTotal = wireBytesSentTotal + entry.frameBytes.sumOf(Int::toLong),
        )
        else -> this
    }
}
