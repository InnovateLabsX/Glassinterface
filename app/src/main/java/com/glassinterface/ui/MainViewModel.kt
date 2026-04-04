package com.glassinterface.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassinterface.core.aibridge.AIEngine
import com.glassinterface.core.aibridge.engine.FaceRecognitionEngine
import com.glassinterface.core.camera.CameraFrameProvider
import com.glassinterface.core.common.Alert
import com.glassinterface.core.common.AlertConfig
import com.glassinterface.core.common.BoundingBox
import com.glassinterface.core.memory.MemoryRepository
import com.glassinterface.core.tts.TTSManager
import com.glassinterface.core.voice.CommandType
import com.glassinterface.core.voice.VoiceCommand
import com.glassinterface.core.voice.VoiceInputManager
import com.glassinterface.feature.settings.SettingsRepository
import com.google.android.gms.location.FusedLocationProviderClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val alertConfig: AlertConfig = AlertConfig(),
    val isListening: Boolean = false,
    val voiceFeedback: String? = null
)

/**
 * Main ViewModel orchestrating:
 * - Camera → AI Object Detection → Overlay → TTS alerts
 * - Face Recognition (ML Kit) running in parallel with object detection
 * - Voice assistant input → memory save/recall commands
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val cameraFrameProvider: CameraFrameProvider,
    private val aiEngine: AIEngine,
    private val ttsManager: TTSManager,
    private val settingsRepository: SettingsRepository,
    private val memoryRepository: MemoryRepository,
    val voiceInputManager: VoiceInputManager,
    private val locationClient: FusedLocationProviderClient
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val faceEngine = FaceRecognitionEngine()
    private var frameCount = 0L
    private var lastFpsTime = System.currentTimeMillis()

    /** The last frame bitmap, kept for cropping on "save" commands. */
    @Volatile
    private var lastFrame: Bitmap? = null

    init {
        observeSettings()
        initializeAIEngine()
        startInferenceLoop()
        observeVoiceCommands()
    }

    private fun initializeAIEngine() {
        viewModelScope.launch(Dispatchers.Default) {
            aiEngine.initialize()
            _uiState.update { it.copy(isConnected = true) }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.alertConfig.collect { config ->
                _uiState.update { it.copy(alertConfig = config) }
                if (config.useExternalCamera) {
                    cameraFrameProvider.startExternalStream(config.serverUrl, viewModelScope)
                } else {
                    cameraFrameProvider.stopExternalStream()
                }
            }
        }
    }

    // ── Voice Command Handling ────────────────────────────────────────

    fun toggleVoiceInput() {
        if (voiceInputManager.isListening.value) {
            voiceInputManager.stopListening()
        } else {
            ttsManager.stop() // Stop TTS making noise to free mic
            voiceInputManager.startListening()
        }
    }

    private fun observeVoiceCommands() {
        viewModelScope.launch {
            voiceInputManager.isListening.collect { listening ->
                _uiState.update { it.copy(isListening = listening) }
            }
        }
        viewModelScope.launch {
            voiceInputManager.lastCommand.collect { cmd ->
                if (cmd != null) handleVoiceCommand(cmd)
            }
        }
    }

    private fun handleVoiceCommand(command: VoiceCommand) {
        viewModelScope.launch {
            when (command.type) {
                CommandType.SAVE_FACE -> handleSaveFace(command.payload)
                CommandType.SAVE_OBJECT -> handleSaveObject()
                CommandType.SAVE_CONTACT -> handleSaveContact(command.payload)
                CommandType.SAVE_LOCATION -> handleSaveLocation(command.payload)
                CommandType.SAVE_TIMESTAMP -> handleSaveTimestamp(command.payload)
                CommandType.SAVE_NOTE -> handleSaveNote(command.payload)
                CommandType.DESCRIBE_SCENE -> handleDescribeScene()
                CommandType.IDENTIFY_FACE -> handleIdentifyFace()
                CommandType.LIST_MEMORIES -> handleListMemories()
                CommandType.HELP -> handleHelp()
                CommandType.UNKNOWN -> {
                    speak("Sorry, I didn't understand that. Say help for available commands.")
                }
            }
            voiceInputManager.consumeCommand()
        }
    }

    private suspend fun handleSaveFace(name: String) {
        val frame = lastFrame
        if (frame == null) {
            speak("No camera frame available.")
            return
        }

        val faces = withContext(Dispatchers.Default) { faceEngine.detectFaces(frame) }
        if (faces.isEmpty()) {
            speak("No face detected. Please look at the camera.")
            return
        }

        val face = faces.first()
        val faceName = name.ifBlank { "Unknown" }
        val thumbnail = faceEngine.cropFace(frame, face.boundingBox)
        memoryRepository.saveFace(faceName, face.embedding, thumbnail)
        speak("Saved face as $faceName.")
    }

    private suspend fun handleSaveObject() {
        val boxes = _uiState.value.boundingBoxes
        if (boxes.isEmpty()) {
            speak("No objects detected to save.")
            return
        }
        val topBox = boxes.maxByOrNull { it.confidence }!!
        val frame = lastFrame
        val thumbnail = frame?.let { cropBox(it, topBox) }
        memoryRepository.saveObject(topBox.label, topBox.confidence, thumbnail)
        speak("Saved ${topBox.label} with ${(topBox.confidence * 100).toInt()}% confidence.")
    }

    private suspend fun handleSaveContact(payload: String) {
        val name = payload.ifBlank { "Unknown contact" }
        memoryRepository.saveContact(name)
        speak("Saved contact $name.")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun handleSaveLocation(payload: String) {
        val label = payload.ifBlank { "My location" }
        try {
            locationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    viewModelScope.launch {
                        memoryRepository.saveLocation(label, location.latitude, location.longitude)
                        speak("Saved location $label.")
                    }
                } else {
                    viewModelScope.launch { speak("Could not get your location. Make sure GPS is enabled.") }
                }
            }
        } catch (e: Exception) {
            speak("Location error: ${e.message}")
        }
    }

    private suspend fun handleSaveTimestamp(payload: String) {
        val note = payload.ifBlank { null }
        memoryRepository.saveTimestamp(note = note)
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date())
        speak("Timestamp saved at $time.${if (note != null) " Note: $note" else ""}")
    }

    private suspend fun handleSaveNote(payload: String) {
        if (payload.isBlank()) {
            speak("Please say what you want to note.")
            return
        }
        memoryRepository.saveNote(payload)
        speak("Note saved: $payload")
    }

    private fun handleDescribeScene() {
        val boxes = _uiState.value.boundingBoxes
        if (boxes.isEmpty()) {
            speak("I don't see anything right now.")
            return
        }
        val description = boxes.groupBy { it.label }.map { (label, items) ->
            val count = items.size
            val closest = items.minByOrNull { it.distance }
            val dist = if (closest != null && closest.distance > 0)
                "closest about ${"%.1f".format(closest.distance)} metres ${closest.direction.lowercase()}" else ""
            if (count > 1) "$count ${label}s $dist" else "$label $dist"
        }.joinToString(". ")
        speak("I see: $description.")
    }

    private suspend fun handleIdentifyFace() {
        val frame = lastFrame
        if (frame == null) { speak("No camera frame available."); return }

        val faces = withContext(Dispatchers.Default) { faceEngine.detectFaces(frame) }
        if (faces.isEmpty()) { speak("No face detected."); return }

        val name = memoryRepository.findFaceByEmbedding(faces.first().embedding)
        speak(if (name != null) "I think this is $name." else "I don't recognize this person.")
    }

    private suspend fun handleListMemories() {
        val summary = memoryRepository.getMemorySummary()
        speak(summary)
    }

    private fun handleHelp() {
        speak("Available commands: save face, save this, save contact, save location, " +
                "save time, save note, what do you see, who is this, list memories, help.")
    }

    private fun speak(text: String) {
        _uiState.update { it.copy(voiceFeedback = text) }
        ttsManager.speakAlert(text, cooldownMs = 0, priority = "INFO", label = "voice_assistant")
    }

    // ── Inference Loop ───────────────────────────────────────────────

    private fun startInferenceLoop() {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isInferenceRunning = true) }

            cameraFrameProvider.frames.collect { bitmap ->
                try {
                    lastFrame = bitmap
                    val result = aiEngine.process(bitmap)

                    // Run face recognition in parallel (non-blocking)
                    val faceBoxes = mutableListOf<BoundingBox>()
                    try {
                        val faces = faceEngine.detectFaces(bitmap)
                        for (face in faces) {
                            val name = memoryRepository.findFaceByEmbedding(face.embedding)
                            faceBoxes.add(
                                BoundingBox(
                                    label = "face",
                                    confidence = 0.95f,
                                    rect = face.normalizedBox,
                                    faceName = name ?: "Unknown"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Face detection skipped: ${e.message}")
                    }

                    val config = _uiState.value.alertConfig
                    val filteredBoxes = result.boxes.filter { it.confidence >= config.sensitivity }
                    val allBoxes = filteredBoxes + faceBoxes

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

                    val topAlert = result.alerts.firstOrNull()

                    _uiState.update {
                        it.copy(
                            boundingBoxes = allBoxes,
                            alerts = result.alerts,
                            lastAlert = topAlert?.message,
                            fps = fps,
                            serverProcessingMs = result.processingTimeMs
                        )
                    }

                    if (!voiceInputManager.isListening.value) {
                        topAlert?.let { alert ->
                            ttsManager.speakAlert(
                                message = alert.message,
                                cooldownMs = config.cooldownMs,
                                priority = alert.priority,
                                label = alert.label
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Inference error", e)
                }
            }
        }
    }

    fun getCameraFrameProvider(): CameraFrameProvider = cameraFrameProvider

    private fun cropBox(bitmap: Bitmap, box: BoundingBox): Bitmap? {
        return try {
            val left = (box.rect.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (box.rect.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val right = (box.rect.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (box.rect.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        aiEngine.release()
        faceEngine.release()
        voiceInputManager.destroy()
    }
}
