package dev.stealthcompanion

import dev.stealthcompanion.core.protocol.LengthProbe
import dev.stealthcompanion.ui.CompanionMode
import dev.stealthcompanion.ui.LengthProbeOutcome

/** Keeps numerical laboratory results separate from BLE connection state and payloads. */
internal class LengthProbeHistory(private val capacity: Int = 48) {
    init { require(capacity > 0) }

    private data class Entry(
        val id: Long,
        val spec: LengthProbe,
        val extended: Boolean,
        val mode: CompanionMode,
        val parts: Int,
        val completed: Int = 0,
        val outcome: LengthProbeOutcome = LengthProbeOutcome.QUEUED,
    )

    private val entries = LinkedHashMap<Long, Entry>()

    fun queued(id: Long, spec: LengthProbe, extended: Boolean, mode: CompanionMode, parts: Int) {
        require(parts > 0 && id !in entries)
        entries[id] = Entry(id, spec, extended, mode, parts)
        if (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun finish(id: Long, completed: Int, outcome: LengthProbeOutcome) {
        val entry = entries[id] ?: return
        if (entry.outcome != LengthProbeOutcome.QUEUED) return
        require(completed in 0..entry.parts && outcome != LengthProbeOutcome.QUEUED)
        entries[id] = entry.copy(completed = completed, outcome = outcome)
    }

    fun appendTo(export: StringBuilder) {
        entries.values.forEach { entry ->
            val delayMs = if (entry.spec.paged) 0 else entry.spec.delayMs
            export.appendLine(
                "LENGTH_PROBE test=${entry.id} mode=${entry.mode} bytes=${entry.spec.bytes} " +
                    "text_kind=${entry.spec.text} delay_ms=$delayMs extended=${entry.extended} " +
                    "paged=${entry.spec.paged} parts=${entry.completed}/${entry.parts} outcome=${entry.outcome}",
            )
        }
    }
}
