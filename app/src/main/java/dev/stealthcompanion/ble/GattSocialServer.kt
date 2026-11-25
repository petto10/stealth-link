package dev.stealthcompanion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import dev.stealthcompanion.core.protocol.ProtocolOptions
import dev.stealthcompanion.core.protocol.SocialAttribute
import dev.stealthcompanion.core.protocol.SocialCodec
import dev.stealthcompanion.core.protocol.SocialPayload
import dev.stealthcompanion.core.protocol.SocialSession
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class GattSocialServer(
    private val context: Context,
    private val bluetooth: BluetoothManager,
    private val handler: Handler,
    private val options: ProtocolOptions,
    private val requireEncryption: Boolean,
    private val accepted: (BluetoothDevice) -> Boolean,
    private val diagnose: (BleDiagnosticEvent, Int?) -> Unit,
    private val changed: () -> Unit,
    private val restart: () -> Unit,
) {
    private data class Record(val key: String, val synthetic: Boolean, val publishedAt: Long)

    private data class Send(
        val uid: Int,
        val record: Record,
        val outcome: CompletableDeferred<Boolean>,
        val attributes: MutableSet<SocialAttribute> = mutableSetOf(),
    )

    private data class Packet(
        val characteristic: BluetoothGattCharacteristic,
        val bytes: ByteArray,
        val synthetic: Boolean,
        val onSent: () -> Unit = {},
    )

    private var server: BluetoothGattServer? = null
    private var registered = false
    private var closed = false
    private var peer: BluetoothDevice? = null
    private var sourceSubscribed = false
    private var dataSubscribed = false
    private val session = SocialSession(options, MAX_RECORDS, RECORD_TTL_MS)
    private val records = LinkedHashMap<Int, Record>()
    private val packets = ArrayDeque<Packet>()
    private val recordExpiry = Runnable { expireRecords() }
    private var inFlight: Packet? = null
    private var pending: Send? = null
    private var pendingTimeout: Runnable? = null
    private var removeOutcome: CompletableDeferred<Boolean>? = null
    private var packetTimeout: Runnable? = null
    private var serviceTimeout: Runnable? = null

    private val source = BluetoothGattCharacteristic(SocialCodec.NOTIFICATION_SOURCE_UUID, notifyProperties, 0)
    private val data = BluetoothGattCharacteristic(SocialCodec.DATA_SOURCE_UUID, notifyProperties, 0)
    private val controlPoint = BluetoothGattCharacteristic(
        SocialCodec.CONTROL_POINT_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE,
        if (requireEncryption) BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        else BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    var mtu: Int = SocialCodec.DEFAULT_MTU
        private set

    val ready: Boolean get() = registered && sourceSubscribed && dataSubscribed &&
        peer?.let(accepted) == true

    private val notifyProperties: Int
        get() = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            if (requireEncryption) 0 else BluetoothGattCharacteristic.PROPERTY_WRITE

    fun open(): Boolean {
        val notificationPermissions = if (requireEncryption) {
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
        } else {
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        }
        source.addDescriptor(BluetoothGattDescriptor(SocialCodec.CCC_UUID, notificationPermissions))
        data.addDescriptor(BluetoothGattDescriptor(SocialCodec.CCC_UUID, notificationPermissions))
        if (!requireEncryption) {
            controlPoint.addDescriptor(BluetoothGattDescriptor(EXTENDED_PROPERTIES_UUID, BluetoothGattDescriptor.PERMISSION_READ))
        }
        val service = BluetoothGattService(SocialCodec.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(controlPoint)
        service.addCharacteristic(source)
        service.addCharacteristic(data)
        val opened = runCatching { bluetooth.openGattServer(context, callback) }.getOrNull() ?: return false
        server = opened
        if (!runCatching { opened.addService(service) }.getOrDefault(false)) {
            close()
            return false
        }
        serviceTimeout = Runnable {
            if (!registered && !closed) {
                diagnose(BleDiagnosticEvent.SERVER_FAILED, null)
                restart()
            }
        }.also { handler.postDelayed(it, SERVICE_TIMEOUT_MS) }
        return true
    }

    fun close() {
        closed = true
        serviceTimeout?.let(handler::removeCallbacks)
        serviceTimeout = null
        val previous = server
        server = null
        resetPeer()
        if (previous != null) runCatching { previous.close() }
    }

    fun peerDisconnected(device: BluetoothDevice) {
        if (peer?.address != device.address) return
        if (inFlight != null) {
            // An Android callback carries no packet identifier, so restart this GATT server.
            restart()
            return
        }
        diagnose(BleDiagnosticEvent.PEER_DISCONNECTED, null)
        resetPeer()
        changed()
    }

    fun send(
        key: String,
        payload: SocialPayload,
        synthetic: Boolean,
        category: Int,
    ): CompletableDeferred<Boolean>? {
        if (!ready || pending != null || packets.size + 1 >= MAX_PACKETS) return null
        val now = SystemClock.elapsedRealtime()
        records.entries.removeAll { now - it.value.publishedAt >= RECORD_TTL_MS }
        val sessionKey = if (synthetic) "synthetic:$key" else "notification:$key"
        val event = session.publish(sessionKey, payload, now, category) ?: return null
        val uid = SocialCodec.decodeEvent(event)?.uid ?: return null
        val record = Record(sessionKey, synthetic, now)
        records.remove(uid)
        if (records.size >= MAX_RECORDS) records.remove(records.keys.first())
        records[uid] = record
        expireRecords()
        val outcome = CompletableDeferred<Boolean>()
        val send = Send(uid, record, outcome)
        pending = send
        pendingTimeout = Runnable {
            if (pending === send) {
                diagnose(BleDiagnosticEvent.NOTIFICATION_TIMED_OUT, null)
                forgetRecord(send)
                finishPending(false)
            }
        }.also { handler.postDelayed(it, RECORD_TIMEOUT_MS) }
        packets.addLast(Packet(source, event, synthetic))
        sendNext()
        return outcome
    }

    fun cancelSend(outcome: CompletableDeferred<Boolean>) {
        val send = pending?.takeIf { it.outcome === outcome } ?: return
        forgetRecord(send)
        finishPending(false)
    }

    fun remove(key: String): CompletableDeferred<Boolean>? {
        if (!ready || !options.allowRemovals || packets.size + 1 >= MAX_PACKETS || removeOutcome != null) return null
        val sessionKey = "synthetic:$key"
        if (records.values.none { it.key == sessionKey }) return null
        val event = session.remove(sessionKey, SystemClock.elapsedRealtime()) ?: return null
        records.entries.removeAll { it.value.key == sessionKey }
        val outcome = CompletableDeferred<Boolean>()
        removeOutcome = outcome
        packets.addLast(Packet(source, event, true) {
            removeOutcome = null
            outcome.complete(true)
        })
        sendNext()
        return outcome
    }

    fun discardPersonal() {
        val now = SystemClock.elapsedRealtime()
        records.values.filter { !it.synthetic }.map { it.key }.distinct().forEach { session.forget(it, now) }
        records.entries.removeAll { !it.value.synthetic }
        expireRecords()
        packets.removeAll { !it.synthetic }
        if (pending?.record?.synthetic == false) finishPending(false)
    }

    private fun expireRecords() {
        handler.removeCallbacks(recordExpiry)
        val now = SystemClock.elapsedRealtime()
        session.expire(now)
        records.entries.removeAll { now - it.value.publishedAt >= RECORD_TTL_MS }
        val oldest = records.values.firstOrNull() ?: return
        handler.postDelayed(recordExpiry, (oldest.publishedAt + RECORD_TTL_MS - now).coerceAtLeast(1))
    }

    private fun forgetRecord(send: Send) {
        records.remove(send.uid)
        session.forget(send.record.key, SystemClock.elapsedRealtime())
    }

    private fun finishPending(success: Boolean) {
        pendingTimeout?.let(handler::removeCallbacks)
        pendingTimeout = null
        val outcome = pending?.outcome
        pending = null
        outcome?.complete(success)
    }

    private fun resetPeer() {
        peer = null
        mtu = SocialCodec.DEFAULT_MTU
        sourceSubscribed = false
        dataSubscribed = false
        session.reset()
        handler.removeCallbacks(recordExpiry)
        records.clear()
        packets.clear()
        inFlight = null
        removeOutcome?.complete(false)
        removeOutcome = null
        packetTimeout?.let(handler::removeCallbacks)
        packetTimeout = null
        finishPending(false)
    }

    private fun sendNext() {
        if (inFlight != null || packets.isEmpty()) return
        val remote = peer
        val gatt = server
        if (remote == null || gatt == null || !ready) {
            failPackets()
            return
        }
        val packet = packets.removeFirst()
        inFlight = packet
        val queued = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.notifyCharacteristicChanged(remote, packet.characteristic, false, packet.bytes) == BluetoothStatusCodes.SUCCESS
            } else {
                packet.characteristic.value = packet.bytes
                gatt.notifyCharacteristicChanged(remote, packet.characteristic, false)
            }
        }.getOrDefault(false)
        if (!queued) {
            failPackets()
            return
        }
        packetTimeout = Runnable {
            diagnose(BleDiagnosticEvent.NOTIFICATION_TIMED_OUT, null)
            failPackets()
        }.also { handler.postDelayed(it, PACKET_TIMEOUT_MS) }
    }

    private fun failPackets(status: Int? = null) {
        diagnose(BleDiagnosticEvent.NOTIFICATION_FAILED, status)
        finishPending(false)
        removeOutcome?.complete(false)
        removeOutcome = null
        restart()
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = dispatch {
            if (service.uuid != SocialCodec.SERVICE_UUID) return@dispatch
            serviceTimeout?.let(handler::removeCallbacks)
            serviceTimeout = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                registered = true
                diagnose(BleDiagnosticEvent.SERVER_OPENED, null)
                changed()
            } else {
                diagnose(BleDiagnosticEvent.SERVER_FAILED, status)
                restart()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = dispatch {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peerDisconnected(device)
            } else if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS && accepted(device)) {
                setPeer(device)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = dispatch {
            if (!accepted(device)) return@dispatch
            setPeer(device)
            this@GattSocialServer.mtu = mtu.coerceIn(23, 517)
            diagnose(BleDiagnosticEvent.MTU_CHANGED, this@GattSocialServer.mtu)
            changed()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) = dispatch {
            reply(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) = dispatch {
            if (!accepted(device)) {
                reply(device, requestId, BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION)
                return@dispatch
            }
            val characteristic = descriptor.characteristic
            val subscribed = when (characteristic.uuid) {
                SocialCodec.NOTIFICATION_SOURCE_UUID -> sourceSubscribed
                SocialCodec.DATA_SOURCE_UUID -> dataSubscribed
                else -> false
            } && peer?.address == device.address
            val value = when (descriptor.uuid) {
                SocialCodec.CCC_UUID -> if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                EXTENDED_PROPERTIES_UUID -> byteArrayOf(0, 0)
                else -> null
            }
            when {
                value == null -> reply(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
                offset !in 0..value.size -> reply(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
                else -> reply(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val received = value.copyOf()
            dispatch {
                val characteristic = descriptor.characteristic.uuid
                val enable = received.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val disable = received.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                val status = when {
                    !accepted(device) -> BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    preparedWrite || descriptor.uuid != SocialCodec.CCC_UUID ||
                        (characteristic != SocialCodec.NOTIFICATION_SOURCE_UUID && characteristic != SocialCodec.DATA_SOURCE_UUID) ->
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    !enable && !disable -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                if (responseNeeded) reply(device, requestId, status)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    diagnose(BleDiagnosticEvent.PEER_REJECTED, status)
                    return@dispatch
                }
                setPeer(device)
                val wasReady = ready
                if (characteristic == SocialCodec.NOTIFICATION_SOURCE_UUID) sourceSubscribed = enable
                else dataSubscribed = enable
                diagnose(BleDiagnosticEvent.SUBSCRIPTION_CHANGED, if (enable) 1 else 0)
                if (wasReady && !ready) {
                    if (inFlight != null) {
                        restart()
                        return@dispatch
                    }
                    session.reset()
                    records.clear()
                    packets.clear()
                    finishPending(false)
                }
                if (ready && !wasReady) diagnose(BleDiagnosticEvent.SOCIAL_READY, null)
                changed()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val request = value.copyOf()
            dispatch {
                val status = when {
                    !accepted(device) || peer?.address != device.address -> BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                    !ready -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    preparedWrite || characteristic.uuid != SocialCodec.CONTROL_POINT_UUID -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (responseNeeded) reply(device, requestId, status)
                    diagnose(BleDiagnosticEvent.CONTROL_REQUEST_REJECTED, status)
                    return@dispatch
                }
                handleAttributeRequest(device, requestId, responseNeeded, request)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = dispatch {
            reply(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = dispatch {
            if (peer?.address != device.address) return@dispatch
            val packet = inFlight ?: return@dispatch
            packetTimeout?.let(handler::removeCallbacks)
            packetTimeout = null
            inFlight = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failPackets(status)
                return@dispatch
            }
            diagnose(BleDiagnosticEvent.NOTIFICATION_SENT, null)
            packet.onSent()
            sendNext()
        }
    }

    private fun handleAttributeRequest(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        request: ByteArray,
    ) {
        val now = SystemClock.elapsedRealtime()
        val parsed = SocialCodec.decodeRequest(request)
        val record = parsed?.let { records[it.uid] }
        val frames = session.respond(request, mtu, now)
        if (frames.isEmpty() || (parsed != null && record == null) || packets.size + frames.size > MAX_PACKETS) {
            if (responseNeeded) reply(device, requestId, BluetoothGatt.GATT_FAILURE)
            diagnose(BleDiagnosticEvent.CONTROL_REQUEST_REJECTED, null)
            return
        }
        if (responseNeeded && !reply(device, requestId, BluetoothGatt.GATT_SUCCESS)) return
        val send = pending?.takeIf { it.uid == parsed?.uid }
        val attributes = parsed?.attributes.orEmpty().map { it.attribute }
        frames.forEachIndexed { index, frame ->
            packets.addLast(Packet(data, frame, record?.synthetic ?: true) {
                if (index == frames.lastIndex && send != null && pending === send) {
                    send.attributes.addAll(attributes)
                    if (SocialAttribute.TITLE in send.attributes && SocialAttribute.MESSAGE in send.attributes) finishPending(true)
                }
            })
        }
        sendNext()
    }

    private fun setPeer(device: BluetoothDevice) {
        if (peer?.address != device.address) {
            resetPeer()
            peer = device
        }
    }

    private fun dispatch(action: () -> Unit) {
        handler.post { if (!closed) action() }
    }

    private fun reply(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int = 0,
        value: ByteArray? = null,
    ): Boolean = runCatching { server?.sendResponse(device, requestId, status, offset, value) == true }.getOrDefault(false)

    private companion object {
        val EXTENDED_PROPERTIES_UUID: UUID = UUID.fromString("00002900-0000-1000-8000-00805f9b34fb")
        const val MAX_RECORDS = 32
        const val MAX_PACKETS = 64
        const val RECORD_TTL_MS = 30_000L
        const val RECORD_TIMEOUT_MS = 10_000L
        const val PACKET_TIMEOUT_MS = 3_000L
        const val SERVICE_TIMEOUT_MS = 5_000L
    }
}
