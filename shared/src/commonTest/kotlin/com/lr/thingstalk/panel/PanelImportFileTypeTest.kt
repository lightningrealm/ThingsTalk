package com.lr.thingstalk.panel

import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.presentation.panel.preparePanelImport
import kotlin.test.*

class PanelImportFileTypeTest {
    @Test fun vectorFilesReportTheirPurposeBeforeTransportProjection() {
        val source = """{"configuration":"device.thingstalk.json","valid":[],"invalid":[{"hex":"01 01 00","reason":"Extra byte"}]}"""
        for (ble in listOf(false, true)) {
            val error = assertFailsWith<IllegalArgumentException> { preparePanelImport(source, bleAvailable = ble) }
            assertTrue(error.message.orEmpty().contains("协议测试向量"))
            assertTrue(error.message.orEmpty().contains("configuration"))
            assertFalse(error.message.orEmpty().contains("Extra byte"))
        }
    }

    @Test fun unknownFieldsStayRejectedWithoutLeakingRawInput() {
        val error = assertFailsWith<IllegalArgumentException> {
            PanelJson.decode("""{"title":"Example","schemaVersion":4,"transport":"MQTT","password":"do-not-display"}""")
        }
        assertTrue(error.message.orEmpty().contains("字段"))
        assertFalse(error.message.orEmpty().contains("do-not-display"))
    }

    @Test fun nonObjectsAndMalformedJsonHaveActionableErrors() {
        for (source in listOf("[]", "null", "not-json", "{", """{"schemaVersion":{},"title":"Example"}""")) {
            val error = assertFailsWith<IllegalArgumentException> { PanelJson.decode(source) }
            assertTrue(error.message.orEmpty().contains(".thingstalk.json"))
        }
    }

    @Test fun validLegacyConfigurationWithoutExplicitVersionStillImports() {
        assertEquals("Example", PanelJson.decode("""{"title":"Example"}""").title)
    }
}
