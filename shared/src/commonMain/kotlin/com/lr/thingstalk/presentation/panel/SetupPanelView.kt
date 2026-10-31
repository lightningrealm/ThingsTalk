package com.lr.thingstalk.presentation.panel

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
            Text("请连接 BLE；设备需提供此面板配置的服务和特征。", color = Muted)
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
        savedPasswords = buildSet { if (status.wifiPasswordSaved) add(2); if (status.mqttPasswordSaved) add(5) }
        unsavedSections = definition.sections.filter { if (it.id == 1) !status.wifiSaved else !status.mqttSaved }.map { it.id }.toSet()
        if (status.unlocked) { inputs = definition.initialInputs(session.readValues(), unsavedSections); loaded = true }
    }
    Text(when {
        busy && !loaded -> "正在验证设备访问权限。如已完成配对，请稍候，客户端会重新读取验证（最多 60 秒）。"
        busy -> "正在与设备通信…"
        unlocked == false -> "设备设置窗口未开启，请按设备说明开启后重试读取。"
        !loaded -> "请先准备设备设置。将读取配置特征；如系统要求配对，请先完成配对。"
        else -> "设备配置读取成功，可以编辑"
    }, color = Muted)
    TextButton(enabled = !busy, onClick = {
        preparationJob = scope.launch {
            busy = true; loaded = false; unlocked = null; message = null
            try { reload() }
            catch (timeout: TimeoutCancellationException) { message = "设备设置准备超时，请完成系统配对、确认设置窗口后重试。" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SetupPanelFailure) { message = failure.message }
            catch (failure: IllegalArgumentException) { message = failure.message ?: "设备设置配置无效" }
            catch (_: Exception) { message = "读取失败，请检查连接和设备状态。" }
            finally { busy = false }
        }
    }) { Text(if (loaded) "重新准备并读取配置（不读取密码）" else "准备设备设置（可能需要配对）") }
    if (busy && !loaded) TextButton(onClick = {
        preparationJob?.cancel()
        message = "已取消准备，尚未发送配置命令。"
    }) { Text("取消准备") }
    Row { definition.sections.forEach { section ->
        TextButton(enabled = !busy, onClick = { selected = section.id }) { Text((if (selected == section.id) "● " else "") + section.title) }
    } }
    val section = definition.sections.single { it.id == selected }
    if (loaded && section.id in unsavedSections) Text("设备尚未保存${section.title}配置，可以直接填写。", color = Muted)
    section.fields.forEach { field ->
        OutlinedTextField(inputs[field.id].orEmpty(), { inputs = inputs + (field.id to it) },
            label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy && loaded,
            visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None)
        field.defaultValue?.let { expected ->
            Text("面板建议${field.label}：$expected；请与 MQTT topic 中的设备标识保持一致。", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (loaded && inputs[field.id] != expected) {
                Text("当前值与面板建议值不同，可能导致设备与 App 使用不同主题。", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { inputs = inputs + (field.id to expected) }) { Text("使用面板建议值") }
            }
        }
        if (field.secret) {
            Text("留空保留设备原值；只有明确勾选清除才提交空值。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(field.id in clearSecrets, { clearSecrets = if (it) clearSecrets + field.id else clearSecrets - field.id }, enabled = !busy && loaded)
                Text("清除${field.label}")
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
                savedPasswords = buildSet { if (result.wifiPasswordSaved) add(2); if (result.mqttPasswordSaved) add(5) }
                unsavedSections = unsavedSections - section.id; message = "设备已确认保存；不代表网络已连接。" }
            catch (timeout: TimeoutCancellationException) { message = "保存结果未知，请读回核实；不会自动重发。" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SetupPanelFailure) {
                message = buildString {
                    append(failure.message)
                    failure.opcode?.let { append("\n操作=0x${it.toString(16)} 请求=${failure.request}") }
                    failure.reply?.let { append("\n回执：请求=${it.request} 操作=0x${it.opcode.toString(16)} 结果=${it.result} 字段=${it.field} 偏移=${it.offset}") }
                }
            }
            catch (failure: IllegalArgumentException) { message = failure.message ?: "字段无效" }
            catch (_: Exception) { message = "设备未确认保存，请检查连接、设置窗口和字段。不会自动补发。" }
            finally {
                val secrets = definition.sections.flatMap { it.fields }.filter { it.secret }.map { it.id }.toSet()
                inputs = inputs - secrets; clearSecrets = emptySet(); busy = false
            }
        }
    }
    val passwordNotice = section.passwordConfirmation(inputs, clearSecrets, savedPasswords)
    Primary(if (busy) "正在通信…" else "保存${section.title}到设备", !busy && loaded && unlocked == true) {
        if (passwordNotice != null) confirmSave = true else submitSection()
    }
    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        title = { Text("确认密码处理") },
        text = { Text(passwordNotice.orEmpty() + "\n确认后才会发送配置。") },
        confirmButton = { TextButton(enabled = !busy && loaded && unlocked == true, onClick = {
            confirmSave = false
            submitSection()
        }) { Text("继续保存") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("返回填写") } },
    )
    message?.let { Text(it, color = Muted) }
}
