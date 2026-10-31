package com.lr.thingstalk.presentation.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.appearance.*

// Existing screens use semantic tokens so all surfaces follow the same theme.
val Ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val Paper: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val Line: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
val Soft: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh

internal fun themeColors(palette: ThemePalette, dark: Boolean): ColorScheme {
    val colors = when (palette) {
        ThemePalette.SAGE -> listOf(0xFF416D60, 0xFFA1D1BD, 0xFFE0EDE5, 0xFF253F35)
        ThemePalette.BLUE -> listOf(0xFF41658B, 0xFFA8C8EF, 0xFFE1EBF6, 0xFF25394E)
        ThemePalette.VIOLET -> listOf(0xFF6B5891, 0xFFD0BCF3, 0xFFEDE5F7, 0xFF3C304E)
        ThemePalette.CLAY -> listOf(0xFF905B45, 0xFFEDBBA4, 0xFFF4E5DC, 0xFF4C3329)
        ThemePalette.GRAPHITE -> listOf(0xFF5B6368, 0xFFC2CBD1, 0xFFE4E9EC, 0xFF333B40)
    }.map { Color(it) }
    val primary = colors[if (dark) 1 else 0]
    val container = colors[if (dark) 3 else 2]
    // Keep large surfaces quiet; cards and outlines carry only a restrained palette tint.
    val surfaces = when (palette) {
        ThemePalette.SAGE -> listOf(0xFFF9FCF6, 0xFFADC8B7, 0xFF1C2C24, 0xFF4A6B58)
        ThemePalette.BLUE -> listOf(0xFFF5F9FF, 0xFFAEBFD3, 0xFF1E2E43, 0xFF4C6C90)
        ThemePalette.VIOLET -> listOf(0xFFFAF6FF, 0xFFC4B3D6, 0xFF2E233F, 0xFF705889)
        ThemePalette.CLAY -> listOf(0xFFFFF9F3, 0xFFD7B5A3, 0xFF39291F, 0xFF89634D)
        ThemePalette.GRAPHITE -> listOf(0xFFFAFBFC, 0xFFB9C2CA, 0xFF282D32, 0xFF5C6872)
    }.map { Color(it) }
    val background = Color(if (dark) 0xFF151719 else 0xFFF6F7F3)
    val surface = lerp(Color(if (dark) 0xFF222529 else 0xFFFCFCFA),
        surfaces[if (dark) 2 else 0], if (dark) .35f else .45f)
    val border = lerp(Color(if (dark) 0xFF353B40 else 0xFFDDE1E3),
        surfaces[if (dark) 3 else 1], .28f)
    val low = lerp(background, surface, .65f)
    val high = lerp(surface, container, .45f)
    val highest = lerp(surface, container, .65f)
    val ink = lerp(if (dark) Color(0xFFF0F0F0) else Color(0xFF202124), primary, .12f)
    val muted = lerp(if (dark) Color(0xFFBDC0C4) else Color(0xFF505459), primary, .16f)
    val outline = lerp(if (dark) Color(0xFF85898E) else Color(0xFF73777C), primary, .28f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = if (dark) Color(0xFF17191C) else Color.White,
        primaryContainer = container, onPrimaryContainer = ink,
        secondary = primary, onSecondary = if (dark) Color(0xFF17191C) else Color.White,
        secondaryContainer = container, onSecondaryContainer = ink,
        tertiary = primary, onTertiary = if (dark) Color(0xFF17191C) else Color.White,
        tertiaryContainer = container, onTertiaryContainer = ink,
        background = background, onBackground = ink, surface = surface, onSurface = ink,
        onSurfaceVariant = muted,
        surfaceVariant = high, outline = outline,
        outlineVariant = border,
        surfaceContainerLowest = background, surfaceContainerLow = low,
        surfaceContainer = surface, surfaceContainerHigh = high, surfaceContainerHighest = highest,
        surfaceBright = highest, surfaceDim = background, surfaceTint = primary,
        inverseSurface = lerp(if (dark) Color(0xFFF0F0F0) else Color(0xFF252525), primary, .12f),
        inverseOnSurface = if (dark) Color(0xFF202124) else Color(0xFFF5F5F5),
        inversePrimary = colors[if (dark) 0 else 1],
    )
}

