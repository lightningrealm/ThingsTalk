package com.lr.thingstalk.domain.device

import com.lr.thingstalk.domain.panel.PanelDefinition
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** A user-owned workspace; the panel can exist before a physical BLE device is associated. */
@Serializable
data class UserDevice(
    val id: String,
    val name: String,
    val panel: PanelDefinition,
    val bleTarget: BleDeviceTarget? = null,
    val mqttEndpoint: String = "",
)

/** The platform identifier is an Android address or an iOS peripheral UUID, never a cross-platform ID. */
@Serializable
data class BleDeviceTarget(val id: String, val name: String?)

interface DeviceRepository {
    val devices: StateFlow<List<UserDevice>>

    /** Creates an empty panel, optionally associating the discovered peripheral in the same save. */
    suspend fun create(name: String, target: BleDeviceTarget? = null): UserDevice
    /** Persists the validated configuration and optional BLE association in one transaction. */
    suspend fun createFromPanel(name: String, panel: PanelDefinition, target: BleDeviceTarget? = null): UserDevice = error("Configuration creation unsupported")
    suspend fun createMqtt(name: String): UserDevice = error("MQTT devices unsupported")
    suspend fun createDual(name: String): UserDevice = error("Dual devices unsupported")
    suspend fun promoteToDual(id: String): Unit = error("Dual devices unsupported")
    suspend fun saveMqttEndpoint(id: String, endpoint: String): Unit = error("MQTT devices unsupported")
    suspend fun rename(id: String, name: String)
    suspend fun savePanel(id: String, panel: PanelDefinition)
    suspend fun associate(id: String, target: BleDeviceTarget?)
    suspend fun delete(id: String)
}
