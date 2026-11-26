package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stealthcompanion.R

@Composable
internal fun TransportReadout(diagnostics: DiagnosticsUi, mode: CompanionMode) {
    val socialMtuLabel = when {
        mode == CompanionMode.SIMULATOR -> R.string.diagnostic_sim_mtu
        diagnostics.socialMtuKnown -> R.string.diagnostic_social_mtu
        else -> R.string.diagnostic_default_mtu
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DiagnosticNumber(socialMtuLabel, diagnostics.socialMtu ?: diagnostics.mtu)
        if (mode == CompanionMode.CONTROLLER) {
            DiagnosticNumber(R.string.diagnostic_config_mtu, diagnostics.configurationMtu)
            DiagnosticValue(
                R.string.diagnostic_notification_subscription,
                subscriptionLabel(diagnostics.notificationSourceSubscribed),
            )
            DiagnosticValue(
                R.string.diagnostic_data_subscription,
                subscriptionLabel(diagnostics.dataSourceSubscribed),
            )
        }
        DiagnosticNumber(R.string.diagnostic_requests, diagnostics.attributeRequests)
        DiagnosticNumber(R.string.diagnostic_fragments, diagnostics.fragmentsSent)
    }
    if (listOf(diagnostics.requestedBytesTotal, diagnostics.declaredBytesTotal, diagnostics.sentBytesTotal)
            .any { it != null }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.diagnostic_cumulative_lengths),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            DiagnosticNumber(R.string.diagnostic_cumulative_requested, diagnostics.requestedBytesTotal)
            DiagnosticNumber(R.string.diagnostic_cumulative_declared, diagnostics.declaredBytesTotal)
            DiagnosticNumber(R.string.diagnostic_cumulative_sent, diagnostics.sentBytesTotal)
        }
    }
    AttributeReadout(diagnostics)
}

@Composable
private fun subscriptionLabel(subscribed: Boolean?): String? = when (subscribed) {
    true -> stringResource(R.string.diagnostic_subscription_active)
    false -> stringResource(R.string.diagnostic_subscription_inactive)
    null -> null
}

@Composable
private fun DiagnosticNumber(@StringRes label: Int, value: Number?) {
    DiagnosticValue(label, value?.toString())
}

@Composable
private fun DiagnosticValue(@StringRes label: Int, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(label),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(value ?: "—", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun AttributeReadout(diagnostics: DiagnosticsUi) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.diagnostic_attribute_lengths),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cols = listOf(
                R.string.diagnostic_attribute,
                R.string.diagnostic_attribute_requested,
                R.string.diagnostic_attribute_declared,
                R.string.diagnostic_attribute_sent,
            )
            cols.forEachIndexed { index, column ->
                Text(
                    stringResource(column),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                )
            }
        }
        AttributeLengthsRow(
            R.string.synthetic_title_field, diagnostics.lastRequestedTitleBytes,
            diagnostics.lastDeclaredTitleBytes, diagnostics.lastSentTitleBytes,
        )
        AttributeLengthsRow(
            R.string.synthetic_body_field, diagnostics.lastRequestedBodyBytes,
            diagnostics.lastDeclaredBodyBytes, diagnostics.lastSentBodyBytes,
        )
    }
}

@Composable
private fun AttributeLengthsRow(@StringRes label: Int, requested: Int?, declared: Int?, sent: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        listOf(requested, declared, sent).forEach { value ->
            Text(
                value?.toString() ?: "—",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
            )
        }
    }
}
