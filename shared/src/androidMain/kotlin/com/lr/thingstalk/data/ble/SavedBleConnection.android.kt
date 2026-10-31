package com.lr.thingstalk.data.ble

import com.juul.kable.Peripheral
import com.lr.thingstalk.domain.ble.BleConnection

internal actual fun restoreSavedBleConnection(deviceId: String): BleConnection? =
    savedAndroidBleConnection(deviceId) { address ->
        // Kable uses BluetoothAdapter.getRemoteDevice(address); no scan is started here.
        // Creating this handle does not prove that the peripheral is currently reachable.
        KableBleConnection(Peripheral(address))
    }

/** Validate before touching the Bluetooth stack; iOS UUIDs are not Android addresses. */
internal fun savedAndroidBleConnection(deviceId: String, create: (String) -> BleConnection): BleConnection? {
    val address = deviceId.uppercase()
    if (!Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").matches(address)) return null
    return create(address)
}
