@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.data.mqtt

import com.lr.thingstalk.domain.mqtt.MqttConnectionConfig
import com.lr.thingstalk.domain.mqtt.MqttConnectionStatus
import com.lr.thingstalk.domain.mqtt.MqttException
import com.lr.thingstalk.domain.mqtt.MqttMessage
import com.lr.thingstalk.domain.mqtt.MqttQos
import com.lr.thingstalk.domain.mqtt.MqttSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultMqttRepositoryTest {
    @Test fun receivesImmediateRetainedMessageWhileSubscribeIsStillAwaitingItsAck() = runTest {
        val transport = FakeTransport()
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        val messages = mutableListOf<MqttMessage>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.messages.collect { messages += it } }
        repository.connect(CONFIG)
        transport.onSubscribe = { transport.messages.emit(MESSAGE) }
        transport.grantedQos = MqttQos.AT_MOST_ONCE

        repository.subscribe("device/#", MqttQos.AT_LEAST_ONCE)
        runCurrent()

        assertContentEquals(MESSAGE.payload, messages.single().payload)
        assertEquals(listOf(MqttSubscription("device/#", MqttQos.AT_MOST_ONCE)), repository.subscriptions.value)
        repository.close()
    }

    @Test fun acknowledgedSubscriptionsCanBeUpdatedAndRemoved() = runTest {
        val transport = FakeTransport()
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        repository.connect(CONFIG)
        repository.subscribe("device/#", MqttQos.AT_MOST_ONCE)
        transport.grantedQos = MqttQos.EXACTLY_ONCE
        repository.subscribe("device/#", MqttQos.EXACTLY_ONCE)
        assertEquals(listOf(MqttSubscription("device/#", MqttQos.EXACTLY_ONCE)), repository.subscriptions.value)

        transport.unsubscribeFailure = MqttException("服务器拒绝取消订阅。")
        assertFailsWith<MqttException> { repository.unsubscribe("device/#") }
        assertEquals(1, repository.subscriptions.value.size)
        transport.unsubscribeFailure = null
        repository.unsubscribe("device/#")
        assertTrue(repository.subscriptions.value.isEmpty())
        repository.close()
    }

    @Test fun failedSubscriptionNeverAppearsAsActive() = runTest {
        val transport = FakeTransport().apply { onSubscribe = { throw MqttException("服务器拒绝订阅。") } }
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        repository.connect(CONFIG)
        assertFailsWith<MqttException> { repository.subscribe("device/#", MqttQos.AT_MOST_ONCE) }
        assertTrue(repository.subscriptions.value.isEmpty())
        assertEquals(MqttConnectionStatus.CONNECTED, repository.state.value.status)
        repository.close()
    }

    @Test fun connectionLossClearsAcknowledgedSubscriptionsAndReleasesTransport() = runTest {
        val transport = FakeTransport()
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        repository.connect(CONFIG)
        repository.subscribe("device/#", MqttQos.AT_MOST_ONCE)
        runCurrent()
        transport.connected.value = false
        runCurrent()
        assertEquals(MqttConnectionStatus.DISCONNECTED, repository.state.value.status)
        assertNotNull(repository.state.value.error)
        assertTrue(repository.subscriptions.value.isEmpty())
        assertEquals(1, transport.closed)
        assertFailsWith<MqttException> { repository.publish("device/test", byteArrayOf(1), MqttQos.AT_MOST_ONCE, false) }
        repository.close()
    }

    @Test fun immediateConnectionLossCannotBeHiddenByStateFlowConflation() = runTest {
        val transport = FakeTransport()
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        repository.connect(CONFIG)
        // No scheduler turn between completing the handshake and the socket disappearing.
        transport.connected.value = false
        runCurrent()
        assertEquals(MqttConnectionStatus.DISCONNECTED, repository.state.value.status)
        assertNotNull(repository.state.value.error)
        assertEquals(1, transport.closed)
        repository.close()
    }

    @Test fun closeDuringConnectionCannotResurrectTheSession() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply { connectGate = gate }
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        var failure: Throwable? = null
        val connect = backgroundScope.launch { try { repository.connect(CONFIG) } catch (error: Exception) { failure = error } }
        runCurrent()
        assertEquals(MqttConnectionStatus.CONNECTING, repository.state.value.status)
        repository.close()
        gate.complete(Unit)
        runCurrent()
        assertTrue(connect.isCompleted)
        assertNotNull(failure)
        assertEquals(MqttConnectionStatus.DISCONNECTED, repository.state.value.status)
        assertNull(repository.state.value.error)
        assertEquals(1, transport.closed)
        repository.close()
        assertEquals(1, transport.closed)
        assertFailsWith<MqttException> { repository.connect(CONFIG) }
    }

    @Test fun cancelledConnectReleasesNetworkResources() = runTest {
        val transport = FakeTransport().apply { connectGate = CompletableDeferred() }
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        val connect = backgroundScope.launch { repository.connect(CONFIG) }
        runCurrent()
        connect.cancelAndJoin()
        assertEquals(1, transport.closed)
        assertEquals(MqttConnectionStatus.DISCONNECTED, repository.state.value.status)
        assertNull(repository.state.value.error)
        repository.close()
    }

    @Test fun connectTimeoutAndTransportErrorsDoNotExposeCredentials() = runTest {
        val transport = FakeTransport().apply { connectGate = CompletableDeferred() }
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        var failure: Throwable? = null
        backgroundScope.launch { try { repository.connect(CONFIG) } catch (error: Exception) { failure = error } }
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertTrue(failure is MqttException)
        assertEquals(1, transport.closed)
        assertEquals(MqttConnectionStatus.DISCONNECTED, repository.state.value.status)
        repository.close()

        val unsafe = DefaultMqttRepository(backgroundScope) { throw IllegalStateException("token=private-password") }
        val error = assertFailsWith<MqttException> { unsafe.connect(CONFIG.copy(password = "private-password")) }
        assertFalse(error.toString().contains("private-password"))
        assertNull(error.cause)
        unsafe.close()
    }

    @Test fun invalidReplacementConfigurationKeepsExistingConnection() = runTest {
        val transport = FakeTransport()
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        repository.connect(CONFIG)
        assertFailsWith<MqttException> { repository.connect(CONFIG.copy(url = "mqtt://wrong:1883")) }
        assertEquals(MqttConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(0, transport.closed)
        repository.close()
    }

    @Test fun reconnectUsesFreshSessionWithoutReplayingOldMessagesOrSubscriptions() = runTest {
        val first = FakeTransport()
        val second = FakeTransport()
        var created = 0
        val repository = DefaultMqttRepository(backgroundScope) { if (created++ == 0) first else second }
        val messages = mutableListOf<MqttMessage>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.messages.collect { messages += it } }
        repository.connect(CONFIG)
        repository.subscribe("device/#", MqttQos.AT_MOST_ONCE)
        repository.connect(CONFIG.copy(clientId = "replacement"))
        first.messages.emit(MESSAGE)
        second.messages.emit(MESSAGE.copy(topic = "device/new"))
        runCurrent()
        assertEquals(1, first.disconnected)
        assertEquals(1, first.closed)
        assertEquals("device/new", messages.single().topic)
        assertTrue(repository.subscriptions.value.isEmpty())
        repository.close()
    }

    @Test fun cancelledSubscriptionClosesSessionSoNoHiddenSubscriptionRemains() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply { onSubscribe = { gate.await() } }
        val repository = DefaultMqttRepository(backgroundScope) { transport }
        repository.connect(CONFIG)
        val subscription = backgroundScope.launch { repository.subscribe("device/#", MqttQos.AT_MOST_ONCE) }
        runCurrent()
        subscription.cancelAndJoin()
        assertTrue(repository.subscriptions.value.isEmpty())
        assertEquals(MqttConnectionStatus.DISCONNECTED, repository.state.value.status)
        assertEquals(1, transport.closed)
        repository.close()
    }

    private class FakeTransport : MqttTransport {
        override val connected = MutableStateFlow(false)
        override val messages = MutableSharedFlow<MqttMessage>()
        var connectGate: CompletableDeferred<Unit>? = null
        var onSubscribe: suspend () -> Unit = {}
        var grantedQos = MqttQos.AT_MOST_ONCE
        var unsubscribeFailure: Exception? = null
        var closed = 0
        var disconnected = 0
        override suspend fun connect() { connectGate?.await(); connected.value = true }
        override suspend fun disconnect() { disconnected++; connected.value = false }
        override suspend fun subscribe(topicFilter: String, qos: MqttQos): MqttQos { onSubscribe(); return grantedQos }
        override suspend fun unsubscribe(topicFilter: String) { unsubscribeFailure?.let { throw it } }
        override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean) = Unit
        override fun close() { closed++ }
    }

    private companion object {
        val CONFIG = MqttConnectionConfig("wss://broker.example/mqtt", "things-test")
        val MESSAGE = MqttMessage("device/status", byteArrayOf(0, 0x7f, 0xff.toByte()), MqttQos.AT_MOST_ONCE, true)
    }
}
