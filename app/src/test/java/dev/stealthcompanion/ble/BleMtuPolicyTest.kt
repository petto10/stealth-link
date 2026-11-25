package dev.stealthcompanion.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleMtuPolicyTest {
    @Test
    fun clientNegotiationRequiresOptInSameApprovedPeerAndConfigurationChannel() {
        assertTrue(BleMtuPolicy.canRequestClientMtu(true, true, true, true))
        assertFalse(BleMtuPolicy.canRequestClientMtu(false, true, true, true))
        assertFalse(BleMtuPolicy.canRequestClientMtu(true, false, true, true))
        assertFalse(BleMtuPolicy.canRequestClientMtu(true, true, false, true))
        assertFalse(BleMtuPolicy.canRequestClientMtu(true, true, true, false))
    }

    @Test
    fun effectiveMtuUsesServerCallbackOrItsDefaultOnly() {
        assertEquals(23, BleMtuPolicy.socialMtu(null))
        assertEquals(247, BleMtuPolicy.socialMtu(247))
        assertEquals(517, BleMtuPolicy.socialMtu(517))
        assertEquals(23, BleMtuPolicy.socialMtu(518))
        assertNull(BleMtuPolicy.validReportedMtu(22))
        assertNull(BleMtuPolicy.validReportedMtu(518))
    }
}
