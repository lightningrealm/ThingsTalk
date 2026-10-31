package com.lr.thingstalk.presentation.panel

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
            messages = listOf("请等待当前操作完成后再导入配置")
            return
        }
        try {
            val imported = viewModel.importDefinition(source)
            editor.importDefinitionWhenReady(imported, source)
            showFormats = imported.payloadFormats.isNotEmpty()
            messages = emptyList()
            notice = if (imported.transport == PanelTransport.DUAL && imported.bindings.any { it.publish != null || it.subscribe != null } && imported.bindings.none { it.read != null || it.write != null || it.notify != null })
                "已载入双连接草稿，目前只有 MQTT 绑定。可在组件操作的 BLE 标签补充特征映射，或导入包含两种通道的 v5 文件；保存后应用。"
            else "已载入配置草稿。请检查通信绑定与数据格式，点击保存后应用。"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { messages = listOf("导入失败：${failure.message}"); notice = null }
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
        Page("PANEL DRAFT", "编辑草稿", "草稿与已保存的设备配置分别保存。") {
            Text(editing.loadError ?: "正在读取本机草稿…")
            if (editing.loadError != null) {
                Primary("重新读取", !editing.processingExit) { editor.retryLoad() }
                TextButton(onClick = { confirmClear = true }, enabled = !editing.processingExit) { Text("丢弃无法读取的草稿") }
                editing.checkpointError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("丢弃本机草稿？") },
            text = { Text("仅删除此设备未应用的草稿。已保存面板保持不变。") },
            confirmButton = { TextButton(onClick = { editor.discard { confirmClear = false } }, enabled = !editing.processingExit) { Text("丢弃") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
        return
    }
    if (editing.recovery != null) {
        Page("PANEL DRAFT", "发现旧草稿", "设备已保存的配置在这份草稿之后发生了变化。") {
            Text("当前面板：${state.definition.title}")
            Text("草稿面板：${editing.recovery!!.definition.title}")
            Text("恢复仅载入编辑器，检查并保存后才会替换当前面板。", color = Muted)
            Primary("恢复旧草稿继续编辑", !editing.processingExit) { editor.restoreConflictingDraft() }
            OutlinedButton(onClick = { editor.discard() }, enabled = !editing.processingExit) { Text("丢弃旧草稿，使用当前配置") }
            editing.checkpointError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("协议与面板", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        messages = viewModel.updateDefinition(draft)
                        notice = null
                    }, enabled = !state.busy && !fileActions.busy && !editing.draft.jsonEdited && !editing.processingExit,
                        modifier = Modifier.semantics { contentDescription = "保存并应用面板" }) {
                        Text(if (state.saving) "正在保存…" else "保存")
                    }
                }
                val saveStatus = messages.firstOrNull() ?: state.error
                if (saveStatus != null) Text(saveStatus, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (editing.draft.jsonEdited) Text("请先导入或丢弃 JSON 文本更改后保存", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else state.feedback?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 800.dp).fillMaxSize()) {
    Page("DEVELOPER SETTINGS", "协议与面板", "定义收发格式、绑定通信操作，再将字段映射到展示组件。") {
        Panel {
            Tag(if (!state.ready) "离线编辑" else if (draft.transport == PanelTransport.BLE) "已连接 · 可选择特征" else if (draft.transport == PanelTransport.MQTT) "MQTT 已连接" else "双连接 · 部分或全部通道已连接")
            Text(when {
                editing.checkpointError != null -> "草稿尚未保留：${editing.checkpointError}"
                editing.checkpointing -> "正在保留草稿…"
                editing.dirty && editing.checkpointSaved -> "未应用更改 · 草稿已保留在本机"
                editing.dirty -> "未应用更改"
                else -> "与已保存面板一致"
            }, color = if (editing.checkpointError != null) MaterialTheme.colorScheme.error else Muted,
                style = MaterialTheme.typography.bodySmall)
            editing.notice?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            if (editing.checkpointError != null) TextButton(onClick = { editor.checkpointNow() }) { Text("重试保留草稿") }
            Text("这里编辑共用面板。导入会替换整份草稿，保存后应用；不会合并两份文件，也不会自动连接或写入设备。", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, label = { Text("面板标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = fileActions.importFile, enabled = !state.busy && !fileActions.busy, modifier = Modifier.fillMaxWidth()) { Text("导入配置文件") }
            OutlinedButton(onClick = {
                messages = validatePanel(draft)
                if (messages.isEmpty()) {
                    try { fileActions.exportFile(viewModel.exportDefinition(draft), "panel.thingstalk.json") }
                    catch (failure: Exception) { messages = listOf("导出失败：${failure.message}") }
                }
            }, enabled = !state.busy && !fileActions.busy, modifier = Modifier.fillMaxWidth()) { Text("导出草稿为配置文件") }
            Text(".thingstalk.json · 包含面板、数据格式和通信绑定；不包含具体外设标识或连接凭据。", color = Muted, style = MaterialTheme.typography.bodySmall)
            notice?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            messages.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (draft.transport == PanelTransport.DUAL) Panel {
            if (draft.schemaVersion >= 6) {
                Text("分通道配置 · BLE / MQTT 各自定义绑定")
                Text("默认打开的通道")
                Choices(listOf("BLE", "MQTT"), requireNotNull(draft.defaultChannel).name) {
                    draft = draft.copy(defaultChannel = PanelTransport.valueOf(it))
                }
            } else {
                Text("当前为 v5 固定路由。转换后，展示页标签会切换组件实际通信通道，原混合路由不再使用。", color = Muted)
                TextButton(onClick = { draft = draft.asChannelPanel(); notice = "已转为 v6 草稿，默认 BLE。检查两侧绑定后保存。" }) {
                    Text("转换为分通道配置 v6")
                }
            }
        }
        Section("00 / 数据格式 · ${draft.payloadFormats.size}", if (showFormats) "收起" else "展开") { showFormats = !showFormats }
        if (showFormats) key(state.definition, importedRevision) {
            PayloadFormatsEditor(draft.payloadFormats, draft.bindings) { formats ->
                draft = draft.copy(schemaVersion = maxOf(3, draft.schemaVersion), payloadFormats = formats)
                messages = emptyList(); notice = null
            }
        }
        if (draft.setupPanels.isNotEmpty()) Panel {
            Text("设备设置表单 · ${draft.setupPanels.size}")
            Text("字段、校验及特征由 JSON setupPanels 定义；运行时填写的值不会导出。", color = Muted)
            draft.setupPanels.forEach { form ->
                Text("${form.title} · ${form.sections.sumOf { it.fields.size }} 个字段")
            }
        }
        PayloadInspectorPanel(draft, inspector)
        Section("01 / 添加组件")
        Panel {
            Text("每个新组件都会创建独立能力，不带预设设备操作。", color = Muted, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetType.entries.forEach { type ->
                    OutlinedButton(onClick = {
                        var suffix = 1
                        while (draft.capabilities.any { it.id == "control-$suffix" } || draft.widgets.any { it.id == "widget-$suffix" }) suffix++
                        val capabilityType = when (type) {
                            WidgetType.VALUE, WidgetType.SLIDER, WidgetType.NUMBER_INPUT -> CapabilityType.NUMBER
                            WidgetType.SWITCH -> CapabilityType.BOOLEAN
                            WidgetType.BUTTON -> CapabilityType.ACTION
                        }
                        val capability = PanelCapability("control-$suffix", type.label(), capabilityType,
                            minimum = if (capabilityType == CapabilityType.NUMBER) 0.0 else null,
                            maximum = if (capabilityType == CapabilityType.NUMBER) 100.0 else null)
                        val widget = PanelWidget("widget-$suffix", type, type.label(), capability.id)
                        draft = draft.copy(capabilities = draft.capabilities + capability, widgets = draft.widgets + widget)
                        selectedId = widget.id
                    }, enabled = draft.widgets.size < 32 && draft.capabilities.size < 32) { Text("＋ ${type.label()}") }
                }
            }
        }
        if (draft.widgets.isEmpty()) Panel {
            Text("面板还是空白的", style = MaterialTheme.typography.titleMedium)
            Text("选择上方任一组件开始。也可以保存为空面板。", color = Muted)
        } else Panel {
            Text("点击名称编辑；上下移动可调整展示顺序。", color = Muted, style = MaterialTheme.typography.bodySmall)
            draft.widgets.forEachIndexed { index, widget ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { selectedId = widget.id }, modifier = Modifier.weight(1f)) { Text("${if (widget.id == selectedId) "● " else ""}${index + 1}. ${widget.label}") }
                    TextButton(onClick = { draft = draft.copy(widgets = draft.widgets.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0) { Text("上移") }
                    TextButton(onClick = { draft = draft.copy(widgets = draft.widgets.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index < draft.widgets.lastIndex) { Text("下移") }
                }
            }
        }
        val capability = selected?.let { widget -> draft.capabilities.firstOrNull { it.id == widget.capabilityId } }
        if (selected != null && capability != null) {
            Section("02 / 组件设置")
            Panel {
                Text("编辑 · ${selected.label}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(selected.label, { label ->
                    changeWidget(selected.copy(label = label))
                    draft = draft.copy(capabilities = draft.capabilities.map { if (it.id == capability.id) it.copy(label = label) else it })
                }, label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                val types = compatibleTypes(capability)
                Choices(types.map { it.label() }, selected.type.label()) { label -> changeWidget(selected.copy(type = types.first { it.label() == label })) }
                if (capability.type == CapabilityType.NUMBER) key(capability.id, state.definition, importedRevision) {
                    NumberCapabilityFields(capability) { changed -> draft = draft.copy(capabilities = draft.capabilities.map { if (it.id == changed.id) changed else it }) }
                }
                Text("能力 ${capability.id}", color = Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val remaining = draft.widgets.filterNot { it.id == selected.id }
                    val stillUsed = remaining.any { it.capabilityId == capability.id }
                    draft = draft.copy(widgets = remaining,
                        capabilities = if (stillUsed) draft.capabilities else draft.capabilities.filterNot { it.id == capability.id },
                        bindings = if (stillUsed) draft.bindings else draft.bindings.filterNot { it.capabilityId == capability.id })
                    selectedId = remaining.firstOrNull()?.id
                }) { Text("移除此组件", color = MaterialTheme.colorScheme.error) }
            }
            Section("03 / 组件操作")
            Panel {
                Text("${selected.label} · ${if (draft.bindings.none { it.capabilityId == capability.id }) "尚未绑定" else "已配置绑定"}", style = MaterialTheme.typography.titleMedium)
                Text(if (draft.schemaVersion >= 6) "BLE 与 MQTT 分别定义绑定，展示页选择通道后使用对应操作。两侧共用组件与数据格式。" else if (draft.transport == PanelTransport.DUAL) "分别配置 BLE 特征与 MQTT topic，再选择控制通道和状态来源。未选中的操作不会自动执行。" else if (draft.transport == PanelTransport.MQTT) "绑定状态订阅与命令发布 topic，使用预定义报文格式映射组件字段。" else if (state.ready) "选择此设备已发现的服务和特征。请按设备协议确认每个字节的含义。" else "可按设备协议手动填写 UUID 和数据格式；连接外设后也可从真实服务中选择。", color = Muted, style = MaterialTheme.typography.bodySmall)
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
        messages.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, color = Muted) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.feedback?.let { Text(it, color = Muted) }
        if (editing.draft.jsonEdited) {
            Text("JSON 文本有未导入的更改。请先校验并导入，或丢弃这些文本更改后再保存面板。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { showJson = true }) { Text("查看未导入 JSON") }
            TextButton(onClick = { confirmDiscardJson = true }) { Text("丢弃 JSON 文本更改") }
        }
        Section("配置迁移", if (showJson) "收起" else "展开") { showJson = !showJson }
        if (showJson) Panel {
            Text("JSON v${draft.schemaVersion} · 兼容导入 v1 / v2 / v3 / v4 / v5 / v6 / v7（需匹配设备类型）", style = MaterialTheme.typography.titleMedium)
            Text("可直接编辑或复制完整配置。命名数据格式使用 v3；旧版配置保留兼容，导入后仍需保存才会应用。", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                messages = validatePanel(draft)
                if (messages.isEmpty()) {
                    try {
                        editor.generatedJson(viewModel.exportDefinition(draft))
                        notice = "JSON 已生成，可长按文本复制。"
                    } catch (failure: Exception) {
                        messages = listOf("导出失败：${failure.message}")
                        notice = null
                    }
                }
            }, enabled = !editing.draft.jsonEdited, modifier = Modifier.fillMaxWidth()) { Text("生成草稿 JSON") }
            OutlinedTextField(json, { json = it }, label = { Text("粘贴 / 复制 JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp), minLines = 6)
            Primary("校验并导入为草稿", enabled = !state.busy && !fileActions.busy) { scope.launch { importDraft(json) } }
            messages.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            notice?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = { confirmClear = true }, enabled = draft.widgets.isNotEmpty() || draft.payloadFormats.isNotEmpty()) { Text("清空面板草稿") }
    }
        }
    }
    }
    if (confirmDiscardJson) AlertDialog(onDismissRequest = { confirmDiscardJson = false }, title = { Text("丢弃未导入的 JSON 更改？") },
        text = { Text("仅清空文本输入，已编辑的组件与格式保持不变。") },
        confirmButton = { TextButton(onClick = { editor.discardJsonEdits(); confirmDiscardJson = false }) { Text("丢弃文本更改") } },
        dismissButton = { TextButton(onClick = { confirmDiscardJson = false }) { Text("继续编辑") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空面板草稿？") },
        text = { Text("所有组件、操作绑定和数据格式将从草稿移除。点击保存后才会替换当前面板。") },
        confirmButton = { TextButton(onClick = { editor.replaceDraft(draft.copy(capabilities = emptyList(), bindings = emptyList(), widgets = emptyList(), payloadFormats = emptyList())); selectedId = null; messages = emptyList(); notice = "草稿已清空，请保存后应用。"; confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun NumberCapabilityFields(capability: PanelCapability, onChange: (PanelCapability) -> Unit) {
    var minimum by remember { mutableStateOf(panelNumberText(capability.minimum ?: 0.0)) }
    var maximum by remember { mutableStateOf(panelNumberText(capability.maximum ?: 255.0)) }
    var step by remember { mutableStateOf(panelNumberText(capability.step)) }
    OutlinedTextField(minimum, { minimum = it; onChange(capability.copy(minimum = it.toDoubleOrNull() ?: Double.NaN)) }, label = { Text("最小值") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(maximum, { maximum = it; onChange(capability.copy(maximum = it.toDoubleOrNull() ?: Double.NaN)) }, label = { Text("最大值") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(step, { step = it; onChange(capability.copy(step = it.toDoubleOrNull() ?: Double.NaN)) }, label = { Text("滑块步长") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(capability.unit, { onChange(capability.copy(unit = it)) }, label = { Text("单位（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("范围和步长可用小数或负数。滑块范围需由 1–10000 个等长步组成；更细或更大的范围请使用数值输入。实际可写精度由编解码格式和倍率决定。", color = Muted, style = MaterialTheme.typography.bodySmall)
}

private fun compatibleTypes(capability: PanelCapability): List<WidgetType> = when (capability.type) {
    CapabilityType.NUMBER -> listOf(WidgetType.VALUE, WidgetType.SLIDER, WidgetType.NUMBER_INPUT)
    CapabilityType.BOOLEAN -> listOf(WidgetType.VALUE, WidgetType.SWITCH)
    CapabilityType.ACTION -> listOf(WidgetType.BUTTON)
}
private fun WidgetType.label() = when (this) { WidgetType.VALUE -> "状态卡片"; WidgetType.SWITCH -> "开关"; WidgetType.SLIDER -> "滑块"; WidgetType.BUTTON -> "按钮"; WidgetType.NUMBER_INPUT -> "数值输入" }

@Composable
internal fun PanelPicker(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label：${options.firstOrNull { it.first == selected }?.second ?: selected}" }, enabled = options.isNotEmpty()) {
            Text((options.firstOrNull { it.first == selected }?.second ?: selected).ifEmpty { "暂无可用选项" })
        }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) } }
    }
}

/** Read the latest checkpoint synchronously when multiple editor changes occur in one callback. */
private class EditorValue<T>(private val read: () -> T, private val write: (T) -> Unit) {
    operator fun getValue(owner: Any?, property: kotlin.reflect.KProperty<*>): T = read()
    operator fun setValue(owner: Any?, property: kotlin.reflect.KProperty<*>, value: T) = write(value)
}
