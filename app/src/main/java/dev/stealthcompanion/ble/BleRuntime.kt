package dev.stealthcompanion.ble

import android.app.Application
import android.content.Intent
import android.os.Build
import dev.stealthcompanion.core.protocol.ProtocolOptions
import kotlinx.coroutines.flow.StateFlow

object BleRuntime {
    private lateinit var controller: BleController

    @Synchronized
    fun init(application: Application) {
        if (!::controller.isInitialized) controller = BleController(application)
    }

    val state: StateFlow<BleState> get() = controller.state
    val diagnostics: StateFlow<List<BleDiagnostic>> get() = controller.diagnostics

    fun bondedDevices(): List<BleDeviceChoice> = controller.bondedDevices()
    fun hasConnectPermission(): Boolean = controller.hasConnectPermission()

    fun start(deviceAddress: String, socialAddress: String? = null) {
        val intent = serviceIntent()
            .putExtra(SocialConnectionService.SETTINGS_ADDRESS, deviceAddress)
            .putExtra(SocialConnectionService.SOCIAL_ADDRESS, socialAddress)
        try {
            if (Build.VERSION.SDK_INT >= 26) controller.context.startForegroundService(intent)
            else controller.context.startService(intent)
        } catch (_: RuntimeException) {
            controller.foregroundFailed()
        }
    }

    fun stop() {
        controller.context.stopService(serviceIntent())
        controller.stop()
    }

    fun setProbeOptions(
        options: ProtocolOptions = ProtocolOptions(),
        syntheticOnlyUnencrypted: Boolean = false,
    ) = controller.setProbeOptions(options, syntheticOnlyUnencrypted)

    fun setLongTextEnabled(enabled: Boolean) = controller.setLongTextEnabled(enabled)
    fun setNotificationsOnly(enabled: Boolean) = controller.setNotificationsOnly(enabled)
    internal fun markProbeDetail(id: Long) = controller.markProbeDetail(id)

    internal suspend fun send(
        key: String,
        title: String,
        text: String,
        packageName: String? = null,
        synthetic: Boolean = false,
        category: Int = 4,
        applicationName: String? = null,
        longTextOverride: Boolean? = null,
        probe: BleProbe? = null,
    ): Boolean = controller.send(key, title, text, packageName, synthetic, category, applicationName, longTextOverride, probe)

    suspend fun remove(key: String): Boolean = controller.remove(key)

    fun discardPersonal() = controller.discardPersonal()

    fun refreshSettings() = controller.refreshSettings()

