package com.lr.thingstalk.data.mqtt

import com.lr.thingstalk.domain.mqtt.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface MqttProfileStorage {
    val passwordsPersisted: Boolean
    suspend fun load(): String?
    suspend fun save(source: String)
}
expect fun createMqttProfileStorage(): MqttProfileStorage

class StoredMqttProfileRepository(private val storage: MqttProfileStorage) : MqttProfileRepository {
    private val mutable = MutableStateFlow(MqttProfileArchive())
    override val state = mutable.asStateFlow()
    override val passwordsPersisted get() = storage.passwordsPersisted
    private val mutex = Mutex()
    private var loaded = false
    private val json = Json { encodeDefaults = true }
    override suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        val source = storage.load()
        require(source == null || source.length <= 512 * 1024) { "MQTT 配置存储过大" }
        val archive = source?.let { json.decodeFromString<MqttProfileArchive>(it) } ?: MqttProfileArchive()
        require(archive.version == 1 && archive.profiles.size <= 32 && archive.selected.size <= 1024) { "MQTT 配置存储版本或数量无效" }
        require(archive.profiles.map { it.id }.distinct().size == archive.profiles.size)
        archive.profiles.forEach { validate(it) }
        mutable.value = archive
        loaded = true
    }
    private fun validate(profile: MqttProfile) {
        require(profile.id.isNotBlank() && profile.id.length <= 128) { "配置 ID 无效" }
        require(profile.name.isNotBlank() && profile.name.length <= 80) { "配置名称需为 1–80 个字符" }
        require(profile.clientId.length <= 128 && profile.endpoint.length <= 2048 && profile.password.length <= 4096 && profile.username.length <= 256) { "连接字段超出长度限制" }
        MqttConnectionConfig(profile.endpoint, if (profile.automaticClientId) "validation-client" else profile.clientId,
            profile.username.takeIf { it.isNotEmpty() }, profile.password.takeIf { it.isNotEmpty() }).validated()
    }
    private suspend fun mutate(change: (MqttProfileArchive) -> MqttProfileArchive) = mutex.withLock {
        check(loaded) { "请先读取连接配置" }
        val next = change(mutable.value)
        require(next.profiles.size <= 32 && next.selected.size <= 1024) { "连接配置或关联数量已达上限" }
        // Web never persists a password. Retain the requirement so reload cannot silently
        // connect with different credentials. Mobile stores the archive in protected storage.
        val stored = if (passwordsPersisted) next else next.copy(profiles = next.profiles.map { it.copy(password = "") })
        val source = json.encodeToString(stored)
        require(source.length <= 512 * 1024) { "连接配置存储已达上限" }
        withContext(NonCancellable) {
            storage.save(source)
            mutable.value = next
        }
    }
    override suspend fun save(profile: MqttProfile, owner: String) {
        val normalized = profile.copy(name = profile.name.trim(), endpoint = profile.endpoint.trim(), clientId = profile.clientId.trim(),
            requiresPassword = profile.password.isNotEmpty() || profile.requiresPassword)
        validate(normalized)
        mutate { archive ->
            archive.copy(profiles = if (archive.profiles.any { it.id == normalized.id }) archive.profiles.map { if (it.id == normalized.id) normalized else it }
                else archive.profiles + normalized, selected = archive.selected + (owner to normalized.id))
        }
    }
    override suspend fun select(owner: String, id: String) = mutate {
        require(it.profiles.any { p -> p.id == id }) { "连接配置已删除，请重新选择" }
        it.copy(selected = it.selected + (owner to id))
    }
    override suspend fun delete(id: String) = mutate {
        it.copy(profiles = it.profiles.filterNot { p -> p.id == id }, selected = it.selected.filterValues { selected -> selected != id })
    }
}

/** Last-line Web storage filter; callers cannot accidentally persist credentials here. */
internal fun mqttProfileMetadataOnly(source: String): String {
    val archive = Json.decodeFromString<MqttProfileArchive>(source)
    val safe = archive.copy(profiles = archive.profiles.map {
        // Also reject credentials embedded in an address before persisting metadata.
        MqttConnectionConfig(it.endpoint, "validation-client").validated()
        it.copy(password = "", requiresPassword = it.requiresPassword || it.password.isNotEmpty())
    })
    return Json.encodeToString(safe)
}
