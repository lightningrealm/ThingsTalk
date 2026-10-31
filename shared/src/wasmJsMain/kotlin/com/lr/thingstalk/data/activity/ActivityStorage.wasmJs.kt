package com.lr.thingstalk.data.activity
import kotlinx.browser.window
actual fun createActivityStorage(): ActivityStorage = object : ActivityStorage {
    override fun load() = window.localStorage.getItem("thingstalk.activity.v1")
    override suspend fun save(source: String) { window.localStorage.setItem("thingstalk.activity.v1", source) }
}
