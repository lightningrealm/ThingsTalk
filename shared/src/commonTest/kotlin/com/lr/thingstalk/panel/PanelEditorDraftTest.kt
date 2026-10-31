@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lr.thingstalk.panel

import androidx.lifecycle.ViewModelStore
import com.lr.thingstalk.data.panel.JsonPanelDraftRepository
import com.lr.thingstalk.data.panel.PanelDraftStorage
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.PanelEditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

class PanelEditorDraftTest {
    private val original = PanelDefinition(schemaVersion = 3, title = "Saved")

    @Test fun localCheckpointPreservesIncompleteNumbersAndRawJsonAndIsolatesDevices() = runTest {
        val storage = DraftStorage()
        val repository = JsonPanelDraftRepository(storage)
        val incomplete = original.copy(capabilities = listOf(
            PanelCapability("value", "Value", CapabilityType.NUMBER, minimum = Double.NaN, maximum = 100.0),
        ))
        val checkpoint = PanelDraftRecord(original, incomplete, jsonText = "{\n incomplete JSON", jsonEdited = true)
        repository.save("first", checkpoint)
        repository.save("second", PanelDraftRecord(original, original.copy(title = "Second")))
        val restored = JsonPanelDraftRepository(storage).load("first")!!
        assertTrue(restored.definition.capabilities.single().minimum!!.isNaN())
        assertEquals(checkpoint.jsonText, restored.jsonText)
        assertTrue(validatePanel(restored.definition).isNotEmpty())
        repository.delete("first")
        assertNull(repository.load("first"))
        assertEquals("Second", repository.load("second")!!.definition.title)
    }

    @Test fun invalidArchiveThrowsWithoutOverwritingOriginalBytes() = runTest {
        val storage = DraftStorage().apply { values["device"] = "{unsupported" }
        val repository = JsonPanelDraftRepository(storage)
        assertFails { repository.load("device") }
        assertEquals("{unsupported", storage.values["device"])
    }

    @Test fun editIsDebouncedAndKeepingPersistsBeforeNavigation() = runTest {
        withEditor { editor, repository ->
            editor.updateDefinition(original.copy(title = "First edit"))
            advanceTimeBy(300)
            editor.updateDefinition(original.copy(title = "Final edit"))
            advanceTimeBy(300)
            assertEquals(0, repository.writes)
            var left = false
            editor.keepAndLeave { left = true; assertEquals("Final edit", repository.records["device"]?.definition?.title) }
            runCurrent()
            assertTrue(left)
            assertEquals(1, repository.writes)
            assertEquals("Saved", editor.state.value.draft.baseline.title)
            assertTrue(editor.state.value.dirty)
        }
    }

    @Test fun checkpointFailureKeepsUserInEditorAndRetryWorks() = runTest {
        withEditor { editor, repository ->
            editor.updateDefinition(original.copy(title = "Keep me"))
            repository.fail = true
            var left = false
            editor.keepAndLeave { left = true }
            runCurrent()
            assertFalse(left)
            assertNotNull(editor.state.value.checkpointError)
            assertTrue(editor.state.value.dirty)
            assertFalse(editor.state.value.checkpointSaved)
            repository.fail = false
            editor.keepAndLeave { left = true }
            runCurrent()
            assertTrue(left)
            assertEquals("Keep me", repository.records["device"]?.definition?.title)
        }
    }

    @Test fun discardCancelsPendingCheckpointAndDoesNotApplyToSavedDefinition() = runTest {
        withEditor { editor, repository ->
            editor.updateDefinition(original.copy(title = "Discard me"))
            editor.updateJson("broken raw JSON")
            var left = false
            editor.discard { left = true }
            advanceUntilIdle()
            assertTrue(left)
            assertFalse(editor.state.value.dirty)
            assertEquals(original, editor.state.value.draft.definition)
            assertNull(repository.records["device"])
        }
    }

    @Test fun successfulSaveUpdatesBaselineButKeepsEditsMadeWhileSaving() = runTest {
        withEditor { editor, repository ->
            val submitted = original.copy(title = "Submitted")
            editor.updateDefinition(submitted)
            editor.updateDefinition(original.copy(title = "Typed while saving"))
            editor.savedDefinitionChanged(submitted)
            assertEquals(submitted, editor.state.value.draft.baseline)
            assertEquals("Typed while saving", editor.state.value.draft.definition.title)
            assertTrue(editor.state.value.dirty)
            editor.savedDefinitionChanged(editor.state.value.draft.definition)
            advanceUntilIdle()
            assertFalse(editor.state.value.dirty)
            assertNull(repository.records["device"])
        }
    }

