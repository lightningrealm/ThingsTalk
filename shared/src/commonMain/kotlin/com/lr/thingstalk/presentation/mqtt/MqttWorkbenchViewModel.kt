package com.lr.thingstalk.presentation.mqtt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lr.thingstalk.domain.mqtt.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.uuid.Uuid

enum class MqttPayloadFormat { TEXT, HEX }
enum class MqttMessageDirection { RECEIVED, SENT }

/** Bounded display records deliberately contain no connection credentials. */
data class MqttMessageRecord(
    val id: Long,
    val direction: MqttMessageDirection,
    val topic: String,
    val text: String,
    val hex: String,
    val byteCount: Int,
    val qos: MqttQos,
    val retained: Boolean,
    val truncated: Boolean,
)

data class MqttWorkbenchUiState(
    val endpoint: String = "",
    val clientId: String = "thingstalk-${Uuid.random().toString().take(12)}",
    val username: String = "",
    val password: String = "",
    val connection: MqttConnectionState = MqttConnectionState(MqttConnectionStatus.DISCONNECTED),
    val subscriptionFilter: String = "",
    val subscriptionQos: MqttQos = MqttQos.AT_MOST_ONCE,
    val subscriptions: List<MqttSubscription> = emptyList(),
    val publishTopic: String = "",
    val publishPayload: String = "",
    val publishQos: MqttQos = MqttQos.AT_MOST_ONCE,
    val retain: Boolean = false,
    val payloadFormat: MqttPayloadFormat = MqttPayloadFormat.TEXT,
    val showHex: Boolean = false,
    val messages: List<MqttMessageRecord> = emptyList(),
    val receivedCount: Long = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val feedback: String? = null,
) {
    val connected: Boolean get() = connection.status == MqttConnectionStatus.CONNECTED

    override fun toString(): String = "MqttWorkbenchUiState(status=${connection.status}, password=<redacted>, messages=${messages.size})"
}

