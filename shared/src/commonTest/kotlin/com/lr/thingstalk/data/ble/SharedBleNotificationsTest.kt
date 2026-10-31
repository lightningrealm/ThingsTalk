@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.BleCharacteristicId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlin.test.*

class SharedBleNotificationsTest {
    @Test fun firstAndLaterSubscribersAreReadyAndOnlyLastCancellationStopsSource() = runTest {
        val events = MutableSharedFlow<ByteArray>()
        var starts = 0
        var stops = 0
        val shared = SharedBleNotifications(backgroundScope) { _, ready ->
            events.onSubscription { starts++; ready() }.onCompletion { stops++ }
        }
        val session = Any()
        var firstReady = 0
        var secondReady = 0
        val firstValues = mutableListOf<ByteArray>()
        val secondValues = mutableListOf<ByteArray>()
        val first = backgroundScope.launch { shared.observe(session, ID) { firstReady++ }.collect { firstValues += it } }
        runCurrent()
        events.emit(byteArrayOf(0x11))
        runCurrent()
        val second = backgroundScope.launch { shared.observe(session, ID) { secondReady++ }.collect { secondValues += it } }
        runCurrent()
        assertEquals(1, starts)
        assertEquals(1, firstReady)
        assertEquals(1, secondReady)
        assertTrue(secondValues.isEmpty(), "A late subscriber must not receive replayed values")
        events.emit(byteArrayOf(0x22))
        runCurrent()
        assertEquals(2, firstValues.size)
        assertContentEquals(byteArrayOf(0x22), secondValues.single())
        first.cancelAndJoin()
        runCurrent()
        assertEquals(0, stops)
        events.emit(byteArrayOf(0x33))
        runCurrent()
        assertEquals(2, secondValues.size)
        second.cancelAndJoin()
        runCurrent()
        assertEquals(1, stops)
    }

    @Test fun readyActionCanWriteAndReceiveAnImmediateFirstResponse() = runTest {
        val events = MutableSharedFlow<ByteArray>()
        val shared = SharedBleNotifications(backgroundScope) { _, ready -> events.onSubscription { ready() } }
        val received = mutableListOf<ByteArray>()
        var writeCompleted = false
        val collector = backgroundScope.launch {
            shared.observe(Any(), ID) {
                // Simulates a write that produces its response before returning to the caller.
                events.emit(byteArrayOf(0x42))
                writeCompleted = true
            }.take(1).collect { received += it }
        }
        runCurrent()
        assertTrue(writeCompleted)
        assertTrue(collector.isCompleted)
        assertContentEquals(byteArrayOf(0x42), received.single())
    }

    @Test fun oneReadyActionFailureDoesNotCancelOtherLocalSubscribers() = runTest {
        val events = MutableSharedFlow<ByteArray>()
        var starts = 0
        val shared = SharedBleNotifications(backgroundScope) { _, ready -> events.onSubscription { starts++; ready() } }
        val session = Any()
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { shared.observe(session, ID) {}.collect { received += it } }
        runCurrent()
        val failures = mutableListOf<Throwable>()
        backgroundScope.launch {
            shared.observe(session, ID) { error("local setup failed") }.catch { failures += it }.collect()
        }
        runCurrent()
        events.emit(byteArrayOf(0x33))
        runCurrent()
        assertEquals(1, starts)
        assertEquals("local setup failed", failures.single().message)
        assertContentEquals(byteArrayOf(0x33), received.single())
    }

    @Test fun upstreamFailureReachesAllSubscribersAndRetryWaitsForFreshReady() = runTest {
        val fail = CompletableDeferred<Unit>()
        val retryReady = CompletableDeferred<Unit>()
        var starts = 0
        val shared = SharedBleNotifications(backgroundScope) { _, ready -> flow {
            starts++
            if (starts == 1) {
                ready()
                fail.await()
                error("transport failure")
            } else {
                retryReady.await()
                ready()
                awaitCancellation()
            }
        } }
        val session = Any()
        val failures = mutableListOf<Throwable>()
        repeat(2) { backgroundScope.launch { shared.observe(session, ID) {}.catch { failures += it }.collect() } }
        runCurrent()
        assertEquals(1, starts)
        fail.complete(Unit)
        runCurrent()
        assertEquals(2, failures.size)
        assertTrue(failures.all { it.message == "transport failure" })
        var ready = false
        backgroundScope.launch { shared.observe(session, ID) { ready = true }.collect() }
        runCurrent()
        assertEquals(2, starts)
        assertFalse(ready)
        retryReady.complete(Unit)
        runCurrent()
        assertTrue(ready)
    }

