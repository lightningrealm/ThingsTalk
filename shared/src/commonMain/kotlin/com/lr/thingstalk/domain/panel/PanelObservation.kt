package com.lr.thingstalk.domain.panel

/** A live subscription distinguishes setup, valid data, and a rejected individual payload. */
sealed interface PanelObservation {
    /** The transport has confirmed that this subscription is ready to receive data. */
    data object Ready : PanelObservation
    /** Selected source is offline; cached values must no longer look current. */
    data object Offline : PanelObservation
    /** Source failed; a dual connection watcher remains alive for the next explicit connection. */
    data class Failed(val message: String) : PanelObservation
    data class Value(val value: String) : PanelObservation
    /** This packet was rejected; the subscription remains active and may receive another packet. */
    data class InvalidPayload(val message: String) : PanelObservation
}
