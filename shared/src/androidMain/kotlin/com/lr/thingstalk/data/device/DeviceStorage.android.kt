package com.lr.thingstalk.data.device

import android.content.Context
import android.content.SharedPreferences
import com.lr.thingstalk.domain.device.DeviceRepository

private var devicePreferences: SharedPreferences? = null

/** Initialize with application context before constructing the shared application. */
fun initializeDeviceStorage(context: Context) {
    com.lr.thingstalk.presentation.activity.initializeAppBuild(context)
    com.lr.thingstalk.data.activity.initializeActivityStorage(context)
    com.lr.thingstalk.data.mqtt.initializeMqttProfileStorage(context)
    com.lr.thingstalk.data.appearance.initializeAppearanceStorage(context)
    com.lr.thingstalk.data.panel.initializePanelDraftStorage(context)
    devicePreferences = context.applicationContext.getSharedPreferences("thingstalk_devices", Context.MODE_PRIVATE)
}

actual fun createDeviceRepository(): DeviceRepository {
    val preferences = checkNotNull(devicePreferences) { "请先初始化 Android 设备存储" }
    return JsonDeviceRepository(AndroidDeviceStorage(preferences))
}

private class AndroidDeviceStorage(private val preferences: SharedPreferences) : DeviceStorage {
    override fun load(): String? = preferences.getString("archive", null)

    override fun save(source: String) {
        check(preferences.edit().putString("archive", source).commit()) { "设备保存失败，请检查可用存储空间后重试" }
    }
}
