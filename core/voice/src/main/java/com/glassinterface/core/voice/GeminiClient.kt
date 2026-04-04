package com.glassinterface.core.voice

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini LLM client using the REST API directly via OkHttp.
 *
 * Provides context-aware conversational Q&A — the system prompt includes
 * the current scene (detected objects), saved memories, and time so
 * the assistant can answer questions about the user's environment.
 */
@Singleton
class GeminiClient @Inject constructor() {

    companion object {
        private const val TAG = "GeminiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
        private const val MAX_HISTORY = 10
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** Conversation history for multi-turn Q&A (role + text pairs). */
    private val history = mutableListOf<Pair<String, String>>()

    /**
     * Send a user query to Gemini with scene context.
     *
     * @param query The user's spoken question
     * @param apiKey Gemini API key from settings
     * @param sceneContext Description of what the camera currently sees
     * @param memorySummary Summary of saved memories
     * @return The assistant's response text, or an error message
     */
    suspend fun ask(
        query: String,
        apiKey: String,
        sceneContext: String = "",
        memorySummary: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "Gemini API key not set. Go to Settings to add your key."
        }

        try {
            val systemPrompt = buildSystemPrompt(sceneContext, memorySummary)
            val requestBody = buildRequestBody(systemPrompt, query)

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API error ${response.code}: $body")
                return@withContext when (response.code) {
                    400 -> "I couldn't process that request."
                    401, 403 -> "Invalid Gemini API key. Check Settings."
                    429 -> "Too many requests. Try again in a moment."
                    else -> "Gemini error: ${response.code}"
                }
            }

            val text = parseResponse(body)

            // Update conversation history
            history.add("user" to query)
            history.add("model" to text)
            if (history.size > MAX_HISTORY * 2) {
                history.removeAt(0)
                history.removeAt(0)
            }

            text
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "No internet for Gemini")
            "I need internet for that question. Check your connection."
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Gemini timed out")
            "The request timed out. Try again."
        } catch (e: Exception) {
            Log.e(TAG, "Gemini request failed", e)
            "Sorry, something went wrong: ${e.message}"
        }
    }

    fun clearHistory() {
        history.clear()
    }

    private fun buildSystemPrompt(sceneContext: String, memorySummary: String): String {
        val sb = StringBuilder()
        sb.appendLine("You are GlassAssistant, a helpful AI assistant embedded in an assistive vision app for visually impaired users.")
        sb.appendLine("You answer concisely — keep responses under 3 sentences since they will be spoken aloud via TTS.")
        sb.appendLine("Be warm, clear, and practical. Avoid markdown formatting — plain text only.")
        sb.appendLine()

        if (sceneContext.isNotBlank()) {
            sb.appendLine("CURRENT SCENE (what the camera sees right now):")
            sb.appendLine(sceneContext)
            sb.appendLine()
        }

        if (memorySummary.isNotBlank()) {
            sb.appendLine("USER'S SAVED MEMORIES:")
            sb.appendLine(memorySummary)
            sb.appendLine()
        }

        val time = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date())
        sb.appendLine("CURRENT TIME: $time")

        return sb.toString()
    }

    private fun buildRequestBody(systemPrompt: String, query: String): String {
        val root = JsonObject()

        // System instruction
        val systemInstruction = JsonObject()
        val systemParts = JsonArray()
        val systemPart = JsonObject()
        systemPart.addProperty("text", systemPrompt)
        systemParts.add(systemPart)
        systemInstruction.add("parts", systemParts)
        root.add("system_instruction", systemInstruction)

        // Contents (conversation history + current query)
        val contents = JsonArray()

        // Add history
        for ((role, text) in history) {
            val msg = JsonObject()
            msg.addProperty("role", role)
            val parts = JsonArray()
            val part = JsonObject()
            part.addProperty("text", text)
            parts.add(part)
            msg.add("parts", parts)
            contents.add(msg)
        }

        // Add current query
        val userMsg = JsonObject()
        userMsg.addProperty("role", "user")
        val userParts = JsonArray()
        val userPart = JsonObject()
        userPart.addProperty("text", query)
        userParts.add(userPart)
        userMsg.add("parts", userParts)
        contents.add(userMsg)

        root.add("contents", contents)

        // Generation config
        val genConfig = JsonObject()
        genConfig.addProperty("maxOutputTokens", 200)
        genConfig.addProperty("temperature", 0.7)
        root.add("generationConfig", genConfig)

        return gson.toJson(root)
    }

    private fun parseResponse(body: String): String {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
            val candidates = json.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val content = candidates[0].asJsonObject.getAsJsonObject("content")
                val parts = content.getAsJsonArray("parts")
                if (parts != null && parts.size() > 0) {
                    parts[0].asJsonObject.get("text").asString.trim()
                } else "I got an empty response."
            } else "I couldn't generate a response."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response: $body", e)
            "I had trouble understanding the response."
        }
    }
}
