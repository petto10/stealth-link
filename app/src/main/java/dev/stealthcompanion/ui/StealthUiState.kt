package dev.stealthcompanion.ui

import dev.stealthcompanion.core.protocol.DisplayTextPolicy
import dev.stealthcompanion.core.protocol.LengthProbe

enum class CompanionMode { SIMULATOR, CONTROLLER }
enum class ControllerRole { CONFIGURATION, SOCIAL, BOTH }
enum class ConnectionState { IDLE, CONNECTING, WAITING_FOR_SUBSCRIPTION, READY, RETRYING, UNAVAILABLE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class LanguageMode { SYSTEM, ITALIAN, ENGLISH }
enum class DisplayCapability {
    AUTO_OPEN, WAKE_DISPLAY, LONG_TEXT, SCROLL, APP_ICONS, CATEGORIES, VIBRATION, DURATION, UPDATE, REMOVAL,
}
enum class ProbeObservation { UNTESTED, OBSERVED, NOT_OBSERVED, INCONCLUSIVE }
enum class LengthProbeOutcome { QUEUED, GATT_COMPLETE, SIMULATOR_COMPLETE, INCOMPLETE }
enum class UiFeedback {
    TEST_QUEUED, TEST_UNAVAILABLE, PROBE_QUEUED, PROBE_MANUAL, PROBE_UNAVAILABLE,
    SETTINGS_REQUESTED, SETTINGS_UNAVAILABLE,
    PROFILES_IMPORTED, PROFILES_EXPORTED, DIAGNOSTICS_EXPORTED, FILE_ERROR,
}

data class RuleUi(
    val enabled: Boolean = false,
    val allowSilent: Boolean = false,
    val includeAppName: Boolean = true,
    val allowedChannels: Set<String> = emptySet(),
    val allowedContacts: Set<String> = emptySet(),
    val blockedContacts: Set<String> = emptySet(),
    val allowedConversations: Set<String> = emptySet(),
    val blockedConversations: Set<String> = emptySet(),
    val paginate: Boolean = true,
)

data class ObservedAppUi(
    val packageName: String,
    val label: String,
    val observedChannels: Set<String> = emptySet(),
    val rule: RuleUi = RuleUi(),
)

data class ControllerUi(val id: String, val name: String, val role: ControllerRole = ControllerRole.CONFIGURATION)

data class DisplayPreviewUi(val title: String, val body: String)

data class DiagnosticsUi(
    val queued: Int = 0,
    val sent: Int = 0,
    val dropped: Int = 0,
    val reconnects: Int = 0,
    val mtu: Int? = null,
    val socialMtu: Int? = null,
    val socialMtuKnown: Boolean = false,
    val configurationMtu: Int? = null,
    val attributeRequests: Int? = null,
    val fragmentsSent: Int? = null,
    val notificationSourceSubscribed: Boolean? = null,
    val dataSourceSubscribed: Boolean? = null,
    val requestedBytesTotal: Long? = null,
    val declaredBytesTotal: Long? = null,
    val sentBytesTotal: Long? = null,
    val lastRequestedTitleBytes: Int? = null,
    val lastRequestedBodyBytes: Int? = null,
    val lastDeclaredTitleBytes: Int? = null,
    val lastDeclaredBodyBytes: Int? = null,
    val lastSentTitleBytes: Int? = null,
    val lastSentBodyBytes: Int? = null,
)

data class ProfileUi(val id: String, val name: String)

data class ProfilesUi(
    val local: List<ProfileUi> = emptyList(),
    val selectedLocalProfileId: String? = null,
    val activeSlot: Int? = null,
    val slotNames: Map<Int, String> = emptyMap(),
)

data class ConfigurationUi(
    val connected: Boolean = false,
    val reading: Boolean = false,
    val complete: Boolean = false,
    val powerMode: Int? = null,
    val displayBrightness: Int? = null,
    val ledBrightness: Int? = null,
    val screenTimeoutMinutes: Int? = null,
    val sleepTimeoutMinutes: Int? = null,
    val chargeLedBrightness: Int? = null,
    val batteryPercent: Int? = null,
    val firmwareRevision: String? = null,
    val hardwareRevision: String? = null,
    val softwareRevision: String? = null,
)

data class StealthUiState(
    val mode: CompanionMode = CompanionMode.SIMULATOR,
    val connection: ConnectionState = ConnectionState.IDLE,
    val isRunning: Boolean = false,
    val notificationAccess: Boolean = false,
    val bluetoothPermission: Boolean = false,
    val userPaused: Boolean = false,
    val privacyWhileLocked: Boolean = true,
    val devices: List<ControllerUi> = emptyList(),
    val selectedDeviceId: String? = null,
    val selectedSocialDeviceId: String? = null,
    val notificationsOnly: Boolean = false,
    val apps: List<ObservedAppUi> = emptyList(),
    val preview: DisplayPreviewUi? = null,
    val diagnostics: DiagnosticsUi = DiagnosticsUi(),
    val profiles: ProfilesUi = ProfilesUi(),
    val configuration: ConfigurationUi = ConfigurationUi(),
    val probesEnabled: Boolean = false,
    val relaxedSynthetic: Boolean = false,
    val extendedText: Boolean = true,
    val fragmentDelayMs: Int = 25,
    val bodyByteLimit: Int = DisplayTextPolicy.DEFAULT_BODY_LIMIT_BYTES,
    val paginateNotifications: Boolean = true,
    val compactLines: Boolean = false,
    val reversePageDelivery: Boolean = false,
    val maxNotificationPages: Int = DisplayTextPolicy.MAX_PAGES,
    val simulatorMtu: Int = 23,
    val lastLengthProbeId: Long? = null,
    val lastLengthProbeOutcome: LengthProbeOutcome? = null,
    val observations: Map<DisplayCapability, ProbeObservation> = emptyMap(),
    val feedback: UiFeedback? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: LanguageMode = LanguageMode.SYSTEM,
)

sealed interface UiAction {
    data class SetMode(val value: CompanionMode) : UiAction
    data object OpenNotificationAccess : UiAction
    data object RequestBluetooth : UiAction
    data object RefreshDevices : UiAction
    data class SelectDevice(val id: String) : UiAction
    data class SelectSocialDevice(val id: String?) : UiAction
    data class SetNotificationsOnly(val value: Boolean) : UiAction
    data object StartCompanion : UiAction
    data object StopCompanion : UiAction
    data object RecoverConnection : UiAction
    data class SetPaused(val value: Boolean) : UiAction
    data class SetLockPrivacy(val value: Boolean) : UiAction
    data class SetRule(val packageName: String, val rule: RuleUi) : UiAction
    data class SendSynthetic(val title: String, val body: String) : UiAction
    data class SetProbesEnabled(val value: Boolean) : UiAction
    data class SetRelaxedSynthetic(val value: Boolean) : UiAction
    data class SetExtendedText(val value: Boolean) : UiAction
    data class SetFragmentDelay(val value: Int) : UiAction
    data class SetBodyByteLimit(val value: Int) : UiAction
    data class SetPaginateNotifications(val value: Boolean) : UiAction
    data class SetCompactLines(val value: Boolean) : UiAction
    data class SetReversePageDelivery(val value: Boolean) : UiAction
    data class SetMaxNotificationPages(val value: Int) : UiAction
    data class SetSimulatorMtu(val value: Int) : UiAction
    data class RunProbe(val capability: DisplayCapability, val extended: Boolean? = null) : UiAction
    data class SendLengthProbe(val spec: LengthProbe, val extended: Boolean) : UiAction
    data object MarkLengthProbeDetail : UiAction
    data class SetObservation(val capability: DisplayCapability, val value: ProbeObservation) : UiAction
    data object ExportDiagnostics : UiAction
    data object ReadSettings : UiAction
    data class AddProfile(val name: String) : UiAction
    data class RenameProfile(val id: String, val name: String) : UiAction
    data class SelectLocalProfile(val id: String?) : UiAction
    data class RemoveProfile(val id: String) : UiAction
    data object ImportProfiles : UiAction
    data object ExportProfiles : UiAction
    data class SetTheme(val value: ThemeMode) : UiAction
    data class SetLanguage(val value: LanguageMode) : UiAction
    data object ClearFeedback : UiAction
}
