package com.lr.thingstalk.presentation.theme

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import com.lr.thingstalk.domain.appearance.ThemeMode
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

@Composable internal actual fun PlatformAppearance(mode: ThemeMode, dark: Boolean, background: androidx.compose.ui.graphics.Color) {
    SideEffect {
        val backgroundHex = "#" + background.toArgb().toUInt().toString(16).padStart(8, '0').takeLast(6)
        (document.documentElement as? HTMLElement)?.style?.setProperty("color-scheme", if (dark) "dark" else "light")
        (document.documentElement as? HTMLElement)?.style?.setProperty("background-color", backgroundHex)
        document.body?.style?.setProperty("background-color", backgroundHex)
        document.querySelector("meta[name='theme-color']")?.setAttribute("content", backgroundHex)
    }
}
