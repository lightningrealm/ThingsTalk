@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.presentation.mqtt

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.domain.mqtt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class MqttWorkbenchViewModelTest {
    @Test fun selectingProfileAppliesCredentialsAtomicallyWithoutReplacingLiveConnection() = withModel { model, repository ->
        val config = MqttConnectionConfig("wss://broker.example/mqtt", "assigned-client", "test-user", "test-secret")
        model.connect(config); runCurrent()
        assertEquals(config, repository.selectedConfig)
        model.connect(config.copy(username = "different")); runCurrent()
        assertEquals(config, repository.selectedConfig)
        assertEquals(1, repository.connectCalls)
    }

    @Test fun emptyClientIdDoesNotStartConnection() = withModel { model, repository ->
        model.setEndpoint("wss://broker.example/mqtt")
        model.setClientId("  ")
        model.connect(); runCurrent()
        assertEquals(0, repository.connectCalls)
        assertEquals("请输入 Client ID", model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun boundedHistoryRetainsNewestMessagesWithoutRetainingWholeLargePayloads() = withModel(maximumLogEntries = 3) { model, repository ->
        repeat(5) { index ->
            repository.messages.emit(MqttMessage("sensor/$index", ByteArray(5_000) { 65 }, MqttQos.AT_MOST_ONCE, false))
            runCurrent()
        }
        assertEquals(5L, model.state.value.receivedCount)
        assertEquals(listOf("sensor/4", "sensor/3", "sensor/2"), model.state.value.messages.map { it.topic })
        assertEquals(5_000, model.state.value.messages.first().byteCount)
        assertEquals(2_048, model.state.value.messages.first().text.length)
        assertTrue(model.state.value.messages.all { it.truncated })
        model.clearMessages()
        assertTrue(model.state.value.messages.isEmpty())
        assertEquals(0L, model.state.value.receivedCount)
    }

    @Test fun invalidHexDoesNotPublishAndValidBinaryRoundTripsExactly() = withModel { model, repository ->
        repository.state.value = MqttConnectionState(MqttConnectionStatus.CONNECTED)
        runCurrent()
        model.setPublishTopic("device/command")
        model.setPayloadFormat(MqttPayloadFormat.HEX)
        model.setPublishPayload("1 A0")
        model.publish(); runCurrent()
        assertNotNull(model.state.value.error)
        assertTrue(repository.published.isEmpty())
        assertTrue(model.state.value.messages.isEmpty())

        model.setPublishPayload("01 a0\nFF")
        model.setPublishQos(MqttQos.AT_LEAST_ONCE)
        model.setRetain(true)
        model.publish(); runCurrent()
        assertNull(model.state.value.error)
        assertContentEquals(byteArrayOf(1, 0xa0.toByte(), 0xff.toByte()), repository.published.single().payload)
        assertEquals(MqttQos.AT_LEAST_ONCE, repository.published.single().qos)
        assertTrue(repository.published.single().retained)
        assertEquals("01 A0 FF", model.state.value.messages.single().hex)
        assertEquals(MqttMessageDirection.SENT, model.state.value.messages.single().direction)
    }

    @Test fun failedPublishIsNotRecordedAsSentAndDoesNotExposeCredentials() = withModel { model, repository ->
        model.setPassword("very-secret")
        repository.state.value = MqttConnectionState(MqttConnectionStatus.CONNECTED)
        repository.publishFailure = IllegalStateException("Rejected at wss://alice:very-secret@broker.example/mqtt")
        runCurrent()
        model.setPublishTopic("device/command")
        model.setPublishPayload("start")
        model.publish(); runCurrent()
        val failure = assertNotNull(model.state.value.error)
        assertFalse(failure.contains("very-secret"))
        assertFalse(failure.contains("alice:"))
        assertFalse(model.state.value.toString().contains("very-secret"))
        assertTrue(model.state.value.messages.isEmpty())
        assertFalse(model.state.value.busy)
        advanceTimeBy(40_000); runCurrent()
        assertEquals(1, repository.publishCalls)
    }

    @Test fun disconnectCancelsPendingConnectBeforeClosingAndClearsBusyState() = withModel { model, repository ->
        var connectionCancelled = false
        repository.connectAction = { try { awaitCancellation() } finally { connectionCancelled = true } }
        model.setEndpoint("wss://broker.example/mqtt")
        model.connect(); runCurrent()
        assertTrue(model.state.value.busy)
        model.setEndpoint("wss://should-not-replace.example/mqtt")
        assertEquals("wss://broker.example/mqtt", model.state.value.endpoint)
        model.disconnect(); runCurrent()
        assertTrue(connectionCancelled)
        assertEquals(1, repository.disconnectCalls)
        assertEquals(MqttConnectionStatus.DISCONNECTED, model.state.value.connection.status)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.error)
    }

    @Test fun publishTimeoutExplainsUnknownDeliveryAndDoesNotClaimSuccess() = withModel { model, repository ->
        repository.state.value = MqttConnectionState(MqttConnectionStatus.CONNECTED)
        repository.publishAction = { awaitCancellation() }
        runCurrent()
        model.setPublishTopic("device/command")
        model.publish(); runCurrent()
        advanceTimeBy(20_001); runCurrent()
        assertTrue(assertNotNull(model.state.value.error).contains("送达状态未知"))
        assertTrue(model.state.value.messages.isEmpty())
        assertFalse(model.state.value.busy)
        advanceTimeBy(40_000); runCurrent()
        assertEquals(1, repository.publishCalls)
    }

    @Test fun duplicateActionsAreIgnoredWhileOperationPendingAndSubscriptionsFollowRepository() = withModel { model, repository ->
        val release = CompletableDeferred<Unit>()
        repository.state.value = MqttConnectionState(MqttConnectionStatus.CONNECTED)
        repository.subscribeAction = { release.await() }
        runCurrent()
        model.setSubscriptionFilter("sensor/+/value")
        model.setSubscriptionQos(MqttQos.EXACTLY_ONCE)
        model.subscribe(); model.subscribe(); runCurrent()
        assertEquals(1, repository.subscribeCalls)
        release.complete(Unit); runCurrent()
        assertEquals(MqttSubscription("sensor/+/value", MqttQos.EXACTLY_ONCE), model.state.value.subscriptions.single())
        model.unsubscribe("sensor/+/value"); runCurrent()
        assertTrue(model.state.value.subscriptions.isEmpty())
    }

    @Test fun clearingViewModelStoreReleasesRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeMqttRepository()
        val store = ViewModelStore()
        try {
            store.put("mqtt", MqttWorkbenchViewModel({ repository }))
            runCurrent()
            store.clear(); runCurrent()
            assertTrue(repository.closed)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    private fun withModel(
        maximumLogEntries: Int = 100,
        test: suspend TestScope.(MqttWorkbenchViewModel, FakeMqttRepository) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeMqttRepository()
        val model = MqttWorkbenchViewModel({ repository }, maximumLogEntries)
        val store = ViewModelStore().apply { put("mqtt", model) }
        try { runCurrent(); test(model, repository) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private class FakeMqttRepository : MqttRepository {
        override val state = MutableStateFlow(MqttConnectionState(MqttConnectionStatus.DISCONNECTED))
        override val subscriptions = MutableStateFlow<List<MqttSubscription>>(emptyList())
        override val messages = MutableSharedFlow<MqttMessage>()
        var connectAction: suspend () -> Unit = {}
        var publishAction: suspend () -> Unit = {}
        var subscribeAction: suspend () -> Unit = {}
        var publishFailure: Exception? = null
        var disconnectCalls = 0
        var selectedConfig: MqttConnectionConfig? = null
        var connectCalls = 0
        var publishCalls = 0
        var subscribeCalls = 0
        var closed = false
        val published = mutableListOf<MqttMessage>()

        override suspend fun connect(config: MqttConnectionConfig) {
            selectedConfig = config
            connectCalls++
            state.value = MqttConnectionState(MqttConnectionStatus.CONNECTING, config.url)
            try {
                connectAction()
                state.value = MqttConnectionState(MqttConnectionStatus.CONNECTED, config.url)
            } catch (failure: Exception) {
                state.value = MqttConnectionState(MqttConnectionStatus.DISCONNECTED)
                throw failure
            }
        }
        override suspend fun disconnect() { disconnectCalls++; state.value = MqttConnectionState(MqttConnectionStatus.DISCONNECTED) }
        override suspend fun subscribe(topicFilter: String, qos: MqttQos) {
            subscribeCalls++
            subscribeAction()
            subscriptions.value += MqttSubscription(topicFilter, qos)
        }
        override suspend fun unsubscribe(topicFilter: String) { subscriptions.value = subscriptions.value.filterNot { it.topicFilter == topicFilter } }
        override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean) {
            publishCalls++
            publishFailure?.let { throw it }
            publishAction()
            published += MqttMessage(topic, payload.copyOf(), qos, retain)
        }
        override fun close() { closed = true }
    }
}
