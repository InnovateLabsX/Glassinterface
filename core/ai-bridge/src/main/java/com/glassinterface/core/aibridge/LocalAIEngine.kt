package com.glassinterface.core.aibridge.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.glassinterface.core.aibridge.AIEngine
import com.glassinterface.core.common.BoundingBox
import com.glassinterface.core.common.DetectionResult
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class LocalAIEngine(private val context: Context) : AIEngine {

    companion object {
        private const val TAG = "LocalAIEngine"
        private const val MODEL_PATH = "yolov8s.tflite"
        private const val INPUT_SIZE = 640

        // Global minimum — anything below this is always rejected
        private const val CONFIDENCE_THRESHOLD = 0.42f

        // Per-class stricter minimums to suppress noisy dominant classes.
        // "person" is COCO class 0 and the most over-detected label at low
        // quality; we raise its bar significantly.
        private val CLASS_MIN_CONFIDENCE = mapOf(
            "person"     to 0.58f,
            "chair"      to 0.50f,
            "potted plant" to 0.50f,
            "bottle"     to 0.48f,
            "cup"        to 0.48f
        )
    }

    private var interpreter: Interpreter? = null
    private var enhancedBitmap: Bitmap? = null
    private var enhancedCanvas: Canvas? = null
    private val enhancementPaint = Paint()
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()
    private val distanceEstimator = DistanceEstimator()
    private val centroidTracker = CentroidTracker()
    private val riskScorer = RiskScorer()

    // Assuming COCO labels
    private val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
        "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
        "hair drier", "toothbrush"
    )

    override suspend fun initialize() {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_PATH)
            val options = Interpreter.Options()
            options.numThreads = 4
            interpreter = Interpreter(model, options)
            Log.i(TAG, "TFLite Model loaded successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TFLite model", e)
        }
    }

    override suspend fun process(frame: Bitmap): DetectionResult {
        val startMs = System.currentTimeMillis()

        if (interpreter == null) {
            return DetectionResult(emptyList())
        }

        // 1. Enhance low-quality / ESP32-CAM frames.
        val prepStart = System.currentTimeMillis()
        val enhancedFrame = enhanceFrame(frame)

        // 2. Preprocess using TensorImage
        val inputTensor = interpreter?.getInputTensor(0)
        val inputDataType = inputTensor?.dataType() ?: DataType.FLOAT32

        var tensorImage = TensorImage(inputDataType)
        tensorImage.load(enhancedFrame)
        tensorImage = imageProcessor.process(tensorImage)
        val prepTime = System.currentTimeMillis() - prepStart

        // 3. Output Buffer Allocation
        val inferStart = System.currentTimeMillis()
        val outputTensor = interpreter?.getOutputTensor(0)
        val outputShape = outputTensor?.shape() ?: intArrayOf(1, 84, 8400)
        val outputDataType = outputTensor?.dataType() ?: DataType.FLOAT32
        val outputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType)

        // 4. Inference
        try {
            interpreter?.run(tensorImage.buffer, outputBuffer.buffer)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            return DetectionResult(emptyList())
        }
        val inferTime = System.currentTimeMillis() - inferStart

        // 5. Post-process
        val postStart = System.currentTimeMillis()
        val rawBoxes = extractBoxes(outputBuffer.floatArray, frame.width, frame.height)
        val enrichedBoxes = distanceEstimator.estimate(rawBoxes, frame.height)
        val trackedBoxes = centroidTracker.update(enrichedBoxes)
        val alerts = riskScorer.score(trackedBoxes)
        val postTime = System.currentTimeMillis() - postStart

        val totalMs = System.currentTimeMillis() - startMs
        if (totalMs > 10) {
            Log.d(TAG, "Frame stats: total=${totalMs}ms (prep=${prepTime}ms, infer=${inferTime}ms, post=${postTime}ms)")
        }

        return DetectionResult(
            boxes = trackedBoxes,
            alerts = alerts,
            processingTimeMs = totalMs.toFloat()
        )
    }

    private fun extractBoxes(output: FloatArray, origW: Int, origH: Int): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        val numClasses = 80
        val numBoxes = 8400

        for (i in 0 until numBoxes) {
            var maxClassScore = 0f
            var classIndex = -1

            for (c in 0 until numClasses) {
                val score = output[(c + 4) * numBoxes + i]
                if (score > maxClassScore) {
                    maxClassScore = score
                    classIndex = c
                }
            }

            val label = if (classIndex >= 0 && classIndex < labels.size) labels[classIndex] else null
            val effectiveThreshold = label?.let { CLASS_MIN_CONFIDENCE[it] } ?: CONFIDENCE_THRESHOLD
            if (maxClassScore > effectiveThreshold && label != null) {
                val cx = output[0 * numBoxes + i]
                val cy = output[1 * numBoxes + i]
                val w = output[2 * numBoxes + i]
                val h = output[3 * numBoxes + i]

                // TFLite models may output pixel coords [0..640] or normalized [0..1]
                val isPixelCoords = cx > 1.5f || cy > 1.5f || w > 1.5f || h > 1.5f
                val normCx = if (isPixelCoords) cx / INPUT_SIZE else cx
                val normCy = if (isPixelCoords) cy / INPUT_SIZE else cy
                val normW = if (isPixelCoords) w / INPUT_SIZE else w
                val normH = if (isPixelCoords) h / INPUT_SIZE else h

                val left = Math.max(0f, normCx - normW / 2)
                val top = Math.max(0f, normCy - normH / 2)
                val right = Math.min(1f, normCx + normW / 2)
                val bottom = Math.min(1f, normCy + normH / 2)

                boxes.add(
                    BoundingBox(
                        label = label,
                        confidence = maxClassScore,
                        rect = RectF(left, top, right, bottom)
                    )
                )
            }
        }
        return applyNMS(boxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): List<BoundingBox> {
        val nmsThreshold = 0.5f
        val sortedBoxes = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val current = sortedBoxes.removeAt(0)
            selectedBoxes.add(current)
            sortedBoxes.removeAll { box ->
                box.label == current.label && iou(current.rect, box.rect) > nmsThreshold
            }
        }
        return selectedBoxes
    }

    private fun iou(box1: RectF, box2: RectF): Float {
        val interLeft = Math.max(box1.left, box2.left)
        val interTop = Math.max(box1.top, box2.top)
        val interRight = Math.min(box1.right, box2.right)
        val interBottom = Math.min(box1.bottom, box2.bottom)

        if (interRight < interLeft || interBottom < interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)

        return interArea / (box1Area + box2Area - interArea)
    }

    override fun release() {
        interpreter?.close()
        interpreter = null
        enhancedBitmap?.recycle()
        enhancedBitmap = null
        enhancedCanvas = null
    }

    /**
     * Applies a contrast + brightness boost useful for low-quality JPEG frames
     * from devices like the ESP32-CAM (which produce washed-out, compressed images).
     *
     * Uses a ColorMatrix to perform the enhancement entirely on the GPU-side
     * Canvas without allocating extra pixel byte arrays.
     *
     * contrast = 1.25  — punches up edges/textures
     * brightness = +8  — compensates for underexposed streams
     */
    private fun enhanceFrame(src: Bitmap): Bitmap {
        val contrast = 1.25f
        val brightness = 8f

        // Re-use bitmap if dimensions match
        if (enhancedBitmap == null || enhancedBitmap?.width != src.width || enhancedBitmap?.height != src.height) {
            enhancedBitmap?.recycle()
            enhancedBitmap = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            enhancedCanvas = Canvas(enhancedBitmap!!)
            
            val translate = (-(255 * (contrast - 1)) / 2) + brightness
            enhancementPaint.colorFilter = ColorMatrixColorFilter(
                ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
            )
        }

        enhancedCanvas?.drawBitmap(src, 0f, 0f, enhancementPaint)
        return enhancedBitmap!!
    }
}
