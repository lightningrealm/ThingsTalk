package com.lr.thingstalk.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.panel.PanelTransport

@Composable
internal fun TransportTabs(
    selected: PanelTransport,
    onSelect: (PanelTransport) -> Unit,
    bleStatus: String,
    mqttStatus: String,
) {
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(PanelTransport.BLE to bleStatus, PanelTransport.MQTT to mqttStatus).forEach { (channel, status) ->
            val active = selected == channel
            val background = animateColorAsState(
                if (active) MaterialTheme.colorScheme.surface else Color.Transparent, tween(180), label = "transportTab").value
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(background)
                .selectable(selected = active, role = Role.Tab, onClick = {
                    if (!active) { focus.clearFocus(); onSelect(channel) }
                }).padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Keep both forms composed so switching tabs preserves incomplete input and connection UI state.
 * Only the selected form is measured/placed: the other has no layout or accessible controls.
 * Visibility never controls the lifetime of a BLE/MQTT session.
 */
@Composable
internal fun TransportTabContent(
    selected: PanelTransport,
    ble: @Composable () -> Unit,
    mqtt: @Composable () -> Unit,
) {
    val spacing = if (LocalCompactHeight.current) 12.dp else 18.dp
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        Box(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(spacing)) { ble() } }
        Box(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(spacing)) { mqtt() } }
    }) { measurables, constraints ->
        val visible = measurables[if (selected == PanelTransport.BLE) 0 else 1].measure(constraints)
        layout(visible.width, visible.height) { visible.placeRelative(0, 0) }
    }
}
