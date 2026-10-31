package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.*
import kotlinx.serialization.Serializable

/** Portable v6 format. Separate DTOs prevent MQTT operations from entering the BLE section. */
@Serializable
internal data class ChannelPanelJson(
    val schemaVersion: Int,
    val title: String,
    val transport: PanelTransport,
    val defaultChannel: PanelTransport? = null,
    val capabilities: List<PanelCapability> = emptyList(),
    val widgets: List<PanelWidget> = emptyList(),
    val payloadFormats: List<PayloadFormat> = emptyList(),
    val channels: Channels,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val setupPanels: List<SetupPanel> = emptyList(),
) {
    fun definition(): PanelDefinition {
        require(schemaVersion in 6..7)
        require(schemaVersion == 7 || transport == PanelTransport.DUAL)
        require(schemaVersion == 7 || setupPanels.isEmpty())
        require(transport != PanelTransport.BLE || channels.mqtt.bindings.isEmpty())
        require(transport != PanelTransport.MQTT || channels.ble.bindings.isEmpty())
        require(if (transport == PanelTransport.DUAL) defaultChannel == PanelTransport.BLE || defaultChannel == PanelTransport.MQTT else defaultChannel == null)
        val ble = channels.ble.bindings
        val mqtt = channels.mqtt.bindings
        require(ble.map { it.capabilityId }.distinct().size == ble.size && mqtt.map { it.capabilityId }.distinct().size == mqtt.size) {
            "同一通道内每项能力只能有一个绑定"
        }
        require(ble.all { it.read != null || it.write != null || it.notify != null } && mqtt.all { it.publish != null || it.subscribe != null }) {
            "通道绑定至少需要一个操作"
        }
        val ids = (ble.map { it.capabilityId } + mqtt.map { it.capabilityId }).distinct()
        return PanelDefinition(schemaVersion, title, capabilities, ids.map { id ->
            val b = ble.singleOrNull { it.capabilityId == id }
            val m = mqtt.singleOrNull { it.capabilityId == id }
            PanelBinding(id, b?.read, b?.write, b?.notify, b?.actionValue, m?.publish, m?.subscribe,
                controlTransport = if (transport != PanelTransport.DUAL) null else if (b?.write != null) PanelTransport.BLE else if (m?.publish != null) PanelTransport.MQTT else null,
                stateTransport = if (transport != PanelTransport.DUAL) null else if (b?.read != null || b?.notify != null) PanelTransport.BLE else if (m?.subscribe != null) PanelTransport.MQTT else null)
        }, widgets, payloadFormats, transport, defaultChannel, setupPanels)
    }
}

@Serializable internal data class Channels(val ble: BleChannel, val mqtt: MqttChannel)
@Serializable internal data class BleChannel(val bindings: List<BleChannelBinding> = emptyList())
@Serializable internal data class MqttChannel(val bindings: List<MqttChannelBinding> = emptyList())
@Serializable internal data class BleChannelBinding(
    val capabilityId: String, val read: BleOperationBinding? = null, val write: BleOperationBinding? = null,
    val notify: BleOperationBinding? = null, val actionValue: String? = null,
)
@Serializable internal data class MqttChannelBinding(
    val capabilityId: String, val publish: MqttOperationBinding? = null, val subscribe: MqttOperationBinding? = null,
)

internal fun PanelDefinition.channelJson() = ChannelPanelJson(schemaVersion, title, transport,
    defaultChannel, capabilities, widgets, payloadFormats,
    Channels(BleChannel(bindings.filter { it.read != null || it.write != null || it.notify != null }.map {
        BleChannelBinding(it.capabilityId, it.read, it.write, it.notify, it.actionValue)
    }), MqttChannel(bindings.filter { it.publish != null || it.subscribe != null }.map {
        MqttChannelBinding(it.capabilityId, it.publish, it.subscribe)
    })), setupPanels)
