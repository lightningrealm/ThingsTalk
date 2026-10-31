@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.device

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.data.ble.InMemoryBleTraffic
import com.lr.thingstalk.data.ble.TracingBleConnection
import com.lr.thingstalk.data.panel.*
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.device.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.domain.mqtt.*
import com.lr.thingstalk.presentation.device.DeviceBleDiagnostics
import com.lr.thingstalk.presentation.device.DeviceWorkspaceViewModel
import com.lr.thingstalk.presentation.panel.PanelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Exercises composition: panel, debug tools and traffic must operate on the same owned transport. */
class DeviceWorkspaceViewModelTest {
    private val power = BleCharacteristicId(DemoPanelGatt.SERVICE, DemoPanelGatt.POWER)

    @Test fun panelAndDebugShareOneConnectionAndOneTrafficHistory() = withWorkspace { fixture ->
        val workspace = fixture.workspace
        workspace.connect("first")
        runCurrent()
        val connection = fixture.scanner.created.single().second
        val panel = assertNotNull(workspace.panel("first"))
        val debug = assertNotNull(workspace.debug("first"))
        val traffic = assertNotNull(workspace.traffic("first"))
        assertTrue(panel.state.value.ready)
        assertTrue(debug.state.value.connected)
        assertEquals(1, fixture.traceCount)
        assertEquals(3, connection.demo.activeSubscriptions)

        debug.setNotifications(power, true)
        runCurrent()
        assertEquals(4, connection.demo.activeSubscriptions)
        traffic.clear()
        panel.write("power", "false")
        runCurrent()
        assertEquals("false", panel.state.value.values["power"])
        assertEquals("00", debug.state.value.values[power])
        debug.write(power, "01", BleWriteMode.WITH_RESPONSE)
        runCurrent()
        assertEquals("true", panel.state.value.values["power"])
        assertEquals("01", debug.state.value.values[power])
        assertEquals(listOf("00", "01"), traffic.entries.value.filter { it.direction == BleTrafficDirection.TX }.map { it.hex })
        assertEquals(2, traffic.entries.value.count { it.operation == "write.gatt_acknowledged" })
        assertEquals(1, fixture.scanner.created.size)
        assertEquals(2, connection.writes)
    }

    @Test fun disconnectCancelsBothConsumersAndReconnectPreservesLogsWhileReplacingTransport() = withWorkspace { fixture ->
        val workspace = fixture.workspace
        workspace.connect("first")
        runCurrent()
        val first = fixture.scanner.created.single().second
        val panel = assertNotNull(workspace.panel("first"))
        val debug = assertNotNull(workspace.debug("first"))
        val traffic = assertNotNull(workspace.traffic("first"))
        debug.setNotifications(power, true)
        runCurrent()
        assertEquals(4, first.demo.activeSubscriptions)
        val retainedId = traffic.entries.value.first().id

        workspace.disconnect("first")
        runCurrent()
        assertEquals(0, first.demo.activeSubscriptions)
        assertEquals(1, first.disconnects)
        assertFalse(panel.state.value.ready)
        assertFalse(debug.state.value.connected)
        assertTrue(debug.state.value.observing.isEmpty())
        assertEquals(4, traffic.entries.value.count { it.operation == "notifications.cancelled" })

        workspace.connect("first")
        runCurrent()
        val replacement = fixture.scanner.created.last().second
        assertEquals(2, fixture.scanner.created.size)
        assertNotSame(first, replacement)
        assertEquals(1, first.closes)
        assertEquals(0, first.demo.activeSubscriptions)
        assertEquals(3, replacement.demo.activeSubscriptions)
        assertSame(traffic, workspace.traffic("first"))
        assertTrue(traffic.entries.value.any { it.id == retainedId })
        assertEquals(2, traffic.entries.value.count { it.operation == "connect.completed" })
        assertTrue(panel.state.value.ready)
        assertTrue(debug.state.value.connected)
    }

