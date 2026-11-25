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
    private var retryAttempt = 0
    private var receiverRegistered = false
    private var syntheticOnlyUnencrypted = false
    private var protocolOptions = ProtocolOptions()
    private val syntheticOnly: Boolean get() = syntheticOnlyUnencrypted || protocolOptions != ProtocolOptions()

    private val settings = ReadOnlyGattSettings(
        handler,
        publish = { mutableState.value = mutableState.value.copy(settings = it) },
        diagnose = ::diagnose,
        disconnected = ::scheduleRetry,
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
        diagnose(BleDiagnosticEvent.SESSION_STARTED, null)
        closeLinks(clearSettings = true)
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
        mutableState.value = BleState(syntheticOnly = syntheticOnly)
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

    suspend fun send(
        key: String,
        title: String,
        text: String,
        packageName: String?,
        synthetic: Boolean,
        category: Int,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (category !in 0..11 || (!synthetic && syntheticOnly)) return@withContext false
        if (!validateSession()) return@withContext false
        val transport = social ?: return@withContext false
        val payload = SocialPayload(
            title,
            text,
            if (synthetic) packageName else null,
            if (synthetic && packageName != null) "Social test" else null,
        )
        val result = transport.send(key, payload, synthetic, category) ?: return@withContext false
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
        handler.post { if (validateSession() && mutableState.value.settingsConnected) settings.refresh() }
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
            changed = {
                if (social?.ready == true) retryAttempt = 0
                synchronizeSocialState()
                if (client == null && target == selection) connect(configDevice)
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
            scheduleRetry()
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
        cancelClientTimeout()
        settingsCharacteristic = null
        settings.onLinkEnded(clearSettings)
        val server = social
        social = null
        server?.close()
        val gatt = client
        client = null
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        mutableState.value = mutableState.value.copy(
            socialReady = false,
            encryptedByPolicy = false,
            settingsConnected = false,
            mtu = SocialCodec.DEFAULT_MTU,
        )
    }

    private fun scheduleRetry() {
        if (target == null || retryTask != null) return
        closeLinks(clearSettings = false)
        if (!validateSession()) return
        val delay = minOf(1_000L shl retryAttempt.coerceAtMost(5), RETRY_MAX_MS) + Random.nextLong(0, 251)
        retryAttempt = (retryAttempt + 1).coerceAtMost(6)
        mutableState.value = mutableState.value.copy(phase = BlePhase.RETRYING)
        diagnose(BleDiagnosticEvent.RETRY_SCHEDULED, delay.toInt())
        retryTask = Runnable {
            retryTask = null
            rebuild()
        }.also { handler.postDelayed(it, delay) }
    }

    private fun scheduleClientTimeout() {
        cancelClientTimeout()
        clientTimeout = Runnable {
            diagnose(BleDiagnosticEvent.CONNECTION_FAILED, null)
            scheduleRetry()
        }.also { handler.postDelayed(it, CLIENT_TIMEOUT_MS) }
    }

    private fun cancelClientTimeout() {
        clientTimeout?.let(handler::removeCallbacks)
        clientTimeout = null
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
                scheduleRetry()
                return@clientDispatch
            }
            if (newState != BluetoothProfile.STATE_CONNECTED) return@clientDispatch
            diagnose(BleDiagnosticEvent.CONNECTION_CONNECTED, null)
            if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                scheduleRetry()
                return@clientDispatch
            }
            scheduleClientTimeout()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = clientDispatch(gatt) {
            cancelClientTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                diagnose(BleDiagnosticEvent.CONNECTION_FAILED, status)
                scheduleRetry()
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
                return@clientDispatch
            }
            settingsCharacteristic = characteristic
            if (!subscribe(gatt, characteristic)) {
                settingsCharacteristic = null
                diagnose(BleDiagnosticEvent.SETTINGS_CHANNEL_MISSING, null)
                waitingForSocial()
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
                    retryAttempt = 0
                    settings.onLinkReady { bytes -> writeSettings(gatt, characteristic, bytes) }
                    mutableState.value = mutableState.value.copy(settingsConnected = true)
                    diagnose(BleDiagnosticEvent.SETTINGS_CHANNEL_READY, null)
                } else {
                    settingsCharacteristic = null
                    diagnose(BleDiagnosticEvent.SETTINGS_CHANNEL_MISSING, status)
                }
                waitingForSocial()
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
                    if (device.address == selection.configuration) scheduleRetry()
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
        val entry = BleDiagnostic(SystemClock.elapsedRealtime() - createdAt, event, status)
        mutableDiagnostics.value = (mutableDiagnostics.value + entry).takeLast(DIAGNOSTIC_LIMIT)
    }

    private companion object {
        const val CLIENT_TIMEOUT_MS = 12_000L
        const val RETRY_MAX_MS = 30_000L
        const val DIAGNOSTIC_LIMIT = 128
    }
}
