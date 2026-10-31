package com.lr.thingstalk.presentation.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.appearance.*
import com.lr.thingstalk.presentation.components.*

@Composable
fun AppearancePage(viewModel: AppearanceViewModel) {
    val settings by viewModel.appearance.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val error by viewModel.error.collectAsState()
    Page("APPEARANCE", "换一种舒服的色调。", "即时应用，自动保存在本机。") {
        Panel {
            Text("显示模式", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = settings.mode == mode, enabled = !saving,
                        onClick = { viewModel.update(settings.copy(mode = mode)) },
                        label = { Text(when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) })
                }
            }
            Text("跟随系统会自动响应设备的浅色与深色设置。", color = Muted)
        }
        Panel {
            Text("主题色", style = MaterialTheme.typography.titleMedium)
            Text("用素雅背景衬托卡片、边框与控件的主题色。", color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemePalette.entries.forEach { palette ->
                    FilterChip(selected = settings.palette == palette, enabled = !saving,
                        onClick = { viewModel.update(settings.copy(palette = palette)) },
                        leadingIcon = { Box(Modifier.size(14.dp).background(themeColors(palette, false).primary, CircleShape)) },
                        label = { Text(when (palette) {
                            ThemePalette.SAGE -> "苔绿"; ThemePalette.BLUE -> "雾蓝"; ThemePalette.VIOLET -> "鸢紫"
                            ThemePalette.CLAY -> "陶土"; ThemePalette.GRAPHITE -> "石墨"
                        }) })
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (saving) Text("正在保存外观…", color = Muted)
        Section("效果预览")
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("桌面温度", style = MaterialTheme.typography.titleMedium); Text("仅为外观预览", color = Muted) }
                Tag("在线")
            }
            Text("24.6 ℃", style = MaterialTheme.typography.headlineLarge)
            HorizontalDivider(color = Line)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {}) { Text("主要按钮") }
                OutlinedButton(onClick = {}) { Text("次要按钮") }
            }
        }
        Text("这些设置仅影响显示，不修改设备配置或发送通信指令。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
