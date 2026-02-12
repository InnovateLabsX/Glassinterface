package com.glassinterface.core.common

import android.graphics.RectF

/**
 * Structured detection result returned by the AI engine for a single frame.
 *
 * @property boxes List of detected object bounding boxes.
 * @property alerts List of prioritised alerts (CRITICAL/WARNING/INFO).
 * @property processingTimeMs End-to-end inference time reported by the server.
 */
data class DetectionResult(
    val boxes: List<BoundingBox>,
    val alerts: List<Alert> = emptyList(),
    val processingTimeMs: Float = 0f
) {
    /** Convenience: the highest-priority alert message, or null. */
    val alertMessage: String?
        get() = alerts.firstOrNull()?.message
}

/**
 * A single detected object with its bounding box and distance info.
 *
 * @property label Human-readable label (e.g., "person", "car").
 * @property confidence Detection confidence in [0.0, 1.0].
 * @property rect Bounding box in normalized coordinates [0.0, 1.0].
 * @property distance Estimated distance in metres.
 * @property direction Spatial direction: "LEFT", "CENTER", or "RIGHT".
 * @property velocity Object velocity in m/s (negative = approaching).
 * @property approaching Whether the object is moving towards the user.
 * @property riskScore Continuous risk score [0.0, 2.0].
 * @property trackingId Persistent tracking ID across frames.
 */
data class BoundingBox(
    val label: String,
    val confidence: Float,
    val rect: RectF,
    val distance: Float = 0f,
    val direction: String = "CENTER",
    val velocity: Float = 0f,
    val approaching: Boolean = false,
    val riskScore: Float = 0f,
    val trackingId: Int? = null
)

/**
 * A structured alert from the AI engine.
 *
 * @property priority "CRITICAL", "WARNING", or "INFO".
 * @property message TTS-ready string (e.g., "Person ahead, about 2 metres").
 * @property label Object class that triggered this alert.
 * @property distance Distance in metres.
 * @property direction "LEFT", "CENTER", or "RIGHT".
 */
data class Alert(
    val priority: String,
    val message: String,
    val label: String = "",
    val distance: Float = 0f,
    val direction: String = "CENTER"
)

/**
 * User-configurable alert settings.
 */
data class AlertConfig(
    val sensitivity: Float = 0.5f,
    val mode: SceneMode = SceneMode.OUTDOOR,
    val cooldownMs: Long = 3000L,
    val serverUrl: String = "http://10.0.2.2:8000"
)

/**
 * Scene mode affects AI detection priorities and alert behavior.
 */
enum class SceneMode {
    INDOOR,
    OUTDOOR
}
