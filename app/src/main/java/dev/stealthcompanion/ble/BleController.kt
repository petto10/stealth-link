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
import dev.stealthcompanion.core.protocol.SocialAttribute
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
    private var settingsCharacteristic: BluetoothGattCharacteristic? = null
    private var clientTimeout: Runnable? = null
    private var retryTask: Runnable? = null
    private var settingsRetryTask: Runnable? = null
    private var settingsRetryAttempt = 0
    private var mtuRequestTimeout: Runnable? = null
    private var mtuRequestAttempted = false
    private var mtuSkipCause: BleDiagnosticCause? = null
    private var awaitingSettingsRead = false
    private var longTextEnabled = false
    private var retryAttempt = 0
    private var receiverRegistered = false
    private var syntheticOnlyUnencrypted = false
    private var protocolOptions = ProtocolOptions()
    private val syntheticOnly: Boolean get() = syntheticOnlyUnencrypted || protocolOptions != ProtocolOptions()

    private val settings = ReadOnlyGattSettings(
        handler,
        publish = {
            val finished = mutableState.value.settings.reading && !it.reading
            mutableState.value = mutableState.value.copy(settings = it)
            if (finished) handler.post { maybeRequestClientMtu() }
        },
        diagnose = ::diagnose,
        disconnected = ::retryConfiguration,
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
        if (target == selected) return@post
        registerReceiver()
        target = selected
        retryAttempt = 0
        settingsRetryAttempt = 0
        diagnose(BleDiagnosticEvent.SESSION_STARTED, null)
        closeLinks(clearSettings = true)
        mutableState.value = BleState(
            longTextEnabled = longTextEnabled,
            syntheticOnly = syntheticOnly,
            samePeerForMtu = selected.configuration == selected.social,
        )
        rebuild()
    }

    fun stop() = handler.post {
        if (target == null && !receiverRegistered) return@post
        target = null
        closeLinks(clearSettings = true)
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        diagnose(BleDiagnosticEvent.SESSION_STOPPED, null)
        mutableState.value = BleState(syntheticOnly = syntheticOnly, longTextEnabled = longTextEnabled)
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
            retryAttempt = 0
            closeLinks(clearSettings = false)
        }
        diagnose(BleDiagnosticEvent.PROBE_CHANGED, if (syntheticOnly) 1 else 0)
        mutableState.value = mutableState.value.copy(syntheticOnly = syntheticOnly)
        if (target != null) rebuild()
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

    suspend fun send(
        key: String,
        title: String,
        text: String,
        packageName: String?,
        synthetic: Boolean,
        category: Int,
        applicationName: String?,
        longTextOverride: Boolean?,
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
        val result = transport.send(key, payload, synthetic, category, extended) ?: return@withContext false
        try {
            result.await()
        } finally {
            transport.cancelSend(result)
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
            if (!validateSession() || !mutableState.value.settingsConnected) return@post
            if (mtuRequestTimeout != null) awaitingSettingsRead = true
            else settings.refresh()
        }
    }

    private fun rebuild() {
        val selection = target ?: return
        if (!validateSession()) return
        val configDevice = bondedDevice(selection.configuration)
        val socialDevice = bondedDevice(selection.social)
        if (configDevice == null || socialDevice == null || bluetooth == null) {
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
            accepted = { device -> isApproved(device, selection.social) },
            diagnose = ::diagnose,
            detail = ::reportDiagnostic,
            changed = {
                if (social?.ready == true) retryAttempt = 0
                synchronizeSocialState()
                if (client == null && settingsRetryTask == null && target == selection) connect(configDevice)
                maybeRequestClientMtu()
            },
            restart = ::scheduleRetry,
        )
        social = server
        if (!server.open()) {
            diagnose(BleDiagnosticEvent.SERVER_FAILED, null)
            scheduleRetry()
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (!validateSession()) return
        mutableState.value = mutableState.value.copy(
            phase = if (social?.ready == true) BlePhase.READY else BlePhase.CONNECTING,
        )
        diagnose(BleDiagnosticEvent.CONNECTION_STARTED, null)
        val gatt = runCatching { device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) {
            diagnose(BleDiagnosticEvent.CONNECTION_FAILED, null)
            retryConfiguration()
            return
        }
        client = gatt
        scheduleClientTimeout()
    }

    private fun synchronizeSocialState() {
        val transport = social
        val ready = transport?.ready == true
        val previous = mutableState.value
        mutableState.value = previous.copy(
            phase = when {
                ready -> BlePhase.READY
                previous.phase == BlePhase.RETRYING -> BlePhase.RETRYING
                previous.phase == BlePhase.STARTING -> BlePhase.STARTING
                previous.phase == BlePhase.CONNECTING -> BlePhase.CONNECTING
                else -> BlePhase.WAITING_FOR_SUBSCRIPTION
            },
            socialReady = ready,
            encryptedByPolicy = ready && !syntheticOnlyUnencrypted,
            mtu = transport?.mtu ?: SocialCodec.DEFAULT_MTU,
            socialMtuKnown = transport?.reportedMtu != null,
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
        retryTask?.let(handler::removeCallbacks)
        retryTask = null
        closeConfiguration(clearSettings)
        val server = social
        social = null
        server?.close()
        mutableState.value = mutableState.value.copy(
            socialReady = false,
            encryptedByPolicy = false,
            mtu = SocialCodec.DEFAULT_MTU,
            socialMtuKnown = false,
        )
    }

    private fun closeConfiguration(clearSettings: Boolean) {
        settingsRetryTask?.let(handler::removeCallbacks)
        settingsRetryTask = null
        cancelClientTimeout()
        mtuRequestTimeout?.let(handler::removeCallbacks)
        mtuRequestTimeout = null
        mtuRequestAttempted = false
        mtuSkipCause = null
        awaitingSettingsRead = false
        settingsCharacteristic = null
        val gatt = client
        client = null
        mutableState.value = mutableState.value.copy(settingsConnected = false, clientMtu = null)
        settings.onLinkEnded(clearSettings)
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private fun retryConfiguration() {
        val selected = target ?: return
        if (selected.configuration == selected.social || social == null) {
            scheduleRetry()
            return
        }
        if (settingsRetryTask != null || retryTask != null) return
        closeConfiguration(clearSettings = false)
        if (!validateSession()) return
        val delay = retryDelay(settingsRetryAttempt)
        settingsRetryAttempt = (settingsRetryAttempt + 1).coerceAtMost(6)
        diagnose(BleDiagnosticEvent.SETTINGS_RETRY_SCHEDULED, delay.toInt())
        waitingForSocial()
        settingsRetryTask = Runnable {
            settingsRetryTask = null
            if (target != selected) return@Runnable
            val device = bondedDevice(selected.configuration)
            if (device == null) diagnose(BleDiagnosticEvent.DEVICE_UNAVAILABLE, null)
            else connect(device)
        }.also { handler.postDelayed(it, delay) }
    }

    private fun scheduleRetry() {
        if (target == null || retryTask != null) return
        closeLinks(clearSettings = false)
        if (!validateSession()) return
        val delay = retryDelay(retryAttempt)
        retryAttempt = (retryAttempt + 1).coerceAtMost(6)
        mutableState.value = mutableState.value.copy(phase = BlePhase.RETRYING)
        diagnose(BleDiagnosticEvent.RETRY_SCHEDULED, delay.toInt())
        retryTask = Runnable {
            retryTask = null
            rebuild()
        }.also { handler.postDelayed(it, delay) }
    }

    private fun retryDelay(attempt: Int): Long =
        minOf(1_000L shl attempt.coerceAtMost(5), RETRY_MAX_MS) + Random.nextLong(0, 251)

    private fun scheduleClientTimeout() {
        cancelClientTimeout()
        clientTimeout = Runnable {
            diagnose(BleDiagnosticEvent.CONNECTION_FAILED, null)
            retryConfiguration()
        }.also { handler.postDelayed(it, CLIENT_TIMEOUT_MS) }
    }

    private fun cancelClientTimeout() {
        clientTimeout?.let(handler::removeCallbacks)
        clientTimeout = null
    }

    private fun maybeRequestClientMtu() {
        val featureEnabled = if (syntheticOnly) protocolOptions.allowFragmentation else longTextEnabled
        if (!featureEnabled || mtuRequestAttempted || mutableState.value.settings.reading) return
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
        mtuRequestTimeout = Runnable {
            reportDiagnostic(BleDiagnostic(0, BleDiagnosticEvent.CLIENT_MTU_FAILED, cause = BleDiagnosticCause.CALLBACK_TIMEOUT))
            finishMtuRequest()
        }.also { handler.postDelayed(it, MTU_TIMEOUT_MS) }
    }

    private fun finishMtuRequest() {
        mtuRequestTimeout?.let(handler::removeCallbacks)
        mtuRequestTimeout = null
        if (awaitingSettingsRead) {
            awaitingSettingsRead = false
            settings.refresh()
        }
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
        if (client !== gatt || !validateSession()) return false
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
            cancelClientTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                diagnose(BleDiagnosticEvent.CONNECTION_DISCONNECTED, status)
                retryConfiguration()
                return@clientDispatch
            }
            if (newState != BluetoothProfile.STATE_CONNECTED) return@clientDispatch
            diagnose(BleDiagnosticEvent.CONNECTION_CONNECTED, null)
            if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                retryConfiguration()
                return@clientDispatch
            }
            scheduleClientTimeout()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = clientDispatch(gatt) {
            cancelClientTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                diagnose(BleDiagnosticEvent.CONNECTION_FAILED, status)
                retryConfiguration()
                return@clientDispatch
            }
            val characteristic = gatt.getService(ReadOnlyGattSettings.SERVICE_UUID)
                ?.getCharacteristic(ReadOnlyGattSettings.CHARACTERISTIC_UUID)
            val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            if (characteristic == null || characteristic.properties and writable == 0 ||
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            ) {
                // The App Link session may still be usable when this peer has no settings characteristic.
                diagnose(BleDiagnosticEvent.SETTINGS_CHANNEL_MISSING, null)
                waitingForSocial()
                maybeRequestClientMtu()
                return@clientDispatch
            }
            settingsCharacteristic = characteristic
            if (!subscribe(gatt, characteristic)) {
                settingsCharacteristic = null
                diagnose(BleDiagnosticEvent.SETTINGS_CHANNEL_MISSING, null)
                waitingForSocial()
                maybeRequestClientMtu()
                return@clientDispatch
            }
            scheduleClientTimeout()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            clientDispatch(gatt) {
                if (descriptor.uuid != SocialCodec.CCC_UUID ||
                    descriptor.characteristic.uuid != ReadOnlyGattSettings.CHARACTERISTIC_UUID
                ) return@clientDispatch
                cancelClientTimeout()
                val characteristic = settingsCharacteristic
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    settingsRetryAttempt = 0
                    if (target?.configuration == target?.social) retryAttempt = 0
                    settings.onLinkReady { bytes -> writeSettings(gatt, characteristic, bytes) }
                    mutableState.value = mutableState.value.copy(settingsConnected = true)
                    diagnose(BleDiagnosticEvent.SETTINGS_CHANNEL_READY, null)
                } else {
                    settingsCharacteristic = null
                    diagnose(BleDiagnosticEvent.SETTINGS_CHANNEL_MISSING, status)
                }
                waitingForSocial()
                maybeRequestClientMtu()
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

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            clientDispatch(gatt) {
                if (characteristic.uuid == ReadOnlyGattSettings.CHARACTERISTIC_UUID) settings.onWrite(status)
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

    private fun waitingForSocial() {
        val ready = social?.ready == true
        mutableState.value = mutableState.value.copy(
            phase = if (ready) BlePhase.READY else BlePhase.WAITING_FOR_SUBSCRIPTION,
        )
    }

    private fun acceptSettingsFrame(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
        clientDispatch(gatt) {
            if (characteristic.uuid == ReadOnlyGattSettings.CHARACTERISTIC_UUID) settings.onData(value)
        }

    private fun clientDispatch(gatt: BluetoothGatt, callback: () -> Unit) {
        handler.post { if (client === gatt) callback() }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
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
                    BluetoothAdapter.STATE_ON -> if (social == null && retryTask == null) rebuild()
                }
                return
            }
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (device.address != selection.social && device.address != selection.configuration) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    social?.peerDisconnected(device)
                    if (device.address == selection.configuration) retryConfiguration()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (bond == BluetoothDevice.BOND_NONE) {
                        closeLinks(clearSettings = true)
                        diagnose(BleDiagnosticEvent.DEVICE_UNAVAILABLE, null)
                        mutableState.value = mutableState.value.copy(phase = BlePhase.UNAVAILABLE)
                    } else if (bond == BluetoothDevice.BOND_BONDED && social == null && retryTask == null) {
                        rebuild()
                    }
                }
            }
        }
    }

    private fun diagnose(event: BleDiagnosticEvent, status: Int?) {
        reportDiagnostic(BleDiagnostic(0, event, status))
    }

    private fun reportDiagnostic(diagnostic: BleDiagnostic) {
        val entry = diagnostic.copy(elapsedMs = SystemClock.elapsedRealtime() - createdAt)
        mutableDiagnostics.value = (mutableDiagnostics.value + entry).takeLast(DIAGNOSTIC_LIMIT)
        val previous = mutableState.value
        val title = entry.attributes.firstOrNull { it.id == SocialAttribute.TITLE.id }
        val body = entry.attributes.firstOrNull { it.id == SocialAttribute.MESSAGE.id }
        mutableState.value = when (entry.event) {
            BleDiagnosticEvent.CONTROL_REQUEST_RECEIVED -> previous.copy(
                attributeRequests = previous.attributeRequests + entry.attributes.size,
                lastRequestedTitleBytes = title?.requestedMaxBytes ?: previous.lastRequestedTitleBytes,
                lastRequestedBodyBytes = body?.requestedMaxBytes ?: previous.lastRequestedBodyBytes,
            )
            BleDiagnosticEvent.CONTROL_RESPONSE_COMPLETED -> previous.copy(
                lastSentTitleBytes = title?.payloadBytes ?: previous.lastSentTitleBytes,
                lastSentBodyBytes = body?.payloadBytes ?: previous.lastSentBodyBytes,
            )
            BleDiagnosticEvent.SOCIAL_FRAGMENT_SENT -> previous.copy(fragmentsSent = previous.fragmentsSent + 1)
            else -> previous
        }
    }

    private companion object {
        const val CLIENT_TIMEOUT_MS = 12_000L
        const val MTU_TIMEOUT_MS = 4_000L
        const val RETRY_MAX_MS = 30_000L
        const val DIAGNOSTIC_LIMIT = 256
    }
}
