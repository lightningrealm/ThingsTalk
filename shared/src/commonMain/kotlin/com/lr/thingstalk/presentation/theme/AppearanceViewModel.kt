package com.lr.thingstalk.presentation.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lr.thingstalk.domain.appearance.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppearanceViewModel(private val repository: AppearanceRepository) : ViewModel() {
    val appearance = repository.appearance
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun update(value: Appearance) {
        if (mutableSaving.value || value == appearance.value) return
        mutableSaving.value = true
        mutableError.value = null
        viewModelScope.launch {
            try { repository.save(value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableError.value = "外观保存失败，请重试" }
            finally { mutableSaving.value = false }
        }
    }
}
