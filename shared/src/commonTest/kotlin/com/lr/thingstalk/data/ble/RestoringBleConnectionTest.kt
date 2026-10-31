@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.lr.thingstalk.data.ble

import com.lr.thingstalk.data.panel.DemoPanelBleConnection
import com.lr.thingstalk.domain.ble.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class RestoringBleConnectionTest {
    @Test fun restorationIsDeferredAndPublishesOnlyAnEstablishedConnection() = runTest {
        var opens = 0
        val gate = CompletableDeferred<Unit>()
        val native = FakeConnection()
        val connection = RestoringBleConnection { opens++; gate.await(); native.connect(); native }
        val states = mutableListOf<BleConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { connection.state.toList(states) }
        assertEquals(0, opens)
        val job = launch { connection.connect() }; runCurrent()
        assertEquals(BleConnectionState.CONNECTING, states.last())
        assertFailsWith<IllegalStateException> { connection.discoverServices() }
        gate.complete(Unit); job.join(); runCurrent()
        assertEquals(BleConnectionState.CONNECTED, states.last())
        assertTrue(connection.discoverServices().isNotEmpty())
        connection.close(); connection.close(); runCurrent()
        assertEquals(1, native.closes)
        assertEquals(BleConnectionState.DISCONNECTED, states.last())
    }

    @Test fun closeCancelsRestoreAndDisposesLateNativeResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val native = FakeConnection()
        val connection = RestoringBleConnection {
            withContext(NonCancellable) { gate.await(); native.connect(); native }
        }
        val job = launch { connection.connect() }; runCurrent()
        connection.close(); gate.complete(Unit); job.join(); runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, native.closes)
        assertEquals(BleConnectionState.DISCONNECTED, connection.state.first())
        assertFailsWith<IllegalStateException> { connection.connect() }
    }

    @Test fun disconnectCancelsPendingLookupAndPermitsExplicitRetry() = runTest {
        var attempts = 0
        var cancelled = false
        val native = FakeConnection()
        val connection = RestoringBleConnection {
            attempts++
            if (attempts == 1) try { awaitCancellation() } finally { cancelled = true }
            native.connect(); native
        }
        val first = launch { connection.connect() }; runCurrent()
        connection.disconnect(); first.join()
        assertTrue(cancelled)
        assertEquals(BleConnectionState.DISCONNECTED, connection.state.first())
        connection.connect()
        assertEquals(2, attempts)
        assertEquals(BleConnectionState.CONNECTED, connection.state.first())
        connection.disconnect()
        assertEquals(BleConnectionState.DISCONNECTED, connection.state.first())
        connection.close()
    }

    @Test fun failedRestorationDoesNotLeaveConnectingStateOrReplayOperations() = runTest {
        var attempts = 0
        val connection = RestoringBleConnection { attempts++; throw BleException("identifier not found") }
        repeat(2) {
            assertFailsWith<BleException> { connection.connect() }
            assertEquals(BleConnectionState.DISCONNECTED, connection.state.first())
        }
        assertEquals(2, attempts)
        connection.close()
    }

    private class FakeConnection(private val delegate: BleConnection = DemoPanelBleConnection()) : BleConnection by delegate {
        var closes = 0
        override fun close() { closes++; delegate.close() }
    }
}