    @Test fun devicesHaveIsolatedConnectionsValuesAndLogs() = withWorkspace { fixture ->
        val workspace = fixture.workspace
        workspace.connect("first")
        workspace.connect("second")
        runCurrent()
        assertEquals(listOf("peripheral-first", "peripheral-second"), fixture.scanner.created.map { it.first })
        val firstPanel = assertNotNull(workspace.panel("first"))
        val secondPanel = assertNotNull(workspace.panel("second"))
        val firstTraffic = assertNotNull(workspace.traffic("first"))
        val secondTraffic = assertNotNull(workspace.traffic("second"))
        assertNotSame(firstTraffic, secondTraffic)
        firstTraffic.clear()
        secondTraffic.clear()

        assertNotNull(workspace.debug("second")).write(power, "00", BleWriteMode.WITHOUT_RESPONSE)
        runCurrent()
        assertEquals("true", firstPanel.state.value.values["power"])
        assertEquals("false", secondPanel.state.value.values["power"])
        assertTrue(firstTraffic.entries.value.isEmpty())
        assertEquals("00", secondTraffic.entries.value.single { it.direction == BleTrafficDirection.TX }.hex)
        assertTrue(secondTraffic.entries.value.any { it.operation == "write.sent" })
        workspace.disconnect("first")
        runCurrent()
        assertFalse(firstPanel.state.value.ready)
        assertTrue(secondPanel.state.value.ready)
        assertEquals(0, fixture.scanner.created.first().second.demo.activeSubscriptions)
        assertEquals(3, fixture.scanner.created.last().second.demo.activeSubscriptions)
    }

    @Test fun renameRetainsRuntimeButReassociationAndDeleteDisposeIt() = withWorkspace { fixture ->
        val workspace = fixture.workspace
        workspace.connect("first")
        runCurrent()
        val connection = fixture.scanner.created.single().second
        val panel = assertNotNull(workspace.panel("first"))
        val debug = assertNotNull(workspace.debug("first"))
        val traffic = assertNotNull(workspace.traffic("first"))
        debug.setNotifications(power, true)
        runCurrent()
        workspace.rename("first", "Renamed device")
        runCurrent()
        assertEquals("Renamed device", workspace.devices.value.first().name)
        assertSame(panel, workspace.panel("first"))
        assertSame(debug, workspace.debug("first"))
        assertSame(traffic, workspace.traffic("first"))
        assertEquals(4, connection.demo.activeSubscriptions)

        workspace.associate("first", BleDeviceTarget("replacement-peripheral", "Replacement"))
        runCurrent()
        assertEquals(1, connection.closes)
        assertEquals(0, connection.demo.activeSubscriptions)
        assertNotSame(panel, workspace.panel("first"))
        assertNotSame(debug, workspace.debug("first"))
        val replacementTraffic = assertNotNull(workspace.traffic("first"))
        assertNotSame(traffic, replacementTraffic)
        assertTrue(replacementTraffic.entries.value.isEmpty())
        workspace.connect("first")
        runCurrent()
        val replacement = fixture.scanner.created.last()
        assertEquals("replacement-peripheral", replacement.first)
        assertEquals(3, replacement.second.demo.activeSubscriptions)
        workspace.delete("first")
        runCurrent()
        assertEquals(1, replacement.second.closes)
        assertEquals(0, replacement.second.demo.activeSubscriptions)
        assertNull(workspace.panel("first"))
        assertNull(workspace.debug("first"))
        assertNull(workspace.traffic("first"))
    }

    @Test fun clearingWorkspaceClosesEachOwnedConnectionAndScannerOnce() = withWorkspace { fixture ->
        val workspace = fixture.workspace
        workspace.connect("first")
        workspace.connect("second")
        runCurrent()
        assertNotNull(workspace.debug("first")).setNotifications(power, true)
        runCurrent()
        assertEquals(7, fixture.scanner.created.sumOf { it.second.demo.activeSubscriptions })
        fixture.store.clear()
        runCurrent()
        assertEquals(1, fixture.scanner.closes)
        fixture.scanner.created.forEach { (_, connection) ->
            assertEquals(1, connection.closes)
            assertEquals(0, connection.demo.activeSubscriptions)
        }
    }

    @Test fun dualBleReconnectClearsOldDisconnectErrorButNewDisconnectStillReports() = withWorkspace { fixture ->
        val workspace = fixture.workspace
        workspace.promoteToDual("first"); runCurrent()
        workspace.connect("first"); runCurrent()
        val panel = assertNotNull(workspace.panel("first"))
        assertTrue(PanelTransport.BLE in panel.state.value.connectedTransports)
        fixture.scanner.created.last().second.demo.disconnect(); runCurrent()
        assertEquals("BLE 连接已断开", workspace.status.value.error)
        assertFalse(PanelTransport.BLE in panel.state.value.connectedTransports)

        workspace.connect("first")
        assertNull(workspace.status.value.error)
        runCurrent()
        assertTrue(PanelTransport.BLE in panel.state.value.connectedTransports)
        assertNull(workspace.status.value.error)
        panel.write("power", "false"); runCurrent()
        assertEquals(1, fixture.scanner.created.last().second.writes)
        assertNull(workspace.status.value.error)

        fixture.scanner.created.last().second.demo.disconnect(); runCurrent()
        assertFalse(PanelTransport.BLE in panel.state.value.connectedTransports)
        assertEquals("BLE 连接已断开", workspace.status.value.error)
    }

