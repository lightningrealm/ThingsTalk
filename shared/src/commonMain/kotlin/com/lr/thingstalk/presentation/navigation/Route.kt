package com.lr.thingstalk.presentation.navigation

import com.lr.thingstalk.presentation.i18n.*
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable(with = ScreenSerializer::class)
enum class Screen(private val sourceTitle: String) {
    DEVICES("设备"), DEVICE("设备详情"), PANEL_EDITOR("面板编辑"), DESCRIPTION("能力描述"),
    DEVELOPER_SETTINGS("开发者设置"), DEVICE_DEVELOPER("设备开发者设置"),
    NEARBY("附近设备"), SCAN_BINDING("关联蓝牙设备"),
    GATT_SERVICES("服务与特征"), GATT_CHARACTERISTIC("特征调试"), BLE_TRAFFIC("收发日志"), BLE_LOG_DEVICES("设备通信日志"),
    MQTT("MQTT"), MQTT_PROFILES("连接配置"), MQTT_PROFILE_EDITOR("编辑连接配置"),
    EVENTS("消息"), EVENT("事件详情"), SUBSCRIPTIONS("连接管理"), SUBSCRIPTION("连接详情"),
    ACTIVITY_RECORD("通信详情"), DATA_SETTINGS("数据管理"), LICENSES("开源组件"),
    MORE("我的"), PRESETS("面板模板"), PRESET("模板详情"), TRANSFER("导入与导出"),
    API("服务连接"), NOTIFICATIONS("消息偏好"), SETTINGS("应用设置"), ABOUT("关于语物"), UNAVAILABLE("页面已移除");

    val title: String get() = com.lr.thingstalk.presentation.i18n.diagnosticText(sourceTitle)
}

/** Stable saved-state slots: never remove or reorder old names, even after their pages are retired. */
@Serializable
@SerialName("com.lr.thingstalk.presentation.navigation.Screen")
private enum class SavedScreen {
    DEVICES, DEVICE, PANEL_EDITOR, DESCRIPTION, HISTORY, DEVELOPER_SETTINGS, DEVICE_DEVELOPER, DEMO_DEVICE, DEMO_DESCRIPTION, COMMANDS, COMMAND, NEARBY, SCAN_BINDING, BLE, SERVICES, CHARACTERISTIC, LOGS, GATT_SERVICES, GATT_CHARACTERISTIC, BLE_TRAFFIC, BLE_LOG_DEVICES, MQTT, MQTT_PROFILES, MQTT_PROFILE_EDITOR, EVENTS, EVENT, SUBSCRIPTIONS, SUBSCRIPTION, ACTIVITY_RECORD, DATA_SETTINGS, LICENSES, MORE, PRESETS, PRESET, TRANSFER, API, NOTIFICATIONS, SETTINGS, ABOUT, UNAVAILABLE
}

/** Retired back-stack entries render an unavailable page; live entries retain their original indices. */
object ScreenSerializer : KSerializer<Screen> {
    override val descriptor = SavedScreen.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Screen) = encoder.encodeEnum(descriptor, SavedScreen.valueOf(value.name).ordinal)
    override fun deserialize(decoder: Decoder): Screen {
        val name = SavedScreen.entries.getOrNull(decoder.decodeEnum(descriptor))?.name
        return Screen.entries.firstOrNull { it.name == name } ?: Screen.UNAVAILABLE
    }
}

@Serializable
data class Route(
    val screen: Screen,
    val id: String = "",
    val serviceUuid: String = "",
    val characteristicUuid: String = "",
    val profileId: String = "",
) : NavKey

val navigationConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) { subclass(Route::class, Route.serializer()) }
    }
}
