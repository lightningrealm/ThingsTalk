package com.lr.thingstalk.domain.mqtt

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class MqttProfile(
    val id: String,
    val name: String,
    val endpoint: String = "",
    val automaticClientId: Boolean = true,
    val clientId: String = "",
    val username: String = "",
    val password: String = "",
    val requiresPassword: Boolean = false,
) {
    override fun toString() = "MqttProfile(id=$id, credentials=redacted)"
    fun connectionConfig(): MqttConnectionConfig {
        if (requiresPassword && password.isEmpty()) throw MqttException("此配置需要补充密码，请进入连接配置编辑")
        return MqttConnectionConfig(endpoint,
            if (automaticClientId) "thingstalk-${Uuid.random()}" else clientId,
            username.takeIf { it.isNotEmpty() }, password.takeIf { it.isNotEmpty() })
    }
}

@Serializable
data class MqttProfileArchive(
    val version: Int = 1,
    val profiles: List<MqttProfile> = emptyList(),
    val selected: Map<String, String> = emptyMap(),
) {
    override fun toString() = "MqttProfileArchive(profiles=${profiles.size})"
    fun selectedProfile(owner: String) = profiles.firstOrNull { it.id == selected[owner] }
}

interface MqttProfileRepository {
    val state: StateFlow<MqttProfileArchive>
    val passwordsPersisted: Boolean
    suspend fun load()
    suspend fun save(profile: MqttProfile, owner: String)
    suspend fun delete(id: String)
    suspend fun select(owner: String, id: String)
}
