@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.panel

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.data.panel.BlePanelOperations
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.PanelNotificationStatus
import com.lr.thingstalk.presentation.panel.PanelViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class PanelNotificationHealthTest {
    @Test fun malformedJsonAndMissingFieldsDoNotEndTheNotificationStream() = runTest {
        val connection = HealthConnection()
        connection.connect()
        val definition = healthPanel(json = true)
        val events = mutableListOf<PanelObservation>()
        val job = backgroundScope.launch { BlePanelOperations(connection).observeEvents(definition, "value").toList(events) }
        runCurrent()
        assertEquals(listOf<PanelObservation>(PanelObservation.Ready), events)
        connection.packet("{broken".encodeToByteArray())
        connection.packet("{}".encodeToByteArray())
        connection.packet("{\"reading\":42}".encodeToByteArray())
        runCurrent()
        assertEquals(4, events.size)
        assertIs<PanelObservation.InvalidPayload>(events[1])
        assertIs<PanelObservation.InvalidPayload>(events[2])
        assertEquals(PanelObservation.Value("42"), events[3])
        assertTrue(job.isActive)
        assertEquals(1, connection.attempts)
        job.cancelAndJoin()
        assertEquals(0, connection.active)
    }

    @Test fun waitingInvalidAndHealthyStatesReflectActualValidNotifications() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val readyGate = CompletableDeferred<Unit>()
        try {
            var now = 100L
            val connection = HealthConnection().apply { this.readyGate = readyGate }
            val vm = PanelViewModel(healthPanel(), BlePanelOperations(connection), PanelJson, connection, nowEpochMillis = { now })
            store.put("panel", vm)
            vm.connect(); runCurrent()
            assertEquals(PanelNotificationStatus.STARTING, vm.state.value.notificationStates.getValue("value").status)
            readyGate.complete(Unit); runCurrent()
            assertEquals(PanelNotificationStatus.WAITING, vm.state.value.notificationStates.getValue("value").status)
            assertTrue(vm.state.value.values.isEmpty())
            connection.packet(byteArrayOf(0xBB.toByte(), 42, 0x55)); runCurrent()
            val rejected = vm.state.value.notificationStates.getValue("value")
            assertEquals(PanelNotificationStatus.WAITING, rejected.status)
            assertEquals(1L, rejected.invalidPayloadCount)
            assertNull(rejected.lastValueAt)
            assertTrue(vm.state.value.values.isEmpty())
            now = 200L
            connection.packet(byteArrayOf(0xAA.toByte(), 42, 0x55)); runCurrent()
            assertEquals("42", vm.state.value.values["value"])
            assertEquals(200L, vm.state.value.valueUpdatedAt["value"])
            val healthy = vm.state.value.notificationStates.getValue("value")
            assertEquals(PanelNotificationStatus.RECEIVING, healthy.status)
            assertEquals(200L, healthy.lastValueAt)
            assertEquals(1L, healthy.invalidPayloadCount)
            assertNull(healthy.lastDecodeError)
            now = 300L
            connection.packet(byteArrayOf(0xAA.toByte(), 43)); runCurrent()
            assertEquals("42", vm.state.value.values["value"])
            assertEquals(200L, vm.state.value.valueUpdatedAt["value"])
            assertEquals(2L, vm.state.value.notificationStates.getValue("value").invalidPayloadCount)
            assertEquals(1, connection.attempts)
        } finally { readyGate.complete(Unit); store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun transportFailureCanBeRetriedWithoutReplayingAReadOrWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val connection = HealthConnection()
            val vm = PanelViewModel(healthPanel(read = true), BlePanelOperations(connection), PanelJson, connection, nowEpochMillis = { 123L })
            store.put("panel", vm)
            vm.connect(); runCurrent()
            assertEquals(1, connection.reads)
            connection.packet(byteArrayOf(0xAA.toByte(), 44, 0x55)); runCurrent()
            connection.fail(IllegalStateException("transport failed")); runCurrent()
            val failed = vm.state.value.notificationStates.getValue("value")
            assertEquals(PanelNotificationStatus.FAILED, failed.status)
            assertEquals("transport failed", failed.failure)
            assertEquals("44", vm.state.value.values["value"])
            vm.retryNotifications("value")
            vm.retryNotifications("value")
            runCurrent()
            assertEquals(2, connection.attempts)
            assertEquals(PanelNotificationStatus.WAITING, vm.state.value.notificationStates.getValue("value").status)
            assertEquals(123L, vm.state.value.notificationStates.getValue("value").lastValueAt)
            assertEquals(1, connection.reads)
            assertEquals(0, connection.writes)
            connection.packet(byteArrayOf(0xAA.toByte(), 45, 0x55)); runCurrent()
            assertEquals("45", vm.state.value.values["value"])
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun disconnectAndReconnectResetNotificationHealthAndRejectOfflinePackets() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val connection = HealthConnection()
            val vm = PanelViewModel(healthPanel(), BlePanelOperations(connection), PanelJson, connection)
            store.put("panel", vm)
            vm.connect(); runCurrent()
            connection.packet(byteArrayOf(0)); runCurrent()
            assertEquals(1L, vm.state.value.notificationStates.getValue("value").invalidPayloadCount)
            vm.disconnect(); runCurrent()
            assertEquals(0, connection.active)
            assertEquals(PanelNotificationStatus.STOPPED, vm.state.value.notificationStates.getValue("value").status)
            connection.packet(byteArrayOf(0xAA.toByte(), 88, 0x55)); runCurrent()
            assertTrue(vm.state.value.values.isEmpty())
            assertTrue(vm.state.value.valueUpdatedAt.isEmpty())
            vm.connect(); runCurrent()
            assertEquals(PanelNotificationStatus.WAITING, vm.state.value.notificationStates.getValue("value").status)
            assertEquals(0L, vm.state.value.notificationStates.getValue("value").invalidPayloadCount)
            assertEquals(2, connection.attempts)
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun staleSubscriptionCleanupCannotMarkTheReplacementAsFailed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val cleanupGate = CompletableDeferred<Unit>()
        try {
            val old = HealthConnection()
            val oldOperations = object : PanelOperations by BlePanelOperations(old) {
                override fun observeEvents(definition: PanelDefinition, capabilityId: String): Flow<PanelObservation> = flow {
                    try { emit(PanelObservation.Ready); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanupGate.await(); error("late old failure") } }
                }
            }
            val vm = PanelViewModel(healthPanel(), oldOperations, PanelJson, old)
            store.put("panel", vm)
            vm.connect(); runCurrent()
            val replacement = HealthConnection()
            vm.attachConnection(replacement, BlePanelOperations(replacement), "replacement")
            vm.connect(); runCurrent()
            assertEquals(PanelNotificationStatus.WAITING, vm.state.value.notificationStates.getValue("value").status)
            cleanupGate.complete(Unit); runCurrent()
            assertEquals(PanelNotificationStatus.WAITING, vm.state.value.notificationStates.getValue("value").status)
            assertNull(vm.state.value.notificationStates.getValue("value").failure)
            replacement.packet(byteArrayOf(0xAA.toByte(), 51, 0x55)); runCurrent()
            assertEquals("51", vm.state.value.values["value"])
        } finally { cleanupGate.complete(Unit); store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun valuesOnlyAdaptersDoNotClaimSubscriptionReadinessBeforeReceiving() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val connection = HealthConnection()
            val values = MutableSharedFlow<String>()
            val operations = object : PanelOperations {
                override suspend fun read(definition: PanelDefinition, capabilityId: String) = "0"
                override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) = Unit
                override fun observe(definition: PanelDefinition, capabilityId: String) = values
            }
            val vm = PanelViewModel(healthPanel(), operations, PanelJson, connection)
            store.put("panel", vm)
            vm.connect(); runCurrent()
            assertEquals(PanelNotificationStatus.STARTING, vm.state.value.notificationStates.getValue("value").status)
            values.emit("7"); runCurrent()
            assertEquals(PanelNotificationStatus.RECEIVING, vm.state.value.notificationStates.getValue("value").status)
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun cancellationAndDownstreamExceptionsAreNotReportedAsBadPayloads() = runTest {
        val connection = HealthConnection()
        connection.connect()
        var invalid = 0
        val cancelledSource = object : BleConnection by connection {
            override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = flow {
                onReady()
                throw CancellationException("cancel source")
            }
        }
        assertFailsWith<CancellationException> {
            BlePanelOperations(cancelledSource).observeEvents(healthPanel(), "value").collect { if (it is PanelObservation.InvalidPayload) invalid++ }
        }
        val onePacket = object : BleConnection by connection {
            override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = flow {
                onReady(); emit(byteArrayOf(0xAA.toByte(), 42, 0x55))
            }
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            BlePanelOperations(onePacket).observeEvents(healthPanel(), "value").collect {
                if (it is PanelObservation.InvalidPayload) invalid++
                if (it is PanelObservation.Value) throw IllegalArgumentException("consumer failed")
            }
        }
        assertEquals("consumer failed", failure.message)
        assertEquals(0, invalid)
    }
}

