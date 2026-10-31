package com.lr.thingstalk.data.device

import com.lr.thingstalk.domain.device.BleDeviceTarget
import com.lr.thingstalk.domain.device.DeviceRepository
import com.lr.thingstalk.domain.device.UserDevice
import com.lr.thingstalk.domain.panel.asDual
import com.lr.thingstalk.domain.panel.PanelDefinition
import com.lr.thingstalk.domain.panel.validatePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Serializes mutations and publishes only persisted snapshots. No seeded or synthetic devices are added. */
class JsonDeviceRepository(
    private val storage: DeviceStorage,
    private val nextId: () -> String = ::newDeviceId,
) : DeviceRepository {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }
    private val mutableDevices = MutableStateFlow(load())
    override val devices: StateFlow<List<UserDevice>> = mutableDevices.asStateFlow()

    override suspend fun create(name: String, target: BleDeviceTarget?): UserDevice {
        val normalizedName = deviceName(name)
        validateTarget(target)
        return mutate { current ->
            val id = nextId()
            require(id.isNotBlank() && id.length <= 128) { "设备 ID 无效" }
            require(current.none { it.id == id }) { "设备 ID 重复，请重试" }
            val device = UserDevice(
                id = id,
                name = normalizedName,
                panel = PanelDefinition(
                    schemaVersion = 2,
                    title = normalizedName,
                    capabilities = emptyList(),
                    bindings = emptyList(),
                    widgets = emptyList(),
                ),
                bleTarget = target,
            )
            (current + device) to device
        }
    }

    override suspend fun createFromPanel(name: String, panel: PanelDefinition, target: BleDeviceTarget?): UserDevice {
        val normalizedName = deviceName(name)
        val errors = validatePanel(panel)
        require(errors.isEmpty()) { errors.joinToString("；") }
        require(target == null || panel.transport != com.lr.thingstalk.domain.panel.PanelTransport.MQTT) {
            "纯 MQTT 配置不能关联蓝牙外设，请从设备列表导入创建"
        }
        validateTarget(target)
        return mutate { current ->
            val device = UserDevice(nextId(), normalizedName, panel, bleTarget = target)
            (current + device) to device
        }
    }

    override suspend fun createMqtt(name: String): UserDevice = mutate { current ->
        val id = nextId()
        val device = UserDevice(id, deviceName(name), PanelDefinition(schemaVersion = 4, title = deviceName(name), transport = com.lr.thingstalk.domain.panel.PanelTransport.MQTT))
        (current + device) to device
    }

    override suspend fun createDual(name: String): UserDevice = mutate { current ->
        val device = UserDevice(nextId(), deviceName(name), PanelDefinition(title = deviceName(name)).asDual())
        (current + device) to device
    }

    override suspend fun promoteToDual(id: String) { update(id) { it.copy(panel = it.panel.asDual()) } }

    override suspend fun saveMqttEndpoint(id: String, endpoint: String) {
        require(endpoint.length <= 2048 && endpoint.none { it in "@?#" }) { "服务器地址无效，请将认证信息填写到独立输入框" }
        update(id) { it.copy(mqttEndpoint = endpoint) }
    }

    override suspend fun rename(id: String, name: String) {
        val normalizedName = deviceName(name)
        update(id) { it.copy(name = normalizedName) }
    }

    override suspend fun savePanel(id: String, panel: PanelDefinition) {
        val snapshot = panel.copy(
            capabilities = panel.capabilities.toList(),
            bindings = panel.bindings.toList(),
            widgets = panel.widgets.toList(),
            payloadFormats = panel.payloadFormats.map { it.copy(fields = it.fields.toList()) },
        )
        update(id) {
            require(it.panel.transport == snapshot.transport) { "导入配置的传输类型与设备不一致，请新建对应类型的设备" }
            it.copy(panel = snapshot)
        }
    }

    override suspend fun associate(id: String, target: BleDeviceTarget?) {
        validateTarget(target)
        update(id) { it.copy(bleTarget = target) }
    }

    override suspend fun delete(id: String) {
        mutate { current ->
            require(current.any { it.id == id }) { "设备不存在，可能已被删除" }
            current.filterNot { it.id == id } to Unit
        }
    }

    private suspend fun update(id: String, change: (UserDevice) -> UserDevice) {
        mutate { current ->
            require(current.any { it.id == id }) { "设备不存在，可能已被删除" }
            current.map { if (it.id == id) change(it) else it } to Unit
        }
    }

    private suspend fun <T> mutate(change: (List<UserDevice>) -> Pair<List<UserDevice>, T>): T = mutex.withLock {
        // Once persistence begins, cancellation must not leave disk ahead of the published state.
        // Preference serialization and disk writes run off the UI thread on both platforms.
        withContext(Dispatchers.Default + NonCancellable) {
            val (next, result) = change(mutableDevices.value)
            validateDevices(next)
            storage.save(json.encodeToString(DeviceArchive(devices = next)))
            mutableDevices.value = next
            result
        }
    }

    private fun load(): List<UserDevice> {
        val source = storage.load() ?: return emptyList()
        val archive = json.decodeFromString<DeviceArchive>(source)
        require(archive.schemaVersion in 1..2) { "无法读取此版本的设备存储：${archive.schemaVersion}" }
        validateDevices(archive.devices)
        return archive.devices
    }

    private fun validateDevices(devices: List<UserDevice>) {
        require(devices.map { it.id }.distinct().size == devices.size) { "设备存储包含重复 ID" }
        devices.forEach { device ->
            require(device.id.isNotBlank() && device.id.length <= 128) { "设备 ID 无效" }
            deviceName(device.name)
            validateTarget(device.bleTarget)
            val errors = validatePanel(device.panel)
            require(errors.isEmpty()) { "${device.name}：${errors.joinToString("；")}" }
        }
    }

    private fun deviceName(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 80) { "设备名称需为 1–80 个字符" }
    }

    private fun validateTarget(target: BleDeviceTarget?) {
        if (target == null) return
        require(target.id.isNotBlank() && target.id.length <= 256) { "蓝牙设备标识无效" }
        require(target.name == null || target.name.length <= 256) { "蓝牙广播名称过长" }
    }
}

@Serializable
private data class DeviceArchive(val schemaVersion: Int = 2, val devices: List<UserDevice>)

@OptIn(ExperimentalUuidApi::class)
private fun newDeviceId(): String = Uuid.random().toString()
