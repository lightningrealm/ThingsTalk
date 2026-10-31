package com.lr.thingstalk.data.device

import com.lr.thingstalk.domain.device.DeviceRepository
import kotlinx.browser.window

actual fun createDeviceRepository(): DeviceRepository = JsonDeviceRepository(object : DeviceStorage {
    override fun load(): String? = window.localStorage.getItem("thingstalk.devices.v1")
    override fun save(source: String) { window.localStorage.setItem("thingstalk.devices.v1", source) }
})
