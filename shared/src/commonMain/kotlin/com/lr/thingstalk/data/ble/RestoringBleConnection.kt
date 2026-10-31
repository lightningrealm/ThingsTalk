package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Defers native identity restoration to connect(), with cancellation and explicit ownership. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RestoringBleConnection(private val openConnected: suspend () -> BleConnection) : BleConnection {
    private sealed interface Slot {
        data object Empty : Slot
        data object Closed : Slot
        data class Active(val connection: BleConnection) : Slot
    }
    private val slot = MutableStateFlow<Slot>(Slot.Empty)
    private val phase = MutableStateFlow(BleConnectionState.DISCONNECTED)
    private val pending = MutableStateFlow<Job?>(null)
    private val mutex = Mutex()
    override val state: Flow<BleConnectionState> = slot.flatMapLatest {
        when (it) {
            Slot.Closed -> flowOf(BleConnectionState.DISCONNECTED)
            Slot.Empty -> phase
            is Slot.Active -> it.connection.state
        }
    }.distinctUntilChanged()

    override suspend fun connect() = mutex.withLock {
        val previous = slot.getAndUpdate { if (it == Slot.Closed) it else Slot.Empty }
        check(previous != Slot.Closed) { "BLE connection is closed" }
        (previous as? Slot.Active)?.connection?.close()
        phase.value = BleConnectionState.CONNECTING
        try {
            coroutineScope {
                pending.value = currentCoroutineContext().job
                var candidate: BleConnection? = null
                try {
                    check(slot.value != Slot.Closed) { "BLE connection is closed" }
                    candidate = openConnected()
                    currentCoroutineContext().ensureActive()
                    check(slot.compareAndSet(Slot.Empty, Slot.Active(candidate))) { "BLE connection is closed" }
                    candidate = null // Ownership transfers only after cancellation/close checks.
                } finally { candidate?.close(); pending.value = null }
            }
        } finally { phase.value = BleConnectionState.DISCONNECTED }
    }

    override suspend fun disconnect() {
        pending.value?.cancelAndJoin()
        (slot.value as? Slot.Active)?.connection?.disconnect()
        phase.value = BleConnectionState.DISCONNECTED
    }
    private fun active(): BleConnection {
        check(slot.value != Slot.Closed) { "BLE connection is closed" }
        return checkNotNull((slot.value as? Slot.Active)?.connection) { "BLE device has not been restored and connected" }
    }
    override suspend fun discoverServices() = active().discoverServices()
    override suspend fun read(characteristic: BleCharacteristicId) = active().read(characteristic)
    override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) = active().write(characteristic, value, mode)
    override suspend fun maximumWriteLength(mode: BleWriteMode) = active().maximumWriteLength(mode)
    override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> =
        slot.flatMapLatest { (it as? Slot.Active)?.connection?.notifications(characteristic, onReady) ?: emptyFlow() }
    override fun close() {
        val previous = slot.getAndUpdate { Slot.Closed }
        pending.value?.cancel()
        (previous as? Slot.Active)?.connection?.close()
    }
}
