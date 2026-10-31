@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.lr.thingstalk.data.activity

import com.lr.thingstalk.domain.mqtt.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ActivityRepositoryTest {
    private class Storage(var text: String? = null) : ActivityStorage {
        var fail = false
        override fun load() = text
        override suspend fun save(source: String) { check(!fail); text = source }
    }
    @Test fun summariesSurviveRestartWithoutTrafficPayloads() = runTest {
        val storage = Storage()
        val repo = StoredActivityRepository(storage, backgroundScope) { 1000 }
        repo.setCapturePayloads(true)
        val ref = repo.record("d", "Device", "MQTT", "message.received", payload = "secret-payload")
        repo.event("d", "Device", "MQTT", "Failed", "safe summary", true, ref)
        repo.acknowledge(repo.archive.value.events.single().id)
        advanceTimeBy(300); runCurrent()
        assertFalse(storage.text!!.contains("secret-payload"))
        val restored = StoredActivityRepository(storage, backgroundScope) { 1000 }
        assertTrue(restored.archive.value.events.single().acknowledged)
        assertTrue(restored.archive.value.events.single().read)
        assertTrue(restored.records.value.isEmpty())
        assertFalse(restored.capturePayloads.value)
    }
    @Test fun burstsCoalesceAndAcknowledgedFailuresCanRecur() = runTest {
        val repo = StoredActivityRepository(Storage(), backgroundScope) { 1000 }
        repeat(50) { repo.event("d", "Device", "BLE", "Failure", "read failed", true) }
        assertEquals(50, repo.archive.value.events.single().count)
        repo.acknowledge(repo.archive.value.events.single().id)
        repo.event("d", "Device", "BLE", "Failure", "read failed", true)
        assertEquals(2, repo.archive.value.events.size)
        assertFalse(repo.archive.value.events.first().acknowledged)
    }
    @Test fun viewedEventStaysReadWhenMatchingEventArrives() = runTest {
        val storage = Storage()
        val repo = StoredActivityRepository(storage, backgroundScope) { 1000 }
        repo.event("d", "Device", "BLE", "Failure", "read failed", true, "old-record")
        val viewedId = repo.archive.value.events.single().id
        repo.markRead(viewedId)
        repeat(3) { repo.event("d", "Device", "BLE", "Failure", "read failed", true, "new-record") }
        val viewed = repo.archive.value.events.single { it.id == viewedId }
        assertTrue(viewed.read)
        assertFalse(viewed.acknowledged)
        assertEquals(1, viewed.count)
        assertEquals("old-record", viewed.recordId)
        val next = repo.archive.value.events.first()
        assertNotEquals(viewedId, next.id)
        assertFalse(next.read)
        assertEquals(3, next.count)
        advanceTimeBy(300); runCurrent()
        val restored = StoredActivityRepository(storage, backgroundScope) { 1000 }
        assertTrue(restored.archive.value.events.single { it.id == viewedId }.read)
        assertEquals(1, restored.archive.value.events.count { !it.read })
    }
    @Test fun markAllReadDoesNotConsumeFutureEventsAndReadingDoesNotAcknowledge() = runTest {
        val repo = StoredActivityRepository(Storage(), backgroundScope) { 1000 }
        repo.event("d", "Device", "MQTT", "Failure", "publish failed", true)
        val originalId = repo.archive.value.events.single().id
        repo.markAllRead()
        repo.event("d", "Device", "MQTT", "Failure", "publish failed", true)
        assertTrue(repo.archive.value.events.single { it.id == originalId }.read)
        assertEquals(1, repo.archive.value.events.count { !it.read })
        assertTrue(repo.archive.value.events.none { it.acknowledged })
        repo.markRead(originalId, false)
        assertEquals(2, repo.archive.value.events.count { !it.read })
    }
    @Test fun boundedTrafficAndDisablingCaptureErasesPreviews() = runTest {
        val repo = StoredActivityRepository(Storage(), backgroundScope)
        repo.record("d", "Device", "BLE", "notify", payload = "hidden")
        assertNull(repo.records.value.single().payload)
        repo.setCapturePayloads(true)
        repeat(600) { repo.record("d", "Device", "BLE", "notify", payload = "visible") }
        assertEquals(500, repo.records.value.size)
        repo.setCapturePayloads(false)
        assertTrue(repo.records.value.all { it.payload == null })
        assertTrue(repo.archive.value.events.isEmpty())
    }
    @Test fun retentionPrunesAndStorageFailureCanRetry() = runTest {
        var now = 1000L
        val storage = Storage().apply { fail = true }
        val repo = StoredActivityRepository(storage, backgroundScope) { now }
        repo.event("d", "Device", "BLE", "Old", "old event")
        advanceTimeBy(300); runCurrent()
        assertNotNull(repo.error.value)
        assertEquals(1, repo.archive.value.events.size)
        storage.fail = false; repo.retrySave()
        advanceTimeBy(300); runCurrent()
        assertNull(repo.error.value)
        assertNotNull(storage.text)
        now += 8 * 86_400_000L
        repo.setRetention(7)
        assertTrue(repo.archive.value.events.isEmpty())
    }
    @Test fun damagedArchiveIsNeverOverwritten() = runTest {
        val storage = Storage("damaged")
        val repo = StoredActivityRepository(storage, backgroundScope)
        repo.event("d", "Device", "BLE", "New", "event")
        repo.clearEvents(); repo.retrySave()
        advanceTimeBy(300); runCurrent()
        assertNotNull(repo.error.value)
        assertEquals("damaged", storage.text)
    }
    @Test fun mqttCollectsWithoutPageAndCloseDoesNotReportUnexpectedDisconnect() = runTest {
        val fake = FakeMqtt()
        val journal = StoredActivityRepository(Storage(), backgroundScope)
        val session = ActivityMqttRepository(fake, backgroundScope, journal, "d") { "Device" }
        session.connect(MqttConnectionConfig("ws://localhost/mqtt", "test")); runCurrent()
        fake.messages.emit(MqttMessage("state/d", byteArrayOf(1), MqttQos.AT_MOST_ONCE, true)); runCurrent()
        assertEquals(1, journal.records.value.count { it.operation == "message.received" })
        session.publish("command/d", byteArrayOf(2), MqttQos.AT_LEAST_ONCE, false)
        assertTrue(journal.records.value.first().detail.contains("不代表设备执行成功"))
        session.close(); runCurrent()
        assertTrue(journal.archive.value.events.any { it.title == "MQTT 已停止" })
        assertTrue(journal.archive.value.events.none { it.warning })
    }
    @Test fun templatesValidateAndSurviveRestart() = runTest {
        val storage = Storage()
        val repo = StoredActivityRepository(storage, backgroundScope)
        val definition = com.lr.thingstalk.domain.panel.PanelDefinition(title = "Template")
        val json = com.lr.thingstalk.data.panel.PanelJson.encode(definition)
        repo.saveTemplate("Template", json)
        assertFails { repo.saveTemplate("Invalid", "{}") }
        advanceTimeBy(300); runCurrent()
        val restored = StoredActivityRepository(storage, backgroundScope)
        assertEquals(definition, com.lr.thingstalk.data.panel.PanelJson.decode(restored.archive.value.templates.single().json))
    }
    @Test fun setupPayloadIsRedactedBeforeReachingActivitySource() = runTest {
        val journal = StoredActivityRepository(Storage(), backgroundScope)
        journal.setCapturePayloads(true)
        val traffic = com.lr.thingstalk.data.ble.InMemoryBleTraffic()
        val id = com.lr.thingstalk.domain.ble.BleCharacteristicId("service", "settings")
        traffic.protect { listOf(id) }
        traffic.onEntry = BleActivitySource(journal, "d") { "Device" }::record
        traffic.record(com.lr.thingstalk.domain.ble.BleTrafficDirection.TX, "write.request", id,
            "secret".encodeToByteArray(), "secret detail")
        assertNull(journal.records.value.single().payload)
        assertFalse(journal.records.value.single().detail.contains("secret"))
    }
    @Test fun readUpdatesReturnCommittedCountsAndExplicitFailures() = runTest {
        val repo = StoredActivityRepository(Storage(), backgroundScope)
        repeat(10) { repo.event("d$it", "Device", "BLE", "Failure", "failed", true) }
        val all = repo.markAllRead()
        assertEquals(10, all.changed)
        assertEquals(0, all.unread)
        assertNull(all.error)
        assertEquals(0, repo.markAllRead().changed)
        assertNotNull(repo.markRead("missing").error)
        val damaged = StoredActivityRepository(Storage("broken"), backgroundScope)
        assertNotNull(damaged.markAllRead().error)
    }
    @Test fun failedPersistenceDoesNotBlockImmediateReadUpdates() = runTest {
        val storage = Storage().apply { fail = true }
        val repo = StoredActivityRepository(storage, backgroundScope)
        repo.event("d", "Device", "BLE", "Failure", "failed", true)
        advanceTimeBy(300); runCurrent()
        assertNotNull(repo.error.value)
        assertEquals(1, repo.markAllRead().changed)
        assertTrue(repo.archive.value.events.single().read)
        storage.fail = false
        repo.retrySave()
        advanceTimeBy(300); runCurrent()
        assertTrue(StoredActivityRepository(storage, backgroundScope).archive.value.events.single().read)
    }
    @Test fun concurrentTrafficCannotRevertViewedEvent() = runTest {
        val repo = StoredActivityRepository(Storage(), backgroundScope)
        repo.event("d", "Device", "BLE", "Failure", "failed", true)
        val id = repo.archive.value.events.single().id
        coroutineScope {
            launch(Dispatchers.Default) { repeat(300) { repo.event("d", "Device", "BLE", "Failure", "failed", true) } }
            launch(Dispatchers.Default) { repeat(300) { repo.markRead(id) } }
        }
        assertTrue(repo.archive.value.events.single { it.id == id }.read)
        val result = repo.markAllRead()
        assertEquals(0, result.unread)
        assertTrue(repo.archive.value.events.all { it.read })
    }
    private class FakeMqtt : MqttRepository {
        override val state = MutableStateFlow(MqttConnectionState())
        override val subscriptions = MutableStateFlow<List<MqttSubscription>>(emptyList())
        override val messages = MutableSharedFlow<MqttMessage>()
        override suspend fun connect(config: MqttConnectionConfig) { state.value = MqttConnectionState(MqttConnectionStatus.CONNECTED) }
        override suspend fun disconnect() { state.value = MqttConnectionState() }
        override suspend fun subscribe(topicFilter: String, qos: MqttQos) = Unit
        override suspend fun unsubscribe(topicFilter: String) = Unit
        override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean) = Unit
        override fun close() { state.value = MqttConnectionState() }
    }
}
