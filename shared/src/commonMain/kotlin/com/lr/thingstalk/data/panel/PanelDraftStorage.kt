package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.PanelDraftRecord
import com.lr.thingstalk.domain.panel.PanelDraftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface PanelDraftStorage {
    fun load(deviceId: String): String?
    /** Null deletes only this device's checkpoint. Failures must throw. */
    fun save(deviceId: String, source: String?)
}

expect fun createPanelDraftRepository(): PanelDraftRepository

/** Unfinished numeric input can contain NaN; it remains invalid for ordinary PanelJson saving. */
class JsonPanelDraftRepository(private val storage: PanelDraftStorage) : PanelDraftRepository {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; allowSpecialFloatingPointValues = true }

    override suspend fun load(deviceId: String): PanelDraftRecord? = mutex.withLock {
        withContext(Dispatchers.Default) {
            val source = storage.load(deviceId) ?: return@withContext null
            require(source.length <= MAX_DRAFT_CHARACTERS) { "草稿过大，原始内容已保留" }
            val archive = json.decodeFromString<DraftArchive>(source)
            require(archive.version == 1) { "无法读取此版本的草稿，原始内容已保留" }
            archive.draft
        }
    }

    override suspend fun save(deviceId: String, draft: PanelDraftRecord) = mutex.withLock {
        withContext(Dispatchers.Default + NonCancellable) {
            val source = json.encodeToString(DraftArchive(draft = draft))
            require(source.length <= MAX_DRAFT_CHARACTERS) { "草稿超过 2M 字符，请减少内容后重试" }
            storage.save(deviceId, source)
        }
    }

    override suspend fun delete(deviceId: String) = mutex.withLock {
        withContext(Dispatchers.Default + NonCancellable) { storage.save(deviceId, null) }
    }
}

private const val MAX_DRAFT_CHARACTERS = 2 * 1024 * 1024
@Serializable private data class DraftArchive(val version: Int = 1, val draft: PanelDraftRecord)
