package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stealthcompanion.R
import dev.stealthcompanion.core.protocol.LengthProbe
import dev.stealthcompanion.core.protocol.ProbeText

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LengthProbeControls(
    state: StealthUiState,
    canRun: Boolean,
    onAction: (UiAction) -> Unit,
) {
    var bytes by rememberSaveable { mutableStateOf(32) }
    var delayMs by rememberSaveable { mutableStateOf(0) }
    var text by rememberSaveable { mutableStateOf(ProbeText.ASCII) }
    var paged by rememberSaveable { mutableStateOf(false) }
    var showSample by rememberSaveable { mutableStateOf(false) }
    var lastProbe by remember { mutableStateOf<UiAction.SendLengthProbe?>(null) }
    val spec = remember(bytes, delayMs, text, paged) { LengthProbe(bytes, delayMs, text, paged) }
    val simulator = state.mode == CompanionMode.SIMULATOR

    Text(stringResource(R.string.probe_byte_count), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LengthProbe.SUPPORTED_SIZES.sorted().forEach { size ->
            FilterChip(
                selected = size == bytes,
                onClick = { bytes = size },
                label = { Text(size.toString()) },
            )
        }
    }

    Text(stringResource(R.string.probe_text_style), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProbeText.entries.forEach { style ->
            FilterChip(
                selected = style == text,
                onClick = { text = style },
                label = { Text(stringResource(style.label())) },
            )
        }
    }

    Text(stringResource(R.string.probe_fragment_delay), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LengthProbe.SUPPORTED_DELAYS.sorted().forEach { delay ->
            FilterChip(
                selected = delay == delayMs,
                onClick = { delayMs = delay },
                label = { Text(delay.toString()) },
            )
        }
    }
    SwitchRow(R.string.probe_pagination, paged, { paged = it }, R.string.probe_pagination_note)

    val samplePreview = stringResource(R.string.probe_show_sample)
    BodyNote(stringResource(R.string.probe_final_marker, spec.finalMarker))
    TextButton(onClick = { showSample = true }) { Text(samplePreview) }
    if (showSample) {
        AlertDialog(
            onDismissRequest = { showSample = false },
            title = { Text(samplePreview) },
            text = {
                Text(
                    spec.body(),
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showSample = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }

    fun submit(extended: Boolean) {
        val action = UiAction.SendLengthProbe(spec, extended)
        lastProbe = action
        onAction(action)
    }

    if (paged) {
        OutlinedButton(enabled = canRun, onClick = { submit(extended = false) }) {
            Text(stringResource(R.string.probe_send_paged))
        }
    } else {
        BodyNote(if (simulator) R.string.probe_comparison_simulator else R.string.probe_comparison_note)
        OutlinedButton(enabled = canRun, onClick = { submit(extended = false) }) {
            Text(stringResource(R.string.probe_legacy))
        }
        OutlinedButton(enabled = canRun, onClick = { submit(extended = true) }) {
            Text(stringResource(R.string.probe_extended))
        }
    }
    lastProbe?.let { previous ->
        TextButton(enabled = canRun, onClick = { onAction(previous) }) {
            Text(stringResource(R.string.probe_repeat))
        }
    }

    HorizontalDivider()
    state.lastLengthProbeId?.let { id ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.probe_last_id, id),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            state.lastLengthProbeOutcome?.let { outcome ->
                BodyNote(outcome.label())
                val canMark = !simulator &&
                    (outcome == LengthProbeOutcome.GATT_COMPLETE || outcome == LengthProbeOutcome.INCOMPLETE)
                if (canMark) {
                    OutlinedButton(onClick = { onAction(UiAction.MarkLengthProbeDetail) }) {
                        Text(stringResource(R.string.probe_mark_detail))
                    }
                    BodyNote(R.string.probe_mark_detail_note)
                }
            }
        }
    }
    AttributeReadout(state.diagnostics)
}

@StringRes
private fun ProbeText.label(): Int = when (this) {
    ProbeText.ASCII -> R.string.probe_text_ascii
    ProbeText.ACCENTS -> R.string.probe_text_accents
    ProbeText.EMOJI -> R.string.probe_text_emoji
    ProbeText.MULTILINE -> R.string.probe_text_multiline
}

@StringRes
private fun LengthProbeOutcome.label(): Int = when (this) {
    LengthProbeOutcome.QUEUED -> R.string.probe_outcome_queued
    LengthProbeOutcome.GATT_COMPLETE -> R.string.probe_outcome_gatt
    LengthProbeOutcome.SIMULATOR_COMPLETE -> R.string.probe_outcome_simulator
    LengthProbeOutcome.INCOMPLETE -> R.string.probe_outcome_incomplete
}
