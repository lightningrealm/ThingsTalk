package com.lr.thingstalk.di

import com.lr.thingstalk.data.mqtt.createMqttRepository
import com.lr.thingstalk.presentation.mqtt.MqttWorkbenchViewModel

fun createMqttWorkbenchViewModel(activity: com.lr.thingstalk.domain.activity.ActivityRepository? = null) = MqttWorkbenchViewModel({ scope ->
    val raw = createMqttRepository(scope)
    activity?.let { com.lr.thingstalk.data.activity.ActivityMqttRepository(raw, scope, it, "", { "MQTT 工作台" }) } ?: raw
})

fun createMqttProfilesViewModel() = com.lr.thingstalk.presentation.mqtt.MqttProfilesViewModel(
    com.lr.thingstalk.data.mqtt.StoredMqttProfileRepository(com.lr.thingstalk.data.mqtt.createMqttProfileStorage()))
