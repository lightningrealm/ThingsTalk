package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.platformCapabilities
import com.lr.thingstalk.presentation.i18n.*

internal data class PreparedPanelImport(val original: PanelDefinition, val panel: PanelDefinition, val source: String) {
    val mqttOnly: Boolean get() = original.transport == PanelTransport.DUAL && panel.transport == PanelTransport.MQTT
    fun notice(): String? = if (!mqttOnly) null else tr(AppStrings.web_dual_import_notice,
        panel.widgets.size, original.widgets.size - panel.widgets.size, original.setupPanels.size)
}

internal fun preparePanelImport(source: String, bleAvailable: Boolean = platformCapabilities().ble): PreparedPanelImport {
    val original = PanelJson.decode(source)
    val panel = if (bleAvailable) original else original.mqttImportProjection()
    return PreparedPanelImport(original, panel, if (panel === original) source else PanelJson.encode(panel))
}
