package dev.stealthcompanion.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
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

private enum class Destination(@StringRes val label: Int, val glyph: Glyph, val primary: Boolean = false) {
    HOME(R.string.nav_device, Glyph.DEVICE, primary = true),
    NOTIFICATIONS(R.string.nav_notifications, Glyph.NOTIFICATIONS, primary = true),
    PROFILES(R.string.nav_profiles, Glyph.PROFILES, primary = true),
    CONFIGURATION(R.string.nav_configuration, Glyph.CONFIGURATION),
    LAB(R.string.nav_lab, Glyph.LAB),
    SETTINGS(R.string.nav_settings, Glyph.SETTINGS),
    DIAGNOSTICS(R.string.nav_diagnostics, Glyph.DIAGNOSTICS),
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
    var page by rememberSaveable { mutableStateOf(Destination.HOME) }
    var lastPrimary by rememberSaveable { mutableStateOf(Destination.HOME) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !page.primary && !showMore) { page = lastPrimary }
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
            topBar = { BrandBar() },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Destination.entries.filter { it.primary }.forEach { item ->
                        NavigationBarItem(
                            selected = item == page,
                            onClick = {
                                page = item
                                lastPrimary = item
                            },
                            icon = { NavigationSymbol(item.glyph, item == page) },
                            label = { NavigationLabel(item.label) },
                            colors = colors,
                        )
                    }
                    NavigationBarItem(
                        selected = !page.primary,
                        onClick = { showMore = true },
                        icon = { NavigationSymbol(Glyph.MORE, !page.primary) },
                        label = { NavigationLabel(R.string.nav_more) },
                        colors = colors,
                    )
                }
            },
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                when (page) {
                    Destination.HOME -> DevicePanel(state, onAction)
                    Destination.NOTIFICATIONS -> NotificationPanel(state, onAction)
                    Destination.PROFILES -> ProfilesPanel(state, onAction)
                    Destination.CONFIGURATION -> ConfigurationPanel(state, onAction)
                    Destination.LAB -> LabPanel(state, onAction)
                    Destination.SETTINGS -> SettingsPanel(state, onAction)
                    Destination.DIAGNOSTICS -> DiagnosticsPanel(state, onAction)
                }
            }
        }

        if (showMore) {
            MoreDestinations(
                page,
                close = { showMore = false },
                open = { destination ->
                    page = destination
                    showMore = false
                },
            )
        }
    }
}

@Composable
private fun BrandBar() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 60.dp)
                .padding(horizontal = 20.dp),
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
    }
}

@Composable
private fun NavigationSymbol(glyph: Glyph, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Symbol(glyph, color)
}

@Composable
private fun NavigationLabel(@StringRes label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreDestinations(page: Destination, close: () -> Unit, open: (Destination) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Destination.entries.filterNot { it.primary }.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(stringResource(item.label)) },
                    selected = item == page,
                    onClick = { open(item) },
                    icon = { NavigationSymbol(item.glyph, item == page) },
                )
            }
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
