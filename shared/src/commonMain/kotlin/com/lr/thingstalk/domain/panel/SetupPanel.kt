package com.lr.thingstalk.domain.panel

import com.lr.thingstalk.domain.ble.*
import kotlinx.serialization.Serializable

@Serializable enum class SetupProtocol { BLE_CHUNKED_SETTINGS_V1 }
@Serializable enum class SetupFieldRule { TEXT, WIFI_PSK, MQTT_URI, IDENTIFIER }
@Serializable data class SetupPanel(
    val id: String, val title: String, val instructions: String,
    val serviceUuid: String, val commandUuid: String, val statusUuid: String,
    val sections: List<SetupSection>,
    val protocol: SetupProtocol = SetupProtocol.BLE_CHUNKED_SETTINGS_V1,
) {
    val command get() = BleCharacteristicId(serviceUuid, commandUuid)
    val status get() = BleCharacteristicId(serviceUuid, statusUuid)
    fun readId(field: SetupField) = field.readUuid?.let { BleCharacteristicId(serviceUuid, it) }
    fun sensitiveCharacteristics() = listOf(command) + sections.flatMap { it.fields }.mapNotNull(::readId)
    fun supported(services: List<BleService>): Boolean {
        val required = listOf(command to BleProperty.WRITE, status to BleProperty.READ) +
            sections.flatMap { it.fields }.mapNotNull(::readId).map { it to BleProperty.READ }
        val all = services.flatMap { it.characteristics }
        return required.all { (id, property) -> all.filter { it.id.serviceUuid.equals(id.serviceUuid, true) &&
            it.id.characteristicUuid.equals(id.characteristicUuid, true) }.singleOrNull()?.properties?.contains(property) == true }
    }
}
@Serializable data class SetupSection(val id: Int, val title: String, val fields: List<SetupField>)
@Serializable data class SetupField(
    val id: Int, val label: String, val minBytes: Int = 0, val maxBytes: Int,
    val secret: Boolean = false, val readUuid: String? = null, val rule: SetupFieldRule = SetupFieldRule.TEXT,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val defaultValue: String? = null,
)

fun validateSetupPanels(panels: List<SetupPanel>): List<String> = buildList {
    fun issue(bad: Boolean, message: String) { if (bad) add(message) }
    val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    issue(panels.size > 4 || panels.map { it.id }.distinct().size != panels.size, "设置面板最多 4 个，ID 不能重复")
    panels.forEach { panel ->
        issue(!Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}").matches(panel.id), "设置面板 ID 无效")
        issue(panel.title.isBlank() || panel.title.length > 80 || panel.instructions.length > 500, "设置面板标题或说明长度无效")
        issue(!uuid.matches(panel.serviceUuid) || !uuid.matches(panel.commandUuid) || !uuid.matches(panel.statusUuid), "设置面板需要完整 UUID")
        issue(panel.commandUuid.equals(panel.statusUuid, true), "命令和状态特征不能相同")
        issue(panel.sections.isEmpty() || panel.sections.size > 2 || panel.sections.map { it.id }.distinct().size != panel.sections.size, "设置分组应为 1–2 个且 ID 唯一")
        panel.sections.forEach { section ->
            issue(section.id !in 1..2 || section.title.isBlank() || section.title.length > 80, "设置分组无效")
            val allowed = if (section.id == 1) 1..2 else 3..6
            issue(section.fields.isEmpty() || section.fields.size > allowed.count() || section.fields.map { it.id }.distinct().size != section.fields.size, "设置字段数量或重复 ID 无效")
            section.fields.forEach { field ->
                issue(field.id !in allowed || field.label.isBlank() || field.label.length > 80, "设置字段 ID 或名称无效")
                issue(field.minBytes !in 0..512 || field.maxBytes !in 1..512 || field.minBytes > field.maxBytes, "设置字段长度无效")
                issue(field.secret && field.readUuid != null, "密码字段不能读回")
                field.defaultValue?.let { value ->
                    issue(field.secret || field.id != 6 || field.rule != SetupFieldRule.IDENTIFIER,
                        "设置默认值仅支持设备名称（字段 6、IDENTIFIER），不允许凭据默认值")
                    issue(runCatching { field.validateValue(value) }.isFailure, "设备名称默认值格式或长度无效")
                }
                issue(field.id in listOf(2, 5) && !field.secret, "此协议的密码字段必须标为 secret")
                issue(field.readUuid != null && (!uuid.matches(field.readUuid) || field.readUuid.equals(panel.commandUuid, true) || field.readUuid.equals(panel.statusUuid, true)), "字段读取特征无效")
            }
        }
    }
}

fun SetupField.validateValue(value: String) {
    require('\u0000' !in value && value.encodeToByteArray().size in minBytes..maxBytes) { "$label 的字节长度无效" }
    require(when (rule) {
        SetupFieldRule.TEXT -> true
        SetupFieldRule.WIFI_PSK -> value.isEmpty() || value.encodeToByteArray().size in 8..63 || (value.length == 64 && value.all { it in "0123456789abcdefABCDEF" })
        SetupFieldRule.MQTT_URI -> listOf("ws://", "wss://", "mqtt://", "mqtts://").any(value::startsWith) && value.none { it.isWhitespace() || it in "@?#" }
        SetupFieldRule.IDENTIFIER -> Regex("[A-Za-z0-9_-]+").matches(value)
    }) { "$label 的格式无效" }
}

/** Inputs are ephemeral. Implementations must not persist or log their values. */
interface SetupPanelSession {
    suspend fun status(): NetworkSetupStatus
    suspend fun readValues(): Map<Int, String>
    suspend fun submit(sectionId: Int, values: Map<Int, String>): NetworkSetupStatus
}

/** Safe UI diagnostics: never include raw transport errors or configuration payloads. */
enum class SetupFailureKind { PREPARE_READ, WRITE, STATUS_READ, INVALID_STATUS, ACK_TIMEOUT, REJECTED, ACK_MISMATCH }
class SetupPanelFailure(
    val kind: SetupFailureKind,
    message: String,
    val opcode: Int? = null,
    val request: Int? = null,
    val reply: NetworkSetupStatus? = null,
) : IllegalStateException(message)

/** Existing saved values win, including empty values; defaults apply only to unsaved groups. */
fun SetupPanel.initialInputs(values: Map<Int, String>, unsavedSections: Set<Int>): Map<Int, String> =
    values + sections.filter { it.id in unsavedSections }.flatMap { it.fields }
        .filter { !it.secret && it.defaultValue != null }
        .associate { it.id to requireNotNull(it.defaultValue) }

fun SetupSection.passwordConfirmation(inputs: Map<Int, String>, clearSecrets: Set<Int>, savedPasswords: Set<Int>): String? {
    val warnings = fields.filter { it.secret }.mapNotNull { field ->
        when {
            field.id in clearSecrets -> "${field.label}将被明确清空。"
            inputs[field.id].isNullOrEmpty() && field.id in savedPasswords -> "${field.label}留空：本次不发送该字段，保留设备已保存的密码。"
            inputs[field.id].isNullOrEmpty() -> "${field.label}留空：设备未报告已保存密码，本次也不会设置密码；需要密码的网络或 Broker 可能无法连接。"
            else -> null
        }
    }
    return warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

fun PanelDefinition.setupImportNotice(): String = setupPanels.flatMap { it.sections }.flatMap { it.fields }
    .filter { it.defaultValue != null }.joinToString("\n") {
        "${it.label}建议值：${it.defaultValue}。仅在设备未配置时预填；已有名称不会覆盖，请确保与 MQTT topic 中的设备标识一致。"
    }