private const val healthService = "0000fff0-0000-1000-8000-00805f9b34fb"
private const val healthCharacteristic = "0000fff1-0000-1000-8000-00805f9b34fb"

private fun healthPanel(json: Boolean = false, read: Boolean = false): PanelDefinition {
    val operation = BleOperationBinding(healthService, healthCharacteristic, payload = PayloadReference("status", "reading"))
    return PanelDefinition(schemaVersion = 3, title = "Health panel",
        capabilities = listOf(PanelCapability("value", "Value", CapabilityType.NUMBER, 0.0, 100.0)),
        widgets = listOf(PanelWidget("value-card", WidgetType.VALUE, "Value", "value")),
        bindings = listOf(PanelBinding("value", read = operation.takeIf { read }, notify = operation)),
        payloadFormats = listOf(PayloadFormat("status", "Status", encoding = if (json) PayloadEncoding.JSON else PayloadEncoding.BINARY,
            fields = listOf(PayloadField("reading")), prefixHex = if (json) "" else "AA", suffixHex = if (json) "" else "55")))
}

private class HealthConnection : BleConnection {
    private sealed interface Step {
        data class Packet(val bytes: ByteArray) : Step
        data class Failure(val error: Exception) : Step
    }
    override val state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    private val steps = MutableSharedFlow<Step>(extraBufferCapacity = 16)
    var readyGate: CompletableDeferred<Unit>? = null
    var attempts = 0
    var active = 0
    var reads = 0
    var writes = 0
    suspend fun packet(bytes: ByteArray) { steps.emit(Step.Packet(bytes)) }
    suspend fun fail(error: Exception) { steps.emit(Step.Failure(error)) }
    override suspend fun connect() { state.value = BleConnectionState.CONNECTED }
    override suspend fun disconnect() { state.value = BleConnectionState.DISCONNECTED }
    override suspend fun discoverServices() = listOf(BleService(healthService, listOf(
        BleCharacteristic(BleCharacteristicId(healthService, healthCharacteristic), BleProperty.entries.toSet(), emptyList()))))
    override suspend fun read(characteristic: BleCharacteristicId): ByteArray { reads++; return byteArrayOf(0xAA.toByte(), 12, 0x55) }
    override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) { writes++ }
    override suspend fun maximumWriteLength(mode: BleWriteMode) = 20
    override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = flow {
        attempts++; active++
        try {
            readyGate?.await()
            onReady()
            steps.collect { step -> when (step) {
                is Step.Packet -> emit(step.bytes)
                is Step.Failure -> throw step.error
            } }
        } finally { active-- }
    }
    override fun close() { state.value = BleConnectionState.DISCONNECTED }
}
