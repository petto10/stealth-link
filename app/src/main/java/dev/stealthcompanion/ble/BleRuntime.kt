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

    suspend fun send(
        key: String,
        title: String,
        text: String,
        packageName: String? = null,
        synthetic: Boolean = false,
        category: Int = 4,
    ): Boolean = controller.send(key, title, text, packageName, synthetic, category)

    suspend fun remove(key: String): Boolean = controller.remove(key)

    fun discardPersonal() = controller.discardPersonal()

    fun refreshSettings() = controller.refreshSettings()

    fun exportDiagnostics(): String = buildString {
        appendLine("Stealth Link BLE diagnostics v1")
        appendLine("phase=${state.value.phase.name}")
        appendLine("social_ready=${state.value.socialReady}")
        appendLine("encrypted_subscription_required=${state.value.encryptedByPolicy}")
        appendLine("mtu=${state.value.mtu}")
        appendLine("settings_connected=${state.value.settingsConnected}")
        appendLine("synthetic_only=${state.value.syntheticOnly}")
        diagnostics.value.forEach { entry ->
            append(entry.elapsedMs)
            append("ms ")
            append(entry.event.name)
            entry.status?.let { append(" ($it)") }
            appendLine()
        }
    }

    private fun serviceIntent() = Intent(controller.context, SocialConnectionService::class.java)

    internal fun serviceStarted(deviceAddress: String, socialAddress: String?) =
        controller.start(deviceAddress, socialAddress ?: deviceAddress)

    internal fun serviceStopped() = controller.stop()
    internal fun foregroundFailed() = controller.foregroundFailed()
}
