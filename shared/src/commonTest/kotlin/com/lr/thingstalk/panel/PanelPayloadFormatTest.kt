package com.lr.thingstalk.panel

import com.lr.thingstalk.data.panel.BlePanelOperations
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.data.panel.defaultDemoPanel
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class PanelPayloadFormatTest {
    private val service = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val characteristic = "0000fff1-0000-1000-8000-00805f9b34fb"
    private val temperature = PanelCapability("temperature", "温度", CapabilityType.NUMBER, -100.0, 100.0, "℃")
    private val binary = PayloadFormat("sensor", "温度报文", fields = listOf(
        PayloadField("command", defaultValue = "1"),
        PayloadField("temperature", CodecType.INT16, scale = 0.01),
        PayloadField("open", CodecType.BOOLEAN, defaultValue = "false"),
    ), prefixHex = "AA", suffixHex = "55")
    private fun op(format: String = binary.id, field: String? = "temperature") = BleOperationBinding(service, characteristic, payload = PayloadReference(format, field))
    private fun panel(format: PayloadFormat = binary, capability: PanelCapability = temperature, operation: BleOperationBinding = op(format.id)) = PanelDefinition(
        schemaVersion = 3, title = "设备面板", capabilities = listOf(capability), payloadFormats = listOf(format),
        bindings = listOf(PanelBinding(capability.id, read = operation, write = operation, notify = operation)),
        widgets = listOf(PanelWidget("value", WidgetType.VALUE, capability.label, capability.id)),
    )
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()

    @Test fun namedBinaryFormatRoundTripsThroughConfigurationJson() {
        val definition = panel()
        assertTrue(validatePanel(definition).isEmpty(), validatePanel(definition).joinToString())
        assertEquals(definition, PanelJson.decode(PanelJson.encode(definition)))
        assertEquals(3, PanelJson.decode(PanelJson.encode(definition)).schemaVersion)
    }

    @Test fun fullBinaryFrameReadsNotifiesAndWritesBoundField() = runTest {
        val connection = Connection().apply { response = bytes(0xaa, 1, 0x2e, 0xfb, 1, 0x55) }
        val operations = BlePanelOperations(connection)
        assertEquals("-12.34", operations.read(panel(), "temperature"))
        assertEquals("-12.34", operations.observe(panel(), "temperature").first())
        operations.write(panel(), "temperature", "-12.34")
        // Unbound write fields use explicit defaults, not the last received state.
        assertContentEquals(bytes(0xaa, 1, 0x2e, 0xfb, 0, 0x55), connection.writes.single())
    }

    @Test fun byteOrderAndBooleanCodewordsAreDeclaredPerField() = runTest {
        val format = binary.copy(fields = listOf(
            PayloadField("command", defaultValue = "1"),
            PayloadField("temperature", CodecType.INT16, PanelByteOrder.BIG_ENDIAN, 0.01),
            PayloadField("open", CodecType.BOOLEAN, falseHex = "f0 00", trueHex = "f0 01", defaultValue = "true"),
        ))
        val connection = Connection().apply { response = bytes(0xaa, 1, 0xfb, 0x2e, 0xf0, 1, 0x55) }
        val operations = BlePanelOperations(connection)
        assertEquals("-12.34", operations.read(panel(format), "temperature"))
        operations.write(panel(format), "temperature", "-12.34")
        assertContentEquals(connection.response, connection.writes.single())
    }

    @Test fun malformedFrameAndAnyInvalidFieldAreRejected() = runTest {
        val connection = Connection()
        val operations = BlePanelOperations(connection)
        listOf(
            bytes(0xbb, 1, 0x2e, 0xfb, 1, 0x55),
            bytes(0xaa, 1, 0x2e, 0xfb, 1, 0x56),
            bytes(0xaa, 1, 0x2e, 0xfb, 1),
            bytes(0xaa, 1, 0x2e, 0xfb, 1, 0x55, 0),
            bytes(0xaa, 1, 0x2e, 0xfb, 2, 0x55),
        ).forEach { payload ->
            connection.response = payload
            assertFailsWith<IllegalArgumentException> { operations.read(panel(), "temperature") }
            assertIs<PanelObservation.InvalidPayload>(operations.observeEvents(panel(), "temperature").first { it !is PanelObservation.Ready })
        }
        assertTrue(connection.writes.isEmpty())
    }

    @Test fun writeRejectsOutOfRangeOrUnrepresentableValuesBeforeSending() = runTest {
        val connection = Connection()
        val operations = BlePanelOperations(connection)
        listOf("101", "-101", "23.555", "NaN", "1e-400", " 12").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { operations.write(panel(), "temperature", value) }
        }
        assertTrue(connection.writes.isEmpty())
    }

    @Test fun everyUnboundWriteFieldRequiresAnExplicitValidDefault() {
        val missing = binary.copy(fields = binary.fields.map { if (it.id == "command") it.copy(defaultValue = null) else it })
        assertTrue(validatePanel(panel(missing)).any { "默认值" in it })
        listOf("256", "-1", "1.5", "1e-400").forEach { value ->
            val invalid = binary.copy(fields = binary.fields.map { if (it.id == "command") it.copy(defaultValue = value) else it })
            assertTrue(validatePanel(panel(invalid)).isNotEmpty(), value)
        }
        val readOnly = panel(missing).let { it.copy(bindings = it.bindings.map { binding -> binding.copy(write = null) }) }
        assertTrue(validatePanel(readOnly).isEmpty())
    }

    @Test fun structuredActionUsesOnlyDefaultsAndIgnoresCallerValue() = runTest {
        val format = binary.copy(fields = binary.fields.map { if (it.id == "temperature") it.copy(defaultValue = "20.5") else it })
        val action = PanelCapability("sample", "采样", CapabilityType.ACTION)
        val definition = panel(format, action, op(format.id, null)).copy(
            bindings = listOf(PanelBinding(action.id, write = op(format.id, null))),
            widgets = listOf(PanelWidget("sample", WidgetType.BUTTON, "采样", action.id)),
        )
        val connection = Connection()
        assertTrue(validatePanel(definition).isEmpty(), validatePanel(definition).joinToString())
        BlePanelOperations(connection).write(definition, action.id, "DE AD BE EF")
        assertContentEquals(bytes(0xaa, 1, 0x02, 0x08, 0, 0x55), connection.writes.single())
        assertTrue(validatePanel(definition.copy(bindings = listOf(definition.bindings.single().copy(actionValue = "ff")))).isNotEmpty())
        assertTrue(validatePanel(definition.copy(bindings = listOf(PanelBinding(action.id, write = op(format.id, "temperature"))))).isNotEmpty())
    }

    private val jsonFormat = PayloadFormat("state", "JSON 状态", PayloadEncoding.JSON, fields = listOf(
        PayloadField("temperature", CodecType.FLOAT32),
        PayloadField("enabled", CodecType.BOOLEAN, defaultValue = "false"),
        PayloadField("command", CodecType.UINT8, defaultValue = "2"),
    ))

    @Test fun jsonUsesTypedEngineeringValuesWithoutBinaryScaling() = runTest {
        val connection = Connection().apply { response = """{"temperature":-12.34,"enabled":true,"command":2}""".encodeToByteArray() }
        val definition = panel(jsonFormat)
        val operations = BlePanelOperations(connection)
        assertEquals("-12.34", operations.read(definition, "temperature"))
        assertEquals("-12.34", operations.observe(definition, "temperature").first())
        operations.write(definition, "temperature", "23.125")
        val written = Json.parseToJsonElement(connection.writes.single().decodeToString()).jsonObject
        assertEquals(23.125, written.getValue("temperature").jsonPrimitive.double)
        assertFalse(written.getValue("temperature").jsonPrimitive.isString)
        assertEquals(false, written.getValue("enabled").jsonPrimitive.boolean)
        assertEquals("2", written.getValue("command").jsonPrimitive.content)
    }

    @Test fun jsonRejectsMissingExtraDuplicateOrWrongTypedFields() = runTest {
        val connection = Connection()
        val operations = BlePanelOperations(connection)
        listOf(
            """{"temperature":"1","enabled":true,"command":2}""",
            """{"temperature":1,"enabled":"true","command":2}""",
            """{"temperature":1,"enabled":true}""",
            """{"temperature":1,"enabled":true,"command":2,"extra":0}""",
            """{"temperature":1,"temperature":2,"enabled":true,"command":2}""",
            """{"temperature":1,"\u0074emperature":2,"enabled":true,"command":2}""",
            """{"temperature":null,"enabled":true,"command":2}""",
            """{"temperature":{},"enabled":true,"command":2}""",
            """{"temperature":1,"enabled":true,"command":256}""",
            """{"temperature":1,"enabled":true,"command":2.5}""",
            """{"temperature":1e400,"enabled":true,"command":2}""",
            """{"temperature":1e-400,"enabled":true,"command":2}""",
            """{"temperature":01,"enabled":true,"command":2}""",
            "[]",
        ).forEach { source ->
            connection.response = source.encodeToByteArray()
            assertFailsWith<IllegalArgumentException>(source) { operations.read(panel(jsonFormat), "temperature") }
        }
        connection.response = bytes(0xc3, 0x28)
        assertFailsWith<IllegalArgumentException> { operations.read(panel(jsonFormat), "temperature") }
    }

    @Test fun jsonBinaryOnlySettingsAreRejectedBeforeReadOrWrite() = runTest {
        val invalid = listOf(
            jsonFormat.copy(prefixHex = "AA"),
            jsonFormat.copy(suffixHex = "55"),
            jsonFormat.copy(fields = jsonFormat.fields.map { if (it.id == "temperature") it.copy(scale = 0.01) else it }),
            jsonFormat.copy(fields = jsonFormat.fields.map { if (it.id == "temperature") it.copy(byteOrder = PanelByteOrder.BIG_ENDIAN) else it }),
            jsonFormat.copy(fields = jsonFormat.fields.map { if (it.id == "enabled") it.copy(falseHex = "ff") else it }),
        )
        val connection = Connection()
        invalid.forEach { format ->
            assertFailsWith<IllegalArgumentException> { BlePanelOperations(connection).read(panel(format), "temperature") }
            assertFailsWith<IllegalArgumentException> { BlePanelOperations(connection).write(panel(format), "temperature", "1") }
        }
        assertEquals(0, connection.reads)
        assertTrue(connection.writes.isEmpty())
    }

    @Test fun referencesCannotBeMissingAmbiguousOrCombinedWithInlineParameters() {
        val bad = listOf(
            panel(operation = op("missing")),
            panel(operation = op(field = "missing")),
            panel(operation = op(field = null)),
            panel(operation = op(field = "open")),
            panel(operation = op().copy(codec = CodecType.INT16)),
            panel(operation = op().copy(scale = 0.01)),
            panel(operation = op().copy(readOffset = 1)),
            panel(operation = op().copy(writePrefixHex = "AA")),
            panel().copy(payloadFormats = listOf(binary, binary)),
            panel(binary.copy(fields = binary.fields + binary.fields.first())),
            panel(binary.copy(fields = binary.fields.map { it.copy(codec = CodecType.HEX) })),
        )
        bad.forEach { assertTrue(validatePanel(it).isNotEmpty()) }
    }

    @Test fun frameAndNegotiatedWriteLimitsAreCheckedForTheWholePayload() = runTest {
        val connection = Connection().apply { limit = 5 }
        assertFailsWith<IllegalArgumentException> { BlePanelOperations(connection).write(panel(), "temperature", "1") }
        assertTrue(connection.writes.isEmpty())
        assertTrue(validatePanel(panel(binary.copy(prefixHex = "AA".repeat(510)))).isNotEmpty())
        val longJson = jsonFormat.copy(fields = jsonFormat.fields + (1..20).map { PayloadField("field-${it.toString().padStart(40, '0')}", defaultValue = "1") })
        connection.limit = 512
        assertTrue(validatePanel(panel(longJson)).isEmpty())
        assertFailsWith<IllegalArgumentException> { BlePanelOperations(connection).write(panel(longJson), "temperature", "1") }
        assertTrue(connection.writes.isEmpty())
    }

    @Test fun versionsOneAndTwoKeepInlineSemanticsAndRejectPayloadReferences() {
        for (version in 1..2) {
            val legacy = defaultDemoPanel().copy(schemaVersion = version)
            assertEquals(legacy, PanelJson.decode(PanelJson.encode(legacy)))
            assertTrue(validatePanel(panel().copy(schemaVersion = version)).isNotEmpty())
            val noFormats = panel().copy(schemaVersion = version, payloadFormats = emptyList())
            assertTrue(validatePanel(noFormats).isNotEmpty())
        }
    }

    @Test fun oversizedConfigurationCannotExportAnUnimportableFile() {
        val formats = (1..32).map { index -> PayloadFormat("format-$index", "格式 $index", fields = (1..32).map { field -> PayloadField("field-$field") }) }
        val definition = PanelDefinition(schemaVersion = 3, title = "大配置", payloadFormats = formats)
        assertTrue(validatePanel(definition).isEmpty())
        assertFailsWith<IllegalArgumentException> { PanelJson.encode(definition) }
    }

    @Test fun transportCancellationStillPropagates() = runTest {
        val connection = object : BleConnection by Connection() {
            override suspend fun read(characteristic: BleCharacteristicId): ByteArray = throw CancellationException("cancel")
        }
        assertFailsWith<CancellationException> { BlePanelOperations(connection).read(panel(), "temperature") }
    }

    private inner class Connection : BleConnection {
        override val state = MutableStateFlow(BleConnectionState.CONNECTED)
        var response = byteArrayOf()
        var reads = 0
        var limit = 512
        val writes = mutableListOf<ByteArray>()
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun discoverServices() = listOf(BleService(service, listOf(BleCharacteristic(BleCharacteristicId(service, characteristic), BleProperty.entries.toSet(), emptyList()))))
        override suspend fun read(characteristic: BleCharacteristicId): ByteArray { reads++; return response.copyOf() }
        override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) { writes += value.copyOf() }
        override suspend fun maximumWriteLength(mode: BleWriteMode) = limit
        override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = flow { onReady(); emit(response.copyOf()) }
        override fun close() = Unit
    }
}
