package com.lr.thingstalk.domain.panel

/** A portable MQTT-only copy for Web import, never a mutation of the original file. */
fun PanelDefinition.mqttImportProjection(): PanelDefinition {
    require(transport != PanelTransport.BLE) { "该配置仅包含蓝牙，网页版无法使用；请提供 MQTT 配置" }
    if (transport == PanelTransport.MQTT) return this
    val mqttBindings = bindings.mapNotNull { binding ->
        if (binding.publish == null && binding.subscribe == null) null else PanelBinding(
            capabilityId = binding.capabilityId, publish = binding.publish, subscribe = binding.subscribe,
        )
    }
    require(mqttBindings.isNotEmpty()) { "该双连接配置没有可用的 MQTT 绑定，无法在网页版导入" }
    val byId = mqttBindings.associateBy { it.capabilityId }
    val supportedWidgets = widgets.filter { widget ->
        val binding = byId[widget.capabilityId]
        when (widget.type) {
            WidgetType.VALUE -> binding?.subscribe != null
            else -> binding?.publish != null
        }
    }
    val formats = mqttBindings.flatMap { listOfNotNull(it.publish?.payload?.formatId, it.subscribe?.payload?.formatId) }.toSet()
    return copy(
        schemaVersion = 4, transport = PanelTransport.MQTT, defaultChannel = null, setupPanels = emptyList(),
        bindings = mqttBindings, widgets = supportedWidgets,
        capabilities = capabilities.filter { it.id in byId },
        payloadFormats = payloadFormats.filter { it.id in formats },
    ).also { require(validatePanel(it).isEmpty()) { validatePanel(it).joinToString("\n") } }
}