    @Test fun dualConnectionsRemainIndependentAcrossReassociationAndUpgrade() = withWorkspace { fixture ->
        val workspace = fixture.workspace
        val old = workspace.panel("first")
        workspace.promoteToDual("first"); runCurrent()
        val panel = assertNotNull(workspace.panel("first"))
        assertNotSame(old, panel)
        assertEquals(PanelTransport.DUAL, panel.state.value.definition.transport)
        workspace.connect("first"); workspace.connectMqtt("first", "wss://example.test/mqtt", "", ""); runCurrent()
        assertEquals(setOf(PanelTransport.BLE, PanelTransport.MQTT), panel.state.value.connectedTransports)
        val connection = fixture.scanner.created.single().second
        workspace.associate("first", BleDeviceTarget("replacement", "Replacement")); runCurrent()
        assertSame(panel, workspace.panel("first"))
        assertEquals(setOf(PanelTransport.MQTT), panel.state.value.connectedTransports)
        assertEquals(1, connection.closes)
        assertEquals(0, fixture.mqtt.closes)
        workspace.connect("first"); runCurrent()
        assertEquals("replacement", fixture.scanner.created.last().first)
        workspace.disconnectMqtt("first"); runCurrent()
        assertEquals(setOf(PanelTransport.BLE), panel.state.value.connectedTransports)
        assertEquals(1, fixture.mqtt.closes)
        assertTrue(panel.state.value.values.isNotEmpty())
        workspace.disconnectAll(); runCurrent()
        assertTrue(panel.state.value.connectedTransports.isEmpty())
        assertFalse(panel.state.value.ready)
        assertEquals(1, fixture.scanner.created.last().second.closes)
    }

    @Test fun iosBackgroundKeepsBleAndNotificationsButStopsMqtt() = withWorkspace { fixture ->
        val workspace = fixture.workspace
        workspace.promoteToDual("first"); runCurrent()
        workspace.connect("first")
        workspace.connectMqtt("first", "wss://example.test/mqtt", "", ""); runCurrent()
        val panel = assertNotNull(workspace.panel("first"))
        val connection = fixture.scanner.created.single().second
        assertEquals(3, connection.demo.activeSubscriptions)
        workspace.enterBackground(keepBleSession = true); runCurrent()
        assertEquals(setOf(PanelTransport.BLE), panel.state.value.connectedTransports)
        assertEquals(0, connection.closes)
        assertEquals(0, connection.disconnects)
        assertEquals(3, connection.demo.activeSubscriptions)
        connection.demo.updateTemperature(42); runCurrent()
        assertEquals("42", panel.state.value.values["temperature"])
        assertEquals(0, connection.writes)
        assertEquals(1, fixture.mqtt.closes)
        workspace.enterBackground(keepBleSession = true); runCurrent()
        assertEquals(1, fixture.scanner.created.size)
        assertEquals(1, fixture.mqtt.closes)
        workspace.disconnect("first"); runCurrent()
        assertEquals(1, connection.closes)
        assertEquals(0, connection.demo.activeSubscriptions)
    }

    @Test fun backgroundPolicyAlsoKeepsSingleBleAndOtherPlatformsStillDisconnect() = withWorkspace { fixture ->
        val workspace = fixture.workspace
        workspace.connect("first"); runCurrent()
        val connection = fixture.scanner.created.single().second
        val panel = assertNotNull(workspace.panel("first"))
        workspace.enterBackground(keepBleSession = true); runCurrent()
        assertTrue(panel.state.value.ready)
        assertEquals(0, connection.disconnects)
        assertEquals(3, connection.demo.activeSubscriptions)
        workspace.enterBackground(keepBleSession = false); runCurrent()
        assertFalse(panel.state.value.ready)
        assertEquals(1, connection.disconnects)
        assertEquals(0, connection.demo.activeSubscriptions)
    }

