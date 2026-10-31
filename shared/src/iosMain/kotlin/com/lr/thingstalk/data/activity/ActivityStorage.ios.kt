package com.lr.thingstalk.data.activity
import platform.Foundation.NSUserDefaults
actual fun createActivityStorage(): ActivityStorage = object : ActivityStorage {
    override fun load() = NSUserDefaults.standardUserDefaults.stringForKey("thingstalk.activity.v1")
    override suspend fun save(source: String) { NSUserDefaults.standardUserDefaults.setObject(source, forKey = "thingstalk.activity.v1") }
}
