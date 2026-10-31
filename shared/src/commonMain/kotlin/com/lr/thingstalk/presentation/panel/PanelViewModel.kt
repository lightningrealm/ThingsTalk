package com.lr.thingstalk.presentation.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class PanelUiState(
    val definition: PanelDefinition,
    val activeChannel: PanelTransport? = definition.defaultChannel,
    val values: Map<String, String> = emptyMap(),
    val valueUpdatedAt: Map<String, Long> = emptyMap(),
    val notificationStates: Map<String, PanelNotificationState> = emptyMap(),
    val services: List<BleService> = emptyList(),
    val activeOperations: Set<String> = emptySet(),
    val operationMessages: Map<String, String> = emptyMap(),
    val operationErrors: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val saving: Boolean = false,
    val ready: Boolean = false,
    val connectedTransports: Set<PanelTransport> = emptySet(),
    val connectingTransports: Set<PanelTransport> = emptySet(),
    val hasConnection: Boolean = false,
    val transportLabel: String = "未关联设备",
    val error: String? = null,
    val feedback: String? = null,
)

internal val PanelUiState.effectiveDefinition: PanelDefinition
    get() = if (definition.schemaVersion >= 6 && definition.transport == PanelTransport.DUAL) definition.forActiveChannel(activeChannel ?: requireNotNull(definition.defaultChannel)) else definition

