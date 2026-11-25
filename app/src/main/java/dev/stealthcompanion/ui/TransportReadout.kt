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
        DiagnosticValue(socialMtuLabel, diagnostics.socialMtu ?: diagnostics.mtu)
        if (mode == CompanionMode.CONTROLLER) {
            DiagnosticValue(R.string.diagnostic_config_mtu, diagnostics.configurationMtu)
        }
        DiagnosticValue(R.string.diagnostic_requests, diagnostics.attributeRequests)
        DiagnosticValue(R.string.diagnostic_fragments, diagnostics.fragmentsSent)
    }
    AttributeReadout(diagnostics)
}

@Composable
private fun DiagnosticValue(@StringRes label: Int, value: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(label),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(value?.toString() ?: "—", style = MaterialTheme.typography.labelLarge)
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
            Text(
                stringResource(R.string.diagnostic_attribute),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                stringResource(R.string.diagnostic_attribute_requested),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
            )
            Text(
                stringResource(R.string.diagnostic_attribute_sent),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
            )
        }
        AttributeLengthsRow(
            R.string.synthetic_title_field, diagnostics.lastRequestedTitleBytes, diagnostics.lastSentTitleBytes,
        )
        AttributeLengthsRow(
            R.string.synthetic_body_field, diagnostics.lastRequestedBodyBytes, diagnostics.lastSentBodyBytes,
        )
    }
}

@Composable
private fun AttributeLengthsRow(@StringRes label: Int, requested: Int?, sent: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            requested?.toString() ?: "—",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
        Text(
            sent?.toString() ?: "—",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}
