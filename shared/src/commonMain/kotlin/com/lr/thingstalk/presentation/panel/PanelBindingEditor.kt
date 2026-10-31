package com.lr.thingstalk.presentation.panel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted

@Composable
internal fun PanelBindingEditor(
    capability: PanelCapability,
    binding: PanelBinding,
    services: List<BleService>,
    payloadFormats: List<PayloadFormat> = emptyList(),
    onChange: (PanelBinding) -> Unit,
) {
    val codecs = when (capability.type) {
        CapabilityType.BOOLEAN -> listOf(CodecType.BOOLEAN)
        CapabilityType.NUMBER -> numericPanelCodecs.toList()
        CapabilityType.ACTION -> listOf(CodecType.HEX)
    }
    if (capability.type != CapabilityType.ACTION) {
        OperationEditor("读取", OperationKind.READ, binding.read, codecs, services, payloadFormats, capability.type) { onChange(binding.copy(read = it)) }
        OperationEditor("通知接收", OperationKind.NOTIFY, binding.notify, codecs, services, payloadFormats, capability.type) { onChange(binding.copy(notify = it)) }
    }
    OperationEditor("写入", OperationKind.WRITE, binding.write, codecs, services, payloadFormats, capability.type) {
        onChange(binding.copy(write = it, actionValue = if (it?.payload != null || it == null) null else binding.actionValue))
    }
    if (capability.type == CapabilityType.ACTION && binding.write != null && binding.write.payload == null) Panel {
        val value = binding.actionValue.orEmpty()
        OutlinedTextField(
            value, { onChange(binding.copy(actionValue = it)) },
            label = { Text("固定命令 · 完整 HEX 报文") },
            supportingText = { Text("包含设备协议要求的全部字节，例如 AA 01 FF。支持 1–512 字节；不自动添加帧头、校验和或重试。") },
            isError = !validBindingHex(value, 1, 512),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private enum class OperationKind { READ, WRITE, NOTIFY }
private enum class EncodingMode { INLINE, PAYLOAD }

private fun BleCharacteristic.supports(kind: OperationKind) = when (kind) {
    OperationKind.READ -> BleProperty.READ in properties
    OperationKind.NOTIFY -> BleProperty.NOTIFY in properties || BleProperty.INDICATE in properties
    OperationKind.WRITE -> BleProperty.WRITE in properties || BleProperty.WRITE_WITHOUT_RESPONSE in properties
}

/** Duplicate UUIDs cannot be addressed safely by the current UUID-based transport. */
private fun List<BleService>.unambiguous(kind: OperationKind): List<BleService> =
    groupBy { it.uuid.lowercase() }.values.filter { it.size == 1 }.mapNotNull { matches ->
        val service = matches.single()
        val characteristics = service.characteristics.groupBy { it.id.characteristicUuid.lowercase() }
            .values.filter { it.size == 1 }.map { it.single() }.filter { it.supports(kind) }
        if (characteristics.isEmpty()) null else service.copy(characteristics = characteristics)
    }

private fun BleOperationBinding.select(characteristic: BleCharacteristic, kind: OperationKind): BleOperationBinding {
    val mode = if (kind != OperationKind.WRITE) PanelWriteMode.WITH_RESPONSE else when {
        writeMode == PanelWriteMode.WITH_RESPONSE && BleProperty.WRITE in characteristic.properties -> writeMode
        writeMode == PanelWriteMode.WITHOUT_RESPONSE && BleProperty.WRITE_WITHOUT_RESPONSE in characteristic.properties -> writeMode
        BleProperty.WRITE in characteristic.properties -> PanelWriteMode.WITH_RESPONSE
        else -> PanelWriteMode.WITHOUT_RESPONSE
    }
    return copy(serviceUuid = characteristic.id.serviceUuid, characteristicUuid = characteristic.id.characteristicUuid, writeMode = mode)
}

@Composable
private fun OperationEditor(
    title: String,
    kind: OperationKind,
    operation: BleOperationBinding?,
    codecs: List<CodecType>,
    services: List<BleService>,
    payloadFormats: List<PayloadFormat>,
    capabilityType: CapabilityType,
    onChange: (BleOperationBinding?) -> Unit,
) {
    val availableServices = services.unambiguous(kind)
    val compatibleFormats = compatiblePayloadFormats(payloadFormats, capabilityType)
    var pendingMode by remember { mutableStateOf<EncodingMode?>(null) }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (operation == null) "未启用" else "已启用 · 保存后应用", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(operation != null, onCheckedChange = { enabled ->
                if (!enabled) onChange(null)
                else {
                    val blank = BleOperationBinding("", "", codecs.first())
                    val first = availableServices.firstOrNull()?.characteristics?.firstOrNull()
                    onChange(if (first == null) blank else blank.select(first, kind))
                }
            }, modifier = Modifier.semantics { contentDescription = title })
        }
        if (operation != null) {
            Text("可手动填写设备协议中的完整 UUID；连接后也可从已发现的 GATT 服务中选择。", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (availableServices.isNotEmpty()) {
                PanelPicker("$title · 选择已发现服务", availableServices.map { it.uuid to "Service ${shortUuid(it.uuid)}" },
                    availableServices.firstOrNull { it.uuid.equals(operation.serviceUuid, ignoreCase = true) }?.uuid ?: "请选择服务") { uuid ->
                    val characteristic = availableServices.first { it.uuid == uuid }.characteristics.first()
                    onChange(operation.select(characteristic, kind))
                }
                val available = availableServices.firstOrNull { it.uuid.equals(operation.serviceUuid, ignoreCase = true) }?.characteristics.orEmpty()
                PanelPicker("$title · 选择已发现特征", available.map { it.id.characteristicUuid to "${shortUuid(it.id.characteristicUuid)} · ${propertyNames(it)}" },
                    available.firstOrNull { it.id.characteristicUuid.equals(operation.characteristicUuid, ignoreCase = true) }?.id?.characteristicUuid ?: "请选择特征") { uuid ->
                    onChange(operation.select(available.first { it.id.characteristicUuid == uuid }, kind))
                }
            } else if (services.isNotEmpty()) {
                Text("当前 GATT 中没有可唯一定位且支持此操作的特征；重复 UUID 已从选项中排除。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(operation.serviceUuid, { onChange(operation.copy(serviceUuid = it)) },
                label = { Text("$title · 服务 UUID") }, isError = !bindingUuid.matches(operation.serviceUuid),
                supportingText = { Text("完整 128 位 UUID，例如 0000180f-0000-1000-8000-00805f9b34fb") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(operation.characteristicUuid, { onChange(operation.copy(characteristicUuid = it)) },
                label = { Text("$title · 特征 UUID") }, isError = !bindingUuid.matches(operation.characteristicUuid),
                singleLine = true, modifier = Modifier.fillMaxWidth())

            val matchingServices = services.filter { it.uuid.equals(operation.serviceUuid, ignoreCase = true) }
            val matchingCharacteristics = matchingServices.singleOrNull()?.characteristics.orEmpty()
                .filter { it.id.characteristicUuid.equals(operation.characteristicUuid, ignoreCase = true) }
            val characteristic = matchingCharacteristics.singleOrNull()
            val problem = when {
                matchingServices.size > 1 || matchingCharacteristics.size > 1 -> "存在重复 UUID，无法唯一定位此特征，不能执行操作。请换用其他特征。"
                characteristic != null && !characteristic.supports(kind) -> "此特征不支持$title，请确认设备协议并重新选择。"
                else -> null
            }
            if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (characteristic == null) Text(
                if (services.isEmpty()) "尚未验证设备特征。连接设备后会检查属性和实际写入长度。" else "当前 GATT 中未找到此绑定。请确认已连接正确的设备。",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            else Text("已发现 · ${propertyNames(characteristic)}", color = Muted, style = MaterialTheme.typography.bodySmall)

            val reusable = operation.payload != null
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !reusable, onClick = { if (reusable) pendingMode = EncodingMode.INLINE }, label = { Text("单值编码") })
                FilterChip(selected = reusable, onClick = { if (!reusable) pendingMode = EncodingMode.PAYLOAD },
                    label = { Text("引用数据格式") }, enabled = compatibleFormats.isNotEmpty())
            }
            if (compatibleFormats.isEmpty()) Text("请先定义有效的数据格式，并添加与此组件类型匹配的字段，再启用格式引用。", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (reusable) {
                PayloadBindingFields(title, kind, capabilityType, operation, payloadFormats) { onChange(it) }
            } else {
                if (codecs.size > 1) PanelPicker("$title · 数据类型", codecs.map { it.name to it.bindingLabel() }, operation.codec.name) { selected ->
                    onChange(operation.withCodec(CodecType.valueOf(selected), kind == OperationKind.WRITE))
                } else Text("数据类型 · ${codecs.single().bindingLabel()}", color = Muted, style = MaterialTheme.typography.bodySmall)
                key(operation.codec) {
                    PanelBindingFormatFields(title, operation, kind == OperationKind.WRITE) { onChange(it) }
                }
            }
            if (kind == OperationKind.WRITE) {
                val modes = buildList {
                    if (characteristic == null || BleProperty.WRITE in characteristic.properties) add(PanelWriteMode.WITH_RESPONSE)
                    if (characteristic == null || BleProperty.WRITE_WITHOUT_RESPONSE in characteristic.properties) add(PanelWriteMode.WITHOUT_RESPONSE)
                }
                if (modes.isNotEmpty()) Choices(modes.map { it.label() }, operation.writeMode.label()) { label ->
                    onChange(operation.copy(writeMode = modes.first { it.label() == label }))
                }
                Text("有响应写入表示 GATT 确认，不等于业务成功；业务结果需要设备协议中的回复。无响应写入仅表示已交给蓝牙栈。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (pendingMode != null && operation != null) AlertDialog(
        onDismissRequest = { pendingMode = null },
        title = { Text(if (pendingMode == EncodingMode.PAYLOAD) "改用预定义报文格式？" else "恢复为单值编码？") },
        text = { Text(if (pendingMode == EncodingMode.PAYLOAD)
            "切换会清除当前单值编码的类型、字节序、偏移、倍率、帧前后缀和固定 HEX 命令。BLE 地址与写入模式保留。切换后需重新选择报文格式与字段，检查后再保存。"
        else "切换会移除报文格式与字段引用，并将单值编码参数恢复为默认值。请重新设置数据类型和编码参数；动作按钮需重新填写完整 HEX 命令。BLE 地址与写入模式保留。") },
        confirmButton = { TextButton(onClick = {
            when (pendingMode) {
                EncodingMode.PAYLOAD -> if (compatibleFormats.isNotEmpty()) onChange(operation.withPayload(PayloadReference("")))
                EncodingMode.INLINE -> onChange(BleOperationBinding(operation.serviceUuid, operation.characteristicUuid, codecs.first(), operation.writeMode))
                null -> Unit
            }
            pendingMode = null
        }, enabled = pendingMode != EncodingMode.PAYLOAD || compatibleFormats.isNotEmpty()) { Text("清除并切换") } },
        dismissButton = { TextButton(onClick = { pendingMode = null }) { Text("保留当前配置") } },
    )
}

@Composable
private fun PayloadBindingFields(
    title: String,
    kind: OperationKind,
    capabilityType: CapabilityType,
    operation: BleOperationBinding,
    formats: List<PayloadFormat>,
    onChange: (BleOperationBinding) -> Unit,
) {
    val reference = operation.payload ?: return
    val available = compatiblePayloadFormats(formats, capabilityType)
    val format = formats.singleOrNull { it.id == reference.formatId }
    PanelPicker("$title · 报文格式", available.map { it.id to "${it.label} · ${it.encoding.name}" }, reference.formatId) { id ->
        val selected = available.first { it.id == id }
        onChange(operation.withPayload(PayloadReference(id,
            if (capabilityType == CapabilityType.ACTION) null else selected.fields.firstOrNull { it.compatibleWith(capabilityType) }?.id)))
    }
    if (format == null) Text("请先在数据格式中定义报文，再选择格式。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    else if (capabilityType == CapabilityType.ACTION) {
        Text("点击按钮时，按照各字段的默认值序列化完整报文。所有字段都需要设置默认值。", color = Muted, style = MaterialTheme.typography.bodySmall)
        val missing = format.fields.filter { it.defaultValue == null }
        if (missing.isNotEmpty()) Text("缺少默认值：${missing.joinToString { it.id }}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else {
        val fields = format.fields.filter { it.compatibleWith(capabilityType) }
        PanelPicker("$title · 组件对应字段", fields.map { it.id to "${it.id} · ${it.codec.name}" }, reference.fieldId.orEmpty()) {
            onChange(operation.withPayload(reference.copy(fieldId = it)))
        }
        if (fields.none { it.id == reference.fieldId }) Text("请选择与组件类型匹配的字段。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (kind == OperationKind.WRITE) {
            Text("将组件输入填入此字段，其余字段使用格式中配置的默认值，随后发送完整报文。", color = Muted, style = MaterialTheme.typography.bodySmall)
            val missing = format.fields.filter { it.id != reference.fieldId && it.defaultValue == null }
            if (missing.isNotEmpty()) Text("其他字段缺少默认值：${missing.joinToString { it.id }}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else Text("收到完整报文后按格式校验、反序列化，再用所选字段更新组件。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun PayloadField.compatibleWith(type: CapabilityType): Boolean = when (type) {
    CapabilityType.BOOLEAN -> codec == CodecType.BOOLEAN
    CapabilityType.NUMBER -> codec in numericPanelCodecs
    CapabilityType.ACTION -> false
}

private fun compatiblePayloadFormats(formats: List<PayloadFormat>, type: CapabilityType): List<PayloadFormat> =
    formats.filter { format ->
        formats.count { it.id == format.id } == 1 && validatePayloadFormats(listOf(format)).isEmpty() &&
            (type == CapabilityType.ACTION || format.fields.any { it.compatibleWith(type) })
    }

/** Selecting a shared format clears all inline encoding options; only the BLE address/mode survive. */
private fun BleOperationBinding.withPayload(reference: PayloadReference): BleOperationBinding =
    BleOperationBinding(serviceUuid = serviceUuid, characteristicUuid = characteristicUuid, writeMode = writeMode, payload = reference)

private fun BleOperationBinding.withCodec(selected: CodecType, isWrite: Boolean): BleOperationBinding = when (selected) {
    CodecType.HEX -> BleOperationBinding(serviceUuid, characteristicUuid, selected, writeMode)
    CodecType.BOOLEAN -> copy(codec = selected, byteOrder = PanelByteOrder.LITTLE_ENDIAN,
        writeMode = if (isWrite) writeMode else PanelWriteMode.WITH_RESPONSE,
        readOffset = if (isWrite) 0 else readOffset, scale = 1.0, bias = 0.0,
        writePrefixHex = if (isWrite) writePrefixHex else "", writeSuffixHex = if (isWrite) writeSuffixHex else "",
        falseHex = if (codec == CodecType.BOOLEAN) falseHex else "00", trueHex = if (codec == CodecType.BOOLEAN) trueHex else "01")
    else -> copy(codec = selected,
        writeMode = if (isWrite) writeMode else PanelWriteMode.WITH_RESPONSE,
        byteOrder = if (selected == CodecType.UINT8 || selected == CodecType.INT8) PanelByteOrder.LITTLE_ENDIAN else byteOrder,
        readOffset = if (isWrite) 0 else readOffset,
        scale = if (codec in numericPanelCodecs) scale else 1.0, bias = if (codec in numericPanelCodecs) bias else 0.0,
        writePrefixHex = if (isWrite) writePrefixHex else "", writeSuffixHex = if (isWrite) writeSuffixHex else "",
        falseHex = "00", trueHex = "01")
}

private val bindingUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private fun PanelWriteMode.label() = if (this == PanelWriteMode.WITH_RESPONSE) "有响应写入" else "无响应写入"
private fun shortUuid(uuid: String) = if (uuid.startsWith("0000") && uuid.length == 36) uuid.substring(4, 8).uppercase() else uuid
private fun propertyNames(characteristic: BleCharacteristic) = buildList {
    if (BleProperty.READ in characteristic.properties) add("读")
    if (BleProperty.WRITE in characteristic.properties || BleProperty.WRITE_WITHOUT_RESPONSE in characteristic.properties) add("写")
    if (BleProperty.NOTIFY in characteristic.properties || BleProperty.INDICATE in characteristic.properties) add("通知")
}.joinToString(" / ")

private fun CodecType.bindingLabel() = when (this) {
    CodecType.UINT8 -> "UInt8 · 8 位无符号整数"
    CodecType.INT8 -> "Int8 · 8 位有符号整数"
    CodecType.UINT16 -> "UInt16 · 16 位无符号整数"
    CodecType.INT16 -> "Int16 · 16 位有符号整数"
    CodecType.UINT32 -> "UInt32 · 32 位无符号整数"
    CodecType.INT32 -> "Int32 · 32 位有符号整数"
    CodecType.FLOAT32 -> "Float32 · 32 位浮点数"
    CodecType.BOOLEAN -> "Boolean · 自定义真假字节"
    CodecType.HEX -> "HEX · 固定完整报文"
}
