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
    private var lastAlertTimeMs = 0L

    private val _isSpeaking = MutableStateFlow(false)
    /** Observable state for whether TTS is currently speaking. */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

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
     * Speak an alert message if the cooldown period has elapsed.
     *
     * @param message The alert text to speak.
     * @param cooldownMs Minimum time between alerts in milliseconds.
     * @return true if the alert was spoken, false if it was suppressed by cooldown.
     */
    @Synchronized
    fun speakAlert(message: String, cooldownMs: Long): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, skipping alert: $message")
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastAlertTimeMs < cooldownMs) {
            Log.d(TAG, "Alert suppressed by cooldown: $message")
            return false
        }

        lastAlertTimeMs = now
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.i(TAG, "Speaking alert: $message")
        return true
    }

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
