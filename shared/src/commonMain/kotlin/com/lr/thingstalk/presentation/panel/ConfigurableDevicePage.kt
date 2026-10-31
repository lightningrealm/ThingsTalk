package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted
import kotlin.time.Instant

@Composable
fun ConfigurableDevicePage(
    viewModel: PanelViewModel,
    onEdit: () -> Unit,
    onDescription: (() -> Unit)? = null,
    transportContent: (@Composable () -> Unit)? = null,
    setupSession: (SetupPanel) -> SetupPanelSession? = { null },
) {
    val definition by remember(viewModel) { viewModel.displayDefinitions() }.collectAsState(viewModel.state.value.definition)
    Page("PERSONAL DEVICE PANEL", definition.title, tr(AppStrings.your_devices_your_way_11aa9c)) {
        transportContent?.invoke()
        ConfiguredSetupPanels(viewModel, setupSession)
        Section(tr(AppStrings.control_panel_247fbd))
        PanelHeader(viewModel, onEdit)
        if (definition.widgets.isEmpty()) Panel {
            Text(tr(AppStrings.start_with_a_blank_panel_3e2e7a), style = MaterialTheme.typography.titleLarge)
            Text(tr(AppStrings.add_widgets_in_developer_settings_or_import_a_panel_con_fd3e9c), color = Muted)
            Primary(tr(AppStrings.configure_device_panel_f7ba58), action = onEdit)
        }
        definition.widgets.forEach { widget ->
            key(widget.id) {
                val capability = definition.capabilities.firstOrNull { it.id == widget.capabilityId }
                val binding = definition.bindings.firstOrNull { it.capabilityId == widget.capabilityId }
                if (capability != null) BoundPanelWidget(viewModel, widget, capability, binding)
            }
        }
        if (onDescription != null) Item(tr(AppStrings.capabilities_43eed3), tr(AppStrings.view_properties_actions_and_encoding_56c4c2)) { onDescription() }
    }
}

