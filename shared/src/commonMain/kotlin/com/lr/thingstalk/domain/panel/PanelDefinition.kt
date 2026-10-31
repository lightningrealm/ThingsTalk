package com.lr.thingstalk.domain.panel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** Versioned device panel with portable, declarative BLE and payload bindings. */
@Serializable
data class PanelDefinition(
    val schemaVersion: Int = 1,
    val title: String,
    val capabilities: List<PanelCapability> = emptyList(),
    val bindings: List<PanelBinding> = emptyList(),
    val widgets: List<PanelWidget> = emptyList(),
    val payloadFormats: List<PayloadFormat> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val transport: PanelTransport = PanelTransport.BLE,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val defaultChannel: PanelTransport? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val setupPanels: List<SetupPanel> = emptyList(),
)

@Serializable enum class PanelTransport { BLE, MQTT, DUAL }

@Serializable
data class PanelCapability(
    val id: String,
    val label: String,
    val type: CapabilityType,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val unit: String = "",
    val step: Double = 1.0,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxBytes: Int = 512,
)
@Serializable enum class CapabilityType { BOOLEAN, NUMBER, ACTION, TEXT }

@Serializable
data class PanelWidget(val id: String, val type: WidgetType, val label: String, val capabilityId: String)
@Serializable enum class WidgetType { VALUE, SWITCH, SLIDER, BUTTON, NUMBER_INPUT, TEXT_INPUT }

@Serializable
data class PanelBinding(
    val capabilityId: String,
    val read: BleOperationBinding? = null,
    val write: BleOperationBinding? = null,
    val notify: BleOperationBinding? = null,
    val actionValue: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val publish: MqttOperationBinding? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val subscribe: MqttOperationBinding? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val controlTransport: PanelTransport? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stateTransport: PanelTransport? = null,
)
@Serializable
data class BleOperationBinding(
    val serviceUuid: String,
    val characteristicUuid: String,
    val codec: CodecType = CodecType.UINT8,
    val writeMode: PanelWriteMode = PanelWriteMode.WITH_RESPONSE,
    val byteOrder: PanelByteOrder = PanelByteOrder.LITTLE_ENDIAN,
    val readOffset: Int = 0,
    val scale: Double = 1.0,
    val bias: Double = 0.0,
    val writePrefixHex: String = "",
    val writeSuffixHex: String = "",
    val falseHex: String = "00",
    val trueHex: String = "01",
    val payload: PayloadReference? = null,
)
@Serializable enum class PanelWriteMode { WITH_RESPONSE, WITHOUT_RESPONSE }
@Serializable enum class CodecType { UINT8, BOOLEAN, HEX, INT8, UINT16, INT16, UINT32, INT32, FLOAT32, UTF8 }
@Serializable enum class PanelByteOrder { LITTLE_ENDIAN, BIG_ENDIAN }

interface PanelOperations {
    suspend fun read(definition: PanelDefinition, capabilityId: String): String
    /** For ACTION capabilities the configured constant is sent; caller-supplied value is ignored. */
    suspend fun write(definition: PanelDefinition, capabilityId: String, value: String)
    /** Values-only view. Use observeEvents when the caller needs readiness and rejected packets. */
    fun observe(definition: PanelDefinition, capabilityId: String): Flow<String>
    /**
     * Transports with setup acknowledgement should override this to report Ready. The fallback
     * does not claim readiness until a value arrives. Transport failures terminate the flow.
     */
    fun observeEvents(definition: PanelDefinition, capabilityId: String): Flow<PanelObservation> =
        observe(definition, capabilityId).map { PanelObservation.Value(it) }
}

private val identifier = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val hexBytes = Regex("(?:[0-9a-fA-F]{2})+")

