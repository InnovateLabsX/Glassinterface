package com.glassinterface.core.aibridge

import android.graphics.Bitmap
import android.graphics.RectF
import com.glassinterface.core.common.Alert
import com.glassinterface.core.common.BoundingBox
import com.glassinterface.core.common.DetectionResult
import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.random.Random

/**
 * Fake AI engine that returns random bounding boxes after a simulated delay.
 *
 * Use this during development to build and test the full camera → overlay → TTS
 * pipeline without waiting for the real AI model.
 */
class FakeAIEngine @Inject constructor() : AIEngine {

    private val labels = listOf(
        "person", "chair", "table", "door", "stairs",
        "car", "bicycle", "dog", "wall", "curb"
    )

    override suspend fun initialize() {
        // Simulate model loading time
        delay(500)
    }

    override suspend fun process(frame: Bitmap): DetectionResult {
        // Simulate inference latency (50–150ms)
        delay(Random.nextLong(50, 150))

        val numBoxes = Random.nextInt(0, 4)
        val boxes = (0 until numBoxes).map { generateRandomBox() }

        // Generate alert ~30% of the time
        val alerts = if (Random.nextFloat() < 0.3f && boxes.isNotEmpty()) {
            val box = boxes.first()
            listOf(
                Alert(
                    priority = "WARNING",
                    message = "Caution: ${box.label} detected nearby",
                    label = box.label,
                    distance = box.distance,
                    direction = box.direction
                )
            )
        } else {
            emptyList()
        }

        return DetectionResult(
            boxes = boxes,
            alerts = alerts
        )
    }

    override fun release() {
        // Nothing to release in the fake engine
    }

    private fun generateRandomBox(): BoundingBox {
        val left = Random.nextFloat() * 0.6f
        val top = Random.nextFloat() * 0.6f
        val width = Random.nextFloat() * 0.3f + 0.05f
        val height = Random.nextFloat() * 0.3f + 0.05f
        val distance = Random.nextFloat() * 5f + 0.5f

        return BoundingBox(
            label = labels.random(),
            confidence = Random.nextFloat() * 0.5f + 0.5f, // 0.5–1.0
            rect = RectF(left, top, left + width, top + height),
            distance = distance,
            direction = listOf("LEFT", "CENTER", "RIGHT").random(),
            riskScore = Random.nextFloat()
        )
    }
}