@Composable
fun ThinsTalkTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val dark = appearance.isDark(isSystemInDarkTheme())
    val targetScheme = remember(appearance.palette, dark) { themeColors(appearance.palette, dark) }
    val scheme = animatedThemeColors(targetScheme)
    PlatformAppearance(appearance.mode, dark, scheme.background)
    MaterialTheme(colorScheme = scheme,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content)
}

@Composable internal expect fun PlatformAppearance(mode: ThemeMode, dark: Boolean, background: Color)

// Initialize at the current palette; subsequent changes retarget from the displayed color.
@Composable
private fun animatedThemeColor(target: Color, role: String): Color = animateColorAsState(
    targetValue = target,
    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
    label = "theme.$role",
).value

@Composable
private fun animatedThemeColors(target: ColorScheme): ColorScheme = target.copy(
    primary = animatedThemeColor(target.primary, "primary"),
    onPrimary = animatedThemeColor(target.onPrimary, "onPrimary"),
    primaryContainer = animatedThemeColor(target.primaryContainer, "primaryContainer"),
    onPrimaryContainer = animatedThemeColor(target.onPrimaryContainer, "onPrimaryContainer"),
    inversePrimary = animatedThemeColor(target.inversePrimary, "inversePrimary"),
    secondary = animatedThemeColor(target.secondary, "secondary"),
    onSecondary = animatedThemeColor(target.onSecondary, "onSecondary"),
    secondaryContainer = animatedThemeColor(target.secondaryContainer, "secondaryContainer"),
    onSecondaryContainer = animatedThemeColor(target.onSecondaryContainer, "onSecondaryContainer"),
    tertiary = animatedThemeColor(target.tertiary, "tertiary"),
    onTertiary = animatedThemeColor(target.onTertiary, "onTertiary"),
    tertiaryContainer = animatedThemeColor(target.tertiaryContainer, "tertiaryContainer"),
    onTertiaryContainer = animatedThemeColor(target.onTertiaryContainer, "onTertiaryContainer"),
    background = animatedThemeColor(target.background, "background"),
    onBackground = animatedThemeColor(target.onBackground, "onBackground"),
    surface = animatedThemeColor(target.surface, "surface"),
    onSurface = animatedThemeColor(target.onSurface, "onSurface"),
    surfaceVariant = animatedThemeColor(target.surfaceVariant, "surfaceVariant"),
    onSurfaceVariant = animatedThemeColor(target.onSurfaceVariant, "onSurfaceVariant"),
    surfaceTint = animatedThemeColor(target.surfaceTint, "surfaceTint"),
    inverseSurface = animatedThemeColor(target.inverseSurface, "inverseSurface"),
    inverseOnSurface = animatedThemeColor(target.inverseOnSurface, "inverseOnSurface"),
    error = animatedThemeColor(target.error, "error"),
    onError = animatedThemeColor(target.onError, "onError"),
    errorContainer = animatedThemeColor(target.errorContainer, "errorContainer"),
    onErrorContainer = animatedThemeColor(target.onErrorContainer, "onErrorContainer"),
    outline = animatedThemeColor(target.outline, "outline"),
    outlineVariant = animatedThemeColor(target.outlineVariant, "outlineVariant"),
    scrim = animatedThemeColor(target.scrim, "scrim"),
    surfaceBright = animatedThemeColor(target.surfaceBright, "surfaceBright"),
    surfaceDim = animatedThemeColor(target.surfaceDim, "surfaceDim"),
    surfaceContainerLowest = animatedThemeColor(target.surfaceContainerLowest, "surfaceContainerLowest"),
    surfaceContainerLow = animatedThemeColor(target.surfaceContainerLow, "surfaceContainerLow"),
    surfaceContainer = animatedThemeColor(target.surfaceContainer, "surfaceContainer"),
    surfaceContainerHigh = animatedThemeColor(target.surfaceContainerHigh, "surfaceContainerHigh"),
    surfaceContainerHighest = animatedThemeColor(target.surfaceContainerHighest, "surfaceContainerHighest"),
)
