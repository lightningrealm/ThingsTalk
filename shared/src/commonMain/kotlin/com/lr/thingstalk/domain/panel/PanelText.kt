package com.lr.thingstalk.domain.panel

/** Validate without trimming, truncating or changing the user's wire text. */
fun panelTextProblem(value: String, maxBytes: Int): String? {
    if (value.isEmpty()) return "请输入要发送的文本"
    if ('\u0000' in value) return "文本不能包含 NUL"
    val bytes = try { value.encodeToByteArray(throwOnInvalidSequence = true) }
    catch (_: CharacterCodingException) { return "文本包含无效的 Unicode 字符" }
    return if (bytes.size > maxBytes) "文本不能超过 $maxBytes 个 UTF-8 字节" else null
}
