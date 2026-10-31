package com.lr.thingstalk.presentation.theme

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import com.lr.thingstalk.domain.appearance.ThemeMode

@Composable internal actual fun PlatformAppearance(mode: ThemeMode, dark: Boolean, background: androidx.compose.ui.graphics.Color) {
    val view = LocalView.current
    LaunchedEffect(view, dark) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().firstOrNull()
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, 0xFF202124.toInt())
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
