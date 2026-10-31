package com.lr.thingstalk.presentation.i18n

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lr.thingstalk.domain.appearance.AppLanguage
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

@Composable internal actual fun systemLanguageTag(): String {
    fun current() = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
    var tag by remember { mutableStateOf(current()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tag = current()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return tag
}

@Composable internal actual fun PlatformLanguage(language: AppLanguage) = Unit
