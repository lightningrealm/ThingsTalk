@file:OptIn(com.juul.kable.ExperimentalKableApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.data.ble

import com.juul.kable.*
import com.lr.thingstalk.domain.ble.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.uuid.Uuid

class KableBleConnectionTest {
    private val serviceId = Uuid.parse("0000180f-0000-1000-8000-00805f9b34fb")
    private val charId = Uuid.parse("00002a19-0000-1000-8000-00805f9b34fb")
    private val id = BleCharacteristicId(serviceId.toString(), charId.toString())
    private fun service(properties: Int = 0x3e) = object : DiscoveredService {
        override val serviceUuid = serviceId
        override val characteristics = listOf(object : DiscoveredCharacteristic {
            override val serviceUuid = serviceId
            override val characteristicUuid = charId
            override val properties = Characteristic.Properties(properties)
            override val descriptors = listOf(object : DiscoveredDescriptor {
                override val serviceUuid = serviceId
                override val characteristicUuid = charId
                override val descriptorUuid = Uuid.parse("00002902-0000-1000-8000-00805f9b34fb")
            })
        })
    }

    @Test fun discoveryAndWriteValidation() = runTest {
        val fake = FakePeripheral(backgroundScope, listOf(service()))
        val connection = KableBleConnection(fake)
        assertFailsWith<BleException> { connection.discoverServices() }
        connection.connect()
        val characteristic = connection.discoverServices().single().characteristics.single()
        assertEquals(id, characteristic.id)
        assertEquals(1, characteristic.descriptors.size)
        assertTrue(BleProperty.INDICATE in characteristic.properties)
        connection.write(id, byteArrayOf(7))
        assertEquals(WriteType.WithResponse, fake.lastWriteType)
        assertFailsWith<BleException> { connection.write(id, ByteArray(21)) }
        fake.services.value = listOf(service(0x02))
        assertFailsWith<BleException> { connection.write(id, byteArrayOf(1)) }
        fake.services.value = listOf(service(), service())
        assertFailsWith<BleException> { connection.read(id) }
    }

    @Test fun cancellationIsNotConvertedToBleFailure() = runTest {
        val fake = FakePeripheral(backgroundScope, listOf(service()))
        val connection = KableBleConnection(fake)
        connection.connect()
        fake.readFailure = CancellationException("cancel")
        assertFailsWith<CancellationException> { connection.read(id) }
        fake.readFailure = IllegalStateException("transport")
        assertIs<IllegalStateException>(assertFailsWith<BleException> { connection.read(id) }.cause)
    }

    @Test fun notificationsSubscribeReconnectAndCancel() = runTest {
        val fake = FakePeripheral(backgroundScope, listOf(service()))
        val connection = KableBleConnection(fake)
        val received = mutableListOf<ByteArray>()
        var ready = 0
        val job = backgroundScope.launch { connection.notifications(id) { ready++ }.toList(received) }
        runCurrent()
        assertEquals(0, fake.subscribers)
        connection.connect()
        runCurrent()
        assertEquals(1, ready)
        fake.events.emit(byteArrayOf(9))
        runCurrent()
        assertContentEquals(byteArrayOf(9), received.single())
        connection.disconnect()
        runCurrent()
        assertEquals(0, fake.subscribers)
        connection.connect()
        runCurrent()
        assertEquals(2, ready)
        job.cancelAndJoin()
        assertEquals(0, fake.subscribers)
        val closingJob = backgroundScope.launch { connection.notifications(id).collect() }
        runCurrent()
        assertEquals(1, fake.subscribers)
        connection.close()
        runCurrent()
        assertTrue(closingJob.isCompleted)
        assertEquals(0, fake.subscribers)
        assertTrue(fake.closed)
        assertFailsWith<BleException> { connection.connect() }
    }

    @Test fun multipleLocalObserversShareOnePlatformSubscriptionAndEachReceiveReady() = runTest {
        val fake = FakePeripheral(backgroundScope, listOf(service()))
        val connection = KableBleConnection(fake)
        connection.connect()
        var firstReady = 0
        var secondReady = 0
        val firstValues = mutableListOf<ByteArray>()
        val secondValues = mutableListOf<ByteArray>()
        val first = backgroundScope.launch { connection.notifications(id) { firstReady++ }.toList(firstValues) }
        runCurrent()
        val second = backgroundScope.launch {
            connection.notifications(id.copy(serviceUuid = id.serviceUuid.uppercase())) { secondReady++ }.toList(secondValues)
        }
        runCurrent()
        assertEquals(1, fake.subscribers)
        assertEquals(1, firstReady)
        assertEquals(1, secondReady)
        fake.events.emit(byteArrayOf(7))
        runCurrent()
        assertContentEquals(byteArrayOf(7), firstValues.single())
        assertContentEquals(byteArrayOf(7), secondValues.single())
        first.cancelAndJoin()
        runCurrent()
        assertEquals(1, fake.subscribers)
        connection.disconnect()
        runCurrent()
        assertEquals(0, fake.subscribers)
        connection.connect()
        runCurrent()
        assertEquals(2, secondReady)
        assertEquals(1, fake.subscribers)
        second.cancelAndJoin()
        runCurrent()
        assertEquals(0, fake.subscribers)
    }
}

private class FakePeripheral(override val scope: CoroutineScope, profile: List<DiscoveredService>) : Peripheral {
    override val state = MutableStateFlow<State>(State.Disconnected())
    override val services = MutableStateFlow<List<DiscoveredService>?>(profile)
    override val identifier: Identifier get() = error("Unused in tests")
    override val name: String? = null
    var lastWriteType: WriteType? = null
    var readFailure: Exception? = null
    var subscribers = 0
    var closed = false
    val events = MutableSharedFlow<ByteArray>()
    override suspend fun connect(): CoroutineScope { state.value = State.Connected(scope); return scope }
    override suspend fun disconnect() { state.value = State.Disconnected() }
    override fun close() { closed = true; state.value = State.Disconnected() }
    override suspend fun maximumWriteValueLengthForType(writeType: WriteType) = 20
    override suspend fun rssi() = -60
    override suspend fun read(characteristic: Characteristic): ByteArray {
        readFailure?.let { throw it }; return byteArrayOf(1)
    }
    override suspend fun write(characteristic: Characteristic, data: ByteArray, writeType: WriteType) { lastWriteType = writeType }
    override suspend fun read(descriptor: Descriptor) = byteArrayOf(0)
    override suspend fun write(descriptor: Descriptor, data: ByteArray) = Unit
    // Mirrors Kable 0.45: a later platform subscriber does not get another ready callback.
    override fun observe(characteristic: Characteristic, onSubscription: suspend () -> Unit): Flow<ByteArray> =
        events.onSubscription { subscribers++; if (subscribers == 1) onSubscription() }.onCompletion { subscribers-- }
}
