package com.lr.thingstalk.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.lr.thingstalk.presentation.components.Glyph
import com.lr.thingstalk.presentation.components.LocalCompactHeight
import com.lr.thingstalk.presentation.components.Tag
import com.lr.thingstalk.presentation.theme.*

internal data class AppDestination(val screen: Screen, val label: String, val icon: String)

/** The viewport keeps its full height; each scrolling page owns its bottom safe inset. */
@Composable
internal fun AdaptiveAppFrame(
    badge: String,
    destinations: List<AppDestination> = emptyList(),
    selected: Screen = Screen.MQTT,
    showBottomNavigation: Boolean = false,
    back: (() -> Unit)? = null,
    navigate: (Screen) -> Unit = {},
    snackbar: SnackbarHostState? = null,
    contentMaxWidth: Dp = 800.dp,
    content: @Composable (usesRail: Boolean) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = Paper) {
        BoxWithConstraints(Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            // Decide from the full window, before the keyboard changes the content height.
            val compactHeight = maxHeight < 480.dp
            val usesRail = maxWidth >= 600.dp && destinations.size > 1
            CompositionLocalProvider(LocalCompactHeight provides compactHeight) {
                Row(Modifier.fillMaxSize().imePadding()) {
                    if (usesRail) {
                        Column(
                            Modifier.width(80.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 0.dp else 12.dp),
                        ) {
                            destinations.forEach { item ->
                                NavigationRailItem(
                                    selected = selected == item.screen,
                                    onClick = { navigate(item.screen) },
                                    icon = { Glyph(item.icon, Modifier.size(22.dp), if (selected == item.screen) Accent else Muted) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                        VerticalDivider(color = Line)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Row(
                                Modifier.widthIn(max = contentMaxWidth).fillMaxWidth().heightIn(min = if (compactHeight) 48.dp else 56.dp).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (back != null) TextButton(onClick = back) { Text("‹ 返回") }
                                else Text("ThinsTalk", Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.weight(1f))
                                Tag(badge)
                            }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.TopCenter) {
                                content(usesRail)
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = showBottomNavigation && !usesRail,
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    enter = fadeIn(tween(180)), exit = fadeOut(tween(180)),
                                ) {
                                    Box(Modifier.background(Paper).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                                        NavigationBar(Modifier.height(80.dp), containerColor = Paper, tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
                                            destinations.forEach { item ->
                                                NavigationBarItem(
                                                    selected = selected == item.screen,
                                                    onClick = { navigate(item.screen) },
                                                    icon = { Glyph(item.icon, Modifier.size(23.dp), if (selected == item.screen) Accent else Muted) },
                                                    label = { Text(item.label) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (snackbar != null) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                            .padding(bottom = if (showBottomNavigation && !usesRail) 80.dp else 0.dp))
                    }
                }
            }
        }
    }
}
