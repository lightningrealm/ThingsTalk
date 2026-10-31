package com.lr.thingstalk.panel

import com.lr.thingstalk.data.panel.*
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.mqtt.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.preparePanelImport
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class PanelTextInputTest {
    private val service = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val characteristic = "0000fff1-0000-1000-8000-00805f9b34fb"
    private val capability = PanelCapability("text", "Text", CapabilityType.TEXT, maxBytes = 64)
    private val format = PayloadFormat("message", "Message", PayloadEncoding.JSON,
        listOf(PayloadField("text", CodecType.UTF8), PayloadField("opcode", defaultValue = "7")))
    private fun panel(mqtt: Boolean = false) = PanelDefinition(schemaVersion = if (mqtt) 4 else 3, title = "Text",
        transport = if (mqtt) PanelTransport.MQTT else PanelTransport.BLE,
        capabilities = listOf(capability), widgets = listOf(PanelWidget("input", WidgetType.TEXT_INPUT, "Message", "text")),
        payloadFormats = listOf(format), bindings = listOf(if (mqtt)
            PanelBinding("text", publish = MqttOperationBinding("device/message", PayloadReference("message", "text")))
        else PanelBinding("text", write = BleOperationBinding(service, characteristic, CodecType.UTF8))))

    @Test fun validatesUtf8BytesWithoutTrimmingOrSilentReplacement() {
        assertNull(panelTextProblem("你好", 6))
        assertNotNull(panelTextProblem("你好", 5))
        assertNull(panelTextProblem("😀", 4))
        for (value in listOf("", "a\u0000b", "\uD800")) assertNotNull(panelTextProblem(value, 512))
        val value = " 你好\n😀 "
        val bytes = PanelValueCodec.encode(3, capability, panel().bindings.single().write!!, value)
        assertContentEquals(value.encodeToByteArray(), bytes)
    }

    @Test fun jsonStringsAreEscapedAndRemainStrings() {
        val value = "你好\n\"quote\"\\\\"
        val bytes = PanelPayloadCodec.encode(panel(true), capability, PayloadReference("message", "text"), value)
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(value, json.getValue("text").jsonPrimitive.content)
        assertTrue(json.getValue("text").jsonPrimitive.isString)
        assertEquals(7, json.getValue("opcode").jsonPrimitive.int)
        assertEquals(value, PanelPayloadCodec.decodeFields(format, bytes)["text"])
        for (bad in listOf("123", "null", "true", "{}")) assertFailsWith<IllegalArgumentException> {
            PanelPayloadCodec.decodeFields(format, "{\"text\":$bad,\"opcode\":7}".encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            PanelPayloadCodec.encode(panel(true), capability, PayloadReference("message", "text"), "你".repeat(22))
        }
    }

    @Test fun framedTextInspectorRoundTripsAndRejectsAmbiguousBinaryLayouts() {
        val binary = PayloadFormat("text", "Text", fields = listOf(PayloadField("text", CodecType.UTF8)), prefixHex = "AA", suffixHex = "0A")
        val inspector = DefaultPayloadInspector()
        val result = inspector.encode(binary, mapOf("text" to "你好"))
        assertEquals("AA E4 BD A0 E5 A5 BD 0A", result.hex)
        assertEquals(6, result.fields.single().byteCount)
        assertEquals(1, result.fields.single().byteOffset)
        assertTrue(validatePayloadFormats(listOf(binary.copy(fields = binary.fields + PayloadField("extra")))).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { inspector.decode(binary, "AA FF 0A", PayloadInputEncoding.HEX) }
        assertFailsWith<IllegalArgumentException> { inspector.encode(binary, mapOf("text" to "x".repeat(511))) }
    }

    @Test fun schemaRejectsTextStateBindingsAndKeepsDualWebPublishing() {
        val ble = panel()
        assertEquals(ble, PanelJson.decode(PanelJson.encode(ble)))
        assertTrue(validatePanel(ble.copy(schemaVersion = 2)).isNotEmpty())
        assertTrue(validatePanel(ble.copy(bindings = listOf(ble.bindings.single().copy(read = ble.bindings.single().write)))).isNotEmpty())
        assertTrue(validatePanel(ble.copy(widgets = listOf(ble.widgets.single().copy(type = WidgetType.VALUE)))).isNotEmpty())
        val mqtt = panel(true)
        assertTrue(validatePanel(mqtt.copy(bindings = listOf(mqtt.bindings.single().copy(subscribe = mqtt.bindings.single().publish)))).isNotEmpty())
        val dual = ble.asDual().asChannelPanel().copy(bindings = listOf(ble.bindings.single().copy(
            publish = mqtt.bindings.single().publish, controlTransport = PanelTransport.BLE)))
        val web = preparePanelImport(PanelJson.encode(dual), false)
        assertTrue(web.mqttOnly)
        assertEquals(WidgetType.TEXT_INPUT, web.panel.widgets.single().type)
        assertEquals(mqtt.bindings.single().publish, web.panel.bindings.single().publish)
    }

    @Test fun realBleExecutorChecksMtuAndDoesNotSplitOrReplayText() = runTest {
        val writes = mutableListOf<ByteArray>()
        val connection = object : BleConnection {
            override val state = MutableStateFlow(BleConnectionState.CONNECTED)
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
            override fun close() = Unit
            override suspend fun discoverServices() = listOf(BleService(service, listOf(
                BleCharacteristic(BleCharacteristicId(service, characteristic), setOf(BleProperty.WRITE), emptyList()))))
            override suspend fun read(characteristic: BleCharacteristicId): ByteArray = error("Text must not read")
            override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = error("Text must not subscribe")
            override suspend fun maximumWriteLength(mode: BleWriteMode) = 20
            override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) { writes += value.copyOf() }
        }
        val operations = BlePanelOperations(connection)
        operations.write(panel(), "text", "你好")
        assertContentEquals("你好".encodeToByteArray(), writes.single())
        assertFailsWith<IllegalArgumentException> { operations.write(panel(), "text", "你".repeat(7)) }
        assertEquals(1, writes.size)
    }

    @Test fun realMqttExecutorPublishesOnceWithoutRetention() = runTest {
        val published = mutableListOf<MqttMessage>()
        val repository = object : MqttRepository {
            override val state = MutableStateFlow(MqttConnectionState(MqttConnectionStatus.CONNECTED))
            override val subscriptions = MutableStateFlow<List<MqttSubscription>>(emptyList())
            override val messages = MutableSharedFlow<MqttMessage>()
            override suspend fun connect(config: MqttConnectionConfig) = Unit
            override suspend fun disconnect() = Unit
            override suspend fun subscribe(topicFilter: String, qos: MqttQos) = error("Text must not subscribe")
            override suspend fun unsubscribe(topicFilter: String) = Unit
            override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean) { published += MqttMessage(topic, payload, qos, retain) }
            override fun close() = Unit
        }
        MqttPanelOperations(repository).write(panel(true), "text", "你好")
        val message = published.single()
        assertFalse(message.retained)
        assertEquals("device/message", message.topic)
        assertEquals("你好", Json.parseToJsonElement(message.payload.decodeToString()).jsonObject.getValue("text").jsonPrimitive.content)
    }
}
