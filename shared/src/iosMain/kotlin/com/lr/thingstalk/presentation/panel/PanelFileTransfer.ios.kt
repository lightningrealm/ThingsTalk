package com.lr.thingstalk.presentation.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberPanelFileTransfer(
    onImport: (String) -> Unit,
    onFeedback: (String) -> Unit,
): PanelFileActions {
    val controller = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val imported by rememberUpdatedState(onImport)
    val feedback by rememberUpdatedState(onFeedback)
    var busy by remember { mutableStateOf(false) }
    // UIDocumentPicker's delegate is weak; remember keeps the delegate alive through the picker.
    val transfer = remember(controller, scope) {
        IosPanelFileTransfer(controller, scope, { busy = it }, { imported(it) }, { feedback(it) })
    }
    DisposableEffect(transfer) { onDispose { transfer.close() } }
    return PanelFileActions(transfer::importFile, transfer::exportFile, busy)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosPanelFileTransfer(
    private val presenter: UIViewController,
    private val scope: CoroutineScope,
    private val onBusy: (Boolean) -> Unit,
    private val onImport: (String) -> Unit,
    private val onFeedback: (String) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    private var picker: UIDocumentPickerViewController? = null
    private var operation: Job? = null
    private var exportDirectory: NSURL? = null
    private var exporting = false
    private var busy = false
    private var closed = false

    fun importFile() {
        if (!canPresent()) return
        exporting = false
        setBusy(true)
        present(UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON), asCopy = true))
    }

    fun exportFile(content: String, suggestedName: String) {
        if (!canPresent()) return
        val bytes = try { encodePanelFile(content) } catch (failure: Exception) {
            onFeedback("无法导出配置文件：${failure.message ?: "内容格式无效"}")
            return
        }
        exporting = true
        setBusy(true)
        // Create a unique directory so cancellation only removes this export's own temporary file.
        val directory = NSURL.fileURLWithPath(NSTemporaryDirectory() + "thingstalk-panel-${NSUUID().UUIDString}", isDirectory = true)
        exportDirectory = directory
        operation = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    check(NSFileManager.defaultManager.createDirectoryAtURL(directory, true, null, null)) { "无法创建导出目录" }
                    val url = requireNotNull(directory.URLByAppendingPathComponent(panelConfigurationFileName(suggestedName)))
                    val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
                    check(data.writeToURL(url, atomically = true)) { "无法准备导出文件" }
                    url
                }
                if (!closed) present(UIDocumentPickerViewController(forExportingURLs = listOf(file), asCopy = true))
                else finish()
            } catch (cancelled: CancellationException) {
                // The IO block may finish after disposal cleared exportDirectory.
                NSFileManager.defaultManager.removeItemAtURL(directory, error = null)
                finish()
                throw cancelled
            } catch (failure: Exception) {
                NSFileManager.defaultManager.removeItemAtURL(directory, error = null)
                if (!closed) onFeedback("文件导出失败：${failure.message ?: "请稍后重试"}")
                finish()
            }
        }
    }

    private fun canPresent(): Boolean {
        if (closed || busy) return false
        if (presenter.view.window == null || presenter.presentedViewController != null) {
            onFeedback("请先关闭当前弹窗，再打开文件选择器。")
            return false
        }
        return true
    }

    private fun present(documentPicker: UIDocumentPickerViewController) {
        if (closed || presenter.presentedViewController != null || presenter.view.window == null) {
            if (!closed) onFeedback("暂时无法打开系统文件选择器，请稍后重试。")
            finish()
            return
        }
        documentPicker.delegate = this
        documentPicker.allowsMultipleSelection = false
        documentPicker.shouldShowFileExtensions = true
        // Cancel remains available; prevent a sheet gesture bypassing the document-picker callbacks.
        documentPicker.modalInPresentation = true
        picker = documentPicker
        presenter.presentViewController(documentPicker, animated = true, completion = null)
    }

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        // ObjC callbacks may wrap the same native controller in another Kotlin object.
        if (closed || picker != controller) return
        picker?.delegate = null
        picker = null
        val url = didPickDocumentsAtURLs.singleOrNull() as? NSURL
        if (url == null) { finish(); return }
        if (exporting) {
            // This callback confirms that the system completed exporting the copy.
            finish()
            onFeedback("配置文件已导出。")
        } else {
            operation = scope.launch {
                try {
                    val source = withContext(Dispatchers.IO) { readPanelFile(url) }
                    if (!closed) onImport(source)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!closed) onFeedback("文件导入失败：${failure.message ?: "无法读取配置文件"}")
                } finally { finish() }
            }
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        if (picker == controller) finish()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        if (!closed) onBusy(value)
    }

    private fun finish() {
        picker?.delegate = null
        picker = null
        exportDirectory?.let { NSFileManager.defaultManager.removeItemAtURL(it, error = null) }
        exportDirectory = null
        setBusy(false)
    }

    fun close() {
        closed = true
        operation?.cancel()
        picker?.delegate = null
        picker?.dismissViewControllerAnimated(false, completion = null)
        finish()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readPanelFile(url: NSURL): String {
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        val handle = NSFileHandle.fileHandleForReadingFromURL(url, error = null) ?: error("无法打开所选文件")
        try {
            val parts = mutableListOf<ByteArray>()
            var length = 0
            while (true) {
                val data = handle.readDataUpToLength(minOf(8 * 1024, MAX_PANEL_FILE_BYTES + 1 - length).toULong(), error = null)
                    ?: error("无法完整读取所选文件")
                if (data.length == 0uL) break
                require(data.length <= (MAX_PANEL_FILE_BYTES - length).toULong()) { "配置文件不能超过 512 KiB" }
                val part = requireNotNull(data.bytes).readBytes(data.length.toInt())
                parts += part
                length += part.size
            }
            val bytes = ByteArray(length)
            var offset = 0
            parts.forEach { it.copyInto(bytes, offset); offset += it.size }
            return decodePanelFile(bytes)
        } finally { handle.closeAndReturnError(null) }
    } finally { if (scoped) url.stopAccessingSecurityScopedResource() }
}
