package com.lr.thingstalk

import com.lr.thingstalk.data.panel.DefaultPayloadInspector
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.domain.panel.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

/** Portable hardware examples must remain importable and match the actual wire codec. */
class HardwareSkillContractTest {
    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "skills/thingstalk-ble-panel/assets/all-widgets.thingstalk.json").isFile }
    private val assets = File(root, "skills/thingstalk-ble-panel/assets")
    private val definition = PanelJson.decode(File(assets, "all-widgets.thingstalk.json").readText())
    private val vectors = Json.parseToJsonElement(File(assets, "wire-vectors.json").readText()).jsonObject
    private val inspector = DefaultPayloadInspector()

    @Test fun setupExamplesImportAndPreserveFormsAndChannelBindings() {
        for (file in listOf(File(assets, "setup-panel.thingstalk.json"), File(root, "docs/examples/muyu-setup-v7.thingstalk.json"), File(root, "docs/examples/muyu-network-default-v7.thingstalk.json"))) {
            val panel = PanelJson.decode(file.readText())
            assertEquals(7, panel.schemaVersion)
            assertEquals(1, panel.setupPanels.size)
            assertEquals(panel, PanelJson.decode(PanelJson.encode(panel)))
            assertEquals(6, panel.setupPanels.single().sections.sumOf { it.fields.size })
        }
    }

    @Test fun hardwareExampleImportsAndCoversEveryAvailableWidget() {
        assertEquals(WidgetType.entries.toSet(), definition.widgets.map { it.type }.toSet())
        assertEquals(definition, PanelJson.decode(PanelJson.encode(definition)))
        assertEquals(definition.capabilities.map { it.id }.toSet(), definition.bindings.map { it.capabilityId }.toSet())
        assertEquals("all-widgets.thingstalk.json", vectors.getValue("configuration").jsonPrimitive.content)
    }

    @Test fun publishedVectorsDecodeAndEncodeWithRealAppCodec() {
        vectors.getValue("valid").jsonArray.forEach { item ->
            val vector = item.jsonObject
            val format = definition.payloadFormats.single { it.id == vector.getValue("formatId").jsonPrimitive.content }
            val hex = vector.getValue("hex").jsonPrimitive.content
            val expected = vector.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val decoded = inspector.decode(format, hex, PayloadInputEncoding.HEX)
            assertEquals(expected.keys, decoded.fields.map { it.id }.toSet())
            decoded.fields.forEach { field ->
                if (field.codec == CodecType.BOOLEAN) assertEquals(expected[field.id], field.value)
                else assertEquals(expected.getValue(field.id).toDouble(), field.value.toDouble())
            }
            assertEquals(hex, inspector.encode(format, expected).hex)
        }
    }

    @Test fun publishedInvalidVectorsAreRejectedByRealAppCodec() {
        vectors.getValue("invalid").jsonArray.forEach { item ->
            val vector = item.jsonObject
            val format = definition.payloadFormats.single { it.id == vector.getValue("formatId").jsonPrimitive.content }
            assertFailsWith<IllegalArgumentException>(vector.getValue("reason").jsonPrimitive.content) {
                val hex = vector["hex"]?.jsonPrimitive?.content
                if (hex != null) inspector.decode(format, hex, PayloadInputEncoding.HEX)
                else inspector.encode(format, vector.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content })
            }
        }
    }

    @Test fun mqttExampleImportsAndItsPublishedPayloadsMatchTheCodec() {
        val mqtt = PanelJson.decode(File(assets, "mqtt-all-widgets.thingstalk.json").readText())
        assertEquals(PanelTransport.MQTT, mqtt.transport)
        assertEquals(4, mqtt.schemaVersion)
        assertEquals(WidgetType.entries.toSet(), mqtt.widgets.map { it.type }.toSet())
        assertEquals(mqtt, PanelJson.decode(PanelJson.encode(mqtt)))
        val state = mqtt.payloadFormats.single { it.id == "state" }
        val decoded = inspector.decode(state, """{"temperature":25.0,"power":true,"brightness":80}""", PayloadInputEncoding.JSON_TEXT)
        assertEquals(listOf("25", "true", "80"), decoded.fields.map { if (it.codec == CodecType.BOOLEAN) it.value else it.value.toDouble().toInt().toString() })
        val command = mqtt.payloadFormats.single { it.id == "powerCommand" }
        val encoded = inspector.encode(command, mapOf("power" to "false"))
        assertEquals("7B 22 70 6F 77 65 72 22 3A 66 61 6C 73 65 7D", encoded.hex)
        assertFailsWith<IllegalArgumentException> {
            inspector.decode(state, """{"temperature":25,"power":"true","brightness":80}""", PayloadInputEncoding.JSON_TEXT)
        }
        val badRetain = mqtt.copy(bindings = mqtt.bindings.map { it.copy(publish = it.publish?.copy(retain = true)) })
        assertTrue(validatePanel(badRetain).isNotEmpty())
    }

    @Test fun dualExampleHasExplicitIndependentRoutesAndBothWireFormats() {
        val dual = PanelJson.decode(File(assets, "dual-power.thingstalk.json").readText())
        assertEquals(PanelTransport.DUAL, dual.transport)
        assertEquals(5, dual.schemaVersion)
        assertEquals(dual, PanelJson.decode(PanelJson.encode(dual)))
        val binding = dual.bindings.single()
        assertEquals(PanelTransport.BLE, binding.selectedControl)
        assertEquals(PanelTransport.MQTT, binding.selectedState)
        assertFalse(binding.canRead)
        val ble = dual.payloadFormats.single { it.id == "blePower" }
        assertEquals("01", inspector.encode(ble, mapOf("power" to "true")).hex)
        assertEquals("false", inspector.decode(ble, "00", PayloadInputEncoding.HEX).fields.single().value)
        val mqtt = dual.payloadFormats.single { it.id == "mqttPower" }
        assertEquals("true", inspector.decode(mqtt, """{"power":true}""", PayloadInputEncoding.JSON_TEXT).fields.single().value)
        assertTrue(validatePanel(dual.copy(bindings = listOf(binding.copy(controlTransport = null)))).isNotEmpty())
        assertTrue(validatePanel(dual.copy(bindings = listOf(binding.copy(stateTransport = PanelTransport.DUAL)))).isNotEmpty())
        assertTrue(validatePanel(dual.copy(bindings = listOf(binding.copy(publish = binding.publish!!.copy(topic = "devices/#"))))).isNotEmpty())
    }

    @Test fun groupedChannelExamplesAndMuyuConfigurationImportWithoutLosingEitherSide() {
        for (file in listOf(File(assets, "dual-channels.thingstalk.json"), File(root, "docs/examples/muyu-dual-v6.thingstalk.json"))) {
            val panel = PanelJson.decode(file.readText())
            assertEquals(6, panel.schemaVersion)
            assertEquals(PanelTransport.BLE, panel.defaultChannel)
            assertEquals(panel, PanelJson.decode(PanelJson.encode(panel)))
            for (channel in listOf(PanelTransport.BLE, PanelTransport.MQTT)) {
                val active = panel.forActiveChannel(channel)
                assertEquals(emptyList(), validatePanel(active))
                assertEquals(panel.bindings.size, active.bindings.size)
                active.bindings.forEach { binding ->
                    if (binding.canWrite) assertEquals(channel, binding.selectedControl)
                    if (binding.canRead || binding.canObserve) assertEquals(channel, binding.selectedState)
                }
            }
            if (file.name.startsWith("muyu")) {
                assertEquals(10, panel.widgets.size)
                assertEquals(10, panel.bindings.size)
                assertEquals(6, panel.bindings.count { it.write != null && it.publish != null })
                assertEquals(8, panel.bindings.count { it.notify != null && it.subscribe != null })
            }
        }
    }
}
