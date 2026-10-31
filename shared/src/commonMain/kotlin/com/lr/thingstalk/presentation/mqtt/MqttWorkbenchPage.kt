package com.lr.thingstalk.presentation.mqtt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lr.thingstalk.domain.mqtt.MqttConnectionStatus
import com.lr.thingstalk.domain.mqtt.MqttQos
import com.lr.thingstalk.presentation.components.Panel
import com.lr.thingstalk.presentation.components.LocalCompactHeight
import com.lr.thingstalk.presentation.components.Primary
import com.lr.thingstalk.presentation.components.Section
import com.lr.thingstalk.presentation.components.Tag
import com.lr.thingstalk.presentation.components.Toggle
import com.lr.thingstalk.presentation.theme.*

@Composable
fun MqttWorkbenchPage(viewModel: MqttWorkbenchViewModel, onProfiles: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val compact = LocalCompactHeight.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 660.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = if (compact) 20.dp else if (wide) 28.dp else 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text("MESSAGE WORKBENCH", color = Accent, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("MQTT", Modifier.weight(1f), style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Tag(when (state.connection.status) {
                    MqttConnectionStatus.DISCONNECTED -> "未连接"
                    MqttConnectionStatus.CONNECTING -> "连接中"
                    MqttConnectionStatus.CONNECTED -> "已连接"
                })
            }
            Text("通过 Topic 收发设备消息。支持 MQTT 5，使用 WebSocket 地址连接服务器。", color = Muted, style = MaterialTheme.typography.bodyMedium)
            val error = state.error ?: state.connection.error
            if (error != null || state.feedback != null) {
                Surface(
                    color = if (error != null) MaterialTheme.colorScheme.errorContainer else Soft,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(error ?: state.feedback.orEmpty(), Modifier.padding(16.dp),
                        color = if (error != null) MaterialTheme.colorScheme.onErrorContainer else Ink,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 20.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        ConnectionPanel(state, viewModel, onProfiles)
                        SubscriptionsPanel(state, viewModel)
                    }
                    Column(Modifier.weight(1.12f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        PublishPanel(state, viewModel)
                        MessagesPanel(state, viewModel)
                    }
                }
            } else {
                ConnectionPanel(state, viewModel, onProfiles)
                SubscriptionsPanel(state, viewModel)
                PublishPanel(state, viewModel)
                MessagesPanel(state, viewModel)
            }
        }
    }
}

