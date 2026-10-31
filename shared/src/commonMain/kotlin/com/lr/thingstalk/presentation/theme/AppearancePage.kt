package com.lr.thingstalk.presentation.theme

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.appearance.*
import com.lr.thingstalk.presentation.components.*

@Composable
fun AppearancePage(viewModel: AppearanceViewModel) {
    val settings by viewModel.appearance.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val error by viewModel.error.collectAsState()
    Page("APP SETTINGS", tr(AppStrings.message_app_settings_0c79c2), tr(AppStrings.applied_immediately_and_saved_on_this_device_78fa2e)) {
        Panel {
            Text(tr(AppStrings.language_title), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguagePreference.entries.forEach { preference ->
                    FilterChip(selected = settings.language == preference, enabled = !saving,
                        onClick = { viewModel.update(settings.copy(language = preference)) },
                        label = { Text(tr(when (preference) {
                            LanguagePreference.SYSTEM -> AppStrings.language_system
                            LanguagePreference.SIMPLIFIED_CHINESE -> AppStrings.language_chinese
                            LanguagePreference.ENGLISH -> AppStrings.language_english
                        })) })
                }
            }
            Text(tr(AppStrings.language_hint), color = Muted)
        }
        Panel {
            Text(tr(AppStrings.display_mode_4a8bb8), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = settings.mode == mode, enabled = !saving,
                        onClick = { viewModel.update(settings.copy(mode = mode)) },
                        label = { Text(when (mode) { ThemeMode.SYSTEM -> tr(AppStrings.follow_system_f4bbd9); ThemeMode.LIGHT -> tr(AppStrings.light_80ec9e); ThemeMode.DARK -> tr(AppStrings.dark_30b2c9) }) })
                }
            }
            Text(tr(AppStrings.follow_system_responds_automatically_to_the_device_s_li_e0e87d), color = Muted)
        }
        Panel {
            Text(tr(AppStrings.accent_color_90b878), style = MaterialTheme.typography.titleMedium)
            Text(tr(AppStrings.subtle_backgrounds_with_accents_on_cards_borders_and_co_f25cf5), color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemePalette.entries.forEach { palette ->
                    FilterChip(selected = settings.palette == palette, enabled = !saving,
                        onClick = { viewModel.update(settings.copy(palette = palette)) },
                        leadingIcon = { Box(Modifier.size(14.dp).background(themeColors(palette, false).primary, CircleShape)) },
                        label = { Text(when (palette) {
                            ThemePalette.SAGE -> tr(AppStrings.sage_25f818); ThemePalette.BLUE -> tr(AppStrings.mist_blue_5d0a42); ThemePalette.VIOLET -> tr(AppStrings.violet_41dc5f)
                            ThemePalette.CLAY -> tr(AppStrings.clay_df68f7); ThemePalette.GRAPHITE -> tr(AppStrings.graphite_94fb25)
                        }) })
                }
            }
        }
        error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        if (saving) Text(tr(AppStrings.saving_appearance_56c444), color = Muted)
        Section(tr(AppStrings.preview_67d03d))
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(tr(AppStrings.desk_temperature_d5df74), style = MaterialTheme.typography.titleMedium); Text(tr(AppStrings.appearance_preview_only_5629a3), color = Muted) }
                Tag(tr(AppStrings.online_0373ff))
            }
            Text("24.6 ℃", style = MaterialTheme.typography.headlineLarge)
            HorizontalDivider(color = Line)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {}) { Text(tr(AppStrings.primary_button_a717db)) }
                OutlinedButton(onClick = {}) { Text(tr(AppStrings.secondary_button_b776a0)) }
            }
        }
        Text(tr(AppStrings.these_settings_only_affect_display_they_do_not_change_d_3ff3ef), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
