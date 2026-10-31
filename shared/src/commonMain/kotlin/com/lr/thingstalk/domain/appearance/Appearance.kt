package com.lr.thingstalk.domain.appearance

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable enum class ThemePalette { SAGE, BLUE, VIOLET, CLAY, GRAPHITE }
@Serializable data class Appearance(val mode: ThemeMode = ThemeMode.SYSTEM, val palette: ThemePalette = ThemePalette.SAGE)
fun Appearance.isDark(systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
interface AppearanceRepository {
    val appearance: StateFlow<Appearance>
    suspend fun save(value: Appearance)
}
