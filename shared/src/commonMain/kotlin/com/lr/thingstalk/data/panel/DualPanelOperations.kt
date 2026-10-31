package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A route is a connection-scoped adapter. No fallback, retries, or cross-channel command replay. */
@OptIn(ExperimentalCoroutinesApi::class)
class DualPanelOperations(private val routes: StateFlow<Map<PanelTransport, PanelOperations>>) : PanelOperations {
    private fun binding(definition: PanelDefinition, id: String): PanelBinding {
        require(definition.transport == PanelTransport.DUAL)
        require(validatePanel(definition).isEmpty()) { validatePanel(definition).joinToString("\n") }
        return definition.bindings.single { it.capabilityId == id }
    }
    override suspend fun read(definition: PanelDefinition, capabilityId: String): String {
        val binding = binding(definition, capabilityId)
        require(binding.canRead) { "当前状态来源不支持主动读取" }
        val selected = requireNotNull(routes.value[PanelTransport.BLE]) { "BLE 状态通道未连接" }
        val result = selected.read(definition.forTransport(PanelTransport.BLE), capabilityId)
        checkCurrent(PanelTransport.BLE, selected)
        return result
    }
    override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) {
        val channel = requireNotNull(binding(definition, capabilityId).controlTransport) { "未选择控制通道" }
        val selected = requireNotNull(routes.value[channel]) { "$channel 控制通道未连接" }
        selected.write(definition.forTransport(channel), capabilityId, value)
        checkCurrent(channel, selected)
    }
    private fun checkCurrent(channel: PanelTransport, expected: PanelOperations) {
        if (routes.value[channel] !== expected) throw CancellationException("连接已更换，忽略旧连接结果")
    }
    override fun observe(definition: PanelDefinition, capabilityId: String): Flow<String> =
        observeEvents(definition, capabilityId).mapNotNull { (it as? PanelObservation.Value)?.value }

    override fun observeEvents(definition: PanelDefinition, capabilityId: String): Flow<PanelObservation> = flow {
        val binding = binding(definition, capabilityId)
        val channel = requireNotNull(binding.stateTransport) { "未选择状态来源" }
        val projected = definition.forTransport(channel)
        emitAll(routes.map { it[channel] }.distinctUntilChanged { old, new -> old === new }.flatMapLatest { selected ->
            if (selected == null) flowOf<PanelObservation>(PanelObservation.Offline)
            else channelFlow<PanelObservation> {
                val ready = CompletableDeferred<Unit>()
                var valueVersion = 0
                if (binding.canObserve) launch {
                    selected.observeEvents(projected, capabilityId).collect { event ->
                        checkCurrent(channel, selected)
                        if (event == PanelObservation.Ready || event is PanelObservation.Value) ready.complete(Unit)
                        if (event is PanelObservation.Value) valueVersion++
                        send(event)
                    }
                } else { ready.complete(Unit); send(PanelObservation.Ready) }
                withTimeout(10_000) { ready.await() }
                if (binding.canRead) {
                    val beforeRead = valueVersion
                    val value = withTimeout(10_000) { selected.read(projected, capabilityId) }
                    checkCurrent(channel, selected)
                    if (beforeRead == valueVersion) send(PanelObservation.Value(value))
                }
                awaitCancellation()
            }.catch { failure ->
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                emit(PanelObservation.Failed(failure.message ?: "状态通道订阅失败"))
            }
        })
    }
}
