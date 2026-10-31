package com.lr.thingstalk

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/** Product capabilities, independent from the platform's display name. */
data class PlatformCapabilities(
    val ble: Boolean,
    val mqtt: Boolean = true,
    val demoFeatures: Boolean = true,
    /** Native BLE event processing is configured for an existing session in background. */
    val backgroundBleSession: Boolean = false,
)

expect fun platformCapabilities(): PlatformCapabilities
