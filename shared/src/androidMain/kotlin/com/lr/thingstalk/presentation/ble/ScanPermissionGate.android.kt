package com.lr.thingstalk.presentation.ble

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun rememberBleScanAccess(forConnection: Boolean): BleScanAccess {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    val preferences = remember(context) { context.getSharedPreferences("ble_access", Context.MODE_PRIVATE) }
    val permissions = remember(forConnection) { bleRuntimePermissions(Build.VERSION.SDK_INT, forConnection).toTypedArray() }
    val requestKey = if (forConnection) "requested.connect" else "requested"
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }

    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { revision++ }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        // Bluetooth broadcasts can originate from the privileged Bluetooth process, not system UID.
        // Broadcast payloads are never trusted: the callback only re-reads actual system state.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    // Re-read permissions, radio and location whenever the app resumes or a system broadcast arrives.
    val condition = remember(context, activity, revision, forConnection) {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> AccessCondition.UNSUPPORTED
            activity == null -> AccessCondition.NO_ACTIVITY
            missing.isNotEmpty() -> {
                val asked = preferences.getBoolean(requestKey, false)
                if (asked && missing.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) AccessCondition.SETTINGS
                else AccessCondition.PERMISSION
            }
            !context.bluetoothEnabled() -> AccessCondition.BLUETOOTH_OFF
            requiresScanLocationService(Build.VERSION.SDK_INT, forConnection) && !context.locationEnabled() -> AccessCondition.LOCATION_OFF
            else -> AccessCondition.READY
        }
    }
    return BleScanAccess(
        canScan = !forConnection && condition == AccessCondition.READY,
        canConnect = condition == AccessCondition.READY,
        status = when (condition) {
            AccessCondition.READY -> if (forConnection) "蓝牙已就绪，可连接已保存设备" else "蓝牙已就绪，可扫描附近设备"
            AccessCondition.PERMISSION -> if (forConnection) "需要附近设备权限以连接已保存的蓝牙设备" else if (Build.VERSION.SDK_INT >= 31) "需要附近设备权限以扫描和连接蓝牙设备" else "此 Android 版本需要位置权限才能扫描蓝牙设备"
            AccessCondition.SETTINGS -> if (forConnection) "蓝牙连接权限未开启，请在应用设置中允许权限" else "蓝牙扫描权限未开启，请在应用设置中允许权限"
            AccessCondition.BLUETOOTH_OFF -> "蓝牙已关闭，请先打开蓝牙"
            AccessCondition.LOCATION_OFF -> "此 Android 版本还需要开启系统定位服务才能扫描"
            AccessCondition.UNSUPPORTED -> "此设备不支持低功耗蓝牙"
            AccessCondition.NO_ACTIVITY -> "请在应用内打开扫描页面"
        },
        actionLabel = when (condition) {
            AccessCondition.PERMISSION -> "授予权限"
            AccessCondition.SETTINGS -> "打开应用设置"
            AccessCondition.BLUETOOTH_OFF -> "打开蓝牙"
            AccessCondition.LOCATION_OFF -> "打开定位设置"
            else -> null
        },
        requestAccess = {
            when (condition) {
                AccessCondition.PERMISSION -> {
                    preferences.edit().putBoolean(requestKey, true).apply()
                    permissionLauncher.launch(permissions)
                }
                AccessCondition.SETTINGS -> settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                AccessCondition.BLUETOOTH_OFF -> settingsLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                AccessCondition.LOCATION_OFF -> settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                else -> Unit
            }
        },
    )
}

private enum class AccessCondition { READY, PERMISSION, SETTINGS, BLUETOOTH_OFF, LOCATION_OFF, UNSUPPORTED, NO_ACTIVITY }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.bluetoothEnabled(): Boolean = try {
    getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
} catch (_: SecurityException) { false }

private fun Context.locationEnabled(): Boolean {
    val manager = getSystemService(LocationManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
    else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}
