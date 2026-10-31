package com.lr.thingstalk.presentation.ble

import androidx.compose.runtime.Composable

@Composable
actual fun rememberBleScanAccess(forConnection: Boolean): BleScanAccess = BleScanAccess(
    canScan = false,
    status = "Web 当前仅支持 MQTT，未启用蓝牙功能",
)
