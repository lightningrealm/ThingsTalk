package com.lr.thingstalk.presentation.device

import androidx.lifecycle.ViewModel
import com.lr.thingstalk.domain.activity.ActivityRepository
import com.lr.thingstalk.data.activity.*
import com.lr.thingstalk.domain.ble.BleTrafficEntry
import com.lr.thingstalk.data.ble.BleSetupPanelClient
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.lr.thingstalk.domain.ble.BleConnection
import com.lr.thingstalk.domain.ble.BleCharacteristicId
import com.lr.thingstalk.domain.panel.SetupPanel
import com.lr.thingstalk.domain.panel.SetupPanelSession
import com.lr.thingstalk.domain.ble.BleScanner
import com.lr.thingstalk.domain.ble.BleTraffic
import com.lr.thingstalk.domain.ble.BleService
import com.lr.thingstalk.domain.ble.BleConnectionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.lr.thingstalk.domain.device.*
import com.lr.thingstalk.domain.panel.PanelTransport
import com.lr.thingstalk.domain.mqtt.*
import kotlinx.coroutines.Job
import com.lr.thingstalk.domain.panel.PanelDefinition
import com.lr.thingstalk.domain.panel.PanelOperations
import com.lr.thingstalk.domain.panel.PanelDraftRepository
import com.lr.thingstalk.presentation.panel.PanelEditorViewModel
import com.lr.thingstalk.presentation.panel.PanelViewModel
import com.lr.thingstalk.presentation.ble.BleScanViewModel
import com.lr.thingstalk.presentation.ble.BleDebugViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceStatus(val busy: Boolean = false, val error: String? = null)

data class DeviceBleDiagnostics(val traffic: BleTraffic, val trace: (BleConnection) -> BleConnection) {
    var listen: (((BleTrafficEntry) -> Unit) -> Unit) = {}
    var protect: ((() -> List<BleCharacteristicId>) -> Unit) = {}
}

