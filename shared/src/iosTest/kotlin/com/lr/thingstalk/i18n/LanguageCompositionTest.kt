package com.lr.thingstalk.i18n

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.lr.thingstalk.domain.appearance.*
import com.lr.thingstalk.presentation.i18n.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

class LanguageCompositionTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun languageSwitchRecomposesTextWithoutDisposingSessionOrDraft() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val applier = object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }
        val composition = Composition(applier, recomposer)
        val language = mutableStateOf(AppLanguage.CHINESE)
        val oldLanguage = UiLanguage.current
        var created = 0
        var disposed = 0
        var display = ""
        var currentDraft: MutableState<String>? = null
        try {
            composition.setContent {
                ProvideLanguage(language.value) {
                    val session = remember { created++; Any() }
                    val draft = remember { mutableStateOf("unsaved") }
                    currentDraft = draft
                    DisposableEffect(session) { onDispose { disposed++ } }
                    display = tr(AppStrings.language_title)
                }
            }
            suspend fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(1L)
                runCurrent()
            }
            frame(); frame()
            assertEquals("语言", display)
            currentDraft!!.value = "my unsaved device name"
            language.value = AppLanguage.ENGLISH
            frame(); frame()
            assertEquals("Language", display)
            assertEquals(1, created)
            assertEquals(0, disposed)
            assertEquals("my unsaved device name", currentDraft!!.value)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
            UiLanguage.current = oldLanguage
        }
        assertEquals(1, disposed)
    }
}
