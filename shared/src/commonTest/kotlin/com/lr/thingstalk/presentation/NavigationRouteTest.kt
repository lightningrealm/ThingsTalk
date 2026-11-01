package com.lr.thingstalk.presentation

import com.lr.thingstalk.presentation.navigation.Route
import com.lr.thingstalk.presentation.navigation.Screen
import kotlinx.serialization.json.Json
import kotlin.test.*

class NavigationRouteTest {
    @Test fun allDestinationsRoundTripWithIds() {
        Screen.entries.forEach { screen ->
            val route = Route(screen, "设备-01", "0000180f-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb")
            assertEquals(route, Json.decodeFromString<Route>(Json.encodeToString(route)))
        }
    }
    @Test fun retiredDestinationsRestoreAsUnavailable() {
        for (name in listOf("DEMO_DEVICE", "DEMO_DESCRIPTION", "HISTORY", "COMMANDS", "COMMAND", "BLE", "SERVICES", "CHARACTERISTIC", "LOGS")) {
            val route = Json.decodeFromString<Route>("""{"screen":"$name","id":"old-device"}""")
            assertEquals(Screen.UNAVAILABLE, route.screen)
            assertEquals("old-device", route.id)
        }
    }
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun savedStateEnumSlotsRemainCompatible() {
        val slots = mapOf(0 to Screen.DEVICES, 1 to Screen.DEVICE, 2 to Screen.PANEL_EDITOR,
            11 to Screen.NEARBY, 17 to Screen.GATT_SERVICES, 21 to Screen.MQTT,
            24 to Screen.EVENTS, 31 to Screen.MORE, 37 to Screen.SETTINGS, 38 to Screen.ABOUT)
        for ((index, screen) in slots) {
            val decoder = object : kotlinx.serialization.encoding.AbstractDecoder() {
                override val serializersModule = kotlinx.serialization.modules.EmptySerializersModule()
                override fun decodeElementIndex(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) = error("Not a structure")
                override fun decodeEnum(enumDescriptor: kotlinx.serialization.descriptors.SerialDescriptor) = index
            }
            assertEquals(screen, com.lr.thingstalk.presentation.navigation.ScreenSerializer.deserialize(decoder))
            val encoder = object : kotlinx.serialization.encoding.AbstractEncoder() {
                override val serializersModule = kotlinx.serialization.modules.EmptySerializersModule()
                override fun encodeEnum(enumDescriptor: kotlinx.serialization.descriptors.SerialDescriptor, actual: Int) {
                    assertEquals(index, actual)
                }
            }
            com.lr.thingstalk.presentation.navigation.ScreenSerializer.serialize(encoder, screen)
        }
    }
}
