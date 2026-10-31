package com.lr.thingstalk.data.activity

import com.lr.thingstalk.domain.activity.ActivityRepository
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.mqtt.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Receives sanitized entries at emission time, never diffs a truncated UI history. */
class BleActivitySource(private val journal: ActivityRepository, private val id: String, private val name: () -> String) {
    private var connected = false
    private var intentional = false
    fun record(entry: BleTrafficEntry) {
        val ref = journal.record(id, name(), "BLE", entry.operation, entry.characteristic?.let { "${it.serviceUuid}/${it.characteristicUuid}" }.orEmpty(), entry.detail, entry.hex, entry.byteCount)
        if (entry.operation == "connect.request") intentional = false
        if (entry.operation == "disconnect.request" || entry.operation == "close.request") intentional = true
        if (entry.operation == "close.request" && connected) {
            connected = false
            if (journal.archive.value.recordConnectionEvents) journal.event(id, name(), "BLE", "BLE 已停止", "本机会话已关闭", recordId = ref)
        }
        if (entry.operation == "connection.state") {
            if (entry.detail == "CONNECTED") {
                if (!connected && journal.archive.value.recordConnectionEvents) journal.event(id, name(), "BLE", "BLE 已连接", "本机 BLE 链路已建立", recordId = ref)
                connected = true
            } else if (entry.detail == "DISCONNECTED" && connected) {
                if (journal.archive.value.recordConnectionEvents) journal.event(id, name(), "BLE", if (intentional) "BLE 已停止" else "BLE 连接中断", if (intentional) "本机会话已关闭" else "链路断开，不等同于设备故障", !intentional, ref)
                connected = false
            }
        }
        if (entry.direction == BleTrafficDirection.ERROR) journal.event(id, name(), "BLE", "BLE 通信失败", "${entry.operation} 未完成；可查看关联通信记录", true, ref)
    }
}

class ActivityMqttRepository(private val delegate: MqttRepository, scope: CoroutineScope,
    private val journal: ActivityRepository, private val id: String, private val name: () -> String) : MqttRepository by delegate {
    private var active = false
    private var intentional = false
    private val reception = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        delegate.messages.collect { m ->
            journal.record(id, name(), "MQTT", "message.received", m.topic,
                "QoS ${m.qos.ordinal} · retained=${m.retained}", m.payload.take(512).joinToString(" ") { b -> (b.toInt() and 255).toString(16).padStart(2,'0') }, m.payload.size)
        }
    }
    private val status = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        delegate.state.collect { s ->
            if (s.status == MqttConnectionStatus.CONNECTED && !active) {
                active = true
                val ref = journal.record(id, name(), "MQTT", "connection.connected", detail = "Broker 已接受连接")
                if (journal.archive.value.recordConnectionEvents) journal.event(id, name(), "MQTT", "MQTT 已连接", "Broker 已接受连接；不代表设备在线或订阅获准", recordId = ref)
            } else if (s.status == MqttConnectionStatus.DISCONNECTED && active) {
                active = false
                val ref = journal.record(id, name(), "MQTT", "connection.disconnected")
                if (journal.archive.value.recordConnectionEvents) journal.event(id, name(), "MQTT", if (intentional) "MQTT 已停止" else "MQTT 连接中断", if (intentional) "本机会话已关闭" else "与 Broker 的会话已断开", !intentional, ref)
            }
        }
    }
    private suspend fun <T> operation(label: String, address: String = "", block: suspend () -> T): T {
        try { return block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val ref = journal.record(id, name(), "MQTT", "$label.failed", address, "操作失败，未保存底层异常或凭据")
            journal.event(id, name(), "MQTT", "MQTT 操作失败", "$label 失败，请检查连接、认证与主题权限", true, ref)
            throw failure
        }
    }
    override suspend fun connect(config: MqttConnectionConfig) { intentional = false; operation("connect") { delegate.connect(config) } }
    override suspend fun disconnect() { intentional = true; delegate.disconnect() }
    override suspend fun subscribe(topicFilter: String, qos: MqttQos) = operation("subscribe", topicFilter) {
        delegate.subscribe(topicFilter, qos)
        journal.record(id, name(), "MQTT", "subscribe.acknowledged", topicFilter, "Broker 已确认订阅")
        Unit
    }
    override suspend fun unsubscribe(topicFilter: String) = operation("unsubscribe", topicFilter) {
        delegate.unsubscribe(topicFilter); journal.record(id, name(), "MQTT", "unsubscribe.acknowledged", topicFilter); Unit
    }
    override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean) = operation("publish", topic) {
        journal.record(id, name(), "MQTT", "publish.attempt", topic, "QoS ${qos.ordinal} · retained=$retain", payload.take(512).joinToString(" ") { (it.toInt() and 255).toString(16).padStart(2,'0') }, payload.size)
        delegate.publish(topic, payload, qos, retain)
        journal.record(id, name(), "MQTT", "publish.completed", topic, if (qos == MqttQos.AT_MOST_ONCE) "已发送；无 Broker 确认" else "Broker 已确认；不代表设备执行成功")
        Unit
    }
    override fun close() {
        intentional = true
        reception.cancel(); status.cancel()
        if (active) {
            active = false
            val ref = journal.record(id, name(), "MQTT", "connection.closed", detail = "本机会话已关闭")
            if (journal.archive.value.recordConnectionEvents) journal.event(id, name(), "MQTT", "MQTT 已停止", "本机会话已关闭", recordId = ref)
        }
        delegate.close()
    }
}

class ActivitySetupSession(private val delegate: SetupPanelSession, private val journal: ActivityRepository,
    private val id: String, private val name: () -> String, private val form: SetupPanel) : SetupPanelSession by delegate {
    override suspend fun submit(sectionId: Int, values: Map<Int, String>): NetworkSetupStatus {
        try {
            val status = delegate.submit(sectionId, values)
            journal.event(id, name(), "BLE", "设备配置已保存", "${form.sections.single { it.id == sectionId }.title}：设备已确认保存，不代表网络已连接")
            return status
        } catch (timeout: TimeoutCancellationException) {
            journal.event(id, name(), "BLE", "配置保存结果未知", "等待超时；请重新读取核实，未自动重发", true); throw timeout
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            journal.event(id, name(), "BLE", "设备配置未确认保存", if (failure is SetupPanelFailure) "阶段：${failure.kind}；请查看设备页面诊断" else "校验或通信失败，未自动重发", true)
            throw failure
        }
    }
}
