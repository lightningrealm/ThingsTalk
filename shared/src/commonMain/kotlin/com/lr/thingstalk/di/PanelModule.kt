package com.lr.thingstalk.di

import com.lr.thingstalk.data.panel.BlePanelOperations
import com.lr.thingstalk.data.panel.DemoPanelBleConnection
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.data.panel.defaultDemoPanel
import com.lr.thingstalk.presentation.panel.PanelViewModel
import com.lr.thingstalk.domain.panel.PanelDefinition

fun createPanelViewModel(definition: PanelDefinition, onSave: suspend (PanelDefinition) -> Unit): PanelViewModel =
    PanelViewModel(initialDefinition = definition, codec = PanelJson, onSave = onSave)

/** Composition root: presentation depends on domain contracts, while data supplies adapters. */
fun createDemoPanelViewModel(): PanelViewModel {
    val connection = DemoPanelBleConnection()
    return PanelViewModel(defaultDemoPanel(), BlePanelOperations(connection), PanelJson, connection, transportLabel = "示例设备（模拟）")
}
