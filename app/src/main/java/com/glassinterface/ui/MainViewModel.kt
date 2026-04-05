package com.glassinterface.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
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
import com.glassinterface.core.voice.GeminiClient
import com.glassinterface.core.voice.HandsFreeSensorManager
import com.glassinterface.core.voice.VoiceCommand
import com.glassinterface.core.voice.VoiceInputManager
import com.glassinterface.feature.settings.SettingsRepository
import com.google.android.gms.location.FusedLocationProviderClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * - Face Recognition (ML Kit) in parallel
 * - Voice assistant → keyword commands + Gemini LLM Q&A
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val cameraFrameProvider: CameraFrameProvider,
    private val aiEngine: AIEngine,
    private val ttsManager: TTSManager,
    private val settingsRepository: SettingsRepository,
    private val memoryRepository: MemoryRepository,
    val voiceInputManager: VoiceInputManager,
    private val locationClient: FusedLocationProviderClient,
    private val geminiClient: GeminiClient,
    private val sensorManager: HandsFreeSensorManager
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val faceEngine = FaceRecognitionEngine()
    private var frameCount = 0L
    private var lastFpsTime = System.currentTimeMillis()

    @Volatile
    private var lastFrame: Bitmap? = null

    /**
     * Returns a safe copy of the latest frame. This prevents the inference loop
     * from recycling/overwriting the bitmap while a voice command is using it.
     */
    private fun captureFrame(): Bitmap? {
        return try {
            lastFrame?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy last frame", e)
            null
        }
    }

    /** Last spoken response for REPEAT command. */
    private var lastSpokenText: String = ""

    @Volatile
    private var isVoiceSessionActive = false

    init {
        observeSettings()
        initializeAIEngine()
        startInferenceLoop()
        observeVoiceCommands()
        voiceInputManager.startContinuousListening()
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

                // Handle Hands-Free Sensors
                if (config.shakeToWake || config.proximityWake) {
                    sensorManager.startListening(
                        enableShake = config.shakeToWake,
                        enableProximity = config.proximityWake,
                        onTriggerCallback = { toggleVoiceInput() }
                    )
                } else {
                    sensorManager.stopListening()
                }
            }
        }
    }

    // ── Voice Command Handling ────────────────────────────────────────

    fun toggleVoiceInput() {
        if (voiceInputManager.isListening.value && !voiceInputManager.isContinuousMode) {
            voiceInputManager.stopListening()
        } else {
            ttsManager.stop()
            isVoiceSessionActive = true
            voiceInputManager.isAwaitingCommand = true
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

                // ── v0.6.2 New Commands ──
                CommandType.ASK_GEMINI -> handleAskGemini(command.payload)
                CommandType.TIME_DATE -> handleTimeDate()
                CommandType.BATTERY -> handleBattery()
                CommandType.REPEAT -> handleRepeat()
                CommandType.STOP -> handleStop()
                CommandType.READ_TEXT -> handleReadText()
                CommandType.NAVIGATE -> handleNavigate(command.payload)

                CommandType.UNKNOWN -> {
                    if (command.payload == "WAKE_WORD_ACK") {
                        ttsManager.stop()
                        isVoiceSessionActive = true
                        speak("I'm listening.")
                        return@launch
                    } else if (command.payload.isNotBlank()) {
                        speak("I didn't quite catch that. Try asking Gemini or use a specific command.")
                    }
                }
            }
            isVoiceSessionActive = false
            voiceInputManager.consumeCommand()
        }
    }

    // ── Gemini Q&A ───────────────────────────────────────────────────

    private suspend fun handleAskGemini(query: String) {
        if (query.isBlank()) {
            speak("I didn't hear a question. Try again.")
            return
        }

        speak("Let me think...")

        val apiKey = settingsRepository.geminiApiKey.first()
        if (apiKey.isBlank()) {
            speak("Gemini API key not set. Go to Settings to add your key.")
            return
        }

        val sceneContext = buildSceneContext()
        val memorySummary = try { memoryRepository.getMemorySummary() } catch (_: Exception) { "" }

        val response = geminiClient.ask(
            query = query,
            apiKey = apiKey,
            sceneContext = sceneContext,
            memorySummary = memorySummary
        )

        speak(response)
    }

    private fun buildSceneContext(): String {
        val boxes = _uiState.value.boundingBoxes
        if (boxes.isEmpty()) return "No objects currently detected."

        return boxes.groupBy { it.label }.map { (label, items) ->
            val count = items.size
            val closest = items.minByOrNull { it.distance }
            val dist = if (closest != null && closest.distance > 0)
                "nearest ${"%.1f".format(closest.distance)}m ${closest.direction.lowercase()}" else ""
            if (count > 1) "$count ${label}s $dist" else "$label $dist"
        }.joinToString("; ")
    }

    // ── New Simple Commands ──────────────────────────────────────────

    private fun handleTimeDate() {
        val dateTime = java.text.SimpleDateFormat(
            "EEEE, MMMM d, h:mm a", java.util.Locale.getDefault()
        ).format(java.util.Date())
        speak("It's $dateTime.")
    }

    private fun handleBattery() {
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = (level * 100 / scale.coerceAtLeast(1))
        val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                BatteryManager.BATTERY_STATUS_CHARGING
        speak("Battery is at $pct percent.${if (charging) " Currently charging." else ""}")
    }

    private fun handleRepeat() {
        if (lastSpokenText.isBlank()) {
            speak("Nothing to repeat.")
        } else {
            speak(lastSpokenText)
        }
    }

    private fun handleStop() {
        ttsManager.stop()
        _uiState.update { it.copy(voiceFeedback = null) }
    }

    private suspend fun handleReadText() {
        // OCR is a placeholder — route to Gemini with a text-reading prompt
        val apiKey = settingsRepository.geminiApiKey.first()
        if (apiKey.isBlank()) {
            speak("Text reading requires a Gemini API key. Go to Settings.")
            return
        }
        speak("Text reading is coming in a future update. You can ask me to describe what I see instead.")
    }

    private suspend fun handleNavigate(destination: String) {
        if (destination.isBlank()) {
            speak("Where would you like to go? Say navigate to, followed by the place name.")
            return
        }
        // Route to Gemini for directions advice
        val sceneContext = buildSceneContext()
        val apiKey = settingsRepository.geminiApiKey.first()
        if (apiKey.isBlank()) {
            speak("Navigation advice requires a Gemini API key. Go to Settings.")
            return
        }
        val response = geminiClient.ask(
            query = "I'm visually impaired and I need to navigate to $destination. Based on what my camera sees, give me brief safety-aware walking directions.",
            apiKey = apiKey,
            sceneContext = sceneContext
        )
        speak(response)
    }

    // ── Existing Command Handlers (from v0.6.0) ─────────────────────

    private suspend fun handleSaveFace(name: String) {
        val frame = captureFrame()
        if (frame == null) { speak("No camera frame available."); return }
        try {
            val faces = withContext(Dispatchers.Default) { faceEngine.detectFaces(frame) }
            val faceName = name.ifBlank { "Unknown" }
            if (faces.isEmpty()) {
                // Use the correct embedding size so matching works later
                val emptyEmbedding = FloatArray(FaceRecognitionEngine.EMBEDDING_SIZE) { 0f }
                memoryRepository.saveFace(faceName, emptyEmbedding, frame)
                speak("No confident face detected, but saved the screenshot as $faceName.")
                return
            }
            val face = faces.first()
            val thumbnail = withContext(Dispatchers.Default) {
                faceEngine.cropFace(frame, face.boundingBox)
            } ?: frame  // fall back to full frame if crop fails
            memoryRepository.saveFace(faceName, face.embedding, thumbnail)
            Log.d(TAG, "Saved face '$faceName' with embedding size=${face.embedding.size}")
            speak("Saved face as $faceName.")
        } catch (e: Exception) {
            Log.e(TAG, "handleSaveFace failed", e)
            speak("Failed to save face. Please try again.")
        }
    }

    private suspend fun handleSaveObject() {
        val boxes = _uiState.value.boundingBoxes
        val frame = captureFrame()
        try {
            if (boxes.isEmpty()) {
                if (frame != null) {
                    memoryRepository.saveObject("Unknown Scene", 0f, frame)
                    speak("No specific objects detected, but saved the scene screenshot.")
                } else {
                    speak("No objects detected to save and no camera frame.")
                }
                return
            }
            val topBox = boxes.maxByOrNull { it.confidence }!!
            val thumbnail = frame?.let { cropBox(it, topBox) } ?: frame
            memoryRepository.saveObject(topBox.label, topBox.confidence, thumbnail)
            Log.d(TAG, "Saved object '${topBox.label}' conf=${topBox.confidence}")
            speak("Saved ${topBox.label} with ${(topBox.confidence * 100).toInt()}% confidence.")
        } catch (e: Exception) {
            Log.e(TAG, "handleSaveObject failed", e)
            speak("Failed to save object. Please try again.")
        }
    }

    private suspend fun handleSaveContact(payload: String) {
        val name = payload.ifBlank { "Unknown contact" }
        memoryRepository.saveContact(name)
        speak("Saved contact $name.")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun handleSaveLocation(payload: String) {
        val label = payload.ifBlank { "My location" }
        try {
            locationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    viewModelScope.launch {
                        memoryRepository.saveLocation(label, location.latitude, location.longitude)
                        speak("Saved location $label.")
                    }
                } else {
                    viewModelScope.launch {
                        memoryRepository.saveLocation(label, 0.0, 0.0)
                        speak("Could not get exact GPS, but saved a bookmark for $label.") 
                    }
                }
            }.addOnFailureListener {
                viewModelScope.launch {
                    memoryRepository.saveLocation(label, 0.0, 0.0)
                    speak("Location error, but saved a bookmark for $label.") 
                }
            }
        } catch (e: Exception) { 
            viewModelScope.launch { 
                memoryRepository.saveLocation(label, 0.0, 0.0)
                speak("Location error, but saved a bookmark for $label.") 
            } 
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
        if (payload.isBlank()) { speak("Please say what you want to note."); return }
        memoryRepository.saveNote(payload)
        speak("Note saved: $payload")
    }

    private fun handleDescribeScene() {
        val boxes = _uiState.value.boundingBoxes
        if (boxes.isEmpty()) { speak("I don't see anything right now."); return }
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
        val frame = captureFrame()
        if (frame == null) { speak("No camera frame available."); return }
        try {
            val faces = withContext(Dispatchers.Default) { faceEngine.detectFaces(frame) }
            if (faces.isEmpty()) { speak("No face detected."); return }
            val face = faces.first()
            Log.d(TAG, "Identifying face: embedding size=${face.embedding.size}, values=${face.embedding.take(4).joinToString()}")
            val name = memoryRepository.findFaceByEmbedding(face.embedding)
            speak(if (name != null) "I think this is $name." else "I don't recognize this person.")
        } catch (e: Exception) {
            Log.e(TAG, "handleIdentifyFace failed", e)
            speak("Face identification failed. Please try again.")
        }
    }

    private suspend fun handleListMemories() {
        val summary = memoryRepository.getMemorySummary()
        speak(summary)
    }

    private fun handleHelp() {
        speak("You can say: save face, save this, save contact, save location, " +
                "save time, save note, what do you see, who is this, list memories, " +
                "what time is it, battery level, repeat, stop. " +
                "Or ask me anything and I'll use Gemini AI to answer.")
    }

    // ── TTS Helper ───────────────────────────────────────────────────

    private fun speak(text: String) {
        lastSpokenText = text
        _uiState.update { it.copy(voiceFeedback = text) }
        ttsManager.speakAlert(text, cooldownMs = 0, priority = "voice_assistant", label = "voice_assistant")
    }

    // ── Inference Loop ───────────────────────────────────────────────

    private fun startInferenceLoop() {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isInferenceRunning = true) }

            cameraFrameProvider.frames.collect { bitmap ->
                try {
                    lastFrame = bitmap
                    val result = aiEngine.process(bitmap)

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

                    if (!isVoiceSessionActive && (!voiceInputManager.isListening.value || voiceInputManager.isContinuousMode)) {
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
        } catch (e: Exception) { null }
    }

    override fun onCleared() {
        super.onCleared()
        aiEngine.release()
        faceEngine.release()
        voiceInputManager.destroy()
        sensorManager.stopListening()
    }
}
