package com.lr.thingstalk.data.mqtt

import com.lr.thingstalk.domain.mqtt.MqttConnectionConfig
import com.lr.thingstalk.domain.mqtt.MqttException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MqttValidationTest {
    @Test fun secureEndpointPreservesBrokerPath() {
        val result = MqttConnectionConfig(" wss://broker.example:9443/mqtt ", "things-1", "user", "secret").validated()
        assertEquals("wss://broker.example:9443/mqtt", result.url)
        assertEquals("user", result.username)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun rejectsUnsupportedOrAmbiguousConnectionAddresses() {
        listOf(
            "mqtt://broker.example:1883",
            "https://broker.example/mqtt",
            "broker.example",
            "wss://user:secret@broker.example/mqtt",
            "wss://broker.example/mqtt?token=secret",
            "wss://broker.example/mqtt#fragment",
            "wss://broker.example/my path",
            "ws://broker.example:0/mqtt",
            "ws://[::1]:0/mqtt",
            "ws://broker.example:/mqtt",
        ).forEachIndexed { index, address ->
            val diagnosticAddress = address.replace("user:secret@", "<credentials>@").substringBefore('?').substringBefore('#')
            val error = assertFailsWith<MqttException>("Case ${index + 1}: $diagnosticAddress") {
                MqttConnectionConfig(address, "test").validated()
            }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun implicitDefaultPortsAndIpv6EndpointsRemainValid() {
        listOf("ws://broker.example/mqtt", "wss://broker.example/mqtt", "ws://[::1]/mqtt", "ws://[::1]:18884/mqtt").forEach {
            MqttConnectionConfig(it, "test").validated()
        }
    }

    @Test fun requiresNonemptyClientIdAndValidMqttStrings() {
        listOf("", " ", "bad\u0000id", "unpaired\uD800", "x".repeat(65536)).forEach { id ->
            assertFailsWith<MqttException> { MqttConnectionConfig("wss://broker.example/mqtt", id).validated() }
        }
    }

    @Test fun supportsNormalAndSharedWildcardFilters() {
        listOf("#", "+", "house/+/temperature", "house/#", "/", "house//temperature", "\$share/readers/house/+", "中文/温度").forEach {
            validateTopic(it, filter = true)
        }
    }

    @Test fun rejectsWildcardsOutsideWholeLevelsAndMalformedSharedSubscriptions() {
        listOf("", "house/temp+", "house/#/temp", "house/##", "house/a#", "\$share//topic", "\$share/group/", "\$share/g+/topic").forEach {
            assertFailsWith<MqttException> { validateTopic(it, filter = true) }
        }
    }

    @Test fun publishAllowsBinaryPayloadTopicsButNeverWildcardsOrInvalidUtf8() {
        validateTopic("house/temperature", filter = false)
        listOf("house/+", "house/#", "bad\u0000name", "unpaired\uDC00", "你".repeat(21846)).forEach {
            assertFailsWith<MqttException> { validateTopic(it, filter = false) }
        }
    }
}
