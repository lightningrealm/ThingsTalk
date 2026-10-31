@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

/** Fictional demo profile. Never assume these UUIDs describe a real lamp. */
object DemoPanelGatt {
    const val SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val POWER = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val BRIGHTNESS = "0000ffe2-0000-1000-8000-00805f9b34fb"
    const val TEMPERATURE = "0000ffe3-0000-1000-8000-00805f9b34fb"
    const val BLINK = "0000ffe4-0000-1000-8000-00805f9b34fb"
    private val readWrite = setOf(BleProperty.READ, BleProperty.WRITE, BleProperty.WRITE_WITHOUT_RESPONSE, BleProperty.NOTIFY)
    val profile: List<BleService> = listOf(BleService(SERVICE, listOf(
        characteristic(POWER, readWrite),
        characteristic(BRIGHTNESS, readWrite),
        characteristic(TEMPERATURE, setOf(BleProperty.READ, BleProperty.NOTIFY)),
        characteristic(BLINK, setOf(BleProperty.WRITE, BleProperty.WRITE_WITHOUT_RESPONSE)),
    )))
    private fun characteristic(uuid: String, properties: Set<BleProperty>) = BleCharacteristic(
        BleCharacteristicId(SERVICE, uuid), properties,
        if (BleProperty.NOTIFY in properties) listOf(BleDescriptor("00002902-0000-1000-8000-00805f9b34fb")) else emptyList(),
    )
}

fun defaultDemoPanel(): PanelDefinition {
    fun operation(uuid: String, codec: CodecType) = BleOperationBinding(DemoPanelGatt.SERVICE, uuid, codec)
    val power = operation(DemoPanelGatt.POWER, CodecType.BOOLEAN)
    val brightness = operation(DemoPanelGatt.BRIGHTNESS, CodecType.UINT8)
    val temperature = operation(DemoPanelGatt.TEMPERATURE, CodecType.UINT8)
    return PanelDefinition(
        title = "桌面灯面板",
        capabilities = listOf(
            PanelCapability("power", "电源", CapabilityType.BOOLEAN),
            PanelCapability("brightness", "亮度", CapabilityType.NUMBER, 0.0, 100.0, "%"),
            PanelCapability("temperature", "温度", CapabilityType.NUMBER, 0.0, 100.0, "°C"),
            PanelCapability("blink", "闪灯定位", CapabilityType.ACTION),
        ),
        bindings = listOf(
            PanelBinding("power", power, power, power),
            PanelBinding("brightness", brightness, brightness, brightness),
            PanelBinding("temperature", read = temperature, notify = temperature),
            PanelBinding("blink", write = operation(DemoPanelGatt.BLINK, CodecType.HEX), actionValue = "01"),
        ),
        widgets = listOf(
            PanelWidget("power-widget", WidgetType.SWITCH, "电源", "power"),
            PanelWidget("brightness-widget", WidgetType.SLIDER, "亮度", "brightness"),
            PanelWidget("temperature-widget", WidgetType.VALUE, "环境温度", "temperature"),
            PanelWidget("blink-widget", WidgetType.BUTTON, "闪灯定位", "blink"),
        ),
    )
}

/** In-memory transport used by mock panels. Each subscription starts with the current demo value. */
class DemoPanelBleConnection : BleConnection {
    override val state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    private val closedState = MutableStateFlow(false)
    private val values = mutableMapOf(
        DemoPanelGatt.POWER to byteArrayOf(1),
        DemoPanelGatt.BRIGHTNESS to byteArrayOf(65),
        DemoPanelGatt.TEMPERATURE to byteArrayOf(24),
        DemoPanelGatt.BLINK to byteArrayOf(0),
    )
    // A bounded pending queue also permits an onReady callback to write its first request.
    private val events = values.keys.associateWith { MutableSharedFlow<ByteArray>(extraBufferCapacity = 64) }
    var activeSubscriptions = 0
        private set
    var actionCount = 0
        private set

    override suspend fun connect() {
        currentCoroutineContext().ensureActive()
        check(!closedState.value) { "演示连接已关闭" }
        state.value = BleConnectionState.CONNECTED
    }
    override suspend fun disconnect() { state.value = BleConnectionState.DISCONNECTED }
    override suspend fun discoverServices(): List<BleService> { requireConnected(); return DemoPanelGatt.profile }
    override suspend fun read(characteristic: BleCharacteristicId): ByteArray {
        currentCoroutineContext().ensureActive()
        checkProperty(characteristic, BleProperty.READ)
        return values.getValue(characteristic.characteristicUuid).copyOf()
    }
    override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) {
        currentCoroutineContext().ensureActive()
        checkProperty(characteristic, if (mode == BleWriteMode.WITH_RESPONSE) BleProperty.WRITE else BleProperty.WRITE_WITHOUT_RESPONSE)
        require(value.size <= maximumWriteLength(mode)) { "演示数据超过 20 字节" }
        val uuid = characteristic.characteristicUuid
        values[uuid] = value.copyOf()
        if (uuid == DemoPanelGatt.BLINK) actionCount++
        events.getValue(uuid).emit(value.copyOf())
    }
    override suspend fun maximumWriteLength(mode: BleWriteMode): Int { requireConnected(); return 20 }

    override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> =
        combine(state, closedState) { connectionState, closed -> connectionState to closed }
            .transformLatest { (connectionState, closed) ->
                if (closed) {
                    emit(null)
                } else if (connectionState == BleConnectionState.CONNECTED) {
                    checkProperty(characteristic, BleProperty.NOTIFY)
                    activeSubscriptions++
                    try {
                        emitAll(events.getValue(characteristic.characteristicUuid).onSubscription {
                            onReady()
                            emit(values.getValue(characteristic.characteristicUuid).copyOf())
                        })
                    } finally { activeSubscriptions-- }
                }
            }
            .takeWhile { it != null }
            .map { requireNotNull(it) }

    /** A deterministic sensor update for previews/tests; no background timer survives the screen. */
    suspend fun updateTemperature(value: Int) {
        requireConnected()
        require(value in 0..100)
        values[DemoPanelGatt.TEMPERATURE] = byteArrayOf(value.toByte())
        events.getValue(DemoPanelGatt.TEMPERATURE).emit(byteArrayOf(value.toByte()))
    }
    override fun close() { closedState.value = true; state.value = BleConnectionState.DISCONNECTED }

    private fun requireConnected() {
        check(!closedState.value && state.value == BleConnectionState.CONNECTED) { "演示设备尚未连接" }
    }
    private fun checkProperty(id: BleCharacteristicId, property: BleProperty) {
        requireConnected()
        val characteristic = DemoPanelGatt.profile.flatMap { it.characteristics }.singleOrNull { it.id == id }
        require(characteristic != null && property in characteristic.properties) { "演示特征不存在或不支持操作" }
    }
}
