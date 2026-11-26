package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stealthcompanion.R

@Composable
internal fun ConfigurationPanel(state: StealthUiState, onAction: (UiAction) -> Unit) {
    val configuration = state.configuration
    val readStatus = when {
        state.mode == CompanionMode.SIMULATOR -> R.string.configuration_status_simulator
        state.notificationsOnly -> R.string.configuration_status_notifications_only
        !configuration.connected -> R.string.configuration_status_disconnected
        configuration.reading -> R.string.configuration_status_reading
        configuration.complete -> R.string.configuration_status_complete
        configuration.powerMode != null || state.profiles.slotNames.isNotEmpty() ->
            R.string.configuration_status_partial
        else -> R.string.configuration_status_ready
    }
    Page(R.string.nav_configuration, R.string.configuration_intro) {
        Panel {
            SectionTitle(R.string.configuration_read_status)
            BodyNote(readStatus)
            OutlinedButton(
                enabled = state.mode == CompanionMode.CONTROLLER && !state.notificationsOnly &&
                    configuration.connected && !configuration.reading,
                onClick = { onAction(UiAction.ReadSettings) },
            ) { Text(stringResource(R.string.profile_read)) }
        }
        Panel {
            SectionTitle(R.string.configuration_device)
            ConfigurationValue(
                R.string.configuration_battery,
                configuration.batteryPercent?.takeIf { it in 0..100 }
                    ?.let { stringResource(R.string.configuration_percent, it) },
            )
            ConfigurationValue(R.string.configuration_firmware, configuration.firmwareRevision)
            ConfigurationValue(R.string.configuration_hardware, configuration.hardwareRevision)
            ConfigurationValue(R.string.configuration_software, configuration.softwareRevision)
        }
        Panel {
            SectionTitle(R.string.profile_system)
            ConfigurationValue(
                R.string.configuration_active_slot,
                state.profiles.activeSlot?.takeIf { it in 1..10 }?.toString(),
            )
            ConfigurationValue(R.string.configuration_power_mode, powerModeLabel(configuration.powerMode))
            ConfigurationValue(R.string.configuration_display_brightness, configuration.displayBrightness?.toString())
            ConfigurationValue(R.string.configuration_led_brightness, configuration.ledBrightness?.toString())
            ConfigurationValue(R.string.configuration_screen_timeout, timeoutLabel(configuration.screenTimeoutMinutes))
            ConfigurationValue(R.string.configuration_sleep_timeout, timeoutLabel(configuration.sleepTimeoutMinutes))
            ConfigurationValue(R.string.configuration_charge_brightness, configuration.chargeLedBrightness?.toString())
        }
        Panel {
            SectionTitle(R.string.configuration_other)
            BodyNote(R.string.configuration_other_note)
            ConfigurationValue(R.string.configuration_mapping, null)
            ConfigurationValue(R.string.configuration_sticks, null)
            ConfigurationValue(R.string.configuration_triggers, null)
            ConfigurationValue(R.string.configuration_audio, null)
            ConfigurationValue(R.string.configuration_vibration, null)
            ConfigurationValue(R.string.configuration_rgb, null)
        }
    }
}

@Composable
private fun powerModeLabel(value: Int?): String? = when (value) {
    0 -> stringResource(R.string.configuration_power_full)
    1 -> stringResource(R.string.configuration_power_balanced)
    2 -> stringResource(R.string.configuration_power_eco)
    3 -> stringResource(R.string.configuration_power_custom)
    null -> null
    else -> stringResource(R.string.configuration_raw_code, value)
}

@Composable
private fun timeoutLabel(minutes: Int?): String? = minutes?.let {
    stringResource(R.string.configuration_minutes, it)
}

@Composable
private fun ConfigurationValue(@StringRes label: Int, value: String?) {
    val available = value?.takeIf { it.isNotBlank() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.weight(1.35f), style = MaterialTheme.typography.bodyMedium)
        Text(
            available ?: stringResource(R.string.configuration_undefined),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (available == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}
