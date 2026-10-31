package com.lr.thingstalk.data.ble

import com.juul.kable.*
import com.lr.thingstalk.domain.ble.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlin.uuid.Uuid

/** Takes ownership of peripheral. Construct in the composition root from a Kable scan result. */
@OptIn(ExperimentalCoroutinesApi::class)
class KableBleConnection(private val peripheral: Peripheral) : BleConnection {
    private val closed = MutableStateFlow(false)
    private val activeStates = combine(peripheral.state, closed) { state, isClosed -> state to isClosed }
        .transformWhile { (state, isClosed) ->
            emit(if (isClosed) State.Disconnected() else state)
            !isClosed
        }
    private val sharedNotifications = SharedBleNotifications(peripheral.scope) { id, onReady ->
        flow {
            val target = resolve(id)
            require(target.properties.notify || target.properties.indicate) { "Characteristic does not support notifications or indications" }
            emitAll(peripheral.observe(target, onReady).map { it.copyOf() })
        }
    }

    override val state: Flow<BleConnectionState> = activeStates.map {
        when (it) {
            is State.Connected -> BleConnectionState.CONNECTED
            is State.Connecting -> BleConnectionState.CONNECTING
            is State.Disconnecting -> BleConnectionState.DISCONNECTING
            is State.Disconnected -> BleConnectionState.DISCONNECTED
        }
    }.distinctUntilChanged()

    override suspend fun connect() = operation("connect") { peripheral.connect(); Unit }
    override suspend fun disconnect() = operation("disconnect") { peripheral.disconnect() }
    override fun close() {
        closed.value = true
        peripheral.close()
    }

    override suspend fun discoverServices(): List<BleService> = operation("discover services") {
        services().map { service ->
            BleService(service.serviceUuid.toString(), service.characteristics.map { characteristic ->
                BleCharacteristic(
                    BleCharacteristicId(service.serviceUuid.toString(), characteristic.characteristicUuid.toString()),
                    buildSet {
                        if (characteristic.properties.read) add(BleProperty.READ)
                        if (characteristic.properties.write) add(BleProperty.WRITE)
                        if (characteristic.properties.writeWithoutResponse) add(BleProperty.WRITE_WITHOUT_RESPONSE)
                        if (characteristic.properties.notify) add(BleProperty.NOTIFY)
                        if (characteristic.properties.indicate) add(BleProperty.INDICATE)
                    },
                    characteristic.descriptors.map { BleDescriptor(it.descriptorUuid.toString()) },
                )
            })
        }
    }

    override suspend fun read(characteristic: BleCharacteristicId): ByteArray = operation("read") {
        val target = resolve(characteristic)
        require(target.properties.read) { "Characteristic does not support read" }
        peripheral.read(target).copyOf()
    }

    override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) {
        // Copy before suspension: callers may reuse their buffers after this operation.
        val payload = value.copyOf()
        operation("write") {
            val target = resolve(characteristic)
            require(if (mode == BleWriteMode.WITH_RESPONSE) target.properties.write else target.properties.writeWithoutResponse) {
                "Characteristic does not support $mode"
            }
            require(payload.size <= peripheral.maximumWriteValueLengthForType(mode.kable())) { "Payload exceeds negotiated write length" }
            peripheral.write(target, payload, mode.kable())
        }
    }

    override suspend fun maximumWriteLength(mode: BleWriteMode): Int = operation("maximum write length") {
        services() // Require an established connection.
        peripheral.maximumWriteValueLengthForType(mode.kable())
    }

    override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> =
        activeStates.flatMapLatest { current ->
            if (current !is State.Connected) emptyFlow() else flow {
                val target = resolve(characteristic)
                require(target.properties.notify || target.properties.indicate) { "Characteristic does not support notifications or indications" }
                val canonicalId = BleCharacteristicId(target.serviceUuid.toString(), target.characteristicUuid.toString())
                emitAll(sharedNotifications.observe(current, canonicalId, onReady))
            }
        }.buffer(0).catch { error ->
            if (error is CancellationException) throw error
            throw BleException("BLE notifications failed", error)
        }

    private fun services(): List<DiscoveredService> {
        check(peripheral.state.value is State.Connected) { "BLE is not connected" }
        return checkNotNull(peripheral.services.value) { "GATT services are not available" }
    }

    private fun resolve(id: BleCharacteristicId): DiscoveredCharacteristic {
        val serviceUuid = Uuid.parse(id.serviceUuid)
        val characteristicUuid = Uuid.parse(id.characteristicUuid)
        val matchingServices = services().filter { it.serviceUuid == serviceUuid }
        require(matchingServices.size == 1) { "Service is missing or UUID is ambiguous: $serviceUuid" }
        val matching = matchingServices.single().characteristics.filter { it.characteristicUuid == characteristicUuid }
        require(matching.size == 1) { "Characteristic is missing or UUID is ambiguous: $characteristicUuid" }
        return matching.single()
    }

    private fun BleWriteMode.kable() = when (this) {
        BleWriteMode.WITH_RESPONSE -> WriteType.WithResponse
        BleWriteMode.WITHOUT_RESPONSE -> WriteType.WithoutResponse
    }

    private suspend fun <T> operation(name: String, block: suspend () -> T): T = try {
        check(!closed.value) { "BLE connection is closed" }
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw BleException("BLE $name failed: ${error.message}", error)
    }
}
