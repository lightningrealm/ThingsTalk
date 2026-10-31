package com.lr.thingstalk.presentation.ble

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.presentation.components.Panel
import com.lr.thingstalk.presentation.components.Toggle
import com.lr.thingstalk.presentation.theme.Muted

@Composable
internal fun BleScanFilters(filter: BleScanFilter, onChange: (BleScanFilter) -> Unit, onClear: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(filter.namedOnly || filter.minimumRssi != null || filter.serviceUuid.isNotBlank()) }
    val focus = LocalFocusManager.current
    val advancedSummary = buildList {
        if (filter.namedOnly) add(tr(AppStrings.named_only_f55924))
        filter.minimumRssi?.let { add("≥ $it dBm") }
        filter.serviceUuid.trim().takeIf { it.isNotEmpty() }?.let { add(tr(AppStrings.service_f6a6f7, it)) }
    }.joinToString(" · ")
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(AppStrings.filter_devices_adfc92), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { focus.clearFocus(); onClear() }, enabled = filter.isActive) { Text(tr(AppStrings.clear_filters_4bbc4e)) }
        }
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onChange(filter.copy(query = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr(AppStrings.device_name_or_id_e0ee72)) },
            placeholder = { Text(tr(AppStrings.enter_a_keyword_532b4f)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
        TextButton(onClick = { focus.clearFocus(); expanded = !expanded }) { Text(if (expanded) tr(AppStrings.fewer_filters_71b0cb) else tr(AppStrings.more_filters_194b82)) }
        if (!expanded && advancedSummary.isNotEmpty()) {
            Text(advancedSummary, color = if (filter.serviceUuidError != null) MaterialTheme.colorScheme.error else Muted,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Toggle(tr(AppStrings.only_show_named_devices_0b8045), tr(AppStrings.hide_peripherals_without_an_advertised_name_96f405), filter.namedOnly) { onChange(filter.copy(namedOnly = it)) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(AppStrings.minimum_signal_strength_ae8192), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null, -90, -75, -60).forEach { threshold ->
                            FilterChip(
                                selected = filter.minimumRssi == threshold,
                                onClick = { onChange(filter.copy(minimumRssi = threshold)) },
                                label = { Text(threshold?.let { "≥ $it dBm" } ?: tr(AppStrings.any_09c4fc)) },
                            )
                        }
                    }
                    Text(tr(AppStrings.values_closer_to_0_indicate_stronger_signals_not_necess_91131f), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = filter.serviceUuid,
                    onValueChange = { onChange(filter.copy(serviceUuid = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr(AppStrings.advertised_service_uuid_fragment_b09ea3)) },
                    placeholder = { Text(tr(AppStrings.for_example_fff_180d_or_a_full_uuid_249209)) },
                    singleLine = true,
                    isError = filter.serviceUuidError != null,
                    supportingText = { Text(filter.serviceUuidError?.let(::diagnosticText) ?: tr(AppStrings.partial_match_ignoring_case_and_hyphens_matches_adverti_2a7a55)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                Text(tr(AppStrings.all_filters_apply_together_results_update_immediately_50db32), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
