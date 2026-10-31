package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Web workspace uses MQTT; no Bluetooth platform objects are initialized. */
actual fun createBleScanner(): BleScanner = object : BleScanner {
    override fun scan(): Flow<BleScanDevice> = flow { error("Web 不支持 BLE") }
    override fun connection(deviceId: String): BleConnection = error("Web 不支持 BLE")
    override fun close() = Unit
}
