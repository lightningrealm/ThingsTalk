package com.lr.thingstalk.presentation.components

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.lr.thingstalk.presentation.theme.*

val LocalCompactHeight = staticCompositionLocalOf { false }

@Composable
fun Page(eyebrow: String, title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val compact = LocalCompactHeight.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Pad the scroll content, so cards can pass behind the home indicator while scrolling.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = if (compact) 20.dp else 24.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text(eyebrow, color = Accent, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
        Text(title, style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
fun Section(title: String, trailing: String = "", action: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (trailing.isNotEmpty()) TextButton(onClick = action) { Text(trailing) }
    }
}

@Composable
fun Tag(label: String) {
    Surface(color = Soft, shape = RoundedCornerShape(8.dp)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Accent, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Item(title: String, detail: String, symbol: String = "device", onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = Soft) { Glyph(symbol, Modifier.padding(12.dp).size(24.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = Muted, fontSize = 24.sp)
        }
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Toggle(label: String, detail: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label); Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
fun Choices(values: List<String>, selected: String, select: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected == value, { select(value) }, label = { Text(choiceLabel(value)) }) }
    }
}

@Composable
fun Primary(label: String, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) { Text(label) }
}

/** Small line icons share one visual language and need no platform icon dependency. */
@Composable
fun Glyph(kind: String, modifier: Modifier = Modifier, color: Color = Accent) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = 1.7.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(w*x1,h*y1), Offset(w*x2,h*y2), stroke, StrokeCap.Round)
        when (kind) {
            "mqtt" -> { drawCircle(color,w*.12f,Offset(w*.5f,h*.5f),style=Stroke(stroke)); line(.5f,.12f,.5f,.34f); line(.5f,.66f,.5f,.88f); line(.12f,.5f,.34f,.5f); line(.66f,.5f,.88f,.5f); drawCircle(color,w*.07f,Offset(w*.5f,h*.08f)); drawCircle(color,w*.07f,Offset(w*.5f,h*.92f)); drawCircle(color,w*.07f,Offset(w*.08f,h*.5f)); drawCircle(color,w*.07f,Offset(w*.92f,h*.5f)) }
            "bluetooth" -> { line(.5f,.08f,.5f,.92f); line(.5f,.08f,.82f,.32f); line(.82f,.32f,.18f,.76f); line(.18f,.24f,.82f,.68f); line(.82f,.68f,.5f,.92f) }
            "events" -> { drawRoundRect(color, Offset(w*.15f,h*.2f), androidx.compose.ui.geometry.Size(w*.7f,h*.6f), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style=Stroke(stroke)); line(.15f,.25f,.5f,.52f); line(.5f,.52f,.85f,.25f) }
            "more" -> { drawCircle(color, w*.16f, Offset(w*.5f,h*.28f), style=Stroke(stroke)); drawArc(color,180f,180f,false,Offset(w*.2f,h*.53f),androidx.compose.ui.geometry.Size(w*.6f,h*.5f),style=Stroke(stroke)) }
            "light" -> { drawCircle(color,w*.27f,Offset(w*.5f,h*.35f),style=Stroke(stroke)); line(.4f,.62f,.4f,.8f); line(.6f,.62f,.6f,.8f); line(.4f,.8f,.6f,.8f); line(.43f,.93f,.57f,.93f) }
            else -> { drawRoundRect(color,Offset(w*.2f,h*.08f),androidx.compose.ui.geometry.Size(w*.6f,h*.84f),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),style=Stroke(stroke)); line(.2f,.5f,.8f,.5f); line(.65f,.3f,.65f,.38f); line(.65f,.65f,.65f,.73f) }
        }
    }
}

@Composable
fun RetentionChoices(values: List<String>, selected: String, select: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected == value, { select(value) }, label = { Text(tr(AppStrings.days_5d45fa, value)) }) }
    }
}
