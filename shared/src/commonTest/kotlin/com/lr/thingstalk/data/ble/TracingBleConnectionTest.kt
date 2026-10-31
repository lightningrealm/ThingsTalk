@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class TracingBleConnectionTest {
    private val id = BleCharacteristicId("0000180f-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb")

    @Test fun recordsRequestsBeforeTransportAndCompletionsAfterTransport() = runTest {
        val traffic = InMemoryBleTraffic(now = { 123L })
        val fake = TrafficFakeConnection(id)
        val connection = TracingBleConnection(fake, traffic, backgroundScope)
        fake.onRead = {
            assertEquals("read.request", traffic.entries.value.last().operation)
            byteArrayOf(0, 127, -1)
        }
        fake.onWrite = { _, _ -> assertEquals("write.request", traffic.entries.value.last().operation) }
        connection.connect()
        assertEquals(1, connection.discoverServices().size)
        assertContentEquals(byteArrayOf(0, 127, -1), connection.read(id))
        connection.write(id, byteArrayOf(4))
        connection.disconnect()
        assertEquals(
            listOf("connect.request", "connect.completed", "discover.request", "discover.completed", "read.request",
                "read.completed", "write.request", "write.gatt_acknowledged", "disconnect.request", "disconnect.completed"),
            traffic.entries.value.filter { it.operation != "connection.state" }.map { it.operation },
        )
        val received = traffic.entries.value.single { it.operation == "read.completed" }
        assertEquals("00 7F FF", received.hex)
        assertEquals(3, received.byteCount)
        assertEquals(123L, received.timestampMillis)
        assertEquals(BleTrafficDirection.RX, received.direction)
        assertEquals(id, received.characteristic)
        assertTrue(traffic.entries.value.single { it.operation == "discover.completed" }.detail.contains("1 descriptors"))
    }

    @Test fun failedWritesPreserveTheExceptionAndNeverRecordAcknowledgement() = runTest {
        val traffic = InMemoryBleTraffic()
        val fake = TrafficFakeConnection(id)
        val connection = TracingBleConnection(fake, traffic, backgroundScope)
        val failure = BleException("write rejected", IllegalStateException("not connected"))
        fake.onWrite = { _, _ -> throw failure }
        assertSame(failure, assertFailsWith<BleException> { connection.write(id, byteArrayOf(7)) })
        val entries = traffic.entries.value.filter { it.operation.startsWith("write.") }
        assertEquals(listOf("write.request", "write.failed"), entries.map { it.operation })
        assertEquals(BleTrafficDirection.TX, entries.first().direction)
        assertEquals(BleTrafficDirection.ERROR, entries.last().direction)
        assertTrue(entries.last().detail.contains("not connected"))
    }

    @Test fun noResponseWritesOnlyReportSubmission() = runTest {
        val traffic = InMemoryBleTraffic()
        val fake = TrafficFakeConnection(id)
        val connection = TracingBleConnection(fake, traffic, backgroundScope)
        connection.write(id, byteArrayOf(1), BleWriteMode.WITHOUT_RESPONSE)
        assertEquals(BleWriteMode.WITHOUT_RESPONSE, fake.lastWriteMode)
        assertEquals("write.sent", traffic.entries.value.last().operation)
        assertFalse(traffic.entries.value.any { it.operation == "write.gatt_acknowledged" })
    }

    @Test fun cancellationIsPropagatedWithoutAnErrorOrSuccessEntry() = runTest {
        val traffic = InMemoryBleTraffic()
        val fake = TrafficFakeConnection(id)
        val connection = TracingBleConnection(fake, traffic, backgroundScope)
        val cancelled = CancellationException("screen closed")
        fake.onRead = { throw cancelled }
        assertSame(cancelled, assertFailsWith<CancellationException> { connection.read(id) })
        assertEquals("read.cancelled", traffic.entries.value.last().operation)
        assertFalse(traffic.entries.value.any { it.direction == BleTrafficDirection.ERROR || it.operation == "read.completed" })
    }

    @Test fun ownsWriteAndReadBuffersAcrossSuspension() = runTest {
        val traffic = InMemoryBleTraffic()
        val fake = TrafficFakeConnection(id)
        val connection = TracingBleConnection(fake, traffic, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var sent: ByteArray? = null
        fake.onWrite = { bytes, _ -> gate.await(); sent = bytes.copyOf() }
        val original = byteArrayOf(1, 2)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { connection.write(id, original) }
        original[0] = 99
        gate.complete(Unit)
        job.join()
        assertContentEquals(byteArrayOf(1, 2), sent)
        assertEquals("01 02", traffic.entries.value.single { it.operation == "write.request" }.hex)
        val readBuffer = byteArrayOf(3)
        fake.onRead = { readBuffer }
        val result = connection.read(id)
        readBuffer[0] = 4
        assertContentEquals(byteArrayOf(3), result)
        result[0] = 5
        assertEquals("03", traffic.entries.value.single { it.operation == "read.completed" }.hex)
    }

    @Test fun notificationReadyPrecedesCallbackWriteAndCancellationReleasesSubscription() = runTest {
        val traffic = InMemoryBleTraffic()
        val fake = TrafficFakeConnection(id)
        val connection = TracingBleConnection(fake, traffic, backgroundScope)
        val received = mutableListOf<ByteArray>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.notifications(id) { connection.write(id, byteArrayOf(6)) }.toList(received)
        }
        assertEquals(1, fake.subscribers)
        val payload = byteArrayOf(8)
        fake.events.emit(payload)
        payload[0] = 9
        assertContentEquals(byteArrayOf(8), received.single())
        job.cancelAndJoin()
        assertEquals(0, fake.subscribers)
        assertEquals(
            listOf("notifications.subscribe", "notifications.ready", "write.request", "write.gatt_acknowledged",
                "notifications.value", "notifications.cancelled"),
            traffic.entries.value.filter { it.operation != "connection.state" }.map { it.operation },
        )
        val event = traffic.entries.value.single { it.operation == "notifications.value" }
        assertEquals("08", event.hex)
        assertEquals("Local subscription #1", event.detail)
        assertFalse(traffic.entries.value.any { it.direction == BleTrafficDirection.ERROR })
    }

    @Test fun notificationCallbackFailureTerminatesAndReleasesSubscription() = runTest {
        val traffic = InMemoryBleTraffic()
        val fake = TrafficFakeConnection(id)
        val connection = TracingBleConnection(fake, traffic, backgroundScope)
        val failure = IllegalStateException("command failed")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            connection.notifications(id) { throw failure }.collect()
        })
        assertEquals(0, fake.subscribers)
        assertEquals("notifications.failed", traffic.entries.value.last().operation)
        assertTrue(traffic.entries.value.last().detail.contains("Local subscription #1"))
    }

    @Test fun multipleNotificationConsumersAreIdentifiedWithoutChangingDelegateSemantics() = runTest {
        val traffic = InMemoryBleTraffic()
        val fake = TrafficFakeConnection(id)
        val connection = TracingBleConnection(fake, traffic, backgroundScope)
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { connection.notifications(id).collect() }
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { connection.notifications(id).collect() }
        assertEquals(2, fake.subscribers)
        fake.events.emit(byteArrayOf(10))
        val values = traffic.entries.value.filter { it.operation == "notifications.value" }
        assertEquals(setOf("Local subscription #1", "Local subscription #2"), values.map { it.detail }.toSet())
        first.cancelAndJoin()
        assertEquals(1, fake.subscribers)
        second.cancelAndJoin()
        assertEquals(0, fake.subscribers)
    }

    @Test fun stateLoggingIsIndependentOfUiCollectorsAndEndsOnClose() = runTest {
        val traffic = InMemoryBleTraffic()
        val fake = TrafficFakeConnection(id)
        val connection = TracingBleConnection(fake, traffic, backgroundScope)
        backgroundScope.launch { connection.state.collect() }
        backgroundScope.launch { connection.state.collect() }
        runCurrent()
        fake.state.value = BleConnectionState.CONNECTED
        runCurrent()
        assertEquals(1, traffic.entries.value.count { it.operation == "connection.state" && it.detail == "CONNECTED" })
        connection.close()
        assertTrue(fake.closed)
        runCurrent()
        val afterClose = traffic.entries.value.size
        fake.state.value = BleConnectionState.CONNECTING
        runCurrent()
        assertEquals(afterClose, traffic.entries.value.size)
    }

    @Test fun historyAndHexAreBoundedAndClearDoesNotReuseIds() {
        assertFailsWith<IllegalArgumentException> { InMemoryBleTraffic(limit = 0) }
        val traffic = InMemoryBleTraffic(limit = 2)
        traffic.record(BleTrafficDirection.SYSTEM, "first")
        traffic.record(BleTrafficDirection.RX, "large", bytes = ByteArray(600) { 0xab.toByte() })
        traffic.record(BleTrafficDirection.SYSTEM, "third")
        assertEquals(listOf("large", "third"), traffic.entries.value.map { it.operation })
        val large = traffic.entries.value.first()
        assertEquals(600, large.byteCount)
        assertTrue(large.hex.orEmpty().endsWith("… (+88 bytes)"))
        assertTrue(large.hex.orEmpty().length < 1_600)
        val lastId = traffic.entries.value.last().id
        traffic.clear()
        assertTrue(traffic.entries.value.isEmpty())
        traffic.record(BleTrafficDirection.SYSTEM, "after clear")
        assertTrue(traffic.entries.value.single().id > lastId)
    }
}

