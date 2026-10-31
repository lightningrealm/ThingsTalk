package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.BleConnection

/** Null rejects an invalid/unrestorable identity; native lookup may be deferred to connect(). Never match by name. */
internal expect fun restoreSavedBleConnection(deviceId: String): BleConnection?
