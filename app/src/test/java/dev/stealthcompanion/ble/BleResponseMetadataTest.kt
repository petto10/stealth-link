package dev.stealthcompanion.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleResponseMetadataTest {
    @Test
    fun headersAcrossFragmentsYieldByteCountsAndNoPayload() {
        val title = BleAttributeDiagnostic(id = 1, requestedMaxBytes = 300)
        val body = BleAttributeDiagnostic(id = 3, requestedMaxBytes = 1_024)
        val encoded = byteArrayOf(0, 0, 0, 0, 1, 1, 3, 0) + "Tè".toByteArray() +
            byteArrayOf(3, 4, 0) + "🎮".toByteArray()
        val fragments = encoded.toList().chunked(7).map { it.toByteArray() }
        assertEquals(
            listOf(title.copy(payloadBytes = 3), body.copy(payloadBytes = 4)),
            BleResponseMetadata.payloadSizes(fragments, listOf(title, body)),
        )
    }

    @Test
    fun appNameMetadataReportsDisplayNameLengthWithoutIdentifier() {
        val identifier = "dev.stealth.lab".toByteArray()
        val request = byteArrayOf(1) + identifier + byteArrayOf(0, 0)
        val response = byteArrayOf(1) + identifier + byteArrayOf(0, 0, 4, 0) + "Caff".toByteArray()
        val attribute = BleAttributeDiagnostic(0)
        assertTrue(BleResponseMetadata.isAppNameRequest(request))
        assertFalse(BleResponseMetadata.isAppNameRequest(request.copyOf(request.size - 1)))
        assertEquals(
            listOf(attribute.copy(payloadBytes = 4)),
            BleResponseMetadata.payloadSizes(listOf(response), listOf(attribute), BleResponseMetadata.APPLICATION_ATTRIBUTES),
        )
    }

    @Test
    fun invalidOrTruncatedHeadersDoNotClaimAnyPayload() {
        val title = BleAttributeDiagnostic(id = 1, requestedMaxBytes = 100)
        val frame = byteArrayOf(0, 0, 0, 0, 1, 1, 3, 0)
        assertEquals(listOf(title), BleResponseMetadata.payloadSizes(listOf(frame), listOf(title)))
    }
}
