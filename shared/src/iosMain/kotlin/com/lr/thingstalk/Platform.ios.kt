package com.lr.thingstalk

import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun platformCapabilities() = PlatformCapabilities(ble = true, backgroundBleSession = true)
