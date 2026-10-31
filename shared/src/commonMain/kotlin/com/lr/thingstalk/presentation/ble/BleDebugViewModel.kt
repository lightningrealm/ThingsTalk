package com.lr.thingstalk.presentation.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lr.thingstalk.domain.ble.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.uuid.Uuid

data class BleDebugUiState(
    val connected: Boolean = false,
    val services: List<BleService> = emptyList(),
    val busy: Boolean = false,
    /** Latest received bytes, formatted as uppercase HEX. An empty value is a valid empty response. */
    val values: Map<BleCharacteristicId, String> = emptyMap(),
    val notificationCounts: Map<BleCharacteristicId, Int> = emptyMap(),
    val subscribing: Set<BleCharacteristicId> = emptySet(),
    val observing: Set<BleCharacteristicId> = emptySet(),
    val error: String? = null,
    val feedback: String? = null,
    val maximumWriteLengths: Map<BleWriteMode, Int> = emptyMap(),
)

/** Debug operations share the panel's connection. This ViewModel never connects, disconnects or closes it. */
class BleDebugViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(BleDebugUiState())
    val state = mutableState.asStateFlow()
    private var connection: BleConnection? = null
    private var attachmentGeneration = 0
    private var sessionGeneration = 0
    private var stateJob: Job? = null
    private var operationJob: Job? = null
    private var discoveryJob: Job? = null
    private var nextSubscription = 0L
    private data class Subscription(val token: Long, val job: Job)
    private val subscriptions = mutableMapOf<BleCharacteristicId, Subscription>()
    private val valueVersions = mutableMapOf<BleCharacteristicId, Long>()

    fun attachConnection(connection: BleConnection) {
        if (this.connection === connection) return
        detachConnection()
        this.connection = connection
        val generation = attachmentGeneration
        stateJob = viewModelScope.launch {
            try {
                connection.state.distinctUntilChanged().collect { status ->
                    if (this@BleDebugViewModel.connection !== connection || generation != attachmentGeneration) return@collect
                    if (status == BleConnectionState.CONNECTED) {
                        if (!mutableState.value.connected) {
                            mutableState.update { it.copy(connected = true, error = null) }
                            refreshServices()
                        }
                    } else {
                        invalidateSession()
                        mutableState.value = BleDebugUiState()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (this@BleDebugViewModel.connection === connection && generation == attachmentGeneration) {
                    invalidateSession()
                    mutableState.value = BleDebugUiState(error = failure.message ?: "无法读取连接状态")
                }
            }
        }
    }

    fun detachConnection() {
        attachmentGeneration++
        connection = null
        stateJob?.cancel()
        stateJob = null
        invalidateSession()
        mutableState.value = BleDebugUiState()
    }

    fun refreshServices() {
        val activeConnection = requireConnection() ?: return
        if (mutableState.value.busy) return
        // A new service tree invalidates previous UUID selections and subscriptions.
        invalidateSession()
        val generation = sessionGeneration
        mutableState.update { it.copy(
            busy = true, services = emptyList(), values = emptyMap(), notificationCounts = emptyMap(),
            maximumWriteLengths = emptyMap(), error = null, feedback = null,
        ) }
        discoveryJob = viewModelScope.launch {
            try {
                val (services, limits) = withTimeout(OPERATION_TIMEOUT) {
                    val services = activeConnection.discoverServices()
                    val limits = BleWriteMode.entries.associateWith { mode ->
                        currentCoroutineContext().ensureActive()
                        activeConnection.maximumWriteLength(mode).also { require(it > 0) { "设备返回了无效的写入长度上限" } }
                    }
                    services to limits
                }
                if (isCurrent(activeConnection, generation)) mutableState.update { it.copy(
                    services = services, maximumWriteLengths = limits, feedback = "已发现 ${services.size} 个服务",
                ) }
            } catch (timeout: TimeoutCancellationException) {
                if (isCurrent(activeConnection, generation)) mutableState.update { it.copy(error = "发现服务超时，请手动刷新") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent(activeConnection, generation)) mutableState.update { it.copy(error = failure.message ?: "发现服务失败") }
            } finally {
                if (isCurrent(activeConnection, generation)) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun read(id: BleCharacteristicId) = execute("读取超时，请手动重试") { activeConnection, generation ->
        val target = resolve(id)
        require(BleProperty.READ in target.properties) { "该特征不支持读取" }
        val version = valueVersions[target.id] ?: 0
        val bytes = activeConnection.read(target.id)
        if (isCurrent(activeConnection, generation)) {
            // A delayed read must not replace a more recent notification.
            if ((valueVersions[target.id] ?: 0) == version) recordValue(target.id, bytes)
            mutableState.update { it.copy(feedback = "已读取 ${bytes.size} 字节") }
        }
    }

    fun write(id: BleCharacteristicId, hex: String, mode: BleWriteMode) =
        execute("写入超时，结果未知；请先读取设备状态再决定是否重试") { activeConnection, generation ->
            val target = resolve(id)
            val property = if (mode == BleWriteMode.WITH_RESPONSE) BleProperty.WRITE else BleProperty.WRITE_WITHOUT_RESPONSE
            require(property in target.properties) { "该特征不支持所选写入方式" }
            val bytes = parseHex(hex)
            // Re-query the current limit because the negotiated MTU can change after discovery.
            val maximum = activeConnection.maximumWriteLength(mode)
            require(maximum > 0) { "设备返回了无效的写入长度上限" }
            require(bytes.size <= maximum) { "数据为 ${bytes.size} 字节，超过当前写入上限 $maximum 字节；不会自动分包" }
            if (isCurrent(activeConnection, generation)) mutableState.update {
                it.copy(maximumWriteLengths = it.maximumWriteLengths + (mode to maximum))
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(activeConnection, generation)) return@execute
            activeConnection.write(target.id, bytes, mode)
            if (isCurrent(activeConnection, generation)) mutableState.update { it.copy(feedback =
                if (mode == BleWriteMode.WITH_RESPONSE) "已完成 GATT 写入 · 业务结果需设备协议确认"
                else "已提交无响应写入 · 不代表设备已收到或执行",
            ) }
        }

    fun setNotifications(id: BleCharacteristicId, enabled: Boolean) {
        if (!enabled) { stopObserving(id); return }
        val activeConnection = requireConnection() ?: return
        val target = try {
            resolve(id).also { require(BleProperty.NOTIFY in it.properties || BleProperty.INDICATE in it.properties) { "该特征不支持通知或指示" } }
        } catch (failure: Exception) {
            mutableState.update { it.copy(error = failure.message ?: "无法订阅此特征", feedback = null) }
            return
        }
        if (subscriptions.containsKey(target.id)) return
        val generation = sessionGeneration
        val token = ++nextSubscription
        fun current() = isCurrent(activeConnection, generation) && subscriptions[target.id]?.token == token
        mutableState.update { it.copy(subscribing = it.subscribing + target.id, error = null, feedback = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                coroutineScope {
                    val ready = CompletableDeferred<Unit>()
                    val collector = launch {
                        activeConnection.notifications(target.id) {
                            if (current()) {
                                mutableState.update { it.copy(
                                    subscribing = it.subscribing - target.id,
                                    observing = it.observing + target.id,
                                    feedback = "通知订阅已就绪",
                                ) }
                                ready.complete(Unit)
                            }
                        }.collect { bytes ->
                            if (current()) {
                                recordValue(target.id, bytes)
                                mutableState.update { it.copy(notificationCounts = it.notificationCounts +
                                    (target.id to ((it.notificationCounts[target.id] ?: 0) + 1))) }
                            }
                        }
                        check(ready.isCompleted) { "通知订阅在就绪前结束" }
                    }
                    withTimeout(OPERATION_TIMEOUT) { ready.await() }
                    collector.join()
                }
                if (current()) mutableState.update { it.copy(feedback = "通知订阅已结束") }
            } catch (timeout: TimeoutCancellationException) {
                if (current()) mutableState.update { it.copy(error = "通知订阅建立超时，请手动重试") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current()) mutableState.update { it.copy(error = "通知订阅失败：${failure.message ?: "未知错误"}") }
            } finally {
                if (current()) {
                    subscriptions.remove(target.id)
                    mutableState.update { it.copy(subscribing = it.subscribing - target.id, observing = it.observing - target.id) }
                }
            }
        }
        subscriptions[target.id] = Subscription(token, job)
        job.start()
    }

    /** Cancels only debug subscriptions; panel subscriptions on the shared connection are unaffected. */
    fun stopObserving(characteristic: BleCharacteristicId? = null) {
        val ids = if (characteristic == null) subscriptions.keys.toList() else subscriptions.keys.filter { samePath(it, characteristic) }
        ids.forEach { id -> subscriptions.remove(id)?.job?.cancel() }
        if (ids.isNotEmpty()) mutableState.update { it.copy(
            subscribing = it.subscribing - ids.toSet(), observing = it.observing - ids.toSet(), feedback = "调试通知订阅已关闭",
        ) }
    }

    private fun execute(timeoutMessage: String, block: suspend (BleConnection, Int) -> Unit) {
        val activeConnection = requireConnection() ?: return
        if (mutableState.value.busy) return
        val generation = sessionGeneration
        mutableState.update { it.copy(busy = true, error = null, feedback = null) }
        operationJob = viewModelScope.launch {
            try { withTimeout(OPERATION_TIMEOUT) { block(activeConnection, generation) } }
            catch (timeout: TimeoutCancellationException) {
                if (isCurrent(activeConnection, generation)) mutableState.update { it.copy(error = timeoutMessage) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (isCurrent(activeConnection, generation)) mutableState.update { it.copy(error = failure.message ?: "操作失败") }
            } finally {
                if (isCurrent(activeConnection, generation)) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun requireConnection(): BleConnection? {
        if (!mutableState.value.connected || connection == null) {
            mutableState.update { it.copy(error = "请先从设备面板连接蓝牙设备", feedback = null) }
            return null
        }
        return connection
    }

    private fun resolve(id: BleCharacteristicId): BleCharacteristic {
        require(runCatching { Uuid.parse(id.serviceUuid); Uuid.parse(id.characteristicUuid) }.isSuccess) { "服务或特征 UUID 无效" }
        val services = mutableState.value.services.filter { it.uuid.equals(id.serviceUuid, ignoreCase = true) }
        require(services.size == 1) { "服务不存在或 UUID 路径不唯一" }
        val characteristics = services.single().characteristics.filter { it.id.characteristicUuid.equals(id.characteristicUuid, ignoreCase = true) }
        require(characteristics.size == 1 && characteristics.single().id.serviceUuid.equals(id.serviceUuid, ignoreCase = true)) {
            "特征不存在或 UUID 路径不唯一"
        }
        return characteristics.single()
    }

    private fun invalidateSession() {
        sessionGeneration++
        discoveryJob?.cancel()
        discoveryJob = null
        operationJob?.cancel()
        operationJob = null
        stopObserving()
        valueVersions.clear()
    }

    private fun isCurrent(activeConnection: BleConnection, generation: Int) =
        connection === activeConnection && generation == sessionGeneration && mutableState.value.connected

    private fun recordValue(id: BleCharacteristicId, bytes: ByteArray) {
        valueVersions[id] = (valueVersions[id] ?: 0) + 1
        val hex = bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
        mutableState.update { it.copy(values = it.values + (id to hex)) }
    }

    private fun samePath(first: BleCharacteristicId, second: BleCharacteristicId) =
        first.serviceUuid.equals(second.serviceUuid, ignoreCase = true) && first.characteristicUuid.equals(second.characteristicUuid, ignoreCase = true)

    private fun parseHex(source: String): ByteArray {
        val hex = source.filterNot(Char::isWhitespace)
        require(hex.isNotEmpty()) { "请输入要写入的 HEX 字节" }
        require(hex.length % 2 == 0) { "HEX 必须包含完整字节，每个字节两位" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "HEX 只能包含 0–9、A–F 和空白" }
        return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    override fun onCleared() {
        detachConnection()
        super.onCleared()
    }

    private companion object { const val OPERATION_TIMEOUT = 10_000L }
}
