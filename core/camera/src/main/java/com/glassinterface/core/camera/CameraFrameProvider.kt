package com.glassinterface.core.camera

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a stream of camera frames as [Bitmap]s.
 *
 * Uses a [CONFLATED] channel so only the latest frame is kept — older frames
 * are silently dropped if the AI inference is slower than the camera FPS.
 * This prevents backpressure from blocking the camera thread.
 */
@Singleton
class CameraFrameProvider @Inject constructor() {

    private val frameChannel = Channel<Bitmap>(CONFLATED)

    /**
     * Flow of the latest camera frames.
     * Collectors will only see the most recent frame; stale frames are dropped.
     */
    val frames: Flow<Bitmap> = frameChannel.receiveAsFlow()

    /**
     * Returns an [ImageAnalysis.Analyzer] that converts each frame to a [Bitmap]
     * and sends it into the frame channel.
     *
     * Attach this to CameraX's [ImageAnalysis] use case.
     */
    fun createAnalyzer(): ImageAnalysis.Analyzer {
        return FrameAnalyzer()
    }

    private inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            try {
                val bitmap = ImageProxyToBitmapConverter.convert(imageProxy)
                frameChannel.trySend(bitmap)
            } catch (e: Exception) {
                // Log but don't crash on rare conversion failures
                android.util.Log.w("CameraFrameProvider", "Frame conversion failed", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    // ── ESP32-CAM MJPEG Stream ──────────────────────────────────────

    private var mjpegJob: Job? = null

    fun startExternalStream(urlStr: String, scope: CoroutineScope) {
        stopExternalStream()
        mjpegJob = scope.launch(Dispatchers.IO) {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()

                if (connection.responseCode == 200) {
                    val mjpegStream = MjpegInputStream(connection.inputStream)
                    while (isActive) {
                        val bitmap = mjpegStream.readMjpegFrame()
                        if (bitmap != null) {
                            frameChannel.trySend(bitmap)
                        }
                    }
                } else {
                    android.util.Log.e("CameraFrameProvider", "HTTP Error: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                android.util.Log.e("CameraFrameProvider", "MJPEG Stream error", e)
            }
        }
    }

    fun stopExternalStream() {
        mjpegJob?.cancel()
        mjpegJob = null
    }
}
