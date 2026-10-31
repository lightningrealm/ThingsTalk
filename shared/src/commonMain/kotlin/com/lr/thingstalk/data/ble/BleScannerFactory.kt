package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.BleScanner

/** Only call on platforms exposing the BLE capability. */
expect fun createBleScanner(): BleScanner
