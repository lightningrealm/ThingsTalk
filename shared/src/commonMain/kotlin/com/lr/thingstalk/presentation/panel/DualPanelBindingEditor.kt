package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.lr.thingstalk.presentation.theme.Muted
import com.lr.thingstalk.domain.ble.BleService
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*

@Composable
internal fun DualPanelBindingEditor(
    capability: PanelCapability, binding: PanelBinding, services: List<BleService>,
    formats: List<PayloadFormat>, channelPanel: Boolean = false, onChange: (PanelBinding) -> Unit,
) {
    var selected by rememberSaveable(capability.id) { mutableStateOf(
        if (binding.read == null && binding.write == null && binding.notify == null &&
            (binding.publish != null || binding.subscribe != null)) PanelTransport.MQTT else PanelTransport.BLE
    ) }
    fun update(next: PanelBinding) {
        val controls = buildList { if (next.write != null) add(PanelTransport.BLE); if (next.publish != null) add(PanelTransport.MQTT) }
        val sources = buildList { if (next.read != null || next.notify != null) add(PanelTransport.BLE); if (next.subscribe != null) add(PanelTransport.MQTT) }
        onChange(next.copy(controlTransport = next.controlTransport?.takeIf { it in controls } ?: controls.firstOrNull(),
            stateTransport = next.stateTransport?.takeIf { it in sources } ?: sources.firstOrNull()))
    }
    if (!channelPanel) Panel {
        Text(tr(AppStrings.select_control_and_state_channels_separately_channels_n_7d4123))
        if (binding.write != null || binding.publish != null) {
            Text(tr(AppStrings.control_channel_cdcfeb))
            Choices(listOfNotNull("BLE".takeIf { binding.write != null }, "MQTT".takeIf { binding.publish != null }), binding.controlTransport?.name.orEmpty()) {
                update(binding.copy(controlTransport = PanelTransport.valueOf(it)))
            }
        }
        if (binding.read != null || binding.notify != null || binding.subscribe != null) {
            Text(tr(AppStrings.state_source_377857))
            Choices(listOfNotNull("BLE".takeIf { binding.read != null || binding.notify != null }, "MQTT".takeIf { binding.subscribe != null }), binding.stateTransport?.name.orEmpty()) {
                update(binding.copy(stateTransport = PanelTransport.valueOf(it)))
            }
        }
    }
    TransportTabs(selected, { selected = it },
        if (binding.read != null || binding.write != null || binding.notify != null) tr(AppStrings.binding_configured_08e00a) else tr(AppStrings.no_binding_configured_1785a7),
        if (binding.publish != null || binding.subscribe != null) tr(AppStrings.binding_configured_08e00a) else tr(AppStrings.no_binding_configured_1785a7))
    Text(if (channelPanel) tr(AppStrings.define_each_channel_separately_switching_channels_on_th_20e77a) else tr(AppStrings.tabs_only_switch_the_editor_control_and_state_routing_a_f366cc), color = Muted)
    TransportTabContent(selected,
        ble = { PanelBindingEditor(capability, binding, services, formats, ::update) },
        mqtt = { MqttPanelBindingEditor(capability, binding, formats, ::update) })
}
