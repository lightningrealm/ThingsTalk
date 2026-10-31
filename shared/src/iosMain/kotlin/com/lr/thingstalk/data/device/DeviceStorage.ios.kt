package com.lr.thingstalk.data.device

import com.lr.thingstalk.domain.device.DeviceRepository
import platform.Foundation.NSUserDefaults

actual fun createDeviceRepository(): DeviceRepository = JsonDeviceRepository(IosDeviceStorage())

private class IosDeviceStorage : DeviceStorage {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val key = "com.lr.thingstalk.devices.archive"

    override fun load(): String? {
        val value = defaults.objectForKey(key) ?: return null
        return value as? String ?: error("设备存储格式无效，原始内容已保留")
    }

    override fun save(source: String) {
        defaults.setObject(source, forKey = key)
    }
}
