package com.lr.thingstalk.presentation.ble

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Accent
import com.lr.thingstalk.presentation.theme.Muted
import kotlin.time.Instant

@Composable
fun BleServicesPage(
    deviceName: String,
    model: BleDebugViewModel,
    onCharacteristic: (BleCharacteristicId) -> Unit,
    onLogs: () -> Unit,
    onConnect: () -> Unit,
) {
    val state by model.state.collectAsState()
    val subscriptions = (state.subscribing + state.observing).size
    Page("GATT EXPLORER", tr(AppStrings.services_and_characteristics_36f76b), deviceName) {
        Panel {
            ConnectionStatus(state.connected, onConnect)
            Text(
                tr(AppStrings.services_characteristics_descriptors_8528b1, state.services.size, state.services.sumOf { it.characteristics.size }, state.services.sumOf { service -> service.characteristics.sumOf { it.descriptors.size } }),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Primary(if (state.busy) tr(AppStrings.working_1cac8a) else tr(AppStrings.refresh_services_239dbf), enabled = state.connected && !state.busy) { model.refreshServices() }
            Text(tr(AppStrings.shows_the_gatt_structure_of_the_current_connection_refr_66f6d8), color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.view_traffic_log_f7636d)) }
            if (subscriptions > 0) {
                Text(tr(AppStrings.debug_subscriptions_starting_2ae288, subscriptions, state.subscribing.size), color = Accent)
                TextButton(onClick = { model.stopObserving() }) { Text(tr(AppStrings.stop_all_debug_subscriptions_49be81)) }
            }
            Text(tr(AppStrings.debug_notifications_continue_on_the_log_page_leaving_th_b84e0b), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        DebugMessages(state.error, state.feedback)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.services.isEmpty()) {
            Panel { Text(if (state.connected) tr(AppStrings.no_services_found_refresh_to_try_again_579c78) else tr(AppStrings.connect_a_peripheral_to_see_its_services_and_characteri_69eea4), color = Muted) }
        }
        state.services.forEachIndexed { serviceIndex, service ->
            Panel {
                Text(tr(AppStrings.service_f6a6f7, serviceIndex + 1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                UuidText(service.uuid)
                if (service.characteristics.isEmpty()) Text(tr(AppStrings.no_characteristics_found_in_this_service_da390c), color = Muted, style = MaterialTheme.typography.bodySmall)
                service.characteristics.forEachIndexed { characteristicIndex, characteristic ->
                    HorizontalDivider()
                    Text(tr(AppStrings.characteristic_a75f46, characteristicIndex + 1), style = MaterialTheme.typography.labelLarge)
                    UuidText(characteristic.id.characteristicUuid)
                    PropertyTags(characteristic.properties)
                    val unique = state.services.uniqueCharacteristic(characteristic.id) != null
                    if (!unique) Text(tr(AppStrings.this_uuid_path_is_not_unique_and_cannot_be_debugged_0218c0), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { onCharacteristic(characteristic.id) },
                        enabled = unique,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr(AppStrings.debug_characteristic_82d249)) }
                    DescriptorList(characteristic.descriptors)
                }
            }
        }
    }
}

@Composable
fun BleCharacteristicPage(
    deviceName: String,
    characteristicId: BleCharacteristicId,
    model: BleDebugViewModel,
    onLogs: () -> Unit,
    onConnect: () -> Unit,
) {
    val state by model.state.collectAsState()
    val characteristic = state.services.uniqueCharacteristic(characteristicId)
    val properties = characteristic?.properties.orEmpty()
    val modes = buildList {
        if (BleProperty.WRITE in properties) add(BleWriteMode.WITH_RESPONSE)
        if (BleProperty.WRITE_WITHOUT_RESPONSE in properties) add(BleWriteMode.WITHOUT_RESPONSE)
    }
    var writeHex by rememberSaveable(characteristicId.serviceUuid, characteristicId.characteristicUuid) { mutableStateOf("") }
    var writeMode by remember(characteristicId) { mutableStateOf(BleWriteMode.WITH_RESPONSE) }
    LaunchedEffect(modes) { if (writeMode !in modes) modes.firstOrNull()?.let { writeMode = it } }
    val available = state.connected && characteristic != null
    val canAct = available && !state.busy
    val latest = state.values[characteristicId]
    val subscribing = characteristicId in state.subscribing
    val observing = characteristicId in state.observing
    val maximumWriteLength = state.maximumWriteLengths[writeMode]
    val compactHex = writeHex.filterNot(Char::isWhitespace)
    val validHex = compactHex.isNotEmpty() && compactHex.length % 2 == 0 && compactHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    val byteCount = if (validHex) compactHex.length / 2 else null
    val payloadError = when {
        compactHex.isEmpty() -> null
        !validHex -> tr(AppStrings.use_two_hexadecimal_digits_per_byte_for_example_aa_01_f_3b71c0)
        maximumWriteLength != null && byteCount!! > maximumWriteLength -> tr(AppStrings.data_exceeds_the_current_single_write_limit_send_fewer_67ee22)
        else -> null
    }
    Page("CHARACTERISTIC", tr(AppStrings.characteristic_debugger_60772f), deviceName) {
        Panel {
            ConnectionStatus(state.connected, onConnect)
            Text(tr(AppStrings.service_uuid_b53963), color = Muted, style = MaterialTheme.typography.labelMedium)
            UuidText(characteristicId.serviceUuid)
            Text(tr(AppStrings.characteristic_uuid_dbadb2), color = Muted, style = MaterialTheme.typography.labelMedium)
            UuidText(characteristicId.characteristicUuid)
            if (characteristic != null) PropertyTags(properties)
            else if (state.connected) Text(tr(AppStrings.this_uuid_path_is_missing_or_has_multiple_instances_ret_aa56c0), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.view_traffic_log_f7636d)) }
        }
        DebugMessages(state.error, state.feedback)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Panel {
            Section(tr(AppStrings.latest_received_data_68bf3c))
            Text(tr(AppStrings.read_results_and_notifications_hex_8d9db9), color = Muted, style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(
                    when (latest) { null -> tr(AppStrings.no_data_received_yet_354c87); "" -> tr(AppStrings.empty_data_0_bytes_b22141); else -> latest },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!state.connected && latest != null) Text(tr(AppStrings.disconnected_showing_the_last_received_data_9c2ce9), color = Muted, style = MaterialTheme.typography.bodySmall)
            if (BleProperty.READ in properties) Primary(tr(AppStrings.read_characteristic_b7c327), enabled = canAct) { model.read(characteristicId) }
            else if (characteristic != null) Text(tr(AppStrings.this_characteristic_does_not_support_reads_01c2fb), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (modes.isNotEmpty()) Panel {
            Section(tr(AppStrings.write_data_f2bd3d))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { mode ->
                    FilterChip(selected = writeMode == mode, onClick = { writeMode = mode }, enabled = canAct, label = { Text(mode.label()) })
                }
            }
            Text(
                if (maximumWriteLength == null) tr(AppStrings.write_limit_unavailable_refresh_services_e12087) else tr(AppStrings.up_to_bytes_per_write_no_automatic_chunking_0d9f8d, maximumWriteLength),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = writeHex,
                onValueChange = { writeHex = it },
                label = { Text(tr(AppStrings.hex_data_82c833)) },
                placeholder = { Text("AA 01 FF") },
                modifier = Modifier.fillMaxWidth(),
                enabled = canAct,
                minLines = 2,
                maxLines = 5,
                isError = payloadError != null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                supportingText = { Text(payloadError ?: tr(AppStrings.bytes_spaces_and_line_breaks_are_allowed_ab8d68, byteCount ?: 0)) },
            )
            Primary(
                label = if (byteCount == null) tr(AppStrings.send_hex_b0d0db) else tr(AppStrings.send_bytes_413120, byteCount),
                enabled = canAct && validHex && payloadError == null && maximumWriteLength != null && writeMode in modes,
            ) { model.write(characteristicId, writeHex, writeMode) }
            Text(
                if (writeMode == BleWriteMode.WITH_RESPONSE) tr(AppStrings.writing_with_response_provides_a_gatt_acknowledgment_ex_3edaea) else tr(AppStrings.writing_without_response_does_not_wait_for_gatt_acknowl_6958d2),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (BleProperty.NOTIFY in properties || BleProperty.INDICATE in properties) Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(AppStrings.notifications_9be470), style = MaterialTheme.typography.titleMedium)
                    Text(when { subscribing -> tr(AppStrings.enabling_subscription_afe834); observing -> tr(AppStrings.ready_waiting_for_notifications_705830); else -> tr(AppStrings.not_subscribed_e1997c) }, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = subscribing || observing,
                    onCheckedChange = { model.setNotifications(characteristicId, it) },
                    enabled = available && (!state.busy || subscribing || observing),
                    modifier = Modifier.semantics { contentDescription = tr(AppStrings.receive_characteristic_notifications_3ff3f4) },
                )
            }
            if (subscribing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(tr(AppStrings.notifications_received_c58234, state.notificationCounts[characteristicId] ?: 0), color = Accent)
            Text(tr(AppStrings.view_raw_data_in_the_log_leaving_this_device_or_enterin_f1432e), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        characteristic?.let { Panel { DescriptorList(it.descriptors) } }
    }
}

@Composable
fun BleTrafficPage(deviceName: String, traffic: BleTraffic) {
    val entries by traffic.entries.collectAsState()
    // The host already consumes IME insets; keep the remaining safe area inside the list content.
    val bottomInset = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
        .only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    var filter by rememberSaveable { mutableStateOf("choice:all_778fc8") }
    val visible = remember(entries, filter) {
        entries.asReversed().filter {
            when (filter) {
                "RX" -> it.direction == BleTrafficDirection.RX
                "TX" -> it.direction == BleTrafficDirection.TX
                "choice:system_errors_68b07d" -> it.direction == BleTrafficDirection.SYSTEM || it.direction == BleTrafficDirection.ERROR
                else -> true
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 28.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("BLE TRAFFIC", color = Accent, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
                Text(tr(AppStrings.traffic_log_c1c823), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(deviceName, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Panel {
                Section(tr(AppStrings.records_f35481, entries.size), trailing = tr(AppStrings.clear_84fcd7)) { traffic.clear() }
                Choices(listOf("choice:all_778fc8", "RX", "TX", "choice:system_errors_68b07d"), filter) { filter = it }
                Text(tr(AppStrings.newest_first_tx_indicates_a_send_attempt_completion_and_8c0f33), color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(tr(AppStrings.up_to_500_records_enable_notifications_on_the_character_f02f2c), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (visible.isEmpty()) item { Panel { Text(if (entries.isEmpty()) tr(AppStrings.no_traffic_yet_connect_a_peripheral_to_read_write_or_re_499920) else tr(AppStrings.no_records_match_this_filter_7c8984), color = Muted) } }
        items(visible, key = { it.id }) { entry ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(entry.direction.name)
                    Text(entry.operation, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = if (entry.direction == BleTrafficDirection.ERROR) MaterialTheme.colorScheme.error else Accent)
                    if (entry.hex != null) Text("${entry.byteCount} B", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text(Instant.fromEpochMilliseconds(entry.timestampMillis).toString().replace("T", " ").replace("Z", " UTC"), color = Muted, style = MaterialTheme.typography.labelSmall)
                entry.characteristic?.let { characteristic ->
                    Text(tr(AppStrings.service_4b8184), color = Muted, style = MaterialTheme.typography.labelSmall)
                    UuidText(characteristic.serviceUuid)
                    Text(tr(AppStrings.characteristic_672c1d), color = Muted, style = MaterialTheme.typography.labelSmall)
                    UuidText(characteristic.characteristicUuid)
                }
                entry.hex?.let { hex -> SelectionContainer { Text(hex.ifEmpty { tr(AppStrings.empty_data_0_bytes_b22141) }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) } }
                if (entry.detail.isNotBlank()) Text(diagnosticText(entry.detail), color = if (entry.direction == BleTrafficDirection.ERROR) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConnectionStatus(connected: Boolean, onConnect: () -> Unit) {
    Tag(if (connected) tr(AppStrings.peripheral_connected_4b29ba) else tr(AppStrings.peripheral_disconnected_e404be))
    if (!connected) {
        Text(tr(AppStrings.return_to_the_device_page_to_connect_first_40dfd3), color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.go_to_device_connection_516e02)) }
    }
}

@Composable
private fun DebugMessages(error: String?, feedback: String?) {
    error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
    feedback?.let { Text(diagnosticText(it), color = Accent, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun UuidText(uuid: String) {
    SelectionContainer { Text(uuid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun PropertyTags(properties: Set<BleProperty>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BleProperty.entries.filter { it in properties }.forEach { property -> Tag(when (property) {
            BleProperty.READ -> "Read"
            BleProperty.WRITE -> "Write"
            BleProperty.WRITE_WITHOUT_RESPONSE -> "Write no response"
            BleProperty.NOTIFY -> "Notify"
            BleProperty.INDICATE -> "Indicate"
        }) }
        if (properties.isEmpty()) Tag(tr(AppStrings.no_read_write_properties_available_c2a7ab))
    }
}

@Composable
private fun DescriptorList(descriptors: List<BleDescriptor>) {
    Text(tr(AppStrings.descriptors_207b05, descriptors.size), color = Muted, style = MaterialTheme.typography.labelMedium)
    descriptors.forEach { descriptor ->
        UuidText(descriptor.uuid)
        if (descriptor.uuid.equals("00002902-0000-1000-8000-00805f9b34fb", ignoreCase = true)) Text(tr(AppStrings.cccd_managed_automatically_by_the_ble_library_when_subs_2e1b1c), color = Muted, style = MaterialTheme.typography.labelSmall)
    }
    Text(if (descriptors.isEmpty()) tr(AppStrings.no_descriptors_found_0a2022) else tr(AppStrings.descriptors_are_shown_for_inspection_only_raw_reads_and_cb8b38), color = Muted, style = MaterialTheme.typography.bodySmall)
}

private fun BleWriteMode.label() = if (this == BleWriteMode.WITH_RESPONSE) tr(AppStrings.write_with_response_7b158f) else tr(AppStrings.write_without_response_e8c7de)

/** The current BLE abstraction addresses UUID paths, so it cannot safely address duplicate instances. */
private fun List<BleService>.uniqueCharacteristic(id: BleCharacteristicId): BleCharacteristic? {
    val service = filter { it.uuid.equals(id.serviceUuid, ignoreCase = true) }.singleOrNull() ?: return null
    return service.characteristics.filter { it.id.characteristicUuid.equals(id.characteristicUuid, ignoreCase = true) }.singleOrNull()
}
