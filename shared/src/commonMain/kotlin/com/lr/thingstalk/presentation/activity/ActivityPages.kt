package com.lr.thingstalk.presentation.activity

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.lr.thingstalk.domain.activity.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.navigation.*
import com.lr.thingstalk.presentation.device.DeviceWorkspaceViewModel
import com.lr.thingstalk.presentation.panel.*
import com.lr.thingstalk.presentation.theme.Muted
import com.lr.thingstalk.getPlatform
import com.lr.thingstalk.platformCapabilities
import kotlin.time.Instant

private fun ReadUpdateResult.notice(): String = error ?: tr(AppStrings.updated_unread_remaining_5fa648, changed, unread)

private fun timeLabel(value: Long) = Instant.fromEpochMilliseconds(value).toString().replace('T',' ').substringBefore('.').removeSuffix("Z") + " UTC"

@Composable
fun ActivityPages(route: Route, activity: ActivityRepository, workspace: DeviceWorkspaceViewModel,
    go: (Route) -> Unit, feedback: (String) -> Unit) {
    val archive by activity.archive.collectAsState()
    val records = if (route.screen in setOf(Screen.EVENT, Screen.ACTIVITY_RECORD, Screen.DATA_SETTINGS))
        activity.records.collectAsState().value else emptyList()
    val error by activity.error.collectAsState()
    val saving by activity.saving.collectAsState()
    val devices by workspace.devices.collectAsState()
    when (route.screen) {
        Screen.EVENTS -> {
            var view by remember { mutableStateOf(if (route.id == "traffic") "choice:traffic_7c4ff3" else "choice:device_events_814c23") }
            var filter by remember { mutableStateOf("choice:all_778fc8") }
            var transport by remember { mutableStateOf("choice:all_778fc8") }
            var selectedDevice by remember { mutableStateOf("") }
            var query by remember { mutableStateOf("") }
            var limit by remember(view, filter, transport, selectedDevice, query) { mutableStateOf(50) }
            Page("ACTIVITY", tr(AppStrings.messages_dc6de3), tr(AppStrings.actual_traffic_and_events_only_reception_is_not_guarant_f42a09)) {
                Choices(listOf("choice:device_events_814c23", "choice:traffic_7c4ff3"), view) { view = it }
                Choices(if (platformCapabilities().ble) listOf("choice:all_778fc8","BLE","MQTT") else listOf("choice:all_778fc8","MQTT"), transport) { transport = it }
                OutlinedTextField(query, { query = it }, label = { Text(tr(AppStrings.search_devices_events_or_topic_uuid_f18a60)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                var chooseDevice by remember { mutableStateOf(false) }
                TextButton(onClick = { chooseDevice = !chooseDevice }) { Text(tr(AppStrings.device_4bd84f, devices.firstOrNull { it.id == selectedDevice }?.name ?: tr(AppStrings.all_778fc8))) }
                if (chooseDevice) {
                    TextButton(onClick = { selectedDevice = ""; chooseDevice = false }) { Text(tr(AppStrings.all_devices_and_workbench_eb1065)) }
                    devices.forEach { d -> TextButton(onClick = { selectedDevice = d.id; chooseDevice = false }) { Text(d.name) } }
                }
                if (view == "choice:device_events_814c23") {
                    Choices(listOf("choice:all_778fc8", "choice:unread_1e230a", "choice:needs_attention_59a9eb"), filter) { filter = it }
                    Text(tr(AppStrings.unread_kept_locally_for_days_826b13, archive.events.count { !it.read }, archive.retentionDays), color = Muted)
                    TextButton(onClick = { feedback(activity.markAllRead().notice()) }) { Text(tr(AppStrings.mark_all_as_read_203c3f)) }
                    error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
                    val rows = archive.events.filter { e -> (transport == "choice:all_778fc8" || e.transport == transport) && (selectedDevice.isEmpty() || e.deviceId == selectedDevice) &&
                        (filter == "choice:all_778fc8" || filter == "choice:unread_1e230a" && !e.read || filter == "choice:needs_attention_59a9eb" && e.warning && !e.acknowledged) &&
                        (diagnosticText(e.title) + e.deviceName + diagnosticText(e.detail)).contains(query, true) }
                    if (rows.isEmpty()) Panel { Text(tr(AppStrings.no_matching_events_8ccc71)); Text(tr(AppStrings.connect_or_operate_a_device_to_see_real_events_here_a9acdd), color = Muted) }
                    rows.take(limit).forEach { e -> key(e.id) { Item((if (!e.read) "● " else "") + diagnosticText(e.title),
                        "${e.deviceName} · ${e.transport} · ${timeLabel(e.timestamp)}${if (e.count > 1) tr(AppStrings.occurrences_aa7812, e.count) else ""}") {
                        go(Route(Screen.EVENT, e.id))
                    } } }
                    if (rows.size > limit) TextButton(onClick = { limit += 50 }) { Text(tr(AppStrings.load_more_3a0fab)) }
                } else {
                    val records by activity.records.collectAsState()
                    Text(tr(AppStrings.latest_current_app_session_only_raw_payloads_hidden_by_321956, records.size), color = Muted)
                    val rows = records.filter { r -> (transport == "choice:all_778fc8" || r.transport == transport) && (selectedDevice.isEmpty() || r.deviceId == selectedDevice) && (r.deviceName + r.operation + r.address).contains(query, true) }
                    if (rows.isEmpty()) Text(tr(AppStrings.no_traffic_yet_566bf8), color = Muted)
                    rows.take(limit).forEach { r -> key(r.id) { Item("${r.transport} · ${r.operation}", "${r.deviceName} · ${r.address.take(80)} · ${timeLabel(r.timestamp)}") { go(Route(Screen.ACTIVITY_RECORD, r.id)) } } }
                    if (rows.size > limit) TextButton(onClick = { limit += 50 }) { Text(tr(AppStrings.load_more_3a0fab)) }
                }
                Item(tr(AppStrings.connections_16f545), tr(AppStrings.view_device_sessions_connect_or_stop_ccc8e0)) { go(Route(Screen.SUBSCRIPTIONS)) }
                error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error); TextButton(onClick = activity::retrySave) { Text(tr(AppStrings.retry_saving_c47e26)) } }
                if (saving) Text(tr(AppStrings.saving_local_changes_438efa), color = Muted)
            }
        }
        Screen.EVENT -> {
            val e = archive.events.firstOrNull { it.id == route.id }
            // Mark on entry, including restored/direct routes. Do not key on read: users may
            // explicitly mark this event unread while staying on the detail page.
            var readNotice by remember(route.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(activity, e?.id) {
                e?.let { readNotice = activity.markRead(it.id).notice() }
            }
            Page("EVENT", e?.title?.let(::diagnosticText) ?: tr(AppStrings.event_removed_dbe6a6), tr(AppStrings.local_event_details_45670b)) {
                if (e != null) {
                    Panel {
                        KeyValue(tr(AppStrings.devices_01f2c1), e.deviceName); KeyValue(tr(AppStrings.source_c63f79), e.transport); KeyValue(tr(AppStrings.last_occurred_c4dfc8), timeLabel(e.timestamp))
                        KeyValue(tr(AppStrings.occurrences_d4a97e), e.count.toString()); Text(diagnosticText(e.detail))
                        Text(if (e.read) tr(AppStrings.read_642ec8) else tr(AppStrings.unread_1e230a), color = Muted)
                    }
                    TextButton(onClick = { readNotice = activity.markRead(e.id, !e.read).notice() }) { Text(if (e.read) tr(AppStrings.mark_unread_64d2bc) else tr(AppStrings.mark_read_b1971f)) }
                    readNotice?.let { Text(diagnosticText(it), color = Muted) }
                    error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
                    if (saving) Text(tr(AppStrings.saving_read_status_locally_341f6f), color = Muted)
                    if (e.warning) Primary(if (e.acknowledged) tr(AppStrings.acknowledged_d9fea6) else tr(AppStrings.mark_resolved_b1f9fb), !e.acknowledged) { activity.acknowledge(e.id) }
                    Text(tr(AppStrings.acknowledgment_updates_local_records_only_no_device_com_2356b4), color = Muted)
                    val r = records.firstOrNull { it.id == e.recordId }
                    if (r != null) Item(tr(AppStrings.related_traffic_record_ab7a12), r.operation) { go(Route(Screen.ACTIVITY_RECORD, r.id)) }
                    else Text(tr(AppStrings.the_raw_traffic_record_was_not_kept_or_has_expired_6fafc3), color = Muted)
                    if (devices.any { it.id == e.deviceId }) Item(tr(AppStrings.open_device_487957), e.deviceName) { go(Route(Screen.DEVICE, e.deviceId)) }
                    else Text(if (e.deviceId.isEmpty()) tr(AppStrings.from_mqtt_workbench_184104) else tr(AppStrings.device_deleted_keeping_its_historical_name_eeece3), color = Muted)
                }
            }
        }
        Screen.ACTIVITY_RECORD -> {
            val r = records.firstOrNull { it.id == route.id }
            Page("TRAFFIC DETAIL", r?.operation ?: tr(AppStrings.traffic_record_expired_5b1ec9), tr(AppStrings.diagnostic_records_are_not_device_execution_receipts_d046b8)) {
                r?.let {
                    Panel { KeyValue(tr(AppStrings.devices_01f2c1), it.deviceName); KeyValue(tr(AppStrings.transport_14b6b3), it.transport); KeyValue(tr(AppStrings.time_89b4aa), timeLabel(it.timestamp)); KeyValue(tr(AppStrings.byte_count_7fd5ef), it.byteCount.toString())
                        SelectionContainer { Text(it.address) }; Text(diagnosticText(it.detail)) }
                    Panel { Text(tr(AppStrings.hex_up_to_512_bytes_b4c135)); SelectionContainer { Text(it.payload ?: tr(AppStrings.payload_not_captured_or_redacted_enable_temporary_captu_773fea)) } }
                    if (devices.any { d -> d.id == it.deviceId }) Item(tr(AppStrings.open_device_487957), it.deviceName) { go(Route(Screen.DEVICE, it.deviceId)) }
                }
            }
        }
        Screen.MORE -> Page("YOUR WORKSPACE", tr(AppStrings.my_workspace_a82c99), tr(AppStrings.local_device_workspace_no_cloud_account_required_d28356)) {
            Panel { Text(tr(AppStrings.devices_panel_templates_eeb6b7, devices.size, archive.templates.size)); Text(tr(AppStrings.unread_events_18f303, archive.events.count { !it.read }), color = Muted) }
            Item(tr(AppStrings.connections_16f545), tr(AppStrings.connections_survive_navigation_stop_them_any_time_52d502)) { go(Route(Screen.SUBSCRIPTIONS)) }
            Item(tr(AppStrings.mqtt_profiles_b4aeb6), tr(AppStrings.address_authentication_and_client_id_ebbff0)) { go(Route(Screen.MQTT_PROFILES)) }
            Item(tr(AppStrings.panel_templates_4fb3e5), tr(AppStrings.save_import_and_export_real_panel_configurations_065df7)) { go(Route(Screen.PRESETS)) }
            Item(tr(AppStrings.message_preferences_0db69c), tr(AppStrings.connection_events_and_system_notification_support_914da4)) { go(Route(Screen.NOTIFICATIONS)) }
            Item(tr(AppStrings.traffic_7c4ff3), tr(AppStrings.actual_ble_mqtt_traffic_e1b94f)) { go(Route(Screen.EVENTS, "traffic")) }
            Item(tr(AppStrings.data_management_d97701), tr(AppStrings.retention_diagnostic_payloads_and_cleanup_e1c179)) { go(Route(Screen.DATA_SETTINGS)) }
            Item(tr(AppStrings.message_app_settings_0c79c2), tr(AppStrings.settings_summary)) { go(Route(Screen.SETTINGS)) }
            Item(tr(AppStrings.developer_settings_8756ea), tr(AppStrings.protocols_widgets_bindings_and_offline_inspection_d1272b)) { go(Route(Screen.DEVELOPER_SETTINGS)) }
            Item(tr(AppStrings.about_and_help_599ad7), tr(AppStrings.platforms_configuration_compatibility_and_usage_e9a485)) { go(Route(Screen.ABOUT)) }
        }
        Screen.SUBSCRIPTIONS, Screen.SUBSCRIPTION -> Page("CONNECTIONS", tr(AppStrings.connections_16f545), tr(AppStrings.foreground_navigation_keeps_receiving_background_behavi_30b8f6)) {
            if (devices.isEmpty()) Text(tr(AppStrings.no_devices_yet_af18f0), color = Muted)
            devices.forEach { d ->
                val panel = workspace.panel(d.id)
                if (panel != null) {
                    val state by remember(panel) { panel.connectionStates() }.collectAsState(panel.state.value.connectionChrome())
                    val label = if (d.panel.transport == PanelTransport.DUAL) state.connectedTransports.joinToString { it.name }.ifEmpty { tr(AppStrings.disconnected_f2f3e9) } else if (state.ready) tr(AppStrings.connected_84eb88, d.panel.transport) else tr(AppStrings.disconnected_f2f3e9)
                    Panel { Text(d.name); Text(label, color = Muted)
                        Row { TextButton(onClick = { go(Route(Screen.DEVICE, d.id)) }) { Text(tr(AppStrings.open_device_connect_89b8f1)) }
                            TextButton(enabled = state.ready || state.busy || state.connectingTransports.isNotEmpty(), onClick = { workspace.disconnect(d.id) }) { Text(tr(AppStrings.stop_connection_878f8c)) } }
                    }
                }
            }
            Item(tr(AppStrings.mqtt_workbench_3aa6fb), tr(AppStrings.separate_debug_session_connect_or_stop_in_the_workbench_619801)) { go(Route(Screen.MQTT)) }
            Text(tr(AppStrings.continuous_background_monitoring_and_threshold_rules_ar_4b284e), color = Muted)
        }
        Screen.NOTIFICATIONS -> Page("MESSAGE PREFERENCES", tr(AppStrings.message_preferences_0db69c), tr(AppStrings.local_event_recording_is_enabled_system_notifications_a_9777e1)) {
            Panel { Toggle(tr(AppStrings.connection_events_6edc4d), tr(AppStrings.record_connections_and_disconnections_errors_and_save_r_6ffe46), archive.recordConnectionEvents, activity::setConnectionEvents) }
            Text(tr(AppStrings.fcm_apns_and_vendor_push_are_not_integrated_no_system_n_d77af9), color = Muted)
            Text(tr(AppStrings.device_threshold_rules_and_continuous_background_monito_b31573), color = Muted)
            Item(tr(AppStrings.view_messages_5c8222), tr(AppStrings.local_records_and_unread_status_7c3509)) { go(Route(Screen.EVENTS)) }
        }
        Screen.DATA_SETTINGS -> {
            val capture by activity.capturePayloads.collectAsState()
            var confirm by remember { mutableStateOf<String?>(null) }
            Page("LOCAL DATA", tr(AppStrings.data_management_d97701), tr(AppStrings.event_summaries_are_persisted_traffic_payloads_are_not_3b675d)) {
                Panel {
                    Text(tr(AppStrings.events_1000_temporary_traffic_500_templates_20_e7ed43, archive.events.size, records.size, archive.templates.size))
                    Text(tr(AppStrings.retention_shortening_removes_expired_events_e8315b))
                    RetentionChoices(listOf("7", "30", "90"), archive.retentionDays.toString()) { confirm = "retention:${it.substringBefore(' ')}" }
                    Toggle(tr(AppStrings.temporarily_capture_payloads_9888ff), tr(AppStrings.may_contain_private_data_current_session_only_turning_o_a59053), capture, activity::setCapturePayloads)
                }
                OutlinedButton(onClick = { confirm = "events" }) { Text(tr(AppStrings.clear_events_and_unread_records_27ff64)) }
                OutlinedButton(onClick = { confirm = "traffic" }) { Text(tr(AppStrings.clear_temporary_traffic_49bc43)) }
                Text(tr(AppStrings.devices_credentials_and_templates_are_kept_platform_bac_c3a5ff), color = Muted)
                error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error); TextButton(onClick = activity::retrySave) { Text(tr(AppStrings.retry_saving_c47e26)) } }
                if (saving) Text(tr(AppStrings.saving_9ca32f), color = Muted)
            }
            confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(tr(AppStrings.confirm_cleanup_b7dc7a)) }, text = { Text(tr(AppStrings.deleted_records_cannot_be_recovered_device_configuratio_21fb63)) },
                confirmButton = { TextButton(onClick = { when { action == "events" -> activity.clearEvents(); action == "traffic" -> activity.clearRecords(); else -> activity.setRetention(action.substringAfter(':').toInt()) }; confirm = null }) { Text(tr(AppStrings.confirm_b56d9a)) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr(AppStrings.cancel_4d0b46)) } }) }
        }
        Screen.PRESETS, Screen.PRESET, Screen.TRANSFER -> TemplatePages(route, activity, workspace, go, feedback)
        Screen.API -> LaunchedEffect(Unit) { go(Route(Screen.MQTT_PROFILES)) }
        Screen.ABOUT -> Page("THINGSTALK", tr(AppStrings.about_and_help_599ad7), tr(AppStrings.a_protocol_driven_local_device_workspace_679064)) {
            Panel { KeyValue(tr(AppStrings.platform_e4b9d6), getPlatform().name); KeyValue(tr(AppStrings.build_81a94d), appBuildLabel()); KeyValue(tr(AppStrings.message_module_4ac8bb), "20261031-read3"); KeyValue(tr(AppStrings.configuration_d7d7ce), tr(AppStrings.supports_schemaversion_1_7_setup_panel_extension_revisi_c0c7b4))
                KeyValue(tr(AppStrings.connection_7328de), if (platformCapabilities().ble) "BLE / MQTT 5 · WS/WSS" else "MQTT 5 · WS/WSS") }
            Section(tr(AppStrings.help_ef33df))
            Text(tr(AppStrings.mqtt_connected_means_the_broker_accepted_login_if_state_039d18))
            Text(tr(AppStrings.prepare_device_access_before_provisioning_complete_syst_d7603d))
            Text(tr(AppStrings.navigating_within_the_app_keeps_connections_background_ac599a))
            Item(tr(AppStrings.messages_and_diagnostics_03a9b8), tr(AppStrings.view_actual_errors_and_related_records_e0baec)) { go(Route(Screen.EVENTS)) }
            Item(tr(AppStrings.open_source_components_and_licenses_56c472), "Kotlin / Compose / Ktor / Kable / ktor-mqtt") { go(Route(Screen.LICENSES)) }
        }
        Screen.LICENSES -> Page("OPEN SOURCE", tr(AppStrings.open_source_components_c71482), tr(AppStrings.refer_to_each_dependency_version_s_distribution_for_lic_1dffbf)) {
            SelectionContainer { Text("Kotlin、Compose Multiplatform、Ktor、AndroidX：Apache-2.0\nKable：Apache-2.0\nktor-mqtt：Apache-2.0\n\nhttps://github.com/JetBrains/kotlin\nhttps://github.com/JetBrains/compose-multiplatform\nhttps://github.com/ktorio/ktor\nhttps://github.com/JuulLabs/kable\nhttps://github.com/ukemp/ktor-mqtt") }
        }
        else -> Unit
    }
}
