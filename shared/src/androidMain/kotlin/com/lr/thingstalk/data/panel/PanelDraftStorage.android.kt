package com.lr.thingstalk.data.panel

import android.content.Context
import android.content.SharedPreferences
import com.lr.thingstalk.domain.panel.PanelDraftRepository

private var draftPreferences: SharedPreferences? = null

fun initializePanelDraftStorage(context: Context) {
    draftPreferences = context.applicationContext.getSharedPreferences("thingstalk_panel_drafts", Context.MODE_PRIVATE)
}

actual fun createPanelDraftRepository(): PanelDraftRepository = JsonPanelDraftRepository(
    object : PanelDraftStorage {
        private val preferences = checkNotNull(draftPreferences) { "请先初始化 Android 草稿存储" }
        override fun load(deviceId: String): String? = preferences.getString(deviceId, null)
        override fun save(deviceId: String, source: String?) {
            val editor = preferences.edit()
            if (source == null) editor.remove(deviceId) else editor.putString(deviceId, source)
            check(editor.commit()) { "草稿保存失败，请检查存储空间后重试" }
        }
    },
)
