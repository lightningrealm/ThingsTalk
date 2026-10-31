package com.lr.thingstalk.presentation.ble

import android.Manifest

internal fun bleRuntimePermissions(apiLevel: Int, forConnection: Boolean): List<String> = when {
    apiLevel >= 31 && forConnection -> listOf(Manifest.permission.BLUETOOTH_CONNECT)
    apiLevel >= 31 -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    forConnection -> emptyList() // Legacy BLUETOOTH is granted through the manifest.
    else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

internal fun requiresScanLocationService(apiLevel: Int, forConnection: Boolean): Boolean =
    apiLevel < 31 && !forConnection
