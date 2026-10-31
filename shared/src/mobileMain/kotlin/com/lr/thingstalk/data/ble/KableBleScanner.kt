package com.lr.thingstalk.data.ble

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Scanner
import com.lr.thingstalk.domain.ble.BleConnection
import com.lr.thingstalk.domain.ble.BleException
import com.lr.thingstalk.domain.ble.BleScanDevice
import com.lr.thingstalk.domain.ble.BleScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Native BLE objects are created only for an explicit scan or connection request. */
class KableBleScanner internal constructor(
    private val restoreConnection: (String) -> BleConnection? = ::restoreSavedBleConnection,
    private val scanSource: () -> Flow<Advertisement>,
) : BleScanner {
    constructor() : this(scanSource = { Scanner().advertisements })
    private val closed = MutableStateFlow(false)
    private val scanning = MutableStateFlow(false)
    private val advertisements = MutableStateFlow<Map<String, Advertisement>>(emptyMap())

    override fun scan(): Flow<BleScanDevice> = channelFlow {
        check(!closed.value) { "BLE scanner is closed" }
        check(scanning.compareAndSet(false, true)) { "BLE scan is already running" }
        try {
            val collection = launch {
                scanSource().collect { advertisement ->
                    val id = advertisement.identifier.toString()
                    advertisements.update { current ->
                        // Bound retained native objects even in a busy radio environment.
                        (current - id + (id to advertisement)).entries.toList().takeLast(512)
                            .associate { it.key to it.value }
                    }
                    send(BleScanDevice(id, advertisement.name, advertisement.rssi, advertisement.uuids.map { it.toString() }))
                }
            }
            val closeWatcher = launch {
                closed.first { it }
                collection.cancel()
            }
            try {
                collection.join()
            } finally {
                closeWatcher.cancel()
            }
        } finally {
            scanning.value = false
            if (closed.value) advertisements.value = emptyMap()
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        throw BleException("蓝牙扫描失败：${error.message ?: "系统未提供原因"}", error)
    }

    @OptIn(ObsoleteKableApi::class)
    override fun connection(deviceId: String): BleConnection {
        check(!closed.value) { "BLE scanner is closed" }
        val advertisement = advertisements.value[deviceId]
        if (advertisement == null) {
            return restoreConnection(deviceId)
                ?: throw BleException("系统未找到已保存的设备，请重新扫描并关联")
        }
        return KableBleConnection(Peripheral(advertisement) {
            // Kable 0.45 Apple workaround (#1016): CoreBluetooth callbacks may carry a
            // different characteristic object. Match service + characteristic UUID so reads
            // do not stall and Notify values reach their collector. Android ignores this flag.
            // KableBleConnection rejects ambiguous UUID paths before any operation.
            forceCharacteristicEqualityByUuid = true
        })
    }

    override fun close() {
        closed.value = true
        advertisements.value = emptyMap()
    }
}
