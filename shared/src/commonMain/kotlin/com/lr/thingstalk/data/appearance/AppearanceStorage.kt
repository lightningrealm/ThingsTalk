package com.lr.thingstalk.data.appearance

import com.lr.thingstalk.domain.appearance.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

interface AppearanceStorage {
    fun load(): String?
    suspend fun save(source: String)
}
expect fun createAppearanceStorage(): AppearanceStorage

class StoredAppearanceRepository(private val storage: AppearanceStorage) : AppearanceRepository {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    // A failed read falls back for display without overwriting the user's stored value.
    private val mutable = MutableStateFlow(runCatching { storage.load()?.let { json.decodeFromString<Appearance>(it) } }.getOrNull() ?: Appearance())
    override val appearance = mutable.asStateFlow()
    private val mutex = Mutex()
    override suspend fun save(value: Appearance) = mutex.withLock {
        storage.save(json.encodeToString(value))
        mutable.value = value
    }
}
