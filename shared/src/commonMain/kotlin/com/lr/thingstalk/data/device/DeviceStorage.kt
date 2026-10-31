package com.lr.thingstalk.data.device

import com.lr.thingstalk.domain.device.DeviceRepository

/** Stores the complete versioned archive. Failures must throw; they must never appear as an empty store. */
interface DeviceStorage {
    fun load(): String?
    fun save(source: String)
}

/** Reads and validates saved devices. Callers must present load failures rather than silently resetting. */
expect fun createDeviceRepository(): DeviceRepository
