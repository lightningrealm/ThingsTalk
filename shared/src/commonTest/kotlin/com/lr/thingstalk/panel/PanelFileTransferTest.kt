package com.lr.thingstalk.panel

import com.lr.thingstalk.presentation.panel.MAX_PANEL_FILE_BYTES
import com.lr.thingstalk.presentation.panel.decodePanelFile
import com.lr.thingstalk.presentation.panel.encodePanelFile
import com.lr.thingstalk.presentation.panel.panelConfigurationFileName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PanelFileTransferTest {
    @Test fun unicodeAndOptionalUtf8BomRoundTrip() {
        val source = "{\"title\":\"温湿度面板\"}"
        assertEquals(source, decodePanelFile(encodePanelFile(source)))
        assertEquals(source, decodePanelFile(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + encodePanelFile(source)))
    }

    @Test fun malformedUtf8IsRejectedInsteadOfReplacingBytes() {
        assertFailsWith<IllegalArgumentException> { decodePanelFile(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertFailsWith<IllegalArgumentException> { decodePanelFile(byteArrayOf(0xE4.toByte(), 0xB8.toByte())) }
    }

    @Test fun byteLimitIsAppliedToDecodedAndEncodedFiles() {
        assertEquals(MAX_PANEL_FILE_BYTES, decodePanelFile(ByteArray(MAX_PANEL_FILE_BYTES) { 32 }).length)
        assertFailsWith<IllegalArgumentException> { decodePanelFile(ByteArray(MAX_PANEL_FILE_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> { encodePanelFile("温".repeat(MAX_PANEL_FILE_BYTES / 3 + 1)) }
    }

    @Test fun filenamesCannotEscapeDirectoryAndKeepOneExtension() {
        assertEquals("温度面板.thingstalk.json", panelConfigurationFileName("温度面板.thingstalk.json"))
        assertEquals("temperature.thingstalk.json", panelConfigurationFileName("temperature.JSON"))
        assertEquals("outside.thingstalk.json", panelConfigurationFileName("../../outside"))
        assertEquals("panel.thingstalk.json", panelConfigurationFileName(".../"))
    }
}
