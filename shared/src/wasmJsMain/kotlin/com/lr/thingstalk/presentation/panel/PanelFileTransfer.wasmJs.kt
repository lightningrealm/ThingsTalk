@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.lr.thingstalk.presentation.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberPanelFileTransfer(onImport: (String) -> Unit, onFeedback: (String) -> Unit): PanelFileActions {
    val receive = rememberUpdatedState(onImport)
    val feedback = rememberUpdatedState(onFeedback)
    return PanelFileActions(
        importFile = { pickPanelFile({ receive.value(it) }, { feedback.value(it) }) },
        exportFile = { content, name ->
            try {
                encodePanelFile(content)
                downloadPanelFile(content, panelConfigurationFileName(name))
                feedback.value("已请求浏览器下载配置文件")
            } catch (error: Exception) { feedback.value(error.message ?: "文件导出失败") }
        },
    )
}

@JsFun("""(success, failure) => {
    const input = document.createElement('input');
    input.type = 'file'; input.accept = '.json,application/json';
    input.onchange = async () => {
        const file = input.files && input.files[0]; if (!file) return;
        if (file.size > 524288) { failure('配置文件不能超过 512 KiB'); return; }
        try {
            const bytes = await file.arrayBuffer();
            success(new TextDecoder('utf-8', {fatal:true}).decode(bytes));
        } catch (_) { failure('读取失败，配置文件需要使用 UTF-8 编码'); }
    };
    input.click();
}""")
private external fun pickPanelFile(success: (String) -> Unit, failure: (String) -> Unit)

@JsFun("""(content, name) => {
    const url = URL.createObjectURL(new Blob([content], {type:'application/json;charset=utf-8'}));
    const a = document.createElement('a'); a.href = url; a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
}""")
private external fun downloadPanelFile(content: String, name: String)