@Composable
private fun ConnectionPanel(state: MqttWorkbenchUiState, viewModel: MqttWorkbenchViewModel, onProfiles: () -> Unit) {
    val canEdit = !state.busy && state.connection.status == MqttConnectionStatus.DISCONNECTED
    val profiles = LocalMqttProfiles.current
    val archive by profiles.profiles.collectAsState()
    val selected = archive.selectedProfile("workbench")
    Panel {
        Section("连接服务器")
        MqttProfilePicker("workbench", canEdit, onProfiles)
        if (state.connection.status != MqttConnectionStatus.DISCONNECTED) Text("当前连接：${state.connection.endpoint ?: state.endpoint}", color = Muted, style = MaterialTheme.typography.bodySmall)
        when (state.connection.status) {
            MqttConnectionStatus.DISCONNECTED -> Primary(
                if (state.busy) "处理中…" else "连接", enabled = canEdit && selected != null,
                action = { profiles.connect("workbench", viewModel::connect) },
            )
            MqttConnectionStatus.CONNECTING -> OutlinedButton(
                onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp),
            ) { Text("取消连接") }
            MqttConnectionStatus.CONNECTED -> OutlinedButton(
                onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp),
            ) { Text("断开连接") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("网页使用 HTTPS 时，请连接 WSS 地址。切换页面会保留连接；断线后可手动重连并重新订阅。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SubscriptionsPanel(state: MqttWorkbenchUiState, viewModel: MqttWorkbenchViewModel) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("订阅", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${state.subscriptions.size} 个主题", color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        OutlinedTextField(
            value = state.subscriptionFilter, onValueChange = viewModel::setSubscriptionFilter,
            label = { Text("Topic Filter") }, placeholder = { Text("home/+/temperature") },
            supportingText = { Text("+ 匹配一级主题，# 匹配多级主题") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        )
        QosChoices(state.subscriptionQos, viewModel::setSubscriptionQos)
        Primary("订阅主题", enabled = state.connected && !state.busy && state.subscriptionFilter.isNotBlank(), action = viewModel::subscribe)
        if (state.subscriptions.isEmpty()) {
            Text("订阅后，匹配的消息会出现在消息记录中。", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else state.subscriptions.forEach { subscription ->
            HorizontalDivider(color = Line)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer { Text(subscription.topicFilter, style = MaterialTheme.typography.bodyMedium) }
                    Text("QoS ${subscription.qos.ordinal}", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { viewModel.unsubscribe(subscription.topicFilter) }, enabled = state.connected && !state.busy) { Text("取消") }
            }
        }
    }
}

@Composable
private fun PublishPanel(state: MqttWorkbenchUiState, viewModel: MqttWorkbenchViewModel) {
    Panel {
        Section("发布消息")
        OutlinedTextField(
            value = state.publishTopic, onValueChange = viewModel::setPublishTopic,
            label = { Text("Topic") }, placeholder = { Text("home/desk/command") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.payloadFormat == MqttPayloadFormat.TEXT, { viewModel.setPayloadFormat(MqttPayloadFormat.TEXT) }, label = { Text("文本 UTF-8") })
            FilterChip(state.payloadFormat == MqttPayloadFormat.HEX, { viewModel.setPayloadFormat(MqttPayloadFormat.HEX) }, label = { Text("HEX") })
        }
        OutlinedTextField(
            value = state.publishPayload, onValueChange = viewModel::setPublishPayload,
            label = { Text("Payload") }, placeholder = { Text(if (state.payloadFormat == MqttPayloadFormat.HEX) "01 A0 FF" else "输入消息内容") },
            minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium.let { if (state.payloadFormat == MqttPayloadFormat.HEX) it.copy(fontFamily = FontFamily.Monospace) else it },
        )
        QosChoices(state.publishQos, viewModel::setPublishQos)
        Toggle("Retain", "服务器保留此主题的最新消息，供后续订阅者接收", state.retain, viewModel::setRetain)
        if (state.retain && state.publishPayload.isEmpty()) {
            Text("空 Payload + Retain 会清除服务器上该主题的保留消息。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Primary("发布消息", enabled = state.connected && !state.busy && state.publishTopic.isNotBlank(), action = viewModel::publish)
    }
}

@Composable
private fun QosChoices(selected: MqttQos, select: (MqttQos) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MqttQos.entries.forEach { qos ->
            FilterChip(selected == qos, { select(qos) }, label = { Text("QoS ${qos.ordinal}") })
        }
    }
    Text(when (selected) {
        MqttQos.AT_MOST_ONCE -> "至多一次 · 不等待消息确认"
        MqttQos.AT_LEAST_ONCE -> "至少一次 · 消息可能重复"
        MqttQos.EXACTLY_ONCE -> "恰好一次 · MQTT 协议层去重"
    }, color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun MessagesPanel(state: MqttWorkbenchUiState, viewModel: MqttWorkbenchViewModel) {
    Panel {
        Section("消息记录", "清空", viewModel::clearMessages)
        Text("已接收 ${state.receivedCount} 条 · 最近 100 条收发记录 · 新消息在上方", color = Muted, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!state.showHex, { viewModel.setShowHex(false) }, label = { Text("文本") })
            FilterChip(state.showHex, { viewModel.setShowHex(true) }, label = { Text("HEX") })
        }
        if (state.messages.isEmpty()) {
            Text("还没有消息", style = MaterialTheme.typography.titleMedium)
            Text("连接服务器后，订阅一个 Topic，再向同一 Topic 发布消息即可验证收发。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (state.messages.isNotEmpty()) LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                Surface(color = Paper, border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(buildString {
                            append(if (message.direction == MqttMessageDirection.RECEIVED) "收到" else "发出")
                            append(" · QoS ${message.qos.ordinal} · ${message.byteCount} B")
                            if (message.retained) append(" · Retain")
                        }, color = Accent, style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(message.topic, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                        SelectionContainer {
                            Text(if (message.byteCount == 0) "（空 Payload）" else if (state.showHex) message.hex else message.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = if (state.showHex) FontFamily.Monospace else null)
                        }
                        if (message.truncated) Text("预览仅显示前 2 KB", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
