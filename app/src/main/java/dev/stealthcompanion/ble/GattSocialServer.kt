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
    private val detail: (BleDiagnostic) -> Unit,
    private val changed: () -> Unit,
    private val restart: () -> Unit,
) {
    private data class Record(
        val key: String,
        val synthetic: Boolean,
        val longResponse: Boolean,
        val publishedAt: Long,
    )

    private data class Send(
        val uid: Int,
        val record: Record,
        val outcome: CompletableDeferred<Boolean>,
        val attributes: MutableSet<SocialAttribute> = mutableSetOf(),
        var requestReceived: Boolean = false,
        var deadlineMs: Long = record.publishedAt + RECORD_TIMEOUT_MS,
    )

    private data class Packet(
        val characteristic: BluetoothGattCharacteristic,
        val bytes: ByteArray,
        val record: Record? = null,
        val responseNumber: Int? = null,
        val request: ResponseRequest? = null,
        val onSent: () -> Unit = {},
    )

    private data class ResponseRequest(val command: Int?, val uid: Int?, val attributes: List<BleAttributeDiagnostic>)

    private var server: BluetoothGattServer? = null
    private var registered = false
    private var closed = false
    private var peer: BluetoothDevice? = null
    private var sourceSubscribed = false
    private var dataSubscribed = false
    private val session = SocialSession(options, MAX_RECORDS, RECORD_TTL_MS)
    private val records = LinkedHashMap<Int, Record>()
    private val packets = BleFrameQueue<Packet>(MAX_PACKETS, MAX_QUEUED_BYTES) { it.bytes.size }
    private val recordExpiry = Runnable { expireRecords() }
    private var pending: Send? = null
    private var pendingTimeout: Runnable? = null
    private var removeOutcome: CompletableDeferred<Boolean>? = null
    private var packetTimeout: Runnable? = null
    private var packetDeadlineMs = 0L
    private var responseNumber = 0
    private var serviceTimeout: Runnable? = null

    private val source = BluetoothGattCharacteristic(SocialCodec.NOTIFICATION_SOURCE_UUID, notifyProperties, 0)
    private val data = BluetoothGattCharacteristic(SocialCodec.DATA_SOURCE_UUID, notifyProperties, 0)
    private val controlPoint = BluetoothGattCharacteristic(
        SocialCodec.CONTROL_POINT_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE,
        if (requireEncryption) BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        else BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    var reportedMtu: Int? = null
        private set

    val mtu: Int get() = BleMtuPolicy.socialMtu(reportedMtu)

    val ready: Boolean get() = !closed && registered && sourceSubscribed && dataSubscribed &&
        peer?.let(accepted) == true

    fun isReadyForPeer(address: String): Boolean = ready && peer?.address == address

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
        if (packets.inFlight != null) {
            finishPending(false, BleDiagnosticCause.DISCONNECTED)
            // An Android callback carries no packet identifier, so restart this GATT server.
            restart()
            return
        }
        diagnose(BleDiagnosticEvent.PEER_DISCONNECTED, null)
        resetPeer(BleDiagnosticCause.DISCONNECTED)
        changed()
    }

    fun send(
        key: String,
        payload: SocialPayload,
        synthetic: Boolean,
        category: Int,
        longResponse: Boolean,
    ): CompletableDeferred<Boolean>? {
        if (!ready || pending != null) return null
        val now = SystemClock.elapsedRealtime()
        if (expireRecords()) return null
        val sessionKey = if (synthetic) "synthetic:$key" else "notification:$key"
        val event = session.publish(sessionKey, payload, now, category, allowLongResponse = longResponse) ?: return null
        val uid = SocialCodec.decodeEvent(event)?.uid ?: return null
        val record = Record(sessionKey, synthetic, longResponse, now)
        if (!packets.offerBatch(listOf(Packet(source, event, record)))) {
            session.forget(sessionKey, now)
            detail(BleDiagnostic(0, BleDiagnosticEvent.SOCIAL_DELIVERY_ABORTED, cause = BleDiagnosticCause.QUEUE_LIMIT))
            return null
        }
        records.remove(uid)
        if (records.size >= MAX_RECORDS) records.remove(records.keys.first())
        records[uid] = record
        if (expireRecords()) return null
        val outcome = CompletableDeferred<Boolean>()
        val send = Send(uid, record, outcome)
        pending = send
        armDeliveryTimeout(send)
        sendNext()
        return outcome
    }

    fun cancelSend(outcome: CompletableDeferred<Boolean>) {
        val send = pending?.takeIf { it.outcome === outcome } ?: return
        val cause = BleDiagnosticCause.CANCELLED
        if (!forgetRecord(send, cause)) finishPending(false, cause)
    }

    fun remove(key: String): CompletableDeferred<Boolean>? {
        if (!ready || !options.allowRemovals || removeOutcome != null) return null
        val sessionKey = "synthetic:$key"
        if (records.values.none { it.key == sessionKey }) return null
        val event = session.remove(sessionKey, SystemClock.elapsedRealtime()) ?: return null
        records.entries.removeAll { it.value.key == sessionKey }
        val outcome = CompletableDeferred<Boolean>()
        val packet = Packet(source, event) {
            removeOutcome = null
            outcome.complete(true)
        }
        if (!packets.offerBatch(listOf(packet))) return null
        removeOutcome = outcome
        sendNext()
        return outcome
    }

    fun discardPersonal() = discardRecords(BleDiagnosticCause.PRIVACY) { !it.synthetic }
    fun discardLongResponses() = discardRecords(BleDiagnosticCause.MODE_CHANGED) { it.longResponse }

    private fun discardRecords(cause: BleDiagnosticCause, matches: (Record) -> Boolean) {
        val now = SystemClock.elapsedRealtime()
        records.values.filter(matches).map { it.key }.distinct().forEach { session.forget(it, now) }
        records.entries.removeAll { matches(it.value) }
        if (discardFrames(cause) { it.record?.let(matches) == true }) return
        if (pending?.record?.let(matches) == true) finishPending(false, cause)
        expireRecords()
    }

    /** Returns true when expiry had to stop a partially transmitted response. */
    private fun expireRecords(): Boolean {
        handler.removeCallbacks(recordExpiry)
        val now = SystemClock.elapsedRealtime()
        session.expire(now)
        records.entries.removeAll { now - it.value.publishedAt >= RECORD_TTL_MS }
        if (discardFrames(BleDiagnosticCause.RECORD_EXPIRED) {
                it.record?.let { record -> now - record.publishedAt >= RECORD_TTL_MS } == true
            }) return true
        val oldest = records.values.firstOrNull() ?: return false
        handler.postDelayed(recordExpiry, (oldest.publishedAt + RECORD_TTL_MS - now).coerceAtLeast(1))
        return false
    }

    private fun forgetRecord(send: Send, cause: BleDiagnosticCause): Boolean {
        records.entries.removeAll { it.value.key == send.record.key }
        session.forget(send.record.key, SystemClock.elapsedRealtime())
        if (discardFrames(cause) { it.record?.key == send.record.key }) return true
        return expireRecords()
    }

    private fun discardFrames(cause: BleDiagnosticCause, matches: (Packet) -> Boolean): Boolean {
        if (!packets.discardQueued(matches)) return false
        // A subsequent response would be interpreted as the tail of the interrupted response.
        // Cancel the approved server connection as well as replacing the local GATT instance.
        closed = true
        val remote = peer
        if (remote != null) runCatching { server?.cancelConnection(remote) }
        detail(BleDiagnostic(0, BleDiagnosticEvent.PARTIAL_RESPONSE_RESET, cause = cause))
        finishPending(false, cause)
        restart()
        return true
    }

    private fun armDeliveryTimeout(send: Send) {
        pendingTimeout?.let(handler::removeCallbacks)
        pendingTimeout = Runnable { expirePendingDelivery() }.also {
            handler.postDelayed(it, (send.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
    }

    private fun expirePendingDelivery() {
        val send = pending ?: return
        if (SystemClock.elapsedRealtime() < send.deadlineMs) return
        diagnose(BleDiagnosticEvent.NOTIFICATION_TIMED_OUT, null)
        val cause = if (send.requestReceived) BleDiagnosticCause.ATTRIBUTE_RESPONSE_TIMEOUT
            else BleDiagnosticCause.NO_ATTRIBUTE_REQUEST
        if (!forgetRecord(send, cause)) finishPending(false, cause)
    }

    private fun finishPending(success: Boolean, cause: BleDiagnosticCause? = null) {
        pendingTimeout?.let(handler::removeCallbacks)
        pendingTimeout = null
        val send = pending ?: return
        pending = null
        val event = if (success) BleDiagnosticEvent.SOCIAL_DELIVERY_COMPLETED else BleDiagnosticEvent.SOCIAL_DELIVERY_ABORTED
        detail(BleDiagnostic(0, event, cause = cause, attributes = send.attributes.map { BleAttributeDiagnostic(it.id) }))
        send.outcome.complete(success)
    }

    private fun resetPeer(cause: BleDiagnosticCause = BleDiagnosticCause.TRANSPORT_CLOSED) {
        peer = null
        reportedMtu = null
        sourceSubscribed = false
        dataSubscribed = false
        session.reset()
        handler.removeCallbacks(recordExpiry)
        records.clear()
        packets.clear()
        val removal = removeOutcome
        removeOutcome = null
        removal?.complete(false)
        cancelPacketTimeout()
        finishPending(false, cause)
    }

    private fun sendNext() {
        expirePendingDelivery()
        if (closed) return
        val oldest = records.values.firstOrNull()
        if (oldest != null && SystemClock.elapsedRealtime() - oldest.publishedAt >= RECORD_TTL_MS && expireRecords()) return
        val packet = packets.launchNext() ?: return
        val remote = peer
        val gatt = server
        if (remote == null || gatt == null || !ready) {
            failPackets(BleDiagnosticCause.NOT_READY)
            return
        }
        if (packet.bytes.size > (SocialCodec.notificationValueLimit(mtu) ?: 0)) {
            failPackets(BleDiagnosticCause.FRAGMENT_EXCEEDS_MTU)
            return
        }
        val status = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.notifyCharacteristicChanged(remote, packet.characteristic, false, packet.bytes)
            } else {
                packet.characteristic.value = packet.bytes
                if (gatt.notifyCharacteristicChanged(remote, packet.characteristic, false)) BluetoothGatt.GATT_SUCCESS
                else BluetoothGatt.GATT_FAILURE
            }
        }.getOrNull()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failPackets(BleDiagnosticCause.REQUEST_NOT_QUEUED, status)
            return
        }
        packetDeadlineMs = SystemClock.elapsedRealtime() + PACKET_TIMEOUT_MS
        packetTimeout = Runnable {
            diagnose(BleDiagnosticEvent.NOTIFICATION_TIMED_OUT, null)
            failPackets(BleDiagnosticCause.CALLBACK_TIMEOUT)
        }.also { handler.postDelayed(it, PACKET_TIMEOUT_MS) }
    }

    private fun cancelPacketTimeout() {
        packetTimeout?.let(handler::removeCallbacks)
        packetTimeout = null
        packetDeadlineMs = 0
    }

    private fun failPackets(cause: BleDiagnosticCause, status: Int? = null) {
        detail(BleDiagnostic(0, BleDiagnosticEvent.NOTIFICATION_FAILED, status, cause))
        finishPending(false, cause)
        val removal = removeOutcome
        removeOutcome = null
        removal?.complete(false)
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
            val reported = BleMtuPolicy.validReportedMtu(mtu)
            if (reported == null) {
                detail(BleDiagnostic(0, BleDiagnosticEvent.SOCIAL_MTU_CHANGED, cause = BleDiagnosticCause.INVALID_MTU))
                return@dispatch
            }
            reportedMtu = reported
            diagnose(BleDiagnosticEvent.SOCIAL_MTU_CHANGED, reported)
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
                    finishPending(false, BleDiagnosticCause.SUBSCRIPTIONS_LOST)
                    if (packets.inFlight != null) {
                        restart()
                        return@dispatch
                    }
                    session.reset()
                    handler.removeCallbacks(recordExpiry)
                    records.clear()
                    packets.clear()
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
            val packet = packets.complete() ?: return@dispatch
            val deadline = packetDeadlineMs
            cancelPacketTimeout()
            if (deadline != 0L && SystemClock.elapsedRealtime() >= deadline) {
                failPackets(BleDiagnosticCause.CALLBACK_TIMEOUT)
                return@dispatch
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failPackets(BleDiagnosticCause.CALLBACK_FAILURE, status)
                return@dispatch
            }
            if (packet.characteristic == data) {
                detail(
                    BleDiagnostic(
                        0,
                        BleDiagnosticEvent.SOCIAL_FRAGMENT_SENT,
                        frameBytes = listOf(packet.bytes.size),
                        responseNumber = packet.responseNumber,
                    ),
                )
            } else {
                diagnose(BleDiagnosticEvent.NOTIFICATION_SENT, null)
            }
            expirePendingDelivery()
            if (closed) return@dispatch
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
        expirePendingDelivery()
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        val parsed = SocialCodec.decodeRequest(request)
        val record = parsed?.let { records[it.uid] }
        val number = ++responseNumber
        val applicationRequest = options.allowApplicationIdentifier && BleResponseMetadata.isAppNameRequest(request)
        val command = when {
            parsed != null -> BleResponseMetadata.NOTIFICATION_ATTRIBUTES
            applicationRequest -> BleResponseMetadata.APPLICATION_ATTRIBUTES
            else -> null
        }
        val attributes = if (applicationRequest) {
            listOf(BleAttributeDiagnostic(BleResponseMetadata.DISPLAY_NAME_ATTRIBUTE))
        } else {
            parsed?.attributes.orEmpty().map { BleAttributeDiagnostic(it.attribute.id, it.maximumLength) }
        }
        val requestInfo = ResponseRequest(command, parsed?.uid, attributes)
        detail(
            BleDiagnostic(
                0,
                BleDiagnosticEvent.CONTROL_REQUEST_RECEIVED,
                attributes = attributes,
                responseNumber = number,
                command = command,
            ),
        )
        if (packets.any { it.request == requestInfo }) {
            rejectRequest(device, requestId, responseNeeded, number, BleDiagnosticCause.DUPLICATE_RESPONSE_PENDING)
            return
        }
        val frames = session.respond(request, mtu, now)
        if (frames.isEmpty() || (parsed != null && record == null)) {
            val cause = if (parsed != null && (record == null || now - record.publishedAt >= RECORD_TTL_MS)) {
                BleDiagnosticCause.UNKNOWN_OR_EXPIRED_RECORD
            } else {
                BleDiagnosticCause.REJECTED_ATTRIBUTE
            }
            rejectRequest(device, requestId, responseNeeded, number, cause)
            return
        }
        val send = pending?.takeIf { it.uid == parsed?.uid }
        val requestedAttributes = parsed?.attributes.orEmpty().map { it.attribute }
        val payloadSizes = BleResponseMetadata.payloadSizes(frames, attributes, command)
        val transfers = frames.mapIndexed { index, bytes ->
            Packet(data, bytes, record, number, requestInfo) {
                if (index == frames.lastIndex) {
                    detail(
                        BleDiagnostic(
                            0,
                            BleDiagnosticEvent.CONTROL_RESPONSE_COMPLETED,
                            attributes = payloadSizes,
                            responseNumber = number,
                            command = command,
                        ),
                    )
                    if (send != null && pending === send) {
                        send.attributes.addAll(requestedAttributes)
                        if (SocialAttribute.TITLE in send.attributes && SocialAttribute.MESSAGE in send.attributes) finishPending(true)
                    }
                }
            }
        }
        if (!packets.offerBatch(transfers)) {
            rejectRequest(device, requestId, responseNeeded, number, BleDiagnosticCause.QUEUE_LIMIT)
            return
        }
        if (responseNeeded && !reply(device, requestId, BluetoothGatt.GATT_SUCCESS)) {
            packets.discardQueued { it.responseNumber == number }
            detail(
                BleDiagnostic(
                    0,
                    BleDiagnosticEvent.CONTROL_REQUEST_REJECTED,
                    cause = BleDiagnosticCause.ATT_WRITE_RESPONSE_FAILED,
                    responseNumber = number,
                ),
            )
            return
        }
        if (send != null) {
            send.requestReceived = true
            if (frames.size > 1) {
                val extension = (now + RECORD_TIMEOUT_MS + frames.size * FRAGMENT_BUDGET_MS)
                    .coerceAtMost(send.record.publishedAt + DELIVER_MAX_MS)
                if (extension > send.deadlineMs) {
                    send.deadlineMs = extension
                    armDeliveryTimeout(send)
                }
            }
        }
        detail(
            BleDiagnostic(
                0,
                BleDiagnosticEvent.CONTROL_RESPONSE_QUEUED,
                attributes = payloadSizes,
                frameBytes = frames.map(ByteArray::size),
                responseNumber = number,
                command = command,
            ),
        )
        sendNext()
    }

    private fun rejectRequest(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        number: Int,
        cause: BleDiagnosticCause,
    ) {
        if (responseNeeded) reply(device, requestId, BluetoothGatt.GATT_FAILURE)
        detail(BleDiagnostic(0, BleDiagnosticEvent.CONTROL_REQUEST_REJECTED, cause = cause, responseNumber = number))
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
        const val MAX_PACKETS = 128
        const val MAX_QUEUED_BYTES = 12_288
        const val RECORD_TTL_MS = 30_000L
        const val RECORD_TIMEOUT_MS = 10_000L
        const val DELIVER_MAX_MS = 25_000L
        const val FRAGMENT_BUDGET_MS = 150L
        const val PACKET_TIMEOUT_MS = 3_000L
        const val SERVICE_TIMEOUT_MS = 5_000L
    }
}
