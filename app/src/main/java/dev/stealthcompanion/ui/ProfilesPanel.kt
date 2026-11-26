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
    var renaming by remember { mutableStateOf<ProfileUi?>(null) }
    var deleting by remember { mutableStateOf<ProfileUi?>(null) }
    Page(R.string.profile_title, R.string.profile_intro) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(R.string.profile_local) }
                TextButton(onClick = { adding = true }) { Text(stringResource(R.string.profile_create)) }
            }
            if (state.profiles.local.isEmpty()) {
                BodyNote(R.string.profile_local_empty)
            } else {
                BodyNote(R.string.profile_local_selection_note)
                state.profiles.local.forEach { profile ->
                    key(profile.id) {
                        ChoiceRow(profile.name, profile.id == state.profiles.selectedLocalProfileId) {
                            onAction(UiAction.SelectLocalProfile(profile.id))
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { renaming = profile }) {
                                Text(stringResource(R.string.profile_rename))
                            }
                            TextButton(onClick = { deleting = profile }) {
                                Text(stringResource(R.string.action_remove))
                            }
                        }
                    }
                }
                if (state.profiles.selectedLocalProfileId != null) {
                    TextButton(onClick = { onAction(UiAction.SelectLocalProfile(null)) }) {
                        Text(stringResource(R.string.profile_clear_selection))
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
        val canRead = state.mode == CompanionMode.CONTROLLER && !state.notificationsOnly &&
            state.configuration.connected && !state.configuration.reading
        ControllerSlots(state.profiles, canRead) {
            onAction(UiAction.ReadSettings)
        }
    }
    if (adding) {
        ProfileNameDialog(
            title = stringResource(R.string.profile_create),
            initialName = stringResource(R.string.profile_new_default),
            onDismiss = { adding = false },
            onSave = { name ->
                onAction(UiAction.AddProfile(name))
                adding = false
            },
        )
    }
    renaming?.let { profile ->
        key(profile.id) {
            ProfileNameDialog(
                title = stringResource(R.string.profile_rename),
                initialName = profile.name,
                onDismiss = { renaming = null },
                onSave = { name ->
                    onAction(UiAction.RenameProfile(profile.id, name))
                    renaming = null
                },
            )
        }
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
private fun ControllerSlots(profiles: ProfilesUi, canRead: Boolean, read: () -> Unit) {
    Panel {
        SectionTitle(R.string.profile_slots)
        if (profiles.slotNames.isEmpty() && profiles.activeSlot == null) {
            BodyNote(R.string.profile_empty_slots)
        } else {
            (1..10).forEach { slot ->
                val name = profiles.slotNames[slot]
                val displayName = when {
                    name == null -> stringResource(R.string.configuration_undefined)
                    name.isBlank() -> stringResource(R.string.profile_slot_unnamed)
                    else -> name
                }
                val slotLabel = stringResource(R.string.profile_slot_name, slot)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$slotLabel · $displayName",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Medium,
                    )
                    if (slot == profiles.activeSlot) {
                        Text(
                            stringResource(R.string.profile_slot_active),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
        OutlinedButton(enabled = canRead, onClick = read) { Text(stringResource(R.string.profile_read)) }
    }
}

@Composable
private fun ProfileNameDialog(title: String, initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()
    val valid = trimmedName.isNotEmpty() && trimmedName.codePointCount(0, trimmedName.length) <= 64 &&
        trimmedName.none(Char::isISOControl)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { if (it.length <= 256) name = it },
                label = { Text(stringResource(R.string.profile_name)) },
                supportingText = { Text(stringResource(R.string.profile_name_hint)) },
                isError = name.isNotBlank() && !valid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onSave(trimmedName) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
