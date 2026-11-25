package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class Glyph { SIGNAL, DEVICE, NOTIFICATIONS, LAB, PROFILES }

@Composable
internal fun Symbol(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(23.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (glyph) {
                Glyph.SIGNAL -> {
                    drawRoundRect(color, Offset(2.5f, 2.5f), Size(19f, 19f), CornerRadius(6f), style = stroke)
                    drawPath(
                        Path().apply {
                            moveTo(6.5f, 12.6f)
                            lineTo(9.7f, 12.6f)
                            lineTo(11.4f, 8f)
                            lineTo(13.6f, 16f)
                            lineTo(15f, 11.6f)
                            lineTo(17.5f, 11.6f)
                        },
                        color,
                        style = stroke,
                    )
                }
                Glyph.DEVICE -> {
                    drawRoundRect(color, Offset(2.5f, 5f), Size(19f, 14f), CornerRadius(5f), style = stroke)
                    drawLine(color, Offset(6.5f, 12f), Offset(11f, 12f), 1.75f, StrokeCap.Round)
                    drawLine(color, Offset(8.75f, 9.75f), Offset(8.75f, 14.25f), 1.75f, StrokeCap.Round)
                    drawCircle(color, 1.2f, Offset(15f, 10.5f))
                    drawCircle(color, 1.2f, Offset(17.7f, 13.5f))
                }
                Glyph.NOTIFICATIONS -> {
                    drawPath(
                        Path().apply {
                            moveTo(3.5f, 4.5f)
                            lineTo(20.5f, 4.5f)
                            lineTo(20.5f, 16.5f)
                            lineTo(11f, 16.5f)
                            lineTo(6.5f, 20f)
                            lineTo(6.5f, 16.5f)
                            lineTo(3.5f, 16.5f)
                            close()
                        },
                        color,
                        style = stroke,
                    )
                    drawLine(color, Offset(7.5f, 9f), Offset(16.5f, 9f), 1.75f, StrokeCap.Round)
                    drawLine(color, Offset(7.5f, 12.2f), Offset(13.5f, 12.2f), 1.75f, StrokeCap.Round)
                }
                Glyph.LAB -> {
                    drawRoundRect(color, Offset(2.5f, 4f), Size(19f, 13.2f), CornerRadius(3f), style = stroke)
                    drawLine(color, Offset(9f, 20.5f), Offset(15f, 20.5f), 1.75f, StrokeCap.Round)
                    drawLine(color, Offset(12f, 17.5f), Offset(12f, 19.8f), 1.75f, StrokeCap.Round)
                    drawPath(
                        Path().apply {
                            moveTo(6f, 11f)
                            lineTo(9.2f, 11f)
                            lineTo(11.2f, 7.6f)
                            lineTo(13f, 13.8f)
                            lineTo(15f, 10.3f)
                            lineTo(18f, 10.3f)
                        },
                        color,
                        style = stroke,
                    )
                }
                Glyph.PROFILES -> {
                    drawRoundRect(color, Offset(3f, 7f), Size(18f, 13f), CornerRadius(2.5f), style = stroke)
                    drawLine(color, Offset(6f, 4.2f), Offset(18f, 4.2f), 1.75f, StrokeCap.Round)
                    drawLine(color, Offset(7.5f, 12f), Offset(16.5f, 12f), 1.75f, StrokeCap.Round)
                    drawLine(color, Offset(7.5f, 15.5f), Offset(13.5f, 15.5f), 1.75f, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun BodyNote(@StringRes label: Int) {
    BodyNote(stringResource(label))
}

@Composable
internal fun BodyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SwitchRow(
    @StringRes title: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    @StringRes note: Int? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            note?.let { BodyNote(it) }
        }
        val accessibilityLabel = stringResource(title)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = accessibilityLabel },
        )
    }
}

@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}
