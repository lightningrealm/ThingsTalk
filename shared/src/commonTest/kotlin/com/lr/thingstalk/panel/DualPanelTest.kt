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

class DualPanelTest {
    private val bleOp = BleOperationBinding("0000180f-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb", codec = CodecType.BOOLEAN)
    private fun panel() = PanelDefinition(5, "Dual lamp", transport = PanelTransport.DUAL,
        capabilities = listOf(PanelCapability("power", "Power", CapabilityType.BOOLEAN)),
        widgets = listOf(PanelWidget("switch", WidgetType.SWITCH, "Power", "power")),
        payloadFormats = listOf(PayloadFormat("state", "State", PayloadEncoding.JSON, listOf(PayloadField("power", CodecType.BOOLEAN)))),
        bindings = listOf(PanelBinding("power", read = bleOp, write = bleOp, notify = bleOp,
            publish = MqttOperationBinding("lamp/set", PayloadReference("state", "power")),
            subscribe = MqttOperationBinding("lamp/state", PayloadReference("state", "power")),
            controlTransport = PanelTransport.BLE, stateTransport = PanelTransport.MQTT)))

    @Test fun explicitRoutesRoundTripAndLegacyPromotionPreservesBindings() {
        val p = panel()
        assertEquals(emptyList(), validatePanel(p))
        assertEquals(p, PanelJson.decode(PanelJson.encode(p)))
        for (channel in listOf(PanelTransport.BLE, PanelTransport.MQTT)) {
            val single = p.forTransport(channel)
            assertEquals(emptyList(), validatePanel(single))
            assertEquals(channel, single.asDual().bindings.single().selectedControl)
            assertEquals(channel, single.asDual().bindings.single().selectedState)
            assertEquals(single, single.asDual().forTransport(channel))
            assertFalse("controlTransport" in PanelJson.encode(single))
        }
        val b = p.bindings.single()
        for (invalid in listOf(b.copy(controlTransport = null), b.copy(stateTransport = null),
            b.copy(controlTransport = PanelTransport.DUAL), b.copy(subscribe = null),
            b.copy(write = bleOp.copy(serviceUuid = "bad")))) {
            assertTrue(validatePanel(p.copy(bindings = listOf(invalid))).isNotEmpty())
        }
    }

    @Test fun controlOnlyUsesSelectedRouteAndNeverFallsBack() = runTest {
        val ble = FakeOps(); val mqtt = FakeOps()
        val routes = MutableStateFlow<Map<PanelTransport, PanelOperations>>(mapOf(PanelTransport.BLE to ble, PanelTransport.MQTT to mqtt))
        val ops = DualPanelOperations(routes)
        ops.write(panel(), "power", "true")
        assertEquals(listOf("power"), ble.writes)
        assertTrue(mqtt.writes.isEmpty())
        routes.value = mapOf(PanelTransport.MQTT to mqtt)
        assertFailsWith<IllegalArgumentException> { ops.write(panel(), "power", "true") }
        assertTrue(mqtt.writes.isEmpty())
        assertFailsWith<IllegalArgumentException> { ops.read(panel(), "power") }
    }

    @Test fun channelJsonIsSeparatedAndRejectsMixedLegacyFields() {
        val v6 = panel().asChannelPanel()
        val encoded = PanelJson.encode(v6)
        assertTrue("\"channels\"" in encoded)
        assertFalse("controlTransport" in encoded)
        assertFalse("stateTransport" in encoded)
        val imported = PanelJson.decode(encoded)
        assertEquals(6, imported.schemaVersion)
        assertEquals(panel().bindings.single().write, imported.bindings.single().write)
        assertEquals(panel().bindings.single().publish, imported.bindings.single().publish)
        assertEquals(encoded, PanelJson.encode(imported))
        assertFailsWith<IllegalArgumentException> { PanelJson.decode(encoded.replace("\"channels\":", "\"bindings\": [], \"channels\":")) }
        assertFailsWith<IllegalArgumentException> { PanelJson.decode(encoded.replace("\"defaultChannel\": \"BLE\"", "\"defaultChannel\": \"DUAL\"")) }
    }