    @Test fun selectedProfileCredentialsAndClientIdReachDeviceSessionUnchanged() = withWorkspace { fixture ->
        fixture.workspace.promoteToDual("first"); runCurrent()
        val config = MqttConnectionConfig("wss://broker.example/mqtt", "assigned-client", "test-user", "test-secret")
        fixture.workspace.connectMqtt("first", config); runCurrent()
        assertEquals(config, fixture.mqtt.lastConfig)
        fixture.workspace.connectMqtt("first", config.copy(clientId = "replacement")); runCurrent()
        assertEquals(config, fixture.mqtt.lastConfig)
        assertEquals(config.url, fixture.workspace.devices.value.first().mqttEndpoint)
    }

    private fun withWorkspace(block: suspend TestScope.(WorkspaceFixture) -> Unit): TestResult = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = WorkspaceFixture()
        try { block(fixture) }
        finally { fixture.store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}

private class WorkspaceFixture {
    val scanner = WorkspaceScanner()
    var traceCount = 0
    private val repository = WorkspaceRepository()
    val mqtt = WorkspaceMqtt()
    val workspace = DeviceWorkspaceViewModel(
        repository = repository,
        scanner = scanner,
        panelFactory = { device, save -> PanelViewModel(device.panel, codec = PanelJson, onSave = save) },
        operationsFactory = ::BlePanelOperations,
        dualOperationsFactory = ::DualPanelOperations,
        mqttFactory = { mqtt },
        mqttOperationsFactory = ::MqttPanelOperations,
        diagnosticsFactory = { scope ->
            val traffic = InMemoryBleTraffic()
            DeviceBleDiagnostics(traffic) { connection ->
                traceCount++
                TracingBleConnection(connection, traffic, scope)
            }
        },
    )
    val store = ViewModelStore().apply { put("workspace", workspace) }
}

private class WorkspaceScanner : BleScanner {
    val created = mutableListOf<Pair<String, WorkspaceConnection>>()
    var closes = 0
    override fun scan() = emptyFlow<BleScanDevice>()
    override fun connection(deviceId: String): BleConnection = WorkspaceConnection().also { created += deviceId to it }
    override fun close() { closes++ }
}

private class WorkspaceConnection(val demo: DemoPanelBleConnection = DemoPanelBleConnection()) : BleConnection by demo {
    var writes = 0
    var disconnects = 0
    var closes = 0
    override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) {
        writes++
        demo.write(characteristic, value, mode)
    }
    override suspend fun disconnect() { disconnects++; demo.disconnect() }
    override fun close() { closes++; demo.close() }
}

private class WorkspaceRepository : DeviceRepository {
    override val devices = MutableStateFlow(listOf("first", "second").map { id ->
        UserDevice(id, "$id device", defaultDemoPanel(), BleDeviceTarget("peripheral-$id", id))
    })
    override suspend fun create(name: String, target: BleDeviceTarget?): UserDevice = error("Not used by these tests")
    override suspend fun promoteToDual(id: String) = replace(id) { it.copy(panel = it.panel.asDual()) }
    override suspend fun saveMqttEndpoint(id: String, endpoint: String) = replace(id) { it.copy(mqttEndpoint = endpoint) }
    override suspend fun rename(id: String, name: String) = replace(id) { it.copy(name = name) }
    override suspend fun savePanel(id: String, panel: PanelDefinition) = replace(id) { it.copy(panel = panel) }
    override suspend fun associate(id: String, target: BleDeviceTarget?) = replace(id) { it.copy(bleTarget = target) }
    override suspend fun delete(id: String) { devices.update { devices -> devices.filterNot { it.id == id } } }
    private fun replace(id: String, transform: (UserDevice) -> UserDevice) {
        devices.update { devices -> devices.map { if (it.id == id) transform(it) else it } }
    }
}

private class WorkspaceMqtt : MqttRepository {
    var lastConfig: MqttConnectionConfig? = null
    var closes = 0
    override val state = MutableStateFlow(MqttConnectionState())
    override val subscriptions = MutableStateFlow<List<MqttSubscription>>(emptyList())
    override val messages = emptyFlow<MqttMessage>()
    override suspend fun connect(config: MqttConnectionConfig) { lastConfig = config; state.value = MqttConnectionState(MqttConnectionStatus.CONNECTED) }
    override suspend fun disconnect() { state.value = MqttConnectionState() }
    override suspend fun subscribe(topicFilter: String, qos: MqttQos) = Unit
    override suspend fun unsubscribe(topicFilter: String) = Unit
    override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean) = Unit
    override fun close() { closes++; state.value = MqttConnectionState() }
}
