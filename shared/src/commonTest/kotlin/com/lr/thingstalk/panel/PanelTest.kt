@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.panel

import com.lr.thingstalk.data.panel.*
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class PanelTest {
    private val definition = defaultDemoPanel()

    @Test fun configurationRoundTripsAndRejectsUnknownSchema() {
        assertTrue(validatePanel(definition).isEmpty())
        assertEquals(definition, PanelJson.decode(PanelJson.encode(definition)))
        assertFailsWith<IllegalArgumentException> { PanelJson.encode(definition.copy(schemaVersion = 99)) }
        assertFailsWith<IllegalArgumentException> { PanelJson.decode(PanelJson.encode(definition).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")) }
        assertFailsWith<IllegalArgumentException> { PanelJson.decode(" ".repeat(128 * 1024 + 1)) }
    }

    @Test fun invalidReferencesTypesRangesAndBindingCodecsAreRejected() {
        assertTrue(validatePanel(definition.copy(widgets = listOf(definition.widgets[0].copy(capabilityId = "missing")))).isNotEmpty())
        assertTrue(validatePanel(definition.copy(widgets = listOf(definition.widgets[0].copy(type = WidgetType.SLIDER)))).isNotEmpty())
        assertTrue(validatePanel(definition.copy(capabilities = definition.capabilities + definition.capabilities[0])).isNotEmpty())
        assertTrue(validatePanel(definition.copy(capabilities = definition.capabilities.map { if (it.id == "brightness") it.copy(minimum = 101.0) else it })).isNotEmpty())
        assertTrue(validatePanel(definition.copy(bindings = definition.bindings.map { if (it.capabilityId == "power") it.copy(read = it.read!!.copy(codec = CodecType.HEX)) else it })).isNotEmpty())
        assertTrue(validatePanel(definition.copy(bindings = definition.bindings.map { if (it.capabilityId == "blink") it.copy(actionValue = "abc") else it })).isNotEmpty())
        assertTrue(validatePanel(definition.copy(bindings = definition.bindings.map { if (it.capabilityId == "power") it.copy(read = it.read!!.copy(serviceUuid = "ffe0")) else it })).isNotEmpty())
    }

    @Test fun readWriteAndFixedActionUseTheBleTransport() = runTest {
        val connection = DemoPanelBleConnection()
        connection.connect()
        val operations = BlePanelOperations(connection)
        assertEquals("65", operations.read(definition, "brightness"))
        assertEquals("true", operations.read(definition, "power"))
        operations.write(definition, "brightness", "100")
        operations.write(definition, "power", "false")
        assertEquals("100", operations.read(definition, "brightness"))
        assertEquals("false", operations.read(definition, "power"))
        operations.write(definition, "blink", "malicious override")
        assertEquals(1, connection.actionCount)
        connection.close()
    }

    @Test fun rangeAndStrictCodecFailuresDoNotWrite() = runTest {
        val connection = DemoPanelBleConnection()
        connection.connect()
        val operations = BlePanelOperations(connection)
        listOf("101", "-1", "256", "65.5", "NaN", " 65").forEach {
            assertFailsWith<IllegalArgumentException> { operations.write(definition, "brightness", it) }
        }
        assertFailsWith<IllegalArgumentException> { operations.write(definition, "power", "1") }
        assertEquals("65", operations.read(definition, "brightness"))
        connection.write(BleCharacteristicId(DemoPanelGatt.SERVICE, DemoPanelGatt.POWER), byteArrayOf(2))
        assertFailsWith<IllegalArgumentException> { operations.read(definition, "power") }
        connection.close()
    }

    @Test fun discoveryPropertiesAndNegotiatedWriteLimitAreEnforced() = runTest {
        val connection = DemoPanelBleConnection()
        connection.connect()
        val operations = BlePanelOperations(connection)
        val readOnlyTarget = definition.copy(bindings = definition.bindings.map {
            if (it.capabilityId == "brightness") it.copy(write = it.write!!.copy(characteristicUuid = DemoPanelGatt.TEMPERATURE)) else it
        })
        assertFailsWith<IllegalArgumentException> { operations.write(readOnlyTarget, "brightness", "50") }
        val oversized = definition.copy(bindings = definition.bindings.map {
            if (it.capabilityId == "blink") it.copy(actionValue = "01".repeat(21)) else it
        })
        assertFailsWith<IllegalArgumentException> { operations.write(oversized, "blink", "") }
        assertEquals(0, connection.actionCount)
        connection.close()
    }

    @Test fun notificationsUpdateCancelAndResumeAcrossExplicitReconnect() = runTest {
        val connection = DemoPanelBleConnection()
        connection.connect()
        val operations = BlePanelOperations(connection)
        val received = mutableListOf<String>()
        val job = backgroundScope.launch { operations.observe(definition, "brightness").toList(received) }
        runCurrent()
        assertEquals(listOf("65"), received)
        assertEquals(1, connection.activeSubscriptions)
        operations.write(definition, "brightness", "42")
        runCurrent()
        assertEquals(listOf("65", "42"), received)
        connection.disconnect()
        runCurrent()
        assertEquals(0, connection.activeSubscriptions)
        connection.connect()
        runCurrent()
        assertEquals(1, connection.activeSubscriptions)
        job.cancelAndJoin()
        assertEquals(0, connection.activeSubscriptions)
        val closingJob = backgroundScope.launch { operations.observe(definition, "power").collect() }
        runCurrent()
        connection.close()
        runCurrent()
        assertTrue(closingJob.isCompleted)
        assertEquals(0, connection.activeSubscriptions)
    }

    @Test fun cancellationFromTransportIsPreserved() = runTest {
        val demo = DemoPanelBleConnection()
        demo.connect()
        val connection = object : BleConnection by demo {
            override suspend fun read(characteristic: BleCharacteristicId): ByteArray = throw CancellationException("cancel read")
            override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) { throw CancellationException("cancel write") }
        }
        val operations = BlePanelOperations(connection)
        assertFailsWith<CancellationException> { operations.read(definition, "brightness") }
        assertFailsWith<CancellationException> { operations.write(definition, "brightness", "45") }
        demo.close()
    }

    @Test fun notificationReadyCallbackMayWriteARequest() = runTest {
        val connection = DemoPanelBleConnection()
        connection.connect()
        val id = BleCharacteristicId(DemoPanelGatt.SERVICE, DemoPanelGatt.BRIGHTNESS)
        val value = connection.notifications(id) { connection.write(id, byteArrayOf(70)) }.first()
        assertContentEquals(byteArrayOf(70), value)
        connection.close()
    }
}
