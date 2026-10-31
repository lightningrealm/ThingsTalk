package com.lr.thingstalk.presentation.ble

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
        if (filter.namedOnly) add("仅有名称")
        filter.minimumRssi?.let { add("≥ $it dBm") }
        filter.serviceUuid.trim().takeIf { it.isNotEmpty() }?.let { add("服务 $it") }
    }.joinToString(" · ")
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("筛选设备", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { focus.clearFocus(); onClear() }, enabled = filter.isActive) { Text("清除筛选") }
        }
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onChange(filter.copy(query = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("设备名称或 ID") },
            placeholder = { Text("输入关键词") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
        TextButton(onClick = { focus.clearFocus(); expanded = !expanded }) { Text(if (expanded) "收起筛选" else "更多筛选") }
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
                Toggle("仅显示有名称的设备", "隐藏未广播名称的外设", filter.namedOnly) { onChange(filter.copy(namedOnly = it)) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("最低信号强度", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null, -90, -75, -60).forEach { threshold ->
                            FilterChip(
                                selected = filter.minimumRssi == threshold,
                                onClick = { onChange(filter.copy(minimumRssi = threshold)) },
                                label = { Text(threshold?.let { "≥ $it dBm" } ?: "不限") },
                            )
                        }
                    }
                    Text("数值越接近 0，信号越强；信号强度不等于距离。", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = filter.serviceUuid,
                    onValueChange = { onChange(filter.copy(serviceUuid = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("广播服务 UUID 片段") },
                    placeholder = { Text("例如 FFF、180D 或完整 UUID") },
                    singleLine = true,
                    isError = filter.serviceUuidError != null,
                    supportingText = { Text(filter.serviceUuidError ?: "局部匹配，忽略大小写和连字符；仅匹配广播中的服务。") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                Text("所有条件同时生效，修改后立即更新结果。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
