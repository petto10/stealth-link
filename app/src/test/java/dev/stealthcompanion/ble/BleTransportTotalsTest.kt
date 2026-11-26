package dev.stealthcompanion.ble

import dev.stealthcompanion.core.protocol.SocialAttribute
import org.junit.Assert.assertEquals
import org.junit.Test

class BleTransportTotalsTest {
    @Test fun `only callbacks count toward sent bytes and the two totals retain their units`() {
        val request = BleAttributeDiagnostic(SocialAttribute.MESSAGE.id, requestedMaxBytes = 100)
        val response = request.copy(payloadBytes = 30)
        val queued = BleState().recordTransportEvent(
            BleDiagnostic(1, BleDiagnosticEvent.CONTROL_REQUEST_RECEIVED, attributes = listOf(request)),
        ).recordTransportEvent(
            BleDiagnostic(2, BleDiagnosticEvent.CONTROL_RESPONSE_QUEUED, attributes = listOf(response), frameBytes = listOf(20, 18)),
        ).recordTransportEvent(
            BleDiagnostic(3, BleDiagnosticEvent.SOCIAL_FRAGMENT_DISPATCHED, frameBytes = listOf(20)),
        )
        assertEquals(100L, queued.requestedBytesTotal)
        assertEquals(30L, queued.declaredBytesTotal)
        assertEquals(30, queued.lastDeclaredBodyBytes)
        assertEquals(0L, queued.sentBytesTotal)
        assertEquals(0L, queued.wireBytesSentTotal)

        val partial = queued.recordTransportEvent(
            BleDiagnostic(4, BleDiagnosticEvent.SOCIAL_FRAGMENT_SENT, frameBytes = listOf(20)),
        )
        assertEquals(20L, partial.wireBytesSentTotal)
        assertEquals(0L, partial.sentBytesTotal)

        val sent = partial.recordTransportEvent(
            BleDiagnostic(5, BleDiagnosticEvent.SOCIAL_FRAGMENT_SENT, frameBytes = listOf(18)),
        ).recordTransportEvent(
            BleDiagnostic(6, BleDiagnosticEvent.CONTROL_RESPONSE_COMPLETED, attributes = listOf(response)),
        )
        assertEquals(38L, sent.wireBytesSentTotal)
        assertEquals(30L, sent.sentBytesTotal)
        assertEquals(2, sent.fragmentsSent)
    }

    @Test fun `settings callbacks and retries do not increment Social counters`() {
        val events = listOf(
            BleDiagnosticEvent.PEER_CONNECTED,
            BleDiagnosticEvent.CONNECTION_STARTED,
            BleDiagnosticEvent.CONNECTION_CONNECTED,
            BleDiagnosticEvent.CONNECTION_DISCONNECTED,
            BleDiagnosticEvent.SETTINGS_RETRY_SCHEDULED,
            BleDiagnosticEvent.SETTINGS_RETRY_SCHEDULED,
        )
        val state = events.fold(BleState(phase = BlePhase.READY)) { value, event ->
            value.recordTransportEvent(BleDiagnostic(0, event))
        }
        assertEquals(1L, state.socialConnections)
        assertEquals(0L, state.socialDisconnects)
        assertEquals(0L, state.socialServerRetries)
        assertEquals(1L, state.settingsConnectionAttempts)
        assertEquals(1L, state.settingsConnections)
        assertEquals(1L, state.settingsDisconnects)
        assertEquals(2L, state.settingsRetries)
        assertEquals(BlePhase.READY, state.phase)
    }
}
