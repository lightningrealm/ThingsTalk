package com.lr.thingstalk.panel

import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.data.panel.PanelJson
import kotlin.test.*

class SetupDefaultsTest {
    private val name = SetupField(6, "设备名称", 1, 32, rule = SetupFieldRule.IDENTIFIER, defaultValue = "muyu-01")
    private val password = SetupField(5, "MQTT 密码", maxBytes = 128, secret = true)
    private val form = SetupPanel("setup", "网络", "设置", "12345678-1234-1234-1234-123456789001",
        "12345678-1234-1234-1234-123456789002", "12345678-1234-1234-1234-123456789003",
        listOf(SetupSection(2, "MQTT", listOf(name, password))))
    @Test fun defaultsRoundTripAndNeverOverwriteSavedDeviceName() {
        val definition = PanelDefinition(schemaVersion = 7, title = "设备", setupPanels = listOf(form))
        assertEquals(definition, PanelJson.decode(PanelJson.encode(definition)))
        assertEquals("muyu-01", form.initialInputs(mapOf(6 to ""), setOf(2))[6])
        assertEquals("muyu", form.initialInputs(mapOf(6 to "muyu"), emptySet())[6])
        assertEquals("", form.initialInputs(mapOf(6 to ""), emptySet())[6])
        assertFalse(form.initialInputs(emptyMap(), setOf(2)).containsKey(5))
        assertTrue(definition.setupImportNotice().contains("muyu-01"))
        val old = form.copy(sections = listOf(SetupSection(2, "MQTT", listOf(name.copy(defaultValue = null), password))))
        assertFalse("defaultValue" in PanelJson.encode(definition.copy(setupPanels = listOf(old))))
    }
    @Test fun rejectCredentialDefaultsAndInvalidIdentifier() {
        fun invalid(field: SetupField) = validateSetupPanels(listOf(form.copy(sections = listOf(SetupSection(2, "MQTT", listOf(field))))))
        assertTrue(invalid(password.copy(defaultValue = "password123")).isNotEmpty())
        assertTrue(invalid(name.copy(defaultValue = "invalid/name")).isNotEmpty())
        assertTrue(invalid(SetupField(4, "用户名", maxBytes = 64, defaultValue = "account")).isNotEmpty())
    }
    @Test fun emptyAndClearedPasswordsRequireDifferentConfirmationWithoutExposingValue() {
        val section = form.sections.single()
        assertTrue(section.passwordConfirmation(emptyMap(), emptySet(), setOf(5))!!.contains("保留"))
        assertTrue(section.passwordConfirmation(emptyMap(), emptySet(), emptySet())!!.contains("未报告已保存"))
        val clear = section.passwordConfirmation(mapOf(5 to "secret123"), setOf(5), setOf(5))!!
        assertTrue(clear.contains("清空")); assertFalse(clear.contains("secret123"))
        assertNull(section.passwordConfirmation(mapOf(5 to "secret123"), emptySet(), emptySet()))
    }
}
