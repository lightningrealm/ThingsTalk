package com.lr.thingstalk.presentation.mqtt

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.mqtt.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted
import kotlin.uuid.Uuid

val LocalMqttProfiles = staticCompositionLocalOf<MqttProfilesViewModel> { error("MQTT profile provider missing") }

@Composable
fun MqttProfilePicker(owner: String, enabled: Boolean, onManage: () -> Unit) {
    val vm = LocalMqttProfiles.current
    val archive by vm.profiles.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val selected = archive.selectedProfile(owner)
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && loaded && !busy && archive.profiles.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(selected?.name ?: if (archive.profiles.isEmpty()) tr(AppStrings.no_connection_profiles_yet_885693) else tr(AppStrings.choose_connection_profile_69d245))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            archive.profiles.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name) }, onClick = { expanded = false; vm.select(owner, profile.id) })
            }
        }
    }
    selected?.let {
        Text(it.endpoint, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(if (it.requiresPassword && it.password.isEmpty()) tr(AppStrings.password_needed_for_this_session_e758dc) else if (it.username.isNotEmpty() || it.password.isNotEmpty()) tr(AppStrings.authentication_configured_d34c7c) else tr(AppStrings.anonymous_connection_6d2112), color = Muted)
    }
    TextButton(onClick = onManage, enabled = !busy) { Text(tr(AppStrings.manage_connection_profiles_a49d55)) }
    if (!loaded && !busy) TextButton(onClick = vm::reload) { Text(tr(AppStrings.reload_profiles_5913b2)) }
    error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
fun MqttProfilesPage(owner: String, onEdit: (String) -> Unit) {
    val vm = LocalMqttProfiles.current
    val archive by vm.profiles.collectAsState()
    val busy by vm.busy.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val error by vm.error.collectAsState()
    var deleting by remember { mutableStateOf<MqttProfile?>(null) }
    Page("MQTT CONNECTIONS", tr(AppStrings.connection_profiles_7d057b), tr(AppStrings.shared_by_workbench_and_custom_devices_select_a_profile_8eaff4)) {
        Primary(tr(AppStrings.add_connection_profile_af4431), loaded && !busy) { onEdit("") }
        if (!loaded) {
            Text(if (busy) tr(AppStrings.loading_connection_profiles_75f7e9) else tr(AppStrings.profiles_unavailable_existing_data_is_kept_8fed62), color = Muted)
            TextButton(onClick = vm::reload, enabled = !busy) { Text(tr(AppStrings.reload_778497)) }
        }
        archive.profiles.forEach { profile ->
            Panel {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(profile.endpoint, color = Muted)
                if (archive.selected[owner] == profile.id) Tag(tr(AppStrings.selected_c6ee5b))
                Row {
                    TextButton(onClick = { vm.select(owner, profile.id) }, enabled = !busy) { Text(tr(AppStrings.select_fc50b1)) }
                    TextButton(onClick = { onEdit(profile.id) }, enabled = !busy) { Text(tr(AppStrings.edit_a7f814)) }
                    TextButton(onClick = { deleting = profile }, enabled = !busy) { Text(tr(AppStrings.delete_3755f5)) }
                }
            }
        }
        error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        Text(tr(AppStrings.editing_or_deleting_profiles_does_not_affect_live_conne_4980b4), color = Muted)
    }
    deleting?.let { profile ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr(AppStrings.delete_69cde0, profile.name)) },
            text = { Text(tr(AppStrings.removes_this_local_profile_and_its_selections_device_pa_a1136a)) },
            confirmButton = { TextButton(onClick = { vm.delete(profile.id); deleting = null }, enabled = !busy) { Text(tr(AppStrings.delete_3755f5)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr(AppStrings.cancel_4d0b46)) } })
    }
}

