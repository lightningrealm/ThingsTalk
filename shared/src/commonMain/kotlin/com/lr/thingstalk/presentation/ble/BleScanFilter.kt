package com.lr.thingstalk.presentation.ble

import com.lr.thingstalk.domain.ble.BleScanDevice

/** Filters the latest advertised values without restarting the native scan. */
data class BleScanFilter(
    val query: String = "",
    val namedOnly: Boolean = false,
    val minimumRssi: Int? = null,
    val serviceUuid: String = "",
) {
    private val normalizedQuery = query.trim()
    private val normalizedServiceUuid = serviceUuidFragment(serviceUuid)

    val isActive: Boolean
        get() = normalizedQuery.isNotEmpty() || namedOnly || minimumRssi != null || serviceUuid.isNotBlank()

    val serviceUuidError: String?
        get() = if (serviceUuid.isNotBlank() && normalizedServiceUuid == null) {
            "请输入 1–32 个十六进制字符，可包含连字符"
        } else null

    fun matches(device: BleScanDevice): Boolean {
        if (serviceUuidError != null) return false
        if (namedOnly && device.name.isNullOrBlank()) return false
        if (normalizedQuery.isNotEmpty() &&
            device.name?.trim()?.contains(normalizedQuery, ignoreCase = true) != true &&
            !device.id.trim().contains(normalizedQuery, ignoreCase = true)
        ) return false
        // The model exposes the platform's raw RSSI; do not infer distance or an unknown sentinel.
        if (minimumRssi != null && device.rssi < minimumRssi) return false
        if (normalizedServiceUuid != null && device.serviceUuids.none {
                canonicalServiceUuid(it)?.contains(normalizedServiceUuid) == true
            }
        ) return false
        return true
    }
}

private val shortServiceUuid = Regex("[0-9a-fA-F]{4}|[0-9a-fA-F]{8}")
private val fullServiceUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val compactServiceUuid = Regex("[0-9a-fA-F]{32}")
private val serviceUuidCharacters = Regex("[0-9a-fA-F-]+")

/** A fragment is never expanded: e.g. 12345678 must also match a vendor-specific UUID. */
private fun serviceUuidFragment(value: String): String? {
    val trimmed = value.trim()
    if (!serviceUuidCharacters.matches(trimmed)) return null
    return trimmed.replace("-", "").lowercase().takeIf { it.length in 1..32 }
}

private fun canonicalServiceUuid(value: String): String? {
    val trimmed = value.trim()
    return when {
        shortServiceUuid.matches(trimmed) -> "${trimmed.padStart(8, '0').lowercase()}00001000800000805f9b34fb"
        fullServiceUuid.matches(trimmed) || compactServiceUuid.matches(trimmed) -> trimmed.replace("-", "").lowercase()
        else -> null
    }
}
