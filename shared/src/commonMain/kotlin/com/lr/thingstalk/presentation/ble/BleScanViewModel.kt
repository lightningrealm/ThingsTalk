package com.lr.thingstalk.presentation.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lr.thingstalk.domain.ble.BleScanDevice
import com.lr.thingstalk.domain.ble.BleScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class BleScanUiState(
    val scanning: Boolean = false,
    val devices: List<BleScanDevice> = emptyList(),
    val hasScanned: Boolean = false,
    val error: String? = null,
    val filter: BleScanFilter = BleScanFilter(),
) {
    val visibleDevices: List<BleScanDevice>
        get() = devices.filter(filter::matches)
}

class BleScanViewModel(private val scanner: BleScanner) : ViewModel() {
    private val mutableState = MutableStateFlow(BleScanUiState())
    val state = mutableState.asStateFlow()
    private var scanJob: Job? = null
    private var generation = 0

    fun start() {
        if (scanJob?.isActive == true) return
        val previous = scanJob
        val currentGeneration = ++generation
        mutableState.update { BleScanUiState(scanning = true, hasScanned = true, filter = it.filter) }
        scanJob = viewModelScope.launch {
            try {
                previous?.cancelAndJoin()
                withTimeoutOrNull(15_000) {
                    scanner.scan().collect { found ->
                        mutableState.update { state ->
                            val results = state.devices.associateBy { it.id }.toMutableMap()
                            // Keep bounded state in crowded environments and update repeated advertisements.
                            if (results.size < 200 || found.id in results) results[found.id] = found
                            if (generation == currentGeneration) state.copy(devices = results.values.sortedByDescending { it.rssi }) else state
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == currentGeneration) mutableState.update { it.copy(error = failure.message ?: "扫描失败，请检查蓝牙和权限后重试") }
            }
            finally { if (generation == currentGeneration) mutableState.update { it.copy(scanning = false) } }
        }
    }

    fun setFilter(filter: BleScanFilter) { mutableState.update { it.copy(filter = filter) } }
    fun clearFilter() { setFilter(BleScanFilter()) }

    fun stop() { generation++; scanJob?.cancel(); mutableState.update { it.copy(scanning = false) } }
    override fun onCleared() { stop(); super.onCleared() }
}