    @Test fun selectedChannelControlsCommandsAndValuesWithoutChangingSavedDefinition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val original = panel().asChannelPanel()
            val ble = FakeOps(); val mqtt = FakeOps()
            val routes = MutableStateFlow<Map<PanelTransport, PanelOperations>>(mapOf(PanelTransport.BLE to ble, PanelTransport.MQTT to mqtt))
            var saves = 0
            val vm = PanelViewModel(original, codec = PanelJson, onSave = { saves++ })
            store.put("panel", vm); vm.attachDual(DualPanelOperations(routes))
            vm.setDualConnections(routes.value.keys, emptySet(), emptyList()); runCurrent()
            vm.write("power", "true"); runCurrent()
            assertEquals(1, ble.writes.size); assertTrue(mqtt.writes.isEmpty())
            ble.events.emit(PanelObservation.Value("true")); runCurrent()
            assertEquals("true", vm.state.value.values["power"])
            vm.selectChannel(PanelTransport.MQTT)
            assertTrue(vm.state.value.values.isEmpty())
            runCurrent()
            ble.events.emit(PanelObservation.Value("false")); runCurrent()
            assertTrue(vm.state.value.values.isEmpty())
            mqtt.events.emit(PanelObservation.Value("true")); runCurrent()
            assertEquals("true", vm.state.value.values["power"])
            vm.write("power", "false"); runCurrent()
            assertEquals(1, ble.writes.size); assertEquals(1, mqtt.writes.size)
            routes.value = mapOf(PanelTransport.BLE to ble)
            vm.setDualConnections(routes.value.keys, emptySet(), emptyList()); runCurrent()
            vm.write("power", "true"); runCurrent()
            assertFalse(vm.state.value.widget("power").enabled)
            assertEquals(1, ble.writes.size)
            assertEquals(original, vm.state.value.definition)
            assertEquals(0, saves)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun switchingChannelCancelsQueuedCommandsAndIgnoresLateResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); val gate = CompletableDeferred<Unit>()
        try {
            val base = panel().asChannelPanel()
            val original = base.copy(capabilities = base.capabilities + base.capabilities.single().copy(id = "other"),
                bindings = base.bindings + base.bindings.single().copy(capabilityId = "other"))
            val ble = FakeOps(write = { withContext(NonCancellable) { gate.await() } }); val mqtt = FakeOps()
            val routes = MutableStateFlow<Map<PanelTransport, PanelOperations>>(mapOf(PanelTransport.BLE to ble, PanelTransport.MQTT to mqtt))
            val vm = PanelViewModel(original, codec = PanelJson)
            store.put("panel", vm); vm.attachDual(DualPanelOperations(routes)); vm.setDualConnections(routes.value.keys, emptySet(), emptyList()); runCurrent()
            vm.write("power", "true"); runCurrent(); vm.write("other", "true"); runCurrent()
            vm.selectChannel(PanelTransport.MQTT); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(listOf("power"), ble.writes)
            assertTrue(mqtt.writes.isEmpty())
            assertTrue(vm.state.value.activeOperations.isEmpty())
            assertTrue(vm.state.value.operationMessages.isEmpty())
        } finally { gate.complete(Unit); store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun missingOperationOnSelectedChannelDoesNotBorrowOtherChannelBinding() {
        val base = panel().asChannelPanel()
        val partial = base.copy(bindings = listOf(base.bindings.single().copy(write = null, controlTransport = PanelTransport.MQTT)))
        val imported = PanelJson.decode(PanelJson.encode(partial))
        assertEquals(emptyList(), validatePanel(imported))
        val ble = imported.forActiveChannel(PanelTransport.BLE)
        assertEquals(emptyList(), validatePanel(ble))
        assertFalse(ble.bindings.single().canWrite)
        assertTrue(ble.bindings.single().canRead)
        assertNull(ble.bindings.single().publish)
        val mqtt = imported.forActiveChannel(PanelTransport.MQTT)
        assertTrue(mqtt.bindings.single().canWrite)
        assertFalse(mqtt.bindings.single().canRead)
        assertNull(mqtt.bindings.single().read)
    }

    @Test fun selectedStateSurvivesOtherChannelChangesAndResubscribesAfterReconnect() = runTest {
        val ble = FakeOps(); val mqtt = FakeOps()
        val routes = MutableStateFlow<Map<PanelTransport, PanelOperations>>(emptyMap())
        val events = mutableListOf<PanelObservation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { DualPanelOperations(routes).observeEvents(panel(), "power").toList(events) }
        runCurrent(); assertEquals(PanelObservation.Offline, events.last())
        routes.value = mapOf(PanelTransport.BLE to ble, PanelTransport.MQTT to mqtt); runCurrent()
        mqtt.events.emit(PanelObservation.Value("true")); runCurrent()
        routes.value = mapOf(PanelTransport.MQTT to mqtt); runCurrent()
        assertEquals(PanelObservation.Value("true"), events.last())
        assertEquals(1, mqtt.observers)
        assertEquals(0, ble.observers)
        routes.value = emptyMap(); runCurrent()
        assertEquals(PanelObservation.Offline, events.last())
        val replacement = FakeOps()
        routes.value = mapOf(PanelTransport.MQTT to replacement); runCurrent()
        mqtt.events.emit(PanelObservation.Value("stale")); runCurrent()
        replacement.events.emit(PanelObservation.Value("false")); runCurrent()
        assertEquals(PanelObservation.Value("false"), events.last())
        assertFalse(events.contains(PanelObservation.Value("stale")))
        assertTrue(mqtt.writes.isEmpty() && replacement.writes.isEmpty())
    }

    @Test fun lateInitialReadCannotReplaceNewerNotification() = runTest {
        val gate = CompletableDeferred<String>()
        val ble = FakeOps(read = { gate.await() })
        val routes = MutableStateFlow<Map<PanelTransport, PanelOperations>>(mapOf(PanelTransport.BLE to ble))
        val p = panel().let { it.copy(bindings = listOf(it.bindings.single().copy(stateTransport = PanelTransport.BLE))) }
        val events = mutableListOf<PanelObservation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { DualPanelOperations(routes).observeEvents(p, "power").toList(events) }
        runCurrent(); ble.events.emit(PanelObservation.Value("true")); runCurrent()
        gate.complete("false"); runCurrent()
        assertEquals(PanelObservation.Value("true"), events.last())
        assertFalse(events.contains(PanelObservation.Value("false")))
    }

    @Test fun subscriptionFailureKeepsWatchingForExplicitReconnect() = runTest {
        val bad = object : PanelOperations by FakeOps() {
            override fun observeEvents(definition: PanelDefinition, capabilityId: String): Flow<PanelObservation> = flow { error("subscription rejected") }
        }
        val routes = MutableStateFlow<Map<PanelTransport, PanelOperations>>(mapOf(PanelTransport.MQTT to bad))
        val events = mutableListOf<PanelObservation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { DualPanelOperations(routes).observeEvents(panel(), "power").toList(events) }
        runCurrent(); assertIs<PanelObservation.Failed>(events.last())
        val replacement = FakeOps()
        routes.value = mapOf(PanelTransport.MQTT to replacement); runCurrent()
        replacement.events.emit(PanelObservation.Value("true")); runCurrent()
        assertEquals(PanelObservation.Value("true"), events.last())
    }

    @Test fun oldConnectionWriteCompletionIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ble = FakeOps(write = { gate.await() })
        val routes = MutableStateFlow<Map<PanelTransport, PanelOperations>>(mapOf(PanelTransport.BLE to ble))
        val result = async { runCatching { DualPanelOperations(routes).write(panel(), "power", "true") } }
        runCurrent(); routes.value = mapOf(PanelTransport.BLE to FakeOps()); gate.complete(Unit); runCurrent()
        assertIs<CancellationException>(result.await().exceptionOrNull())
    }

    @Test fun losingControlCancelsItsQueuedCommandsButKeepsOtherSourceAndCommands() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val gate = CompletableDeferred<Unit>()
        try {
            val base = panel()
            val p = base.copy(capabilities = base.capabilities + PanelCapability("other", "Other", CapabilityType.BOOLEAN),
                bindings = base.bindings + base.bindings.single().copy(capabilityId = "other", controlTransport = PanelTransport.MQTT))
            val ble = FakeOps(); val mqtt = FakeOps(write = { gate.await() })
            val routes = MutableStateFlow<Map<PanelTransport, PanelOperations>>(mapOf(PanelTransport.BLE to ble, PanelTransport.MQTT to mqtt))
            val vm = PanelViewModel(p, codec = PanelJson)
            store.put("panel", vm); vm.attachDual(DualPanelOperations(routes)); vm.setDualConnections(routes.value.keys, emptySet(), emptyList()); runCurrent()
            mqtt.events.emit(PanelObservation.Value("true")); runCurrent()
            vm.write("other", "true"); runCurrent()
            vm.write("power", "true"); runCurrent()
            assertEquals(setOf("power", "other"), vm.state.value.activeOperations)
            routes.value = mapOf(PanelTransport.MQTT to mqtt)
            vm.setDualConnections(routes.value.keys, emptySet(), emptyList()); runCurrent()
            assertEquals(setOf("other"), vm.state.value.activeOperations)
            assertEquals("true", vm.state.value.values["power"])
            assertFalse(vm.state.value.widget("power").enabled)
            assertTrue(vm.state.value.widget("power").readEnabled)
            routes.value = mapOf(PanelTransport.BLE to ble, PanelTransport.MQTT to mqtt)
            vm.setDualConnections(routes.value.keys, emptySet(), emptyList())
            gate.complete(Unit); runCurrent()
            assertTrue(ble.writes.isEmpty())
            assertEquals(listOf("other"), mqtt.writes)
            assertFalse(vm.state.value.busy)
            routes.value = mapOf(PanelTransport.BLE to ble); vm.setDualConnections(routes.value.keys, emptySet(), emptyList()); runCurrent()
            assertNull(vm.state.value.values["power"])
            assertTrue(vm.state.value.widget("power").enabled)
            assertFalse(vm.state.value.widget("power").readEnabled)
        } finally { gate.complete(Unit); store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private class FakeOps(val read: suspend () -> String = { "false" }, val write: suspend () -> Unit = {}) : PanelOperations {
        val writes = mutableListOf<String>()
        val events = MutableSharedFlow<PanelObservation>()
        var observers = 0
        override suspend fun read(definition: PanelDefinition, capabilityId: String) = read()
        override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) { writes += capabilityId; write() }
        override fun observe(definition: PanelDefinition, capabilityId: String) = observeEvents(definition, capabilityId).mapNotNull { (it as? PanelObservation.Value)?.value }
        override fun observeEvents(definition: PanelDefinition, capabilityId: String): Flow<PanelObservation> = flow {
            observers++; emit(PanelObservation.Ready); emitAll(events)
        }
    }
}
