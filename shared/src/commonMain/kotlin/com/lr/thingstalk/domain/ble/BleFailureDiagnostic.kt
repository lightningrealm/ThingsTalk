package com.lr.thingstalk.domain.ble

/** Kable 0.45 Apple wraps NSError.description in IOException. Extract metadata only. */
fun bleFailureDiagnostic(failure: Throwable): String? {
    var current: Throwable? = failure
    repeat(8) {
        val error = current ?: return null
        val message = error.message.orEmpty()
        // Never return descriptions, userInfo, payloads or arbitrary exception messages.
        val native = Regex("(?:^|\\s)Error Domain=(CBATTErrorDomain|CBErrorDomain) Code=(-?[0-9]{1,10})(?=\\s|$)").find(message)
        if (native != null) return "${native.groupValues[1]} / code=${native.groupValues[2]}"
        if (error::class.simpleName == "NotConnectedException") return "BLE_NOT_CONNECTED"
        if (message == "BLE connection is closed" || message == "BLE is not connected") return "BLE_NOT_CONNECTED"
        if (message == "GATT services are not available") return "BLE_SERVICES_UNAVAILABLE"
        if (message == "Characteristic does not support WITH_RESPONSE") return "BLE_WRITE_WITH_RESPONSE_UNSUPPORTED"
        if (message == "Payload exceeds negotiated write length") return "BLE_PAYLOAD_TOO_LONG"
        current = error.cause
    }
    return null
}
