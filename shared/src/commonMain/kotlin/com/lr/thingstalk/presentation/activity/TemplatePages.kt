package com.lr.thingstalk.presentation.activity

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.lr.thingstalk.domain.activity.ActivityRepository
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.navigation.*
import com.lr.thingstalk.presentation.device.DeviceWorkspaceViewModel
import com.lr.thingstalk.presentation.panel.rememberPanelFileTransfer
import com.lr.thingstalk.presentation.panel.preparePanelImport
import com.lr.thingstalk.presentation.panel.PreparedPanelImport
import com.lr.thingstalk.presentation.theme.Muted

@Composable
internal fun TemplatePages(route: Route, activity: ActivityRepository, workspace: DeviceWorkspaceViewModel,
    go: (Route) -> Unit, feedback: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val archive by activity.archive.collectAsState()
    val error by activity.error.collectAsState()
    val devices by workspace.devices.collectAsState()
    var source by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<PreparedPanelImport?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    fun importText(text: String) {
        try {
            val imported = preparePanelImport(text)
            prepared = imported
            source = imported.source; title = imported.panel.title; localError = null
        } catch (failure: Exception) { localError = failure.message ?: tr(AppStrings.unsupported_format_or_platform_use_a_valid_thingstalk_p_7a1a41) }
    }
    val files = rememberPanelFileTransfer(::importText, feedback)
    val template = archive.templates.firstOrNull { it.id == route.id }
    if (route.screen == Screen.PRESET) {
        val definition = remember(template?.json) { template?.let { runCatching { PanelJson.decode(it.json) }.getOrNull() } }
        Page("PANEL TEMPLATE", template?.title ?: tr(AppStrings.template_not_found_ecb0c7), tr(AppStrings.load_an_editing_draft_first_the_device_panel_changes_on_2896e2)) {
            if (template != null && definition != null) {
                Panel { KeyValue(tr(AppStrings.transport_14b6b3), definition.transport.name); KeyValue(tr(AppStrings.format_version_9d04be), definition.schemaVersion.toString()); KeyValue(tr(AppStrings.widgets_7359f0), definition.widgets.size.toString()); KeyValue(tr(AppStrings.setup_forms_000d28), definition.setupPanels.size.toString()); Text(definition.setupImportNotice(), color = Muted) }
                OutlinedButton(onClick = { files.exportFile(template.json, "panel-template.thingstalk.json") }, enabled = !files.busy) { Text(tr(AppStrings.export_template_8bf525)) }
                Section(tr(AppStrings.apply_to_device_c8fbec))
                val eligible = devices.filter { it.panel.transport == definition.transport || it.panel.transport == PanelTransport.DUAL }
                if (eligible.isEmpty()) Text(tr(AppStrings.no_compatible_devices_create_one_or_upgrade_to_dual_con_7fd725), color = Muted)
                eligible.forEach { d -> Item(d.name, tr(AppStrings.replaces_the_editing_draft_device_identifiers_and_crede_aabc6c)) { targetId = d.id } }
                OutlinedButton(onClick = { deleting = true }) { Text(tr(AppStrings.delete_template_36fb17)) }
                Section(tr(AppStrings.configuration_preview_6c2397))
                SelectionContainer { Text(template.json, style = MaterialTheme.typography.bodySmall) }
            }
            localError?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        }
        targetId?.let { id -> AlertDialog(onDismissRequest = { targetId = null }, title = { Text(tr(AppStrings.load_template_draft_34828e)) },
            text = { Text(tr(AppStrings.replaces_the_current_editing_draft_including_unsaved_ch_0aa7a3)) },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                val editor = workspace.editor(id)
                editor?.state?.first { !it.loading }
                if (editor == null || !editor.state.value.editable || definition == null || template == null) { localError = tr(AppStrings.the_editor_is_not_ready_or_has_a_draft_to_recover_open_354c39) }
                else { editor.importDefinition(definition, template.json); go(Route(Screen.PANEL_EDITOR, id)) }
                }
                targetId = null
            }) { Text(tr(AppStrings.load_and_review_aa4cad)) } }, dismissButton = { TextButton(onClick = { targetId = null }) { Text(tr(AppStrings.cancel_4d0b46)) } }) }
        if (deleting && template != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(tr(AppStrings.delete_template_e35295)) }, text = { Text(tr(AppStrings.devices_already_using_this_template_are_not_affected_7b1c3c)) },
            confirmButton = { TextButton(onClick = { activity.deleteTemplate(template.id); deleting = false; go(Route(Screen.PRESETS)) }) { Text(tr(AppStrings.delete_3755f5)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(tr(AppStrings.cancel_4d0b46)) } })
    } else Page("PANEL LIBRARY", tr(AppStrings.panel_templates_4fb3e5), tr(AppStrings.templates_contain_protocols_and_widget_definitions_only_de2cbc)) {
        archive.templates.forEach { t -> Item(t.title, tr(AppStrings.view_export_or_apply_to_a_compatible_device_68d50f)) { go(Route(Screen.PRESET, t.id)) } }
        if (archive.templates.isEmpty()) Text(tr(AppStrings.no_templates_yet_save_one_from_a_device_or_import_a_con_b0b358), color = Muted)
        Section(tr(AppStrings.save_template_from_device_ec49a7))
        devices.forEach { d -> TextButton(onClick = { source = PanelJson.encode(d.panel); title = d.name; localError = null; prepared = null }) { Text(tr(AppStrings.use_the_saved_panel_of_c76859, d.name)) } }
        Section(tr(AppStrings.import_and_save_0293c4))
        OutlinedButton(onClick = files.importFile, enabled = !files.busy) { Text(tr(AppStrings.import_thingstalk_json_ed1b67)) }
        prepared?.notice()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(title, { title = it }, label = { Text(tr(AppStrings.template_name_bbc511)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(source, { source = it }, label = { Text(tr(AppStrings.complete_panel_json_bc7c46)) }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10)
        Primary(tr(AppStrings.validate_and_save_template_8c4209), !files.busy && source.isNotBlank() && title.isNotBlank()) {
            try {
                val imported = preparePanelImport(source)
                if (imported.mqttOnly) {
                    prepared = imported; source = imported.source; localError = null
                } else {
                    activity.saveTemplate(title.trim(), imported.source)
                    localError = null; source = ""; title = ""; prepared = null
                }
            } catch (failure: Exception) { localError = failure.message ?: tr(AppStrings.could_not_save_check_the_format_platform_name_and_templ_056469) }
        }
        localError?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        Text(tr(AppStrings.up_to_20_templates_each_up_to_50_000_characters_larger_73d253), color = Muted)
    }
}
