package com.lr.thingstalk.presentation.ble

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberBleScanAccess(forConnection: Boolean): BleScanAccess {
    var state by remember { mutableStateOf<CBManagerState?>(null) }
    val monitor = remember { BluetoothAccessMonitor { state = it } }
    DisposableEffect(monitor) {
        // The static authorization query does not allocate a manager or show a permission dialog.
        // Once the user has made a choice, reopening a page can safely monitor the radio immediately.
        if (CBManager.authorization != CBManagerAuthorizationNotDetermined) monitor.start()
        onDispose { monitor.close() }
    }
    return BleScanAccess(
        canScan = state == CBManagerStatePoweredOn,
        isDetermined = state != null && state != CBManagerStateUnknown,
        status = when (state) {
            null -> "开始前请检查蓝牙权限与状态"
            CBManagerStatePoweredOn -> "蓝牙已就绪，可扫描附近设备"
            CBManagerStatePoweredOff -> "蓝牙已关闭，请在系统设置中打开蓝牙"
            CBManagerStateUnauthorized -> "蓝牙权限未开启，请在应用设置中允许蓝牙访问"
            CBManagerStateUnsupported -> "此设备不支持低功耗蓝牙"
            CBManagerStateResetting -> "蓝牙正在重新启动，请稍候"
            CBManagerStateUnknown -> "正在检查蓝牙状态"
            else -> "蓝牙暂不可用"
        },
        actionLabel = when (state) {
            null -> "检查蓝牙权限"
            CBManagerStateUnauthorized -> "打开应用设置"
            CBManagerStatePoweredOff -> "打开设置"
            else -> null
        },
        requestAccess = {
            when (state) {
                null -> monitor.start()
                CBManagerStateUnauthorized, CBManagerStatePoweredOff -> {
                    NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { url ->
                        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
                    }
                }
                else -> Unit
            }
        },
    )
}

/** Kable's old availability API is deprecated; this monitor only reads native state, never scans. */
@OptIn(ExperimentalForeignApi::class)
private class BluetoothAccessMonitor(private val onState: (CBManagerState) -> Unit) : NSObject(), CBCentralManagerDelegateProtocol {
    private var manager: CBCentralManager? = null

    fun start() {
        if (manager == null) {
            onState(CBManagerStateUnknown)
            manager = CBCentralManager(this, dispatch_get_main_queue(), mapOf(CBCentralManagerOptionShowPowerAlertKey to false))
        }
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) { onState(central.state) }

    fun close() {
        manager?.delegate = null
        manager = null
    }
}