    fun exportDiagnostics(): String = buildString {
        val current = state.value
        appendLine("Stealth Link BLE diagnostics v3")
        appendLine("phase=${current.phase.name}")
        appendLine("notifications_only=${current.notificationsOnly}")
        appendLine("social_server_registered=${current.socialRegistered}")
        appendLine("social_peer_connected=${current.socialPeerConnected}")
        appendLine("notification_source_subscribed=${current.notificationSourceSubscribed}")
        appendLine("data_source_subscribed=${current.dataSourceSubscribed}")
        appendLine("social_ready=${current.socialReady}")
        appendLine("encrypted_subscription_required=${current.encryptedByPolicy}")
        appendLine("social_mtu=${current.mtu}")
        appendLine("social_mtu_source=${if (current.socialMtuKnown) "server_callback" else "att_default"}")
        appendLine("configuration_client_mtu=${current.clientMtu ?: "unknown"}")
        appendLine("same_selected_peer=${current.samePeerForMtu}")
        appendLine("settings_connected=${current.settingsConnected}")
        appendLine("settings_connecting=${current.settingsConnecting}")
        appendLine("settings_retry_delay_ms=${current.settingsRetryDelayMs ?: "none"}")
        appendLine("social_retry_delay_ms=${current.socialRetryDelayMs ?: "none"}")
        appendLine("social_connections=${current.socialConnections}")
        appendLine("social_disconnects=${current.socialDisconnects}")
        appendLine("settings_connection_attempts=${current.settingsConnectionAttempts}")
        appendLine("settings_connections=${current.settingsConnections}")
        appendLine("settings_disconnects=${current.settingsDisconnects}")
        appendLine("settings_retries=${current.settingsRetries}")
        appendLine("social_server_retries=${current.socialServerRetries}")
        appendLine("synthetic_only=${current.syntheticOnly}")
        appendLine("long_text_enabled=${current.longTextEnabled}")
        appendLine("attribute_requests=${current.attributeRequests}")
        appendLine("attribute_requested_bytes_total=${current.requestedBytesTotal}")
        appendLine("attribute_declared_bytes_total=${current.declaredBytesTotal}")
        appendLine("attribute_sent_bytes_total=${current.sentBytesTotal}")
        appendLine("data_wire_bytes_sent_total=${current.wireBytesSentTotal}")
        appendLine("data_frames_sent=${current.fragmentsSent}")
        appendLine("social_deliveries_completed=${current.socialDeliveriesCompleted}")
        appendLine("social_deliveries_aborted=${current.socialDeliveriesAborted}")
        appendLine("last_requested_title_bytes=${current.lastRequestedTitleBytes ?: "unknown"}")
        appendLine("last_requested_body_bytes=${current.lastRequestedBodyBytes ?: "unknown"}")
        appendLine("last_declared_title_bytes=${current.lastDeclaredTitleBytes ?: "unknown"}")
        appendLine("last_declared_body_bytes=${current.lastDeclaredBodyBytes ?: "unknown"}")
        appendLine("last_sent_title_bytes=${current.lastSentTitleBytes ?: "unknown"}")
        appendLine("last_sent_body_bytes=${current.lastSentBodyBytes ?: "unknown"}")
        diagnostics.value.forEach { entry ->
            append(entry.elapsedMs)
            append("ms ")
            append(entry.event.name)
            entry.status?.let { append(" status=$it") }
            entry.cause?.let { append(" cause=${it.name}") }
            entry.retryDelayMs?.let { append(" retry_delay_ms=$it") }
            entry.probeId?.let { append(" probe=$it") }
            entry.probePart?.let { append(" part=$it/${entry.probeParts ?: 1}") }
            entry.responseNumber?.let { append(" response=$it") }
            entry.command?.let { append(" command=$it") }
            entry.frameIndex?.let { append(" frame=$it/${entry.frameCount ?: 1}") }
            entry.pacingMillis?.let { append(" pacing_ms=$it") }
            entry.dispatchToCallbackMillis?.let { append(" callback_ms=$it") }
            entry.gapFromPreviousCallbackMillis?.let { append(" gap_ms=$it") }
            entry.responseBytes?.let { append(" declared_wire_bytes=$it") }
            entry.completedBytes?.let { append(" completed_wire_bytes=$it") }
            entry.repeatCount?.let { append(" repeat=$it") }
            entry.subscription?.let { append(" subscription=${it.name}") }
            entry.subscriptionEnabled?.let { append(" subscription_enabled=$it") }
            if (entry.attributes.isNotEmpty()) {
                append(" attributes=")
                append(entry.attributes.joinToString(",") { attribute ->
                    "${attribute.id}/${attribute.requestedMaxBytes ?: "-"}/${attribute.payloadBytes ?: "-"}"
                })
            }
            if (entry.frameBytes.isNotEmpty()) {
                append(" frame_bytes=")
                append(entry.frameBytes.joinToString(","))
            }
            entry.stateAtEvent?.let { snapshot ->
                append(" phase=${snapshot.phase.name}")
                append(" notifications_only=${snapshot.notificationsOnly}")
                append(" social_registered=${snapshot.socialRegistered}")
                append(" peer_connected=${snapshot.socialPeerConnected}")
                append(" ns=${snapshot.notificationSourceSubscribed} ds=${snapshot.dataSourceSubscribed}")
                append(" settings_connected=${snapshot.settingsConnected} settings_connecting=${snapshot.settingsConnecting}")
                append(" social_mtu=${snapshot.socialMtu} client_mtu=${snapshot.clientMtu ?: "unknown"}")
            }
            appendLine()
        }
    }

    private fun serviceIntent() = Intent(controller.context, SocialConnectionService::class.java)

    internal fun serviceStarted(deviceAddress: String, socialAddress: String?) =
        controller.start(deviceAddress, socialAddress ?: deviceAddress)

    internal fun serviceStopped() = controller.stop()
    internal fun foregroundFailed() = controller.foregroundFailed()
}
