package dev.stealthcompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stealthcompanion.R

@Composable
internal fun ProfilesPanel(state: StealthUiState, onAction: (UiAction) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProfileUi?>(null) }
    Page(R.string.profile_title, R.string.profile_intro) {
        Panel {
            SectionTitle(R.string.profile_slots)
            if (state.profiles.slotNames.isEmpty() && state.profiles.activeSlot == null) {
                BodyNote(R.string.profile_empty_slots)
            } else {
                val indices = (state.profiles.slotNames.keys + listOfNotNull(state.profiles.activeSlot)).sorted()
                indices.distinct().forEach { slot ->
                    val name = state.profiles.slotNames[slot].orEmpty()
                    val label = stringResource(R.string.profile_slot_name, slot + 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (name.isBlank()) label else "$label · $name",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Medium,
                        )
                        if (slot == state.profiles.activeSlot) {
                            Text(
                                stringResource(R.string.profile_slot_active),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = { onAction(UiAction.ReadSettings) }) {
                Text(stringResource(R.string.profile_read))
            }
        }
        val system = state.profiles
        if (system.powerMode != null || system.displayBrightness != null || system.sleepMinutes != null) {
            Panel {
                SectionTitle(R.string.profile_system)
                system.powerMode?.let { BodyNote(stringResource(R.string.profile_power_mode, it)) }
                system.displayBrightness?.let { BodyNote(stringResource(R.string.profile_display_brightness, it)) }
                system.sleepMinutes?.let { BodyNote(stringResource(R.string.profile_sleep, it)) }
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(R.string.profile_local) }
                TextButton(onClick = { adding = true }) { Text(stringResource(R.string.profile_create)) }
            }
            if (state.profiles.local.isEmpty()) {
                BodyNote(R.string.profile_local_empty)
            } else {
                state.profiles.local.forEach { profile ->
                    key(profile.id) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            TextButton(onClick = { deleting = profile }) { Text(stringResource(R.string.action_remove)) }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAction(UiAction.ImportProfiles) }) {
                    Text(stringResource(R.string.profile_import))
                }
                OutlinedButton(onClick = { onAction(UiAction.ExportProfiles) }) {
                    Text(stringResource(R.string.profile_export))
                }
            }
        }
    }
    if (adding) {
        NewProfileDialog(
            onDismiss = { adding = false },
            onAdd = { name ->
                onAction(UiAction.AddProfile(name))
                adding = false
            },
        )
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_body, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onAction(UiAction.RemoveProfile(profile.id))
                    deleting = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun NewProfileDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val initialName = stringResource(R.string.profile_new_default)
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_create)) },
        text = {
            OutlinedTextField(
                name, { name = it.take(64) },
                label = { Text(stringResource(R.string.profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onAdd(name.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
