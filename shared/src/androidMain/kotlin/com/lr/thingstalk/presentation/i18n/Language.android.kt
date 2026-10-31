package com.lr.thingstalk.presentation.i18n

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.lr.thingstalk.domain.appearance.AppLanguage

@Composable internal actual fun systemLanguageTag(): String =
    LocalConfiguration.current.locales[0]?.toLanguageTag().orEmpty()

@Composable internal actual fun PlatformLanguage(language: AppLanguage) = Unit
