package com.lr.thingstalk

private object WebPlatform : Platform {
    override val name: String = "Web"
}

actual fun getPlatform(): Platform = WebPlatform

actual fun platformCapabilities() = PlatformCapabilities(ble = false, demoFeatures = false)
