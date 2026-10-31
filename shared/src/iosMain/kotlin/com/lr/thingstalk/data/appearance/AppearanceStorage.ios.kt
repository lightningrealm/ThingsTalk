package com.lr.thingstalk.data.appearance

import platform.Foundation.NSUserDefaults
actual fun createAppearanceStorage(): AppearanceStorage = object : AppearanceStorage {
    private val key = "com.lr.thingstalk.appearance"
    override fun load() = NSUserDefaults.standardUserDefaults.stringForKey(key)
    override suspend fun save(source: String) { NSUserDefaults.standardUserDefaults.setObject(source, forKey = key) }
}
