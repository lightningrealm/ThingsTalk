package com.lr.thingstalk.device

import com.lr.thingstalk.data.device.DeviceStorage
import com.lr.thingstalk.data.device.JsonDeviceRepository
import com.lr.thingstalk.domain.device.BleDeviceTarget
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.domain.panel.BleOperationBinding
import com.lr.thingstalk.domain.panel.CapabilityType
import com.lr.thingstalk.domain.panel.CodecType
import com.lr.thingstalk.domain.panel.PanelBinding
import com.lr.thingstalk.domain.panel.PanelCapability
import com.lr.thingstalk.domain.panel.PanelWidget
import com.lr.thingstalk.domain.panel.WidgetType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceRepositoryTest {
    @Test fun creationFromParsedConfigurationPreservesTransportAndPanelInOneSave() = runTest {
        val ble = com.lr.thingstalk.data.panel.defaultDemoPanel()
        val mqtt = PanelDefinition(schemaVersion = 4, title = "Remote", transport = PanelTransport.MQTT)
        val dual = ble.asDual().asChannelPanel()
        for (panel in listOf(ble, mqtt, dual)) {
            val parsed = PanelJson.decode(PanelJson.encode(panel))
            val storage = MemoryDeviceStorage()
            val repository = JsonDeviceRepository(storage)
            val created = repository.createFromPanel(parsed.title, parsed)
            assertEquals(parsed, created.panel)
            assertEquals(parsed.transport, created.panel.transport)
            assertNull(created.bleTarget)
            assertEquals("", created.mqttEndpoint)
            assertEquals(1, storage.saves)
            assertEquals(created, JsonDeviceRepository(storage).devices.value.single())
        }
    }

    @Test fun configurationCreationFailureLeavesNoEmptyDeviceAndBleAssociationIsExplicit() = runTest {
        val storage = MemoryDeviceStorage()
        val repository = JsonDeviceRepository(storage)
        val target = BleDeviceTarget("known-device", "Sensor")
        val panel = com.lr.thingstalk.data.panel.defaultDemoPanel().asDual()
        storage.failSave = true
        assertFailsWith<IllegalStateException> { repository.createFromPanel("Imported", panel, target) }
        assertTrue(repository.devices.value.isEmpty())
        storage.failSave = false
        val mqtt = PanelDefinition(schemaVersion = 4, title = "MQTT", transport = PanelTransport.MQTT)
        assertFailsWith<IllegalArgumentException> { repository.createFromPanel("MQTT", mqtt, target) }
        assertTrue(repository.devices.value.isEmpty())
        val created = repository.createFromPanel("Imported", panel, target)
        assertEquals(target, created.bleTarget)
        assertEquals(panel, created.panel)
        assertEquals(1, storage.saves)
    }

    @Test fun promotionPreservesIdentityConnectionsAndConfigurationAcrossReload() = runTest {
        val storage = MemoryDeviceStorage()
        val repository = JsonDeviceRepository(storage)
        val target = BleDeviceTarget("saved-peripheral", "Lamp")
        val device = repository.create("Lamp", target)
        val panel = com.lr.thingstalk.data.panel.defaultDemoPanel()
        repository.savePanel(device.id, panel)
        repository.saveMqttEndpoint(device.id, "wss://example.test/mqtt")
        repository.promoteToDual(device.id)
        val saved = JsonDeviceRepository(storage).devices.value.single()
        assertEquals(device.id, saved.id)
        assertEquals(target, saved.bleTarget)
        assertEquals("wss://example.test/mqtt", saved.mqttEndpoint)
        assertEquals(com.lr.thingstalk.domain.panel.PanelTransport.DUAL, saved.panel.transport)
        assertEquals(panel.widgets, saved.panel.widgets)
        assertEquals(panel.capabilities, saved.panel.capabilities)
        assertTrue(saved.panel.bindings.all { it.controlTransport == null || it.controlTransport == com.lr.thingstalk.domain.panel.PanelTransport.BLE })
        val mqtt = repository.createMqtt("Remote")
        repository.promoteToDual(mqtt.id)
        val dual = repository.createDual("Both")
        assertEquals(5, dual.panel.schemaVersion)
        assertEquals(repository.devices.value, JsonDeviceRepository(storage).devices.value)
    }

    @Test
    fun emptyStorageHasNoSeededDevicesAndCreationIsAtomic() = runTest {
        val storage = MemoryDeviceStorage()
        val repository = JsonDeviceRepository(storage) { "device-one" }
        assertTrue(repository.devices.value.isEmpty())
        assertEquals(0, storage.saves)

        val target = BleDeviceTarget("AA:BB:CC:DD:EE:FF", "User Lamp")
        val created = repository.create("  我的灯  ", target)
        assertEquals("device-one", created.id)
        assertEquals("我的灯", created.name)
        assertEquals("我的灯", created.panel.title)
        assertTrue(created.panel.widgets.isEmpty())
        assertTrue(created.panel.capabilities.isEmpty())
        assertTrue(created.panel.bindings.isEmpty())
        assertEquals(target, created.bleTarget)
        assertEquals(listOf(created), repository.devices.value)
        assertEquals(1, storage.saves)
        assertEquals(repository.devices.value, JsonDeviceRepository(storage).devices.value)
    }

    @Test
    fun panelRenameAssociationAndDeleteSurviveReload() = runTest {
        val storage = MemoryDeviceStorage()
        val repository = JsonDeviceRepository(storage)
        val first = repository.create("第一台")
        val second = repository.create("第二台")
        assertNotEquals(first.id, second.id)
        val panel = first.panel.copy(
            title = "卧室控制",
            capabilities = listOf(PanelCapability("power", "电源", CapabilityType.BOOLEAN)),
            bindings = listOf(PanelBinding(
                capabilityId = "power",
                write = BleOperationBinding(
                    serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristicUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
                    codec = CodecType.BOOLEAN,
                ),
            )),
            widgets = listOf(PanelWidget("power-switch", WidgetType.SWITCH, "开启灯光", "power")),
        )
        repository.savePanel(first.id, panel)
        repository.rename(first.id, "  卧室灯  ")
        val target = BleDeviceTarget("A-peripheral-identifier", null)
        repository.associate(first.id, target)
        repository.delete(second.id)

        val reloaded = JsonDeviceRepository(storage)
        val actual = reloaded.devices.value.single()
        assertEquals(first.id, actual.id)
        assertEquals("卧室灯", actual.name)
        assertEquals(panel, actual.panel)
        assertEquals(target, actual.bleTarget)
        reloaded.associate(first.id, null)
        assertNull(JsonDeviceRepository(storage).devices.value.single().bleTarget)
        reloaded.delete(first.id)
        assertTrue(JsonDeviceRepository(storage).devices.value.isEmpty())
    }

    @Test
    fun writeFailureKeepsPublishedStateAndSavedArchive() = runTest {
        val storage = MemoryDeviceStorage()
        val repository = JsonDeviceRepository(storage)
        val original = repository.create("设备")
        val source = storage.source
        storage.failSave = true

        assertFailsWith<IllegalStateException> { repository.rename(original.id, "未保存") }
        assertEquals(listOf(original), repository.devices.value)
        assertEquals(source, storage.source)
        assertFailsWith<IllegalStateException> { repository.delete(original.id) }
        assertEquals(listOf(original), repository.devices.value)

        storage.failSave = false
        repository.rename(original.id, "已保存")
        assertEquals("已保存", JsonDeviceRepository(storage).devices.value.single().name)
    }

    @Test
    fun concurrentMutationsDoNotLoseDevices() = runTest {
        var nextId = 0
        val storage = MemoryDeviceStorage()
        val repository = JsonDeviceRepository(storage) { "device-${nextId++}" }
        (1..16).map { index -> async { repository.create("设备 $index") } }.awaitAll()
        assertEquals(16, repository.devices.value.size)
        assertEquals(16, repository.devices.value.map { it.id }.distinct().size)
        assertEquals(repository.devices.value, JsonDeviceRepository(storage).devices.value)
    }

    @Test
    fun cancellationDuringSaveCannotLeavePublishedStateBehindStorage() = runTest {
        lateinit var mutation: Job
        var saved: String? = null
        val storage = object : DeviceStorage {
            override fun load(): String? = saved
            override fun save(source: String) {
                saved = source
                mutation.cancel()
            }
        }
        val repository = JsonDeviceRepository(storage)
        mutation = launch(start = CoroutineStart.LAZY) { repository.create("保存中的设备") }
        mutation.start()
        mutation.join()
        assertTrue(mutation.isCancelled)
        assertEquals("保存中的设备", repository.devices.value.single().name)
        assertEquals(repository.devices.value, JsonDeviceRepository(storage).devices.value)
    }

    @Test
    fun invalidInputAndMissingDevicesCannotOverwriteStorage() = runTest {
        val storage = MemoryDeviceStorage()
        val repository = JsonDeviceRepository(storage)
        val original = repository.create("设备")
        val source = storage.source

        assertFailsWith<IllegalArgumentException> { repository.create(" \n ") }
        assertFailsWith<IllegalArgumentException> { repository.rename(original.id, "a".repeat(81)) }
        assertFailsWith<IllegalArgumentException> { repository.savePanel(original.id, original.panel.copy(schemaVersion = 99)) }
        assertFailsWith<IllegalArgumentException> { repository.associate(original.id, BleDeviceTarget("", "设备")) }
        assertFailsWith<IllegalArgumentException> { repository.delete("missing") }
        assertFailsWith<IllegalArgumentException> { repository.rename("missing", "名称") }
        assertEquals(listOf(original), repository.devices.value)
        assertEquals(source, storage.source)
    }

    @Test
    fun oldArchiveLoadsAndMqttDevicesPersistWithoutChangingTransport() = runTest {
        val storage = MemoryDeviceStorage("{\"schemaVersion\":1,\"devices\":[]}")
        val repository = JsonDeviceRepository(storage) { "mqtt-device" }
        val device = repository.createMqtt("MQTT lamp")
        repository.saveMqttEndpoint(device.id, "wss://example.test/mqtt")
        val restored = JsonDeviceRepository(storage).devices.value.single()
        assertEquals(com.lr.thingstalk.domain.panel.PanelTransport.MQTT, restored.panel.transport)
        assertEquals("wss://example.test/mqtt", restored.mqttEndpoint)
        assertTrue(storage.source!!.contains("\"schemaVersion\":2"))
        assertFailsWith<IllegalArgumentException> {
            repository.savePanel(device.id, restored.panel.copy(transport = com.lr.thingstalk.domain.panel.PanelTransport.BLE))
        }
        assertFailsWith<IllegalArgumentException> { repository.saveMqttEndpoint(device.id, "wss://user:secret@example.test") }
    }

    @Test
    fun corruptAndFutureStorageArePreservedWithoutAutomaticReset() {
        listOf(
            "broken-json",
            "{\"schemaVersion\":3,\"devices\":[]}",
            "{\"schemaVersion\":1,\"devices\":[],\"unknownFutureField\":true}",
        ).forEach { original ->
            val storage = MemoryDeviceStorage(source = original)
            assertFailsWith<IllegalArgumentException> { JsonDeviceRepository(storage) }
            assertEquals(original, storage.source)
            assertEquals(0, storage.saves)
        }
    }

    @Test
    fun storageReadFailureDoesNotBecomeAnEmptyDeviceList() {
        val repositoryFailure = object : DeviceStorage {
            override fun load(): String? = error("read failure")
            override fun save(source: String) = error("must not save")
        }
        assertFailsWith<IllegalStateException> { JsonDeviceRepository(repositoryFailure) }
    }
}

private class MemoryDeviceStorage(var source: String? = null) : DeviceStorage {
    var saves = 0
    var failSave = false

    override fun load(): String? = source

    override fun save(source: String) {
        check(!failSave) { "disk full" }
        this.source = source
        saves++
    }
}
