package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.mapNotNull

/** No retries, scripts, fragmentation, or automatic connection; lifetime belongs to the caller. */
class BlePanelOperations(private val connection: BleConnection) : PanelOperations {
    override suspend fun read(definition: PanelDefinition, capabilityId: String): String {
        currentCoroutineContext().ensureActive()
        val (capability, binding) = resolve(definition, capabilityId)
        val operation = requireNotNull(binding.read) { "能力不支持读取" }
        val id = discover(operation, BleProperty.READ)
        return decode(definition, capability, operation, connection.read(id))
    }

    override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) {
        currentCoroutineContext().ensureActive()
        val (capability, binding) = resolve(definition, capabilityId)
        val operation = requireNotNull(binding.write) { "能力不支持写入" }
        val payload = if (operation.payload != null) PanelPayloadCodec.encode(definition, capability, operation, value)
            else PanelValueCodec.encode(definition.schemaVersion, capability, operation, if (capability.type == CapabilityType.ACTION) binding.actionValue!! else value)
        val mode = when (operation.writeMode) {
            PanelWriteMode.WITH_RESPONSE -> BleWriteMode.WITH_RESPONSE
            PanelWriteMode.WITHOUT_RESPONSE -> BleWriteMode.WITHOUT_RESPONSE
        }
        val property = if (mode == BleWriteMode.WITH_RESPONSE) BleProperty.WRITE else BleProperty.WRITE_WITHOUT_RESPONSE
        val id = discover(operation, property)
        require(payload.size <= connection.maximumWriteLength(mode)) { "数据超过本次连接的最大写入长度" }
        currentCoroutineContext().ensureActive()
        connection.write(id, payload, mode)
    }

    override fun observe(definition: PanelDefinition, capabilityId: String): Flow<String> =
        observeEvents(definition, capabilityId).mapNotNull { (it as? PanelObservation.Value)?.value }

    override fun observeEvents(definition: PanelDefinition, capabilityId: String): Flow<PanelObservation> = channelFlow {
        val (capability, binding) = resolve(definition, capabilityId)
        val operation = requireNotNull(binding.notify) { "能力不支持通知" }
        val id = discover(operation, BleProperty.NOTIFY, BleProperty.INDICATE)
        connection.notifications(id, onReady = { send(PanelObservation.Ready) }).collect { bytes ->
            // Only decoding a single packet is recoverable. Setup/transport/downstream errors
            // and coroutine cancellation must still terminate collection.
            val observation = try {
                PanelObservation.Value(decode(definition, capability, operation, bytes))
            } catch (failure: IllegalArgumentException) {
                PanelObservation.InvalidPayload(failure.message?.take(300) ?: "通知报文格式无效")
            }
            send(observation)
        }
    }

    private fun decode(definition: PanelDefinition, capability: PanelCapability, operation: BleOperationBinding, bytes: ByteArray): String =
        if (operation.payload != null) PanelPayloadCodec.decode(definition, capability, operation, bytes)
        else PanelValueCodec.decode(definition.schemaVersion, capability, operation, bytes)

    private fun resolve(definition: PanelDefinition, id: String): Pair<PanelCapability, PanelBinding> {
        require(definition.transport == PanelTransport.BLE) { "需要 BLE 面板" }
        val errors = validatePanel(definition)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        return requireNotNull(definition.capabilities.singleOrNull { it.id == id }) { "能力不存在：$id" } to
            requireNotNull(definition.bindings.singleOrNull { it.capabilityId == id }) { "绑定不存在：$id" }
    }

    private suspend fun discover(operation: BleOperationBinding, vararg permitted: BleProperty): BleCharacteristicId {
        val services = connection.discoverServices().filter { it.uuid.equals(operation.serviceUuid, ignoreCase = true) }
        require(services.size == 1) { "未发现唯一的 BLE 服务路径" }
        val matches = services.single().characteristics.filter {
            it.id.serviceUuid.equals(operation.serviceUuid, ignoreCase = true) &&
                it.id.characteristicUuid.equals(operation.characteristicUuid, ignoreCase = true)
        }
        require(matches.size == 1) { "未发现唯一的 BLE 服务 / 特征路径" }
        val characteristic = matches.single()
        require(permitted.any { it in characteristic.properties }) { "设备特征不支持所选操作" }
        return characteristic.id
    }

}
