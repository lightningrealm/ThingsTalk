package com.lr.thingstalk.panel

import com.lr.thingstalk.data.panel.BlePanelOperations
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.data.panel.PanelValueCodec
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PanelValueCodecTest {
    private val service = "0000180f-0000-1000-8000-00805f9b34fb"
    private val characteristic = "00002a19-0000-1000-8000-00805f9b34fb"
    private val number = PanelCapability("value", "数值", CapabilityType.NUMBER, -1e40, 1e40)
    private val boolean = PanelCapability("value", "开关", CapabilityType.BOOLEAN)
    private fun op(codec: CodecType) = BleOperationBinding(service, characteristic, codec)
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun encode(operation: BleOperationBinding, value: String, capability: PanelCapability = number, version: Int = 2) =
        PanelValueCodec.encode(version, capability, operation, value)
    private fun decode(operation: BleOperationBinding, value: ByteArray, capability: PanelCapability = number, version: Int = 2) =
        PanelValueCodec.decode(version, capability, operation, value)
    private fun definition(
        operation: BleOperationBinding,
        capability: PanelCapability = number,
        write: Boolean = false,
        version: Int = 2,
    ) = PanelDefinition(
        schemaVersion = version, title = "设备面板", capabilities = listOf(capability),
        bindings = listOf(if (write) PanelBinding("value", write = operation) else PanelBinding("value", read = operation, notify = operation)),
    )

    @Test fun signedIntegerExtremaRoundTripForBothByteOrders() {
        val cases = listOf(CodecType.INT8 to listOf("-128", "127"), CodecType.INT16 to listOf("-32768", "32767"), CodecType.INT32 to listOf("-2147483648", "2147483647"))
        cases.forEach { (codec, values) -> PanelByteOrder.entries.forEach { order -> values.forEach { value ->
            val operation = op(codec).copy(byteOrder = order)
            assertEquals(value, decode(operation, encode(operation, value)))
        } } }
        assertContentEquals(bytes(0xff, 0xfe), encode(op(CodecType.INT16).copy(byteOrder = PanelByteOrder.BIG_ENDIAN), "-2"))
        assertContentEquals(bytes(0xfe, 0xff), encode(op(CodecType.INT16), "-2"))
    }

    @Test fun unsignedIntegerExtremaNeverBecomeNegative() {
        listOf(CodecType.UINT8 to "255", CodecType.UINT16 to "65535", CodecType.UINT32 to "4294967295").forEach { (codec, maximum) ->
            assertEquals(maximum, decode(op(codec), encode(op(codec), maximum)))
            assertEquals("0", decode(op(codec), encode(op(codec), "0")))
        }
        assertContentEquals(bytes(0x78, 0x56, 0x34, 0x12), encode(op(CodecType.UINT32), "305419896"))
        assertContentEquals(bytes(0x12, 0x34, 0x56, 0x78), encode(op(CodecType.UINT32).copy(byteOrder = PanelByteOrder.BIG_ENDIAN), "305419896"))
    }

    @Test fun scaleBiasAndOffsetDecodeSensorFrameAndEncodeExactRawValue() {
        val operation = op(CodecType.INT16).copy(scale = 0.1, bias = -40.0)
        assertEquals("23.5", decode(operation.copy(readOffset = 2), bytes(0xaa, 0x03, 0x7b, 0x02, 0xcc)))
        assertContentEquals(bytes(0x7b, 0x02), encode(operation, "23.5"))
        assertContentEquals(bytes(0x03), encode(op(CodecType.UINT8).copy(scale = 0.1), "0.3"))
        assertEquals("-5", decode(op(CodecType.INT8).copy(scale = -0.5, bias = 5.0), bytes(20)))
    }

    @Test fun truncatedOrOverflowedOffsetsFailWithoutReadingAnotherField() {
        val operation = op(CodecType.UINT32).copy(readOffset = 2)
        listOf(byteArrayOf(), bytes(1, 2), bytes(1, 2, 3, 4, 5)).forEach {
            assertFailsWith<IllegalArgumentException> { decode(operation, it) }
        }
        assertFailsWith<IllegalArgumentException> { decode(operation.copy(readOffset = Int.MAX_VALUE), bytes(1, 2, 3, 4)) }
        assertFailsWith<IllegalArgumentException> { decode(operation.copy(readOffset = -1), bytes(1, 2, 3, 4)) }
    }

    @Test fun fractionalIntegerInputsAreRejectedInsteadOfRounded() {
        listOf("1.2", "-1.9", "4294967294.0001").forEach {
            assertFailsWith<IllegalArgumentException> { encode(op(CodecType.UINT32), it) }
        }
        assertFailsWith<IllegalArgumentException> { encode(op(CodecType.INT16).copy(scale = 0.1), "23.55") }
    }

    @Test fun numericBoundariesAreCheckedAfterScaleInversion() {
        listOf(
            CodecType.UINT8 to listOf("-1", "256"), CodecType.INT8 to listOf("-129", "128"),
            CodecType.UINT16 to listOf("-1", "65536"), CodecType.INT16 to listOf("-32769", "32768"),
            CodecType.UINT32 to listOf("-1", "4294967296"), CodecType.INT32 to listOf("-2147483649", "2147483648"),
        ).forEach { (codec, values) -> values.forEach { assertFailsWith<IllegalArgumentException> { encode(op(codec), it) } } }
        assertFailsWith<IllegalArgumentException> { encode(op(CodecType.UINT8).copy(scale = 0.1), "30") }
    }

    @Test fun nonFiniteValuesAndInvalidNumbersFail() {
        listOf("NaN", "Infinity", "-Infinity", "1e400", "hello").forEach {
            assertFailsWith<IllegalArgumentException> { encode(op(CodecType.FLOAT32), it) }
        }
        assertFailsWith<IllegalArgumentException> { decode(op(CodecType.FLOAT32), bytes(0, 0, 0x80, 0x7f)) }
        assertFailsWith<IllegalArgumentException> { decode(op(CodecType.FLOAT32), bytes(0, 0, 0xc0, 0x7f)) }
        assertFailsWith<IllegalArgumentException> { encode(op(CodecType.FLOAT32), "3.5e38") }
        assertFailsWith<IllegalArgumentException> { encode(op(CodecType.FLOAT32), "1e-50") }
    }

    @Test fun float32RespectsEndianAndScale() {
        val operation = op(CodecType.FLOAT32)
        assertContentEquals(bytes(0, 0, 0xc0, 0x3f), encode(operation, "1.5"))
        assertContentEquals(bytes(0x3f, 0xc0, 0, 0), encode(operation.copy(byteOrder = PanelByteOrder.BIG_ENDIAN), "1.5"))
        assertEquals("2", decode(operation.copy(scale = 2.0, bias = -1.0), bytes(0, 0, 0xc0, 0x3f)))
        assertEquals("-1.5", decode(operation, bytes(0, 0, 0xc0, 0xbf)))
    }

    @Test fun capabilityRangeAppliesToEngineeringUnitsInBothDirections() {
        val capability = number.copy(minimum = -40.0, maximum = 100.0)
        val operation = op(CodecType.UINT16).copy(scale = 0.1, bias = -40.0)
        assertFailsWith<IllegalArgumentException> { encode(operation, "101", capability) }
        assertFailsWith<IllegalArgumentException> { decode(operation, bytes(0x82, 0x05), capability) }
        assertEquals("100", decode(operation, bytes(0x78, 0x05), capability))
    }

    @Test fun customBooleanFramesRequireExactMatchAtOffset() {
        val operation = op(CodecType.BOOLEAN).copy(falseHex = "AA 00", trueHex = "aa ff")
        assertContentEquals(bytes(0xaa, 0xff), encode(operation, "true", boolean))
        assertContentEquals(bytes(0xaa, 0), encode(operation, "false", boolean))
        assertEquals("true", decode(operation.copy(readOffset = 1), bytes(0x80, 0xaa, 0xff, 0x90), boolean))
        assertEquals("false", decode(operation, bytes(0xaa, 0, 0xff), boolean))
        assertFailsWith<IllegalArgumentException> { decode(operation, bytes(0xaa, 0x01), boolean) }
        assertFailsWith<IllegalArgumentException> { decode(operation, bytes(0xaa), boolean) }
    }

    @Test fun prefixAndSuffixWrapEncodedNumericAndBooleanPayloads() {
        val numeric = op(CodecType.UINT16).copy(writePrefixHex = "AA 55", writeSuffixHex = "0D 0A")
        assertContentEquals(bytes(0xaa, 0x55, 0x34, 0x12, 0x0d, 0x0a), encode(numeric, "4660"))
        val bool = op(CodecType.BOOLEAN).copy(falseHex = "00 ff", trueHex = "01 fe", writePrefixHex = "aa", writeSuffixHex = "bb")
        assertContentEquals(bytes(0xaa, 1, 0xfe, 0xbb), encode(bool, "true", boolean))
    }

    @Test fun versionOneRetainsExactSingleByteAndStrictIntegerSemantics() {
        val capability = number.copy(minimum = 0.0, maximum = 255.0)
        assertEquals("255", decode(op(CodecType.UINT8), bytes(255), capability, 1))
        assertContentEquals(bytes(7), encode(op(CodecType.UINT8), "007", capability, 1))
        listOf("7.0", " 7", "-1", "256").forEach {
            assertFailsWith<IllegalArgumentException> { encode(op(CodecType.UINT8), it, capability, 1) }
        }
        assertFailsWith<IllegalArgumentException> { decode(op(CodecType.UINT8), bytes(1, 2), capability, 1) }
        assertFailsWith<IllegalArgumentException> { decode(op(CodecType.BOOLEAN), bytes(2), boolean, 1) }
        assertFailsWith<IllegalArgumentException> { decode(op(CodecType.BOOLEAN), bytes(1, 0), boolean, 1) }
        assertEquals("1", decode(op(CodecType.UINT8), bytes(1, 2), capability, 2))
    }

    @Test fun versionOneRejectsAllNewFeaturesWhileOldJsonRoundTrips() {
        val capability = number.copy(minimum = 0.0, maximum = 255.0)
        val base = definition(op(CodecType.UINT8), capability, version = 1)
        assertEquals(base, PanelJson.decode(PanelJson.encode(base)))
        listOf(
            op(CodecType.INT16), op(CodecType.UINT8).copy(byteOrder = PanelByteOrder.BIG_ENDIAN),
            op(CodecType.UINT8).copy(readOffset = 1), op(CodecType.UINT8).copy(scale = 0.1),
            op(CodecType.UINT8).copy(bias = 1.0), op(CodecType.UINT8).copy(writePrefixHex = "aa"),
            op(CodecType.UINT8).copy(writeSuffixHex = "aa"), op(CodecType.UINT8).copy(trueHex = "ff"),
        ).forEach { assertTrue(validatePanel(definition(it, capability, version = 1)).isNotEmpty()) }
        assertTrue(validatePanel(base.copy(capabilities = listOf(capability.copy(step = 0.5)))).isNotEmpty())
        assertTrue(validatePanel(base.copy(widgets = listOf(PanelWidget("input", WidgetType.NUMBER_INPUT, "数值", "value")))).isNotEmpty())
    }

    @Test fun versionTwoSerializesAllCodecParametersAndNewWidget() {
        val binding = op(CodecType.INT16).copy(byteOrder = PanelByteOrder.BIG_ENDIAN, scale = 0.1, bias = -40.0, writePrefixHex = "AA", writeSuffixHex = "55")
        val value = definition(binding, number.copy(step = 0.1), write = true).copy(widgets = listOf(PanelWidget("input", WidgetType.NUMBER_INPUT, "温度", "value")))
        assertTrue(validatePanel(value).isEmpty())
        assertEquals(value, PanelJson.decode(PanelJson.encode(value)))
    }

    @Test fun operationSpecificParametersAndInvalidTransformsAreRejected() {
        listOf(
            op(CodecType.INT16).copy(writePrefixHex = "AA"), op(CodecType.INT16).copy(writeSuffixHex = "AA"),
            op(CodecType.INT16).copy(readOffset = -1), op(CodecType.INT16).copy(scale = 0.0),
            op(CodecType.INT16).copy(scale = Double.NaN), op(CodecType.INT16).copy(bias = Double.POSITIVE_INFINITY),
            op(CodecType.INT16).copy(falseHex = "ff"), op(CodecType.INT16).copy(trueHex = "ff"),
            op(CodecType.INT16).copy(writeMode = PanelWriteMode.WITHOUT_RESPONSE),
        ).forEach { assertTrue(validatePanel(definition(it)).isNotEmpty(), it.toString()) }
        assertTrue(validatePanel(definition(op(CodecType.INT16).copy(readOffset = 1), write = true)).isNotEmpty())
        listOf("F", "gg", "aa".repeat(513)).forEach {
            assertTrue(validatePanel(definition(op(CodecType.UINT8).copy(writePrefixHex = it), write = true)).isNotEmpty())
        }
    }

    @Test fun booleanValidationRejectsAmbiguousOrMalformedCodewords() {
        listOf(
            op(CodecType.BOOLEAN).copy(falseHex = "aa", trueHex = "AA"),
            op(CodecType.BOOLEAN).copy(falseHex = "00", trueHex = "00ff"),
            op(CodecType.BOOLEAN).copy(trueHex = ""), op(CodecType.BOOLEAN).copy(trueHex = "F"),
            op(CodecType.BOOLEAN).copy(falseHex = "00".repeat(33), trueHex = "01".repeat(33)),
            op(CodecType.BOOLEAN).copy(scale = 0.1), op(CodecType.BOOLEAN).copy(bias = 1.0),
            op(CodecType.BOOLEAN).copy(byteOrder = PanelByteOrder.BIG_ENDIAN),
        ).forEach { assertTrue(validatePanel(definition(it, boolean)).isNotEmpty(), it.toString()) }
    }

    @Test fun actionRemainsFixedHexAndRejectsMeaninglessNewParameters() {
        val action = PanelCapability("value", "指令", CapabilityType.ACTION)
        val base = definition(op(CodecType.HEX), action, write = true).copy(bindings = listOf(PanelBinding("value", write = op(CodecType.HEX), actionValue = "aa 00")))
        assertTrue(validatePanel(base).isEmpty())
        listOf(op(CodecType.HEX).copy(writePrefixHex = "ff"), op(CodecType.HEX).copy(scale = 2.0), op(CodecType.HEX).copy(byteOrder = PanelByteOrder.BIG_ENDIAN)).forEach {
            assertTrue(validatePanel(base.copy(bindings = listOf(base.bindings.single().copy(write = it)))).isNotEmpty())
        }
    }

    @Test fun sliderRequiresFiniteAlignedManageableStepsButInputKeepsFullRange() {
        fun panel(capability: PanelCapability, type: WidgetType) = definition(op(CodecType.FLOAT32), capability, write = true).copy(widgets = listOf(PanelWidget("widget", type, "数值", "value")))
        val allowed = number.copy(minimum = -50.0, maximum = 50.0, step = 0.1)
        assertTrue(validatePanel(panel(allowed, WidgetType.SLIDER)).isEmpty())
        listOf(
            allowed.copy(step = 0.3), allowed.copy(step = 0.0001), allowed.copy(step = 0.0), allowed.copy(step = Double.NaN),
            allowed.copy(minimum = 1.0, maximum = 1.0), allowed.copy(minimum = -Double.MAX_VALUE, maximum = Double.MAX_VALUE),
        ).forEach { assertTrue(validatePanel(panel(it, WidgetType.SLIDER)).isNotEmpty()) }
        assertTrue(validatePanel(panel(number, WidgetType.NUMBER_INPUT)).isEmpty())
        assertTrue(validatePanel(panel(number.copy(minimum = 1.0, maximum = 1.0), WidgetType.NUMBER_INPUT)).isEmpty())
        assertTrue(validatePanel(panel(allowed.copy(step = -1.0), WidgetType.NUMBER_INPUT)).isNotEmpty())
    }

    @Test fun executorWritesExactFramedBytesAndDecodesReadAndNotify() = runTest {
        val transport = RecordingConnection()
        val operations = BlePanelOperations(transport)
        val read = op(CodecType.INT16).copy(readOffset = 1, byteOrder = PanelByteOrder.BIG_ENDIAN, scale = 0.1, bias = -40.0)
        val write = read.copy(readOffset = 0, writePrefixHex = "AA", writeSuffixHex = "55")
        val panel = definition(read).copy(bindings = listOf(PanelBinding("value", read = read, write = write, notify = read)))
        transport.response = bytes(0x99, 0x02, 0x7b, 0xff)
        assertEquals("23.5", operations.read(panel, "value"))
        assertEquals("23.5", operations.observe(panel, "value").first())
        operations.write(panel, "value", "23.5")
        assertContentEquals(bytes(0xaa, 0x02, 0x7b, 0x55), transport.writes.single())
    }

    @Test fun executorUsesConfiguredBooleanBytesAndWriteMode() = runTest {
        val transport = RecordingConnection()
        val operation = op(CodecType.BOOLEAN).copy(writeMode = PanelWriteMode.WITHOUT_RESPONSE, trueHex = "A1 FF", falseHex = "A1 00", writePrefixHex = "EE")
        BlePanelOperations(transport).write(definition(operation, boolean, write = true), "value", "true")
        assertContentEquals(bytes(0xee, 0xa1, 0xff), transport.writes.single())
        assertEquals(BleWriteMode.WITHOUT_RESPONSE, transport.lastWriteMode)
    }

    @Test fun executorChecksCompleteFrameAgainstNegotiatedAndSchemaLimits() = runTest {
        val transport = RecordingConnection().apply { limit = 3 }
        val operations = BlePanelOperations(transport)
        val operation = op(CodecType.UINT16).copy(writePrefixHex = "AA", writeSuffixHex = "55")
        assertFailsWith<IllegalArgumentException> { operations.write(definition(operation, write = true), "value", "100") }
        val oversized = operation.copy(writePrefixHex = "AA".repeat(510))
        assertTrue(validatePanel(definition(oversized, write = true)).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { operations.write(definition(oversized, write = true), "value", "100") }
        assertTrue(transport.writes.isEmpty())
    }

    @Test fun duplicateServicesAreRejectedEvenIfOnlyOneContainsTargetCharacteristic() = runTest {
        val transport = RecordingConnection()
        transport.services = transport.services + BleService(service.uppercase(), emptyList())
        val operations = BlePanelOperations(transport)
        assertFailsWith<IllegalArgumentException> { operations.read(definition(op(CodecType.UINT8)), "value") }
        assertFailsWith<IllegalArgumentException> { operations.write(definition(op(CodecType.UINT8), write = true), "value", "1") }
        assertEquals(0, transport.reads)
        assertTrue(transport.writes.isEmpty())
    }

    @Test fun duplicateCharacteristicsAndMissingPropertiesFailBeforeTransport() = runTest {
        val transport = RecordingConnection()
        val unique = transport.services.single()
        transport.services = listOf(unique.copy(characteristics = unique.characteristics + unique.characteristics))
        assertFailsWith<IllegalArgumentException> { BlePanelOperations(transport).read(definition(op(CodecType.UINT8)), "value") }
        transport.services = listOf(unique.copy(characteristics = unique.characteristics.map { it.copy(properties = setOf(BleProperty.READ)) }))
        assertFailsWith<IllegalArgumentException> { BlePanelOperations(transport).write(definition(op(CodecType.UINT8), write = true), "value", "1") }
        assertEquals(0, transport.reads)
        assertTrue(transport.writes.isEmpty())
    }

    @Test fun defaultNumericRangeRemainsZeroTo255AndTransformsCannotOverflow() {
        val defaultRange = PanelCapability("value", "数值", CapabilityType.NUMBER)
        assertFailsWith<IllegalArgumentException> { encode(op(CodecType.INT16), "-1", defaultRange) }
        assertFailsWith<IllegalArgumentException> { encode(op(CodecType.UINT32), "256", defaultRange) }
        assertFailsWith<IllegalArgumentException> { decode(op(CodecType.UINT8).copy(scale = 1e308), bytes(255)) }
        assertFailsWith<IllegalArgumentException> { encode(op(CodecType.INT16).copy(scale = Double.MIN_VALUE), "1") }
    }

    @Test fun exactly512ByteFramesAreAcceptedBut513AreRejected() {
        val operation = op(CodecType.UINT8).copy(writePrefixHex = "aa".repeat(511))
        assertTrue(validatePanel(definition(operation, write = true)).isEmpty())
        assertEquals(512, encode(operation, "1").size)
        val oversized = operation.copy(writeSuffixHex = "bb")
        assertTrue(validatePanel(definition(oversized, write = true)).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { encode(oversized, "1") }
    }

    @Test fun versionTwoFixedActionIgnoresCallerSuppliedPayload() = runTest {
        val transport = RecordingConnection()
        val action = PanelCapability("value", "命令", CapabilityType.ACTION)
        val panel = definition(op(CodecType.HEX), action, write = true).copy(bindings = listOf(PanelBinding("value", write = op(CodecType.HEX), actionValue = "aa 01")))
        BlePanelOperations(transport).write(panel, "value", "DE AD")
        assertContentEquals(bytes(0xaa, 1), transport.writes.single())
    }

    @Test fun numericTextUsesPortableDecimalSyntax() {
        listOf(" 1", "1 ", "\t1", "1\n", "0x1.0p0", "0X1P2", "1f", "1D", "1_000", "1,0", "", ".", "1e", "--1").forEach {
            assertFailsWith<IllegalArgumentException>(it) { encode(op(CodecType.FLOAT32), it) }
        }
        assertContentEquals(bytes(100, 0), encode(op(CodecType.UINT16), "+1e2"))
        assertContentEquals(bytes(0xfe, 0xff), encode(op(CodecType.INT16), "-2E+0"))
        assertEquals("0.5", decode(op(CodecType.FLOAT32), encode(op(CodecType.FLOAT32), ".5")))
        assertEquals("-0.5", decode(op(CodecType.FLOAT32), encode(op(CodecType.FLOAT32), "-.5")))
        assertEquals("1", decode(op(CodecType.FLOAT32), encode(op(CodecType.FLOAT32), "1.")))
    }

    @Test fun tinyNonzeroIntegerValuesNeverBecomeZero() {
        listOf("1e-16", "-1e-16", "5e-324", "-5e-324", "1e-400", "-1e-400").forEach {
            assertFailsWith<IllegalArgumentException>(it) { encode(op(CodecType.INT32), it) }
        }
        assertFailsWith<IllegalArgumentException> { encode(op(CodecType.UINT8).copy(scale = 1e300), "1e-300") }
        assertContentEquals(bytes(0), encode(op(CodecType.UINT8), "0"))
        assertContentEquals(bytes(0), encode(op(CodecType.UINT8), "-0e-400"))
        assertContentEquals(bytes(0), encode(op(CodecType.UINT8).copy(bias = 1.25), "1.25"))
        assertContentEquals(bytes(3), encode(op(CodecType.UINT8).copy(scale = 0.1), "0.3"))
    }

    @Test fun floatQuantizationMustNotOverflowTheEngineeringResult() {
        val capability = number.copy(minimum = -Double.MAX_VALUE, maximum = Double.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> {
            encode(op(CodecType.FLOAT32).copy(scale = 1e306), Double.MAX_VALUE.toString(), capability)
        }
    }

    private inner class RecordingConnection : BleConnection {
        override val state = MutableStateFlow(BleConnectionState.CONNECTED)
        var response = byteArrayOf(1)
        var reads = 0
        var limit = 512
        var lastWriteMode: BleWriteMode? = null
        val writes = mutableListOf<ByteArray>()
        var services = listOf(BleService(service, listOf(BleCharacteristic(BleCharacteristicId(service, characteristic), BleProperty.entries.toSet(), emptyList()))))
        override suspend fun connect() { state.value = BleConnectionState.CONNECTED }
        override suspend fun disconnect() { state.value = BleConnectionState.DISCONNECTED }
        override suspend fun discoverServices() = services
        override suspend fun read(characteristic: BleCharacteristicId): ByteArray { reads++; return response.copyOf() }
        override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) { writes += value.copyOf(); lastWriteMode = mode }
        override suspend fun maximumWriteLength(mode: BleWriteMode) = limit
        override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = flow { onReady(); emit(response.copyOf()) }
        override fun close() { state.value = BleConnectionState.DISCONNECTED }
    }
}
