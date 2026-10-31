package com.lr.thingstalk.panel

import com.lr.thingstalk.data.panel.DefaultPayloadInspector
import com.lr.thingstalk.data.panel.PanelPayloadCodec
import com.lr.thingstalk.domain.panel.*
import kotlin.test.*

class PayloadInspectorTest {
    private val inspector: PayloadInspector = DefaultPayloadInspector()
    private val status = PayloadFormat("status", "设备状态", fields = listOf(
        PayloadField("temperature", CodecType.INT16, scale = 0.01),
        PayloadField("power", CodecType.BOOLEAN),
    ), prefixHex = "AA", suffixHex = "55")
    private val jsonFormat = PayloadFormat("state", "JSON 状态", PayloadEncoding.JSON, listOf(
        PayloadField("temperature", CodecType.FLOAT32),
        PayloadField("power", CodecType.BOOLEAN),
        PayloadField("command", defaultValue = "2"),
    ))

    @Test fun binaryDecodeShowsAllFieldsWithOffsetsIncludingFramePrefix() {
        val result = inspector.decode(status, "AA\n2E FB 00\t55", PayloadInputEncoding.HEX)
        assertEquals("AA 2E FB 00 55", result.hex)
        assertEquals(5, result.byteCount)
        assertNull(result.jsonText)
        assertEquals(listOf(
            InspectedPayloadField("temperature", CodecType.INT16, "-12.34", 1, 2),
            InspectedPayloadField("power", CodecType.BOOLEAN, "false", 3, 1),
        ), result.fields)
    }

    @Test fun binaryEncodeUsesSameWireBytesAsBoundDeviceWrite() {
        val format = status.copy(fields = status.fields.map { if (it.id == "power") it.copy(defaultValue = "true") else it })
        val result = inspector.encode(format, mapOf("temperature" to "25", "power" to "true"))
        assertEquals("AA C4 09 01 55", result.hex)
        val capability = PanelCapability("temperature", "温度", CapabilityType.NUMBER, -100.0, 100.0)
        val definition = PanelDefinition(3, "面板", payloadFormats = listOf(format))
        val binding = BleOperationBinding("", "", payload = PayloadReference("status", "temperature"))
        val actualWire = PanelPayloadCodec.encode(definition, capability, binding, "25")
        assertEquals(result.hex, actualWire.hex())
        assertEquals("25", result.fields.first().value)
    }

    @Test fun offsetsRespectBigEndianAndMultipleByteBooleanCodes() {
        val format = status.copy(prefixHex = "AA BB", fields = listOf(
            PayloadField("temperature", CodecType.INT16, PanelByteOrder.BIG_ENDIAN, 0.01),
            PayloadField("power", CodecType.BOOLEAN, falseHex = "FF 00", trueHex = "FF 01"),
        ))
        val result = inspector.encode(format, mapOf("temperature" to "-12.34", "power" to "true"))
        assertEquals("AA BB FB 2E FF 01 55", result.hex)
        assertEquals(listOf(2, 4), result.fields.map { it.byteOffset })
        assertEquals(listOf(2, 2), result.fields.map { it.byteCount })
    }

    @Test fun floatPreviewShowsActualQuantizedValue() {
        val format = PayloadFormat("float", "浮点数", fields = listOf(PayloadField("value", CodecType.FLOAT32)))
        val result = inspector.encode(format, mapOf("value" to "0.1"))
        assertEquals("CD CC CC 3D", result.hex)
        assertEquals(0.1f.toDouble(), result.fields.single().value.toDouble())
    }

    @Test fun jsonTextAndHexInputsProduceTheSameTypedFieldsWithoutFixedOffsets() {
        val input = """{"temperature":23.125,"power":true,"command":2}"""
        val textResult = inspector.decode(jsonFormat, input, PayloadInputEncoding.JSON_TEXT)
        val hexResult = inspector.decode(jsonFormat, input.encodeToByteArray().hex(), PayloadInputEncoding.HEX)
        assertEquals(textResult, hexResult)
        assertEquals(input, textResult.jsonText)
        assertTrue(textResult.fields.all { it.byteOffset == null && it.byteCount == null })
        val encoded = inspector.encode(jsonFormat, mapOf("temperature" to "23.125", "power" to "true", "command" to "2"))
        assertEquals(input, encoded.jsonText)
        assertEquals(listOf("23.125", "true", "2"), encoded.fields.map { it.value })
    }

