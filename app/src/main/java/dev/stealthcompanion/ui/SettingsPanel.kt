package dev.stealthcompanion.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.stealthcompanion.R

@Composable
internal fun SettingsPanel(state: StealthUiState, onAction: (UiAction) -> Unit) {
    Page(R.string.nav_settings, R.string.settings_intro) {
        Panel {
            SectionTitle(R.string.appearance_theme)
            ThemeMode.entries.forEach { theme ->
                val label = when (theme) {
                    ThemeMode.SYSTEM -> R.string.appearance_system
                    ThemeMode.LIGHT -> R.string.appearance_light
                    ThemeMode.DARK -> R.string.appearance_dark
                }
                ChoiceRow(stringResource(label), theme == state.theme) { onAction(UiAction.SetTheme(theme)) }
            }
        }
        Panel {
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
    }
}
