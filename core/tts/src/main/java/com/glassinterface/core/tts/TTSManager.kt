package com.glassinterface.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Text-to-Speech alerts with cooldown logic.
 *
 * Ensures alerts are not spammed by enforcing a minimum gap between
 * consecutive TTS utterances. Thread-safe via synchronized access.
 */
@Singleton
class TTSManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "TTSManager"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    // Per-label last-spoken timestamps so one dominant object (e.g. "person")
    // cannot starve other labels from being announced.
    private val labelLastSpokenMs = mutableMapOf<String, Long>()
    // Track the last spoken message to avoid repeating identical phrases
    private var lastSpokenMessage: String = ""

    private val _isSpeaking = MutableStateFlow(false)
    /** Observable state for whether TTS is currently speaking. */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    
    @Volatile
    private var currentPriority: String = "INFO"

    /**
     * Initialize the TTS engine. Call this in Application.onCreate or Activity.onCreate.
     */
    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.w(TAG, "TTS language not supported, falling back to default")
                    }
                    engine.setSpeechRate(1.1f) // Slightly faster for alerts
                    engine.setPitch(1.0f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }
                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            Log.e(TAG, "TTS error for utterance: $utteranceId")
                        }
                    })
                    isInitialized = true
                    Log.i(TAG, "TTS initialized successfully")
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    /**
     * Speak an alert message if the per-label cooldown has elapsed.
     *
     * Cooldown is scaled by priority:
     *   CRITICAL  → uses [cooldownMs] as-is
     *   WARNING   → 1.5× cooldown
     *   INFO      → 2.5× cooldown
     *
     * If the exact same message was spoken last time, it won't repeat
     * until 2× the effective cooldown has elapsed.
     *
     * @param message  The alert text to speak.
     * @param cooldownMs Base cooldown in milliseconds.
     * @param priority  "CRITICAL", "WARNING", or "INFO".
     * @param label     The detected object label (e.g. "car").
     * @return true if alert was spoken.
     */
    @Synchronized
    fun speakAlert(
        message: String,
        cooldownMs: Long,
        priority: String = "INFO",
        label: String = ""
    ): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, skipping alert: $message")
            return false
        }

        val now = System.currentTimeMillis()

        // Priority-based cooldown multiplier
        val multiplier = when (priority) {
            "CRITICAL" -> 1.0f
            "WARNING"  -> 1.5f
            else       -> 2.5f
        }
        val effectiveCooldown = (cooldownMs * multiplier).toLong()

        // Repeat suppression: if identical message, double the cooldown
        val repeatMultiplier = if (message == lastSpokenMessage) 2L else 1L
        val finalCooldown = effectiveCooldown * repeatMultiplier

        val key = label.ifBlank { message }
        val lastSpoken = labelLastSpokenMs[key] ?: 0L
        if (now - lastSpoken < finalCooldown) {
            Log.d(TAG, "[$priority/$label] suppressed by cooldown (${now - lastSpoken}ms < ${finalCooldown}ms)")
            return false
        }

        // Do not interrupt the voice assistant for routine spatial alerts
        if (_isSpeaking.value && currentPriority == "voice_assistant" && priority != "CRITICAL" && priority != "voice_assistant") {
            Log.d(TAG, "Skipping $priority alert so it doesn't interrupt the voice assistant.")
            return false
        }

        // Adjust speech rate live for CRITICAL urgency
        tts?.setSpeechRate(if (priority == "CRITICAL") 1.25f else 1.05f)

        labelLastSpokenMs[key] = now
        lastSpokenMessage = message
        currentPriority = priority
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.i(TAG, "[$priority] Speaking: $message")
        return true
    }

    /**
     * Legacy single-argument overload for backward compatibility.
     */
    @Synchronized
    fun speakAlert(message: String, cooldownMs: Long): Boolean =
        speakAlert(message, cooldownMs, "INFO", "")

    /**
     * Stop any current speech immediately.
     */
    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    /**
     * Release TTS resources. Call when the app is being destroyed.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _isSpeaking.value = false
    }
}
