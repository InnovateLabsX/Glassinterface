package com.glassinterface.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassinterface.core.aibridge.AIEngine
import com.glassinterface.core.aibridge.NetworkAIEngine
import com.glassinterface.core.camera.CameraFrameProvider
import com.glassinterface.core.common.Alert
import com.glassinterface.core.common.AlertConfig
import com.glassinterface.core.common.BoundingBox
import com.glassinterface.core.common.SceneMode
import com.glassinterface.core.tts.TTSManager
import com.glassinterface.feature.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the main camera + detection screen.
 */
data class MainUiState(
    val boundingBoxes: List<BoundingBox> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val lastAlert: String? = null,
    val isInferenceRunning: Boolean = false,
    val isConnected: Boolean = false,
    val fps: Int = 0,
    val serverProcessingMs: Float = 0f,
    val alertConfig: AlertConfig = AlertConfig()
)

/**
 * Main ViewModel orchestrating the camera → AI → overlay → TTS pipeline.
 *
 * - Collects frames from [CameraFrameProvider]
 * - Sends to [AIEngine] (Python server via WebSocket)
 * - Updates UI state with bounding boxes, alerts, FPS
 * - Triggers TTS alerts with priority-aware cooldown
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val cameraFrameProvider: CameraFrameProvider,
    private val aiEngine: AIEngine,
    private val ttsManager: TTSManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var frameCount = 0L
    private var lastFpsTime = System.currentTimeMillis()

    init {
        observeSettings()
        initializeAIEngine()
        startInferenceLoop()
    }

    private fun initializeAIEngine() {
        viewModelScope.launch(Dispatchers.Default) {
            // Pass server URL to network engine if applicable
            val config = _uiState.value.alertConfig
            if (aiEngine is NetworkAIEngine) {
                aiEngine.serverUrl = config.serverUrl
            }
            aiEngine.initialize()
            _uiState.update { it.copy(isConnected = true) }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.alertConfig.collect { config ->
                _uiState.update { it.copy(alertConfig = config) }
                // Update server URL on the fly
                if (aiEngine is NetworkAIEngine) {
                    aiEngine.serverUrl = config.serverUrl
                }
            }
        }
    }

    /**
     * Core inference loop.
     * Collects the latest frame from the conflated channel,
     * sends to AI server, and updates UI state.
     */
    private fun startInferenceLoop() {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isInferenceRunning = true) }

            cameraFrameProvider.frames.collect { bitmap ->
                try {
                    val result = aiEngine.process(bitmap)

                    // Filter boxes by sensitivity threshold
                    val config = _uiState.value.alertConfig
                    val filteredBoxes = result.boxes.filter {
                        it.confidence >= config.sensitivity
                    }

                    // Calculate FPS
                    frameCount++
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsTime
                    val fps = if (elapsed >= 1000) {
                        val currentFps = (frameCount * 1000 / elapsed).toInt()
                        frameCount = 0
                        lastFpsTime = now
                        currentFps
                    } else {
                        _uiState.value.fps
                    }

                    // Get the highest-priority alert message
                    val topAlert = result.alerts.firstOrNull()

                    _uiState.update {
                        it.copy(
                            boundingBoxes = filteredBoxes,
                            alerts = result.alerts,
                            lastAlert = topAlert?.message,
                            fps = fps,
                            serverProcessingMs = result.processingTimeMs
                        )
                    }

                    // Trigger TTS for the most urgent alert
                    topAlert?.let { alert ->
                        ttsManager.speakAlert(alert.message, config.cooldownMs)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "Inference error", e)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    /** Expose the frame provider so the UI can wire up CameraX. */
    fun getCameraFrameProvider(): CameraFrameProvider = cameraFrameProvider

    override fun onCleared() {
        super.onCleared()
        aiEngine.release()
    }
}
