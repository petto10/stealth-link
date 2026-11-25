package dev.stealthcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stealthcompanion.R

private enum class Destination(@StringRes val label: Int, val glyph: Glyph) {
    DEVICE(R.string.nav_device, Glyph.DEVICE),
    NOTIFICATIONS(R.string.nav_notifications, Glyph.NOTIFICATIONS),
    LAB(R.string.nav_lab, Glyph.LAB),
    PROFILES(R.string.nav_profiles, Glyph.PROFILES),
}

private val DayColors = lightColorScheme(
    primary = Color(0xff654390),
    onPrimary = Color.White,
    primaryContainer = Color(0xffeee2ff),
    onPrimaryContainer = Color(0xff281541),
    secondary = Color(0xff3d655b),
    onSecondary = Color.White,
    secondaryContainer = Color(0xffd3e8dd),
    onSecondaryContainer = Color(0xff173a31),
    background = Color(0xfff8f5fb),
    onBackground = Color(0xff211a2a),
    surface = Color(0xfffffcff),
    onSurface = Color(0xff211a2a),
    surfaceVariant = Color(0xffeee8f3),
    onSurfaceVariant = Color(0xff625a6c),
    outline = Color(0xff81778b),
    outlineVariant = Color(0xffdfd6e7),
)

private val NightColors = darkColorScheme(
    primary = Color(0xffd3b8ff),
    onPrimary = Color(0xff351d57),
    primaryContainer = Color(0xff4d3372),
    onPrimaryContainer = Color(0xfff2e5ff),
    secondary = Color(0xffa8d4c3),
    onSecondary = Color(0xff19392f),
    secondaryContainer = Color(0xff2f5146),
    onSecondaryContainer = Color(0xffc6ebd9),
    background = Color(0xff16121c),
    onBackground = Color(0xffeee5fa),
    surface = Color(0xff201a29),
    onSurface = Color(0xffeee5fa),
    surfaceVariant = Color(0xff2e2639),
    onSurfaceVariant = Color(0xffc9bfd6),
    outline = Color(0xff9589a3),
    outlineVariant = Color(0xff473e52),
)

@Composable
fun StealthApp(state: StealthUiState, onAction: (UiAction) -> Unit) {
    val dark = when (state.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    var page by rememberSaveable { mutableStateOf(Destination.DEVICE) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val feedbackText = state.feedback?.let { stringResource(it.label()) }
    LaunchedEffect(feedbackText) {
        if (feedbackText != null) {
            snackbar.showSnackbar(feedbackText, withDismissAction = true)
            onAction(UiAction.ClearFeedback)
        }
    }
    MaterialTheme(colorScheme = if (dark) NightColors else DayColors) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .statusBarsPadding()
                            .heightIn(min = 60.dp)
                            .padding(start = 20.dp, end = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Symbol(Glyph.SIGNAL, MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.brand),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.6).sp,
                            )
                        }
                        TextButton(onClick = { showAppearance = true }) {
                            Text(stringResource(R.string.appearance))
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == page,
                            onClick = { page = item },
                            icon = {
                                val color = if (item == page) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                                Symbol(item.glyph, color)
                            },
                            label = {
                                Text(
                                    stringResource(item.label),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                when (page) {
                    Destination.DEVICE -> DevicePanel(state, onAction)
                    Destination.NOTIFICATIONS -> NotificationPanel(state, onAction)
                    Destination.LAB -> LabPanel(state, onAction)
                    Destination.PROFILES -> ProfilesPanel(state, onAction)
                }
            }
        }

        if (showAppearance) {
            AppearanceDialog(state, onAction) { showAppearance = false }
        }
    }
}

@Composable
internal fun Page(
    @StringRes title: Int,
    @StringRes description: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 680.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeading(title, description)
        content()
    }
}

@Composable
internal fun PageHeading(@StringRes title: Int, @StringRes description: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1.1).sp,
        )
        BodyNote(description)
    }
}

@Composable
private fun AppearanceDialog(state: StealthUiState, onAction: (UiAction) -> Unit, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.appearance)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle(R.string.appearance_theme)
                ThemeMode.entries.forEach { theme ->
                    val label = when (theme) {
                        ThemeMode.SYSTEM -> R.string.appearance_system
                        ThemeMode.LIGHT -> R.string.appearance_light
                        ThemeMode.DARK -> R.string.appearance_dark
                    }
                    ChoiceRow(stringResource(label), theme == state.theme) { onAction(UiAction.SetTheme(theme)) }
                }
                SectionTitle(R.string.appearance_language)
                LanguageMode.entries.forEach { language ->
                    val label = when (language) {
                        LanguageMode.SYSTEM -> R.string.appearance_system
                        LanguageMode.ITALIAN -> R.string.appearance_italian
                        LanguageMode.ENGLISH -> R.string.appearance_english
                    }
                    ChoiceRow(stringResource(label), language == state.language) {
                        onAction(UiAction.SetLanguage(language))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_close)) } },
    )
}

@StringRes
private fun UiFeedback.label(): Int = when (this) {
    UiFeedback.TEST_QUEUED -> R.string.feedback_test_ok
    UiFeedback.TEST_UNAVAILABLE -> R.string.feedback_test_unavailable
    UiFeedback.PROBE_QUEUED -> R.string.feedback_probe_ok
    UiFeedback.PROBE_MANUAL -> R.string.feedback_probe_manual
    UiFeedback.PROBE_UNAVAILABLE -> R.string.feedback_probe_unavailable
    UiFeedback.SETTINGS_REQUESTED -> R.string.feedback_read_ok
    UiFeedback.SETTINGS_UNAVAILABLE -> R.string.feedback_read_unavailable
    UiFeedback.PROFILES_IMPORTED -> R.string.feedback_profiles_imported
    UiFeedback.PROFILES_EXPORTED -> R.string.feedback_profiles_exported
    UiFeedback.DIAGNOSTICS_EXPORTED -> R.string.feedback_diagnostics_exported
    UiFeedback.FILE_ERROR -> R.string.feedback_file_error
}
