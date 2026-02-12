package com.glassinterface.core.aibridge

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.glassinterface.core.common.Alert
import com.glassinterface.core.common.BoundingBox
import com.glassinterface.core.common.DetectionResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real AI engine implementation that connects to the Python FastAPI server
 * via the REST `/process` endpoint.
 *
 * Pipeline:
 *   1. Bitmap → compress to JPEG bytes
 *   2. POST as multipart file to `/process`
 *   3. Receive FrameResult JSON
 *   4. Parse JSON → DetectionResult
 */
@Singleton
class NetworkAIEngine @Inject constructor() : AIEngine {

    companion object {
        private const val TAG = "NetworkAIEngine"
        private const val JPEG_QUALITY = 70
    }

    private val gson = Gson()
    private lateinit var client: OkHttpClient

    // Server URL — can be updated via settings
    // Default: emulator's host alias for localhost
    @Volatile
    var serverUrl: String = "http://10.0.2.2:8000"

    override suspend fun initialize() {
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        // Verify server is reachable
        try {
            val healthUrl = "$serverUrl/health"
            val request = Request.Builder().url(healthUrl).get().build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (response.isSuccessful) {
                Log.i(TAG, "Server connected: ${response.body?.string()}")
            } else {
                Log.w(TAG, "Server health check failed: ${response.code}")
            }
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Server unreachable at $serverUrl: ${e.message}")
        }
    }

    override suspend fun process(frame: Bitmap): DetectionResult {
        return try {
            // Step 1: Compress bitmap to JPEG bytes
            val jpegBytes = bitmapToJpeg(frame)

            // Step 2: Build multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "frame.jpg",
                    jpegBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$serverUrl/process")
                .post(requestBody)
                .build()

            // Step 3: Execute HTTP request on IO dispatcher
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }

            // Step 4: Parse response
            val json = response.body?.string() ?: "{}"
            response.close()

            if (response.isSuccessful) {
                parseFrameResult(json)
            } else {
                Log.e(TAG, "Server error ${response.code}: $json")
                DetectionResult(boxes = emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Process frame failed: ${e.message}")
            DetectionResult(boxes = emptyList())
        }
    }

    override fun release() {
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    /**
     * Parse the FrameResult JSON from the Python server into Kotlin models.
     *
     * Expected JSON shape:
     * {
     *   "processing_time_ms": 38.2,
     *   "detections": [{ "label": "person", "confidence": 0.87, "bbox": [x1,y1,x2,y2], ... }],
     *   "alerts": [{ "priority": "CRITICAL", "message": "...", ... }]
     * }
     */
    private fun parseFrameResult(json: String): DetectionResult {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)

            if (root.has("error")) {
                Log.e(TAG, "Server error: ${root.get("error").asString}")
                return DetectionResult(boxes = emptyList())
            }

            val processingTime = root.get("processing_time_ms")?.asFloat ?: 0f

            val detections = root.getAsJsonArray("detections")?.map { elem ->
                val det = elem.asJsonObject
                val bbox = det.getAsJsonArray("bbox")
                val x1 = bbox[0].asFloat
                val y1 = bbox[1].asFloat
                val x2 = bbox[2].asFloat
                val y2 = bbox[3].asFloat

                BoundingBox(
                    label = det.get("label").asString,
                    confidence = det.get("confidence").asFloat,
                    rect = RectF(x1, y1, x2, y2),
                    distance = det.get("distance")?.asFloat ?: 0f,
                    direction = det.get("direction")?.asString ?: "CENTER",
                    velocity = det.get("velocity")?.asFloat ?: 0f,
                    approaching = det.get("approaching")?.asBoolean ?: false,
                    riskScore = det.get("risk_score")?.asFloat ?: 0f,
                    trackingId = det.get("id")?.asInt
                )
            } ?: emptyList()

            val alerts = root.getAsJsonArray("alerts")?.map { elem ->
                val alert = elem.asJsonObject
                Alert(
                    priority = alert.get("priority").asString,
                    message = alert.get("message").asString,
                    label = alert.get("label")?.asString ?: "",
                    distance = alert.get("distance")?.asFloat ?: 0f,
                    direction = alert.get("direction")?.asString ?: "CENTER"
                )
            } ?: emptyList()

            DetectionResult(
                boxes = detections,
                alerts = alerts,
                processingTimeMs = processingTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}", e)
            DetectionResult(boxes = emptyList())
        }
    }
}
