package com.lr.thingstalk.presentation.mqtt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lr.thingstalk.domain.mqtt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MqttProfilesViewModel(private val repository: MqttProfileRepository) : ViewModel() {
    val profiles = repository.state
    val passwordsPersisted get() = repository.passwordsPersisted
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableLoaded = MutableStateFlow(false)
    val loaded = mutableLoaded.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    init { reload() }
    fun reload() = action { repository.load(); mutableLoaded.value = true }
    fun save(profile: MqttProfile, owner: String, done: () -> Unit) = action { repository.save(profile, owner); done() }
    fun select(owner: String, id: String) = action { repository.select(owner, id) }
    fun delete(id: String) = action { repository.delete(id) }
    fun connect(owner: String, action: (MqttConnectionConfig) -> Unit) {
        if (!loaded.value || busy.value) return
        try {
            val profile = profiles.value.selectedProfile(owner) ?: throw MqttException("请先选择连接配置")
            action(profile.connectionConfig())
        } catch (failure: MqttException) { mutableError.value = failure.message }
    }
    private fun action(block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true; mutableError.value = null
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: MqttException) { mutableError.value = failure.message }
            catch (failure: IllegalArgumentException) { mutableError.value = "配置无效，请检查名称、地址、Client ID 和字段长度" }
            catch (failure: Exception) { mutableError.value = "连接配置读取或保存失败，原数据已保留，请重试" }
            finally { mutableBusy.value = false }
        }
    }
}
