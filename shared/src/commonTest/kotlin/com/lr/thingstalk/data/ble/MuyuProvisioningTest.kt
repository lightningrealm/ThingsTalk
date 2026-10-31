package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.data.panel.PanelJson
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MuyuProvisioningTest {
    private fun form() = SetupPanel("setup", "网络设置", "按设备说明开启设置",
        "12345678-1234-1234-1234-123456789001", "12345678-1234-1234-1234-123456789002", "12345678-1234-1234-1234-123456789003",
        listOf(SetupSection(1, "无线网络", listOf(SetupField(1, "网络名称", 1, 32), SetupField(2, "密钥", 0, 64, secret = true, rule = SetupFieldRule.WIFI_PSK)))))
    internal class Connection(val command: BleCharacteristicId = MuyuProvisioning.command, val status: BleCharacteristicId = MuyuProvisioning.status) : BleConnection {
        override val state = MutableStateFlow(BleConnectionState.CONNECTED)
        var response = byteArrayOf(0xD1.toByte(),1,0x79,0,0,0,0,0,0,0,0,0,0,0)
        val writes = mutableListOf<ByteArray>()
        var fieldReads = 0
        var fieldValue = "saved".encodeToByteArray()
        var authenticationFailures = 0
        var failRead = false
        var failWrite = false
        var failStatus = false
        var cancelWrite = false
        var failCommit = false
        var wrongOffset = false
        var staleRequest = false
        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override fun close() {}
        override suspend fun discoverServices() = emptyList<BleService>()
        override suspend fun maximumWriteLength(mode: BleWriteMode) = 20
        override suspend fun read(characteristic: BleCharacteristicId) = if (characteristic == status) { check(!failStatus); response.copyOf() } else { fieldReads++; if (authenticationFailures-- > 0) throw BleException("wrapper", IllegalStateException("Error Domain=CBATTErrorDomain Code=5 \"authentication required\"")); check(!failRead) { "read failed" }; fieldValue.copyOf() }
        override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit) = emptyFlow<ByteArray>()
        override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) {
            if (cancelWrite) throw kotlinx.coroutines.CancellationException("cancelled")
            if (failWrite) error("secret transport detail")
            assertEquals(command, characteristic); assertEquals(BleWriteMode.WITH_RESPONSE, mode)
            assertTrue(value.size <= 20); writes += value.copyOf()
            val op = value[0].toInt() and 255
            val offset = (value[4].toInt() and 255) or ((value[5].toInt() and 255) shl 8)
            val end = offset + if (op == 0xB2) value.size - 6 else 0
            response = byteArrayOf(0xD1.toByte(),1,0x79,value[1],value[2],value[0],
                (if (op == 0xB3) if (failCommit) 6 else 2 else 1).toByte(),value[3],end.toByte(),(end ushr 8).toByte(),1,0,0,0)
            if (wrongOffset) response[8] = (response[8] + 1).toByte()
            if (staleRequest) response[3] = (response[3] - 1).toByte()
        }
    }
    @Test fun prepareUnconfiguredGroupReadsProtectedFieldBeforeEditingWithoutWriting() = runTest {
        val base = form()
        val definition = base.copy(sections = listOf(base.sections.single().copy(fields = base.sections.single().fields.map {
            if (it.id == 1) it.copy(readUuid = "12345678-1234-1234-1234-123456789004") else it
        })))
        val raw = Connection(definition.command, definition.status)
        val client = BleSetupPanelClient(raw, definition)
        raw.response[2] = 1 // Unlocked, neither group saved.
        raw.failRead = true
        assertEquals(SetupFailureKind.PREPARE_READ, assertFailsWith<SetupPanelFailure> { client.readValues() }.kind)
        assertTrue(raw.writes.isEmpty())
        raw.failRead = false; raw.fieldValue = byteArrayOf()
        assertEquals(mapOf(1 to ""), client.readValues())
        assertEquals(2, raw.fieldReads)
        assertTrue(raw.writes.isEmpty())
        // Empty initial values are valid; saved read failures are not empty configuration.
        raw.failRead = true
        raw.response[2] = 9
        assertFailsWith<IllegalStateException> { client.readValues() }
        raw.failRead = false; raw.fieldValue = byteArrayOf()
        assertEquals(mapOf(1 to ""), client.readValues())
        raw.response[2] = 0
        assertFailsWith<IllegalArgumentException> { client.readValues() }
        raw.response = byteArrayOf()
        assertFailsWith<SetupPanelFailure> { client.readValues() }
    }

    @Test fun submissionErrorsDistinguishTransportFirmwareAndCancellationWithoutSecrets() = runTest {
        val form = form()
        val raw = Connection(form.command, form.status)
        val client = BleSetupPanelClient(raw, form)
        raw.failWrite = true
        val write = assertFailsWith<SetupPanelFailure> { client.submit(1, mapOf(1 to "wifi", 2 to "password")) }
        assertEquals(SetupFailureKind.WRITE, write.kind)
        assertEquals(0xB1, write.opcode)
        assertFalse("secret transport detail" in write.toString())
        assertNull(write.cause)
        raw.failWrite = false; raw.failStatus = true
        assertEquals(SetupFailureKind.STATUS_READ, assertFailsWith<SetupPanelFailure> { client.submit(1, mapOf(1 to "wifi")) }.kind)
        raw.failStatus = false; raw.failCommit = true
        val reject = assertFailsWith<SetupPanelFailure> { client.submit(1, mapOf(1 to "wifi")) }
        assertEquals(SetupFailureKind.REJECTED, reject.kind)
        assertEquals(6, reject.reply?.result)
        raw.cancelWrite = true
        assertFailsWith<kotlinx.coroutines.CancellationException> { client.submit(1, mapOf(1 to "wifi")) }
    }

    @Test fun nativeFailureMetadataDoesNotExposeDescriptionsOrCredentials() {
        val native = IllegalStateException("Error Domain=CBATTErrorDomain Code=5 \"secret123\" UserInfo={password=secret123}")
        assertEquals("CBATTErrorDomain / code=5", bleFailureDiagnostic(BleException("wrapper", native)))
        assertEquals("CBErrorDomain / code=6", bleFailureDiagnostic(Exception("Error Domain=CBErrorDomain Code=6 \"detail\"")))
        assertEquals("BLE_NOT_CONNECTED", bleFailureDiagnostic(Exception("wrapper", IllegalStateException("BLE connection is closed"))))
        assertNull(bleFailureDiagnostic(Exception("secret123")))
        assertNull(bleFailureDiagnostic(Exception("Error Domain=secret123 Code=5")))
    }

    @Test fun preparationRechecksAuthenticationReadAndStopsWithoutWriting() = runTest {
        val base = form()
        val definition = base.copy(sections = listOf(base.sections.single().copy(fields = base.sections.single().fields.map {
            if (it.id == 1) it.copy(readUuid = "12345678-1234-1234-1234-123456789004") else it
        })))
        val raw = Connection(definition.command, definition.status)
        val client = BleSetupPanelClient(raw, definition)
        raw.response[2] = 1
        raw.authenticationFailures = 2
        assertEquals(mapOf(1 to ""), client.readValues())
        assertEquals(3, raw.fieldReads)
        assertTrue(raw.writes.isEmpty())
        raw.authenticationFailures = 100
        val before = raw.fieldReads
        val failure = assertFailsWith<SetupPanelFailure> { client.readValues() }
        assertEquals(SetupFailureKind.PREPARE_READ, failure.kind)
        assertEquals(15, raw.fieldReads - before)
        assertTrue(raw.writes.isEmpty())
        raw.authenticationFailures = 100
        val preparing = launch { client.readValues() }
        runCurrent()
        val readsAtCancel = raw.fieldReads
        preparing.cancel()
        advanceUntilIdle()
        assertEquals(readsAtCancel, raw.fieldReads)
        assertTrue(raw.writes.isEmpty())
    }

    @Test fun exactPacketAndStatus() {
        assertContentEquals(byteArrayOf(0xB2.toByte(),0x34,0x12,3,14,0,65,66), MuyuProvisioning.packet(0xB2,0x1234,3,14,byteArrayOf(65,66)))
        assertEquals("5c52a00a-7d8a-4b19-9c63-414950415353", MuyuProvisioning.fields.getValue(6).characteristicUuid)
        val s = MuyuProvisioning.parse(byteArrayOf(0xD1.toByte(),1,0x7F,0x34,0x12,0xB3.toByte(),2,0,0,0,4,3,2,1))
        assertTrue(s.unlocked && s.wifiConnected && s.mqttConnected && s.wifiPasswordSaved && s.mqttPasswordSaved)
        assertEquals(0x1234, s.request); assertEquals(0x01020304L,s.revision)
        assertFailsWith<IllegalArgumentException> { MuyuProvisioning.parse(byteArrayOf(1,2,3)) }
    }
    @Test fun wifiChunksPreserveUtf8AndOmitUnchangedPassword() = runTest {
        val c = Connection(); val client = MuyuProvisioningClient(c)
        val ssid = "木鱼测试网络-12345"
        assertEquals(2,client.saveWifi(ssid,null).result)
        assertEquals(0xB1,c.writes.first()[0].toInt() and 255);assertEquals(0xB3,c.writes.last()[0].toInt() and 255)
        val chunks = c.writes.filter { it[0] == 0xB2.toByte() }
        assertTrue(chunks.all { it[3] == 1.toByte() })
        assertContentEquals(ssid.encodeToByteArray(),chunks.flatMap { it.drop(6) }.toByteArray())
    }
    @Test fun explicitEmptyPasswordAndMqttFields() = runTest {
        val c = Connection(); val client = MuyuProvisioningClient(c)
        client.saveMqtt("ws://broker.example:9001/mqtt","account","","muyu-01")
        val chunks = c.writes.filter { it[0] == 0xB2.toByte() }
        assertEquals(setOf(3,4,5,6),chunks.map { it[3].toInt() }.toSet())
        assertEquals(6,chunks.single { it[3] == 5.toByte() }.size)
    }
    @Test fun lockedAndFailedPersistenceAreNotSuccessOrRetried() = runTest {
        val c = Connection();val client = MuyuProvisioningClient(c)
        c.response[2]=0
        assertFailsWith<IllegalArgumentException> { client.saveWifi("wifi","password") }
        assertTrue(c.writes.isEmpty())
        c.response[2]=1;c.failCommit=true
        assertFailsWith<IllegalStateException> { client.saveWifi("wifi","password") }
        assertEquals(1,c.writes.count { it[0]==0xB3.toByte() })
    }
    @Test fun provisioningBytesNeverEnterTrafficHistory() = runTest {
        val c = Connection();val log = InMemoryBleTraffic();val traced = TracingBleConnection(c,log,backgroundScope)
        val payload = MuyuProvisioning.packet(0xB2,1,2,0,"secret123".encodeToByteArray())
        traced.write(MuyuProvisioning.command,payload)
        traced.read(MuyuProvisioning.fields.getValue(1))
        val sensitive = log.entries.value.filter { MuyuProvisioning.sensitive(it.characteristic) }
        assertTrue(sensitive.isNotEmpty());assertTrue(sensitive.all { it.hex == null && it.detail == "设备配置数据已隐藏" })
        assertTrue(log.entries.value.none { "secret123" in it.toString() })
        traced.close()
    }
    @Test fun configuredUuidFormChunksAndRedactsWithoutHardwareSpecificConstants() = runTest {
        val form = form()
        val raw = Connection(form.command, form.status)
        val log = InMemoryBleTraffic()
        var protected = form.sensitiveCharacteristics()
        log.protect { protected }
        val traced = TracingBleConnection(raw, log, backgroundScope)
        val client = BleSetupPanelClient(traced, form)
        val ssid = "测试网络-123456789"
        client.submit(1, mapOf(1 to ssid))
        val chunks = raw.writes.filter { it[0] == 0xB2.toByte() }
        assertTrue(chunks.all { it[3] == 1.toByte() })
        assertContentEquals(ssid.encodeToByteArray(), chunks.flatMap { it.drop(6) }.toByteArray())
        client.submit(1, mapOf(1 to "wifi", 2 to ""))
        assertEquals(6, raw.writes.last { it[0] == 0xB2.toByte() && it[3] == 2.toByte() }.size)
        protected = emptyList()
        client.submit(1, mapOf(1 to "wifi", 2 to "secret123"))
        assertTrue(log.entries.value.filter { it.characteristic == form.command }.all { it.hex == null })
        assertTrue(log.entries.value.none { "secret123" in it.toString() })
        raw.failCommit = true
        val commits = raw.writes.count { it[0] == 0xB3.toByte() }
        assertFailsWith<IllegalStateException> { client.submit(1, mapOf(1 to "wifi")) }
        assertEquals(commits + 1, raw.writes.count { it[0] == 0xB3.toByte() })
        traced.close()
    }
    @Test fun wrongAcknowledgementAndTimeoutNeverCommitOrReplay() = runTest {
        val form = form()
        val raw = Connection(form.command, form.status)
        val client = BleSetupPanelClient(raw, form)
        raw.wrongOffset = true
        assertFailsWith<IllegalStateException> { client.submit(1, mapOf(1 to "wifi")) }
        assertEquals(1, raw.writes.size)
        raw.wrongOffset = false; raw.staleRequest = true
        assertEquals(SetupFailureKind.ACK_TIMEOUT, assertFailsWith<SetupPanelFailure> { client.submit(1, mapOf(1 to "wifi")) }.kind)
        assertEquals(2, raw.writes.size)
        assertTrue(raw.writes.none { it[0] == 0xB3.toByte() })
    }
    @Test fun v7FormRoundTripAndRejectInvalidOrUnsupportedDefinitions() {
        val panel = PanelDefinition(schemaVersion = 7, title = "设置", setupPanels = listOf(form()))
        assertEquals(panel, PanelJson.decode(PanelJson.encode(panel)))
        assertTrue(validatePanel(panel.copy(schemaVersion = 3)).isNotEmpty())
        assertTrue(validatePanel(panel.copy(transport = PanelTransport.MQTT)).isNotEmpty())
        val invalid = form().copy(sections = listOf(SetupSection(1, "设置", listOf(SetupField(2, "密码", maxBytes = 64, secret = true, readUuid = form().commandUuid)))))
        assertTrue(validateSetupPanels(listOf(invalid)).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { form().sections.first().fields.first().validateValue("木".repeat(11)) }
        val dual = panel.asDual().asChannelPanel(PanelTransport.BLE)
        assertEquals(7, dual.schemaVersion)
        assertEquals(dual, PanelJson.decode(PanelJson.encode(dual)))
        assertTrue(dual.forTransport(PanelTransport.BLE).setupPanels.isEmpty())
        assertFalse("setupPanels" in PanelJson.encode(PanelDefinition(schemaVersion = 6, title = "旧面板", transport = PanelTransport.DUAL, defaultChannel = PanelTransport.BLE)))
    }

}
