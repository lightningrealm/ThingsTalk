package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AiProvisioningTest {
    private fun form() = SetupPanel("ai", "AI", "Open setup",
        "5c53a001-7d8a-4b19-9c63-414950415353", "5c53a00c-7d8a-4b19-9c63-414950415353", "5c53a00d-7d8a-4b19-9c63-414950415353",
        listOf(SetupSection(1, "AI", listOf(
            SetupField(1, "Endpoint", 1, 192, defaultValue = "https://api.deepseek.com/chat/completions"),
            SetupField(2, "API Key", 0, 192, secret = true),
            SetupField(3, "Model", 1, 64, defaultValue = "deepseek-flash")))), protocol = SetupProtocol.BLE_AI_SETTINGS_V1)
    @Test fun fixedProviderRequiresDefaultsAndOverridesSavedValues() = runTest {
        val original = form()
        val fixed = original.copy(sections = original.sections.map { section -> section.copy(fields = section.fields.map { if (it.id in listOf(1, 3)) it.copy(readOnly = true) else it }) })
        assertTrue(validateSetupPanels(listOf(fixed)).isEmpty())
        assertEquals("deepseek-flash", fixed.initialInputs(mapOf(3 to "old-model"), emptySet())[3])
        val invalid = fixed.copy(sections = fixed.sections.map { it.copy(fields = it.fields.map { f -> if (f.id == 2) f.copy(readOnly = true) else f }) })
        assertTrue(validateSetupPanels(listOf(invalid)).isNotEmpty())
        val raw = MuyuProvisioningTest.Connection(fixed.command, fixed.status)
        val client = BleSetupPanelClient(raw, fixed)
        assertFailsWith<IllegalArgumentException> { client.submit(1, mapOf(1 to "https://elsewhere.example/chat", 3 to "deepseek-flash")) }
        assertTrue(raw.writes.isEmpty())
    }

    @Test fun validatesIndependentAdapterWithoutRelaxingNetworkFields() {
        val f = form()
        assertTrue(validateSetupPanels(listOf(f)).isEmpty())
        assertTrue(validateSetupPanels(listOf(f.copy(protocol = SetupProtocol.BLE_CHUNKED_SETTINGS_V1))).isNotEmpty())
        assertTrue(validateSetupPanels(listOf(f.copy(sections = listOf(f.sections.single().copy(id = 2))))).isNotEmpty())
        val secretDefault = f.copy(sections = listOf(f.sections.single().copy(fields = f.sections.single().fields.map { if (it.secret) it.copy(defaultValue = "example") else it })))
        assertTrue(validateSetupPanels(listOf(secretDefault)).isNotEmpty())
        assertEquals("deepseek-flash", f.initialInputs(emptyMap(), setOf(1))[3])
        assertEquals("saved-model", f.initialInputs(mapOf(3 to "saved-model"), emptySet())[3])
    }
    @Test fun preparesEncryptedFieldsAndUsesAiSavedFlags() = runTest {
        val base = form()
        val section = base.sections.single()
        val f = base.copy(sections = listOf(section.copy(fields = section.fields.map {
            if (it.secret) it else it.copy(readUuid = if (it.id == 1)
                "5c53a00e-7d8a-4b19-9c63-414950415353" else "5c53a00f-7d8a-4b19-9c63-414950415353")
        })))
        val connection = MuyuProvisioningTest.Connection(f.command, f.status)
        connection.response[2] = 1
        val client = BleSetupPanelClient(connection, f)
        val empty = client.readValues()
        assertEquals(mapOf(1 to "", 3 to ""), empty)
        assertEquals(2, connection.fieldReads)
        assertTrue(connection.writes.isEmpty())
        assertEquals("deepseek-flash", f.initialInputs(empty, setOf(1))[3])
        connection.response[2] = 0x29
        val status = client.status()
        assertTrue(f.isSectionSaved(status, 1))
        assertEquals(setOf(2), f.savedSecretIds(status))
        val saved = client.readValues()
        assertEquals("saved", f.initialInputs(saved, emptySet())[3])
        assertFalse(saved.containsKey(2))
        assertTrue(connection.writes.isEmpty())
        // Network secret flags must not be interpreted as another AI field.
        connection.response[2] = 0x41
        assertTrue(f.savedSecretIds(client.status()).isEmpty())
    }

    @Test fun preservesOrExplicitlyClearsKeyAndChunksLongEndpoint() = runTest {
        val f = form()
        val connection = MuyuProvisioningTest.Connection(f.command, f.status)
        connection.response[2] = 0x29
        val client = BleSetupPanelClient(connection, f)
        val values = mapOf(1 to "https://api.deepseek.com/chat/completions", 3 to "deepseek-flash")
        client.submit(1, values)
        assertFalse(connection.writes.any { (it[0].toInt() and 255) == 0xB2 && it[3].toInt() == 2 })
        assertTrue(connection.writes.count { (it[0].toInt() and 255) == 0xB2 && it[3].toInt() == 1 } >= 3)
        connection.writes.clear()
        client.submit(1, values + (2 to ""))
        assertTrue(connection.writes.any { (it[0].toInt() and 255) == 0xB2 && it[3].toInt() == 2 && it.size == 6 })
        connection.writes.clear()
        client.submit(1, values + (2 to "test-placeholder"))
        assertTrue(connection.writes.any { (it[0].toInt() and 255) == 0xB2 && it[3].toInt() == 2 && it.size > 6 })
    }
}
