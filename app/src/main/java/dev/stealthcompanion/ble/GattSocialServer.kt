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
    private val recoveryPeer: BluetoothDevice? = null,
    private val accepted: (BluetoothDevice) -> Boolean,
    private val diagnose: (BleDiagnosticEvent, Int?) -> Unit,
    private val detail: (BleDiagnostic) -> Unit,
    private val changed: () -> Unit,
    private val restart: () -> Unit,
) {
    private class Record(
        val key: String,
        val synthetic: Boolean,
        val longResponse: Boolean,
        val publishedAt: Long,
        val retainedMillis: Long,
        val probe: BleProbe?,
        val spacingMillis: Long,
    ) {
        val expiresAt: Long get() = publishedAt + retainedMillis
        val acceptedRequests = mutableMapOf<ResponseRequest, Int>()
        val acceptedAttributes = mutableMapOf<Int, Int>()
        var acceptedResponses = 0
        val repeatTimes = ArrayDeque<Long>()
    }

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
        val response: ResponseTransfer? = null,
        val frameIndex: Int? = null,
        val onSent: () -> Unit = {},
    ) {
        var dispatchedAtMillis = 0L
    }

    private data class ResponseRequest(val command: Int?, val uid: Int?, val attributes: List<BleAttributeDiagnostic>)

    private class ResponseTransfer(
        val request: ResponseRequest,
        val number: Int,
        val record: Record?,
        val repeatCount: Int,
        val payloadSizes: List<BleAttributeDiagnostic>,
        val wireFrameBytes: List<Int>,
    ) {
        val responseBytes = wireFrameBytes.sum()
        var completedBytes = 0
        var lastCallbackMillis: Long? = null

        fun diagnostic(
            event: BleDiagnosticEvent,
            status: Int? = null,
            cause: BleDiagnosticCause? = null,
            attributes: List<BleAttributeDiagnostic> = emptyList(),
            frameBytes: List<Int> = emptyList(),
            frameIndex: Int? = null,
            dispatchToCallbackMillis: Long? = null,
            gapFromPreviousCallbackMillis: Long? = null,
        ): BleDiagnostic = BleDiagnostic(
            0, event, status, cause, attributes, frameBytes, number, request.command,
            frameIndex = frameIndex,
            frameCount = wireFrameBytes.size,
            pacingMillis = record?.spacingMillis,
            dispatchToCallbackMillis = dispatchToCallbackMillis,
            gapFromPreviousCallbackMillis = gapFromPreviousCallbackMillis,
            responseBytes = responseBytes,
            completedBytes = completedBytes,
            repeatCount = repeatCount,
            probeId = record?.probe?.id,
            probePart = record?.probe?.part,
            probeParts = record?.probe?.parts,
        )
    }

    private var server: BluetoothGattServer? = null
    private var registered = false
    private var closed = false
    private var peer: BluetoothDevice? = null
    var notificationSourceSubscribed = false
        private set
    var dataSourceSubscribed = false
        private set
    val isRegistered: Boolean get() = registered && !closed
    val peerConnected: Boolean get() = peer != null
    private val session = SocialSession(options, MAX_RECORDS, RECORD_TTL_MS)
    private val records = LinkedHashMap<Int, Record>()
    private val packets = BleFrameQueue<Packet>(MAX_PACKETS, MAX_QUEUED_BYTES) { it.bytes.size }
    private val recordExpiry = delayedAction()
    private val pacedSend = delayedAction()
    private var pending: Send? = null
    private val pendingTimeout = delayedAction()
    private var removeOutcome: CompletableDeferred<Boolean>? = null
    private val packetTimeout = delayedAction()
    private var packetDeadlineMs = 0L
    private var responseNumber = 0
    private val serviceTimeout = delayedAction()
    private val attachTimeout = delayedAction()
    private var attachAttempted = false

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
    var currentMessageLimitBytes: Int? = null
        private set

    val mtu: Int get() = BleMtuPolicy.socialMtu(reportedMtu)

    val ready: Boolean get() = isRegistered && notificationSourceSubscribed && dataSourceSubscribed &&
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
        serviceTimeout.replace(SERVICE_TIMEOUT_MS) {
            if (!registered && !closed && server === opened) {
                diagnose(BleDiagnosticEvent.SERVER_FAILED, null)
                restart()
            }
        }
        return true
    }

    fun close() {
        closed = true
        serviceTimeout.cancel()
        attachTimeout.cancel()
        val previous = server
        server = null
        resetPeer()
        if (previous != null) runCatching { previous.close() }
    }

    fun peerDisconnected(device: BluetoothDevice, status: Int? = null) {
        if (peer?.address != device.address) return
        peer = null
        detail(BleDiagnostic(0, BleDiagnosticEvent.PEER_DISCONNECTED, status, BleDiagnosticCause.DISCONNECTED))
        if (packets.inFlight != null) {
            finishPending(false, BleDiagnosticCause.DISCONNECTED)
            // An Android callback carries no packet identifier, so restart this GATT server.
            restart()
            return
        }
        resetPeer(BleDiagnosticCause.DISCONNECTED)
        changed()
    }

    fun send(
        key: String,
        payload: SocialPayload,
        synthetic: Boolean,
        category: Int,
        longResponse: Boolean,
        probe: BleProbe? = null,
        regularExtendedDelayMs: Int = BlePacingPolicy.DEFAULT_EXTENDED_DELAY_MS,
    ): CompletableDeferred<Boolean>? {
        if (!ready || pending != null) return null
        val syntheticProbe = probe.takeIf { synthetic }
        val now = SystemClock.elapsedRealtime()
        if (expireRecords()) return null
        val sessionKey = if (synthetic) "synthetic:$key" else "notification:$key"
        val retainedMillis = if (syntheticProbe != null) SocialSession.MAX_PROBE_RETENTION_MS else RECORD_TTL_MS
        val event = session.publish(sessionKey, payload, now, category, longResponse, retainedMillis) ?: return null
        val uid = SocialCodec.decodeEvent(event)?.uid ?: return null
        val spacingMillis = BlePacingPolicy.delayMillis(longResponse, synthetic, regularExtendedDelayMs, syntheticProbe)
        val record = Record(sessionKey, synthetic, longResponse, now, retainedMillis, syntheticProbe, spacingMillis)
        if (!packets.offerBatch(listOf(Packet(source, event, record)))) {
            session.forget(sessionKey, now)
            detail(
                BleDiagnostic(
                    0, BleDiagnosticEvent.SOCIAL_DELIVERY_ABORTED, cause = BleDiagnosticCause.QUEUE_LIMIT,
                    probeId = syntheticProbe?.id,
                    probePart = syntheticProbe?.part,
                    probeParts = syntheticProbe?.parts,
                ),
            )
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
        recordExpiry.cancel()
        val now = SystemClock.elapsedRealtime()
        session.expire(now)
        records.entries.removeAll { now >= it.value.expiresAt }
        if (discardFrames(BleDiagnosticCause.RECORD_EXPIRED) {
                it.record?.let { record -> now >= record.expiresAt } == true
            }) return true
        val nearestExpiry = records.values.minOfOrNull(Record::expiresAt) ?: return false
        recordExpiry.replace((nearestExpiry - now).coerceAtLeast(1)) { expireRecords() }
        return false
    }

    private fun forgetRecord(send: Send, cause: BleDiagnosticCause): Boolean {
        records.entries.removeAll { it.value.key == send.record.key }
        session.forget(send.record.key, SystemClock.elapsedRealtime())
        if (discardFrames(cause) { it.record?.key == send.record.key }) return true
        return expireRecords()
    }

    private fun discardFrames(cause: BleDiagnosticCause, matches: (Packet) -> Boolean): Boolean {
        val activeResponse = packets.startedFrame?.response
        if (!packets.discardQueued(matches)) return false
        // A subsequent response would be interpreted as the tail of the interrupted response.
        // Cancel the approved server connection as well as replacing the local GATT instance.
        closed = true
        val remote = peer
        if (remote != null) runCatching { server?.cancelConnection(remote) }
        detail(
            activeResponse?.diagnostic(BleDiagnosticEvent.PARTIAL_RESPONSE_RESET, cause = cause)
                ?: BleDiagnostic(0, BleDiagnosticEvent.PARTIAL_RESPONSE_RESET, cause = cause),
        )
        finishPending(false, cause)
        restart()
        return true
    }

    private fun armDeliveryTimeout(send: Send) {
        pendingTimeout.replace((send.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
            if (pending === send) expirePendingDelivery()
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
        pendingTimeout.cancel()
        val send = pending ?: return
        pending = null
        val event = if (success) BleDiagnosticEvent.SOCIAL_DELIVERY_COMPLETED else BleDiagnosticEvent.SOCIAL_DELIVERY_ABORTED
        detail(
            BleDiagnostic(
                0, event, cause = cause, attributes = send.attributes.map { BleAttributeDiagnostic(it.id) },
                probeId = send.record.probe?.id,
                probePart = send.record.probe?.part,
                probeParts = send.record.probe?.parts,
            ),
        )
        send.outcome.complete(success)
    }

    private fun resetPeer(cause: BleDiagnosticCause = BleDiagnosticCause.TRANSPORT_CLOSED) {
        val previousPeer = peer
        peer = null
        reportedMtu = null
        notificationSourceSubscribed = false
        dataSourceSubscribed = false
        clearPublications(cause)
        if (previousPeer != null) detail(BleDiagnostic(0, BleDiagnosticEvent.PEER_DISCONNECTED, cause = cause))
    }

    private fun clearPublications(cause: BleDiagnosticCause) {
        currentMessageLimitBytes = null
        session.reset()
        recordExpiry.cancel()
        pacedSend.cancel()
        records.clear()
        packets.clear()
        val removal = removeOutcome
        removeOutcome = null
        removal?.complete(false)
        cancelPacketTimeout()
        finishPending(false, cause)
    }

    private fun sendNext() {
        pacedSend.cancel()
        expirePendingDelivery()
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (records.values.any { now >= it.expiresAt } && expireRecords()) return
        val waitMillis = packets.remainingDelayMillis(now)
        if (waitMillis > 0) {
            pacedSend.replace(waitMillis) { sendNext() }
            return
        }
        val packet = packets.launchNext(now) ?: return
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
        packet.dispatchedAtMillis = SystemClock.elapsedRealtime()
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
        packet.response?.let { response ->
            detail(
                response.diagnostic(
                    BleDiagnosticEvent.SOCIAL_FRAGMENT_DISPATCHED,
                    frameBytes = listOf(packet.bytes.size),
                    frameIndex = packet.frameIndex,
                    gapFromPreviousCallbackMillis = response.lastCallbackMillis?.let {
                        (packet.dispatchedAtMillis - it).coerceAtLeast(0)
                    },
                ),
            )
        }
        packetDeadlineMs = packet.dispatchedAtMillis + PACKET_TIMEOUT_MS
        packetTimeout.replace((packetDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
            if (packets.inFlight !== packet) return@replace
            diagnose(BleDiagnosticEvent.NOTIFICATION_TIMED_OUT, null)
            failPackets(BleDiagnosticCause.CALLBACK_TIMEOUT)
        }
    }

    private fun cancelPacketTimeout() {
        packetTimeout.cancel()
        packetDeadlineMs = 0
    }

    private fun failPackets(cause: BleDiagnosticCause, status: Int? = null, callbackAtMillis: Long? = null) {
        val failedFrame = packets.inFlight?.let { packet ->
            packet.response?.diagnostic(
                BleDiagnosticEvent.NOTIFICATION_FAILED, status, cause, frameIndex = packet.frameIndex,
                dispatchToCallbackMillis = callbackAtMillis?.let { (it - packet.dispatchedAtMillis).coerceAtLeast(0) },
            )
        }
        detail(
            failedFrame ?: BleDiagnostic(0, BleDiagnosticEvent.NOTIFICATION_FAILED, status, cause),
        )
        finishPending(false, cause)
        val removal = removeOutcome
        removeOutcome = null
        removal?.complete(false)
        restart()
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = dispatch {
            if (registered || service.uuid != SocialCodec.SERVICE_UUID) return@dispatch
            serviceTimeout.cancel()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                registered = true
                diagnose(BleDiagnosticEvent.SERVER_OPENED, null)
                changed()
                tryRecoveryAttach()
            } else {
                diagnose(BleDiagnosticEvent.SERVER_FAILED, status)
                restart()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = dispatch {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (peer == null && attachTimeout.isPending && accepted(device)) {
                    attachTimeout.cancel()
                    detail(
                        BleDiagnostic(0, BleDiagnosticEvent.SOCIAL_ATTACH_FAILED, status, BleDiagnosticCause.DISCONNECTED),
                    )
                }
                peerDisconnected(device, status)
            } else if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS && accepted(device)) {
                setPeer(device, status)
                changed()
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
                SocialCodec.NOTIFICATION_SOURCE_UUID -> notificationSourceSubscribed
                SocialCodec.DATA_SOURCE_UUID -> dataSourceSubscribed
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
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (responseNeeded) reply(device, requestId, status)
                    diagnose(BleDiagnosticEvent.PEER_REJECTED, status)
                    return@dispatch
                }
                val subscription = if (characteristic == SocialCodec.NOTIFICATION_SOURCE_UUID) {
                    BleSubscription.NOTIFICATION_SOURCE
                } else {
                    BleSubscription.DATA_SOURCE
                }
                if (responseNeeded && !reply(device, requestId, status)) {
                    detail(
                        BleDiagnostic(
                            0, BleDiagnosticEvent.PEER_REJECTED, cause = BleDiagnosticCause.ATT_WRITE_RESPONSE_FAILED,
                            subscription = subscription,
                        ),
                    )
                    return@dispatch
                }
                setPeer(device)
                val wasReady = ready
                if (subscription == BleSubscription.NOTIFICATION_SOURCE) notificationSourceSubscribed = enable
                else dataSourceSubscribed = enable
                detail(
                    BleDiagnostic(
                        0, BleDiagnosticEvent.SUBSCRIPTION_CHANGED, if (enable) 1 else 0,
                        subscription = subscription, subscriptionEnabled = enable,
                    ),
                )
                if (wasReady && !ready) {
                    finishPending(false, BleDiagnosticCause.SUBSCRIPTIONS_LOST)
                    if (packets.hasStartedBatch) {
                        restart()
                        return@dispatch
                    }
                    clearPublications(BleDiagnosticCause.SUBSCRIPTIONS_LOST)
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

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val callbackAtMillis = SystemClock.elapsedRealtime()
            dispatch { handleNotificationCallback(device, status, callbackAtMillis) }
        }
    }

    private fun handleNotificationCallback(device: BluetoothDevice, status: Int, callbackAtMillis: Long) {
        if (peer?.address != device.address) return
        val packet = packets.inFlight ?: return
        val deadline = packetDeadlineMs
        cancelPacketTimeout()
        if (deadline != 0L && callbackAtMillis >= deadline) {
            failPackets(BleDiagnosticCause.CALLBACK_TIMEOUT, status, callbackAtMillis)
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failPackets(BleDiagnosticCause.CALLBACK_FAILURE, status, callbackAtMillis)
            return
        }
        packets.complete(callbackAtMillis)
        val response = packet.response
        if (response != null) {
            response.completedBytes += packet.bytes.size
            response.lastCallbackMillis = callbackAtMillis
            detail(
                response.diagnostic(
                    BleDiagnosticEvent.SOCIAL_FRAGMENT_SENT, status,
                    frameBytes = listOf(packet.bytes.size),
                    frameIndex = packet.frameIndex,
                    dispatchToCallbackMillis = (callbackAtMillis - packet.dispatchedAtMillis).coerceAtLeast(0),
                ),
            )
        } else {
            diagnose(BleDiagnosticEvent.NOTIFICATION_SENT, null)
        }
        expirePendingDelivery()
        if (closed) return
        packet.onSent()
        sendNext()
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
        if (records.values.any { now >= it.expiresAt } && expireRecords()) return
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
        val exactRepeatCount = record?.acceptedRequests?.get(requestInfo) ?: 0
        val repeatCount = attributes.maxOfOrNull { record?.acceptedAttributes?.get(it.id) ?: 0 } ?: 0
        val requested = BleDiagnostic(
            0,
            BleDiagnosticEvent.CONTROL_REQUEST_RECEIVED,
            attributes = attributes,
            responseNumber = number,
            command = command,
            pacingMillis = record?.spacingMillis,
            repeatCount = repeatCount,
            probeId = record?.probe?.id,
            probePart = record?.probe?.part,
            probeParts = record?.probe?.parts,
        )
        detail(requested)
        if (packets.any { it.response?.request == requestInfo }) {
            rejectRequest(device, requestId, responseNeeded, requested, BleDiagnosticCause.DUPLICATE_RESPONSE_PENDING)
            return
        }
        if (record != null && !canRespond(record, exactRepeatCount, repeatCount > 0, now)) {
            rejectRequest(device, requestId, responseNeeded, requested, BleDiagnosticCause.REPEAT_LIMIT)
            return
        }
        val frames = session.respond(request, mtu, now)
        if (frames.isEmpty() || (parsed != null && record == null)) {
            val cause = if (parsed != null && (record == null || now >= record.expiresAt)) {
                BleDiagnosticCause.UNKNOWN_OR_EXPIRED_RECORD
            } else {
                BleDiagnosticCause.REJECTED_ATTRIBUTE
            }
            rejectRequest(device, requestId, responseNeeded, requested, cause)
            return
        }
        val send = pending?.takeIf { it.uid == parsed?.uid }
        val requestedAttributes = parsed?.attributes.orEmpty().map { it.attribute }
        val payloadSizes = BleResponseMetadata.payloadSizes(frames, attributes, command)
        val response = ResponseTransfer(requestInfo, number, record, repeatCount, payloadSizes, frames.map(ByteArray::size))
        val transfers = frames.mapIndexed { index, bytes ->
            Packet(data, bytes, record, response, index + 1) {
                if (index == frames.lastIndex) {
                    detail(
                        response.diagnostic(
                            BleDiagnosticEvent.CONTROL_RESPONSE_COMPLETED,
                            attributes = response.payloadSizes,
                            frameBytes = response.wireFrameBytes,
                        ),
                    )
                    if (send != null && pending === send) {
                        send.attributes.addAll(requestedAttributes)
                        if (SocialAttribute.TITLE in send.attributes && SocialAttribute.MESSAGE in send.attributes) finishPending(true)
                    }
                }
            }
        }
        val spacingMillis = record?.spacingMillis ?: 0L
        if (!packets.offerBatch(transfers, spacingMillis)) {
            rejectRequest(device, requestId, responseNeeded, requested, BleDiagnosticCause.QUEUE_LIMIT)
            return
        }
        if (responseNeeded && !reply(device, requestId, BluetoothGatt.GATT_SUCCESS)) {
            packets.discardQueued { it.response === response }
            detail(
                response.diagnostic(
                    BleDiagnosticEvent.CONTROL_REQUEST_REJECTED,
                    cause = BleDiagnosticCause.ATT_WRITE_RESPONSE_FAILED,
                ),
            )
            return
        }
        if (record != null) {
            record.acceptedRequests[requestInfo] = exactRepeatCount + 1
            attributes.forEach { attribute ->
                record.acceptedAttributes[attribute.id] = (record.acceptedAttributes[attribute.id] ?: 0) + 1
            }
            record.acceptedResponses++
            if (repeatCount > 0) record.repeatTimes.addLast(now)
            if (!record.synthetic && requireEncryption) {
                val requestedLimit = parsed?.attributes
                    ?.firstOrNull { it.attribute == SocialAttribute.MESSAGE }?.maximumLength
                if (requestedLimit != null && currentMessageLimitBytes != requestedLimit) {
                    currentMessageLimitBytes = requestedLimit
                    changed()
                }
            }
        }
        if (send != null) {
            send.requestReceived = true
            if (frames.size > 1) {
                val maxDeliveryMillis = if (send.record.probe == null) DELIVER_MAX_MS else PROBE_DELIVER_MAX_MS
                val extension = (now + RECORD_TIMEOUT_MS + frames.size * (FRAGMENT_BUDGET_MS + spacingMillis))
                    .coerceAtMost(send.record.publishedAt + maxDeliveryMillis)
                if (extension > send.deadlineMs) {
                    send.deadlineMs = extension
                    armDeliveryTimeout(send)
                }
            }
        }
        detail(
            response.diagnostic(
                BleDiagnosticEvent.CONTROL_RESPONSE_QUEUED,
                attributes = response.payloadSizes,
                frameBytes = response.wireFrameBytes,
            ),
        )
        sendNext()
    }

    private fun canRespond(record: Record, exactRepeatCount: Int, hasRepeatedAttribute: Boolean, now: Long): Boolean {
        if (record.acceptedResponses >= MAX_RESPONSES_PER_RECORD || exactRepeatCount > MAX_REPEATS_PER_REQUEST) {
            return false
        }
        if (!hasRepeatedAttribute) return true
        while (record.repeatTimes.firstOrNull()?.let { now - it >= REPEAT_WINDOW_MS } == true) {
            record.repeatTimes.removeFirst()
        }
        return record.repeatTimes.size < MAX_REPEATS_PER_WINDOW
    }

    private fun rejectRequest(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        requested: BleDiagnostic,
        cause: BleDiagnosticCause,
    ) {
        if (responseNeeded) reply(device, requestId, BluetoothGatt.GATT_FAILURE)
        detail(requested.copy(event = BleDiagnosticEvent.CONTROL_REQUEST_REJECTED, cause = cause))
    }

    private fun setPeer(device: BluetoothDevice, status: Int? = null) {
        attachTimeout.cancel()
        if (peer?.address != device.address) {
            resetPeer()
            peer = device
            diagnose(BleDiagnosticEvent.PEER_CONNECTED, status)
        }
    }

    private fun tryRecoveryAttach() {
        val remote = recoveryPeer ?: return
        val registration = server ?: return
        if (closed || !registered || !accepted(remote) || peerConnected || attachAttempted) return
        attachAttempted = true
        diagnose(BleDiagnosticEvent.SOCIAL_ATTACH_REQUESTED, null)
        if (!runCatching { registration.connect(remote, false) }.getOrDefault(false)) {
            detail(BleDiagnostic(0, BleDiagnosticEvent.SOCIAL_ATTACH_FAILED, cause = BleDiagnosticCause.REQUEST_NOT_QUEUED))
            return
        }
        attachTimeout.replace(ATTACH_TIMEOUT_MS) {
            if (closed || server !== registration || peerConnected) return@replace
            runCatching {
                // Do not cancel a link which Android already considers established.
                if (bluetooth.getConnectionState(remote, BluetoothProfile.GATT_SERVER) != BluetoothProfile.STATE_CONNECTED) {
                    registration.cancelConnection(remote)
                }
            }
            detail(BleDiagnostic(0, BleDiagnosticEvent.SOCIAL_ATTACH_FAILED, cause = BleDiagnosticCause.CALLBACK_TIMEOUT))
        }
    }

    private fun delayedAction() = BleDelayedAction(
        post = { task, delay -> handler.postDelayed(task, delay) },
        remove = handler::removeCallbacks,
    )

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
        const val RECORD_TTL_MS = SocialSession.MAX_PERSONAL_RETENTION_MS
        const val RECORD_TIMEOUT_MS = 10_000L
        const val DELIVER_MAX_MS = 25_000L
        const val PROBE_DELIVER_MAX_MS = 80_000L
        const val FRAGMENT_BUDGET_MS = 150L
        const val MAX_RESPONSES_PER_RECORD = 12
        const val MAX_REPEATS_PER_REQUEST = 4
        const val MAX_REPEATS_PER_WINDOW = 4
        const val REPEAT_WINDOW_MS = 1_000L
        const val PACKET_TIMEOUT_MS = 3_000L
        const val SERVICE_TIMEOUT_MS = 5_000L
        const val ATTACH_TIMEOUT_MS = 6_000L
    }
}
