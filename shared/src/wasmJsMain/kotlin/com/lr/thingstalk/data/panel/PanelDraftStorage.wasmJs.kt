package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.PanelDraftRepository
import kotlinx.browser.window

actual fun createPanelDraftRepository(): PanelDraftRepository = JsonPanelDraftRepository(object : PanelDraftStorage {
    override fun load(deviceId: String): String? = window.localStorage.getItem("thingstalk.draft.$deviceId")
    override fun save(deviceId: String, source: String?) {
        val key = "thingstalk.draft.$deviceId"
        if (source == null) window.localStorage.removeItem(key) else window.localStorage.setItem(key, source)
    }
})