/** Each instance belongs to one user-created device. Hardware is connected only by explicit action. */
class PanelViewModel(
    initialDefinition: PanelDefinition,
    private var operations: PanelOperations? = null,
    private val codec: PanelDefinitionCodec,
    private var connection: BleConnection? = null,
    private val onSave: suspend (PanelDefinition) -> Unit = {},
    transportLabel: String = "未关联设备",
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {
    val defaultDefinition: PanelDefinition = initialDefinition.copy(capabilities = emptyList(), bindings = emptyList(), widgets = emptyList(), payloadFormats = emptyList(), setupPanels = emptyList())
    private val mutableState = MutableStateFlow(PanelUiState(initialDefinition, hasConnection = connection != null, transportLabel = transportLabel))
    val state = mutableState.asStateFlow()
    private var subscriptionJob: Job? = null
    private var connectionJob: Job? = null
    private var connectionStateJob: Job? = null
    private val operationJobs = mutableMapOf<String, Job>()
    private val operationChannels = mutableMapOf<String, PanelTransport?>()
    private val operationTokens = mutableMapOf<String, Any>()
    private var operationMutex = Mutex()
    private var disconnectJob: Job? = null
    private var transportGeneration = 0
    private var revision = 0
    private val valueVersions = mutableMapOf<String, Int>()
    private val notificationJobs = mutableMapOf<String, Job>()
    private val notificationTokens = mutableMapOf<String, Any>()

    fun exportDefinition(definition: PanelDefinition): String = codec.encode(definition)
    fun importDefinition(source: String): PanelDefinition = codec.decode(source).let {
        if (mutableState.value.definition.transport == PanelTransport.DUAL) it.asDual() else it
    }.also {
        require(it.transport == mutableState.value.definition.transport) { "文件传输类型与当前设备不一致。请在设备或发现页选择“从配置文件创建设备”，自动识别连接类型" }
    }

    /** Explicit v6 tab selection; sessions stay open, old commands are cancelled, never replayed. */
    fun selectChannel(channel: PanelTransport) {
        val current = mutableState.value
        if (current.definition.schemaVersion < 6 || current.definition.transport != PanelTransport.DUAL || channel == PanelTransport.DUAL || current.activeChannel == channel || current.saving) return
        transportGeneration++
        revision++
        subscriptionJob?.cancel()
        cancelNotifications()
        cancelOperations()
        valueVersions.clear()
        mutableState.update { it.copy(activeChannel = channel, values = emptyMap(), valueUpdatedAt = emptyMap(),
            notificationStates = emptyMap(), busy = false, error = null, feedback = null) }
        restartObservation()
    }

    /** Assigns a selected peripheral; caller must invoke connect() after the user requests connection. */
    fun attachConnection(connection: BleConnection, operations: PanelOperations, transportLabel: String) {
        invalidateConnection()
        disconnectJob?.cancel()
        disconnectJob = null
        this.connection?.close()
        this.connection = connection
        this.operations = operations
        mutableState.update { it.copy(hasConnection = true, transportLabel = transportLabel, error = null) }
    }

    fun connect() {
        if (mutableState.value.ready || mutableState.value.busy || connectionJob?.isActive == true) return
        val activeConnection = connection ?: return
        if (operations == null) return
        val generation = transportGeneration
        mutableState.update { it.copy(busy = true, error = null, feedback = null) }
        connectionStateJob?.cancel()
        connectionStateJob = viewModelScope.launch {
            activeConnection.state.collect { status ->
                if (connection === activeConnection && generation == transportGeneration && status == BleConnectionState.DISCONNECTED) {
                    if (mutableState.value.ready) {
                        transportGeneration++
                        revision++
                        subscriptionJob?.cancel()
                        cancelNotifications()
                        cancelOperations()
                        valueVersions.clear()
                        mutableState.update { it.copy(ready = false, values = emptyMap(), valueUpdatedAt = emptyMap(), notificationStates = stoppedNotifications(it.definition), services = emptyList(), busy = it.saving) }
                    }
                }
            }
        }
        connectionJob = viewModelScope.launch {
            try {
                val services = withTimeout(15_000) {
                    disconnectJob?.join()
                    activeConnection.connect()
                    activeConnection.discoverServices()
                }
                if (connection === activeConnection && transportGeneration == generation) {
                    mutableState.update { it.copy(services = services, ready = true) }
                    restartObservation()
                }
            } catch (timeout: TimeoutCancellationException) {
                if (connection === activeConnection && transportGeneration == generation) {
                    mutableState.update { it.copy(error = "连接或发现服务超时，请重试", ready = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (connection === activeConnection && transportGeneration == generation) mutableState.update { it.copy(error = failure.message ?: "无法连接设备", ready = false) }
            } finally {
                if (connection === activeConnection && transportGeneration == generation) mutableState.update { it.copy(busy = it.saving) }
            }
        }
    }

    /** The workspace owns MQTT sessions; this view model only runs panel operations. */
    fun attachMqtt(operations: PanelOperations, ready: Boolean) {
        invalidateConnection()
        this.operations = operations
        mutableState.update { it.copy(hasConnection = true, ready = ready, transportLabel = "MQTT") }
        if (ready) restartObservation()
    }

    fun attachDual(operations: PanelOperations) {
        invalidateConnection()
        this.operations = operations
        mutableState.update { it.copy(hasConnection = true, transportLabel = "BLE + MQTT") }
        restartObservation()
    }

    fun setDualConnections(connected: Set<PanelTransport>, connecting: Set<PanelTransport>, services: List<BleService>) {
        val current = mutableState.value
        val lost = current.connectedTransports - connected
        val cancelledIds = operationChannels.filterValues { it in lost }.keys.toSet()
        cancelledIds.forEach { id -> operationTokens.remove(id); operationChannels.remove(id); operationJobs.remove(id)?.cancel() }
        val stale = current.effectiveDefinition.bindings.filter { it.stateTransport in lost }.map { it.capabilityId }.toSet()
        mutableState.update { it.copy(connectedTransports = connected, connectingTransports = connecting,
            ready = connected.isNotEmpty(), services = services,
            activeOperations = it.activeOperations - cancelledIds, busy = it.saving || operationJobs.isNotEmpty(),
            values = it.values - stale, valueUpdatedAt = it.valueUpdatedAt - stale,
            notificationStates = it.notificationStates.mapValues { (id, state) ->
                if (id in stale) PanelNotificationState(status = PanelNotificationStatus.STOPPED) else state
            }) }
    }

    fun reconnect() = connect()

    fun disconnect() {
        val activeConnection = connection
        invalidateConnection()
        val generation = transportGeneration
        val previous = disconnectJob
        disconnectJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            try { withTimeout(10_000) { activeConnection?.disconnect() } }
            catch (timeout: TimeoutCancellationException) {
                if (connection === activeConnection && generation == transportGeneration) mutableState.update { it.copy(error = "断开连接超时") }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (connection === activeConnection && generation == transportGeneration) mutableState.update { it.copy(error = failure.message ?: "断开连接失败") }
            }
        }
    }

    private fun invalidateConnection() {
        transportGeneration++
        revision++
        subscriptionJob?.cancel()
        cancelNotifications()
        connectionJob?.cancel()
        connectionStateJob?.cancel()
        cancelOperations()
        valueVersions.clear()
        mutableState.update { it.copy(ready = false, values = emptyMap(), valueUpdatedAt = emptyMap(), notificationStates = stoppedNotifications(it.definition), services = emptyList(), busy = it.saving, feedback = null) }
    }

    /** Returns immediate validation errors; persistence completes asynchronously before state is applied. */
    fun updateDefinition(definition: PanelDefinition): List<String> {
        val errors = validatePanel(definition).toMutableList()
        if (definition.transport != mutableState.value.definition.transport) errors += "配置传输类型与当前设备不一致"
        if (mutableState.value.busy) errors += "请等待当前操作完成后再保存"
        if (errors.isNotEmpty()) return errors
        mutableState.update { it.copy(busy = true, saving = true, error = null, feedback = null) }
        viewModelScope.launch {
            try {
                onSave(definition)
                revision++
                valueVersions.clear()
                mutableState.update { it.copy(definition = definition, activeChannel = definition.defaultChannel, values = emptyMap(), valueUpdatedAt = emptyMap(), notificationStates = stoppedNotifications(definition), operationMessages = emptyMap(), operationErrors = emptyMap(), feedback = "面板已保存") }
                restartObservation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = failure.message ?: "保存失败，请重试") } }
            finally { mutableState.update { it.copy(busy = false, saving = false) } }
        }
        return emptyList()
    }

    fun read(capabilityId: String) = execute(capabilityId, false) { activeOperations, generation ->
        val version = valueVersions[capabilityId] ?: 0
        val value = activeOperations.read(mutableState.value.effectiveDefinition, capabilityId)
        currentCoroutineContext().ensureActive()
        if ((valueVersions[capabilityId] ?: 0) == version && mutableState.value.ready && generation == transportGeneration) recordValue(capabilityId, value)
        if (generation == transportGeneration) mutableState.update { it.copy(operationMessages = it.operationMessages + (capabilityId to "已读取设备特征")) }
    }

    fun write(capabilityId: String, value: String) = execute(capabilityId, true) { activeOperations, generation ->
        val definition = mutableState.value.effectiveDefinition
        valueVersions[capabilityId] = (valueVersions[capabilityId] ?: 0) + 1
        activeOperations.write(definition, capabilityId, value)
        currentCoroutineContext().ensureActive()
        val binding = definition.bindings.first { it.capabilityId == capabilityId }
        if (definition.transport != PanelTransport.DUAL && binding.read != null) {
            val version = valueVersions[capabilityId] ?: 0
            val readback = activeOperations.read(definition, capabilityId)
            if ((valueVersions[capabilityId] ?: 0) == version && mutableState.value.ready && generation == transportGeneration) recordValue(capabilityId, readback)
        }
        if (generation == transportGeneration) mutableState.update { it.copy(operationMessages = it.operationMessages + (capabilityId to "写入已完成 · 业务结果需设备协议确认")) }
    }

    private fun cancelOperations() {
        operationJobs.values.toList().forEach { it.cancel() }
        operationJobs.clear()
        operationChannels.clear()
        operationTokens.clear()
        // A cancelled old transport may still be finishing non-cancellable cleanup.
        operationMutex = Mutex()
        mutableState.update { it.copy(activeOperations = emptySet(), operationMessages = emptyMap(), operationErrors = emptyMap()) }
    }

    private fun execute(capabilityId: String, writing: Boolean, block: suspend (PanelOperations, Int) -> Unit) {
        val activeOperations = operations ?: return
        if (mutableState.value.saving || !mutableState.value.ready || operationJobs.containsKey(capabilityId)) return
        val binding = mutableState.value.effectiveDefinition.bindings.firstOrNull { it.capabilityId == capabilityId } ?: return
        val channel = if (writing) binding.selectedControl else binding.selectedState
        if (mutableState.value.definition.transport == PanelTransport.DUAL && channel !in mutableState.value.connectedTransports) return
        val token = Any()
        operationTokens[capabilityId] = token
        operationChannels[capabilityId] = channel
        val generation = transportGeneration
        val mutex = operationMutex
        mutableState.update { it.copy(busy = true, activeOperations = it.activeOperations + capabilityId,
            operationMessages = it.operationMessages - capabilityId, operationErrors = it.operationErrors - capabilityId) }
        fun failure(message: String) {
            if (generation == transportGeneration && operationTokens[capabilityId] === token) mutableState.update { it.copy(operationErrors = it.operationErrors + (capabilityId to message)) }
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // Serialize transport commands, while keeping progress local to queued components.
                mutex.withLock { withTimeout(10_000) { block(activeOperations, generation) } }
            } catch (timeout: TimeoutCancellationException) {
                failure("设备操作超时，结果未知；请确认设备状态后再决定是否重试")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(error.message ?: "操作未完成") }
            finally {
                if (generation == transportGeneration && operationTokens[capabilityId] === token) {
                    operationTokens.remove(capabilityId)
                    operationChannels.remove(capabilityId)
                    operationJobs.remove(capabilityId)
                    mutableState.update { it.copy(activeOperations = it.activeOperations - capabilityId,
                        busy = it.saving || operationJobs.isNotEmpty()) }
                }
            }
        }
        operationJobs[capabilityId] = job
        job.start()
    }

    private fun restartObservation() {
        val previous = subscriptionJob
        val previousNotifications = cancelNotifications()
        val currentRevision = revision
        val generation = transportGeneration
        val definition = mutableState.value.effectiveDefinition
        val activeOperations = operations ?: return
        subscriptionJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            previousNotifications.forEach { it.join() }
            if ((!mutableState.value.ready && definition.transport != PanelTransport.DUAL) || currentRevision != revision || generation != transportGeneration) return@launch
            definition.bindings.forEach { binding ->
                if (binding.canObserve || (definition.transport == PanelTransport.DUAL && binding.canRead)) startNotification(definition, binding.capabilityId, activeOperations, currentRevision, generation)
                if (definition.transport != PanelTransport.DUAL && binding.read != null) launch {
                    try {
                        val version = valueVersions[binding.capabilityId] ?: 0
                        val value = withTimeout(10_000) { activeOperations.read(definition, binding.capabilityId) }
                        if (revision == currentRevision && mutableState.value.ready && (valueVersions[binding.capabilityId] ?: 0) == version) recordValue(binding.capabilityId, value)
                    } catch (timeout: TimeoutCancellationException) {
                        if (revision == currentRevision) mutableState.update { it.copy(error = "初次读取超时，请手动重试") }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (revision == currentRevision) mutableState.update { it.copy(error = "读取失败：${failure.message}") }
                    }
                }
            }
        }
    }

    /** Retry this subscription only; no read, control write, or connection attempt is replayed. */
    fun retryNotifications(capabilityId: String) {
        val current = mutableState.value
        val activeOperations = operations ?: return
        if (!current.ready || current.busy) return
        if (current.effectiveDefinition.bindings.none { it.capabilityId == capabilityId && it.canObserve }) return
        if (current.notificationStates[capabilityId]?.status !in setOf(PanelNotificationStatus.FAILED, PanelNotificationStatus.STOPPED)) return
        startNotification(current.effectiveDefinition, capabilityId, activeOperations, revision, transportGeneration)
    }

    private fun startNotification(
        definition: PanelDefinition,
        capabilityId: String,
        activeOperations: PanelOperations,
        currentRevision: Int,
        generation: Int,
    ) {
        val previous = notificationJobs[capabilityId]
        val token = Any()
        notificationTokens[capabilityId] = token
        fun isCurrent() = notificationTokens[capabilityId] === token && currentRevision == revision &&
            generation == transportGeneration && (mutableState.value.ready || definition.transport == PanelTransport.DUAL)
        updateNotification(capabilityId) { it.copy(status = PanelNotificationStatus.STARTING, failure = null) }
        notificationJobs[capabilityId] = viewModelScope.launch {
            try {
                previous?.cancelAndJoin()
                if (!isCurrent()) return@launch
                coroutineScope {
                    val ready = CompletableDeferred<Unit>()
                    val collector = async {
                        try {
                            activeOperations.observeEvents(definition, capabilityId).collect { observation ->
                                if (!isCurrent()) return@collect
                                when (observation) {
                                    PanelObservation.Offline -> {
                                        ready.complete(Unit)
                                        valueVersions[capabilityId] = (valueVersions[capabilityId] ?: 0) + 1
                                        mutableState.update { it.copy(values = it.values - capabilityId, valueUpdatedAt = it.valueUpdatedAt - capabilityId) }
                                        updateNotification(capabilityId) { PanelNotificationState(status = PanelNotificationStatus.STOPPED) }
                                    }
                                    is PanelObservation.Failed -> {
                                        ready.complete(Unit)
                                        updateNotification(capabilityId) { it.copy(status = PanelNotificationStatus.FAILED, failure = observation.message) }
                                    }
                                    PanelObservation.Ready -> {
                                        ready.complete(Unit)
                                        updateNotification(capabilityId) { it.copy(status = if (it.status == PanelNotificationStatus.RECEIVING) it.status else PanelNotificationStatus.WAITING, failure = null) }
                                    }
                                    is PanelObservation.Value -> {
                                        ready.complete(Unit)
                                        val receivedAt = nowEpochMillis()
                                        recordValue(capabilityId, observation.value, receivedAt)
                                        updateNotification(capabilityId) { it.copy(status = PanelNotificationStatus.RECEIVING,
                                            lastValueAt = receivedAt, lastDecodeError = null, failure = null) }
                                    }
                                    is PanelObservation.InvalidPayload -> updateNotification(capabilityId) {
                                        it.copy(invalidPayloadCount = if (it.invalidPayloadCount == Long.MAX_VALUE) Long.MAX_VALUE else it.invalidPayloadCount + 1,
                                            lastDecodeError = observation.message)
                                    }
                                }
                            }
                        } finally {
                            // An early end must not leave the setup timer waiting for another event.
                            ready.complete(Unit)
                        }
                    }
                    // Readiness only: a quiet subscribed device may legitimately send no samples.
                    withTimeout(10_000) { ready.await() }
                    collector.await()
                }
                if (isCurrent()) updateNotification(capabilityId) {
                    it.copy(status = PanelNotificationStatus.STOPPED, failure = "通知流已结束，可重新订阅")
                }
            } catch (timeout: TimeoutCancellationException) {
                if (isCurrent()) updateNotification(capabilityId) {
                    it.copy(status = PanelNotificationStatus.FAILED, failure = "通知订阅建立超时，请重新订阅")
                }
            } catch (cancelled: CancellationException) {
                if (isCurrent()) updateNotification(capabilityId) {
                    it.copy(status = PanelNotificationStatus.STOPPED, failure = "通知已取消，可重新订阅")
                }
                throw cancelled
            }
            catch (failure: Exception) {
                if (isCurrent()) updateNotification(capabilityId) {
                    it.copy(status = PanelNotificationStatus.FAILED, failure = failure.message ?: "通知订阅失败")
                }
            }
        }
    }

    private fun updateNotification(capabilityId: String, change: (PanelNotificationState) -> PanelNotificationState) {
        mutableState.update { current ->
            current.copy(notificationStates = current.notificationStates + (capabilityId to change(current.notificationStates[capabilityId] ?: PanelNotificationState())))
        }
    }

    private fun cancelNotifications(): List<Job> {
        // Invalidate first: an old transport may finish non-cancellable cleanup after replacement.
        notificationTokens.clear()
        val previous = notificationJobs.values.toList()
        notificationJobs.clear()
        previous.forEach { it.cancel() }
        return previous
    }

    private fun stoppedNotifications(definition: PanelDefinition) = definition.bindings
        .filter { it.canObserve }.associate { it.capabilityId to PanelNotificationState() }

    private fun recordValue(capabilityId: String, value: String, receivedAt: Long = nowEpochMillis()) {
        valueVersions[capabilityId] = (valueVersions[capabilityId] ?: 0) + 1
        mutableState.update { it.copy(values = it.values + (capabilityId to value), valueUpdatedAt = it.valueUpdatedAt + (capabilityId to receivedAt)) }
    }

    override fun onCleared() {
        invalidateConnection()
        disconnectJob?.cancel()
        connection?.close()
        super.onCleared()
    }
}