/** Lives at app scope so changing tabs or rotating the window does not reconnect MQTT. */
class MqttWorkbenchViewModel(
    repositoryFactory: (CoroutineScope) -> MqttRepository,
    private val maximumLogEntries: Int = 100,
) : ViewModel() {
    init { require(maximumLogEntries > 0) }
    private val repository = repositoryFactory(viewModelScope)
    private val mutableState = MutableStateFlow(MqttWorkbenchUiState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L
    private var nextRecordId = 0L

    init {
        viewModelScope.launch {
            repository.state.collect { connection ->
                mutableState.update { it.copy(connection = connection.copy(error = connection.error?.let(::safeError))) }
            }
        }
        viewModelScope.launch {
            repository.subscriptions.collect { subscriptions ->
                mutableState.update { it.copy(subscriptions = subscriptions) }
            }
        }
        viewModelScope.launch {
            repository.messages.catch { failure ->
                mutableState.update { it.copy(error = safeError(failure.message ?: "接收 MQTT 消息失败")) }
            }.collect { message ->
                record(MqttMessageDirection.RECEIVED, message.topic, message.payload, message.qos, message.retained)
            }
        }
    }

    fun setEndpoint(value: String) = editConnection { copy(endpoint = value) }
    fun setClientId(value: String) = editConnection { copy(clientId = value) }
    fun setUsername(value: String) = editConnection { copy(username = value) }
    fun setPassword(value: String) = editConnection { copy(password = value) }
    fun setSubscriptionFilter(value: String) { mutableState.update { it.copy(subscriptionFilter = value) } }
    fun setSubscriptionQos(value: MqttQos) { mutableState.update { it.copy(subscriptionQos = value) } }
    fun setPublishTopic(value: String) { mutableState.update { it.copy(publishTopic = value) } }
    fun setPublishPayload(value: String) { mutableState.update { it.copy(publishPayload = value) } }
    fun setPublishQos(value: MqttQos) { mutableState.update { it.copy(publishQos = value) } }
    fun setRetain(value: Boolean) { mutableState.update { it.copy(retain = value) } }
    fun setPayloadFormat(value: MqttPayloadFormat) { mutableState.update { it.copy(payloadFormat = value) } }
    fun setShowHex(value: Boolean) { mutableState.update { it.copy(showHex = value) } }
    fun dismissFeedback() { mutableState.update { it.copy(error = null, feedback = null) } }
    fun clearMessages() { mutableState.update { it.copy(messages = emptyList(), receivedCount = 0) } }

    fun connect(config: MqttConnectionConfig) {
        if (state.value.busy || state.value.connection.status != MqttConnectionStatus.DISCONNECTED) return
        mutableState.update { it.copy(endpoint = config.url, clientId = config.clientId,
            username = config.username.orEmpty(), password = config.password.orEmpty()) }
        connect()
    }

    fun connect() {
        val current = state.value
        if (current.connection.status != MqttConnectionStatus.DISCONNECTED) return
        val config = MqttConnectionConfig(
            url = current.endpoint.trim(),
            clientId = current.clientId.trim(),
            username = current.username.takeIf(String::isNotEmpty),
            password = current.password.takeIf(String::isNotEmpty),
        )
        execute("连接超时，请检查服务器地址与网络") {
            require(config.url.isNotEmpty()) { "请输入服务器地址" }
            require(config.clientId.isNotEmpty()) { "请输入 Client ID" }
            repository.connect(config)
            "已连接 MQTT 服务器"
        }
    }

    /** Disconnect also cancels an in-flight connection or command, while awaiting its cleanup. */
    fun disconnect() {
        val previous = operation
        val token = ++generation
        mutableState.update { it.copy(busy = true, error = null, feedback = null) }
        operation = viewModelScope.launch {
            try {
                previous?.cancelAndJoin()
                withTimeout(OPERATION_TIMEOUT) { repository.disconnect() }
                if (generation == token) mutableState.update { it.copy(feedback = "已断开连接") }
            } catch (timeout: TimeoutCancellationException) {
                if (generation == token) mutableState.update { it.copy(error = "断开连接超时") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == token) mutableState.update { it.copy(error = safeError(failure.message ?: "断开连接失败")) }
            } finally {
                if (generation == token) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun subscribe() {
        val current = state.value
        if (!current.connected) return
        execute("订阅超时，请检查连接状态后重试") {
            repository.subscribe(current.subscriptionFilter, current.subscriptionQos)
            "已订阅 ${current.subscriptionFilter}"
        }
    }

    fun unsubscribe(topicFilter: String) {
        if (!state.value.connected) return
        execute("取消订阅超时，请检查连接状态后重试") {
            repository.unsubscribe(topicFilter)
            "已取消订阅 $topicFilter"
        }
    }

    fun publish() {
        val current = state.value
        if (!current.connected) return
        execute("发布超时，送达状态未知；请确认后再决定是否重发") {
            val payload = when (current.payloadFormat) {
                MqttPayloadFormat.TEXT -> current.publishPayload.encodeToByteArray()
                MqttPayloadFormat.HEX -> decodeHex(current.publishPayload)
            }
            repository.publish(current.publishTopic, payload, current.publishQos, current.retain)
            record(MqttMessageDirection.SENT, current.publishTopic, payload, current.publishQos, current.retain)
            if (current.publishQos == MqttQos.AT_MOST_ONCE) "消息已提交 · QoS 0 无服务器确认"
            else "服务器已确认发布 · 设备执行结果需业务消息确认"
        }
    }

    private fun editConnection(change: MqttWorkbenchUiState.() -> MqttWorkbenchUiState) {
        mutableState.update {
            if (it.busy || it.connection.status != MqttConnectionStatus.DISCONNECTED) it else it.change()
        }
    }

    private fun execute(timeoutMessage: String, action: suspend () -> String) {
        if (state.value.busy) return
        val token = ++generation
        mutableState.update { it.copy(busy = true, error = null, feedback = null) }
        operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val feedback = withTimeout(OPERATION_TIMEOUT) { action() }
                if (generation == token) mutableState.update { it.copy(feedback = feedback) }
            } catch (timeout: TimeoutCancellationException) {
                if (generation == token) mutableState.update { it.copy(error = timeoutMessage) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == token) mutableState.update { it.copy(error = safeError(failure.message ?: "MQTT 操作失败")) }
            } finally {
                if (generation == token) mutableState.update { it.copy(busy = false) }
            }
        }.also { it.start() }
    }

    private fun record(direction: MqttMessageDirection, topic: String, payload: ByteArray, qos: MqttQos, retained: Boolean) {
        val preview = payload.copyOfRange(0, minOf(payload.size, MAX_PREVIEW_BYTES))
        val record = MqttMessageRecord(
            id = ++nextRecordId, direction = direction, topic = topic,
            text = preview.decodeToString(), hex = preview.joinToString(" ") { it.toUByte().toString(16).padStart(2, '0').uppercase() },
            byteCount = payload.size, qos = qos, retained = retained, truncated = preview.size < payload.size,
        )
        mutableState.update { it.copy(
            messages = (listOf(record) + it.messages).take(maximumLogEntries),
            receivedCount = it.receivedCount + if (direction == MqttMessageDirection.RECEIVED) 1 else 0,
        ) }
    }

    private fun safeError(message: String): String {
        val password = mutableState.value.password
        val withoutPassword = if (password.isEmpty()) message else message.replace(password, "••••")
        return withoutPassword.replace(Regex("(?i)(wss?://)[^/@\\s]+@"), "$1<已隐藏>@")
    }

    override fun onCleared() { repository.close() }

    companion object {
        private const val OPERATION_TIMEOUT = 20_000L
        private const val MAX_PREVIEW_BYTES = 2_048

        internal fun decodeHex(value: String): ByteArray {
            val compact = value.filterNot(Char::isWhitespace)
            require(compact.length % 2 == 0 && compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "HEX 请使用成对的十六进制字符，例如 01 A0 FF"
            }
            return ByteArray(compact.length / 2) { index -> compact.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }
    }
}
