@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.ble

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.presentation.ble.BleDebugViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlin.test.*

class BleDebugViewModelTest {
    @Test fun attachingDiscoversRealTreeWithoutOwningConnectionLifecycle() = debugTest { model, connection, store ->
        assertTrue(model.state.value.connected)
        assertEquals(connection.services, model.state.value.services)
        assertEquals(20, model.state.value.maximumWriteLengths[BleWriteMode.WITH_RESPONSE])
        assertEquals(40, model.state.value.maximumWriteLengths[BleWriteMode.WITHOUT_RESPONSE])
        assertEquals(1, connection.discoveryCalls)
        model.attachConnection(connection)
        runCurrent()
        assertEquals(1, connection.discoveryCalls)
        model.detachConnection()
        runCurrent()
        assertFalse(model.state.value.connected)
        assertTrue(model.state.value.services.isEmpty())
        model.attachConnection(connection)
        runCurrent()
        store.clear()
        runCurrent()
        assertEquals(0, connection.connectCalls)
        assertEquals(0, connection.disconnectCalls)
        assertEquals(0, connection.closeCalls)
    }

    @Test fun readingFormatsUnsignedBytesAndAllowsAnEmptyResponse() = debugTest { model, connection, _ ->
        connection.readResult = byteArrayOf(0, 0x80.toByte(), 0xFF.toByte())
        model.read(PRIMARY)
        runCurrent()
        assertEquals("00 80 FF", model.state.value.values[PRIMARY])
        connection.readResult = byteArrayOf()
        model.read(PRIMARY)
        runCurrent()
        assertEquals("", model.state.value.values[PRIMARY])
        assertEquals(2, connection.readCalls)
    }

    @Test fun writingParsesHexAndDoesNotInventAReceivedValue() = debugTest { model, connection, _ ->
        model.write(PRIMARY, "00 7f\nFE\t80", BleWriteMode.WITHOUT_RESPONSE)
        runCurrent()
        assertEquals(1, connection.writes.size)
        assertContentEquals(byteArrayOf(0, 0x7F, 0xFE.toByte(), 0x80.toByte()), connection.writes.single().bytes)
        assertEquals(BleWriteMode.WITHOUT_RESPONSE, connection.writes.single().mode)
        assertTrue(model.state.value.values.isEmpty())
        assertContains(model.state.value.feedback.orEmpty(), "不代表")
    }