@Composable
fun MqttProfileEditorPage(owner: String, profileId: String, initialEndpoint: String, onSaved: () -> Unit) {
    val vm = LocalMqttProfiles.current
    val archive by vm.profiles.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    if (!loaded) { Page("MQTT CONFIG", tr(AppStrings.connection_profiles_7d057b), "") { Text(tr(AppStrings.load_connection_profiles_first_ccaecc)); TextButton(onClick = vm::reload, enabled = !busy) { Text(tr(AppStrings.reload_778497)) } }; return }
    val existing = archive.profiles.firstOrNull { it.id == profileId }
    if (profileId.isNotEmpty() && existing == null) { Page("MQTT CONFIG", tr(AppStrings.profile_deleted_f9c34f), tr(AppStrings.return_to_the_profile_list_and_select_another_1875ef)) {}; return }
    var draft by remember(owner, profileId) { mutableStateOf(existing ?: MqttProfile(Uuid.random().toString(), "", initialEndpoint)) }
    var discard by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { discard = true }, enabled = !busy) { Text(tr(AppStrings.revert_changes_6c0c9a)) }
                TextButton(onClick = { vm.save(draft, owner, onSaved) }, enabled = !busy && draft.name.isNotBlank() && draft.endpoint.isNotBlank()) { Text(if (busy) tr(AppStrings.saving_6644f0) else tr(AppStrings.save_and_select_fc2bbb)) }
            }
        }
        Box(Modifier.weight(1f)) {
            Page("MQTT CONFIG", if (profileId.isEmpty()) tr(AppStrings.add_connection_profile_af4431) else tr(AppStrings.edit_connection_profile_163dcd), tr(AppStrings.mqtt_5_websocket_wss_4ce153)) {
                error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
                Panel {
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text(tr(AppStrings.profile_name_a5f08c)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.endpoint, { draft = draft.copy(endpoint = it) }, label = { Text(tr(AppStrings.server_address_2eb291)) }, placeholder = { Text("wss://broker.example.com:8084/mqtt") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text(tr(AppStrings.include_port_and_path_mqtt_native_tcp_url_query_paramet_a1c90b), color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(tr(AppStrings.generate_client_id_automatically_6468bf))
                        Switch(draft.automaticClientId, { draft = draft.copy(automaticClientId = it) }, enabled = !busy)
                    }
                    if (!draft.automaticClientId) OutlinedTextField(draft.clientId, { draft = draft.copy(clientId = it) }, label = { Text(tr(AppStrings.fixed_client_id_f04f74)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text(if (draft.automaticClientId) tr(AppStrings.generates_a_unique_id_for_each_connection_so_devices_sh_f94840) else tr(AppStrings.the_fixed_id_must_meet_server_requirements_concurrent_c_8be422), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Panel {
                    Text(tr(AppStrings.authentication_optional_4bdf78), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(draft.username, { draft = draft.copy(username = it) }, label = { Text(tr(AppStrings.username_a1aaf3)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.password, { draft = draft.copy(password = it, requiresPassword = it.isNotEmpty()) }, label = { Text(tr(AppStrings.password_c839a8)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (draft.requiresPassword && draft.password.isEmpty()) Text(tr(AppStrings.this_profile_requires_a_password_enter_it_or_explicitly_87679f), color = Muted)
                    TextButton(onClick = { draft = draft.copy(password = "", requiresPassword = false) }, enabled = !busy) { Text(tr(AppStrings.clear_password_bcf73a)) }
                    Text(if (vm.passwordsPersisted) tr(AppStrings.credentials_are_stored_in_protected_local_storage_never_25f924) else tr(AppStrings.the_browser_stores_ordinary_settings_only_passwords_las_22e560), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(tr(AppStrings.uses_a_clean_session_with_no_automatic_reconnect_or_res_fe34de), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(tr(AppStrings.revert_unsaved_changes_c5c0bf)) },
        confirmButton = { TextButton(onClick = { draft = existing ?: MqttProfile(draft.id, "", initialEndpoint); discard = false }) { Text(tr(AppStrings.revert_457d44)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(tr(AppStrings.cancel_4d0b46)) } })
}
