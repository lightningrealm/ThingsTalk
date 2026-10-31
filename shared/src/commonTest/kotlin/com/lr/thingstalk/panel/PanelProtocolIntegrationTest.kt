@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.panel

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.data.device.DeviceStorage
import com.lr.thingstalk.data.device.JsonDeviceRepository
import com.lr.thingstalk.data.panel.BlePanelOperations
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.PanelViewModel
import com.lr.thingstalk.presentation.panel.editablePanelDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class PanelProtocolIntegrationTest {
    @Test fun fileImportAndEditorPreserveSchemaAndArchiveReloadKeepsDeviceAssociation() = runTest {
        val storage = object : DeviceStorage {
            var source: String? = null
            override fun load() = source
            override fun save(source: String) { this.source = source }
        }
        val repository = JsonDeviceRepository(storage) { "test-device" }
        val target = com.lr.thingstalk.domain.device.BleDeviceTarget("peripheral-id", "My device")
        val device = repository.create("My panel", target)
        val imported = editablePanelDefinition(PanelJson.decode(PanelJson.encode(protocolPanel())))
        assertEquals(3, imported.schemaVersion)
        assertEquals(protocolPanel(), imported)
        repository.savePanel(device.id, imported)
        val restored = JsonDeviceRepository(storage).devices.value.single()
        assertEquals(imported, restored.panel)
        assertEquals(target, restored.bleTarget)
        // A portable panel never exports the physical device's platform identifier.
        assertFalse(PanelJson.encode(imported).contains("peripheral-id"))
    }

    @Test fun twoWidgetsDecodeOneFrameAndUserWriteUsesDeclaredCommandFormat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val connection = ProtocolConnection()
            val viewModel = PanelViewModel(protocolPanel(), BlePanelOperations(connection), PanelJson, connection)
            store.put("panel", viewModel)
            viewModel.connect()
            runCurrent()
            assertEquals(25.0, viewModel.state.value.values["temperature"]?.toDouble())
            assertNull(viewModel.state.value.values["power"])
            connection.frames.emit(byteArrayOf(0xAA.toByte(), 0xC4.toByte(), 0x09, 1, 0x55))
            runCurrent()
            assertEquals("true", viewModel.state.value.values["power"])
            assertTrue(connection.writes.isEmpty(), "Connecting or importing must not send control commands")
            connection.frames.emit(byteArrayOf(0xAA.toByte(), 0x2E, 0xFB.toByte(), 0, 0x55))
            runCurrent()
            assertEquals(-12.34, viewModel.state.value.values["temperature"]?.toDouble())
            assertEquals("false", viewModel.state.value.values["power"])
            viewModel.write("power", "true")
            runCurrent()
            assertEquals(1, connection.writes.size)
            assertContentEquals(byteArrayOf(0xA1.toByte(), 2, 1, 0xEE.toByte()), connection.writes.single())
            // No readback was configured for power; a successful write cannot manufacture a new state.
            assertEquals("false", viewModel.state.value.values["power"])
        } finally {
            store.clear()
            try { advanceUntilIdle() } finally { Dispatchers.resetMain() }
        }
    }

    @Test fun loadingAValidFileOnlyCreatesADraftUntilExplicitSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val original = PanelDefinition(schemaVersion = 2, title = "Empty")
            var saves = 0
            val viewModel = PanelViewModel(original, codec = PanelJson, onSave = { saves++ })
            store.put("panel", viewModel)
            val imported = viewModel.importDefinition(PanelJson.encode(protocolPanel()))
            assertEquals(original, viewModel.state.value.definition)
            assertEquals(0, saves)
            assertTrue(viewModel.updateDefinition(imported).isEmpty())
            runCurrent()
            assertEquals(1, saves)
            assertEquals(imported, viewModel.state.value.definition)
        } finally {
            store.clear()
            try { advanceUntilIdle() } finally { Dispatchers.resetMain() }
        }
    }
}

private const val protocolService = "0000fff0-0000-1000-8000-00805f9b34fb"
private const val protocolCharacteristic = "0000fff1-0000-1000-8000-00805f9b34fb"

private fun protocolPanel(): PanelDefinition {
    fun operation(format: String, field: String) = BleOperationBinding(protocolService, protocolCharacteristic,
        payload = PayloadReference(format, field))
    return PanelDefinition(
        schemaVersion = 3, title = "Protocol panel",
        capabilities = listOf(
            PanelCapability("temperature", "Temperature", CapabilityType.NUMBER, -100.0, 200.0, "℃"),
            PanelCapability("power", "Power", CapabilityType.BOOLEAN),
        ),
        widgets = listOf(PanelWidget("temperature-card", WidgetType.VALUE, "Temperature", "temperature"),
            PanelWidget("power-switch", WidgetType.SWITCH, "Power", "power")),
        payloadFormats = listOf(
            PayloadFormat("telemetry", "Telemetry", fields = listOf(
                PayloadField("temperature", CodecType.INT16, scale = 0.01),
                PayloadField("power", CodecType.BOOLEAN),
            ), prefixHex = "AA", suffixHex = "55"),
            PayloadFormat("set-power", "Set power", fields = listOf(
                PayloadField("command", defaultValue = "2"),
                PayloadField("power", CodecType.BOOLEAN),
            ), prefixHex = "A1", suffixHex = "EE"),
        ),
        bindings = listOf(
            PanelBinding("temperature", read = operation("telemetry", "temperature"), notify = operation("telemetry", "temperature")),
            PanelBinding("power", write = operation("set-power", "power"), notify = operation("telemetry", "power")),
        ),
    )
}

private class ProtocolConnection : BleConnection {
    override val state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    val writes = mutableListOf<ByteArray>()
    override suspend fun connect() { state.value = BleConnectionState.CONNECTED }
    override suspend fun disconnect() { state.value = BleConnectionState.DISCONNECTED }
    override suspend fun discoverServices() = listOf(BleService(protocolService, listOf(
        BleCharacteristic(BleCharacteristicId(protocolService, protocolCharacteristic), BleProperty.entries.toSet(), emptyList()),
    )))
    override suspend fun read(characteristic: BleCharacteristicId) = byteArrayOf(0xAA.toByte(), 0xC4.toByte(), 0x09, 1, 0x55)
    override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) { writes += value.copyOf() }
    override suspend fun maximumWriteLength(mode: BleWriteMode) = 20
    override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit) = frames
    override fun close() { state.value = BleConnectionState.DISCONNECTED }
}
