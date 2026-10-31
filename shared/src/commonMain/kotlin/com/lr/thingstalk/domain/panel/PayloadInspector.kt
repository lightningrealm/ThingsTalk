package com.lr.thingstalk.domain.panel

/** Offline wire-format inspection. No connection, service discovery, or device write is performed. */
interface PayloadInspector {
    fun decode(format: PayloadFormat, source: String, input: PayloadInputEncoding): PayloadInspection
    /** Values are explicit for every field; callers may prefill them from declared defaults. */
    fun encode(format: PayloadFormat, values: Map<String, String>): PayloadInspection
}

enum class PayloadInputEncoding { HEX, JSON_TEXT }

data class PayloadInspection(
    val hex: String,
    val byteCount: Int,
    val jsonText: String?,
    val fields: List<InspectedPayloadField>,
)

data class InspectedPayloadField(
    val id: String,
    val codec: CodecType,
    val value: String,
    /** Offsets include a binary frame's prefix and start at zero. JSON fields have no fixed offset. */
    val byteOffset: Int? = null,
    val byteCount: Int? = null,
)

const val MAX_INSPECTOR_SOURCE_CHARS = 2048
const val MAX_INSPECTOR_VALUE_CHARS = 128
