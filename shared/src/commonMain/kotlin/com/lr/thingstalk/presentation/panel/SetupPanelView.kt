package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted
import kotlinx.coroutines.*

/** Form values, particularly passwords, never enter the panel definition or saved instance state. */
@Composable
internal fun SetupPanelView(definition: SetupPanel, session: SetupPanelSession?) {
    var expanded by remember(definition.id) { mutableStateOf(false) }
    Panel {
        TextButton(onClick = { expanded = !expanded }) { Text(definition.title + if (expanded) " −" else " +") }
        if (!expanded) return@Panel
        Text(definition.instructions, color = Muted)
        if (session == null) {
            Text(tr(AppStrings.connect_ble_the_device_must_expose_the_services_and_cha_28ccf4), color = Muted)
            return@Panel
        }
        key(session, definition) { SetupForm(definition, session) }
    }
}

@Composable
private fun SetupForm(definition: SetupPanel, session: SetupPanelSession) {
    var selected by remember { mutableStateOf(definition.sections.first().id) }
    var inputs by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var clearSecrets by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var savedPasswords by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var confirmSave by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf<Boolean?>(null) }
    var unsavedSections by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var preparationJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        val status = session.status()
        unlocked = status.unlocked
        savedPasswords = status.savedSecretFieldIds
        unsavedSections = definition.sections.filter { it.id !in status.savedSectionIds }.map { it.id }.toSet()
        if (status.unlocked) { inputs = definition.initialInputs(session.readValues(), unsavedSections); loaded = true }
    }
    Text(when {
        busy && !loaded -> tr(AppStrings.verifying_device_access_after_pairing_wait_while_the_cl_b61510)
        busy -> tr(AppStrings.communicating_with_device_f7d4de)
        unlocked == false -> tr(AppStrings.the_device_settings_window_is_closed_open_it_as_instruc_ee1beb)
        !loaded -> tr(AppStrings.prepare_device_settings_first_reading_configuration_may_9e2e09)
        else -> tr(AppStrings.configuration_read_successfully_ready_to_edit_1dfbbb)
    }, color = Muted)
    TextButton(enabled = !busy, onClick = {
        preparationJob = scope.launch {
            busy = true; loaded = false; unlocked = null; message = null
            try { reload() }
            catch (timeout: TimeoutCancellationException) { message = tr(AppStrings.preparation_timed_out_complete_system_pairing_check_the_ffde10) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SetupPanelFailure) { message = failure.message }
            catch (failure: IllegalArgumentException) { message = failure.message ?: tr(AppStrings.invalid_device_settings_configuration_b70448) }
            catch (_: Exception) { message = tr(AppStrings.read_failed_check_the_connection_and_device_state_bb1d9a) }
            finally { busy = false }
        }
    }) { Text(if (loaded) tr(AppStrings.prepare_again_and_read_configuration_excluding_password_8f42e1) else tr(AppStrings.prepare_device_settings_pairing_may_be_required_5f3ef0)) }
    if (busy && !loaded) TextButton(onClick = {
        preparationJob?.cancel()
        message = tr(AppStrings.preparation_cancelled_no_configuration_command_was_sent_e1fe2f)
    }) { Text(tr(AppStrings.cancel_preparation_cc6016)) }
    Row { definition.sections.forEach { section ->
        TextButton(enabled = !busy, onClick = { selected = section.id }) { Text((if (selected == section.id) "● " else "") + section.title) }
    } }
    val section = definition.sections.single { it.id == selected }
    if (loaded && section.id in unsavedSections) Text(tr(AppStrings.no_configuration_saved_on_the_device_yet_you_can_fill_i_f9f030, section.title), color = Muted)
    section.fields.forEach { field ->
        if (field.readOnly) Text("${field.label}: ${field.defaultValue}", style = MaterialTheme.typography.bodySmall, color = Muted)
        else OutlinedTextField(inputs[field.id].orEmpty(), { inputs = inputs + (field.id to it) },
            label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy && loaded,
            visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None)
        field.defaultValue?.takeIf { !definition.protocol.isAi }?.let { expected ->
            Text(tr(AppStrings.panel_suggestion_for_keep_it_consistent_with_the_device_f7aabf, field.label, expected), color = Muted, style = MaterialTheme.typography.bodySmall)
            if (loaded && inputs[field.id] != expected) {
                Text(tr(AppStrings.this_value_differs_from_the_panel_suggestion_the_device_66eeb1), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { inputs = inputs + (field.id to expected) }) { Text(tr(AppStrings.use_suggested_value_ee3d62)) }
            }
        }
        if (field.secret) {
            Text(tr(AppStrings.leave_blank_to_keep_the_device_value_an_empty_value_is_38a02c), color = Muted, style = MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(field.id in clearSecrets, { clearSecrets = if (it) clearSecrets + field.id else clearSecrets - field.id }, enabled = !busy && loaded)
                Text(tr(AppStrings.clear_45d73a, field.label))
            }
        }
    }
    fun submitSection() {
        val values = buildMap {
            section.fields.forEach { field ->
                val value = inputs[field.id].orEmpty()
                if (!field.secret || value.isNotEmpty() || field.id in clearSecrets) put(field.id, if (field.id in clearSecrets) "" else value)
            }
        }
        busy = true; message = null
        scope.launch {
            try {
                val result = session.submit(section.id, values)
                savedPasswords = result.savedSecretFieldIds
                unsavedSections = definition.sections.filter { it.id !in result.savedSectionIds }.map { it.id }.toSet(); message = if (definition.protocol.isAi) tr(AppStrings.ai_setup_saved) else tr(AppStrings.device_confirmed_saving_this_does_not_confirm_network_c_4349b6) }
            catch (timeout: TimeoutCancellationException) { message = tr(AppStrings.save_result_unknown_read_back_to_verify_no_automatic_re_a1c190) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SetupPanelFailure) {
                message = buildString {
                    append(failure.message)
                    failure.opcode?.let { append(tr(AppStrings.noperation_0x_request_5c2e10, it.toString(16), failure.request)) }
                    failure.reply?.let { append(tr(AppStrings.nreceipt_request_operation_0x_result_field_offset_c338bc, it.request, it.opcode.toString(16), it.result, it.field, it.offset)) }
                }
            }
            catch (failure: IllegalArgumentException) { message = failure.message ?: tr(AppStrings.invalid_field_72c644) }
            catch (_: Exception) { message = tr(AppStrings.device_did_not_confirm_saving_check_the_connection_sett_5e679e) }
            finally {
                val secrets = definition.sections.flatMap { it.fields }.filter { it.secret }.map { it.id }.toSet()
                inputs = inputs - secrets; clearSecrets = emptySet(); busy = false
            }
        }
    }
    val passwordNotice = section.passwordConfirmation(inputs, clearSecrets, savedPasswords)
    Primary(if (busy) tr(AppStrings.communicating_8c8c3f) else tr(AppStrings.save_to_device_6f952b, section.title), !busy && loaded && unlocked == true) {
        if (passwordNotice != null) confirmSave = true else submitSection()
    }
    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        title = { Text(tr(AppStrings.confirm_password_handling_2d43bc)) },
        text = { Text(passwordNotice.orEmpty().lines().joinToString("\n") { diagnosticText(it) } + tr(AppStrings.nconfiguration_will_be_sent_only_after_confirmation_53629e)) },
        confirmButton = { TextButton(enabled = !busy && loaded && unlocked == true, onClick = {
            confirmSave = false
            submitSection()
        }) { Text(tr(AppStrings.continue_saving_2b9228)) } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text(tr(AppStrings.return_to_form_0566fa)) } },
    )
    message?.let { Text(diagnosticText(it), color = Muted) }
}
