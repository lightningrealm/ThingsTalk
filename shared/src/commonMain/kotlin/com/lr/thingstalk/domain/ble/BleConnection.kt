package com.lr.thingstalk.domain.ble

import kotlinx.coroutines.flow.Flow

/** UUIDs use canonical 128-bit strings. Duplicate UUID paths are rejected as ambiguous. */
data class BleCharacteristicId(val serviceUuid: String, val characteristicUuid: String)
data class BleDescriptor(val uuid: String)
data class BleCharacteristic(
    val id: BleCharacteristicId,
    val properties: Set<BleProperty>,
    val descriptors: List<BleDescriptor>,
)
data class BleService(val uuid: String, val characteristics: List<BleCharacteristic>)
enum class BleProperty { READ, WRITE, WRITE_WITHOUT_RESPONSE, NOTIFY, INDICATE }
enum class BleWriteMode { WITH_RESPONSE, WITHOUT_RESPONSE }
enum class BleConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }
class BleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One peripheral per instance. Permissions and Bluetooth availability are handled by the caller. */
interface BleConnection {
    val state: Flow<BleConnectionState>
    /** Connects and performs the platform's initial GATT discovery. */
    suspend fun connect()
    suspend fun disconnect()
    /** Current connection's discovered service/characteristic/descriptor tree; requires connection. */
    suspend fun discoverServices(): List<BleService>
    suspend fun read(characteristic: BleCharacteristicId): ByteArray
    /** No implicit retry or fragmentation. WITH_RESPONSE is a GATT acknowledgement, not business success. */
    suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode = BleWriteMode.WITH_RESPONSE)
    suspend fun maximumWriteLength(mode: BleWriteMode = BleWriteMode.WITH_RESPONSE): Int
    /**
     * Cold flow. Collection enables notify/indicate; cancellation removes this subscription.
     * Waits across disconnects and resumes after an explicit reconnect; does not reconnect itself.
     * onReady runs after subscription setup on each connection, so a request can safely be written there.
     * Errors terminate the collection. No replay/conflation is added; consumers must keep up.
     */
    fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit = {}): Flow<ByteArray>
    /** Terminal resource release; this instance must not be reused. */
    fun close()
}
