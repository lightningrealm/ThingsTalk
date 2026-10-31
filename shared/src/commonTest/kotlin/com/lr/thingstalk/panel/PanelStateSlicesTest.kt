@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.lr.thingstalk.panel

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.data.panel.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class PanelStateSlicesTest {
    @Test fun telemetryOnlyEmitsForItsOwnComponentAndNotPageOrConnection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val connection = DemoPanelBleConnection()
            val vm = PanelViewModel(defaultDemoPanel(), BlePanelOperations(connection), PanelJson, connection)
            store.put("panel", vm)
            vm.connect(); runCurrent()
            val temperature = mutableListOf<PanelWidgetState>()
            val power = mutableListOf<PanelWidgetState>()
            val chrome = mutableListOf<PanelChromeState>()
            val connections = mutableListOf<PanelUiState>()
            val definitions = mutableListOf<PanelDefinition>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.widgetStates("temperature").toList(temperature) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.widgetStates("power").toList(power) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.chromeStates().toList(chrome) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.connectionStates().toList(connections) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.definitions().toList(definitions) }
            connection.updateTemperature(42); runCurrent()
            assertEquals("42", temperature.last().value)
            assertTrue(temperature.size > 1)
            assertEquals(1, power.size)
            assertEquals(1, chrome.size)
            assertEquals(1, connections.size)
            assertEquals(1, definitions.size)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun queuedCommandsHaveLocalBusyStateAndNeverExecuteTwice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val gate = CompletableDeferred<Unit>()
        try {
            val connection = DemoPanelBleConnection()
            val calls = mutableListOf<String>()
            val base = BlePanelOperations(connection)
            val ops = object : PanelOperations by base {
                override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) {
                    calls += capabilityId
                    if (capabilityId == "blink") gate.await()
                    base.write(definition, capabilityId, value)
                }
            }
            val vm = PanelViewModel(defaultDemoPanel(), ops, PanelJson, connection)
            store.put("panel", vm)
            vm.connect(); runCurrent()
            val before = vm.state.value.connectionChrome()
            vm.write("blink", "01"); runCurrent()
            assertTrue(vm.state.value.widget("blink").busy)
            assertTrue(vm.state.value.widget("power").enabled)
            assertEquals(before, vm.state.value.connectionChrome())
            vm.write("power", "true")
            vm.write("blink", "01")
            runCurrent()
            assertEquals(listOf("blink"), calls)
            assertEquals(setOf("blink", "power"), vm.state.value.activeOperations)
            gate.complete(Unit); runCurrent()
            assertEquals(listOf("blink", "power"), calls)
            assertTrue(vm.state.value.activeOperations.isEmpty())
            assertFalse(vm.state.value.busy)
            assertEquals(1, connection.actionCount)
        } finally { gate.complete(Unit); store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun leavingCancelsQueuedCommandBeforeItCanReachDevice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val connection = DemoPanelBleConnection()
            val calls = mutableListOf<String>()
            val ops = object : PanelOperations by BlePanelOperations(connection) {
                override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) {
                    calls += capabilityId
                    awaitCancellation()
                }
            }
            val vm = PanelViewModel(defaultDemoPanel(), ops, PanelJson, connection)
            store.put("panel", vm)
            vm.connect(); runCurrent()
            vm.write("blink", "01"); vm.write("power", "true"); runCurrent()
            vm.disconnect(); runCurrent()
            assertEquals(listOf("blink"), calls)
            assertTrue(vm.state.value.activeOperations.isEmpty())
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}
