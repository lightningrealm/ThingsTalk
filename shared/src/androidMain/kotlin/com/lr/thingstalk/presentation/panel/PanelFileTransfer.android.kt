package com.lr.thingstalk.presentation.panel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberPanelFileTransfer(
    onImport: (String) -> Unit,
    onFeedback: (String) -> Unit,
): PanelFileActions {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val imported by rememberUpdatedState(onImport)
    val feedback by rememberUpdatedState(onFeedback)
    var busy by remember { mutableStateOf(false) }
    // Only a cache filename enters saved state, avoiding a large JSON blob in the activity Bundle.
    var exportSnapshot by rememberSaveable { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) busy = false
        else {
            busy = true
            scope.launch {
                try {
                    val source = withContext(Dispatchers.IO) {
                        val input = resolver.openInputStream(uri) ?: error("无法打开所选文件")
                        input.use {
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val count = it.read(buffer, 0, minOf(buffer.size, MAX_PANEL_FILE_BYTES + 1 - output.size()))
                                if (count < 0) break
                                if (count == 0) error("无法继续读取所选文件")
                                output.write(buffer, 0, count)
                                require(output.size() <= MAX_PANEL_FILE_BYTES) { "配置文件不能超过 512 KiB" }
                            }
                            decodePanelFile(output.toByteArray())
                        }
                    }
                    imported(source)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    feedback("文件导入失败：${failure.message ?: "无法读取配置文件"}")
                } finally { busy = false }
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exportSnapshot?.takeIf { it.matches(Regex("panel-export-[a-f0-9-]+\\.json")) }
            ?.let { File(context.cacheDir, it) }
        exportSnapshot = null
        busy = true
        scope.launch {
            try {
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        check(snapshot != null && snapshot.isFile && snapshot.length() <= MAX_PANEL_FILE_BYTES) {
                            "导出草稿已失效，请重新导出"
                        }
                        val bytes = snapshot.readBytes()
                        val output = resolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
                        output.use { it.write(bytes); it.flush() }
                    }
                    feedback("配置文件已导出。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback("文件导出失败，目标文件可能未完整写入，请重新导出。")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { snapshot?.delete() }
                busy = false
            }
        }
    }
    return PanelFileActions(
        importFile = {
            if (!busy && exportSnapshot == null) {
                busy = true
                // Providers may classify .thingstalk.json as octet-stream or an unknown MIME.
                // Select any document; size/UTF-8 and the real panel schema are validated afterwards.
                try { importer.launch(arrayOf("*/*")) }
                catch (_: Exception) { busy = false; feedback("无法打开系统文件选择器。") }
            }
        },
        exportFile = { content, suggestedName ->
            if (!busy && exportSnapshot == null) {
                busy = true
                scope.launch {
                    val snapshot = File(context.cacheDir, "panel-export-${UUID.randomUUID()}.json")
                    var launched = false
                    try {
                        withContext(Dispatchers.IO) { snapshot.writeBytes(encodePanelFile(content)) }
                        exportSnapshot = snapshot.name
                        exporter.launch(panelConfigurationFileName(suggestedName))
                        launched = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        feedback("无法导出配置文件：${failure.message ?: "请稍后重试"}")
                    } finally {
                        if (!launched) {
                            exportSnapshot = null
                            withContext(NonCancellable + Dispatchers.IO) { snapshot.delete() }
                            busy = false
                        }
                    }
                }
            }
        },
        busy = busy || exportSnapshot != null,
    )
}
