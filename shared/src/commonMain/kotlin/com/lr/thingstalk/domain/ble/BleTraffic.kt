package com.lr.thingstalk.domain.ble

import kotlinx.coroutines.flow.StateFlow

enum class BleTrafficDirection { RX, TX, SYSTEM, ERROR }

/** TX records an attempted write; the corresponding completion or error is a separate entry. */
data class BleTrafficEntry(
    val id: Long,
    val timestampMillis: Long,
    val direction: BleTrafficDirection,
    val operation: String,
    val characteristic: BleCharacteristicId? = null,
    val hex: String? = null,
    val byteCount: Int = 0,
    val detail: String = "",
)

/** A bounded, in-memory diagnostic history. It is not a durable delivery or device-event ledger. */
interface BleTraffic {
    val entries: StateFlow<List<BleTrafficEntry>>
    fun clear()
}
