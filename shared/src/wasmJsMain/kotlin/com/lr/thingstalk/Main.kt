package com.lr.thingstalk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.lr.thingstalk.presentation.theme.ThinsTalkTheme
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import org.jetbrains.skiko.loadBytesFromPath
import org.w3c.dom.PageTransitionEvent
import org.w3c.dom.events.Event

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        WebWindowResources()
        WithChineseFont { App() }
    }
}

@Composable
private fun WebWindowResources() {
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    DisposableEffect(owner) {
        // Compose supplies the owners, but does not yet dispose its window on page close.
        // Preserve the live state when the browser stores this page in its back/forward cache.
        val onPageHide: (Event) -> Unit = { event ->
            if ((event as? PageTransitionEvent)?.persisted != true) owner.viewModelStore.clear()
        }
        window.addEventListener("pagehide", onPageHide)
        onDispose {
            window.removeEventListener("pagehide", onPageHide)
            owner.viewModelStore.clear()
        }
    }
}

@Composable
private fun WithChineseFont(content: @Composable () -> Unit) {
    val resolver = LocalFontFamilyResolver.current
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(resolver, attempt) {
        failed = false
        try {
            val bytes = loadBytesFromPath("fonts/ThingsTalkSansSC-Regular.ttf")
            resolver.preload(FontFamily(Font("ThingsTalk Sans SC Regular", bytes)))
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failed = true
        }
    }
    if (loaded) content()
    else ThinsTalkTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (failed) "The local font could not be loaded." else "Loading ThingsTalk…")
            if (failed) Button(onClick = { attempt++ }) { Text("Retry") }
        }
    }
}
