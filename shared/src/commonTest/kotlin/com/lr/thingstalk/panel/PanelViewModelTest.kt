@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.panel

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.data.panel.*
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.PanelViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class PanelViewModelTest {
    @Test fun delayedInitialReadCannotOverwriteNewerNotification() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val definition = defaultDemoPanel().let { it.copy(
                capabilities = it.capabilities.filter { it.id == "brightness" },
                bindings = it.bindings.filter { it.capabilityId == "brightness" },
                widgets = it.widgets.filter { it.capabilityId == "brightness" },
            ) }
            val delayedRead = CompletableDeferred<String>()
            val notifications = MutableSharedFlow<String>(extraBufferCapacity = 1)
            val operations = object : PanelOperations {
                override suspend fun read(definition: PanelDefinition, capabilityId: String) = delayedRead.await()
                override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) = Unit
                override fun observe(definition: PanelDefinition, capabilityId: String) = notifications
            }
            val viewModel = PanelViewModel(definition, operations, PanelJson, DemoPanelBleConnection())
            store.put("test", viewModel)
            viewModel.connect()
            runCurrent()
            notifications.emit("80")
            runCurrent()
            delayedRead.complete("20")
            runCurrent()
            assertEquals("80", viewModel.state.value.values["brightness"])
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun replacingDefinitionCancelsSubscriptionsAndClearingViewModelReleasesConnection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val connection = DemoPanelBleConnection()
            val viewModel = PanelViewModel(defaultDemoPanel(), BlePanelOperations(connection), PanelJson, connection)
            store.put("test", viewModel)
            viewModel.connect()
            runCurrent()
            assertEquals(3, connection.activeSubscriptions)
            val withoutNotify = defaultDemoPanel().let { it.copy(bindings = it.bindings.map { binding -> binding.copy(notify = null) }) }
            assertTrue(viewModel.updateDefinition(withoutNotify).isEmpty())
            runCurrent()
            assertEquals(0, connection.activeSubscriptions)
            assertEquals("65", viewModel.state.value.values["brightness"])
            assertTrue(viewModel.updateDefinition(defaultDemoPanel()).isEmpty())
            runCurrent()
            assertEquals(3, connection.activeSubscriptions)
            store.clear()
            runCurrent()
            assertEquals(0, connection.activeSubscriptions)
            assertFailsWith<IllegalStateException> { connection.connect() }
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun repeatedConnectRequestsAreCoalesced() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val gate = CompletableDeferred<Unit>()
            val delegate = DemoPanelBleConnection()
            var attempts = 0
            val connection = object : BleConnection by delegate {
                override suspend fun connect() { attempts++; gate.await(); delegate.connect() }
            }
            val operations = object : PanelOperations {
                override suspend fun read(definition: PanelDefinition, capabilityId: String) = "0"
                override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) = Unit
                override fun observe(definition: PanelDefinition, capabilityId: String) = emptyFlow<String>()
            }
            val viewModel = PanelViewModel(defaultDemoPanel(), operations, PanelJson, connection)
            store.put("test", viewModel)
            viewModel.connect()
            runCurrent()
            repeat(4) { viewModel.reconnect() }
            runCurrent()
            assertEquals(1, attempts)
            gate.complete(Unit)
            runCurrent()
            assertTrue(viewModel.state.value.ready)
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }
}
