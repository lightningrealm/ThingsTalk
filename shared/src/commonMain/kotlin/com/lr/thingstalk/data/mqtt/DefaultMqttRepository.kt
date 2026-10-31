package com.lr.thingstalk.data.mqtt

import com.lr.thingstalk.domain.mqtt.MqttConnectionConfig
import com.lr.thingstalk.domain.mqtt.MqttConnectionState
import com.lr.thingstalk.domain.mqtt.MqttConnectionStatus
import com.lr.thingstalk.domain.mqtt.MqttException
import com.lr.thingstalk.domain.mqtt.MqttMessage
import com.lr.thingstalk.domain.mqtt.MqttQos
import com.lr.thingstalk.domain.mqtt.MqttRepository
import com.lr.thingstalk.domain.mqtt.MqttSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

fun createMqttRepository(scope: CoroutineScope): MqttRepository = DefaultMqttRepository(scope, ::KtorMqttTransport)

/** Serializes user operations while session state and terminal close remain safe during an in-flight call. */
internal class DefaultMqttRepository(
    parentScope: CoroutineScope,
    private val transportFactory: (MqttConnectionConfig) -> MqttTransport,
) : MqttRepository {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val operationMutex = Mutex()
    private val snapshot = MutableStateFlow(Snapshot())
    private val received = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 64)

    override val state: StateFlow<MqttConnectionState> = SnapshotStateFlow(snapshot) { it.state }
    override val subscriptions: StateFlow<List<MqttSubscription>> = SnapshotStateFlow(snapshot) { it.subscriptions }
    override val messages = received.asSharedFlow()

    init {
        job.invokeOnCompletion { close() }
    }

    override suspend fun connect(config: MqttConnectionConfig) = operationMutex.withLock {
        ensureOpen()
        val validated = config.validated()
        disconnectSession()
        ensureOpen()
        val session = try {
            Session(transportFactory(validated), scope)
        } catch (_: Exception) {
            throw MqttException("无法创建 MQTT 客户端，请检查连接配置。")
        }
        snapshot.update {
            if (it.closed) it else it.copy(
                session = session,
                state = MqttConnectionState(MqttConnectionStatus.CONNECTING, validated.url),
                subscriptions = emptyList(),
            )
        }
        if (snapshot.value.session !== session) {
            session.close()
            throw MqttException("MQTT 会话已关闭。")
        }
        watchMessages(session)
        try {
            withTimeout(15_000) { session.transport.connect() }
            snapshot.update {
                if (it.session === session) it.copy(state = it.state.copy(status = MqttConnectionStatus.CONNECTED)) else it
            }
            // Start after the handshake: this collector's initial value is authoritative. Watching
            // before connect can conflate false -> true -> false and miss an immediate disconnect.
            watchConnection(session)
            mqttRequire(snapshot.value.session === session) { "连接已结束，请重新连接。" }
        } catch (error: TimeoutCancellationException) {
            loseSession(session, "MQTT 连接超时，请检查 WebSocket 地址和网络。")
            throw MqttException("MQTT 连接超时，请检查 WebSocket 地址和网络。")
        } catch (error: CancellationException) {
            loseSession(session, null)
            throw error
        } catch (error: Exception) {
            val message = (error as? MqttException)?.message ?: "MQTT 连接失败，请检查网络、WebSocket 路径和认证信息。"
            loseSession(session, message)
            throw MqttException(message)
        }
    }

    override suspend fun disconnect() = operationMutex.withLock { disconnectSession() }

    override suspend fun subscribe(topicFilter: String, qos: MqttQos) {
        validateTopic(topicFilter, filter = true)
        operation("订阅") { session ->
            val granted = session.transport.subscribe(topicFilter, qos)
            snapshot.update {
                if (it.session === session) it.copy(
                    subscriptions = it.subscriptions.filterNot { item -> item.topicFilter == topicFilter } +
                        MqttSubscription(topicFilter, granted),
                ) else it
            }
        }
    }

    override suspend fun unsubscribe(topicFilter: String) {
        validateTopic(topicFilter, filter = true)
        operation("取消订阅") { session ->
            session.transport.unsubscribe(topicFilter)
            snapshot.update {
                if (it.session === session) it.copy(subscriptions = it.subscriptions.filterNot { item -> item.topicFilter == topicFilter }) else it
            }
        }
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean) {
        validateTopic(topic, filter = false)
        mqttRequire(payload.size <= 1_000_000) { "单条消息最多支持 1000000 字节。" }
        val ownedPayload = payload.copyOf()
        operation("发布") { it.transport.publish(topic, ownedPayload, qos, retain) }
    }

    override fun close() {
        val previous = snapshot.getAndUpdate {
            it.copy(closed = true, session = null, subscriptions = emptyList(), state = it.state.copy(status = MqttConnectionStatus.DISCONNECTED, error = null))
        }
        if (previous.closed) return
        previous.session?.close()
        scope.cancel()
    }

    private suspend fun operation(action: String, block: suspend (Session) -> Unit) = operationMutex.withLock {
        ensureOpen()
        val current = snapshot.value
        val session = current.session
        mqttRequire(session != null && current.state.status == MqttConnectionStatus.CONNECTED) { "请先连接 MQTT 服务器。" }
        session!!
        try {
            withTimeout(15_000) { block(session) }
            mqttRequire(snapshot.value.session === session) { "MQTT 连接已断开，请重新连接后重试。" }
        } catch (_: TimeoutCancellationException) {
            val message = "${action}超时，连接已关闭；操作可能已到达服务器，请确认后重试。"
            loseSession(session, message)
            throw MqttException(message)
        } catch (error: CancellationException) {
            // A cancelled SUBSCRIBE could otherwise leave an untracked subscription at the broker.
            loseSession(session, null)
            throw error
        } catch (error: MqttException) {
            throw error
        } catch (_: Exception) {
            val message = "${action}失败，连接已关闭；请检查网络和服务器后重新连接。"
            loseSession(session, message)
            throw MqttException(message)
        }
    }

    private fun ensureOpen() = mqttRequire(!snapshot.value.closed && job.isActive) { "MQTT 会话已关闭。" }

    private suspend fun disconnectSession() {
        val previous = snapshot.getAndUpdate {
            it.copy(session = null, subscriptions = emptyList(), state = it.state.copy(status = MqttConnectionStatus.DISCONNECTED, error = null))
        }
        val session = previous.session ?: return
        session.scope.cancel()
        try {
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { runCatching { session.transport.disconnect() } }
            }
        } finally {
            session.close()
        }
    }

    private fun watchMessages(session: Session) {
        session.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.transport.messages.collect {
                    if (snapshot.value.session === session) received.emit(it)
                }
                loseSession(session, "MQTT 消息接收已结束，请重新连接。")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                loseSession(session, "MQTT 消息接收已中断，请重新连接。")
            }
        }
    }

    private fun watchConnection(session: Session) {
        session.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.transport.connected.collect { connected ->
                    if (!connected) loseSession(session, "MQTT 连接已断开，请重新连接。")
                }
                loseSession(session, "MQTT 连接已结束，请重新连接。")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                loseSession(session, "MQTT 连接已中断，请重新连接。")
            }
        }
    }

    private fun loseSession(session: Session, error: String?) {
        val previous = snapshot.getAndUpdate {
            if (it.session === session) it.copy(
                session = null,
                subscriptions = emptyList(),
                state = it.state.copy(status = MqttConnectionStatus.DISCONNECTED, error = error),
            ) else it
        }
        if (previous.session === session) session.close()
    }

    private data class Snapshot(
        val closed: Boolean = false,
        val session: Session? = null,
        val state: MqttConnectionState = MqttConnectionState(),
        val subscriptions: List<MqttSubscription> = emptyList(),
    )

    private class Session(val transport: MqttTransport, parent: CoroutineScope) {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        private val closed = MutableStateFlow(false)
        fun close() {
            if (closed.compareAndSet(expect = false, update = true)) {
                scope.cancel()
                transport.close()
            }
        }
    }
}

/** Both public state values reflect one atomic snapshot, including during close or connection loss. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class SnapshotStateFlow<S, T>(private val source: StateFlow<S>, private val transform: (S) -> T) : StateFlow<T> {
    override val value: T get() = transform(source.value)
    override val replayCache: List<T> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        error("StateFlow collection cannot complete")
    }
}