private class TrafficFakeConnection(id: BleCharacteristicId) : BleConnection {
    override val state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    private val profile = listOf(BleService(id.serviceUuid, listOf(BleCharacteristic(
        id, setOf(BleProperty.READ, BleProperty.WRITE, BleProperty.WRITE_WITHOUT_RESPONSE, BleProperty.NOTIFY),
        listOf(BleDescriptor("00002902-0000-1000-8000-00805f9b34fb")),
    ))))
    val events = MutableSharedFlow<ByteArray>()
    var onRead: suspend () -> ByteArray = { byteArrayOf(0) }
    var onWrite: suspend (ByteArray, BleWriteMode) -> Unit = { _, _ -> }
    var lastWriteMode: BleWriteMode? = null
    var subscribers = 0
    var closed = false

    override suspend fun connect() { state.value = BleConnectionState.CONNECTED }
    override suspend fun disconnect() { state.value = BleConnectionState.DISCONNECTED }
    override suspend fun discoverServices() = profile
    override suspend fun read(characteristic: BleCharacteristicId) = onRead()
    override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) {
        lastWriteMode = mode
        onWrite(value, mode)
    }
    override suspend fun maximumWriteLength(mode: BleWriteMode) = 20
    override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = flow {
        subscribers++
        try { onReady(); emitAll(events) } finally { subscribers-- }
    }
    override fun close() { closed = true; state.value = BleConnectionState.DISCONNECTED }
}