    @Test fun invalidHexAndOversizeWritesNeverReachTransport() = debugTest { model, connection, _ ->
        for (invalid in listOf("", "  ", "1", "GG", "0x12", "0 12")) {
            model.write(PRIMARY, invalid, BleWriteMode.WITH_RESPONSE)
            runCurrent()
            assertNotNull(model.state.value.error, "Expected rejection for '$invalid'")
        }
        connection.maximums[BleWriteMode.WITH_RESPONSE] = 1
        model.write(PRIMARY, "01 02", BleWriteMode.WITH_RESPONSE)
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "上限 1")
        assertTrue(connection.writes.isEmpty())
    }

    @Test fun propertyAndUuidValidationRejectsUnsupportedOperations() = debugTest { model, connection, _ ->
        model.read(WRITE_ONLY)
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "不支持读取")
        model.write(SECONDARY, "01", BleWriteMode.WITH_RESPONSE)
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "写入方式")
        model.write(WRITE_ONLY, "01", BleWriteMode.WITH_RESPONSE)
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "写入方式")
        model.setNotifications(WRITE_ONLY, true)
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "不支持通知")
        model.read(PRIMARY.copy(serviceUuid = "invalid"))
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "UUID 无效")
        model.read(PRIMARY.copy(characteristicUuid = MISSING_UUID))
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "不存在")
        assertEquals(0, connection.readCalls)
        assertTrue(connection.writes.isEmpty())
        assertEquals(0, connection.notificationStarts)
    }

    @Test fun duplicateServicesAndCharacteristicsAreRejectedAsAmbiguous() = debugTest { model, connection, _ ->
        val service = connection.services.single()
        connection.services = listOf(service, service)
        model.refreshServices()
        runCurrent()
        model.read(PRIMARY)
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "不唯一")
        connection.services = listOf(service.copy(characteristics = listOf(service.characteristics.first(), service.characteristics.first())))
        model.refreshServices()
        runCurrent()
        model.write(PRIMARY, "01", BleWriteMode.WITH_RESPONSE)
        runCurrent()
        model.setNotifications(PRIMARY, true)
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "不唯一")
        assertEquals(0, connection.readCalls)
        assertTrue(connection.writes.isEmpty())
        assertEquals(0, connection.notificationStarts)
    }

    @Test fun notificationsBecomeEnabledOnlyAfterReadyAndCanStopIndividually() = debugTest { model, connection, _ ->
        val ready = CompletableDeferred<Unit>()
        connection.notificationReady = { ready.await() }
        model.setNotifications(PRIMARY, true)
        model.setNotifications(SECONDARY, true)
        runCurrent()
        assertEquals(setOf(PRIMARY, SECONDARY), model.state.value.subscribing)
        assertTrue(model.state.value.observing.isEmpty())
        ready.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.subscribing.isEmpty())
        assertEquals(setOf(PRIMARY, SECONDARY), model.state.value.observing)
        connection.events.getValue(PRIMARY).emit(byteArrayOf(0xAA.toByte()))
        runCurrent()
        assertEquals("AA", model.state.value.values[PRIMARY])
        assertEquals(1, model.state.value.notificationCounts[PRIMARY])
        model.setNotifications(PRIMARY, false)
        runCurrent()
        assertEquals(setOf(SECONDARY), model.state.value.observing)
        assertEquals(1, connection.activeNotifications)
        model.stopObserving()
        runCurrent()
        assertTrue(model.state.value.observing.isEmpty())
        assertEquals(0, connection.activeNotifications)
    }

    @Test fun notificationSetupTimesOutButReadySubscriptionsRemainActive() = debugTest { model, connection, _ ->
        connection.notificationReady = { awaitCancellation() }
        model.setNotifications(PRIMARY, true)
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "建立超时")
        assertTrue(model.state.value.subscribing.isEmpty())
        assertTrue(model.state.value.observing.isEmpty())
        assertEquals(0, connection.activeNotifications)
        connection.notificationReady = {}
        model.setNotifications(PRIMARY, true)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(setOf(PRIMARY), model.state.value.observing)
        assertEquals(1, connection.activeNotifications)
    }

    @Test fun notificationFailureIsVisibleAndDoesNotRetryAutomatically() = debugTest { model, connection, _ ->
        connection.notificationReady = { error("subscription rejected") }
        model.setNotifications(PRIMARY, true)
        runCurrent()
        assertContains(model.state.value.error.orEmpty(), "subscription rejected")
        assertTrue(model.state.value.observing.isEmpty())
        assertTrue(model.state.value.subscribing.isEmpty())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, connection.notificationStarts)
    }

    @Test fun lateReadCannotOverwriteNewerNotification() = debugTest { model, connection, _ ->
        val pendingRead = CompletableDeferred<ByteArray>()
        connection.readOperation = { pendingRead.await() }
        model.setNotifications(PRIMARY, true)
        model.read(PRIMARY)
        runCurrent()
        connection.events.getValue(PRIMARY).emit(byteArrayOf(0x22))
        runCurrent()
        pendingRead.complete(byteArrayOf(0x11))
        runCurrent()
        assertEquals("22", model.state.value.values[PRIMARY])
        assertEquals(1, model.state.value.notificationCounts[PRIMARY])
    }

    @Test fun disconnectCancelsPendingWorkAndExplicitReconnectRediscoversWithoutResubscribing() = debugTest { model, connection, _ ->
        var readCancelled = false
        connection.readOperation = { try { awaitCancellation() } finally { readCancelled = true } }
        model.setNotifications(PRIMARY, true)
        runCurrent()
        connection.events.getValue(PRIMARY).emit(byteArrayOf(0x22))
        model.read(PRIMARY)
        runCurrent()
        connection.status.value = BleConnectionState.DISCONNECTED
        runCurrent()
        assertTrue(readCancelled)
        assertFalse(model.state.value.connected)
        assertFalse(model.state.value.busy)
        assertTrue(model.state.value.values.isEmpty())
        assertTrue(model.state.value.notificationCounts.isEmpty())
        assertTrue(model.state.value.services.isEmpty())
        assertTrue(model.state.value.observing.isEmpty())
        assertEquals(0, connection.activeNotifications)
        connection.status.value = BleConnectionState.CONNECTED
        runCurrent()
        assertTrue(model.state.value.connected)
        assertEquals(2, connection.discoveryCalls)
        assertTrue(model.state.value.observing.isEmpty())
        assertEquals(1, connection.notificationStarts)
    }

    @Test fun replacingConnectionDiscardsLateReadAndReadyCallbacksFromOldInstance() = debugTest { model, old, _ ->
        val pendingRead = CompletableDeferred<ByteArray>()
        val pendingReady = CompletableDeferred<Unit>()
        old.readOperation = { withContext(NonCancellable) { pendingRead.await() } }
        old.notificationReady = { withContext(NonCancellable) { pendingReady.await() } }
        model.read(PRIMARY)
        model.setNotifications(PRIMARY, true)
        runCurrent()
        val replacement = FakeConnection().apply { readResult = byteArrayOf(0x33) }
        model.attachConnection(replacement)
        runCurrent()
        model.read(PRIMARY)
        runCurrent()
        assertEquals("33", model.state.value.values[PRIMARY])
        pendingRead.complete(byteArrayOf(0x11))
        pendingReady.complete(Unit)
        runCurrent()
        assertEquals("33", model.state.value.values[PRIMARY])
        assertTrue(model.state.value.subscribing.isEmpty())
        assertTrue(model.state.value.observing.isEmpty())
        assertEquals(0, old.activeNotifications)
        assertEquals(0, old.closeCalls)
    }

    @Test fun operationTimeoutReportsUnknownWriteResultAndNeverRetries() = debugTest { model, connection, _ ->
        connection.writeOperation = { awaitCancellation() }
        model.write(PRIMARY, "01", BleWriteMode.WITH_RESPONSE)
        runCurrent()
        assertTrue(model.state.value.busy)
        advanceTimeBy(10_001)
        runCurrent()
        assertFalse(model.state.value.busy)
        assertContains(model.state.value.error.orEmpty(), "结果未知")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, connection.writes.size)
        assertTrue(model.state.value.values.isEmpty())
    }

    @Test fun discoveryTimeoutCanBeRetriedExplicitly() = debugTest { model, connection, _ ->
        connection.discoveryOperation = { awaitCancellation() }
        model.refreshServices()
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertFalse(model.state.value.busy)
        assertContains(model.state.value.error.orEmpty(), "发现服务超时")
        assertTrue(model.state.value.services.isEmpty())
        connection.discoveryOperation = null
        model.refreshServices()
        runCurrent()
        assertEquals(connection.services, model.state.value.services)
        assertNull(model.state.value.error)
    }

    @Test fun clearingViewModelOnlyCancelsItsSubscriptions() = debugTest { model, connection, store ->
        model.setNotifications(PRIMARY, true)
        runCurrent()
        assertEquals(1, connection.activeNotifications)
        store.clear()
        runCurrent()
        assertEquals(0, connection.activeNotifications)
        assertEquals(0, connection.closeCalls)
        assertEquals(0, connection.disconnectCalls)
        assertEquals(BleConnectionState.CONNECTED, connection.status.value)
    }

    private fun debugTest(block: suspend TestScope.(BleDebugViewModel, FakeConnection, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val connection = FakeConnection()
            val model = BleDebugViewModel()
            store.put("debug", model)
            model.attachConnection(connection)
            runCurrent()
            block(model, connection, store)
        } finally {
            store.clear()
            try { advanceUntilIdle() } finally { Dispatchers.resetMain() }
        }
    }

    private class FakeConnection : BleConnection {
        val status = MutableStateFlow(BleConnectionState.CONNECTED)
        override val state: Flow<BleConnectionState> = status
        var services = listOf(BleService(SERVICE_UUID, listOf(
            BleCharacteristic(PRIMARY, BleProperty.entries.toSet(), listOf(BleDescriptor("00002902-0000-1000-8000-00805f9b34fb"))),
            BleCharacteristic(SECONDARY, setOf(BleProperty.READ, BleProperty.INDICATE), emptyList()),
            BleCharacteristic(WRITE_ONLY, setOf(BleProperty.WRITE_WITHOUT_RESPONSE), emptyList()),
        )))
        val maximums = mutableMapOf(BleWriteMode.WITH_RESPONSE to 20, BleWriteMode.WITHOUT_RESPONSE to 40)
        val events = listOf(PRIMARY, SECONDARY).associateWith { MutableSharedFlow<ByteArray>(extraBufferCapacity = 1) }
        var readResult = byteArrayOf(0x01)
        var readOperation: (suspend () -> ByteArray)? = null
        var writeOperation: (suspend () -> Unit)? = null
        var discoveryOperation: (suspend () -> List<BleService>)? = null
        var notificationReady: suspend () -> Unit = {}
        var connectCalls = 0
        var disconnectCalls = 0
        var closeCalls = 0
        var discoveryCalls = 0
        var readCalls = 0
        var notificationStarts = 0
        var activeNotifications = 0
        data class Write(val id: BleCharacteristicId, val bytes: ByteArray, val mode: BleWriteMode)
        val writes = mutableListOf<Write>()
        override suspend fun connect() { connectCalls++; status.value = BleConnectionState.CONNECTED }
        override suspend fun disconnect() { disconnectCalls++; status.value = BleConnectionState.DISCONNECTED }
        override fun close() { closeCalls++ }
        override suspend fun discoverServices(): List<BleService> { discoveryCalls++; return discoveryOperation?.invoke() ?: services }
        override suspend fun read(characteristic: BleCharacteristicId): ByteArray { readCalls++; return readOperation?.invoke() ?: readResult }
        override suspend fun maximumWriteLength(mode: BleWriteMode) = maximums.getValue(mode)
        override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) {
            writes += Write(characteristic, value.copyOf(), mode)
            writeOperation?.invoke()
        }
        override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = flow {
            notificationStarts++
            activeNotifications++
            try {
                notificationReady()
                onReady()
                emitAll(events.getValue(characteristic))
            } finally { activeNotifications-- }
        }
    }

    private companion object {
        const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val MISSING_UUID = "0000ffff-0000-1000-8000-00805f9b34fb"
        val PRIMARY = BleCharacteristicId(SERVICE_UUID, "0000ffe1-0000-1000-8000-00805f9b34fb")
        val SECONDARY = BleCharacteristicId(SERVICE_UUID, "0000ffe2-0000-1000-8000-00805f9b34fb")
        val WRITE_ONLY = BleCharacteristicId(SERVICE_UUID, "0000ffe3-0000-1000-8000-00805f9b34fb")
    }
}