    @Test fun failureBeforeReadyDoesNotLeaveSubscribersWaiting() = runTest {
        val fail = CompletableDeferred<Unit>()
        val shared = SharedBleNotifications(backgroundScope) { _, _ -> flow<ByteArray> {
            fail.await()
            error("setup rejected")
        } }
        val session = Any()
        val failures = mutableListOf<Throwable>()
        var ready = 0
        repeat(2) { backgroundScope.launch { shared.observe(session, ID) { ready++ }.catch { failures += it }.collect() } }
        runCurrent()
        fail.complete(Unit)
        runCurrent()
        assertEquals(0, ready)
        assertEquals(2, failures.size)
        assertTrue(failures.all { it.message == "setup rejected" })
    }

    @Test fun aNewSessionCannotReuseOldReadinessOrOverlapPlatformCleanup() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var starts = 0
        var active = 0
        var maximumActive = 0
        val shared = SharedBleNotifications(backgroundScope) { _, ready -> flow<ByteArray> {
            starts++
            active++
            maximumActive = maxOf(maximumActive, active)
            try { ready(); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() }; active-- }
        } }
        var firstReady = 0
        var secondReady = 0
        val first = backgroundScope.launch { shared.observe(Any(), ID) { firstReady++ }.collect() }
        runCurrent()
        assertEquals(1, firstReady)
        val second = backgroundScope.launch { shared.observe(Any(), ID) { secondReady++ }.collect() }
        runCurrent()
        assertEquals(1, starts)
        assertEquals(0, secondReady)
        assertTrue(first.isCompleted)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, secondReady)
        assertEquals(1, maximumActive)
        second.cancelAndJoin()
        runCurrent()
        assertEquals(0, active)
    }

    @Test fun resubscribingWhileLastObserverStopsWaitsForCleanupAndCanBeCancelled() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var starts = 0
        val shared = SharedBleNotifications(backgroundScope) { _, ready -> flow<ByteArray> {
            starts++
            try { ready(); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        } }
        val session = Any()
        val first = backgroundScope.launch { shared.observe(session, ID) {}.collect() }
        runCurrent()
        first.cancelAndJoin()
        var secondReady = false
        val second = backgroundScope.launch { shared.observe(session, ID) { secondReady = true }.collect() }
        runCurrent()
        assertEquals(1, starts)
        assertFalse(secondReady)
        second.cancelAndJoin()
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(1, starts, "A cancelled waiter must not start another observation")
        backgroundScope.launch { shared.observe(session, ID) { secondReady = true }.collect() }
        runCurrent()
        assertEquals(2, starts)
        assertTrue(secondReady)
    }

    @Test fun eachSubscriberReceivesItsOwnByteArrayCopy() = runTest {
        val events = MutableSharedFlow<ByteArray>()
        val shared = SharedBleNotifications(backgroundScope) { _, ready -> events.onSubscription { ready() } }
        val session = Any()
        val secondValues = mutableListOf<ByteArray>()
        backgroundScope.launch { shared.observe(session, ID) {}.collect { it[0] = 0x7F } }
        backgroundScope.launch { shared.observe(session, ID) {}.collect { secondValues += it } }
        runCurrent()
        val source = byteArrayOf(0x01)
        events.emit(source)
        runCurrent()
        assertContentEquals(byteArrayOf(0x01), source)
        assertContentEquals(byteArrayOf(0x01), secondValues.single())
    }

    @Test fun cancellingASlowSubscriberUnblocksAFullInboxWithoutDroppingOtherSubscribersValues() = runTest {
        val events = MutableSharedFlow<ByteArray>()
        val shared = SharedBleNotifications(backgroundScope) { _, ready -> events.onSubscription { ready() } }
        val session = Any()
        val slowReady = CompletableDeferred<Unit>()
        val slow = backgroundScope.launch { shared.observe(session, ID) { slowReady.await() }.collect() }
        runCurrent()
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { shared.observe(session, ID) {}.collect { received += it } }
        runCurrent()
        var emittedAll = false
        backgroundScope.launch {
            repeat(66) { events.emit(byteArrayOf(it.toByte())) }
            emittedAll = true
        }
        runCurrent()
        assertFalse(emittedAll)
        assertEquals(64, received.size)
        slow.cancelAndJoin()
        runCurrent()
        assertTrue(emittedAll)
        assertEquals(66, received.size)
        assertContentEquals(ByteArray(66) { it.toByte() }, received.map { it.single() }.toByteArray())
    }

    @Test fun anAlreadyCancelledOwnerScopeCannotLeaveACollectorWaitingForReady() = runTest {
        val ownerJob = SupervisorJob().apply { cancel() }
        val ownerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + ownerJob)
        var sourceStarted = false
        var localReady = false
        val shared = SharedBleNotifications(ownerScope) { _, ready -> flow<ByteArray> {
            sourceStarted = true
            ready()
            awaitCancellation()
        } }
        val collector = backgroundScope.launch { shared.observe(Any(), ID) { localReady = true }.collect() }
        runCurrent()
        assertTrue(collector.isCompleted)
        assertFalse(sourceStarted)
        assertFalse(localReady)
    }

    private companion object {
        val ID = BleCharacteristicId("0000180f-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb")
    }
}
