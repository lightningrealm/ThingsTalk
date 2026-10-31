package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.PanelDraftRepository
import platform.Foundation.NSUserDefaults

actual fun createPanelDraftRepository(): PanelDraftRepository = JsonPanelDraftRepository(
    object : PanelDraftStorage {
        private val defaults = NSUserDefaults.standardUserDefaults
        private fun key(deviceId: String) = "com.lr.thingstalk.panel.draft.$deviceId"
        override fun load(deviceId: String): String? {
            val value = defaults.objectForKey(key(deviceId)) ?: return null
            return value as? String ?: error("草稿存储格式无效，原始内容已保留")
        }
        override fun save(deviceId: String, source: String?) {
            if (source == null) defaults.removeObjectForKey(key(deviceId))
            else defaults.setObject(source, forKey = key(deviceId))
            check(defaults.stringForKey(key(deviceId)) == source) { "草稿保存失败，请重试" }
        }
    },
)