/** Each device owns one connection shared by its panel and debug tools. */
class DeviceWorkspaceViewModel(
    private val repository: DeviceRepository,
    private val activity: ActivityRepository? = null,
    private val scanner: BleScanner,
    private val panelFactory: (UserDevice, suspend (PanelDefinition) -> Unit) -> PanelViewModel,
    private val operationsFactory: (BleConnection) -> PanelOperations,
    private val diagnosticsFactory: (CoroutineScope) -> DeviceBleDiagnostics,
    private val drafts: PanelDraftRepository? = null,
    private val mqttFactory: (CoroutineScope) -> MqttRepository = { error("MQTT factory unavailable") },
    private val mqttOperationsFactory: (MqttRepository) -> PanelOperations = { error("MQTT operations unavailable") },
    private val dualOperationsFactory: (StateFlow<Map<PanelTransport, PanelOperations>>) -> PanelOperations = { error("Dual operations unavailable") },
) : ViewModel() {
    val devices = repository.devices
    val scan = BleScanViewModel(scanner)
    private val scanStore = ViewModelStore().apply { put("scan", scan) }
    private val mutableStatus = MutableStateFlow(WorkspaceStatus())
    val status = mutableStatus.asStateFlow()
    private data class DeviceSession(
        val store: ViewModelStore,
        val panel: PanelViewModel,
        val debug: BleDebugViewModel,
        val diagnostics: DeviceBleDiagnostics,
        val dual: Boolean,
        val routes: MutableStateFlow<Map<PanelTransport, PanelOperations>>,
        var bleConnection: BleConnection? = null,
        var setupConnection: BleConnection? = null,
        val setupClients: MutableMap<SetupPanel, SetupPanelSession> = mutableMapOf(),
        val setupMutex: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(),
        var bleJob: Job? = null,
        var services: List<BleService> = emptyList(),
        var connecting: Set<PanelTransport> = emptySet(),
    )
    private val sessions = mutableMapOf<String, DeviceSession>()
    private val editorStores = mutableMapOf<String, ViewModelStore>()
    private val mqttSessions = mutableMapOf<String, Pair<MqttRepository, Job>>()
    private val editors = mutableMapOf<String, PanelEditorViewModel>()

    fun editor(id: String): PanelEditorViewModel? {
        val device = devices.value.firstOrNull { it.id == id } ?: return null
        val repository = drafts ?: return null
        return editors.getOrPut(id) {
            PanelEditorViewModel(id, device.panel, repository).also { editor ->
                editorStores[id] = ViewModelStore().apply { put("editor", editor) }
            }
        }
    }

    private fun session(id: String): DeviceSession? {
        val device = devices.value.firstOrNull { it.id == id } ?: return null
        return sessions.getOrPut(id) {
            val panel = panelFactory(device) { repository.savePanel(id, it) }
            val debug = BleDebugViewModel()
            val store = ViewModelStore().apply { put("panel", panel); put("debug", debug) }
            val routes = MutableStateFlow<Map<PanelTransport, PanelOperations>>(emptyMap())
            val dual = device.panel.transport == PanelTransport.DUAL
            DeviceSession(store, panel, debug, diagnosticsFactory(viewModelScope), dual, routes).also {
                activity?.let { journal ->
                    val source = BleActivitySource(journal, id) { devices.value.firstOrNull { d -> d.id == id }?.name ?: device.name }
                    it.diagnostics.listen(source::record)
                }
                it.diagnostics.protect { panel.state.value.definition.setupPanels.flatMap { form -> form.sensitiveCharacteristics() } }
                if (dual) panel.attachDual(dualOperationsFactory(routes))
            }
        }
    }

    fun panel(id: String): PanelViewModel? = session(id)?.panel
    fun debug(id: String): BleDebugViewModel? = session(id)?.debug
    fun traffic(id: String): BleTraffic? = session(id)?.diagnostics?.traffic
    fun setupPanelSession(id: String, definition: SetupPanel): SetupPanelSession? {
        val s = sessions[id] ?: return null
        val state = s.panel.state.value
        if (definition !in state.definition.setupPanels) return null
        val ready = if (s.dual) PanelTransport.BLE in state.connectedTransports else state.ready
        val services = if (s.dual) s.services else state.services
        val connection = s.setupConnection ?: return null
        if (!ready || !definition.supported(services)) return null
        return s.setupClients.getOrPut(definition) {
            val client = BleSetupPanelClient(connection, definition, s.setupMutex)
            activity?.let { journal -> ActivitySetupSession(client, journal, id, { devices.value.firstOrNull { it.id == id }?.name ?: id }, definition) } ?: client
        }
    }

    fun create(name: String, target: BleDeviceTarget? = null, onCreated: (String) -> Unit) = change {
        onCreated(repository.create(name, target).id)
    }

    fun createFromPanel(name: String, definition: com.lr.thingstalk.domain.panel.PanelDefinition,
        target: BleDeviceTarget? = null, onCreated: (String) -> Unit) = change {
        onCreated(repository.createFromPanel(name, definition, target).id)
    }

    fun createMqtt(name: String, onCreated: (String) -> Unit) = change {
        onCreated(repository.createMqtt(name).id)
    }

    fun createDual(name: String, onCreated: (String) -> Unit) = change { onCreated(repository.createDual(name).id) }

    fun promoteToDual(id: String) = change {
        disconnect(id)
        repository.promoteToDual(id)
        sessions.remove(id)?.store?.clear()
        // Any editor draft will be recovered against the new baseline rather than overwritten.
        editors.remove(id)
        editorStores.remove(id)?.clear()
    }

    private fun refreshDual(session: DeviceSession) {
        session.panel.setDualConnections(session.routes.value.keys, session.connecting, session.services)
    }

    private fun route(session: DeviceSession, channel: PanelTransport, operations: PanelOperations?) {
        session.routes.value = if (operations == null) session.routes.value - channel else session.routes.value + (channel to operations)
        refreshDual(session)
    }

    private fun connectDualBle(session: DeviceSession, device: UserDevice) {
        if (session.bleConnection != null) return
        val target = device.bleTarget ?: return
        // A new attempt supersedes the previous disconnect/failure shown by the card.
        // Unlike single-BLE connect(), this path returns early from its caller.
        mutableStatus.update { it.copy(error = null) }
        val connection = session.diagnostics.trace(scanner.connection(target.id))
        session.bleConnection = connection
        session.setupClients.clear()
        session.setupConnection = connection
        session.debug.attachConnection(connection)
        session.connecting += PanelTransport.BLE
        refreshDual(session)
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val services = withTimeout(15_000) { connection.connect(); connection.discoverServices() }
                if (session.bleConnection !== connection) return@launch
                session.services = services
                session.connecting -= PanelTransport.BLE
                route(session, PanelTransport.BLE, operationsFactory(connection))
                connection.state.collect { check(it != BleConnectionState.DISCONNECTED) { "BLE 连接已断开" } }
            } catch (timeout: TimeoutCancellationException) {
                if (session.bleConnection === connection) mutableStatus.update { it.copy(error = "BLE 连接或服务发现超时，请重试或重新扫描") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (session.bleConnection === connection) mutableStatus.update { it.copy(error = failure.message ?: "BLE 连接失败") }
            } finally {
                if (session.bleConnection === connection) {
                    session.setupClients.clear()
                    session.setupConnection = null
                    session.bleConnection = null; session.bleJob = null; session.services = emptyList()
                    session.connecting -= PanelTransport.BLE
                    session.debug.detachConnection()
                    route(session, PanelTransport.BLE, null)
                    connection.close()
                }
            }
        }
        session.bleJob = job
        job.start()
    }

    fun connectMqtt(id: String, endpoint: String, username: String, password: String) =
        connectMqtt(id, MqttConnectionConfig(endpoint, "thingstalk-${kotlin.uuid.Uuid.random()}", username.takeIf { it.isNotEmpty() }, password.takeIf { it.isNotEmpty() }))

    fun connectMqtt(id: String, config: MqttConnectionConfig) {
        val deviceSession = session(id) ?: return
        val model = deviceSession.panel
        if (mqttSessions.containsKey(id)) return
        val rawMqtt = mqttFactory(viewModelScope)
        val mqtt = activity?.let { ActivityMqttRepository(rawMqtt, viewModelScope, it, id) { devices.value.firstOrNull { d -> d.id == id }?.name ?: id } } ?: rawMqtt
        val operations = mqttOperationsFactory(mqtt)
        if (deviceSession.dual) { deviceSession.connecting += PanelTransport.MQTT; refreshDual(deviceSession) }
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                repository.saveMqttEndpoint(id, config.url)
                mqtt.connect(config)
                mqtt.state.collect { state ->
                    if (mqttSessions[id]?.first !== mqtt) throw CancellationException("旧连接已关闭")
                    if (state.status != MqttConnectionStatus.CONNECTED) throw MqttException(state.error ?: "MQTT 连接已断开")
                    if (deviceSession.dual) {
                        deviceSession.connecting -= PanelTransport.MQTT
                        route(deviceSession, PanelTransport.MQTT, operations)
                    } else model.attachMqtt(operations, state.status == MqttConnectionStatus.CONNECTED)
                    state.error?.let { failure -> mutableStatus.update { it.copy(error = failure) } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (mqttSessions[id]?.first === mqtt) mutableStatus.update { it.copy(error = failure.message ?: "MQTT 连接失败") } }
            finally {
                if (mqttSessions[id]?.first === mqtt) {
                    mqttSessions.remove(id)
                    if (deviceSession.dual) {
                        deviceSession.connecting -= PanelTransport.MQTT
                        route(deviceSession, PanelTransport.MQTT, null)
                    } else model.attachMqtt(operations, false)
                    mqtt.close()
                }
            }
        }
        mqttSessions[id] = mqtt to job
        mutableStatus.update { it.copy(error = null) }
        job.start()
    }

    fun rename(id: String, name: String) = change {
        repository.rename(id, name)
    }

    fun delete(id: String) = change {
        disconnect(id)
        repository.delete(id)
        sessions.remove(id)?.store?.clear()
        val editor = editors.remove(id)
        try {
            if (editor != null) editor.deleteCheckpoint() else drafts?.delete(id)
        } finally { editorStores.remove(id)?.clear() }
    }

    fun associate(id: String, target: BleDeviceTarget?, onSaved: () -> Unit = {}) = change {
        disconnectBle(id)
        repository.associate(id, target)
        if (sessions[id]?.dual != true) sessions.remove(id)?.store?.clear()
        onSaved()
    }

    fun connect(id: String) {
        val device = devices.value.firstOrNull { it.id == id } ?: return
        val target = device.bleTarget ?: return
        try {
            val session = session(id) ?: return
            if (session.dual) { connectDualBle(session, device); return }
            val model = session.panel
            if (model.state.value.ready || model.state.value.busy) return
            val connection = session.diagnostics.trace(scanner.connection(target.id))
            session.bleConnection = connection
            session.setupClients.clear()
            session.setupConnection = connection
            session.debug.detachConnection()
            model.attachConnection(connection, operationsFactory(connection), target.name ?: "BLE 外设")
            session.debug.attachConnection(connection)
            model.connect()
            mutableStatus.update { it.copy(error = null) }
        } catch (failure: Exception) {
            mutableStatus.update { it.copy(error = "连接未开始：${failure.message ?: "请重新扫描关联设备"}") }
        }
    }

    fun disconnectBle(id: String) {
        val session = sessions[id] ?: return
        session.debug.detachConnection()
        session.setupClients.clear()
        session.setupConnection = null
        if (session.dual) {
            val connection = session.bleConnection
            session.bleConnection = null
            session.bleJob?.cancel(); session.bleJob = null
            session.services = emptyList(); session.connecting -= PanelTransport.BLE
            route(session, PanelTransport.BLE, null)
            connection?.close()
        } else if (session.panel.state.value.definition.transport == PanelTransport.BLE) { session.bleConnection = null; session.panel.disconnect() }
    }

    fun disconnectMqtt(id: String) {
        mqttSessions.remove(id)?.let { (mqtt, job) -> job.cancel(); mqtt.close() }
        sessions[id]?.let { session ->
            if (session.dual) {
                session.connecting -= PanelTransport.MQTT
                route(session, PanelTransport.MQTT, null)
            } else if (session.panel.state.value.definition.transport == PanelTransport.MQTT) session.panel.disconnect()
        }
    }

    fun disconnect(id: String) { disconnectMqtt(id); disconnectBle(id) }
    fun disconnectAll() { sessions.keys.toList().forEach(::disconnect) }
    /** Background policy is platform-owned; foreground navigation does not end sessions. */
    fun enterBackground(keepBleSession: Boolean) {
        scan.stop()
        sessions.keys.toList().forEach { id ->
            disconnectMqtt(id)
            if (!keepBleSession) disconnectBle(id)
        }
    }
    fun clearError() { mutableStatus.update { it.copy(error = null) } }

    private fun change(action: suspend () -> Unit) {
        if (mutableStatus.value.busy) return
        mutableStatus.value = WorkspaceStatus(busy = true)
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableStatus.update { it.copy(error = failure.message ?: "设备保存失败，请重试") } }
            finally { mutableStatus.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() {
        sessions.keys.toList().forEach(::disconnect)
        mqttSessions.values.toList().forEach { (mqtt, job) -> job.cancel(); mqtt.close() }
        mqttSessions.clear()
        scanStore.clear()
        editorStores.values.forEach { it.clear() }
        editorStores.clear()
        editors.clear()
        sessions.values.forEach { it.debug.detachConnection(); it.store.clear() }
        sessions.clear()
        scanner.close()
        super.onCleared()
    }
}

/** Storage failures are visible and retryable; never replace damaged user configuration with defaults. */
class WorkspaceHostViewModel(private val factory: () -> DeviceWorkspaceViewModel) : ViewModel() {
    private val store = ViewModelStore()
    private val mutableWorkspace = MutableStateFlow<DeviceWorkspaceViewModel?>(null)
    val workspace = mutableWorkspace.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    init { retry() }
    fun retry() {
        if (mutableWorkspace.value != null) return
        try {
            val model = factory()
            store.put("workspace", model)
            mutableWorkspace.value = model
            mutableError.value = null
        } catch (failure: Exception) { mutableError.value = failure.message ?: "无法读取本机设备配置" }
    }
    override fun onCleared() { store.clear(); super.onCleared() }
}
