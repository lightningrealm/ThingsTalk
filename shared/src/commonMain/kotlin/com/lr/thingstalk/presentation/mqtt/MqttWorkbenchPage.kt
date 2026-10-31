package com.lr.thingstalk.presentation.mqtt

import com.lr.thingstalk.presentation.i18n.*
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
                    MqttConnectionStatus.DISCONNECTED -> tr(AppStrings.disconnected_f2f3e9)
                    MqttConnectionStatus.CONNECTING -> tr(AppStrings.connecting_699ade)
                    MqttConnectionStatus.CONNECTED -> tr(AppStrings.connected_65fe35)
                })
            }
            Text(tr(AppStrings.exchange_messages_through_topics_supports_mqtt_5_over_w_7f61bd), color = Muted, style = MaterialTheme.typography.bodyMedium)
            val error = state.error ?: state.connection.error
            if (error != null || state.feedback != null) {
                Surface(
                    color = if (error != null) MaterialTheme.colorScheme.errorContainer else Soft,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(diagnosticText(error ?: state.feedback.orEmpty()), Modifier.padding(16.dp),
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
        Section(tr(AppStrings.connect_to_server_65c838))
        MqttProfilePicker("workbench", canEdit, onProfiles)
        if (state.connection.status != MqttConnectionStatus.DISCONNECTED) Text(tr(AppStrings.current_connection_fb9be9, state.connection.endpoint ?: state.endpoint), color = Muted, style = MaterialTheme.typography.bodySmall)
        when (state.connection.status) {
            MqttConnectionStatus.DISCONNECTED -> Primary(
                if (state.busy) tr(AppStrings.working_1cac8a) else tr(AppStrings.connection_7328de), enabled = canEdit && selected != null,
                action = { profiles.connect("workbench", viewModel::connect) },
            )
            MqttConnectionStatus.CONNECTING -> OutlinedButton(
                onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp),
            ) { Text(tr(AppStrings.cancel_connection_4523e5)) }
            MqttConnectionStatus.CONNECTED -> OutlinedButton(
                onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp),
            ) { Text(tr(AppStrings.disconnect_eb7246)) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(tr(AppStrings.use_wss_when_this_page_uses_https_navigation_keeps_the_a125a0), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SubscriptionsPanel(state: MqttWorkbenchUiState, viewModel: MqttWorkbenchViewModel) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(AppStrings.subscribe_5319af), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(tr(AppStrings.topics_4eeb23, state.subscriptions.size), color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        OutlinedTextField(
            value = state.subscriptionFilter, onValueChange = viewModel::setSubscriptionFilter,
            label = { Text("Topic Filter") }, placeholder = { Text("home/+/temperature") },
            supportingText = { Text(tr(AppStrings.matches_one_topic_level_matches_multiple_levels_299fdb)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        )
        QosChoices(state.subscriptionQos, viewModel::setSubscriptionQos)
        Primary(tr(AppStrings.subscribe_to_topic_65cd76), enabled = state.connected && !state.busy && state.subscriptionFilter.isNotBlank(), action = viewModel::subscribe)
        if (state.subscriptions.isEmpty()) {
            Text(tr(AppStrings.matching_messages_appear_in_the_message_log_after_subsc_023096), color = Muted, style = MaterialTheme.typography.bodySmall)
        } else state.subscriptions.forEach { subscription ->
            HorizontalDivider(color = Line)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer { Text(subscription.topicFilter, style = MaterialTheme.typography.bodyMedium) }
                    Text("QoS ${subscription.qos.ordinal}", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { viewModel.unsubscribe(subscription.topicFilter) }, enabled = state.connected && !state.busy) { Text(tr(AppStrings.cancel_4d0b46)) }
            }
        }
    }
}

@Composable
private fun PublishPanel(state: MqttWorkbenchUiState, viewModel: MqttWorkbenchViewModel) {
    Panel {
        Section(tr(AppStrings.publish_message_0598a9))
        OutlinedTextField(
            value = state.publishTopic, onValueChange = viewModel::setPublishTopic,
            label = { Text("Topic") }, placeholder = { Text("home/desk/command") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.payloadFormat == MqttPayloadFormat.TEXT, { viewModel.setPayloadFormat(MqttPayloadFormat.TEXT) }, label = { Text(tr(AppStrings.utf_8_text_2d9a59)) })
            FilterChip(state.payloadFormat == MqttPayloadFormat.HEX, { viewModel.setPayloadFormat(MqttPayloadFormat.HEX) }, label = { Text("HEX") })
        }
        OutlinedTextField(
            value = state.publishPayload, onValueChange = viewModel::setPublishPayload,
            label = { Text("Payload") }, placeholder = { Text(if (state.payloadFormat == MqttPayloadFormat.HEX) "01 A0 FF" else tr(AppStrings.enter_message_content_dabb75)) },
            minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium.let { if (state.payloadFormat == MqttPayloadFormat.HEX) it.copy(fontFamily = FontFamily.Monospace) else it },
        )
        QosChoices(state.publishQos, viewModel::setPublishQos)
        Toggle("Retain", tr(AppStrings.keep_the_latest_message_on_this_topic_for_future_subscr_619b6a), state.retain, viewModel::setRetain)
        if (state.retain && state.publishPayload.isEmpty()) {
            Text(tr(AppStrings.an_empty_payload_with_retain_clears_the_retained_messag_c213d5), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Primary(tr(AppStrings.publish_message_0598a9), enabled = state.connected && !state.busy && state.publishTopic.isNotBlank(), action = viewModel::publish)
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
        MqttQos.AT_MOST_ONCE -> tr(AppStrings.at_most_once_no_acknowledgment_6c4174)
        MqttQos.AT_LEAST_ONCE -> tr(AppStrings.at_least_once_duplicates_possible_15c0f8)
        MqttQos.EXACTLY_ONCE -> tr(AppStrings.exactly_once_deduplicated_at_the_mqtt_protocol_level_0188a3)
    }, color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun MessagesPanel(state: MqttWorkbenchUiState, viewModel: MqttWorkbenchViewModel) {
    Panel {
        Section(tr(AppStrings.message_log_0fe8bf), tr(AppStrings.clear_84fcd7), viewModel::clearMessages)
        Text(tr(AppStrings.received_latest_100_traffic_records_newest_first_e3c326, state.receivedCount), color = Muted, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!state.showHex, { viewModel.setShowHex(false) }, label = { Text(tr(AppStrings.text_f1926e)) })
            FilterChip(state.showHex, { viewModel.setShowHex(true) }, label = { Text("HEX") })
        }
        if (state.messages.isEmpty()) {
            Text(tr(AppStrings.no_messages_yet_2c0e32), style = MaterialTheme.typography.titleMedium)
            Text(tr(AppStrings.connect_subscribe_to_a_topic_then_publish_to_the_same_t_b425f0), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (state.messages.isNotEmpty()) LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                Surface(color = Paper, border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(buildString {
                            append(if (message.direction == MqttMessageDirection.RECEIVED) tr(AppStrings.received_e37d13) else tr(AppStrings.sent_3d391b))
                            append(" · QoS ${message.qos.ordinal} · ${message.byteCount} B")
                            if (message.retained) append(" · Retain")
                        }, color = Accent, style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(message.topic, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                        SelectionContainer {
                            Text(if (message.byteCount == 0) tr(AppStrings.empty_payload_66d3b4) else if (state.showHex) message.hex else message.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = if (state.showHex) FontFamily.Monospace else null)
                        }
                        if (message.truncated) Text(tr(AppStrings.preview_limited_to_the_first_2_kb_ec1096), color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
