package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stealthcompanion.R

@Composable
internal fun DiagnosticsPanel(state: StealthUiState, onAction: (UiAction) -> Unit) {
    val diagnostics = state.diagnostics
    Page(R.string.diagnostic_title, R.string.diagnostic_subtitle) {
        Panel {
            SectionTitle(R.string.diagnostic_relay_activity)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric(R.string.metric_queued, diagnostics.queued, Modifier.weight(1f))
                Metric(R.string.metric_sent, diagnostics.sent, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric(R.string.metric_dropped, diagnostics.dropped, Modifier.weight(1f))
                Metric(R.string.metric_reconnects, diagnostics.reconnects, Modifier.weight(1f))
            }
        }
        Panel {
            SectionTitle(R.string.diagnostic_transport)
            TransportReadout(diagnostics, state.mode)
            OutlinedButton(onClick = { onAction(UiAction.ExportDiagnostics) }) {
                Text(stringResource(R.string.diagnostic_export))
            }
        }
    }
}

@Composable
private fun Metric(@StringRes label: Int, value: Int, modifier: Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
