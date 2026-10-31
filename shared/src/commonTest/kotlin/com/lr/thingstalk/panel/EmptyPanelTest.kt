@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.panel

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.data.panel.*
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.PanelViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

class EmptyPanelTest {
    @Test fun emptyAndUnboundPanelsRoundTripButMalformedBindingsStillFail() {
        val empty = PanelDefinition(title = "我的设备")
        assertEquals(empty, PanelJson.decode(PanelJson.encode(empty)))
        val unbound = defaultDemoPanel().copy(bindings = emptyList())
        assertTrue(validatePanel(unbound).isEmpty())
        assertEquals(unbound, PanelJson.decode(PanelJson.encode(unbound)))
        assertTrue(validatePanel(unbound.copy(bindings = listOf(PanelBinding("power")))).isNotEmpty())
        assertTrue(validatePanel(unbound.copy(widgets = listOf(unbound.widgets.first().copy(capabilityId = "missing")))).isNotEmpty())
    }

    @Test fun attachingDoesNotConnectAndDisconnectClearsValuesAndSubscriptions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val connection = DemoPanelBleConnection()
            val vm = PanelViewModel(defaultDemoPanel(), codec = PanelJson)
            store.put("test", vm)
            vm.attachConnection(connection, BlePanelOperations(connection), "我的设备")
            runCurrent()
            assertFalse(vm.state.value.ready)
            assertEquals(0, connection.activeSubscriptions)
            vm.write("brightness", "42")
            runCurrent()
            assertTrue(vm.state.value.values.isEmpty())
            vm.connect()
            runCurrent()
            assertTrue(vm.state.value.ready)
            assertEquals("65", vm.state.value.values["brightness"])
            vm.disconnect()
            runCurrent()
            assertFalse(vm.state.value.ready)
            assertTrue(vm.state.value.values.isEmpty())
            assertEquals(0, connection.activeSubscriptions)
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun configurationIsAppliedOnlyAfterPersistenceSucceeds() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val initial = PanelDefinition(title = "新设备")
            val gate = CompletableDeferred<Unit>()
            var stored: PanelDefinition? = null
            val vm = PanelViewModel(initial, codec = PanelJson, onSave = { gate.await(); stored = it })
            store.put("test", vm)
            val edited = initial.copy(title = "我的控制面板")
            assertTrue(vm.updateDefinition(edited).isEmpty())
            runCurrent()
            assertTrue(vm.state.value.saving)
            assertEquals(initial, vm.state.value.definition)
            gate.complete(Unit)
            runCurrent()
            assertEquals(edited, stored)
            assertEquals(edited, vm.state.value.definition)
            assertFalse(vm.state.value.saving)
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }

    @Test fun failedPersistencePreservesCurrentPanel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val initial = PanelDefinition(title = "新设备")
            val vm = PanelViewModel(initial, codec = PanelJson, onSave = { error("存储不可用") })
            store.put("test", vm)
            vm.updateDefinition(initial.copy(title = "不应生效"))
            runCurrent()
            assertEquals(initial, vm.state.value.definition)
            assertEquals("存储不可用", vm.state.value.error)
            assertFalse(vm.state.value.saving)
        } finally { store.clear(); try { advanceUntilIdle() } finally { Dispatchers.resetMain() } }
    }
}
