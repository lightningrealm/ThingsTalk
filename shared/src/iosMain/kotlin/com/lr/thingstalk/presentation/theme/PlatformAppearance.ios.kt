package com.lr.thingstalk.presentation.theme

import androidx.compose.runtime.*
import androidx.compose.ui.uikit.LocalUIViewController
import com.lr.thingstalk.domain.appearance.ThemeMode
import platform.UIKit.*

@Composable internal actual fun PlatformAppearance(mode: ThemeMode, dark: Boolean, background: androidx.compose.ui.graphics.Color) {
    val controller = LocalUIViewController.current
    LaunchedEffect(controller, mode, dark) {
        val style = when (mode) {
            ThemeMode.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            ThemeMode.LIGHT -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ThemeMode.DARK -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
        }
        controller.overrideUserInterfaceStyle = style
        controller.view.window?.overrideUserInterfaceStyle = style
        controller.setNeedsStatusBarAppearanceUpdate()
    }
}
