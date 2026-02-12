package com.glassinterface.core.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import com.glassinterface.core.common.BoundingBox

/**
 * Compose Canvas overlay that draws labeled bounding boxes on top of the camera preview.
 * Now shows distance and direction info from the AI engine.
 *
 * @param boxes List of bounding boxes with normalized coordinates [0.0, 1.0].
 * @param modifier Modifier for sizing — typically [Modifier.fillMaxSize] to cover the preview.
 */
@Composable
fun BoundingBoxOverlay(
    boxes: List<BoundingBox>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        boxes.forEach { box ->
            // Color by risk level
            val color = when {
                box.riskScore >= 0.7f -> Color(0xFFFF1744) // Critical — red
                box.riskScore >= 0.4f -> Color(0xFFFF6D00) // Warning — orange
                box.riskScore >= 0.15f -> Color(0xFFFFEA00) // Info — yellow
                else -> Color(0xFF00E676) // Safe — green
            }
            val strokeWidth = if (box.riskScore >= 0.7f) 6f else 4f

            // Convert normalized [0,1] coordinates to canvas pixels
            val left = box.rect.left * canvasWidth
            val top = box.rect.top * canvasHeight
            val width = (box.rect.right - box.rect.left) * canvasWidth
            val height = (box.rect.bottom - box.rect.top) * canvasHeight

            // Draw bounding box
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(width = strokeWidth)
            )

            // Draw label background
            val labelHeight = if (box.distance > 0) 68f else 48f
            drawRect(
                color = color.copy(alpha = 0.35f),
                topLeft = Offset(left, top - labelHeight),
                size = Size(width.coerceAtLeast(160f), labelHeight)
            )

            // Draw text labels
            drawContext.canvas.nativeCanvas.apply {
                val labelPaint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.WHITE
                    textSize = 14.sp.toPx()
                    isAntiAlias = true
                    isFakeBoldText = true
                    setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
                }

                // Line 1: Label + confidence
                val label = "${box.label} ${(box.confidence * 100).toInt()}%"
                drawText(label, left + 6f, top - labelHeight + 20f, labelPaint)

                // Line 2: Distance + direction (if available from AI engine)
                if (box.distance > 0) {
                    val distPaint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.WHITE
                        textSize = 12.sp.toPx()
                        isAntiAlias = true
                        setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
                    }
                    val distLabel = "${"%.1f".format(box.distance)}m ${box.direction}" +
                        if (box.approaching) " ⚠ approaching" else ""
                    drawText(distLabel, left + 6f, top - labelHeight + 44f, distPaint)
                }
            }
        }
    }
}
