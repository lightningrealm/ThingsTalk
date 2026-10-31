package com.lr.thingstalk.presentation.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.Primary
import com.lr.thingstalk.presentation.i18n.*

/** Local draft only; transport state updates must not replace text being edited. */
@Composable
internal fun PanelTextInput(label: String, capability: PanelCapability, enabled: Boolean, onWrite: (String) -> Unit) {
    var text by remember(capability.id) { mutableStateOf("") }
    val problem = panelTextProblem(text, capability.maxBytes)
    OutlinedTextField(text, { text = it }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 5,
        enabled = enabled, isError = text.isNotEmpty() && problem != null,
        supportingText = {
            Text(if (text.isNotEmpty() && problem != null) diagnosticText(problem)
                else tr(AppStrings.text_byte_count, text.encodeToByteArray().size, capability.maxBytes))
        })
    Primary(tr(AppStrings.text_send), enabled && problem == null) { onWrite(text) }
}
