package com.lr.thingstalk

import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.domain.panel.*
import java.io.File
import kotlin.test.*

class MqttPanelExampleTest {
    @Test fun dualExampleImportsAndProjectsToBothAdapters() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "docs/examples/dual-power.thingstalk.json").exists() }
        val panel = PanelJson.decode(File(root, "docs/examples/dual-power.thingstalk.json").readText())
        assertEquals(PanelTransport.DUAL, panel.transport)
        assertEquals(PanelTransport.BLE, panel.bindings.single().controlTransport)
        assertEquals(PanelTransport.MQTT, panel.bindings.single().stateTransport)
        assertEquals(panel, PanelJson.decode(PanelJson.encode(panel)))
        assertTrue(validatePanel(panel.forTransport(PanelTransport.BLE)).isEmpty())
        assertTrue(validatePanel(panel.forTransport(PanelTransport.MQTT)).isEmpty())
    }

    @Test fun distributableMqttExampleImportsAndCoversAllWidgets() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "docs/examples/mqtt-temperature-light.thingstalk.json").exists() }
        val panel = PanelJson.decode(File(root, "docs/examples/mqtt-temperature-light.thingstalk.json").readText())
        assertEquals(PanelTransport.MQTT, panel.transport)
        assertEquals(WidgetType.entries.toSet(), panel.widgets.map { it.type }.toSet())
        assertEquals(panel, PanelJson.decode(PanelJson.encode(panel)))
    }
}
