package dev.stealthcompanion.ble

import android.os.Handler
import java.util.UUID

/**
 * Allowlisted queries. The only application writes this channel can originate are requests
 * for slot, profile name, and system data.
 */
internal class ReadOnlyGattSettings(
    private val handler: Handler,
    private val publish: (ReadOnlySettings) -> Unit,
    private val diagnose: (BleDiagnosticEvent, Int?) -> Unit,
    private val disconnected: () -> Unit,
) {
    private var writeQuery: ((ByteArray) -> Boolean)? = null
    private var snapshot = ReadOnlySettings()
    private val queue = ArrayDeque<SettingsQuery>()
    private var pending: SettingsQuery? = null
    private var received = false
    private var acknowledged = false
    private var missingResponse = false
    private var timeout: Runnable? = null

    fun onLinkReady(write: (ByteArray) -> Boolean) {
        writeQuery = write
    }

    fun onLinkEnded(clear: Boolean = false) {
        writeQuery = null
        cancelTimeout()
        queue.clear()
        pending = null
        if (snapshot.reading) diagnose(BleDiagnosticEvent.SETTINGS_READ_PARTIAL, null)
        snapshot = if (clear) ReadOnlySettings() else snapshot.copy(reading = false, complete = false)
        publish(snapshot)
    }

    fun refresh() {
        if (writeQuery == null || pending != null) return
        queue.clear()
        missingResponse = false
        queue.addLast(SettingsQuery(SettingsQueryType.ACTIVE_SLOT))
        for (slot in 0 until ReadOnlySettingsCodec.SLOT_COUNT) queue.addLast(SettingsQuery(SettingsQueryType.PROFILE_NAME, slot))
        queue.addLast(SettingsQuery(SettingsQueryType.SYSTEM))
        snapshot = ReadOnlySettings(reading = true)
        publish(snapshot)
        issueNext()
    }

    fun onWrite(status: Int) {
        if (pending == null) return
        if (status != 0) {
            fail(status)
            return
        }
        acknowledged = true
        continueIfComplete()
    }

    fun onData(frame: ByteArray) {
        val query = pending ?: return
        if (received) return
        val updated = ReadOnlySettingsCodec.decode(query, frame, snapshot) ?: return
        snapshot = updated
        received = true
        publish(snapshot)
        continueIfComplete()
    }

    private fun issueNext() {
        pending = queue.removeFirstOrNull()
        val query = pending
        if (query == null) {
            snapshot = snapshot.copy(reading = false, complete = !missingResponse)
            publish(snapshot)
            diagnose(
                if (missingResponse) BleDiagnosticEvent.SETTINGS_READ_PARTIAL else BleDiagnosticEvent.SETTINGS_READ_COMPLETED,
                null,
            )
            return
        }
        received = false
        acknowledged = false
        diagnose(BleDiagnosticEvent.SETTINGS_READ_REQUESTED, query.type.ordinal)
        if (writeQuery?.invoke(query.bytes()) != true) {
            fail(null)
            return
        }
        timeout = Runnable {
            if (acknowledged) {
                // Each reply is matched by opcode and slot. No late write callback remains.
                cancelTimeout()
                missingResponse = true
                issueNext()
            } else {
                fail(null)
            }
        }.also { handler.postDelayed(it, QUERY_TIMEOUT_MS) }
    }

    private fun continueIfComplete() {
        if (!received || !acknowledged) return
        cancelTimeout()
        issueNext()
    }

    private fun fail(status: Int?) {
        cancelTimeout()
        queue.clear()
        pending = null
        snapshot = snapshot.copy(reading = false, complete = false)
        publish(snapshot)
        diagnose(BleDiagnosticEvent.SETTINGS_READ_PARTIAL, status)
        // A late write callback must not acknowledge a new query on this link.
        disconnected()
    }

    private fun cancelTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private const val QUERY_TIMEOUT_MS = 2_500L
    }
}
