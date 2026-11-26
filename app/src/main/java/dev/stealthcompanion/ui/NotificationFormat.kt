package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.stealthcompanion.R

private val fragmentDelays = listOf(0, 10, 25, 50, 100, 300)
private val messageByteLimits = listOf(64, 96, 112, 120, 128, 255)

@Composable
internal fun NotificationFormat(state: StealthUiState, onAction: (UiAction) -> Unit) {
    Panel {
        SectionTitle(R.string.controller_format)
        SwitchRow(R.string.extended_text, state.extendedText, {
            onAction(UiAction.SetExtendedText(it))
        }, R.string.extended_text_note)
        HorizontalDivider()
        FormatChoices(R.string.probe_fragment_delay, fragmentDelays, state.fragmentDelayMs) {
            onAction(UiAction.SetFragmentDelay(it))
        }
        FormatChoices(R.string.notification_body_byte_limit, messageByteLimits, state.bodyByteLimit) {
            onAction(UiAction.SetBodyByteLimit(it))
        }
        HorizontalDivider()
        SwitchRow(R.string.notification_paginate, state.paginateNotifications, {
            onAction(UiAction.SetPaginateNotifications(it))
        }, R.string.notification_paginate_note)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatChoices(@StringRes title: Int, values: List<Int>, selected: Int, choose: (Int) -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { choose(value) },
                label = { Text(value.toString()) },
            )
        }
    }
}
