package com.lr.thingstalk.presentation.panel

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
        Text("控制通道与状态来源分别选择；不会自动切换通道或重发命令。")
        if (binding.write != null || binding.publish != null) {
            Text("控制通道")
            Choices(listOfNotNull("BLE".takeIf { binding.write != null }, "MQTT".takeIf { binding.publish != null }), binding.controlTransport?.name.orEmpty()) {
                update(binding.copy(controlTransport = PanelTransport.valueOf(it)))
            }
        }
        if (binding.read != null || binding.notify != null || binding.subscribe != null) {
            Text("状态来源")
            Choices(listOfNotNull("BLE".takeIf { binding.read != null || binding.notify != null }, "MQTT".takeIf { binding.subscribe != null }), binding.stateTransport?.name.orEmpty()) {
                update(binding.copy(stateTransport = PanelTransport.valueOf(it)))
            }
        }
    }
    TransportTabs(selected, { selected = it },
        if (binding.read != null || binding.write != null || binding.notify != null) "已配置绑定" else "未配置绑定",
        if (binding.publish != null || binding.subscribe != null) "已配置绑定" else "未配置绑定")
    Text(if (channelPanel) "分别定义两个通道的操作。展示页切换通道后，组件使用对应绑定；未配置的操作不可用。" else "标签只切换编辑内容，不改变上方控制通道和状态来源。两边共用组件与数据格式。", color = Muted)
    TransportTabContent(selected,
        ble = { PanelBindingEditor(capability, binding, services, formats, ::update) },
        mqtt = { MqttPanelBindingEditor(capability, binding, formats, ::update) })
}
