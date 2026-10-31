package com.lr.thingstalk.data.activity
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
private var preferences: SharedPreferences? = null
fun initializeActivityStorage(context: Context) { preferences = context.applicationContext.getSharedPreferences("thingstalk_activity", Context.MODE_PRIVATE) }
actual fun createActivityStorage(): ActivityStorage = object : ActivityStorage {
    override fun load() = checkNotNull(preferences).getString("archive", null)
    override suspend fun save(source: String) = withContext(Dispatchers.IO) { check(checkNotNull(preferences).edit().putString("archive", source).commit()) }
}
