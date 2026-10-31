package com.lr.thingstalk.domain.panel

val PanelBinding.selectedControl: PanelTransport? get() = controlTransport ?: when {
    write != null -> PanelTransport.BLE
    publish != null -> PanelTransport.MQTT
    else -> null
}
val PanelBinding.selectedState: PanelTransport? get() = stateTransport ?: when {
    read != null || notify != null -> PanelTransport.BLE
    subscribe != null -> PanelTransport.MQTT
    else -> null
}
val PanelBinding.canRead: Boolean get() = selectedState == PanelTransport.BLE && read != null

/** Remove other-channel bindings before handing a validated definition to a single-channel adapter. */
fun PanelDefinition.forTransport(channel: PanelTransport): PanelDefinition {
    require(channel != PanelTransport.DUAL)
    val selected = bindings.mapNotNull { b ->
        val single = if (channel == PanelTransport.BLE) b.copy(publish = null, subscribe = null, controlTransport = null, stateTransport = null)
            else b.copy(read = null, write = null, notify = null, actionValue = null, controlTransport = null, stateTransport = null)
        single.takeIf { it.read != null || it.write != null || it.notify != null || it.publish != null || it.subscribe != null }
    }
    return copy(schemaVersion = if (channel == PanelTransport.BLE) 3 else 4, transport = channel, defaultChannel = null, setupPanels = emptyList(), bindings = selected, widgets = emptyList())
}

fun PanelDefinition.asDual(): PanelDefinition = if (transport == PanelTransport.DUAL) this else
    copy(schemaVersion = if (schemaVersion == 7) 7 else 5, defaultChannel = if (schemaVersion == 7) transport else null, transport = PanelTransport.DUAL, bindings = bindings.map {
        it.copy(controlTransport = it.selectedControl, stateTransport = it.selectedState)
    })

internal fun validateDualBinding(definition: PanelDefinition, binding: PanelBinding): List<String> = buildList {
    fun issue(condition: Boolean, message: String) { if (condition) add(message) }
    val hasControl = binding.write != null || binding.publish != null
    val hasState = binding.read != null || binding.notify != null || binding.subscribe != null
    issue(!hasControl && !hasState, "绑定至少需要一个操作")
    issue(hasControl && (binding.controlTransport == null || !binding.canWrite), "请选择已配置操作的控制通道：${binding.capabilityId}")
    issue(!hasControl && binding.controlTransport != null, "没有控制操作时不能选择控制通道")
    issue(hasState && (binding.stateTransport == null || when (binding.stateTransport) {
        PanelTransport.BLE -> binding.read == null && binding.notify == null
        PanelTransport.MQTT -> binding.subscribe == null
        else -> true
    }), "请选择已配置操作的状态来源：${binding.capabilityId}")
    issue(!hasState && binding.stateTransport != null, "没有状态操作时不能选择状态来源")
    issue(binding.controlTransport == PanelTransport.DUAL || binding.stateTransport == PanelTransport.DUAL, "一个操作只能选择一条通道")
    val one = definition.copy(bindings = listOf(binding), widgets = emptyList())
    listOf(PanelTransport.BLE, PanelTransport.MQTT).forEach { channel ->
        val projected = one.forTransport(channel)
        if (projected.bindings.isNotEmpty()) addAll(validatePanel(projected))
    }
}

/** v6 displays one channel at a time without falling back to an operation on the other side. */
fun PanelDefinition.forActiveChannel(channel: PanelTransport): PanelDefinition {
    require(channel != PanelTransport.DUAL)
    return forTransport(channel).asDual()
}

fun PanelDefinition.asChannelPanel(defaultChannel: PanelTransport = PanelTransport.BLE): PanelDefinition {
    require(transport == PanelTransport.DUAL && defaultChannel != PanelTransport.DUAL)
    return copy(schemaVersion = if (schemaVersion == 7) 7 else 6, defaultChannel = defaultChannel)
}
