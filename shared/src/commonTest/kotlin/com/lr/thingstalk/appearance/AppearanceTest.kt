package com.lr.thingstalk.appearance

import com.lr.thingstalk.data.appearance.*
import com.lr.thingstalk.domain.appearance.*
import com.lr.thingstalk.presentation.theme.themeColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppearanceTest {
    @Test fun followsSystemOnlyWhenSelected() {
        assertFalse(Appearance().isDark(false))
        assertTrue(Appearance().isDark(true))
        assertFalse(Appearance(ThemeMode.LIGHT).isDark(true))
        assertTrue(Appearance(ThemeMode.DARK).isDark(false))
    }
    @Test fun persistedChoiceSurvivesRecreation() = runTest {
        val storage = MemoryStorage()
        val repository = StoredAppearanceRepository(storage)
        for (mode in ThemeMode.entries) for (palette in ThemePalette.entries) for (language in LanguagePreference.entries) {
            val expected = Appearance(mode, palette, language)
            repository.save(expected)
            assertEquals(expected, StoredAppearanceRepository(storage).appearance.value)
        }
    }
    @Test fun failedSaveKeepsPreviousChoiceAndDoesNotOverwriteData() = runTest {
        val storage = MemoryStorage()
        val repository = StoredAppearanceRepository(storage)
        repository.save(Appearance(ThemeMode.DARK, ThemePalette.BLUE))
        val previous = storage.source
        storage.fail = true
        assertFailsWith<IllegalStateException> { repository.save(Appearance(ThemeMode.LIGHT)) }
        assertEquals(Appearance(ThemeMode.DARK, ThemePalette.BLUE), repository.appearance.value)
        assertEquals(previous, storage.source)
    }
    @Test fun badOrFutureSettingsFallBackWithoutErasingSource() {
        for (source in listOf("not-json", "{\"mode\":\"FUTURE\",\"palette\":\"FUTURE\"}")) {
            val storage = MemoryStorage().apply { this.source = source }
            assertEquals(Appearance(), StoredAppearanceRepository(storage).appearance.value)
            assertEquals(source, storage.source)
        }
    }
    @Test fun allPalettesKeepReadableTextOnTheirSurfaces() {
        fun contrast(a: Color, b: Color): Double {
            val first = a.luminance().toDouble(); val second = b.luminance().toDouble()
            return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
        }
        for (palette in ThemePalette.entries) for (dark in listOf(false, true)) {
            val c = themeColors(palette, dark)
            for ((text, background) in listOf(c.onPrimary to c.primary, c.onPrimaryContainer to c.primaryContainer,
                c.onSurface to c.surface, c.onBackground to c.background, c.onSurfaceVariant to c.surface,
                c.onSurfaceVariant to c.surfaceContainerHigh, c.onSurfaceVariant to c.surfaceContainerHighest,
                c.onSurface to c.surfaceContainerLowest, c.primary to c.surfaceContainerHigh)) {
                assertTrue(contrast(text, background) >= 4.5, "$palette / dark=$dark contrast=${contrast(text, background)}")
            }
        }
    }
    @Test fun changingPaletteAlsoChangesSurfacesAndBordersInBothModes() {
        for (dark in listOf(false, true)) {
            val schemes = ThemePalette.entries.map { themeColors(it, dark) }
            assertEquals(1, schemes.map { it.background }.distinct().size)
            for (role in listOf<(androidx.compose.material3.ColorScheme) -> Color>(
                { it.surface }, { it.surfaceContainerHigh }, { it.outlineVariant }, { it.onSurfaceVariant })) {
                assertEquals(ThemePalette.entries.size, schemes.map(role).distinct().size)
            }
            for (scheme in schemes) {
                assertNotEquals(scheme.background, scheme.surface)
                assertNotEquals(scheme.surface, scheme.surfaceContainerHigh)
                assertNotEquals(scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)
            }
        }
    }

    private class MemoryStorage : AppearanceStorage {
        var source: String? = null
        var fail = false
        override fun load() = source
        override suspend fun save(source: String) { check(!fail); this.source = source }
    }
}
