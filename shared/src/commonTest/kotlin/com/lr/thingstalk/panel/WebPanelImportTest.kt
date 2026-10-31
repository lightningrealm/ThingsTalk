package com.lr.thingstalk.panel

import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.preparePanelImport
import kotlin.test.*

class WebPanelImportTest {
    private val ble = BleOperationBinding("0000180f-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb", codec = CodecType.BOOLEAN)
    private fun dual() = PanelDefinition(5, "设备原名", transport = PanelTransport.DUAL,
        capabilities = listOf(PanelCapability("power", "电源", CapabilityType.BOOLEAN), PanelCapability("local", "本地灯", CapabilityType.BOOLEAN)),
        widgets = listOf(PanelWidget("switch", WidgetType.SWITCH, "电源", "power"), PanelWidget("localSwitch", WidgetType.SWITCH, "本地灯", "local")),
        payloadFormats = listOf(PayloadFormat("state", "状态", PayloadEncoding.JSON, listOf(PayloadField("power", CodecType.BOOLEAN)))),
        bindings = listOf(PanelBinding("power", read = ble, write = ble, notify = ble,
            publish = MqttOperationBinding("device/原名/set", PayloadReference("state", "power")),
            subscribe = MqttOperationBinding("device/原名/state", PayloadReference("state", "power")),
            controlTransport = PanelTransport.BLE, stateTransport = PanelTransport.BLE),
            PanelBinding("local", write = ble, controlTransport = PanelTransport.BLE)))

    @Test fun legacyAndSeparatedChannelsBecomeValidMqttWithoutMutatingTheSource() {
        for (original in listOf(dual(), dual().asChannelPanel())) {
            val source = PanelJson.encode(original)
            val imported = preparePanelImport(source, bleAvailable = false)
            assertTrue(imported.mqttOnly)
            assertNotNull(imported.notice())
            val result = imported.panel
            assertEquals(PanelTransport.MQTT, result.transport)
            assertEquals(4, result.schemaVersion)
            assertNull(result.defaultChannel)
            assertEquals(listOf("switch"), result.widgets.map { it.id })
            assertEquals("电源", result.widgets.single().label)
            assertEquals("设备原名", result.title)
            assertEquals(original.bindings.first().publish, result.bindings.single().publish)
            assertEquals(original.bindings.first().subscribe, result.bindings.single().subscribe)
            assertFalse(result.bindings.single().canRead)
            assertTrue(result.bindings.single().canObserve)
            assertNull(result.bindings.single().write)
            assertNull(result.bindings.single().controlTransport)
            assertEquals(result, PanelJson.decode(imported.source))
            assertEquals(source, PanelJson.encode(original))
            assertEquals(original, preparePanelImport(source, bleAvailable = true).panel)
        }
    }

    @Test fun setupFormsAndReadOnlyBleWidgetsAreExcludedFromWeb() {
        val panel = dual().asChannelPanel().copy(schemaVersion = 7,
            setupPanels = listOf(SetupPanel("setup", "Wi-Fi", "", ble.serviceUuid, ble.characteristicUuid,
                "00002a1a-0000-1000-8000-00805f9b34fb", listOf(SetupSection(1, "Wi-Fi", listOf(SetupField(1, "SSID", maxBytes = 32)))))),
            widgets = dual().widgets + PanelWidget("value", WidgetType.VALUE, "状态", "local"),
            bindings = dual().asChannelPanel().bindings.map { if (it.capabilityId == "local") it.copy(read = ble, stateTransport = PanelTransport.BLE) else it })
        val imported = preparePanelImport(PanelJson.encode(panel), bleAvailable = false)
        assertTrue(imported.panel.setupPanels.isEmpty())
        assertEquals(1, imported.panel.widgets.size)
        assertTrue(validatePanel(imported.panel).isEmpty())
    }

    @Test fun unsupportedFilesAreRejectedAndPureMqttIsUnchanged() {
        val noMqtt = dual().copy(bindings = dual().bindings.map { it.copy(publish = null, subscribe = null) })
        assertFailsWith<IllegalArgumentException> { preparePanelImport(PanelJson.encode(noMqtt), bleAvailable = false) }
        val bleOnly = dual().forTransport(PanelTransport.BLE)
        assertFailsWith<IllegalArgumentException> { preparePanelImport(PanelJson.encode(bleOnly), bleAvailable = false) }
        val mqtt = dual().mqttImportProjection()
        val source = PanelJson.encode(mqtt)
        val imported = preparePanelImport(source, bleAvailable = false)
        assertEquals(source, imported.source)
        assertFalse(imported.mqttOnly)
        assertNull(imported.notice())
    }

    @Test fun widgetsRequireTheirOwnMqttOperationNotJustAnyMqttBinding() {
        val original = dual().copy(bindings = dual().bindings.map { if (it.capabilityId == "power") it.copy(publish = null) else it },
            widgets = dual().widgets + PanelWidget("value", WidgetType.VALUE, "状态", "power"))
        val result = preparePanelImport(PanelJson.encode(original), bleAvailable = false).panel
        assertEquals(listOf("value"), result.widgets.map { it.id })
        assertNotNull(result.bindings.single().subscribe)
        assertNull(result.bindings.single().publish)
    }
}
