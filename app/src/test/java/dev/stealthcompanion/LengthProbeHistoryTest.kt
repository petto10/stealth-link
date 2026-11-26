package dev.stealthcompanion

import dev.stealthcompanion.core.protocol.LengthProbe
import dev.stealthcompanion.core.protocol.ProbeText
import dev.stealthcompanion.ui.CompanionMode
import dev.stealthcompanion.ui.LengthProbeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LengthProbeHistoryTest {
    @Test
    fun exportRetainsEachTestOutcomeAndContainsOnlyMetadata() {
        val history = LengthProbeHistory()
        val spec = LengthProbe(bytes = 255, delayMs = 25, text = ProbeText.ACCENTS)
        history.queued(43, spec, extended = true, CompanionMode.CONTROLLER, parts = 1)
        history.finish(43, completed = 1, LengthProbeOutcome.GATT_COMPLETE)
        history.queued(44, spec.copy(paged = true), extended = false, CompanionMode.CONTROLLER, parts = 22)
        history.finish(44, completed = 5, LengthProbeOutcome.INCOMPLETE)

        val result = buildString { history.appendTo(this) }
        assertEquals(2, result.lineSequence().count(String::isNotBlank))
        assertTrue(result.contains("test=43 mode=CONTROLLER bytes=255 text_kind=ACCENTS delay_ms=25 extended=true paged=false parts=1/1 outcome=GATT_COMPLETE"))
        assertTrue(result.contains("test=44 mode=CONTROLLER bytes=255 text_kind=ACCENTS delay_ms=0 extended=false paged=true parts=5/22 outcome=INCOMPLETE"))
        assertFalse(result.contains(spec.body()))
    }

    @Test
    fun boundedHistoryKeepsLatestRunsAndDoesNotOverwriteTerminalResults() {
        val history = LengthProbeHistory(capacity = 2)
        for (id in 1L..3L) history.queued(id, LengthProbe(), extended = false, CompanionMode.SIMULATOR, parts = 1)
        history.finish(2, completed = 1, LengthProbeOutcome.SIMULATOR_COMPLETE)
        history.finish(2, completed = 0, LengthProbeOutcome.INCOMPLETE)
        history.finish(1, completed = 1, LengthProbeOutcome.SIMULATOR_COMPLETE)

        val result = buildString { history.appendTo(this) }
        assertFalse(result.contains("test=1 "))
        assertTrue(result.lineSequence().first { "test=2 " in it }.endsWith("outcome=SIMULATOR_COMPLETE"))
        assertTrue(result.lineSequence().first { "test=3 " in it }.endsWith("outcome=QUEUED"))
    }
}
