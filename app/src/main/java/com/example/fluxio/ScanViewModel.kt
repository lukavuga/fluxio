package com.example.fluxio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScanViewModel(
    private val supabaseRepository: SupabaseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState

    /**
     * Requirement: Save discovered devices to Supabase.
     */
    fun registerDiscoveredDevices(networkUuid: String, discoveredDevices: List<SupabaseDevice>) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Saving
            try {
                val devicesToSave = discoveredDevices.map { device ->
                    device.copy(networkId = networkUuid)
                }

                // Batch insert using Supabase Postgrest
                supabaseRepository.saveDevices(devicesToSave)

                _uiState.value = ScanUiState.Success("Successfully registered ${devicesToSave.size} devices.")
            } catch (e: Exception) {
                // Handle network errors or serialization issues
                _uiState.value = ScanUiState.Error("Failed to register devices: ${e.localizedMessage}")
            }
        }
    }
}

sealed class ScanUiState {
    object Idle : ScanUiState()
    object Saving : ScanUiState()
    data class Success(val message: String) : ScanUiState()
    data class Error(val message: String) : ScanUiState()
}
