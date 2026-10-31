package com.lr.thingstalk.presentation.ble

import androidx.compose.runtime.Composable

data class BleScanAccess(
    val canScan: Boolean,
    val status: String,
    val actionLabel: String? = null,
    val requestAccess: () -> Unit = {},
    /** False while a platform status monitor is initializing; this is not a permission denial. */
    val isDetermined: Boolean = true,
    val canConnect: Boolean = canScan,
)

/** Only explicit requestAccess calls open dialogs. Connection mode must not require scan/location access. */
@Composable
expect fun rememberBleScanAccess(forConnection: Boolean = false): BleScanAccess
