package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stealthcompanion.R

@Composable
internal fun NotificationPanel(state: StealthUiState, onAction: (UiAction) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var editingPackage by remember { mutableStateOf<String?>(null) }
    val matches = remember(state.apps, search) {
        val query = search.trim()
        state.apps.filter { app ->
            app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
        }
    }
    LazyColumn(
        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeading(R.string.notifications_title, R.string.notifications_intro)
        }
        item {
            Panel {
                SwitchRow(R.string.privacy_locked, state.privacyWhileLocked, {
                    onAction(UiAction.SetLockPrivacy(it))
                }, R.string.privacy_locked_note)
            }
        }
        if (!state.notificationAccess) {
            item {
                Panel {
                    SectionTitle(R.string.permission_notifications)
                    BodyNote(R.string.permission_notifications_note)
                    TextButton(onClick = { onAction(UiAction.OpenNotificationAccess) }) {
                        Text(stringResource(R.string.action_settings))
                    }
                }
            }
        }
        item { SectionTitle(R.string.apps_title) }
        if (state.apps.isEmpty()) {
            item { Panel { BodyNote(R.string.apps_empty) } }
        } else {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.apps_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (matches.isEmpty()) {
                item { BodyNote(R.string.apps_no_match) }
            }
            items(matches, key = { it.packageName }) { app ->
                AppPanel(
                    app,
                    onEnabled = { enabled ->
                        onAction(UiAction.SetRule(app.packageName, app.rule.copy(enabled = enabled)))
                    },
                    onEdit = { editingPackage = app.packageName },
                )
            }
        }
    }
    val editing = state.apps.firstOrNull { it.packageName == editingPackage }
    if (editing != null) {
        key(editing.packageName) {
            RuleDialog(
                editing,
                onDismiss = { editingPackage = null },
                onSave = { rule ->
                    onAction(UiAction.SetRule(editing.packageName, rule))
                    editingPackage = null
                },
            )
        }
    }
}

@Composable
private fun AppPanel(app: ObservedAppUi, onEnabled: (Boolean) -> Unit, onEdit: () -> Unit) {
    Panel {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (app.label != app.packageName) BodyNote(app.packageName)
            }
            val accessibilityLabel = stringResource(R.string.rule_enable) + " · " + app.label
            Switch(
                checked = app.rule.enabled,
                onCheckedChange = onEnabled,
                modifier = Modifier.semantics { contentDescription = accessibilityLabel },
            )
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (app.rule.enabled) R.string.rule_on else R.string.rule_off),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = if (app.rule.enabled) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onEdit) { Text(stringResource(R.string.rule_edit)) }
        }
    }
}

@Composable
private fun RuleDialog(app: ObservedAppUi, onDismiss: () -> Unit, onSave: (RuleUi) -> Unit) {
    var enabled by remember { mutableStateOf(app.rule.enabled) }
    var silent by remember { mutableStateOf(app.rule.allowSilent) }
    var appName by remember { mutableStateOf(app.rule.includeAppName) }
    var channels by remember { mutableStateOf(app.rule.allowedChannels.linesForEdit()) }
    var allowedContacts by remember { mutableStateOf(app.rule.allowedContacts.linesForEdit()) }
    var blockedContacts by remember { mutableStateOf(app.rule.blockedContacts.linesForEdit()) }
    var allowedConversations by remember { mutableStateOf(app.rule.allowedConversations.linesForEdit()) }
    var blockedConversations by remember { mutableStateOf(app.rule.blockedConversations.linesForEdit()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SwitchRow(R.string.rule_enable, enabled, { enabled = it })
                SwitchRow(R.string.rule_silent, silent, { silent = it })
                SwitchRow(R.string.rule_app_name, appName, { appName = it })
                HorizontalDivider()
                BodyNote(R.string.rule_format)
                RuleField(R.string.rule_channels, channels, { channels = it })
                if (app.observedChannels.isNotEmpty()) {
                    BodyNote(R.string.rule_channel_hint)
                    BodyNote(app.observedChannels.sorted().joinToString(" · "))
                }
                RuleField(R.string.rule_allowed_contacts, allowedContacts, { allowedContacts = it })
                RuleField(R.string.rule_blocked_contacts, blockedContacts, { blockedContacts = it })
                RuleField(
                    R.string.rule_allowed_conversations, allowedConversations, { allowedConversations = it },
                )
                RuleField(
                    R.string.rule_blocked_conversations, blockedConversations, { blockedConversations = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    RuleUi(
                        enabled,
                        silent,
                        appName,
                        channels.rulesFromLines(),
                        allowedContacts.rulesFromLines(),
                        blockedContacts.rulesFromLines(),
                        allowedConversations.rulesFromLines(),
                        blockedConversations.rulesFromLines(),
                    ),
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RuleField(@StringRes label: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(stringResource(label)) },
        minLines = 1,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Set<String>.linesForEdit(): String = sorted().joinToString("\n")
private fun String.rulesFromLines(): Set<String> = lineSequence().map(String::trim)
    .filter(String::isNotEmpty).toSet()
