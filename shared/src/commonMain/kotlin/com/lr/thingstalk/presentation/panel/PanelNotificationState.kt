package com.lr.thingstalk.presentation.panel

enum class PanelNotificationStatus { STOPPED, STARTING, WAITING, RECEIVING, FAILED }

/** Health is per capability: two fields may share one physical BLE subscription. */
data class PanelNotificationState(
    val status: PanelNotificationStatus = PanelNotificationStatus.STOPPED,
    val lastValueAt: Long? = null,
    val invalidPayloadCount: Long = 0,
    val lastDecodeError: String? = null,
    val failure: String? = null,
)
