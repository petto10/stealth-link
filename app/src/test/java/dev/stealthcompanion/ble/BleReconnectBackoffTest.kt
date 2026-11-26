package dev.stealthcompanion.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleReconnectBackoffTest {
    @Test fun `brief readiness does not reset the retry window`() {
        val backoff = BleReconnectBackoff()
        assertEquals(1_000L, backoff.nextDelayMillis(0, null, 0))
        assertEquals(2_250L, backoff.nextDelayMillis(2_000, 0, 250))
        assertEquals(4_000L, backoff.nextDelayMillis(24_999, 5_000, 0))
        assertEquals(1_000L, backoff.nextDelayMillis(25_000, 5_000, 0))
    }

    @Test fun `radio roles use independent bounded delays`() {
        val settings = BleReconnectBackoff()
        val social = BleReconnectBackoff()
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        assertEquals(expected, expected.map { settings.nextDelayMillis(0, null, 0) })
        assertEquals(1_000L, social.nextDelayMillis(0, null, -10))
        assertEquals(30_250L, settings.nextDelayMillis(0, null, 999))
        settings.reset()
        assertEquals(1_000L, settings.nextDelayMillis(0, null, 0))
        assertEquals(2_000L, social.nextDelayMillis(0, null, 0))
    }
}
