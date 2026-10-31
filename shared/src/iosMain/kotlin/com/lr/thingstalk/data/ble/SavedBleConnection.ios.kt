package com.lr.thingstalk.data.ble

import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Peripheral
import com.juul.kable.UnmetRequirementException
import com.juul.kable.toIdentifier
import com.lr.thingstalk.domain.ble.BleConnection
import com.lr.thingstalk.domain.ble.BleException
import kotlinx.coroutines.delay

@OptIn(ObsoleteKableApi::class)
internal actual fun restoreSavedBleConnection(deviceId: String): BleConnection? {
    val identifier = try { deviceId.toIdentifier() } catch (_: IllegalArgumentException) { return null }
    return RestoringBleConnection {
        // Kable 0.45's identifier constructor synchronously retrieves from its lazy central,
        // before that central necessarily receives poweredOn. The UI permission monitor owns
        // a DIFFERENT manager. No public Kable API exposes its central's readiness.
        // Retry ONLY identity lookup / explicit pre-connect Unknown or Resetting failures.
        // Never retry a radio connection timeout, GATT discovery, or any control operation.
        var lastFailure: Exception? = null
        var restored: BleConnection? = null
        for (attempt in 0 until 21) {
            if (attempt > 0) delay(100)
            val peripheral = try {
                Peripheral(identifier) { forceCharacteristicEqualityByUuid = true }
            } catch (missing: NoSuchElementException) {
                lastFailure = missing
                continue
            }
            var transferred = false
            try {
                peripheral.connect()
                restored = KableBleConnection(peripheral)
                transferred = true
                break
            } catch (notReady: UnmetRequirementException) {
                // Version-specific: checkBluetoothIsOn fails before connectPeripheral is called.
                val transient = notReady.message in setOf(
                    "Bluetooth was Unknown, but PoweredOn was required",
                    "Bluetooth was Resetting, but PoweredOn was required",
                )
                if (!transient) throw BleException("蓝牙尚不可用：${notReady.message}", notReady)
                lastFailure = notReady
            } finally {
                if (!transferred) peripheral.close()
            }
        }
        restored ?: throw BleException(
            if (lastFailure is NoSuchElementException) "系统未找到已保存的设备标识，请重新扫描并关联；面板配置会保留"
            else "蓝牙初始化尚未完成，请稍后重试连接", lastFailure,
        )
    }
}