    @Test fun malformedFramesAndInvalidJsonRemainErrors() {
        listOf("AA C4 09 02 55", "AB C4 09 01 55", "AA C4 09 01", "AA C4 09 01 55 00", "AA C4 09 01 5", "AA GG 09 01 55", "").forEach {
            assertFailsWith<IllegalArgumentException>(it) { inspector.decode(status, it, PayloadInputEncoding.HEX) }
        }
        listOf(
            """{"temperature":1,"power":true}""",
            """{"temperature":1,"power":true,"command":2,"extra":1}""",
            """{"temperature":1,"temperature":2,"power":true,"command":2}""",
            """{"temperature":1,"power":"true","command":2}""",
            """{"temperature":1,"power":true,"command":256}""",
        ).forEach {
            assertFailsWith<IllegalArgumentException>(it) { inspector.decode(jsonFormat, it, PayloadInputEncoding.JSON_TEXT) }
        }
        assertFailsWith<IllegalArgumentException> { inspector.decode(jsonFormat, "C3 28", PayloadInputEncoding.HEX) }
        assertFailsWith<IllegalArgumentException> { inspector.decode(jsonFormat, "\uD800", PayloadInputEncoding.JSON_TEXT) }
        assertFailsWith<IllegalArgumentException> { inspector.decode(status, "{}", PayloadInputEncoding.JSON_TEXT) }
    }

    @Test fun encodingRequiresExplicitFieldsAndRejectsLossyIntegers() {
        val format = status.copy(fields = status.fields.map { if (it.id == "power") it.copy(defaultValue = "true") else it })
        assertFailsWith<IllegalArgumentException> { inspector.encode(format, mapOf("temperature" to "25")) }
        assertFailsWith<IllegalArgumentException> { inspector.encode(format, mapOf("temperature" to "25", "power" to "true", "extra" to "1")) }
        listOf("25.555", "327.68", "NaN", "1e-400").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { inspector.encode(format, mapOf("temperature" to value, "power" to "true")) }
        }
    }

    @Test fun unfinishedOrIncompatibleFormatCannotProducePreview() {
        listOf(
            status.copy(fields = emptyList()),
            status.copy(fields = status.fields + status.fields.first()),
            status.copy(fields = listOf(PayloadField("data", CodecType.HEX))),
            status.copy(prefixHex = "A"),
            jsonFormat.copy(prefixHex = "AA"),
            status.copy(fields = status.fields.map { it.copy(scale = Double.NaN) }),
        ).forEach { format ->
            assertFailsWith<IllegalArgumentException> { inspector.decode(format, "00", PayloadInputEncoding.HEX) }
        }
    }

    @Test fun inputAndEncodedPayloadSizesAreBounded() {
        assertFailsWith<IllegalArgumentException> { inspector.decode(status, " ".repeat(MAX_INSPECTOR_SOURCE_CHARS + 1), PayloadInputEncoding.HEX) }
        assertFailsWith<IllegalArgumentException> { inspector.encode(status, mapOf("temperature" to "1".repeat(MAX_INSPECTOR_VALUE_CHARS + 1), "power" to "false")) }
        val format = PayloadFormat("large", "大 JSON", PayloadEncoding.JSON, (1..20).map { PayloadField("field-${it.toString().padStart(40, '0')}") })
        assertTrue(validatePayloadFormats(listOf(format)).isEmpty())
        assertFailsWith<IllegalArgumentException> { inspector.encode(format, format.fields.associate { it.id to "1" }) }
    }

    private fun ByteArray.hex() = joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase() }
}