/** Static checks; live GATT permissions and negotiated write limits are checked by the executor. */
fun validatePanel(definition: PanelDefinition): List<String> = buildList {
    fun issue(condition: Boolean, message: String) { if (condition) add(message) }
    val legacy = definition.schemaVersion == 1
    issue(definition.schemaVersion !in 1..7, "仅支持 schemaVersion 1–7")
    issue(definition.schemaVersion < 3 && definition.payloadFormats.isNotEmpty(), "命名报文格式需要 schemaVersion 3")
    issue(definition.transport == PanelTransport.MQTT && definition.schemaVersion !in listOf(4, 7), "MQTT 面板需要 schemaVersion 4 或 7")
    issue(definition.transport == PanelTransport.DUAL && definition.schemaVersion !in 5..7, "双连接面板需要 schemaVersion 5–7")
    issue((definition.schemaVersion == 6 || definition.schemaVersion == 7 && definition.transport == PanelTransport.DUAL) && (definition.transport != PanelTransport.DUAL || definition.defaultChannel !in listOf(PanelTransport.BLE, PanelTransport.MQTT)), "v6/v7 分通道面板需要 DUAL 与 BLE/MQTT defaultChannel")
    issue((definition.schemaVersion < 6 || definition.transport != PanelTransport.DUAL) && definition.defaultChannel != null, "defaultChannel 仅用于 v6/v7 分通道面板")
    issue(definition.setupPanels.isNotEmpty() && (definition.schemaVersion != 7 || definition.transport == PanelTransport.MQTT), "设置面板需要支持 BLE 的 schemaVersion 7 配置")
    addAll(validateSetupPanels(definition.setupPanels))
    addAll(validatePayloadFormats(definition.payloadFormats))
    issue(definition.title.isBlank() || definition.title.length > 80, "面板标题需为 1–80 个字符")
    issue(definition.capabilities.size > 32, "能力数量不能超过 32")
    issue(definition.bindings.size > 32, "绑定数量不能超过 32")
    issue(definition.widgets.size > 32, "控件数量不能超过 32")
    issue(definition.capabilities.map { it.id }.distinct().size != definition.capabilities.size, "能力 ID 不能重复")
    issue(definition.widgets.map { it.id }.distinct().size != definition.widgets.size, "控件 ID 不能重复")
    issue(definition.bindings.map { it.capabilityId }.distinct().size != definition.bindings.size, "每项能力只能有一个绑定")
    definition.bindings.mapNotNull { it.subscribe }.groupBy { it.topic }.forEach { (_, operations) ->
        issue(operations.map { it.qos }.distinct().size > 1, "同一状态 topic 的订阅 QoS 必须一致")
    }
    val capabilities = definition.capabilities.associateBy { it.id }
    val bindings = definition.bindings.associateBy { it.capabilityId }
    definition.capabilities.forEach { capability ->
        issue(!identifier.matches(capability.id), "能力 ID 格式无效：${capability.id}")
        issue(capability.label.isBlank() || capability.label.length > 80, "能力名称需为 1–80 个字符")
        issue(capability.unit.length > 16, "单位不能超过 16 个字符")
        issue(capability.minimum?.isFinite() == false || capability.maximum?.isFinite() == false, "数值范围必须有限")
        issue(capability.type != CapabilityType.NUMBER && (capability.minimum != null || capability.maximum != null), "只有数值能力能定义范围")
        issue((legacy || capability.type != CapabilityType.NUMBER) && capability.step != 1.0, "此能力不支持自定义步长")
        issue(capability.type == CapabilityType.TEXT && (definition.schemaVersion < 3 || capability.maxBytes !in 1..512), "文本能力需要 schemaVersion 3 及以上，maxBytes 为 1–512")
        issue(capability.type != CapabilityType.TEXT && capability.maxBytes != 512, "只有文本能力能设置 maxBytes")
        if (capability.type == CapabilityType.NUMBER) {
            val min = capability.minimum ?: 0.0
            val max = capability.maximum ?: 255.0
            issue(min > max, "数值最大值不能小于最小值")
            if (legacy) {
                issue(min < 0 || max > 255 || min > max || min % 1.0 != 0.0 || max % 1.0 != 0.0, "v1 数值范围必须是 0–255 内的整数")
            } else {
                issue(!capability.step.isFinite() || capability.step <= 0.0, "数值步长必须是有限的正数")
            }
        }
    }
    definition.bindings.forEach bindingLoop@ { binding ->
        if (definition.transport == PanelTransport.DUAL) {
            addAll(validateDualBinding(definition, binding))
            return@bindingLoop
        }
        issue(binding.controlTransport != null || binding.stateTransport != null, "单通道面板不能指定双连接路由")
        if (definition.transport == PanelTransport.MQTT) {
            addAll(validateMqttBinding(definition, binding))
            return@bindingLoop
        }
        issue(binding.publish != null || binding.subscribe != null, "BLE 面板不能包含 MQTT 绑定")
        val capability = capabilities[binding.capabilityId]
        issue(capability == null, "绑定引用不存在的能力：${binding.capabilityId}")
        issue(binding.read == null && binding.write == null && binding.notify == null, "绑定至少需要一个操作")
        listOf("read" to binding.read, "write" to binding.write, "notify" to binding.notify).forEach operationLoop@ { (kind, optional) ->
            val operation = optional ?: return@operationLoop
            issue(!uuid.matches(operation.serviceUuid) || !uuid.matches(operation.characteristicUuid), "BLE UUID 必须为完整的 128 位格式")
            if (operation.payload != null) {
                addAll(validatePayloadReference(definition, capability, operation, kind))
                return@operationLoop
            }
            val compatible = when (capability?.type) {
                CapabilityType.BOOLEAN -> operation.codec == CodecType.BOOLEAN
                CapabilityType.NUMBER -> operation.codec in if (legacy) setOf(CodecType.UINT8) else numericPanelCodecs
                CapabilityType.ACTION -> operation.codec == CodecType.HEX
                CapabilityType.TEXT -> operation.codec == CodecType.UTF8
                null -> true
            }
            issue(!compatible, "能力与编解码类型不匹配：${binding.capabilityId}")
            if (legacy) {
                issue(!operation.hasDefaultV2Parameters(), "v1 不支持字节序、偏移、换算、自定义布尔值或帧前后缀")
            } else {
                issue(operation.readOffset < 0, "读取偏移不能为负数")
                issue(kind == "write" && operation.readOffset != 0, "写入操作不能定义读取偏移")
                issue(kind != "write" && operation.writeMode != PanelWriteMode.WITH_RESPONSE, "读取或通知不能定义写入模式")
                issue(kind != "write" && (operation.writePrefixHex.isNotEmpty() || operation.writeSuffixHex.isNotEmpty()), "读取或通知不能定义写入帧前后缀")
                issue(!operation.scale.isFinite() || operation.scale == 0.0 || !operation.bias.isFinite(), "换算比例必须有限且非零，偏移量必须有限")
                when (operation.codec) {
                    CodecType.BOOLEAN -> {
                        issue(operation.scale != 1.0 || operation.bias != 0.0, "布尔值不能定义数值换算")
                        issue(operation.byteOrder != PanelByteOrder.LITTLE_ENDIAN, "布尔值按完整字节匹配，不支持字节序")
                        val falseValue = operation.falseHex.compactPanelHex()
                        val trueValue = operation.trueHex.compactPanelHex()
                        issue(!isPanelHex(falseValue, 1, 32) || !isPanelHex(trueValue, 1, 32), "布尔值需要 1–32 字节的完整 HEX 数据")
                        issue(falseValue.length != trueValue.length || falseValue.equals(trueValue, ignoreCase = true), "布尔真值和假值必须等长且不同")
                    }
                    CodecType.UTF8 -> issue(kind != "write" || operation.byteOrder != PanelByteOrder.LITTLE_ENDIAN || operation.scale != 1.0 || operation.bias != 0.0 || operation.falseHex != "00" || operation.trueHex != "01", "文本仅支持 UTF-8 写入，不使用数值换算或字节序")
                    CodecType.HEX -> issue(!operation.hasDefaultV2Parameters(), "固定 HEX 动作不能定义额外编解码参数")
                    else -> issue(operation.falseHex != "00" || operation.trueHex != "01", "数值操作不能定义布尔编码")
                }
                if (kind == "write" && operation.codec != CodecType.HEX) {
                    val prefix = operation.writePrefixHex.compactPanelHex()
                    val suffix = operation.writeSuffixHex.compactPanelHex()
                    issue(!isPanelHex(prefix, 0, 512) || !isPanelHex(suffix, 0, 512), "写入帧前后缀需要完整 HEX 字节")
                    val width = if (operation.codec == CodecType.BOOLEAN) operation.falseHex.compactPanelHex().length / 2 else operation.codec.byteWidth()
                    issue(prefix.length / 2 + width + suffix.length / 2 > 512, "完整写入帧不能超过 512 字节")
                }
            }
        }
        issue(capability?.type == CapabilityType.TEXT && (binding.write == null || binding.read != null || binding.notify != null), "文本输入仅支持写入，不支持读取或通知")
        if (capability?.type == CapabilityType.ACTION) {
            issue(binding.write == null || binding.read != null || binding.notify != null, "动作仅支持写入，不支持读取或通知")
            if (binding.write?.payload != null) {
                issue(binding.actionValue != null, "命名报文动作使用字段默认值，不能同时设置固定 HEX 数据")
            } else issue(!isPanelHex(binding.actionValue.orEmpty().compactPanelHex(), 1, 512), "动作需要 1–512 字节的完整 HEX 数据")
        } else {
            issue(binding.actionValue != null, "仅动作能定义固定写入数据")
        }
    }
    definition.widgets.forEach { widget ->
        issue(!identifier.matches(widget.id), "控件 ID 格式无效：${widget.id}")
        issue(widget.label.isBlank() || widget.label.length > 80, "控件名称需为 1–80 个字符")
        val capability = capabilities[widget.capabilityId]
        val binding = bindings[widget.capabilityId]
        issue(capability == null, "控件引用不存在的能力：${widget.capabilityId}")
        val expected = when (widget.type) {
            WidgetType.VALUE -> null
            WidgetType.SWITCH -> CapabilityType.BOOLEAN
            WidgetType.SLIDER, WidgetType.NUMBER_INPUT -> CapabilityType.NUMBER
            WidgetType.BUTTON -> CapabilityType.ACTION
            WidgetType.TEXT_INPUT -> CapabilityType.TEXT
        }
        issue(legacy && widget.type == WidgetType.NUMBER_INPUT, "数值输入组件需要 schemaVersion 2")
        issue(expected != null && capability?.type != expected, "控件与能力类型不匹配：${widget.id}")
        issue(widget.type == WidgetType.VALUE && (capability?.type in listOf(CapabilityType.ACTION, CapabilityType.TEXT) || (binding != null && binding.read == null && binding.notify == null && binding.subscribe == null)), "数值卡片需要可读取或通知的属性")
        issue(widget.type != WidgetType.VALUE && binding != null && !binding.canWrite, "交互控件需要写入绑定：${widget.id}")
        if (widget.type == WidgetType.SLIDER && capability != null) {
            val min = capability.minimum ?: 0.0
            val max = capability.maximum ?: 255.0
            issue(min >= max, "滑块最大值必须大于最小值")
            if (!legacy) {
                val span = max - min
                val steps = span / capability.step
                val rounded = kotlin.math.round(steps)
                issue(!span.isFinite() || !steps.isFinite() || rounded !in 1.0..10000.0 ||
                    kotlin.math.abs(steps - rounded) > kotlin.math.max(1.0, kotlin.math.abs(steps)) * 1e-10 ||
                    min + capability.step == min || max - capability.step == max,
                    "滑块范围必须是步长的整数倍，且含 1–10000 步，请调整或改用数值输入")
            }
        }
    }
}

val numericPanelCodecs: Set<CodecType> = setOf(CodecType.UINT8, CodecType.INT8, CodecType.UINT16, CodecType.INT16, CodecType.UINT32, CodecType.INT32, CodecType.FLOAT32)

internal fun CodecType.byteWidth(): Int = when (this) {
    CodecType.UINT8, CodecType.INT8, CodecType.BOOLEAN -> 1
    CodecType.UINT16, CodecType.INT16 -> 2
    CodecType.UINT32, CodecType.INT32, CodecType.FLOAT32 -> 4
    CodecType.HEX, CodecType.UTF8 -> 0
}

internal fun String.compactPanelHex(): String = filterNot { it.isWhitespace() }
internal fun isPanelHex(compact: String, minBytes: Int, maxBytes: Int): Boolean =
    compact.length in (minBytes * 2)..(maxBytes * 2) && (compact.isEmpty() || hexBytes.matches(compact))

private fun BleOperationBinding.hasDefaultV2Parameters(): Boolean =
    byteOrder == PanelByteOrder.LITTLE_ENDIAN && readOffset == 0 && scale == 1.0 && bias == 0.0 &&
        writePrefixHex.isEmpty() && writeSuffixHex.isEmpty() && falseHex == "00" && trueHex == "01"