@Composable
private fun PanelHeader(viewModel: PanelViewModel, onEdit: () -> Unit) {
    val state by remember(viewModel) { viewModel.chromeStates() }.collectAsState(viewModel.state.value.chrome())
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Tag(if (state.ready) tr(AppStrings.connected_65fe35) else tr(AppStrings.offline_211357))
            TextButton(onClick = onEdit) { Text(tr(AppStrings.developer_settings_8756ea)) }
        }
        Text(tr(AppStrings.connect_the_device_to_view_state_and_perform_actions_566590), style = MaterialTheme.typography.bodySmall, color = Muted)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        state.feedback?.let { Text(diagnosticText(it), color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun BoundPanelWidget(viewModel: PanelViewModel, widget: PanelWidget, capability: PanelCapability, binding: PanelBinding?) {
    val state by remember(viewModel, capability.id) { viewModel.widgetStates(capability.id) }
        .collectAsState(viewModel.state.value.widget(capability.id))
    PanelWidgetView(widget, capability, binding, state.value, state.updatedAt, state.notification,
        state.enabled, state.readEnabled, state.source, state.busy, state.message, state.error,
        onRead = { viewModel.read(capability.id) }, onWrite = { viewModel.write(capability.id, it) },
        onRetryNotifications = { viewModel.retryNotifications(capability.id) })
}

@Composable
private fun PanelWidgetView(
    widget: PanelWidget,
    capability: PanelCapability,
    binding: PanelBinding?,
    value: String?,
    valueUpdatedAt: Long?,
    notification: PanelNotificationState?,
    enabled: Boolean,
    readEnabled: Boolean,
    source: String?,
    busy: Boolean,
    message: String?,
    error: String?,
    onRead: () -> Unit,
    onWrite: (String) -> Unit,
    onRetryNotifications: () -> Unit,
) {
    Panel {
        source?.let { Text(diagnosticText(it), color = Muted, style = MaterialTheme.typography.labelSmall) }
        when (widget.type) {
            WidgetType.VALUE -> {
                Text(widget.label, style = MaterialTheme.typography.titleMedium)
                Text(if (value == null) "—" else displayValue(value, capability), style = MaterialTheme.typography.headlineLarge)
            }
            WidgetType.SWITCH -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(widget.label, style = MaterialTheme.typography.titleMedium)
                        Text(value?.let { if (it == "true") tr(AppStrings.on_d78cde) else tr(AppStrings.off_f62876) } ?: tr(AppStrings.unknown_state_95abdc), color = Muted)
                    }
                    Switch(value == "true", { onWrite(it.toString()) }, enabled = enabled && binding?.canWrite == true, modifier = Modifier.semantics { contentDescription = widget.label })
                }
            }
            WidgetType.SLIDER -> PanelNumberSlider(widget.label, capability, value, enabled && binding?.canWrite == true, onWrite)
            WidgetType.NUMBER_INPUT -> PanelNumberInput(widget.label, capability, value, enabled && binding?.canWrite == true, onWrite)
            WidgetType.TEXT_INPUT -> PanelTextInput(widget.label, capability, enabled && binding?.canWrite == true, onWrite)
            WidgetType.BUTTON -> Primary(widget.label, enabled && binding?.canWrite == true) { onWrite(binding?.actionValue.orEmpty()) }
        }
        // Reserve one local status line so short commands do not move the following cards.
        Text(if (busy) tr(AppStrings.working_waiting_for_the_previous_operation_e0623b) else diagnosticText(error ?: message.orEmpty()),
            modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = if (error != null) MaterialTheme.colorScheme.error else Muted,
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (binding == null) tr(AppStrings.no_operation_bound_ad33a9) else if (binding.canObserve) notificationStatusLabel(notification?.status ?: PanelNotificationStatus.STOPPED)
                else if (!enabled) tr(AppStrings.operation_unavailable_abc0ad) else capability.id, color = Muted, style = MaterialTheme.typography.labelSmall)
            if (binding?.canRead == true) TextButton(onClick = onRead, enabled = readEnabled) { Text(tr(AppStrings.read_b6b97a)) }
        }
        valueUpdatedAt?.let {
            Text(tr(AppStrings.last_valid_data_cd2695, Instant.fromEpochMilliseconds(it).toString().replace("T", " ").replace("Z", " UTC")), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (binding?.canObserve == true && notification != null) {
            if (notification.invalidPayloadCount > 0) Text(tr(AppStrings.ignored_invalid_payloads_in_this_connection_0e33b1, notification.invalidPayloadCount), color = Muted, style = MaterialTheme.typography.labelSmall)
            notification.lastDecodeError?.let {
                Text(tr(AppStrings.latest_payload_failed_to_decode_keeping_the_last_valid_00ab13, diagnosticText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            notification.failure?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (notification.status in setOf(PanelNotificationStatus.FAILED, PanelNotificationStatus.STOPPED) && readEnabled) {
                OutlinedButton(onClick = onRetryNotifications, modifier = Modifier.semantics { contentDescription = tr(AppStrings.resubscribe_e73451, widget.label) }) { Text(tr(AppStrings.resubscribe_6113c5)) }
            }
        }
    }
}

private fun notificationStatusLabel(status: PanelNotificationStatus): String = when (status) {
    PanelNotificationStatus.STOPPED -> tr(AppStrings.notifications_stopped_152847)
    PanelNotificationStatus.STARTING -> tr(AppStrings.subscribing_to_notifications_cca994)
    PanelNotificationStatus.WAITING -> tr(AppStrings.subscribed_waiting_for_valid_data_c34e47)
    PanelNotificationStatus.RECEIVING -> tr(AppStrings.receiving_notifications_99d9bd)
    PanelNotificationStatus.FAILED -> tr(AppStrings.notification_subscription_failed_46a3e8)
}

private fun displayValue(value: String, capability: PanelCapability): String =
    if (capability.type == CapabilityType.BOOLEAN) { if (value == "true") tr(AppStrings.on_256783) else tr(AppStrings.off_6c14bd) }
    else "${value.toDoubleOrNull()?.let(::panelNumberText) ?: value} ${capability.unit}".trim()

@Composable
private fun ConfiguredSetupPanels(viewModel: PanelViewModel, session: (SetupPanel) -> SetupPanelSession?) {
    val state by remember(viewModel) { viewModel.connectionStates() }.collectAsState(viewModel.state.value.connectionChrome())
    if (state.definition.transport == PanelTransport.DUAL && state.activeChannel != PanelTransport.BLE) return
    state.definition.setupPanels.forEach { definition ->
        key(definition.id) { SetupPanelView(definition, session(definition)) }
    }
}
