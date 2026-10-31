package com.lr.thingstalk.presentation.i18n

import androidx.compose.runtime.*
import com.lr.thingstalk.domain.appearance.AppLanguage
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event

@Composable internal actual fun systemLanguageTag(): String {
    var tag by remember { mutableStateOf(window.navigator.language) }
    DisposableEffect(Unit) {
        val changed: (Event) -> Unit = { tag = window.navigator.language }
        window.addEventListener("languagechange", changed)
        onDispose { window.removeEventListener("languagechange", changed) }
    }
    return tag
}

@Composable internal actual fun PlatformLanguage(language: AppLanguage) {
    SideEffect {
        document.documentElement?.setAttribute("lang", language.tag)
        document.title = if (language == AppLanguage.CHINESE) "ThingsTalk · 设备工作台" else "ThingsTalk · Device workspace"
        document.querySelector("meta[name='description']")?.setAttribute("content",
            if (language == AppLanguage.CHINESE) "ThingsTalk MQTT 工作台" else "ThingsTalk MQTT workspace")
    }
}
