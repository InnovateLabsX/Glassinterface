package com.glassinterface.core.aibridge.engine

import com.glassinterface.core.common.Alert
import com.glassinterface.core.common.BoundingBox
import com.glassinterface.core.common.SceneMode
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Computes continuous risk scores and generates prioritised alerts.
 *
 * Transliterated from `scorer.py` to allow fully native on-device Android inference.
 */
class RiskScorer(
    private var sceneMode: SceneMode = SceneMode.OUTDOOR
) {
    companion object {
        private const val MAX_RISK_RANGE = 8.0f // metres

        // Thresholds
        private const val RISK_INFO = 0.15f
        private const val RISK_WARNING = 0.40f
        private const val RISK_CRITICAL = 0.80f

        // Labels that demand higher attention
        private val SAFETY_LABELS = setOf(
            "person", "car", "bicycle", "motorcycle", "bus", "truck", "stairs"
        )
    }

    fun setSceneMode(mode: SceneMode) {
        sceneMode = mode
    }

    fun score(detections: List<BoundingBox>): List<Alert> {
        val now = System.currentTimeMillis()
        val alerts = mutableListOf<Alert>()

        for (det in detections) {
            val risk = computeRisk(det)
            
            val priority = riskToPriority(risk) ?: continue // Suppressed

            val alert = makeAlert(priority, det, risk, now)
            alerts.add(alert)
        }

        // Sort descending
        alerts.sortByDescending { it.riskScore }
        return alerts
    }

    private fun computeRisk(det: BoundingBox): Float {
        // Factor 1: Distance
        val distanceFactor = max(0.0f, min(1.0f, 1.0f - det.distance / MAX_RISK_RANGE))

        // Factor 2: Velocity
        val velocityBoost = max(0.0f, min(1.0f, -det.velocity / 2.0f))
        val velocityFactor = 1.0f + velocityBoost

        // Factor 3: Priority
        val objectPriority = if (det.label in SAFETY_LABELS) 1.0f else 0.5f

        // Factor 4: Context
        val contextWeight = getContextWeight(sceneMode, det.label)

        return distanceFactor * velocityFactor * objectPriority * contextWeight
    }

    private fun getContextWeight(mode: SceneMode, label: String): Float {
        return when (mode) {
            SceneMode.INDOOR -> {
                when (label) {
                    "chair", "laptop", "cup", "bed", "tv", "cell phone" -> 1.0f
                    "car", "truck", "bus", "bus" -> 0.1f
                    else -> 0.8f
                }
            }
            SceneMode.OUTDOOR -> {
                when (label) {
                    "car", "truck", "bus", "motorcycle", "bicycle" -> 1.5f // Major boost
                    "chair", "bed", "tv", "laptop" -> 0.1f // Very unlikely, probably a false positive
                    else -> 1.0f
                }
            }
        }
    }

    private fun riskToPriority(risk: Float): String? {
        if (risk >= RISK_CRITICAL) return "CRITICAL"
        if (risk >= RISK_WARNING) return "WARNING"
        if (risk >= RISK_INFO) return "INFO"
        return null
    }

    private fun makeAlert(priority: String, det: BoundingBox, risk: Float, now: Long): Alert {
        val labelCap = det.label.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        val directionText = directionText(det.direction)

        var msg = when (priority) {
            "CRITICAL" -> {
                if (det.approaching) {
                    "$labelCap approaching fast $directionText, ${"%.1f".format(det.distance)} metres!"
                } else {
                    "$labelCap very close $directionText, ${"%.1f".format(det.distance)} metres!"
                }
            }
            "WARNING" -> "$labelCap $directionText, about ${"%.1f".format(det.distance)} metres."
            else -> "$labelCap nearby $directionText, ${"%.1f".format(det.distance)} metres."
        }

        if (priority == "CRITICAL" || priority == "WARNING") {
            val hint = getNavigationHint(det, risk)
            msg = "$msg $hint"
        }

        return Alert(
            priority = priority,
            message = msg,
            label = det.label,
            distance = det.distance,
            direction = det.direction,
            riskScore = risk
        )
    }

    private fun directionText(direction: String): String {
        return when (direction) {
            "LEFT" -> "to your left"
            "RIGHT" -> "to your right"
            "CENTER" -> "ahead"
            else -> "nearby"
        }
    }

    private fun getNavigationHint(det: BoundingBox, risk: Float): String {
        if (!det.approaching && det.distance > 3.0f) {
            return "Proceed with caution."
        }

        return when (det.direction) {
            "CENTER" -> {
                if (risk > 1.0f) "Hold." else "Move right."
            }
            "LEFT" -> "Move right."
            "RIGHT" -> "Move left."
            else -> "Hold."
        }
    }
}
