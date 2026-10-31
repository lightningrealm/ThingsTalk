package com.lr.thingstalk.presentation.activity
import platform.Foundation.NSBundle
actual fun appBuildLabel(): String {
    val bundle = NSBundle.mainBundle
    return "${bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") ?: "未知版本"} (${bundle.objectForInfoDictionaryKey("CFBundleVersion") ?: "未知构建"})"
}
