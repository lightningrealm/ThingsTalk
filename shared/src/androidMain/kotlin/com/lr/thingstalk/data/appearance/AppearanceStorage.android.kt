package com.lr.thingstalk.data.appearance

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private var preferences: SharedPreferences? = null
fun initializeAppearanceStorage(context: Context) {
    preferences = context.applicationContext.getSharedPreferences("thingstalk_appearance", Context.MODE_PRIVATE)
}
actual fun createAppearanceStorage(): AppearanceStorage = object : AppearanceStorage {
    override fun load() = preferences?.getString("appearance", null)
    override suspend fun save(source: String) = withContext(Dispatchers.IO) {
        check(checkNotNull(preferences).edit().putString("appearance", source).commit()) { "外观保存失败" }
    }
}
