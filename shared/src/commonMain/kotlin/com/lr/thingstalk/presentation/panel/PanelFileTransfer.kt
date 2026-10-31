package com.lr.thingstalk.presentation.panel

import androidx.compose.runtime.Composable

/** The picker only transfers text. Validation and applying an imported draft belong to the editor. */
class PanelFileActions(
    val importFile: () -> Unit,
    val exportFile: (content: String, suggestedName: String) -> Unit,
    val busy: Boolean = false,
)

@Composable
expect fun rememberPanelFileTransfer(
    onImport: (String) -> Unit,
    onFeedback: (String) -> Unit,
): PanelFileActions

internal const val MAX_PANEL_FILE_BYTES = 512 * 1024

internal fun decodePanelFile(bytes: ByteArray): String {
    require(bytes.size <= MAX_PANEL_FILE_BYTES) { "配置文件不能超过 512 KiB" }
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("配置文件需要使用 UTF-8 编码")
    }
}

internal fun encodePanelFile(content: String): ByteArray {
    require(content.length <= MAX_PANEL_FILE_BYTES) { "配置文件不能超过 512 KiB" }
    val bytes = content.encodeToByteArray(throwOnInvalidSequence = true)
    require(bytes.size <= MAX_PANEL_FILE_BYTES) { "配置文件不能超过 512 KiB" }
    return bytes
}

/** Keep the exported extension consistent and never interpret a suggested name as a path. */
internal fun panelConfigurationFileName(suggested: String): String {
    val stem = suggested.replace(Regex("(?i)(\\.thingstalk)?\\.json$"), "")
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("").trim('_').take(64).ifEmpty { "panel" }
    return "$stem.thingstalk.json"
}
