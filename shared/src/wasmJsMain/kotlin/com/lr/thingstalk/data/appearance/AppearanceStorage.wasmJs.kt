package com.lr.thingstalk.data.appearance

import kotlinx.browser.window
actual fun createAppearanceStorage(): AppearanceStorage = object : AppearanceStorage {
    override fun load() = window.localStorage.getItem("thingstalk.appearance.v1")
    override suspend fun save(source: String) { window.localStorage.setItem("thingstalk.appearance.v1", source) }
}
