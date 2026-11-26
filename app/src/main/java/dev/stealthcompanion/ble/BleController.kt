package dev.stealthcompanion.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.stealthcompanion.configuration.ControllerDeviceStatus
import dev.stealthcompanion.configuration.ControllerStatusCodec
import dev.stealthcompanion.configuration.ControllerStatusField
import dev.stealthcompanion.core.protocol.ProtocolOptions
import dev.stealthcompanion.core.protocol.SocialCodec
import dev.stealthcompanion.core.protocol.SocialPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.random.Random

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class BleController(val context: Application) {
    private data class Target(val configuration: String, val social: String)
    private data class StatusRead(val field: ControllerStatusField, val characteristic: BluetoothGattCharacteristic)
    private enum class ClientStage { CONNECTING, DISCOVERING, SUBSCRIBING, READY }

    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val createdAt = SystemClock.elapsedRealtime()
    private val mutableState = MutableStateFlow(BleState())
    private val mutableDiagnostics = MutableStateFlow<List<BleDiagnostic>>(emptyList())
    val state = mutableState.asStateFlow()
    val diagnostics = mutableDiagnostics.asStateFlow()

    private var target: Target? = null
    private var social: GattSocialServer? = null
    private var client: BluetoothGatt? = null
    private var clientStage: ClientStage? = null
    private var settingsCharacteristic: BluetoothGattCharacteristic? = null
    private val clientTimeout = delayedAction()
    private val socialRetryTask = delayedAction()
    private val settingsRetryTask = delayedAction()
    private val settingsAclFallback = delayedAction()
    private val socialAclFallback = delayedAction()
    private val settingsBackoff = BleReconnectBackoff()
    private val socialBackoff = BleReconnectBackoff()
    private var settingsReadyAt: Long? = null
    private var socialRegisteredAt: Long? = null
    private val mtuRequestTimeout = delayedAction()
    private val deviceStatusTimeout = delayedAction()
    private var deviceStatusRefreshRequested = false
    private val deviceStatusReads = ArrayDeque<StatusRead>()
    private var pendingDeviceStatusRead: StatusRead? = null
    private var mtuRequestAttempted = false
    private var mtuSkipCause: BleDiagnosticCause? = null
    private var awaitingSettingsRead = false
    private var longTextEnabled = false
    private var extendedFragmentDelayMs = BlePacingPolicy.DEFAULT_EXTENDED_DELAY_MS
    private var notificationsOnly = false
    private var activeProbe: BleProbe? = null
    private var receiverRegistered = false
    private var syntheticOnlyUnencrypted = false
    private var protocolOptions = ProtocolOptions()
    private val syntheticOnly: Boolean get() = syntheticOnlyUnencrypted || protocolOptions != ProtocolOptions()

    private val settings = ReadOnlyGattSettings(
        handler,
        publish = {
            val finished = mutableState.value.settings.reading && !it.reading
            mutableState.value = mutableState.value.copy(settings = it)
            val owner = client
            if (finished && owner != null) {
                handler.post {
                    if (client === owner && !mutableState.value.settings.reading) {
                        if (deviceStatusRefreshRequested) beginDeviceStatusReads(owner)
                        else maybeRequestClientMtu()
                    }
                }
            }
        },
        diagnose = ::diagnose,
        disconnected = { retryConfiguration(BleDiagnosticCause.SETTINGS_READ_FAILED) },
    )

    fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun bondedDevices(): List<BleDeviceChoice> {
        if (!hasConnectPermission()) return emptyList()
        return runCatching {
            bluetooth?.adapter?.bondedDevices.orEmpty().mapNotNull { device ->
                val name = device.name ?: return@mapNotNull null
                val role = deviceRole(name) ?: return@mapNotNull null
                BleDeviceChoice(device.address, name, role)
            }.sortedBy { it.role.ordinal }
        }.getOrDefault(emptyList())
    }

    fun start(deviceAddress: String, socialAddress: String) = handler.post {
        val selected = Target(deviceAddress, socialAddress)
        val existing = target
        if (existing != null && existing == selected) {
            if (social == null && !socialRetryTask.isPending) rebuildSocial()
            ensureSettingsConnection(existing)
            return@post
        }
        registerReceiver()
        closeLinks(clearSettings = true)
        target = selected
        socialBackoff.reset()
        settingsBackoff.reset()
        mutableState.value = mutableState.value.copy(
            phase = BlePhase.STARTING,
            samePeerForMtu = selected.configuration == selected.social,
        )
        diagnose(BleDiagnosticEvent.SESSION_STARTED, null)
        rebuildSocial()
        ensureSettingsConnection(selected)
    }

    fun recoverConnection() = handler.post {
        val selection = target ?: return@post
        if (!validateSession()) return@post
        diagnose(BleDiagnosticEvent.CONNECTION_RECOVERY_REQUESTED, null)
        socialBackoff.reset()
        closeSocial()
        if (!notificationsOnly && !mutableState.value.settingsConnected) {
            settingsBackoff.reset()
            closeConfiguration(clearSettings = false)
        }
        val hasSettingsEndpoint = settingsDevice(selection) != null
        val canAttachIndependently = notificationsOnly || selection.configuration != selection.social || !hasSettingsEndpoint
        rebuildSocial(requestAttach = canAttachIndependently)
        ensureSettingsConnection(selection)
    }

    fun stop() = handler.post {
        if (target == null && !receiverRegistered) return@post
        closeLinks(clearSettings = true)
        target = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        mutableState.value = mutableState.value.copy(phase = BlePhase.IDLE, samePeerForMtu = false)
        diagnose(BleDiagnosticEvent.SESSION_STOPPED, null)
    }

    fun foregroundFailed() = handler.post {
        if (!hasConnectPermission()) diagnose(BleDiagnosticEvent.PERMISSION_MISSING, null)
        else diagnose(BleDiagnosticEvent.FOREGROUND_FAILED, null)
        mutableState.value = mutableState.value.copy(
            phase = if (hasConnectPermission()) BlePhase.UNAVAILABLE else BlePhase.PERMISSION_REQUIRED,
        )
    }

    fun setProbeOptions(options: ProtocolOptions, unencrypted: Boolean) {
        if (Looper.myLooper() === handler.looper) applyProbeOptions(options, unencrypted)
        else handler.post { applyProbeOptions(options, unencrypted) }
    }

    private fun applyProbeOptions(options: ProtocolOptions, unencrypted: Boolean) {
        if (options == protocolOptions && unencrypted == syntheticOnlyUnencrypted) return
        protocolOptions = options
        syntheticOnlyUnencrypted = unencrypted
        if (target != null) {
            socialBackoff.reset()
            closeSocial()
        }
        diagnose(BleDiagnosticEvent.PROBE_CHANGED, if (syntheticOnly) 1 else 0)
        mutableState.value = mutableState.value.copy(syntheticOnly = syntheticOnly)
        if (target != null) rebuildSocial()
    }

    fun setLongTextEnabled(enabled: Boolean) {
        if (Looper.myLooper() === handler.looper) applyLongTextPreference(enabled)
        else handler.post { applyLongTextPreference(enabled) }
    }

    private fun applyLongTextPreference(enabled: Boolean) {
        if (longTextEnabled == enabled) return
        longTextEnabled = enabled
        if (!enabled) social?.discardLongResponses()
        mutableState.value = mutableState.value.copy(longTextEnabled = enabled)
        diagnose(BleDiagnosticEvent.LONG_TEXT_CHANGED, if (enabled) 1 else 0)
        if (enabled) maybeRequestClientMtu()
    }

    fun setExtendedFragmentDelayMs(delayMs: Int) {
        val delay = BlePacingPolicy.normalizeRegularDelay(delayMs)
        if (Looper.myLooper() === handler.looper) applyExtendedFragmentDelay(delay)
        else handler.post { applyExtendedFragmentDelay(delay) }
    }

    private fun applyExtendedFragmentDelay(delayMs: Int) {
        if (extendedFragmentDelayMs == delayMs) return
        extendedFragmentDelayMs = delayMs
        mutableState.value = mutableState.value.copy(extendedFragmentDelayMs = delayMs)
    }

    fun setNotificationsOnly(enabled: Boolean) {
        if (Looper.myLooper() === handler.looper) applyNotificationsOnly(enabled)
        else handler.post { applyNotificationsOnly(enabled) }
    }

    private fun applyNotificationsOnly(enabled: Boolean) {
        if (notificationsOnly == enabled) return
        notificationsOnly = enabled
        settingsBackoff.reset()
        mutableState.value = mutableState.value.copy(notificationsOnly = enabled)
        if (enabled) closeConfiguration(clearSettings = false)
        diagnose(BleDiagnosticEvent.NOTIFICATIONS_ONLY_CHANGED, if (enabled) 1 else 0)
        if (!enabled) target?.let(::ensureSettingsConnection)
    }

    suspend fun send(
        key: String,
        title: String,
        text: String,
        packageName: String?,
        synthetic: Boolean,
        category: Int,
        applicationName: String?,
        longTextOverride: Boolean?,
        probe: BleProbe? = null,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (category !in 0..11 || (!synthetic && syntheticOnly)) return@withContext false
        if (!validateSession()) return@withContext false
        val transport = social ?: return@withContext false
        val payload = SocialPayload(
            title,
            text,
            if (synthetic) packageName else null,
            if (synthetic && packageName != null) applicationName?.take(128) ?: "Social test" else null,
        )
        val extended = if (synthetic && longTextOverride != null) longTextOverride else longTextEnabled && !syntheticOnly
        val syntheticProbe = probe.takeIf { synthetic }
        val result = transport.send(
            key, payload, synthetic, category, extended, syntheticProbe, extendedFragmentDelayMs,
        ) ?: return@withContext false
        if (syntheticProbe != null) activeProbe = syntheticProbe
        try {
            result.await()
        } finally {
            transport.cancelSend(result)
            if (activeProbe === syntheticProbe) activeProbe = null
        }
    }

    suspend fun remove(key: String): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!validateSession()) return@withContext false
        social?.remove(key)?.await() ?: false
    }

    fun discardPersonal() {
        if (Looper.myLooper() === handler.looper) social?.discardPersonal()
        else handler.post { social?.discardPersonal() }
    }

    fun refreshSettings() {
        handler.post {
            if (notificationsOnly || !validateSession() || !mutableState.value.settingsConnected ||
                deviceStatusRefreshRequested || pendingDeviceStatusRead != null || mutableState.value.settings.reading
            ) return@post
            if (mtuRequestTimeout.isPending) awaitingSettingsRead = true
            else beginReadOnlyRefresh()
        }
    }

    fun markProbeDetail(id: Long) {
        handler.post { reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.PROBE_DETAIL_MARKED, probeId = id)) }
    }

    private fun rebuildSocial(requestAttach: Boolean = false) {
        val selection = target ?: return
        if (social != null || !validateSession()) return
        val socialDevice = bondedDevice(selection.social)
        if (socialDevice == null || bluetooth == null) {
            diagnose(BleDiagnosticEvent.DEVICE_UNAVAILABLE, null)
            mutableState.value = mutableState.value.copy(phase = BlePhase.UNAVAILABLE)
            return
        }
        mutableState.value = mutableState.value.copy(
            phase = BlePhase.STARTING,
            syntheticOnly = syntheticOnly,
        )
        val server = GattSocialServer(
            context = context,
            bluetooth = bluetooth,
            handler = handler,
            options = protocolOptions,
            requireEncryption = !syntheticOnlyUnencrypted,
            recoveryPeer = socialDevice.takeIf { requestAttach },
            accepted = { device -> isApproved(device, selection.social) },
            diagnose = ::diagnose,
            detail = ::reportDiagnostic,
            changed = {
                synchronizeSocialState()
                ensureSettingsConnection(selection)
                maybeRequestClientMtu()
            },
            restart = ::scheduleSocialRetry,
        )
        social = server
        if (!server.open()) {
            diagnose(BleDiagnosticEvent.SERVER_FAILED, null)
            scheduleSocialRetry()
        }
    }

    private fun ensureSettingsConnection(selection: Target) {
        if (notificationsOnly || client != null || settingsRetryTask.isPending || target !== selection ||
            !validateSession()
        ) return
        if (selection.configuration == selection.social && social?.isRegistered == false) return
        val device = settingsDevice(selection)
        if (device == null) {
            if (bondedDevice(selection.configuration) != null) return
            reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CONNECTION_FAILED, cause = BleDiagnosticCause.LINK_UNAVAILABLE))
            return
        }
        connectSettings(device)
    }

    private fun settingsDevice(selection: Target): BluetoothDevice? = bondedDevice(selection.configuration)
        ?.takeIf { device -> runCatching { deviceRole(device.name) }.getOrNull() in SETTING_DEVICE_ROLES }

    private fun connectSettings(device: BluetoothDevice) {
        if (notificationsOnly || client != null || !validateSession()) return
        mutableState.value = mutableState.value.copy(
            settingsConnecting = true,
        )
        diagnose(BleDiagnosticEvent.CONNECTION_STARTED, null)
        val gatt = runCatching { device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) {
            reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CONNECTION_FAILED, cause = BleDiagnosticCause.REQUEST_NOT_QUEUED))
            retryConfiguration(BleDiagnosticCause.REQUEST_NOT_QUEUED)
            return
        }
        client = gatt
        clientStage = ClientStage.CONNECTING
        scheduleClientTimeout()
    }

    private fun synchronizeSocialState() {
        val transport = social
        val ready = transport?.ready == true
        val registered = transport?.isRegistered == true
        val previous = mutableState.value
        if (registered && socialRegisteredAt == null) socialRegisteredAt = SystemClock.elapsedRealtime()
        mutableState.value = previous.copy(
            phase = when {
                ready -> BlePhase.READY
                registered -> BlePhase.WAITING_FOR_SUBSCRIPTION
                socialRetryTask.isPending -> BlePhase.RETRYING
                transport != null -> BlePhase.STARTING
                else -> previous.phase
            },
            socialReady = ready,
            encryptedByPolicy = ready && !syntheticOnlyUnencrypted,
            mtu = transport?.mtu ?: SocialCodec.DEFAULT_MTU,
            socialMtuKnown = transport?.reportedMtu != null,
            socialRegistered = registered,
            socialPeerConnected = transport?.peerConnected == true,
            notificationSourceSubscribed = transport?.notificationSourceSubscribed == true,
            dataSourceSubscribed = transport?.dataSourceSubscribed == true,
            currentMessageLimitBytes = transport?.currentMessageLimitBytes?.takeIf { ready },
        )
    }

    private fun validateSession(): Boolean {
        if (target == null) return false
        if (!hasConnectPermission()) {
            if (mutableState.value.phase != BlePhase.PERMISSION_REQUIRED) {
                diagnose(BleDiagnosticEvent.PERMISSION_MISSING, null)
                closeLinks(clearSettings = false)
                mutableState.value = mutableState.value.copy(phase = BlePhase.PERMISSION_REQUIRED)
            }
            return false
        }
        if (bluetooth == null) {
            mutableState.value = mutableState.value.copy(phase = BlePhase.UNAVAILABLE)
            return false
        }
        if (!runCatching { bluetooth.adapter?.isEnabled == true }.getOrDefault(false)) {
            if (mutableState.value.phase != BlePhase.BLUETOOTH_OFF) {
                diagnose(BleDiagnosticEvent.BLUETOOTH_DISABLED, null)
                closeLinks(clearSettings = false)
                mutableState.value = mutableState.value.copy(phase = BlePhase.BLUETOOTH_OFF)
            }
            return false
        }
        return true
    }

    private fun bondedDevice(address: String): BluetoothDevice? = runCatching {
        bluetooth?.adapter?.bondedDevices?.firstOrNull { isApproved(it, address) }
    }.getOrNull()

    private fun isApproved(device: BluetoothDevice, address: String): Boolean = runCatching {
        hasConnectPermission() && device.address == address &&
            device.bondState == BluetoothDevice.BOND_BONDED && deviceRole(device.name) != null
    }.getOrDefault(false)

    private fun deviceRole(name: String?): BleDeviceRole? = when (name?.trim()?.uppercase(Locale.ROOT)) {
        "STEALTH ULTRA APP", "TURTLE BEACH STEALTH ULTRA APP" -> BleDeviceRole.CONFIGURATION
        "STEALTH ANCS" -> BleDeviceRole.SOCIAL
        "STEALTH ULTRA", "TURTLE BEACH STEALTH ULTRA" -> BleDeviceRole.BOTH
        else -> null
    }

    private fun closeLinks(clearSettings: Boolean) {
        closeConfiguration(clearSettings)
        closeSocial()
    }

    private fun closeSocial() {
        socialRetryTask.cancel()
        socialAclFallback.cancel()
        socialRegisteredAt = null
        val server = social
        social = null
        server?.close()
        mutableState.value = mutableState.value.copy(
            socialReady = false,
            encryptedByPolicy = false,
            mtu = SocialCodec.DEFAULT_MTU,
            socialMtuKnown = false,
            socialRegistered = false,
            socialPeerConnected = false,
            notificationSourceSubscribed = false,
            dataSourceSubscribed = false,
            currentMessageLimitBytes = null,
            socialRetryDelayMs = null,
        )
    }

    private fun closeConfiguration(clearSettings: Boolean) {
        settingsRetryTask.cancel()
        settingsAclFallback.cancel()
        settingsReadyAt = null
        cancelClientTimeout()
        mtuRequestTimeout.cancel()
        clearDeviceStatusReads()
        mtuRequestAttempted = false
        mtuSkipCause = null
        awaitingSettingsRead = false
        settingsCharacteristic = null
        val gatt = client
        client = null
        clientStage = null
        mutableState.value = mutableState.value.copy(
            settingsConnected = false, settingsConnecting = false, settingsRetryDelayMs = null, clientMtu = null,
        )
        settings.onLinkEnded(clearSettings)
        if (!clearSettings && mutableState.value.settings.deviceStatus != ControllerDeviceStatus()) {
            settings.updateDeviceStatus(ControllerDeviceStatus())
        }
        if (gatt != null) {
            // A same-device settings client can share the physical link used by the Social server.
            // Release our client registration without explicitly disconnecting that peer.
            if (target?.let { it.configuration == it.social } != true) runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private fun retryConfiguration(cause: BleDiagnosticCause, status: Int? = null) {
        val selected = target ?: return
        if (notificationsOnly || settingsRetryTask.isPending) return
        val delay = nextDelay(settingsBackoff, settingsReadyAt)
        closeConfiguration(clearSettings = false)
        if (!validateSession()) return
        mutableState.value = mutableState.value.copy(settingsRetryDelayMs = delay)
        reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.SETTINGS_RETRY_SCHEDULED, status, cause, retryDelayMs = delay))
        settingsRetryTask.replace(delay) {
            if (target !== selected) return@replace
            mutableState.value = mutableState.value.copy(settingsRetryDelayMs = null)
            ensureSettingsConnection(selected)
        }
    }

    private fun scheduleSocialRetry() {
        val selected = target ?: return
        if (socialRetryTask.isPending) return
        val delay = nextDelay(socialBackoff, socialRegisteredAt)
        closeSocial()
        if (!validateSession()) return
        mutableState.value = mutableState.value.copy(phase = BlePhase.RETRYING, socialRetryDelayMs = delay)
        reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.RETRY_SCHEDULED, retryDelayMs = delay))
        socialRetryTask.replace(delay) {
            if (target !== selected) return@replace
            mutableState.value = mutableState.value.copy(socialRetryDelayMs = null)
            rebuildSocial()
        }
        ensureSettingsConnection(selected)
    }

    private fun nextDelay(backoff: BleReconnectBackoff, stableSince: Long?): Long =
        backoff.nextDelayMillis(SystemClock.elapsedRealtime(), stableSince, Random.nextLong(0, 251))

    private fun scheduleClientTimeout() {
        val owner = client ?: return
        clientTimeout.replace(CLIENT_TIMEOUT_MS) {
            if (client !== owner) return@replace
            reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CONNECTION_FAILED, cause = BleDiagnosticCause.CALLBACK_TIMEOUT))
            retryConfiguration(BleDiagnosticCause.CALLBACK_TIMEOUT)
        }
    }

    private fun cancelClientTimeout() {
        clientTimeout.cancel()
    }

    private fun maybeRequestClientMtu() {
        val featureEnabled = if (syntheticOnly) protocolOptions.allowFragmentation else longTextEnabled
        if (notificationsOnly || !featureEnabled || mtuRequestAttempted || mutableState.value.settings.reading ||
            pendingDeviceStatusRead != null || deviceStatusRefreshRequested
        ) return
        val current = client ?: return
        val selection = target ?: return
        val sameApprovedPeer = selection.configuration == selection.social && isApproved(current.device, selection.social)
        val configurationReady = mutableState.value.settingsConnected && settingsCharacteristic != null
        val socialSubscribed = social?.isReadyForPeer(selection.social) == true
        if (!BleMtuPolicy.canRequestClientMtu(featureEnabled, configurationReady, sameApprovedPeer, socialSubscribed)) {
            val cause = when {
                !sameApprovedPeer -> BleDiagnosticCause.DIFFERENT_PEERS
                !configurationReady -> BleDiagnosticCause.CONFIGURATION_CHANNEL_UNAVAILABLE
                else -> BleDiagnosticCause.SOCIAL_SUBSCRIPTION_NOT_READY
            }
            if (cause != mtuSkipCause) {
                reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CLIENT_MTU_SKIPPED, cause = cause))
                mtuSkipCause = cause
            }
            return
        }
        mtuRequestAttempted = true
        if (social?.reportedMtu?.let { it > SocialCodec.DEFAULT_MTU } == true) {
            reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CLIENT_MTU_SKIPPED, cause = BleDiagnosticCause.ALREADY_NEGOTIATED))
            return
        }
        diagnose(BleDiagnosticEvent.CLIENT_MTU_REQUESTED, BleMtuPolicy.REQUESTED_CLIENT_MTU)
        val requested = runCatching { current.requestMtu(BleMtuPolicy.REQUESTED_CLIENT_MTU) }.getOrDefault(false)
        if (!requested) {
            reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CLIENT_MTU_FAILED, cause = BleDiagnosticCause.REQUEST_NOT_QUEUED))
            return
        }
        mtuRequestTimeout.replace(MTU_TIMEOUT_MS) {
            if (client !== current) return@replace
            reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CLIENT_MTU_FAILED, cause = BleDiagnosticCause.CALLBACK_TIMEOUT))
            // Android can still complete an ATT operation after the deadline.
            // A new configuration client avoids overlapping its pending operation with an explicit read.
            retryConfiguration(BleDiagnosticCause.CALLBACK_TIMEOUT)
        }
    }

    private fun finishMtuRequest() {
        mtuRequestTimeout.cancel()
        if (awaitingSettingsRead) {
            awaitingSettingsRead = false
            beginReadOnlyRefresh()
        }
    }

    private fun beginReadOnlyRefresh() {
        deviceStatusRefreshRequested = true
        settings.refresh()
    }

    private fun beginDeviceStatusReads(gatt: BluetoothGatt) {
        if (client !== gatt || clientStage != ClientStage.READY) return
        deviceStatusRefreshRequested = false
        settings.updateDeviceStatus(ControllerDeviceStatus())
        for (field in ControllerStatusField.entries) {
            val characteristic = gatt.getService(field.serviceUuid)?.getCharacteristic(field.characteristicUuid) ?: continue
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                deviceStatusReads.addLast(StatusRead(field, characteristic))
            }
        }
        nextDeviceStatusRead(gatt)
    }

    private fun nextDeviceStatusRead(gatt: BluetoothGatt) {
        while (client === gatt && clientStage == ClientStage.READY) {
            val read = deviceStatusReads.removeFirstOrNull()
            if (read == null) {
                maybeRequestClientMtu()
                return
            }
            pendingDeviceStatusRead = read
            if (!runCatching { gatt.readCharacteristic(read.characteristic) }.getOrDefault(false)) {
                pendingDeviceStatusRead = null
                reportDiagnostic(
                    BleDiagnostic(0, BleDiagnosticEvent.DEVICE_STATUS_READ_FAILED, cause = BleDiagnosticCause.REQUEST_NOT_QUEUED),
                )
                continue
            }
            deviceStatusTimeout.replace(STATUS_READ_TIMEOUT_MS) {
                if (client !== gatt || pendingDeviceStatusRead !== read) return@replace
                reportDiagnostic(
                    BleDiagnostic(0, BleDiagnosticEvent.DEVICE_STATUS_READ_FAILED, cause = BleDiagnosticCause.CALLBACK_TIMEOUT),
                )
                retryConfiguration(BleDiagnosticCause.CALLBACK_TIMEOUT)
            }
            return
        }
    }

    private fun onDeviceStatusRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) = clientDispatch(gatt) {
        val read = pendingDeviceStatusRead ?: return@clientDispatch
        if (characteristic.service?.uuid != read.field.serviceUuid ||
            characteristic.uuid != read.field.characteristicUuid
        ) return@clientDispatch
        pendingDeviceStatusRead = null
        deviceStatusTimeout.cancel()
        val decoded = if (status == BluetoothGatt.GATT_SUCCESS) {
            val previous = mutableState.value.settings.deviceStatus
            ControllerStatusCodec.decode(read.field, value, previous)
        } else null
        if (decoded != null) {
            settings.updateDeviceStatus(decoded)
            diagnose(BleDiagnosticEvent.DEVICE_STATUS_READ_COMPLETED, read.field.ordinal)
        } else {
            val cause = if (status == BluetoothGatt.GATT_SUCCESS) BleDiagnosticCause.REJECTED_ATTRIBUTE
                else BleDiagnosticCause.CALLBACK_FAILURE
            reportDiagnostic(
                BleDiagnostic(0, BleDiagnosticEvent.DEVICE_STATUS_READ_FAILED, status, cause),
            )
        }
        nextDeviceStatusRead(gatt)
    }

    private fun clearDeviceStatusReads() {
        deviceStatusTimeout.cancel()
        deviceStatusRefreshRequested = false
        deviceStatusReads.clear()
        pendingDeviceStatusRead = null
    }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean = runCatching {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return@runCatching false
        val ccc = characteristic.getDescriptor(SocialCodec.CCC_UUID) ?: return@runCatching false
        val notification = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(ccc, notification) == BluetoothStatusCodes.SUCCESS
        } else {
            ccc.value = notification
            gatt.writeDescriptor(ccc)
        }
    }.getOrDefault(false)

    private fun writeSettings(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        if (notificationsOnly || client !== gatt || !validateSession()) return false
        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, bytes, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = clientDispatch(gatt) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                diagnose(BleDiagnosticEvent.CONNECTION_DISCONNECTED, status)
                retryConfiguration(BleDiagnosticCause.DISCONNECTED, status)
                return@clientDispatch
            }
            if (newState != BluetoothProfile.STATE_CONNECTED || clientStage != ClientStage.CONNECTING) return@clientDispatch
            cancelClientTimeout()
            settingsAclFallback.cancel()
            clientStage = ClientStage.DISCOVERING
            diagnose(BleDiagnosticEvent.CONNECTION_CONNECTED, null)
            if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CONNECTION_FAILED, cause = BleDiagnosticCause.REQUEST_NOT_QUEUED))
                retryConfiguration(BleDiagnosticCause.REQUEST_NOT_QUEUED)
                return@clientDispatch
            }
            scheduleClientTimeout()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = clientDispatch(gatt) {
            if (clientStage != ClientStage.DISCOVERING) return@clientDispatch
            cancelClientTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                diagnose(BleDiagnosticEvent.CONNECTION_FAILED, status)
                retryConfiguration(BleDiagnosticCause.CALLBACK_FAILURE, status)
                return@clientDispatch
            }
            val characteristic = gatt.getService(ReadOnlyGattSettings.SERVICE_UUID)
                ?.getCharacteristic(ReadOnlyGattSettings.CHARACTERISTIC_UUID)
            val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            if (characteristic == null || characteristic.properties and writable == 0 ||
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            ) {
                settingsChannelMissing(BleDiagnosticCause.SERVICE_NOT_FOUND)
                return@clientDispatch
            }
            settingsCharacteristic = characteristic
            clientStage = ClientStage.SUBSCRIBING
            if (!subscribe(gatt, characteristic)) {
                settingsChannelMissing(BleDiagnosticCause.SUBSCRIPTION_NOT_QUEUED)
                return@clientDispatch
            }
            scheduleClientTimeout()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            clientDispatch(gatt) {
                if (clientStage != ClientStage.SUBSCRIBING || descriptor.uuid != SocialCodec.CCC_UUID ||
                    descriptor.characteristic.service?.uuid != ReadOnlyGattSettings.SERVICE_UUID ||
                    descriptor.characteristic.uuid != ReadOnlyGattSettings.CHARACTERISTIC_UUID
                ) return@clientDispatch
                cancelClientTimeout()
                val characteristic = settingsCharacteristic
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    clientStage = ClientStage.READY
                    settingsReadyAt = SystemClock.elapsedRealtime()
                    settings.onLinkReady { bytes -> writeSettings(gatt, characteristic, bytes) }
                    mutableState.value = mutableState.value.copy(settingsConnected = true, settingsConnecting = false)
                    diagnose(BleDiagnosticEvent.SETTINGS_CHANNEL_READY, null)
                    maybeRequestClientMtu()
                } else {
                    settingsChannelMissing(BleDiagnosticCause.CALLBACK_FAILURE, status)
                }
            }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = clientDispatch(gatt) {
            val reported = BleMtuPolicy.validReportedMtu(mtu)
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CLIENT_MTU_FAILED, status, BleDiagnosticCause.CALLBACK_FAILURE))
                }
                reported == null -> {
                    reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CLIENT_MTU_FAILED, cause = BleDiagnosticCause.INVALID_MTU))
                }
                else -> {
                    mutableState.value = mutableState.value.copy(clientMtu = reported)
                    diagnose(BleDiagnosticEvent.CLIENT_MTU_CHANGED, reported)
                }
            }
            finishMtuRequest()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = onDeviceStatusRead(gatt, characteristic, value.copyOf(), status)

        @Deprecated("Used on Android versions below API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                onDeviceStatusRead(gatt, characteristic, characteristic.value?.copyOf() ?: byteArrayOf(), status)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            clientDispatch(gatt) {
                if (clientStage == ClientStage.READY && characteristic.service?.uuid == ReadOnlyGattSettings.SERVICE_UUID &&
                    characteristic.uuid == ReadOnlyGattSettings.CHARACTERISTIC_UUID
                ) settings.onWrite(status)
            }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = acceptSettingsFrame(gatt, characteristic, value.copyOf())

        @Deprecated("Used on Android versions below API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                characteristic.value?.copyOf()?.let { acceptSettingsFrame(gatt, characteristic, it) }
            }
        }
    }

    private fun settingsChannelMissing(cause: BleDiagnosticCause, status: Int? = null) {
        reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.SETTINGS_CHANNEL_MISSING, status, cause))
        retryConfiguration(cause, status)
    }

    private fun acceptSettingsFrame(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
        clientDispatch(gatt) {
            if (clientStage == ClientStage.READY && characteristic.service?.uuid == ReadOnlyGattSettings.SERVICE_UUID &&
                characteristic.uuid == ReadOnlyGattSettings.CHARACTERISTIC_UUID
            ) settings.onData(value)
        }

    private fun clientDispatch(gatt: BluetoothGatt, callback: () -> Unit) {
        handler.post { if (client === gatt) callback() }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val selection = target ?: return
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (adapterState) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        closeLinks(clearSettings = false)
                        diagnose(BleDiagnosticEvent.BLUETOOTH_DISABLED, null)
                        mutableState.value = mutableState.value.copy(phase = BlePhase.BLUETOOTH_OFF)
                    }
                    BluetoothAdapter.STATE_ON -> {
                        if (social == null && !socialRetryTask.isPending) rebuildSocial()
                        ensureSettingsConnection(selection)
                    }
                }
                return
            }
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            val matchesSocial = device.address == selection.social
            val matchesSettings = device.address == selection.configuration
            if (!matchesSocial && !matchesSettings) return
            if ((intent.action == BluetoothDevice.ACTION_ACL_CONNECTED || intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) &&
                Build.VERSION.SDK_INT >= 33 &&
                intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO) == BluetoothDevice.TRANSPORT_BREDR
            ) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (isApproved(device, device.address)) observeWake(selection, matchesSocial, matchesSettings)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (matchesSocial) observeSocialAclLoss(device)
                    if (matchesSettings && !notificationsOnly) observeSettingsAclLoss()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (runCatching { device.bondState }.getOrNull() != bond) return
                    if (bond == BluetoothDevice.BOND_NONE) {
                        if (matchesSettings) closeConfiguration(clearSettings = true)
                        if (matchesSocial) {
                            closeSocial()
                            mutableState.value = mutableState.value.copy(phase = BlePhase.UNAVAILABLE)
                        }
                        diagnose(BleDiagnosticEvent.DEVICE_UNAVAILABLE, null)
                    } else if (bond == BluetoothDevice.BOND_BONDED) {
                        if (matchesSocial && social == null && !socialRetryTask.isPending) rebuildSocial()
                        if (matchesSettings) ensureSettingsConnection(selection)
                    }
                }
            }
        }
    }

    private fun observeWake(selection: Target, matchesSocial: Boolean, matchesSettings: Boolean) {
        if (!validateSession()) return
        diagnose(BleDiagnosticEvent.PEER_WAKE_OBSERVED, null)
        if (matchesSocial) {
            socialAclFallback.cancel()
            if (social == null) {
                socialRetryTask.cancel()
                mutableState.value = mutableState.value.copy(socialRetryDelayMs = null)
                rebuildSocial()
            }
        }
        if (matchesSettings && !notificationsOnly) {
            settingsAclFallback.cancel()
            if (client == null) {
                settingsRetryTask.cancel()
                mutableState.value = mutableState.value.copy(settingsRetryDelayMs = null)
                ensureSettingsConnection(selection)
            }
        }
    }

    private fun observeSocialAclLoss(device: BluetoothDevice) {
        val server = social ?: return
        if (!server.peerConnected || socialAclFallback.isPending) return
        diagnose(BleDiagnosticEvent.SOCIAL_ACL_DISCONNECTED, null)
        socialAclFallback.replace(ACL_CALLBACK_GRACE_MS) {
            if (social === server && server.peerConnected) server.peerDisconnected(device)
        }
    }

    private fun observeSettingsAclLoss() {
        val current = client ?: return
        if (settingsAclFallback.isPending) return
        diagnose(BleDiagnosticEvent.SETTINGS_ACL_DISCONNECTED, null)
        settingsAclFallback.replace(ACL_CALLBACK_GRACE_MS) {
            if (client === current) retryConfiguration(BleDiagnosticCause.ACL_DISCONNECTED)
        }
    }

    private fun delayedAction() = BleDelayedAction(
        post = { task, delay -> handler.postDelayed(task, delay) },
        remove = handler::removeCallbacks,
    )

    private fun diagnose(event: BleDiagnosticEvent, status: Int?) {
        reportDiagnostic(BleDiagnostic(0, event, status))
    }

    private fun reportDiagnostic(diagnostic: BleDiagnostic) {
        if (diagnostic.event == BleDiagnosticEvent.PEER_CONNECTED || diagnostic.event == BleDiagnosticEvent.PEER_DISCONNECTED) {
            socialAclFallback.cancel()
        }
        val previous = mutableState.value
        val transport = social
        val inheritedProbe = activeProbe.takeIf { diagnostic.probeId == null }
        val entry = diagnostic.copy(
            elapsedMs = SystemClock.elapsedRealtime() - createdAt,
            probeId = diagnostic.probeId ?: inheritedProbe?.id,
            probePart = diagnostic.probePart ?: inheritedProbe?.part,
            probeParts = diagnostic.probeParts ?: inheritedProbe?.parts,
            stateAtEvent = BleDiagnosticState(
                phase = previous.phase,
                notificationsOnly = notificationsOnly,
                socialRegistered = transport?.isRegistered == true,
                socialPeerConnected = transport?.peerConnected == true,
                notificationSourceSubscribed = transport?.notificationSourceSubscribed == true,
                dataSourceSubscribed = transport?.dataSourceSubscribed == true,
                settingsConnected = previous.settingsConnected,
                settingsConnecting = previous.settingsConnecting,
                socialMtu = transport?.mtu ?: SocialCodec.DEFAULT_MTU,
                clientMtu = previous.clientMtu,
            ),
        )
        mutableDiagnostics.value = (mutableDiagnostics.value + entry).takeLast(DIAGNOSTIC_LIMIT)
        mutableState.value = previous.recordTransportEvent(entry)
    }

    private companion object {
        val SETTING_DEVICE_ROLES = setOf(BleDeviceRole.CONFIGURATION, BleDeviceRole.BOTH)
        const val CLIENT_TIMEOUT_MS = 12_000L
        const val STATUS_READ_TIMEOUT_MS = 4_000L
        const val MTU_TIMEOUT_MS = 4_000L
        const val ACL_CALLBACK_GRACE_MS = 400L
        const val DIAGNOSTIC_LIMIT = 1024
    }
}
