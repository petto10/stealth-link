package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stealthcompanion.R

@Composable
internal fun DevicePanel(state: StealthUiState, onAction: (UiAction) -> Unit) {
    Page(R.string.device_title, R.string.device_intro) {
        ConnectionHero(state)
        Panel {
            SectionTitle(R.string.mode_title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(CompanionMode.SIMULATOR, state.mode, R.string.mode_simulator, onAction)
                ModeChip(CompanionMode.CONTROLLER, state.mode, R.string.mode_controller, onAction)
            }
            if (state.mode == CompanionMode.CONTROLLER) {
                BodyNote(R.string.mode_controller_note)
            } else {
                BodyNote(R.string.mode_simulator_note)
            }
        }
        Panel {
            SectionTitle(R.string.permission_title)
            PermissionRow(
                R.string.permission_notifications,
                R.string.permission_notifications_note,
                state.notificationAccess,
                R.string.action_settings,
            ) { onAction(UiAction.OpenNotificationAccess) }
            if (state.mode == CompanionMode.CONTROLLER) {
                HorizontalDivider()
                PermissionRow(
                    R.string.permission_bluetooth,
                    R.string.permission_bluetooth_note,
                    state.bluetoothPermission,
                    R.string.action_allow,
                ) { onAction(UiAction.RequestBluetooth) }
            }
        }
        if (state.mode == CompanionMode.CONTROLLER) {
            ControllersPanel(state, onAction)
        }
        Panel {
            SwitchRow(R.string.pause_title, state.userPaused, {
                onAction(UiAction.SetPaused(it))
            }, R.string.pause_subtitle)
        }
        DiagnosticsPanel(state.diagnostics) { onAction(UiAction.ExportDiagnostics) }
    }
}

@Composable
private fun ModeChip(
    mode: CompanionMode,
    current: CompanionMode,
    @StringRes label: Int,
    onAction: (UiAction) -> Unit,
) {
    FilterChip(
        selected = mode == current,
        onClick = { onAction(UiAction.SetMode(mode)) },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun ConnectionHero(state: StealthUiState) {
    val ready = state.connection == ConnectionState.READY && !state.userPaused
    val mint = Color(0xffbdedca)
    val lavender = Color(0xffd7bbff)
    val status = when {
        state.userPaused -> R.string.status_paused
        state.connection == ConnectionState.IDLE -> R.string.status_idle
        state.connection == ConnectionState.CONNECTING -> R.string.status_connecting
        state.connection == ConnectionState.WAITING_FOR_SUBSCRIPTION -> R.string.status_waiting
        state.connection == ConnectionState.RETRYING -> R.string.status_retrying
        state.connection == ConnectionState.UNAVAILABLE -> R.string.status_unavailable
        state.mode == CompanionMode.SIMULATOR -> R.string.status_simulator
        else -> R.string.status_ready
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = Color(0xff20152f)) {
        Row(
            Modifier.background(
                Brush.linearGradient(listOf(Color(0xff21152f), Color(0xff432762))),
            ).padding(horizontal = 19.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    stringResource(
                        if (state.mode == CompanionMode.SIMULATOR) R.string.status_badge_simulator
                        else R.string.status_badge_controller,
                    ),
                    color = lavender,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.35.sp,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(if (ready) mint else lavender)
                    Text(
                        stringResource(status),
                        color = Color(0xfffaf1ff),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.45).sp,
                    )
                }
            }
            ConnectionGraphic(if (ready) mint else lavender)
        }
    }
}

@Composable
private fun ConnectionGraphic(accent: Color) {
    Canvas(Modifier.size(width = 98.dp, height = 82.dp)) {
        val unit = size.width / 100f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        val container = Size(78f * unit, 56f * unit)
        drawRoundRect(
            accent.copy(alpha = 0.09f), point(17f, 14f), container, CornerRadius(14f * unit),
        )
        drawRoundRect(
            accent.copy(alpha = 0.55f), point(12f, 10f), container, CornerRadius(13f * unit),
            style = Stroke(1.5f * unit),
        )
        drawRoundRect(
            accent.copy(alpha = 0.13f), point(18f, 16f), Size(66f * unit, 44f * unit),
            CornerRadius(8f * unit),
        )
        drawPath(
            Path().apply {
                moveTo(24f * unit, 39f * unit)
                lineTo(39f * unit, 39f * unit)
                lineTo(45f * unit, 29f * unit)
                lineTo(53f * unit, 47f * unit)
                lineTo(60f * unit, 35f * unit)
                lineTo(76f * unit, 35f * unit)
            },
            accent,
            style = Stroke(2.2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(accent.copy(alpha = 0.5f), point(43f, 74f), point(59f, 74f), 1.5f * unit, StrokeCap.Round)
    }
}

@Composable
private fun PermissionRow(
    @StringRes title: Int,
    @StringRes note: Int,
    granted: Boolean,
    @StringRes action: Int,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            BodyNote(note)
        }
        if (granted) {
            Text(
                stringResource(R.string.permission_granted),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            TextButton(onClick = onClick) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun ControllersPanel(state: StealthUiState, onAction: (UiAction) -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(R.string.controllers_title) }
            TextButton(
                enabled = state.bluetoothPermission,
                onClick = { onAction(UiAction.RefreshDevices) },
            ) { Text(stringResource(R.string.controllers_refresh)) }
        }
        val configurationDevices = state.devices.filter { it.role != ControllerRole.SOCIAL }
        if (configurationDevices.isEmpty()) {
            BodyNote(R.string.controllers_empty)
        }
        configurationDevices.forEach { device ->
            ChoiceRow(device.name, device.id == state.selectedDeviceId) {
                onAction(UiAction.SelectDevice(device.id))
            }
        }
        val socialDevices = state.devices.filter { it.role != ControllerRole.CONFIGURATION }
        if (socialDevices.isNotEmpty()) {
            HorizontalDivider()
            SectionTitle(R.string.controllers_social_title)
            BodyNote(R.string.controllers_social_note)
            ChoiceRow(stringResource(R.string.controllers_same), state.selectedSocialDeviceId == null) {
                onAction(UiAction.SelectSocialDevice(null))
            }
            socialDevices.forEach { device ->
                ChoiceRow(device.name, device.id == state.selectedSocialDeviceId) {
                    onAction(UiAction.SelectSocialDevice(device.id))
                }
            }
        }
        val hasDevice = state.selectedDeviceId != null || state.selectedSocialDeviceId != null
        if (state.isRunning) {
            OutlinedButton(onClick = { onAction(UiAction.StopCompanion) }) {
                Text(stringResource(R.string.action_disconnect))
            }
        } else {
            Button(
                enabled = state.bluetoothPermission && hasDevice,
                onClick = { onAction(UiAction.StartCompanion) },
            ) { Text(stringResource(R.string.action_connect)) }
        }
    }
}

@Composable
private fun DiagnosticsPanel(diagnostics: DiagnosticsUi, export: () -> Unit) {
    Panel {
        SectionTitle(R.string.diagnostic_title)
        BodyNote(R.string.diagnostic_subtitle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric(R.string.metric_queued, diagnostics.queued, Modifier.weight(1f))
            Metric(R.string.metric_sent, diagnostics.sent, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric(R.string.metric_dropped, diagnostics.dropped, Modifier.weight(1f))
            Metric(R.string.metric_reconnects, diagnostics.reconnects, Modifier.weight(1f))
        }
        diagnostics.mtu?.let { BodyNote(stringResource(R.string.diagnostic_mtu, it)) }
        OutlinedButton(onClick = export) { Text(stringResource(R.string.diagnostic_export)) }
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
