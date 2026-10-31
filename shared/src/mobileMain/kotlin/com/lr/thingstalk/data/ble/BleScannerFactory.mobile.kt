package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.BleScanner

actual fun createBleScanner(): BleScanner = KableBleScanner()
