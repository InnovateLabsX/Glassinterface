package com.glassinterface.core.aibridge

import android.graphics.Bitmap
import com.glassinterface.core.common.DetectionResult

/**
 * Contract for the AI inference engine.
 *
 * Your teammate will provide a concrete implementation of this interface.
 * The app codes against this abstraction so the AI engine can be swapped,
 * mocked for tests, or stubbed during development.
 */
interface AIEngine {

    /**
     * One-time initialization: load model weights, allocate GPU/NNAPI buffers, etc.
     * Call on a background thread before the first [process] call.
     */
    suspend fun initialize()

    /**
     * Run inference on a single camera frame.
     *
     * @param frame ARGB_8888 bitmap at the camera's analysis resolution.
     * @return Structured detection result with bounding boxes and an optional alert.
     */
    suspend fun process(frame: Bitmap): DetectionResult

    /**
     * Release all native resources (model, buffers).
     * Safe to call multiple times.
     */
    fun release()
}
