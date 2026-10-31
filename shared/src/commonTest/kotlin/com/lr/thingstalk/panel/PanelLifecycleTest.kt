@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.panel

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.data.panel.*
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.PanelViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class PanelLifecycleTest {
    @Test fun oldDisconnectFailureDoesNotChangeNewConnectionState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        try {
            val first = DemoPanelBleConnection()
            val oldConnection = object : BleConnection by first {
                override suspend fun disconnect() = withContext(NonCancellable) { oldGate.await(); error("old disconnect failure") }
            }
            val vm = PanelViewModel(defaultDemoPanel(), BlePanelOperations(oldConnection), PanelJson, oldConnection)
            store.put("test", vm)
            vm.connect(); runCurrent()
            vm.disconnect(); runCurrent()
            val second = DemoPanelBleConnection()
            val newConnection = object : BleConnection by second {
                override suspend fun connect() { newGate.await(); second.connect() }
            }
            vm.attachConnection(newConnection, BlePanelOperations(newConnection), "新设备")
            vm.connect(); runCurrent()
            oldGate.complete(Unit); runCurrent()
            assertTrue(vm.state.value.busy)
            assertNull(vm.state.value.error)
            newGate.complete(Unit); runCurrent()
            assertTrue(vm.state.value.ready)
            assertEquals("新设备", vm.state.value.transportLabel)
        } finally { oldGate.complete(Unit); newGate.complete(Unit); store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun oldOperationFailureCannotClearNewConnectionBusyState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        try {
            val first = DemoPanelBleConnection()
            val operations = object : PanelOperations by BlePanelOperations(first) {
                override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) = withContext(NonCancellable) { oldGate.await(); error("old write failure") }
            }
            val vm = PanelViewModel(defaultDemoPanel(), operations, PanelJson, first)
            store.put("test", vm)
            vm.connect(); runCurrent()
            vm.write("brightness", "20"); runCurrent()
            val second = DemoPanelBleConnection()
            val newConnection = object : BleConnection by second {
                override suspend fun connect() { newGate.await(); second.connect() }
            }
            vm.attachConnection(newConnection, BlePanelOperations(newConnection), "新设备")
            vm.connect(); runCurrent()
            oldGate.complete(Unit); runCurrent()
            assertTrue(vm.state.value.busy)
            assertNull(vm.state.value.error)
            newGate.complete(Unit); runCurrent()
            assertEquals("65", vm.state.value.values["brightness"])
        } finally { oldGate.complete(Unit); newGate.complete(Unit); store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun oldConnectCompletionCannotClearNewConnectionBusyState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        try {
            val first = DemoPanelBleConnection()
            val oldConnection = object : BleConnection by first {
                override suspend fun connect() = withContext(NonCancellable) { oldGate.await(); error("old connection failure") }
            }
            val vm = PanelViewModel(defaultDemoPanel(), BlePanelOperations(oldConnection), PanelJson, oldConnection)
            store.put("test", vm)
            vm.connect(); runCurrent()
            val second = DemoPanelBleConnection()
            val newConnection = object : BleConnection by second {
                override suspend fun connect() { newGate.await(); second.connect() }
            }
            vm.attachConnection(newConnection, BlePanelOperations(newConnection), "新设备")
            vm.connect(); runCurrent()
            oldGate.complete(Unit); runCurrent()
            assertTrue(vm.state.value.busy)
            assertNull(vm.state.value.error)
            newGate.complete(Unit); runCurrent()
            assertTrue(vm.state.value.ready)
        } finally { oldGate.complete(Unit); newGate.complete(Unit); store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun connectionAndOperationTimeoutsReleaseBusyState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val delegate = DemoPanelBleConnection()
            val connection = object : BleConnection by delegate {
                override suspend fun connect() { awaitCancellation() }
            }
            val vm = PanelViewModel(defaultDemoPanel(), BlePanelOperations(connection), PanelJson, connection)
            store.put("test", vm)
            vm.connect(); runCurrent()
            advanceTimeBy(15_000); runCurrent()
            assertFalse(vm.state.value.busy)
            assertFalse(vm.state.value.ready)
            assertTrue(vm.state.value.error.orEmpty().contains("超时"))
            val fresh = DemoPanelBleConnection()
            val operations = object : PanelOperations by BlePanelOperations(fresh) {
                override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) { awaitCancellation() }
            }
            vm.attachConnection(fresh, operations, "设备")
            vm.connect(); runCurrent()
            vm.write("brightness", "20"); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertFalse(vm.state.value.busy)
            assertTrue(vm.state.value.operationErrors["brightness"].orEmpty().contains("结果未知"))
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun savingAfterLeavingDeviceDoesNotRestartNotifications() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val gate = CompletableDeferred<Unit>()
            val connection = DemoPanelBleConnection()
            val vm = PanelViewModel(defaultDemoPanel(), BlePanelOperations(connection), PanelJson, connection, onSave = { gate.await() })
            store.put("test", vm)
            vm.connect(); runCurrent()
            assertEquals(3, connection.activeSubscriptions)
            val edited = defaultDemoPanel().copy(title = "我的面板")
            vm.updateDefinition(edited); runCurrent()
            vm.disconnect(); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(edited, vm.state.value.definition)
            assertEquals(0, connection.activeSubscriptions)
            assertFalse(vm.state.value.ready)
            assertTrue(vm.state.value.values.isEmpty())
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }
}