    @Test fun jsonTextRemainsUnappliedUntilExplicitImport() = runTest {
        withEditor { editor, _ ->
            editor.updateJson("{\"title\":\"New\"}")
            assertEquals(original, editor.state.value.draft.definition)
            assertTrue(editor.state.value.draft.jsonEdited)
            editor.importDefinition(original.copy(title = "Imported"), "valid JSON")
            assertFalse(editor.state.value.draft.jsonEdited)
            assertEquals("Imported", editor.state.value.draft.definition.title)
            assertEquals(original, editor.state.value.draft.baseline)
        }
    }

    @Test fun storedDraftRestoresOnNewEditorWithoutApplyingIt() = runTest {
        val repository = DraftRepository().apply {
            records["device"] = PanelDraftRecord(original, original.copy(title = "Recovered"), "raw", true)
        }
        withEditor(repository) { editor, _ ->
            assertEquals("Recovered", editor.state.value.draft.definition.title)
            assertEquals(original, editor.state.value.draft.baseline)
            assertTrue(editor.state.value.draft.jsonEdited)
            assertTrue(editor.state.value.checkpointSaved)
        }
    }

    @Test fun changedBaselineRequiresExplicitRecoveryAndKeepingDoesNotRewriteIt() = runTest {
        val oldDraft = PanelDraftRecord(original.copy(title = "Old saved"), original.copy(title = "Old draft"))
        val repository = DraftRepository().apply { records["device"] = oldDraft }
        withEditor(repository) { editor, _ ->
            assertEquals(oldDraft, editor.state.value.recovery)
            assertEquals(original, editor.state.value.draft.definition)
            var left = false
            editor.keepAndLeave { left = true }
            assertTrue(left)
            assertEquals(oldDraft, repository.records["device"])
            editor.restoreConflictingDraft()
            advanceUntilIdle()
            assertNull(editor.state.value.recovery)
            assertEquals(original, editor.state.value.draft.baseline)
            assertEquals("Old draft", editor.state.value.draft.definition.title)
        }
    }

    @Test fun loadFailureBlocksEditsUntilExplicitDiscard() = runTest {
        val repository = DraftRepository().apply { fail = true }
        withEditor(repository) { editor, _ ->
            assertNotNull(editor.state.value.loadError)
            editor.updateDefinition(original.copy(title = "Should not overwrite"))
            advanceUntilIdle()
            assertEquals(0, repository.writes)
            assertEquals(original, editor.state.value.draft.definition)
            repository.fail = false
            editor.discard()
            runCurrent()
            assertTrue(editor.state.value.editable)
        }
    }

    @Test fun pickerResultWaitsForDraftLoadInsteadOfBeingSilentlyIgnored() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val repository = object : PanelDraftRepository {
            override suspend fun load(deviceId: String): PanelDraftRecord? {
                gate.await()
                return PanelDraftRecord(original, original.copy(title = "Old draft"))
            }
            override suspend fun save(deviceId: String, draft: PanelDraftRecord) = Unit
            override suspend fun delete(deviceId: String) = Unit
        }
        val store = ViewModelStore()
        try {
            val editor = PanelEditorViewModel("device", original, repository)
            store.put("editor", editor)
            val imported = original.copy(title = "Imported file")
            val result = async { editor.importDefinitionWhenReady(imported, "file JSON") }
            runCurrent()
            assertFalse(result.isCompleted)
            gate.complete(Unit)
            result.await()
            assertEquals(imported, editor.state.value.draft.definition)
            assertEquals("file JSON", editor.state.value.draft.jsonText)
        } finally { store.clear(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    private suspend fun TestScope.withEditor(
        repository: DraftRepository = DraftRepository(),
        block: suspend TestScope.(PanelEditorViewModel, DraftRepository) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val editor = PanelEditorViewModel("device", original, repository)
            store.put("editor", editor)
            runCurrent()
            block(editor, repository)
        } finally {
            store.clear()
            try { advanceUntilIdle() } finally { Dispatchers.resetMain() }
        }
    }
}

private class DraftRepository : PanelDraftRepository {
    val records = mutableMapOf<String, PanelDraftRecord>()
    var writes = 0
    var fail = false
    override suspend fun load(deviceId: String): PanelDraftRecord? {
        check(!fail) { "Storage unavailable" }
        return records[deviceId]
    }
    override suspend fun save(deviceId: String, draft: PanelDraftRecord) {
        check(!fail) { "Storage unavailable" }
        writes++
        records[deviceId] = draft
    }
    override suspend fun delete(deviceId: String) {
        check(!fail) { "Storage unavailable" }
        records.remove(deviceId)
    }
}

private class DraftStorage : PanelDraftStorage {
    val values = mutableMapOf<String, String>()
    override fun load(deviceId: String): String? = values[deviceId]
    override fun save(deviceId: String, source: String?) { if (source == null) values.remove(deviceId) else values[deviceId] = source }
}
