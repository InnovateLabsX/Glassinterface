package com.glassinterface.core.aibridge.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Face detection + lightweight embedding via ML Kit.
 *
 * Uses face landmarks (eyes, nose, mouth, cheeks, ears) as a compact
 * embedding vector for re-identification. Runs entirely on-device.
 */
class FaceRecognitionEngine {

    companion object {
        private const val TAG = "FaceRecognitionEngine"
        const val EMBEDDING_SIZE = 20 // 10 landmarks × 2 (x,y)
    }

    private val detector: FaceDetector

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
        detector = FaceDetection.getClient(options)
    }

    data class FaceResult(
        val boundingBox: Rect,
        val embedding: FloatArray,
        val normalizedBox: android.graphics.RectF
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FaceResult) return false
            return boundingBox == other.boundingBox && embedding.contentEquals(other.embedding)
        }
        override fun hashCode(): Int = 31 * boundingBox.hashCode() + embedding.contentHashCode()
    }

    /**
     * Detect all faces in the given bitmap and return their embeddings.
     * Uses blocking Tasks.await() to bridge ML Kit's Task API — call from a background thread.
     */
    fun detectFaces(bitmap: Bitmap): List<FaceResult> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faces: List<Face> = Tasks.await(detector.process(inputImage))

            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()

            faces.mapNotNull { face: Face ->
                val embedding = extractEmbedding(face)
                if (embedding != null) {
                    val box = face.boundingBox
                    val normalizedBox = android.graphics.RectF(
                        (box.left / width).coerceIn(0f, 1f),
                        (box.top / height).coerceIn(0f, 1f),
                        (box.right / width).coerceIn(0f, 1f),
                        (box.bottom / height).coerceIn(0f, 1f)
                    )
                    FaceResult(
                        boundingBox = box,
                        embedding = embedding,
                        normalizedBox = normalizedBox
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed", e)
            emptyList()
        }
    }

    fun cropFace(bitmap: Bitmap, box: Rect): Bitmap? {
        return try {
            val safeLeft = box.left.coerceIn(0, bitmap.width - 1)
            val safeTop = box.top.coerceIn(0, bitmap.height - 1)
            val safeRight = box.right.coerceIn(safeLeft + 1, bitmap.width)
            val safeBottom = box.bottom.coerceIn(safeTop + 1, bitmap.height)
            Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop face", e)
            null
        }
    }

    private fun extractEmbedding(face: Face): FloatArray? {
        val landmarks = listOf(
            FaceLandmark.LEFT_EYE,
            FaceLandmark.RIGHT_EYE,
            FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_LEFT,
            FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.MOUTH_BOTTOM,
            FaceLandmark.LEFT_CHEEK,
            FaceLandmark.RIGHT_CHEEK,
            FaceLandmark.LEFT_EAR,
            FaceLandmark.RIGHT_EAR
        )

        val box = face.boundingBox
        val cx = box.centerX().toFloat()
        val cy = box.centerY().toFloat()
        val scale = Math.max(box.width(), box.height()).toFloat().coerceAtLeast(1f)

        // Always produce a fixed-size embedding; missing landmarks get 0.0
        val embedding = FloatArray(EMBEDDING_SIZE)
        var foundCount = 0
        for ((idx, type) in landmarks.withIndex()) {
            val lm = face.getLandmark(type)
            if (lm != null) {
                val xi = idx * 2
                val yi = idx * 2 + 1
                if (xi < EMBEDDING_SIZE) embedding[xi] = (lm.position.x - cx) / scale
                if (yi < EMBEDDING_SIZE) embedding[yi] = (lm.position.y - cy) / scale
                foundCount++
            }
        }

        // Need at least 3 landmarks (eyes + nose) for a meaningful embedding
        if (foundCount < 3) return null

        return embedding
    }

    fun release() {
        detector.close()
    }
}
