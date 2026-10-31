package com.lr.thingstalk.domain.ble

/** Legacy UUIDs retained for diagnostic redaction and protocol compatibility tests. */
object MuyuProvisioning {
    const val SERVICE = "5c52a001-7d8a-4b19-9c63-414950415353"
    fun characteristic(number: Int) = BleCharacteristicId(SERVICE, "5c52a${number.toString(16).padStart(3, '0')}-7d8a-4b19-9c63-414950415353")
    val command = characteristic(5)
    val status = characteristic(6)
    val fields = mapOf(1 to characteristic(7), 3 to characteristic(8), 4 to characteristic(9), 6 to characteristic(10))
    fun sensitive(id: BleCharacteristicId?) = id?.serviceUuid.equals(SERVICE, true) &&
        (id?.characteristicUuid.equals(command.characteristicUuid, true) || fields.values.any { it.characteristicUuid.equals(id?.characteristicUuid, true) })
    fun supported(services: List<BleService>): Boolean {
        val all = services.flatMap { it.characteristics }
        val required = listOf(command to BleProperty.WRITE, status to BleProperty.READ) + fields.values.map { it to BleProperty.READ }
        return required.all { (id, property) -> all.filter { it.id.serviceUuid.equals(id.serviceUuid, true) && it.id.characteristicUuid.equals(id.characteristicUuid, true) }.singleOrNull()?.properties?.contains(property) == true }
    }
    fun packet(op: Int, request: Int, field: Int = 0, offset: Int = 0, payload: ByteArray = byteArrayOf()) =
        ChunkedBleSettingsProtocol.packet(op, request, field, offset, payload)
    fun parse(bytes: ByteArray) = ChunkedBleSettingsProtocol.parse(bytes)

}

data class NetworkSetupValues(val ssid: String, val uri: String, val username: String, val device: String)
