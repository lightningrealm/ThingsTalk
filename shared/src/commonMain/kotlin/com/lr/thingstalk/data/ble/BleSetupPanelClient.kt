package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** No credential persistence, traffic logging, reconnect or automatic replay. */
class BleSetupPanelClient(private val connection: BleConnection, private val definition: SetupPanel, private val mutex: Mutex = Mutex()) : SetupPanelSession {
    init { require(validateSetupPanels(listOf(definition)).isEmpty()) }
    private var sequence = Random.nextInt(1, 65535)
    override suspend fun status(): SetupState = mutex.withLock { withTimeout(5_000.milliseconds) { readStatus().state } }
    private suspend fun readStatus(): SetupReceipt {
        val bytes = try { connection.read(definition.status) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw SetupPanelFailure(SetupFailureKind.STATUS_READ, "BLE 状态读取失败，无法确认设备处理结果；请检查连接后重读状态。") }
        return try {
            val decoded = if (definition.protocol.isLegacy) ChunkedBleSettingsProtocol.parseLegacy(bytes, definition.protocol.isAi)
                else ChunkedBleSettingsProtocol.parse(bytes)
            decoded.also { receipt ->
                // A device may expose only a subset of its standard fields in this panel.
                val ai = definition.protocol.isAi
                require(receipt.state.savedSectionIds.all { it in if (ai) setOf(1) else setOf(1, 2) })
                require(receipt.state.savedSecretFieldIds.all { it in if (ai) setOf(2) else setOf(2, 5) })
            }
        }
        catch (_: IllegalArgumentException) { throw SetupPanelFailure(SetupFailureKind.INVALID_STATUS, if (definition.protocol.isLegacy)
            "设备状态报文无效，需为协议 v1 的完整 14 字节；请核对固件与面板协议。"
            else "设备状态报文无效，需为协议 v2 的完整 16 字节；请核对固件与面板协议。") }
    }
    override suspend fun readValues(): Map<Int, String> = mutex.withLock {
        withTimeout(60_000.milliseconds) {
            val status = readStatus()
            require(status.state.unlocked) { "请先按设备说明开启设置窗口" }
            require(definition.sections.flatMap { it.fields }.any { it.readUuid != null }) {
                "设置面板未声明读取特征，无法在保存前准备访问；请补充固件提供的加密 readUuid。"
            }
            buildMap {
                for (section in definition.sections) for (field in section.fields) {
                    if (field.secret) continue
                    val saved = section.id in status.state.savedSectionIds
                    val id = definition.readId(field)
                    if (id == null) { if (!saved) put(field.id, ""); continue }
                    // Even an unconfigured field must be accessed before opening the editor:
                    // firmware READ_ENC permissions trigger the OS security procedure here.
                    val bytes = readPreparedField(id)
                    try {
                        require(bytes.size <= field.maxBytes) { "设备配置字段长度无效" }
                        val text = bytes.decodeToString(throwOnInvalidSequence = true)
                        put(field.id, if (saved) text else "")
                    } finally { bytes.fill(0) }
                }
            }
        }
    }
    private suspend fun readPreparedField(id: BleCharacteristicId): ByteArray {
        repeat(15) { attempt ->
            try { return connection.read(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val diagnostic = bleFailureDiagnostic(failure) ?: "系统未提供可识别的错误码"
                val awaitingSecurity = diagnostic == "CBATTErrorDomain / code=5" || diagnostic == "CBATTErrorDomain / code=15"
                if (awaitingSecurity && attempt < 14) {
                    // Only recheck a read. Never replay BEGIN/APPEND/COMMIT after pairing.
                    delay(2_000.milliseconds)
                    require(readStatus().state.unlocked) { "设备设置窗口已关闭，请重新开启后准备" }
                } else {
                    throw SetupPanelFailure(SetupFailureKind.PREPARE_READ,
                        if (awaitingSecurity) "设备仍拒绝加密读取：$diagnostic。即使已确认配对，当前访问权限仍未通过；请检查固件加密状态。尚未发送配置命令。"
                        else "设备设置准备失败：$diagnostic。请检查连接和设置窗口后重试；尚未发送配置命令。")
                }
            }
        }
        error("Unreachable")
    }

    override suspend fun submit(sectionId: Int, values: Map<Int, String>): SetupState {
        val section = definition.sections.single { it.id == sectionId }
        require(values.keys.all { id -> section.fields.any { it.id == id } }) { "包含未定义的字段" }
        section.fields.forEach { field ->
            val value = values[field.id]
            require(value != null || field.secret) { "请填写 ${field.label}" }
            if (value != null) field.validateValue(value)
            require(!field.readOnly || value == field.defaultValue) { "固定服务参数不能修改" }
        }
        return save(sectionId, values).state
    }
    private suspend fun save(section: Int, fields: Map<Int, String>): SetupReceipt = mutex.withLock {
        require(fields.values.none { '\u0000' in it }) { "字段不能包含 NUL" }
        withTimeout(90_000.milliseconds) {
            val initial = readStatus()
            require(initial.state.unlocked) { "请先按设备说明开启设置窗口" }
            sequence = initial.request // Next ID cannot match the device's last acknowledgement.
            val size = minOf(14, connection.maximumWriteLength(BleWriteMode.WITH_RESPONSE) - 6)
            require(size > 0) { "当前 BLE 写入长度不足" }
            exchange(0xB1, section)
            for ((field, text) in fields) {
                val bytes = text.encodeToByteArray()
                try {
                    if (bytes.isEmpty()) exchange(0xB2, field)
                    var offset = 0
                    while (offset < bytes.size) {
                        val chunk = bytes.copyOfRange(offset, minOf(offset + size, bytes.size))
                        try { exchange(0xB2, field, offset, chunk) } finally { chunk.fill(0) }
                        offset += chunk.size
                    }
                } finally { bytes.fill(0) }
            }
            exchange(0xB3).also { receipt ->
                if (!definition.protocol.isLegacy && section !in receipt.state.savedSectionIds)
                    throw SetupPanelFailure(SetupFailureKind.ACK_MISMATCH, "保存回执未标记目标分组已保存；请重读状态，不会自动重发。", 0xB3, receipt.request, receipt)
            }
        }
    }
    private suspend fun exchange(op: Int, field: Int = 0, offset: Int = 0, payload: ByteArray = byteArrayOf()): SetupReceipt {
        sequence = (sequence + 1) and 65535
        val expected = sequence
        val packet = ChunkedBleSettingsProtocol.packet(op, expected, field, offset, payload)
        try {
            val written = withTimeoutOrNull(30_000.milliseconds) {
                connection.write(definition.command, packet, BleWriteMode.WITH_RESPONSE)
                true
            }
            if (written != true) throw SetupPanelFailure(SetupFailureKind.WRITE, "BLE 写入超时，无法确认设备是否收到；请检查连接或系统配对提示，不会自动重发。", op, expected)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SetupPanelFailure) { throw failure }
        catch (failure: Exception) {
            val diagnostic = bleFailureDiagnostic(failure) ?: "系统未提供可识别的错误码"
            throw SetupPanelFailure(SetupFailureKind.WRITE,
                "BLE 写入失败，尚未取得固件回执。$diagnostic。不会自动重发。", op, expected)
        }
        finally { packet.fill(0) }
        var lastReply: SetupReceipt? = null
        return withTimeoutOrNull(15_000.milliseconds) {
            var result: SetupReceipt
            while (true) {
                result = readStatus()
                lastReply = result
                if (result.request == expected && result.opcode == op) break
                delay(75.milliseconds)
            }
            val expectedCode = if (op == 0xB3) 2 else 1
            if (result.result != expectedCode) throw SetupPanelFailure(SetupFailureKind.REJECTED, when (result.result) {
                3 -> "设备拒绝了配置，请检查地址、密码和字段长度"
                4 -> "设备设置窗口已关闭，请按设备说明开启后重试"
                5 -> "配置会话已失效或被其他配置覆盖，请重新保存"
                6 -> "设备存储失败，配置未确认保存"
                else -> "设备未确认配置，请重新读取状态"
            }, op, expected, result)
            if (result.field != field || result.offset != offset + if (op == 0xB2) payload.size else 0)
                throw SetupPanelFailure(SetupFailureKind.ACK_MISMATCH, "设备回执字段或偏移不匹配；已停止提交，不会自动重发。", op, expected, result)
            result
        } ?: throw SetupPanelFailure(SetupFailureKind.ACK_TIMEOUT, "BLE 写入已完成，但等待匹配的固件回执超时；保存结果未确认，请重读状态，不会自动重发。", op, expected, lastReply)
    }
}
