package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted

@Composable
fun PanelEditorPage(viewModel: PanelViewModel, editor: PanelEditorViewModel, inspector: PayloadInspector, onFeedback: (String) -> Unit = {}) {
    val state by remember(viewModel) { viewModel.editorStates() }.collectAsState(viewModel.state.value.editorChrome())
    val editing by editor.state.collectAsState()
    var draft by EditorValue({ editor.state.value.draft.definition }, editor::updateDefinition)
    var selectedId by EditorValue({ editor.state.value.draft.selectedWidgetId ?: draft.widgets.firstOrNull()?.id }, editor::selectWidget)
    LaunchedEffect(state.definition) { editor.savedDefinitionChanged(state.definition) }
    var messages by remember { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var json by EditorValue({ editor.state.value.draft.jsonText }, editor::updateJson)
    var showJson by remember { mutableStateOf(false) }
    var showFormats by remember { mutableStateOf(true) }
    val importedRevision = editing.revision
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDiscardJson by remember { mutableStateOf(false) }
    val selected = draft.widgets.firstOrNull { it.id == selectedId }
    fun changeWidget(widget: PanelWidget) {
        draft = draft.copy(widgets = draft.widgets.map { if (it.id == widget.id) widget else it })
        messages = emptyList(); notice = null
    }
    suspend fun importDraft(source: String) {
        if (viewModel.state.value.busy) {
            messages = listOf(tr(AppStrings.wait_for_the_current_operation_before_importing_f4dad9))
            return
        }
        try {
            val prepared = preparePanelImport(source)
            val imported = viewModel.importDefinition(prepared.source)
            editor.importDefinitionWhenReady(imported, prepared.source)
            showFormats = imported.payloadFormats.isNotEmpty()
            messages = emptyList()
            notice = prepared.notice() ?: if (imported.transport == PanelTransport.DUAL && imported.bindings.any { it.publish != null || it.subscribe != null } && imported.bindings.none { it.read != null || it.write != null || it.notify != null })
                tr(AppStrings.dual_connection_draft_loaded_with_mqtt_bindings_only_ad_6e9d28)
            else tr(AppStrings.configuration_draft_loaded_check_bindings_and_payload_f_3b8d68)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { messages = listOf(tr(AppStrings.import_failed_7bab25, failure.message?.let(::diagnosticText))); notice = null }
    }
    val scope = rememberCoroutineScope()
    val fileActions = rememberPanelFileTransfer(onImport = { source ->
        // Android can recreate the activity while the document picker is open.
        // Its result may arrive before the persisted editor draft finishes loading.
        scope.launch {
            importDraft(source)
        }
    }, onFeedback = { notice = it })
    LaunchedEffect(notice, messages) {
        (messages.firstOrNull() ?: notice)?.let(onFeedback)
    }
    if (editing.loading || editing.loadError != null) {
        Page("PANEL DRAFT", tr(AppStrings.editing_draft_13bd30), tr(AppStrings.drafts_are_stored_separately_from_the_saved_device_conf_eeeb6e)) {
            Text(editing.loadError ?: tr(AppStrings.loading_local_draft_4abc66))
            if (editing.loadError != null) {
                Primary(tr(AppStrings.reload_778497), !editing.processingExit) { editor.retryLoad() }
                TextButton(onClick = { confirmClear = true }, enabled = !editing.processingExit) { Text(tr(AppStrings.discard_unreadable_draft_115e90)) }
                editing.checkpointError?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
            }
        }
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(tr(AppStrings.discard_local_draft_3d50d1)) },
            text = { Text(tr(AppStrings.only_this_device_s_unapplied_draft_will_be_deleted_the_40f3ee)) },
            confirmButton = { TextButton(onClick = { editor.discard { confirmClear = false } }, enabled = !editing.processingExit) { Text(tr(AppStrings.discard_ea4899)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr(AppStrings.cancel_4d0b46)) } })
        return
    }
    if (editing.recovery != null) {
        Page("PANEL DRAFT", tr(AppStrings.older_draft_found_b28e32), tr(AppStrings.the_saved_device_configuration_has_changed_since_this_d_890000)) {
            Text(tr(AppStrings.current_panel_a8f4a9, state.definition.title))
            Text(tr(AppStrings.draft_panel_6a376d, editing.recovery!!.definition.title))
            Text(tr(AppStrings.restoring_only_loads_the_editor_review_and_save_to_repl_ccac75), color = Muted)
            Primary(tr(AppStrings.restore_draft_and_continue_editing_7c38ff), !editing.processingExit) { editor.restoreConflictingDraft() }
            OutlinedButton(onClick = { editor.discard() }, enabled = !editing.processingExit) { Text(tr(AppStrings.discard_draft_and_use_current_configuration_27c45e)) }
            editing.checkpointError?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(tr(AppStrings.protocol_and_panel_623472), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        messages = viewModel.updateDefinition(draft)
                        notice = null
                    }, enabled = !state.busy && !fileActions.busy && !editing.draft.jsonEdited && !editing.processingExit,
                        modifier = Modifier.semantics { contentDescription = tr(AppStrings.save_and_apply_panel_95f36c) }) {
                        Text(if (state.saving) tr(AppStrings.saving_9ca32f) else tr(AppStrings.save_fadf24))
                    }
                }
                val saveStatus = messages.firstOrNull() ?: state.error
                if (saveStatus != null) Text(diagnosticText(saveStatus), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (editing.draft.jsonEdited) Text(tr(AppStrings.import_or_discard_json_text_changes_before_saving_e54c3a), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else state.feedback?.let { Text(diagnosticText(it), color = Muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 800.dp).fillMaxSize()) {
    Page("DEVELOPER SETTINGS", tr(AppStrings.protocol_and_panel_623472), tr(AppStrings.define_payload_formats_bind_operations_then_map_fields_657ef9)) {
        Panel {
            Tag(if (!state.ready) tr(AppStrings.offline_editor_990246) else if (draft.transport == PanelTransport.BLE) tr(AppStrings.connected_characteristics_available_8e69cf) else if (draft.transport == PanelTransport.MQTT) tr(AppStrings.mqtt_connected_90e8df) else tr(AppStrings.dual_connection_some_or_all_channels_connected_bd5e65))
            Text(when {
                editing.checkpointError != null -> tr(AppStrings.draft_not_preserved_5d686f, editing.checkpointError)
                editing.checkpointing -> tr(AppStrings.preserving_draft_fa30ff)
                editing.dirty && editing.checkpointSaved -> tr(AppStrings.unapplied_changes_draft_saved_locally_11100f)
                editing.dirty -> tr(AppStrings.unapplied_changes_ccacfc)
                else -> tr(AppStrings.matches_the_saved_panel_8476a0)
            }, color = if (editing.checkpointError != null) MaterialTheme.colorScheme.error else Muted,
                style = MaterialTheme.typography.bodySmall)
            editing.notice?.let { Text(diagnosticText(it), color = Muted, style = MaterialTheme.typography.bodySmall) }
            if (editing.checkpointError != null) TextButton(onClick = { editor.checkpointNow() }) { Text(tr(AppStrings.retry_preserving_draft_49b7ba)) }
            Text(tr(AppStrings.edit_the_shared_panel_here_import_replaces_the_whole_dr_4cecc2), color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, label = { Text(tr(AppStrings.panel_title_f30fc0)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = fileActions.importFile, enabled = !state.busy && !fileActions.busy, modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.import_configuration_file_5fad12)) }
            OutlinedButton(onClick = {
                messages = validatePanel(draft)
                if (messages.isEmpty()) {
                    try { fileActions.exportFile(viewModel.exportDefinition(draft), "panel.thingstalk.json") }
                    catch (failure: Exception) { messages = listOf(tr(AppStrings.export_failed_3c3cf9, failure.message?.let(::diagnosticText))) }
                }
            }, enabled = !state.busy && !fileActions.busy, modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.export_draft_as_configuration_file_d4be77)) }
            Text(tr(AppStrings.thingstalk_json_includes_panel_payload_formats_and_bind_1c4628), color = Muted, style = MaterialTheme.typography.bodySmall)
            notice?.let { Text(diagnosticText(it), color = Muted, style = MaterialTheme.typography.bodySmall) }
            messages.forEach { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (draft.transport == PanelTransport.DUAL) Panel {
            if (draft.schemaVersion >= 6) {
                Text(tr(AppStrings.separate_channels_independent_ble_mqtt_bindings_a3a803))
                Text(tr(AppStrings.default_channel_97831f))
                Choices(listOf("BLE", "MQTT"), requireNotNull(draft.defaultChannel).name) {
                    draft = draft.copy(defaultChannel = PanelTransport.valueOf(it))
                }
            } else {
                Text(tr(AppStrings.this_is_v5_fixed_routing_after_conversion_tabs_switch_a_d7be1c), color = Muted)
                TextButton(onClick = { draft = draft.asChannelPanel(); notice = tr(AppStrings.converted_to_a_v6_draft_with_ble_as_default_check_both_afce2f) }) {
                    Text(tr(AppStrings.convert_to_separate_channels_v6_99fef1))
                }
            }
        }
        Section(tr(AppStrings.text_00_payload_formats_88db36, draft.payloadFormats.size), if (showFormats) tr(AppStrings.collapse_5d5815) else tr(AppStrings.expand_b0e248)) { showFormats = !showFormats }
        if (showFormats) key(state.definition, importedRevision) {
            PayloadFormatsEditor(draft.payloadFormats, draft.bindings) { formats ->
                draft = draft.copy(schemaVersion = maxOf(3, draft.schemaVersion), payloadFormats = formats)
                messages = emptyList(); notice = null
            }
        }
        if (draft.setupPanels.isNotEmpty()) Panel {
            Text(tr(AppStrings.device_setup_forms_d42ee7, draft.setupPanels.size))
            Text(tr(AppStrings.fields_validation_and_characteristics_are_defined_by_js_263af2), color = Muted)
            draft.setupPanels.forEach { form ->
                Text(tr(AppStrings.fields_14ab80, form.title, form.sections.sumOf { it.fields.size }))
            }
        }
        PayloadInspectorPanel(draft, inspector)
        Section(tr(AppStrings.text_01_add_widgets_aba2c9))
        Panel {
            Text(tr(AppStrings.each_new_widget_creates_an_independent_capability_witho_2cec8f), color = Muted, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetType.entries.forEach { type ->
                    OutlinedButton(onClick = {
                        var suffix = 1
                        while (draft.capabilities.any { it.id == "control-$suffix" } || draft.widgets.any { it.id == "widget-$suffix" }) suffix++
                        val capabilityType = when (type) {
                            WidgetType.VALUE, WidgetType.SLIDER, WidgetType.NUMBER_INPUT -> CapabilityType.NUMBER
                            WidgetType.SWITCH -> CapabilityType.BOOLEAN
                            WidgetType.BUTTON -> CapabilityType.ACTION
                            WidgetType.TEXT_INPUT -> CapabilityType.TEXT
                        }
                        val capability = PanelCapability("control-$suffix", type.label(), capabilityType,
                            minimum = if (capabilityType == CapabilityType.NUMBER) 0.0 else null,
                            maximum = if (capabilityType == CapabilityType.NUMBER) 100.0 else null)
                        val widget = PanelWidget("widget-$suffix", type, type.label(), capability.id)
                        draft = draft.copy(schemaVersion = if (type == WidgetType.TEXT_INPUT) maxOf(3, draft.schemaVersion) else draft.schemaVersion, capabilities = draft.capabilities + capability, widgets = draft.widgets + widget)
                        selectedId = widget.id
                    }, enabled = draft.widgets.size < 32 && draft.capabilities.size < 32) { Text("＋ ${type.label()}") }
                }
            }
        }
        if (draft.widgets.isEmpty()) Panel {
            Text(tr(AppStrings.the_panel_is_empty_cd2fa2), style = MaterialTheme.typography.titleMedium)
            Text(tr(AppStrings.choose_a_widget_above_to_start_you_can_also_save_an_emp_105b67), color = Muted)
        } else Panel {
            Text(tr(AppStrings.select_a_name_to_edit_move_widgets_up_or_down_to_reorde_33b252), color = Muted, style = MaterialTheme.typography.bodySmall)
            draft.widgets.forEachIndexed { index, widget ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { selectedId = widget.id }, modifier = Modifier.weight(1f)) { Text("${if (widget.id == selectedId) "● " else ""}${index + 1}. ${widget.label}") }
                    TextButton(onClick = { draft = draft.copy(widgets = draft.widgets.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0) { Text(tr(AppStrings.move_up_8a0c83)) }
                    TextButton(onClick = { draft = draft.copy(widgets = draft.widgets.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index < draft.widgets.lastIndex) { Text(tr(AppStrings.move_down_05c46f)) }
                }
            }
        }
        val capability = selected?.let { widget -> draft.capabilities.firstOrNull { it.id == widget.capabilityId } }
        if (selected != null && capability != null) {
            Section(tr(AppStrings.text_02_widget_settings_f4d189))
            Panel {
                Text(tr(AppStrings.edit_1d75af, selected.label), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(selected.label, { label ->
                    changeWidget(selected.copy(label = label))
                    draft = draft.copy(capabilities = draft.capabilities.map { if (it.id == capability.id) it.copy(label = label) else it })
                }, label = { Text(tr(AppStrings.display_name_75ae6a)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                val types = compatibleTypes(capability)
                Choices(types.map { it.label() }, selected.type.label()) { label -> changeWidget(selected.copy(type = types.first { it.label() == label })) }
                if (capability.type == CapabilityType.NUMBER) key(capability.id, state.definition, importedRevision) {
                    NumberCapabilityFields(capability) { changed -> draft = draft.copy(capabilities = draft.capabilities.map { if (it.id == changed.id) changed else it }) }
                }
                if (capability.type == CapabilityType.TEXT) key(capability.id, state.definition, importedRevision) {
                    var limit by remember { mutableStateOf(capability.maxBytes.toString()) }
                    OutlinedTextField(limit, {
                        limit = it
                        draft = draft.copy(capabilities = draft.capabilities.map { current ->
                            if (current.id == capability.id) current.copy(maxBytes = it.toIntOrNull() ?: 0) else current
                        })
                    }, label = { Text(tr(AppStrings.text_max_bytes)) }, singleLine = true,
                        isError = limit.toIntOrNull() !in 1..512, modifier = Modifier.fillMaxWidth())
                }
                Text(tr(AppStrings.capability_d7feb1, capability.id), color = Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val remaining = draft.widgets.filterNot { it.id == selected.id }
                    val stillUsed = remaining.any { it.capabilityId == capability.id }
                    draft = draft.copy(widgets = remaining,
                        capabilities = if (stillUsed) draft.capabilities else draft.capabilities.filterNot { it.id == capability.id },
                        bindings = if (stillUsed) draft.bindings else draft.bindings.filterNot { it.capabilityId == capability.id })
                    selectedId = remaining.firstOrNull()?.id
                }) { Text(tr(AppStrings.remove_widget_9f82b6), color = MaterialTheme.colorScheme.error) }
            }
            Section(tr(AppStrings.text_03_widget_operations_a730c1))
            Panel {
                Text("${selected.label} · ${if (draft.bindings.none { it.capabilityId == capability.id }) tr(AppStrings.not_bound_56a269) else tr(AppStrings.binding_configured_08e00a)}", style = MaterialTheme.typography.titleMedium)
                Text(if (draft.schemaVersion >= 6) tr(AppStrings.define_ble_and_mqtt_bindings_separately_the_selected_di_fef7eb) else if (draft.transport == PanelTransport.DUAL) tr(AppStrings.configure_ble_characteristics_and_mqtt_topics_then_sele_9f4664) else if (draft.transport == PanelTransport.MQTT) tr(AppStrings.bind_state_and_command_topics_then_map_widget_fields_wi_2ea655) else if (state.ready) tr(AppStrings.select_discovered_services_and_characteristics_check_ea_55d536) else tr(AppStrings.enter_uuids_and_payload_formats_manually_or_connect_to_df4e11), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            val binding = draft.bindings.firstOrNull { it.capabilityId == capability.id } ?: PanelBinding(capability.id)
            key(capability.id, state.definition, importedRevision) {
                if (draft.transport == PanelTransport.DUAL) DualPanelBindingEditor(capability, binding, state.services, draft.payloadFormats, channelPanel = draft.schemaVersion >= 6) { updated ->
                    val hasOperation = updated.read != null || updated.write != null || updated.notify != null || updated.publish != null || updated.subscribe != null
                    draft = draft.copy(bindings = draft.bindings.filterNot { it.capabilityId == updated.capabilityId } + if (hasOperation) listOf(updated) else emptyList())
                    messages = emptyList(); notice = null
                } else if (draft.transport == PanelTransport.MQTT) MqttPanelBindingEditor(capability, binding, draft.payloadFormats) { updated ->
                    draft = draft.copy(bindings = draft.bindings.filterNot { it.capabilityId == updated.capabilityId } +
                        if (updated.publish != null || updated.subscribe != null) listOf(updated) else emptyList())
                    messages = emptyList(); notice = null
                } else PanelBindingEditor(capability, binding, state.services, draft.payloadFormats) { updated ->
                    val hasOperation = updated.read != null || updated.write != null || updated.notify != null
                    val hasPayload = listOfNotNull(updated.read, updated.write, updated.notify).any { it.payload != null }
                    draft = draft.copy(schemaVersion = if (hasPayload) maxOf(3, draft.schemaVersion) else draft.schemaVersion,
                        bindings = draft.bindings.filterNot { it.capabilityId == updated.capabilityId } + if (hasOperation) listOf(updated) else emptyList())
                    messages = emptyList(); notice = null
                }
            }
        }
        messages.forEach { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(diagnosticText(it), color = Muted) }
        state.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        state.feedback?.let { Text(diagnosticText(it), color = Muted) }
        if (editing.draft.jsonEdited) {
            Text(tr(AppStrings.json_text_has_unapplied_changes_validate_and_import_it_9d43ab), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { showJson = true }) { Text(tr(AppStrings.view_unimported_json_fc2dd2)) }
            TextButton(onClick = { confirmDiscardJson = true }) { Text(tr(AppStrings.discard_json_text_changes_cebb4b)) }
        }
        Section(tr(AppStrings.configuration_migration_2f0c73), if (showJson) tr(AppStrings.collapse_5d5815) else tr(AppStrings.expand_b0e248)) { showJson = !showJson }
        if (showJson) Panel {
            Text(tr(AppStrings.json_v_imports_v1_v2_v3_v4_v5_v6_v7_must_match_the_devi_ac1965, draft.schemaVersion), style = MaterialTheme.typography.titleMedium)
            Text(tr(AppStrings.edit_or_copy_the_full_configuration_named_payload_forma_cddaf7), color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                messages = validatePanel(draft)
                if (messages.isEmpty()) {
                    try {
                        editor.generatedJson(viewModel.exportDefinition(draft))
                        notice = tr(AppStrings.json_generated_long_press_the_text_to_copy_1cfc8a)
                    } catch (failure: Exception) {
                        messages = listOf(tr(AppStrings.export_failed_3c3cf9, failure.message?.let(::diagnosticText)))
                        notice = null
                    }
                }
            }, enabled = !editing.draft.jsonEdited, modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.generate_draft_json_13f177)) }
            OutlinedTextField(json, { json = it }, label = { Text(tr(AppStrings.paste_copy_json_54dbe1)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp), minLines = 6)
            Primary(tr(AppStrings.validate_and_import_as_draft_ff6080), enabled = !state.busy && !fileActions.busy) { scope.launch { importDraft(json) } }
            messages.forEach { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            notice?.let { Text(diagnosticText(it), color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = { confirmClear = true }, enabled = draft.widgets.isNotEmpty() || draft.payloadFormats.isNotEmpty()) { Text(tr(AppStrings.clear_panel_draft_a2aac8)) }
    }
        }
    }
    }
    if (confirmDiscardJson) AlertDialog(onDismissRequest = { confirmDiscardJson = false }, title = { Text(tr(AppStrings.discard_unimported_json_changes_d7bcae)) },
        text = { Text(tr(AppStrings.only_text_input_is_cleared_edited_widgets_and_formats_a_5759ae)) },
        confirmButton = { TextButton(onClick = { editor.discardJsonEdits(); confirmDiscardJson = false }) { Text(tr(AppStrings.discard_text_changes_c92a52)) } },
        dismissButton = { TextButton(onClick = { confirmDiscardJson = false }) { Text(tr(AppStrings.continue_editing_3a481b)) } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(tr(AppStrings.clear_panel_draft_a3d2ab)) },
        text = { Text(tr(AppStrings.all_widgets_bindings_and_payload_formats_will_be_remove_fc641a)) },
        confirmButton = { TextButton(onClick = { editor.replaceDraft(draft.copy(capabilities = emptyList(), bindings = emptyList(), widgets = emptyList(), payloadFormats = emptyList())); selectedId = null; messages = emptyList(); notice = tr(AppStrings.draft_cleared_save_to_apply_a03031); confirmClear = false }) { Text(tr(AppStrings.clear_84fcd7)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr(AppStrings.cancel_4d0b46)) } })
}

@Composable
private fun NumberCapabilityFields(capability: PanelCapability, onChange: (PanelCapability) -> Unit) {
    var minimum by remember { mutableStateOf(panelNumberText(capability.minimum ?: 0.0)) }
    var maximum by remember { mutableStateOf(panelNumberText(capability.maximum ?: 255.0)) }
    var step by remember { mutableStateOf(panelNumberText(capability.step)) }
    OutlinedTextField(minimum, { minimum = it; onChange(capability.copy(minimum = it.toDoubleOrNull() ?: Double.NaN)) }, label = { Text(tr(AppStrings.minimum_c3d641)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(maximum, { maximum = it; onChange(capability.copy(maximum = it.toDoubleOrNull() ?: Double.NaN)) }, label = { Text(tr(AppStrings.maximum_018219)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(step, { step = it; onChange(capability.copy(step = it.toDoubleOrNull() ?: Double.NaN)) }, label = { Text(tr(AppStrings.slider_step_35276c)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(capability.unit, { onChange(capability.copy(unit = it)) }, label = { Text(tr(AppStrings.unit_optional_d1c586)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(tr(AppStrings.ranges_and_steps_may_be_decimal_or_negative_sliders_req_e36981), color = Muted, style = MaterialTheme.typography.bodySmall)
}

private fun compatibleTypes(capability: PanelCapability): List<WidgetType> = when (capability.type) {
    CapabilityType.NUMBER -> listOf(WidgetType.VALUE, WidgetType.SLIDER, WidgetType.NUMBER_INPUT)
    CapabilityType.BOOLEAN -> listOf(WidgetType.VALUE, WidgetType.SWITCH)
    CapabilityType.ACTION -> listOf(WidgetType.BUTTON)
    CapabilityType.TEXT -> listOf(WidgetType.TEXT_INPUT)
}
private fun WidgetType.label() = when (this) { WidgetType.VALUE -> tr(AppStrings.status_card_7ec304); WidgetType.SWITCH -> tr(AppStrings.switch_d7716c); WidgetType.SLIDER -> tr(AppStrings.slider_6c9e05); WidgetType.BUTTON -> tr(AppStrings.button_a6a6ea); WidgetType.NUMBER_INPUT -> tr(AppStrings.numeric_input_a9fc9d); WidgetType.TEXT_INPUT -> tr(AppStrings.text_input) }

@Composable
internal fun PanelPicker(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label：${options.firstOrNull { it.first == selected }?.second ?: selected}" }, enabled = options.isNotEmpty()) {
            Text((options.firstOrNull { it.first == selected }?.second ?: selected).ifEmpty { tr(AppStrings.no_options_available_f8be9e) })
        }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) } }
    }
}

/** Read the latest checkpoint synchronously when multiple editor changes occur in one callback. */
private class EditorValue<T>(private val read: () -> T, private val write: (T) -> Unit) {
    operator fun getValue(owner: Any?, property: kotlin.reflect.KProperty<*>): T = read()
    operator fun setValue(owner: Any?, property: kotlin.reflect.KProperty<*>, value: T) = write(value)
}
