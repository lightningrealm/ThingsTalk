package com.lr.thingstalk.presentation.ble

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
    Page("GATT EXPLORER", "服务与特征", deviceName) {
        Panel {
            ConnectionStatus(state.connected, onConnect)
            Text(
                "${state.services.size} 个服务 · ${state.services.sumOf { it.characteristics.size }} 个特征 · ${state.services.sumOf { service -> service.characteristics.sumOf { it.descriptors.size } }} 个描述符",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Primary(if (state.busy) "处理中…" else "刷新服务", enabled = state.connected && !state.busy) { model.refreshServices() }
            Text("显示当前连接发现的 GATT 结构。刷新会停止调试订阅并清空最近值。", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("查看收发日志") }
            if (subscriptions > 0) {
                Text("$subscriptions 个调试通知订阅 · ${state.subscribing.size} 个正在启用", color = Accent)
                TextButton(onClick = { model.stopObserving() }) { Text("停止所有调试订阅") }
            }
            Text("调试订阅可在日志页持续接收；离开此设备或进入后台会停止。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        DebugMessages(state.error, state.feedback)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.services.isEmpty()) {
            Panel { Text(if (state.connected) "尚未发现服务，点击刷新服务重试。" else "连接外设后，这里会显示它实际提供的服务和特征。", color = Muted) }
        }
        state.services.forEachIndexed { serviceIndex, service ->
            Panel {
                Text("服务 ${serviceIndex + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                UuidText(service.uuid)
                if (service.characteristics.isEmpty()) Text("此服务未发现特征。", color = Muted, style = MaterialTheme.typography.bodySmall)
                service.characteristics.forEachIndexed { characteristicIndex, characteristic ->
                    HorizontalDivider()
                    Text("特征 ${characteristicIndex + 1}", style = MaterialTheme.typography.labelLarge)
                    UuidText(characteristic.id.characteristicUuid)
                    PropertyTags(characteristic.properties)
                    val unique = state.services.uniqueCharacteristic(characteristic.id) != null
                    if (!unique) Text("UUID 路径不唯一，暂不支持调试此特征。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { onCharacteristic(characteristic.id) },
                        enabled = unique,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开特征调试") }
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
        !validHex -> "每个字节需要两位十六进制字符，例如 AA 01 FF。"
        maximumWriteLength != null && byteCount!! > maximumWriteLength -> "超过当前单次写入上限，请缩短数据。"
        else -> null
    }
    Page("CHARACTERISTIC", "特征调试", deviceName) {
        Panel {
            ConnectionStatus(state.connected, onConnect)
            Text("服务 UUID", color = Muted, style = MaterialTheme.typography.labelMedium)
            UuidText(characteristicId.serviceUuid)
            Text("特征 UUID", color = Muted, style = MaterialTheme.typography.labelMedium)
            UuidText(characteristicId.characteristicUuid)
            if (characteristic != null) PropertyTags(properties)
            else if (state.connected) Text("此 UUID 路径未发现或存在多个实例，无法操作。请返回服务树刷新。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("查看收发日志") }
        }
        DebugMessages(state.error, state.feedback)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Panel {
            Section("最近收到的数据")
            Text("读取结果与通知内容 · HEX", color = Muted, style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(
                    when (latest) { null -> "尚未收到数据"; "" -> "空数据 · 0 字节"; else -> latest },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!state.connected && latest != null) Text("连接已断开，以上为最近一次收到的数据。", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (BleProperty.READ in properties) Primary("读取特征", enabled = canAct) { model.read(characteristicId) }
            else if (characteristic != null) Text("此特征不支持主动读取。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (modes.isNotEmpty()) Panel {
            Section("写入数据")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { mode ->
                    FilterChip(selected = writeMode == mode, onClick = { writeMode = mode }, enabled = canAct, label = { Text(mode.label()) })
                }
            }
            Text(
                if (maximumWriteLength == null) "暂未取得单次写入上限，请刷新服务。" else "当前单次最多 $maximumWriteLength 字节 · 不自动分包",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = writeHex,
                onValueChange = { writeHex = it },
                label = { Text("HEX 数据") },
                placeholder = { Text("AA 01 FF") },
                modifier = Modifier.fillMaxWidth(),
                enabled = canAct,
                minLines = 2,
                maxLines = 5,
                isError = payloadError != null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                supportingText = { Text(payloadError ?: "${byteCount ?: 0} 字节 · 可用空格或换行分隔") },
            )
            Primary(
                label = if (byteCount == null) "发送 HEX" else "发送 $byteCount 字节",
                enabled = canAct && validHex && payloadError == null && maximumWriteLength != null && writeMode in modes,
            ) { model.write(characteristicId, writeHex, writeMode) }
            Text(
                if (writeMode == BleWriteMode.WITH_RESPONSE) "有响应写入可获得 GATT 确认；设备是否完成业务操作仍取决于它的协议回复。" else "无响应写入不等待 GATT 确认；发送完成不代表设备已经执行。",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (BleProperty.NOTIFY in properties || BleProperty.INDICATE in properties) Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("接收通知", style = MaterialTheme.typography.titleMedium)
                    Text(when { subscribing -> "正在启用，等待订阅就绪…"; observing -> "已就绪，等待外设推送"; else -> "未订阅" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = subscribing || observing,
                    onCheckedChange = { model.setNotifications(characteristicId, it) },
                    enabled = available && (!state.busy || subscribing || observing),
                    modifier = Modifier.semantics { contentDescription = "接收特征通知" },
                )
            }
            if (subscribing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("通知流已接收 ${state.notificationCounts[characteristicId] ?: 0} 条数据", color = Accent)
            Text("日志页可持续查看原始数据。离开此设备或进入后台会停止调试订阅。", color = Muted, style = MaterialTheme.typography.bodySmall)
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
    var filter by rememberSaveable { mutableStateOf("全部") }
    val visible = remember(entries, filter) {
        entries.asReversed().filter {
            when (filter) {
                "RX" -> it.direction == BleTrafficDirection.RX
                "TX" -> it.direction == BleTrafficDirection.TX
                "系统 / 错误" -> it.direction == BleTrafficDirection.SYSTEM || it.direction == BleTrafficDirection.ERROR
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
                Text("收发日志", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(deviceName, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Panel {
                Section("${entries.size} 条记录", trailing = "清空") { traffic.clear() }
                Choices(listOf("全部", "RX", "TX", "系统 / 错误"), filter) { filter = it }
                Text("最新记录在前。TX 表示发送尝试，完成或失败会单独记录；日志仅保留在当前会话。", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("最多保留 500 条。通知需要先在特征页启用；多个订阅会分别记录，iOS 的读回值也可能进入通知流。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (visible.isEmpty()) item { Panel { Text(if (entries.isEmpty()) "还没有收发记录。连接外设后，可在特征页读取、写入或接收通知。" else "当前筛选下没有记录。", color = Muted) } }
        items(visible, key = { it.id }) { entry ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(entry.direction.name)
                    Text(entry.operation, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = if (entry.direction == BleTrafficDirection.ERROR) MaterialTheme.colorScheme.error else Accent)
                    if (entry.hex != null) Text("${entry.byteCount} B", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text(Instant.fromEpochMilliseconds(entry.timestampMillis).toString().replace("T", " ").replace("Z", " UTC"), color = Muted, style = MaterialTheme.typography.labelSmall)
                entry.characteristic?.let { characteristic ->
                    Text("服务", color = Muted, style = MaterialTheme.typography.labelSmall)
                    UuidText(characteristic.serviceUuid)
                    Text("特征", color = Muted, style = MaterialTheme.typography.labelSmall)
                    UuidText(characteristic.characteristicUuid)
                }
                entry.hex?.let { hex -> SelectionContainer { Text(hex.ifEmpty { "空数据 · 0 字节" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) } }
                if (entry.detail.isNotBlank()) Text(entry.detail, color = if (entry.direction == BleTrafficDirection.ERROR) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConnectionStatus(connected: Boolean, onConnect: () -> Unit) {
    Tag(if (connected) "外设已连接" else "外设未连接")
    if (!connected) {
        Text("请先返回设备页连接外设。", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("返回设备连接") }
    }
}

@Composable
private fun DebugMessages(error: String?, feedback: String?) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    feedback?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodySmall) }
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
        if (properties.isEmpty()) Tag("无可用读写属性")
    }
}

@Composable
private fun DescriptorList(descriptors: List<BleDescriptor>) {
    Text("描述符 · ${descriptors.size}", color = Muted, style = MaterialTheme.typography.labelMedium)
    descriptors.forEach { descriptor ->
        UuidText(descriptor.uuid)
        if (descriptor.uuid.equals("00002902-0000-1000-8000-00805f9b34fb", ignoreCase = true)) Text("CCCD · 订阅通知时由蓝牙库自动管理", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
    Text(if (descriptors.isEmpty()) "未发现描述符。" else "描述符仅展示发现结果，不提供原始读写。", color = Muted, style = MaterialTheme.typography.bodySmall)
}

private fun BleWriteMode.label() = if (this == BleWriteMode.WITH_RESPONSE) "有响应写入" else "无响应写入"

/** The current BLE abstraction addresses UUID paths, so it cannot safely address duplicate instances. */
private fun List<BleService>.uniqueCharacteristic(id: BleCharacteristicId): BleCharacteristic? {
    val service = filter { it.uuid.equals(id.serviceUuid, ignoreCase = true) }.singleOrNull() ?: return null
    return service.characteristics.filter { it.id.characteristicUuid.equals(id.characteristicUuid, ignoreCase = true) }.singleOrNull()
}
