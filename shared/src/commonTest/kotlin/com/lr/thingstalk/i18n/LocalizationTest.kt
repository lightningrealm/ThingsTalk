package com.lr.thingstalk.i18n

import com.lr.thingstalk.data.appearance.*
import com.lr.thingstalk.domain.appearance.*
import com.lr.thingstalk.presentation.i18n.*
import kotlin.test.*

class LocalizationTest {
    @Test fun systemLanguageAndManualOverride() {
        for (tag in listOf("zh", "zh-CN", "zh_Hans_CN", "ZH-hant-TW")) {
            assertEquals(AppLanguage.CHINESE, LanguagePreference.SYSTEM.resolve(tag))
            assertEquals(AppLanguage.ENGLISH, LanguagePreference.ENGLISH.resolve(tag))
        }
        for (tag in listOf("en-US", "fr", "", "ja-JP")) {
            assertEquals(AppLanguage.ENGLISH, LanguagePreference.SYSTEM.resolve(tag))
            assertEquals(AppLanguage.CHINESE, LanguagePreference.SIMPLIFIED_CHINESE.resolve(tag))
        }
    }

    @Test fun existingAppearanceSettingsKeepThemeAndDefaultToSystemLanguage() {
        val source = """{"mode":"DARK","palette":"BLUE"}"""
        val storage = object : AppearanceStorage {
            override fun load() = source
            override suspend fun save(source: String) = error("Must not overwrite during load")
        }
        assertEquals(Appearance(ThemeMode.DARK, ThemePalette.BLUE, LanguagePreference.SYSTEM),
            StoredAppearanceRepository(storage).appearance.value)
    }

    @Test fun interpolationPreservesUserTextAndAllowsReordering() {
        val text = UiString("example", "%1\$s：%2\$s", "%2\$s from %1\$s")
        val name = "设备 / %2\$s / devices/灯/#"
        assertEquals("42 from $name", text.resolve(AppLanguage.ENGLISH, name, 42))
        assertEquals("$name：42", text.resolve(AppLanguage.CHINESE, name, 42))
        assertFailsWith<IllegalArgumentException> { text.resolve(AppLanguage.ENGLISH, name) }
    }

    @Test fun legacyEventsAndDiagnosticTemplatesTranslateWithoutChangingArguments() {
        assertEquals("BLE connected", DiagnosticTranslations.resolve("BLE 已连接", AppLanguage.ENGLISH))
        assertEquals("Subscribed to devices/设备/%2\$s", DiagnosticTranslations.resolve("已订阅 devices/设备/%2\$s", AppLanguage.ENGLISH))
        assertEquals("Payload too short for 4 bytes at offset 2", DiagnosticTranslations.resolve("报文长度不足，无法读取偏移 2 的 4 字节数据", AppLanguage.ENGLISH))
        val unknown = "CBATTErrorDomain / code=5 / vendor payload"
        assertEquals(unknown, DiagnosticTranslations.resolve(unknown, AppLanguage.CHINESE))
        assertEquals("主题：原始数据", DiagnosticTranslations.resolve("主题：原始数据", AppLanguage.ENGLISH))
        assertEquals("Import failed: Field 温度: Number must be finite",
            DiagnosticTranslations.resolve("导入失败：字段 温度：数值必须有限", AppLanguage.ENGLISH))
    }

    @Test fun catalogHasCompleteTranslationsAndMatchingPlaceholders() {
        val resources = AppStrings.catalog()
        assertTrue(resources.size > 1000)
        assertEquals(resources.size, resources.map { it.id }.toSet().size)
        val parameter = Regex("%([1-9][0-9]*)\\\$s")
        resources.forEach { resource ->
            assertTrue(resource.english.isNotBlank(), resource.id)
            assertTrue(resource.chinese.isNotBlank(), resource.id)
            assertEquals(parameter.findAll(resource.chinese).map { it.value }.toList().sorted(),
                parameter.findAll(resource.english).map { it.value }.toList().sorted(), resource.id)
        }
    }

}
