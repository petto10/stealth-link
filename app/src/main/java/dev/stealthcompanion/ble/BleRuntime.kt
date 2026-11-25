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

    suspend fun send(
        key: String,
        title: String,
        text: String,
        packageName: String? = null,
        synthetic: Boolean = false,
        category: Int = 4,
        applicationName: String? = null,
        longTextOverride: Boolean? = null,
    ): Boolean = controller.send(key, title, text, packageName, synthetic, category, applicationName, longTextOverride)

    suspend fun remove(key: String): Boolean = controller.remove(key)

    fun discardPersonal() = controller.discardPersonal()

    fun refreshSettings() = controller.refreshSettings()

    fun exportDiagnostics(): String = buildString {
        val current = state.value
        appendLine("Stealth Link BLE diagnostics v2")
        appendLine("phase=${current.phase.name}")
        appendLine("social_ready=${current.socialReady}")
        appendLine("encrypted_subscription_required=${current.encryptedByPolicy}")
        appendLine("social_mtu=${current.mtu}")
        appendLine("social_mtu_source=${if (current.socialMtuKnown) "server_callback" else "att_default"}")
        appendLine("configuration_client_mtu=${current.clientMtu ?: "unknown"}")
        appendLine("same_selected_peer=${current.samePeerForMtu}")
        appendLine("settings_connected=${current.settingsConnected}")
        appendLine("synthetic_only=${current.syntheticOnly}")
        appendLine("long_text_enabled=${current.longTextEnabled}")
        appendLine("attribute_requests=${current.attributeRequests}")
        appendLine("data_frames_sent=${current.fragmentsSent}")
        appendLine("last_requested_title_bytes=${current.lastRequestedTitleBytes ?: "unknown"}")
        appendLine("last_requested_body_bytes=${current.lastRequestedBodyBytes ?: "unknown"}")
        appendLine("last_sent_title_bytes=${current.lastSentTitleBytes ?: "unknown"}")
        appendLine("last_sent_body_bytes=${current.lastSentBodyBytes ?: "unknown"}")
        diagnostics.value.forEach { entry ->
            append(entry.elapsedMs)
            append("ms ")
            append(entry.event.name)
            entry.status?.let { append(" status=$it") }
            entry.cause?.let { append(" cause=${it.name}") }
            entry.responseNumber?.let { append(" response=$it") }
            entry.command?.let { append(" command=$it") }
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
            appendLine()
        }
    }

    private fun serviceIntent() = Intent(controller.context, SocialConnectionService::class.java)

    internal fun serviceStarted(deviceAddress: String, socialAddress: String?) =
        controller.start(deviceAddress, socialAddress ?: deviceAddress)

    internal fun serviceStopped() = controller.stop()
    internal fun foregroundFailed() = controller.foregroundFailed()
}
