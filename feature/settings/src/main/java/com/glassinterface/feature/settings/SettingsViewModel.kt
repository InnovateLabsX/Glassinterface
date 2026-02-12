package com.glassinterface.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassinterface.core.common.AlertConfig
import com.glassinterface.core.common.SceneMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val alertConfig: StateFlow<AlertConfig> = settingsRepository.alertConfig
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AlertConfig()
        )

    fun onSensitivityChanged(value: Float) {
        viewModelScope.launch {
            settingsRepository.updateSensitivity(value)
        }
    }

    fun onSceneModeChanged(mode: SceneMode) {
        viewModelScope.launch {
            settingsRepository.updateSceneMode(mode)
        }
    }

    fun onCooldownChanged(ms: Long) {
        viewModelScope.launch {
            settingsRepository.updateCooldown(ms)
        }
    }

    fun onServerUrlChanged(url: String) {
        viewModelScope.launch {
            settingsRepository.updateServerUrl(url)
        }
    }
}
