package com.lr.thingstalk.presentation.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lr.thingstalk.domain.panel.setupImportNotice
import com.lr.thingstalk.domain.panel.asDual
import com.lr.thingstalk.domain.panel.PanelTransport
import com.lr.thingstalk.domain.panel.PanelDefinition
import com.lr.thingstalk.domain.panel.PanelDraftRecord
import com.lr.thingstalk.domain.panel.PanelDraftRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PanelEditorState(
    val draft: PanelDraftRecord,
    val loading: Boolean = true,
    val loadError: String? = null,
    val checkpointing: Boolean = false,
    val checkpointError: String? = null,
    val checkpointSaved: Boolean = false,
    val processingExit: Boolean = false,
    val recovery: PanelDraftRecord? = null,
    val notice: String? = null,
    val revision: Int = 0,
) {
    val dirty: Boolean get() = draft.definition != draft.baseline || draft.jsonEdited
    val editable: Boolean get() = !loading && loadError == null && recovery == null && !processingExit
}

/** A local checkpoint is never passed to the panel executor until the user explicitly saves it. */
class PanelEditorViewModel(
    private val deviceId: String,
    initialDefinition: PanelDefinition,
    private val repository: PanelDraftRepository,
    private val checkpointDelayMillis: Long = 500,
) : ViewModel() {
    private var savedDefinition = editablePanelDefinition(initialDefinition)
    private val mutableState = MutableStateFlow(PanelEditorState(emptyDraft()))
    val state = mutableState.asStateFlow()
    private var checkpointJob: Job? = null
    private var loadJob: Job? = null
    private var generation = 0

    init { retryLoad() }

    private fun emptyDraft() = PanelDraftRecord(savedDefinition, savedDefinition)

    fun retryLoad() {
        if (loadJob?.isActive == true) return
        mutableState.update { it.copy(loading = true, loadError = null) }
        loadJob = viewModelScope.launch {
            try {
                val stored = repository.load(deviceId)
                when {
                    stored == null -> mutableState.update { it.copy(loading = false, draft = emptyDraft()) }
                    stored.definition == savedDefinition && !stored.jsonEdited -> {
                        repository.delete(deviceId)
                        mutableState.update { it.copy(loading = false, draft = emptyDraft()) }
                    }
                    stored.baseline != savedDefinition -> mutableState.update {
                        it.copy(loading = false, recovery = stored, notice = null)
                    }
                    else -> mutableState.update {
                        it.copy(loading = false, draft = stored, checkpointSaved = true,
                            notice = "已恢复本机未应用草稿。无效或未完成的字段仍需修正。", revision = it.revision + 1)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(loading = false, loadError = failure.message ?: "无法读取草稿") } }
        }
    }

    fun restoreConflictingDraft() {
        val recovered = mutableState.value.recovery ?: return
        mutableState.update { it.copy(draft = recovered.copy(baseline = savedDefinition, definition = if (savedDefinition.transport == PanelTransport.DUAL) recovered.definition.asDual() else recovered.definition), recovery = null,
            notice = "已恢复旧草稿，请对照当前配置检查后再保存。", revision = it.revision + 1) }
        scheduleCheckpoint()
    }

    fun updateDefinition(definition: PanelDefinition) = edit { copy(definition = definition) }
    fun replaceDraft(definition: PanelDefinition) {
        if (!mutableState.value.editable) return
        mutableState.update { it.copy(draft = it.draft.copy(definition = definition), revision = it.revision + 1) }
        scheduleCheckpoint()
    }
    fun selectWidget(id: String?) = edit { copy(selectedWidgetId = id) }
    fun updateJson(source: String) = edit { copy(jsonText = source, jsonEdited = true) }
    fun generatedJson(source: String) = edit { copy(jsonText = source, jsonEdited = false) }
    fun discardJsonEdits() = edit { copy(jsonText = "", jsonEdited = false) }

    suspend fun importDefinitionWhenReady(definition: PanelDefinition, source: String) {
        state.first { !it.loading }
        check(state.value.editable) { "草稿尚未就绪，请先处理草稿恢复或读取错误，再导入配置" }
        importDefinition(definition, source)
    }

    fun importDefinition(definition: PanelDefinition, source: String) {
        if (!mutableState.value.editable) return
        mutableState.update { it.copy(draft = it.draft.copy(definition = editablePanelDefinition(definition),
            jsonText = source, jsonEdited = false, selectedWidgetId = definition.widgets.firstOrNull()?.id),
            revision = it.revision + 1, notice = listOf("已载入配置草稿，保存并应用后才会替换设备面板。", definition.setupImportNotice()).filter { it.isNotEmpty() }.joinToString("\n")) }
        scheduleCheckpoint()
    }

    private fun edit(change: PanelDraftRecord.() -> PanelDraftRecord) {
        if (!mutableState.value.editable) return
        mutableState.update { it.copy(draft = it.draft.change(), notice = null) }
        scheduleCheckpoint()
    }

    /** Called only when the saved configuration published by PanelViewModel actually changes. */
    fun savedDefinitionChanged(definition: PanelDefinition) {
        val normalized = editablePanelDefinition(definition)
        if (savedDefinition == normalized) return
        savedDefinition = normalized
        val current = mutableState.value
        if (current.loading || current.loadError != null || current.recovery != null) return
        mutableState.update { it.copy(draft = if (!it.dirty) emptyDraft() else it.draft.copy(baseline = normalized),
            revision = it.revision + 1) }
        scheduleCheckpoint()
    }

    fun checkpointNow() {
        if (!mutableState.value.editable) return
        val previous = checkpointJob
        generation++
        val expected = generation
        mutableState.update { it.copy(checkpointing = true, checkpointSaved = false, checkpointError = null) }
        checkpointJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            persistCurrent(expected)
        }
    }

    private fun scheduleCheckpoint() {
        generation++
        val expected = generation
        checkpointJob?.cancel()
        mutableState.update { it.copy(checkpointing = true, checkpointSaved = false, checkpointError = null) }
        checkpointJob = viewModelScope.launch {
            delay(checkpointDelayMillis)
            persistCurrent(expected)
        }
    }

    private suspend fun persistCurrent(expected: Int = generation): Boolean {
        val snapshot = mutableState.value
        try {
            if (snapshot.dirty) repository.save(deviceId, snapshot.draft) else repository.delete(deviceId)
            if (expected == generation) mutableState.update {
                it.copy(checkpointing = false, checkpointSaved = snapshot.dirty, checkpointError = null)
            }
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (expected == generation) mutableState.update {
                it.copy(checkpointing = false, checkpointSaved = false, checkpointError = failure.message ?: "草稿保存失败")
            }
            return false
        }
    }

    fun keepAndLeave(onComplete: () -> Unit) {
        if (mutableState.value.recovery != null && !mutableState.value.processingExit) {
            // The conflicting checkpoint already exists on disk; leaving does not replace it.
            onComplete()
            return
        }
        if (!mutableState.value.editable) return
        mutableState.update { it.copy(processingExit = true) }
        viewModelScope.launch {
            checkpointJob?.cancelAndJoin()
            val success = persistCurrent()
            mutableState.update { it.copy(processingExit = false) }
            if (success) onComplete()
        }
    }

    fun discard(onComplete: () -> Unit = {}) {
        if (mutableState.value.processingExit) return
        mutableState.update { it.copy(processingExit = true, checkpointError = null) }
        viewModelScope.launch {
            try {
                loadJob?.cancelAndJoin()
                checkpointJob?.cancelAndJoin()
                repository.delete(deviceId)
                mutableState.value = PanelEditorState(emptyDraft(), loading = false,
                    revision = mutableState.value.revision + 1, notice = "已丢弃草稿，当前设备配置保持不变。")
                onComplete()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(processingExit = false, checkpointError = failure.message ?: "无法删除草稿") } }
        }
    }

    /** Deletion waits for pending writes before removing this device's isolated checkpoint. */
    suspend fun deleteCheckpoint() {
        loadJob?.cancelAndJoin()
        checkpointJob?.cancelAndJoin()
        repository.delete(deviceId)
    }
}
