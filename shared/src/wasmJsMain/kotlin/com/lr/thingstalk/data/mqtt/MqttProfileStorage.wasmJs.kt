package com.lr.thingstalk.data.mqtt

import kotlinx.browser.window
actual fun createMqttProfileStorage(): MqttProfileStorage = object : MqttProfileStorage {
    override val passwordsPersisted = false
    override suspend fun load() = window.localStorage.getItem("thingstalk.mqtt.profiles.v1")
    override suspend fun save(source: String) {
        // Enforce the no-password rule at the final persistence boundary too.
        window.localStorage.setItem("thingstalk.mqtt.profiles.v1", mqttProfileMetadataOnly(source))
    }
}
