package com.lr.thingstalk.domain.ble

import kotlinx.coroutines.flow.Flow

/** Platform-local identity: Android address or iOS identifier, not a portable device identity. */
data class BleScanDevice(
    val id: String,
    val name: String?,
    val rssi: Int,
    /** Advertised services only; connect and discoverServices() to obtain the GATT tree. */
    val serviceUuids: List<String>,
)

interface BleScanner {
    /** Cold stream of advertisements, including RSSI updates. Cancelling collection stops scanning. */
    fun scan(): Flow<BleScanDevice>

    /** Creates a connection from a scan result or a platform-restorable saved identity; caller owns it. */
    fun connection(deviceId: String): BleConnection

    /** Stops scanning and discards advertisements. Already created connections remain caller-owned. */
    fun close()
}
