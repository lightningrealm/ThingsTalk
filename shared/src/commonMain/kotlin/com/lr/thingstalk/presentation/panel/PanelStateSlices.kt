package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Read each projection in the composable that renders it, never at the whole-page level. */
internal data class PanelChromeState(val ready: Boolean, val busy: Boolean, val error: String?, val feedback: String?)
internal data class PanelWidgetState(
    val value: String?, val updatedAt: Long?, val notification: PanelNotificationState?,
    val enabled: Boolean, val readEnabled: Boolean, val source: String?, val busy: Boolean, val message: String?, val error: String?,
)
internal fun PanelUiState.chrome() = PanelChromeState(if (definition.schemaVersion >= 6 && definition.transport == PanelTransport.DUAL) activeChannel in connectedTransports else ready, saving || (!ready && busy), error, feedback)
internal fun PanelUiState.widget(id: String): PanelWidgetState {
    val binding = effectiveDefinition.bindings.singleOrNull { it.capabilityId == id }
    val dual = definition.transport == PanelTransport.DUAL
    val available = ready && !saving && id !in activeOperations
    return PanelWidgetState(values[id], valueUpdatedAt[id], notificationStates[id],
        available && (!dual || binding?.selectedControl in connectedTransports),
        available && (!dual || binding?.selectedState in connectedTransports),
        if (definition.schemaVersion >= 6 && definition.transport == PanelTransport.DUAL && binding == null) "${activeChannel?.name} · 此通道未配置该能力"
        else if (dual) "控制：${binding?.selectedControl?.name ?: "无"} · 状态：${binding?.selectedState?.name ?: "无"}" else null,
        id in activeOperations, operationMessages[id], operationErrors[id])
}
internal fun PanelUiState.connectionChrome() = copy(values = emptyMap(), valueUpdatedAt = emptyMap(), notificationStates = emptyMap(),
    busy = saving || (!ready && busy), activeOperations = emptySet(), operationMessages = emptyMap(), operationErrors = emptyMap())
internal fun PanelUiState.editorChrome() = connectionChrome().copy(busy = busy)
internal fun PanelViewModel.definitions(): Flow<PanelDefinition> = state.map { it.definition }.distinctUntilChanged()
internal fun PanelViewModel.chromeStates(): Flow<PanelChromeState> = state.map { it.chrome() }.distinctUntilChanged()
internal fun PanelViewModel.widgetStates(id: String): Flow<PanelWidgetState> = state.map { it.widget(id) }.distinctUntilChanged()
internal fun PanelViewModel.connectionStates(): Flow<PanelUiState> = state.map { it.connectionChrome() }.distinctUntilChanged()
internal fun PanelViewModel.editorStates(): Flow<PanelUiState> = state.map { it.editorChrome() }.distinctUntilChanged()

internal fun PanelViewModel.displayDefinitions(): Flow<PanelDefinition> = state.map { it.definition.copy(bindings = it.effectiveDefinition.bindings) }.distinctUntilChanged()
