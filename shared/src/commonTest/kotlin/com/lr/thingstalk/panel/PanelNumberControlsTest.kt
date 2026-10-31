package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.data.panel.BlePanelOperations
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PanelNumberControlsTest {
    @Test fun decimalGridSurvivesFloatPositionAndEncodesExactInteger() = runTest {
        val capability = number(0.0, 1.0, 0.1)
        for (index in 0..10) {
            val selected = panelSliderValue(capability, index / 10f)
            assertEquals(index / 10.0, selected)
            assertWrite(capability, selected, CodecType.UINT8, 0.1, byteArrayOf(index.toByte()))
        }
        assertEquals("0.3", panelNumberText(panelSliderValue(capability, 0.3f)))
    }

    @Test fun negativeRangeProducesSignedLittleEndianBytes() = runTest {
        val capability = number(-10.5, 10.5, 0.5)
        for (index in 0..42) {
            val selected = panelSliderValue(capability, index / 42f)
            val raw = index - 21
            assertEquals(raw / 2.0, selected)
            assertWrite(capability, selected, CodecType.INT16, 0.5,
                byteArrayOf(raw.toByte(), (raw shr 8).toByte()))
        }
    }

    @Test fun largeUnsigned32BitRangeKeepsEachUnitInsteadOfFloatPrecision() = runTest {
        val minimum = 4_294_967_000L
        val capability = number(minimum.toDouble(), 4_294_967_295.0, 1.0)
        for (index in listOf(0, 1, 127, 294, 295)) {
            val expected = minimum + index
            val selected = panelSliderValue(capability, index / 295f)
            assertEquals(expected.toDouble(), selected)
            assertWrite(capability, selected, CodecType.UINT32, 1.0,
                ByteArray(4) { ((expected ushr (8 * it)) and 0xff).toByte() })
        }
    }

    @Test fun stepBelowFifteenDecimalPlacesIsNotRoundedToZero() = runTest {
        val capability = number(0.0, 1e-15, 1e-16)
        assertEquals(5e-16, panelSliderValue(capability, 0.5f))
        for (index in 0..10) {
            val selected = panelSliderValue(capability, index / 10f)
            assertWrite(capability, selected, CodecType.UINT8, 1e-16, byteArrayOf(index.toByte()))
        }
    }

    @Test fun tinyNegativeRangeRetainsGridAndZeroCrossing() = runTest {
        val capability = number(-1e-20, 1e-20, 1e-21)
        for (index in 0..20) {
            val raw = index - 10
            val selected = panelSliderValue(capability, index / 20f)
            assertWrite(capability, selected, CodecType.INT8, 1e-21, byteArrayOf(raw.toByte()))
        }
        assertEquals(0.0, panelSliderValue(capability, 0.5f))
    }

    @Test fun subnormalStepDoesNotOverflowDecimalCleanupOrEraseValues() = runTest {
        val step = Double.MIN_VALUE
        val capability = number(0.0, step * 10, step)
        for (index in 0..10) {
            val selected = panelSliderValue(capability, index / 10f)
            assertEquals(step * index, selected)
            assertWrite(capability, selected, CodecType.UINT8, step, byteArrayOf(index.toByte()))
        }
    }

    @Test fun scaledOffsetGridRemovesBinaryTailsBeforeReverseEncoding() = runTest {
        val capability = number(-0.2, 1.0, 0.3)
        val expected = listOf(-0.2, 0.1, 0.4, 0.7, 1.0)
        for (index in 0..4) {
            val selected = panelSliderValue(capability, index / 4f)
            assertEquals(expected[index], selected)
            assertWrite(capability, selected, CodecType.UINT8, 0.3, byteArrayOf(index.toByte()), bias = -0.2)
        }
    }

    @Test fun normalizedPositionIsClampedAtBothEnds() {
        val capability = number(-2.5, 2.5, 0.5)
        assertEquals(-2.5, panelSliderValue(capability, -0.1f))
        assertEquals(2.5, panelSliderValue(capability, 1.1f))
    }

    private fun number(minimum: Double, maximum: Double, step: Double) =
        PanelCapability("number", "数值", CapabilityType.NUMBER, minimum, maximum, step = step)

    private suspend fun assertWrite(
        capability: PanelCapability,
        value: Double,
        codec: CodecType,
        scale: Double,
        expected: ByteArray,
        bias: Double = 0.0,
    ) {
        val connection = RecordingConnection()
        val operation = BleOperationBinding(id.serviceUuid, id.characteristicUuid, codec, scale = scale, bias = bias)
        val definition = PanelDefinition(schemaVersion = 2, title = "滑块回归测试", capabilities = listOf(capability),
            bindings = listOf(PanelBinding(capability.id, write = operation)),
            widgets = listOf(PanelWidget("slider", WidgetType.SLIDER, "数值", capability.id)))
        assertEquals(emptyList(), validatePanel(definition))
        BlePanelOperations(connection).write(definition, capability.id, panelNumberText(value))
        assertEquals(1, connection.writes.size)
        assertContentEquals(expected, connection.writes.single())
    }

    private class RecordingConnection : BleConnection {
        override val state = flowOf(BleConnectionState.CONNECTED)
        val writes = mutableListOf<ByteArray>()
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun discoverServices() = listOf(BleService(id.serviceUuid,
            listOf(BleCharacteristic(id, setOf(BleProperty.WRITE), emptyList()))))
        override suspend fun read(characteristic: BleCharacteristicId): ByteArray = error("Not read in this test")
        override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) {
            assertEquals(id, characteristic)
            writes += value.copyOf()
        }
        override suspend fun maximumWriteLength(mode: BleWriteMode) = 512
        override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    private companion object {
        val id = BleCharacteristicId("0000180f-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb")
    }
}
