package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stealthcompanion.R

@Composable
internal fun LabPanel(state: StealthUiState, onAction: (UiAction) -> Unit) {
    val initialTitle = stringResource(R.string.sample_title)
    val initialBody = stringResource(R.string.sample_body)
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var body by remember(initialBody) { mutableStateOf(initialBody) }
    var selected by remember { mutableStateOf<DisplayCapability?>(null) }
    Page(R.string.lab_title, R.string.lab_intro) {
        DisplayPreview(state.preview)
        Panel {
            SectionTitle(R.string.synthetic_title)
            OutlinedTextField(
                title, { if (it.length <= 512) title = it },
                label = { Text(stringResource(R.string.synthetic_title_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                body, { if (it.length <= 4096) body = it },
                label = { Text(stringResource(R.string.synthetic_body_field)) },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onAction(UiAction.SendSynthetic(title, body)) },
                enabled = title.isNotBlank() || body.isNotBlank(),
            ) { Text(stringResource(R.string.synthetic_send)) }
        }
        Panel {
            SectionTitle(R.string.probes_title)
            SwitchRow(R.string.probes_enable, state.probesEnabled, {
                onAction(UiAction.SetProbesEnabled(it))
            }, R.string.probes_toggle_note)
            if (state.mode == CompanionMode.CONTROLLER && state.probesEnabled) {
                SwitchRow(R.string.probes_relaxed, state.relaxedSynthetic, {
                    onAction(UiAction.SetRelaxedSynthetic(it))
                }, R.string.probes_relaxed_note)
            }
        }
        if (state.probesEnabled) {
            DisplayCapability.entries.forEach { capability ->
                val result = state.observations[capability] ?: ProbeObservation.UNTESTED
                val expanded = selected == capability
                Panel {
                    ChoiceRow(stringResource(capability.label()), expanded) {
                        selected = capability.takeUnless { expanded }
                    }
                    Text(
                        stringResource(result.label()),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (expanded) {
                        BodyNote(capability.instructions())
                        OutlinedButton(
                            enabled = state.mode == CompanionMode.CONTROLLER && state.connection == ConnectionState.READY,
                            onClick = { onAction(UiAction.RunProbe(capability)) },
                        ) { Text(stringResource(R.string.probe_run)) }
                        if (state.mode == CompanionMode.CONTROLLER) {
                            Text(stringResource(R.string.probe_observation), style = MaterialTheme.typography.titleSmall)
                            ObservationChoices(result) { observation ->
                                onAction(UiAction.SetObservation(capability, observation))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObservationChoices(current: ProbeObservation, onSelect: (ProbeObservation) -> Unit) {
    Column {
        ProbeObservation.entries.chunked(2).forEach { items ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { item ->
                    FilterChip(
                        selected = current == item,
                        onClick = { onSelect(item) },
                        label = { Text(stringResource(item.label())) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayPreview(preview: DisplayPreviewUi?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp),
        color = Color(0xff241735),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(Glyph.LAB, Color(0xffd6beff))
                Text(
                    stringResource(R.string.preview_label),
                    color = Color(0xffd6beff),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xff151021),
                shape = RoundedCornerShape(15.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (preview == null) {
                        Text(
                            stringResource(R.string.preview_empty),
                            color = Color(0xffd0c5dc),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            preview.title, color = Color(0xfff8eeff), fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            preview.body, color = Color(0xffd0c5dc), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@StringRes
private fun DisplayCapability.label(): Int = when (this) {
    DisplayCapability.AUTO_OPEN -> R.string.cap_auto_open
    DisplayCapability.WAKE_DISPLAY -> R.string.cap_wake
    DisplayCapability.LONG_TEXT -> R.string.cap_length
    DisplayCapability.SCROLL -> R.string.cap_scroll
    DisplayCapability.APP_ICONS -> R.string.cap_icons
    DisplayCapability.CATEGORIES -> R.string.cap_categories
    DisplayCapability.VIBRATION -> R.string.cap_vibration
    DisplayCapability.DURATION -> R.string.cap_duration
    DisplayCapability.UPDATE -> R.string.cap_update
    DisplayCapability.REMOVAL -> R.string.cap_removal
}

@StringRes
private fun DisplayCapability.instructions(): Int = when (this) {
    DisplayCapability.AUTO_OPEN -> R.string.cap_auto_open_note
    DisplayCapability.WAKE_DISPLAY -> R.string.cap_wake_note
    DisplayCapability.LONG_TEXT -> R.string.cap_length_note
    DisplayCapability.SCROLL -> R.string.cap_scroll_note
    DisplayCapability.APP_ICONS -> R.string.cap_icons_note
    DisplayCapability.CATEGORIES -> R.string.cap_categories_note
    DisplayCapability.VIBRATION -> R.string.cap_vibration_note
    DisplayCapability.DURATION -> R.string.cap_duration_note
    DisplayCapability.UPDATE -> R.string.cap_update_note
    DisplayCapability.REMOVAL -> R.string.cap_removal_note
}

@StringRes
private fun ProbeObservation.label(): Int = when (this) {
    ProbeObservation.UNTESTED -> R.string.observation_untested
    ProbeObservation.OBSERVED -> R.string.observation_yes
    ProbeObservation.NOT_OBSERVED -> R.string.observation_no
    ProbeObservation.INCONCLUSIVE -> R.string.observation_uncertain
}
