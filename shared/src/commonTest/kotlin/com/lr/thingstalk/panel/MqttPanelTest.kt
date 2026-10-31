@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.lr.thingstalk.panel

import com.lr.thingstalk.data.panel.*
import com.lr.thingstalk.domain.mqtt.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class MqttPanelTest {
    private fun panel() = PanelDefinition(schemaVersion = 4, title = "Lamp", transport = PanelTransport.MQTT,
        capabilities = listOf(PanelCapability("power", "Power", CapabilityType.BOOLEAN)),
        widgets = listOf(PanelWidget("switch", WidgetType.SWITCH, "Power", "power")),
        payloadFormats = listOf(PayloadFormat("state", "State", PayloadEncoding.JSON, listOf(PayloadField("power", CodecType.BOOLEAN)))),
        bindings = listOf(PanelBinding("power", publish = MqttOperationBinding("lamp/set", PayloadReference("state", "power")),
            subscribe = MqttOperationBinding("lamp/state", PayloadReference("state", "power")))))

    @Test fun configurationRoundTripsAndRejectsMixedOrAmbiguousBindings() {
        val definition = panel()
        assertTrue(validatePanel(definition).isEmpty())
        assertEquals(definition, PanelJson.decode(PanelJson.encode(definition)))
        assertTrue(validatePanel(definition.copy(schemaVersion = 3)).isNotEmpty())
        assertTrue(validatePanel(definition.copy(bindings = listOf(definition.bindings.single().copy(read = BleOperationBinding("", ""))))).isNotEmpty())
        assertTrue(validatePanel(definition.copy(bindings = listOf(definition.bindings.single().copy(subscribe = MqttOperationBinding("lamp/#"))))).isNotEmpty())
        assertTrue(validatePanel(definition.copy(bindings = listOf(definition.bindings.single().copy(publish = definition.bindings.single().publish!!.copy(retain = true))))).isNotEmpty())
        // BLE v3 exports must not acquire new fields that older strict parsers reject.
        val legacy = PanelJson.encode(PanelDefinition(schemaVersion = 3, title = "BLE"))
        assertFalse("transport" in legacy)
    }

    @Test fun receivesRetainedBeforeSubscribeReturnsAndRecoversAfterInvalidPayload() = runTest {
        val repository = FakeMqtt()
        val operations = MqttPanelOperations(repository)
        val events = mutableListOf<PanelObservation>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            operations.observeEvents(panel(), "power").toList(events)
        }
        runCurrent()
        assertTrue(events.any { it == PanelObservation.Value("true") })
        assertTrue(events.contains(PanelObservation.Ready))
        repository.packet("other/state", "{\"power\":false}")
        repository.packet("lamp/state", "not-json")
        repository.packet("lamp/state", "{\"power\":false}")
        runCurrent()
        assertEquals(1, events.count { it is PanelObservation.InvalidPayload })
        assertEquals(PanelObservation.Value("false"), events.last())
        job.cancelAndJoin()
        assertEquals(0, repository.published.size)
    }

    @Test fun publishesEncodedCommandWithoutInventingDeviceConfirmation() = runTest {
        val repository = FakeMqtt()
        val operations = MqttPanelOperations(repository)
        operations.write(panel(), "power", "false")
        val message = repository.published.single()
        assertEquals("lamp/set", message.topic)
        assertEquals("{\"power\":false}", message.payload.decodeToString())
        assertFalse(message.retained)
        assertFailsWith<IllegalArgumentException> { operations.write(panel(), "power", "oops") }
        assertEquals(1, repository.published.size)
    }

    @Test fun disconnectTerminatesObservationAndCancellationDoesNotPublish() = runTest {
        val repository = FakeMqtt()
        var failure: Throwable? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try { MqttPanelOperations(repository).observeEvents(panel(), "power").collect() }
            catch (error: IllegalStateException) { failure = error }
        }
        runCurrent()
        repository.state.value = MqttConnectionState(MqttConnectionStatus.DISCONNECTED)
        runCurrent()
        assertTrue(job.isCompleted)
        assertNotNull(failure)
        assertTrue(repository.published.isEmpty())
    }

    private class FakeMqtt : MqttRepository {
        override val state = MutableStateFlow(MqttConnectionState(MqttConnectionStatus.CONNECTED))
        override val subscriptions = MutableStateFlow<List<MqttSubscription>>(emptyList())
        override val messages = MutableSharedFlow<MqttMessage>()
        val published = mutableListOf<MqttMessage>()
        override suspend fun connect(config: MqttConnectionConfig) = Unit
        override suspend fun disconnect() { state.value = MqttConnectionState() }
        override suspend fun subscribe(topicFilter: String, qos: MqttQos) {
            packet(topicFilter, "{\"power\":true}")
            subscriptions.value = listOf(MqttSubscription(topicFilter, qos))
        }
        suspend fun packet(topic: String, text: String) { messages.emit(MqttMessage(topic, text.encodeToByteArray(), MqttQos.AT_MOST_ONCE, true)) }
        override suspend fun unsubscribe(topicFilter: String) = Unit
        override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean) { published += MqttMessage(topic, payload, qos, retain) }
        override fun close() = Unit
    }
}
