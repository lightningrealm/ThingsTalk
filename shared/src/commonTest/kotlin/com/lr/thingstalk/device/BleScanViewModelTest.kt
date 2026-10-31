@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.device

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.presentation.ble.BleScanFilter
import com.lr.thingstalk.presentation.ble.BleScanViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

class BleScanViewModelTest {
    @Test fun deduplicatesResultsAndStopsAfterDeadline() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var released = false
        val scanner = scanner {
            try {
                emit(BleScanDevice("a", "First", -70, emptyList()))
                emit(BleScanDevice("b", "Second", -50, emptyList()))
                emit(BleScanDevice("a", "First updated", -30, emptyList()))
                awaitCancellation()
            } finally { released = true }
        }
        val model = BleScanViewModel(scanner)
        val store = ViewModelStore().apply { put("scan", model) }
        try {
            model.start(); runCurrent()
            assertEquals(listOf("a", "b"), model.state.value.devices.map { it.id })
            assertEquals("First updated", model.state.value.devices.first().name)
            advanceTimeBy(15_001); runCurrent()
            assertFalse(model.state.value.scanning)
            assertTrue(released)
            assertNull(model.state.value.error)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun rapidRestartWaitsForNativeScanCleanup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var active = 0
        var starts = 0
        val scanner = scanner {
            check(active == 0) { "Concurrent scan" }
            active++; starts++
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { delay(100); active-- } }
        }
        val model = BleScanViewModel(scanner)
        val store = ViewModelStore().apply { put("scan", model) }
        try {
            model.start(); runCurrent()
            model.stop(); model.start(); runCurrent()
            assertEquals(1, starts)
            advanceTimeBy(101); runCurrent()
            assertEquals(2, starts)
            assertTrue(model.state.value.scanning)
            assertNull(model.state.value.error)
        } finally { store.clear(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun filtersExistingResultsAndClearRestoresThemWithoutRestartingScan() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var starts = 0
        val scanner = scanner {
            starts++
            emit(BleScanDevice("a", "Desk sensor", -60, listOf("180D")))
            emit(BleScanDevice("b", null, -45, emptyList()))
            emit(BleScanDevice("c", "Lamp", -80, listOf("180F")))
            awaitCancellation()
        }
        val model = BleScanViewModel(scanner)
        val store = ViewModelStore().apply { put("scan", model) }
        try {
            model.start(); runCurrent()
            val allResults = model.state.value.devices
            assertEquals(listOf("b", "a", "c"), allResults.map { it.id })

            model.setFilter(BleScanFilter(namedOnly = true))
            assertEquals(listOf("a", "c"), model.state.value.visibleDevices.map { it.id })
            model.setFilter(BleScanFilter(query = "lamp", minimumRssi = -65))
            assertTrue(model.state.value.visibleDevices.isEmpty())
            model.setFilter(BleScanFilter(serviceUuid = "80d"))
            assertEquals(listOf("a"), model.state.value.visibleDevices.map { it.id })
            model.setFilter(BleScanFilter(serviceUuid = "invalid"))
            assertNotNull(model.state.value.filter.serviceUuidError)
            assertTrue(model.state.value.visibleDevices.isEmpty())
            assertEquals(allResults, model.state.value.devices)

            model.clearFilter(); runCurrent()
            assertEquals(allResults, model.state.value.visibleDevices)
            assertFalse(model.state.value.filter.isActive)
            assertTrue(model.state.value.scanning)
            assertEquals(1, starts)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun repeatedAdvertisementsReevaluateNameSignalAndServices() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val advertisements = Channel<BleScanDevice>(Channel.UNLIMITED)
        val model = BleScanViewModel(scanner { for (device in advertisements) emit(device) })
        val store = ViewModelStore().apply { put("scan", model) }
        try {
            model.setFilter(BleScanFilter(query = "sensor", namedOnly = true, minimumRssi = -70, serviceUuid = "80d"))
            model.start(); runCurrent()
            val device = BleScanDevice("a", null, -80, emptyList())
            advertisements.send(device); runCurrent()
            assertTrue(model.state.value.visibleDevices.isEmpty())

            advertisements.send(device.copy(name = "Sensor", rssi = -70, serviceUuids = listOf("180D"))); runCurrent()
            assertEquals(listOf("a"), model.state.value.visibleDevices.map { it.id })
            advertisements.send(device.copy(name = "Sensor", rssi = -71, serviceUuids = listOf("180D"))); runCurrent()
            assertTrue(model.state.value.visibleDevices.isEmpty())
            advertisements.send(device.copy(name = "Sensor", rssi = -60, serviceUuids = listOf("180F"))); runCurrent()
            assertTrue(model.state.value.visibleDevices.isEmpty())
            advertisements.send(device.copy(name = "Other", rssi = -60, serviceUuids = listOf("180D"))); runCurrent()
            assertTrue(model.state.value.visibleDevices.isEmpty())
            assertEquals(1, model.state.value.devices.size)
            assertEquals("Other", model.state.value.devices.single().name)
        } finally { store.clear(); runCurrent(); advertisements.close(); Dispatchers.resetMain() }
    }

    @Test fun manualStopTimeoutAndRescanPreserveFilterWhileRescanClearsResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var starts = 0
        val model = BleScanViewModel(scanner {
            starts++
            emit(BleScanDevice("a", "Sensor", -55, emptyList()))
            awaitCancellation()
        })
        val store = ViewModelStore().apply { put("scan", model) }
        val filter = BleScanFilter(namedOnly = true, minimumRssi = -70)
        try {
            model.setFilter(filter)
            model.start(); runCurrent()
            model.stop(); runCurrent()
            assertEquals(filter, model.state.value.filter)
            assertEquals(1, model.state.value.visibleDevices.size)
            assertFalse(model.state.value.scanning)

            model.start()
            assertEquals(filter, model.state.value.filter)
            assertTrue(model.state.value.devices.isEmpty())
            runCurrent()
            model.start(); runCurrent()
            assertEquals(2, starts)
            advanceTimeBy(15_001); runCurrent()
            assertEquals(filter, model.state.value.filter)
            assertFalse(model.state.value.scanning)
            assertEquals(1, model.state.value.visibleDevices.size)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private fun scanner(source: suspend kotlinx.coroutines.flow.FlowCollector<BleScanDevice>.() -> Unit) = object : BleScanner {
        override fun scan(): Flow<BleScanDevice> = flow(source)
        override fun connection(deviceId: String): BleConnection = error("Not used")
        override fun close() = Unit
    }
}
