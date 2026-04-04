package com.glassinterface.core.camera

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

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

    private val _frames = MutableSharedFlow<Bitmap>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Flow of the latest camera frames.
     * Collectors will only see the most recent frame.
     */
    val frames: Flow<Bitmap> = _frames.asSharedFlow()

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
                _frames.tryEmit(bitmap)
            } catch (e: Exception) {
                // Log but don't crash on rare conversion failures
                android.util.Log.w("CameraFrameProvider", "Frame conversion failed", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    // ── ESP32-CAM MJPEG / WebSocket Stream ──────────────────────────

    private var mjpegJob: Job? = null
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    // Throttle external streams to ~8 fps so the AI engine is not overwhelmed
    // by burst frames from the ESP32-CAM or similar low-power cameras.
    private val framePeriodMs = 120L
    private var lastExternalFrameMs = 0L

    fun startExternalStream(urlStr: String, scope: CoroutineScope) {
        android.util.Log.d("CameraFrameProvider", "Requested to start stream with URL: $urlStr")
        stopExternalStream()

        var finalUrlStr = urlStr.trim()
        val isWebSocket = finalUrlStr.startsWith("ws://", ignoreCase = true) || finalUrlStr.startsWith("wss://", ignoreCase = true)

        if (!isWebSocket) {
            if (!finalUrlStr.startsWith("http://", ignoreCase = true) && !finalUrlStr.startsWith("https://", ignoreCase = true)) {
                finalUrlStr = "http://$finalUrlStr"
            }
            startHttpMjpegStream(finalUrlStr, scope)
        } else {
            startWebSocketStream(finalUrlStr)
        }
    }

    private fun startWebSocketStream(urlStr: String) {
        android.util.Log.d("CameraFrameProvider", "Attempting WebSocket connection to URL: $urlStr")
        val request = Request.Builder().url(urlStr).build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            var framesRead = 0
            
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                android.util.Log.d("CameraFrameProvider", "WebSocket connection opened!")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastExternalFrameMs < framePeriodMs) return

                    val byteArray = bytes.toByteArray()
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size, options)
                    if (bitmap != null) {
                        lastExternalFrameMs = now
                        framesRead++
                        _frames.tryEmit(bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CameraFrameProvider", "Error processing WebSocket frame", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastExternalFrameMs < framePeriodMs) return

                    val byteArray = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size, options)
                    if (bitmap != null) {
                        lastExternalFrameMs = now
                        framesRead++
                        _frames.tryEmit(bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CameraFrameProvider", "Error processing Text WebSocket frame", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                android.util.Log.e("CameraFrameProvider", "WebSocket failure", t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.d("CameraFrameProvider", "WebSocket closed: $reason")
            }
        })
    }

    private fun startHttpMjpegStream(finalUrlStr: String, scope: CoroutineScope) {
        android.util.Log.d("CameraFrameProvider", "Attempting HTTP connection to URL: $finalUrlStr")
        mjpegJob = scope.launch(Dispatchers.IO) {
            try {
                val url = URL(finalUrlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()

                if (connection.responseCode == 200) {
                    android.util.Log.d("CameraFrameProvider", "Connection successful! Starting MJPEG read loop.")
                    val mjpegStream = MjpegInputStream(connection.inputStream)
                    var framesRead = 0
                    while (isActive) {
                        try {
                            val now = System.currentTimeMillis()
                            // Skip reading/decoding the frame if it's too fast for our gate
                            if (now - lastExternalFrameMs < framePeriodMs) {
                                // We still need to clear the stream, but mjpegStream.readMjpegFrame is what reads it.
                                // It might be better to just read it and drop it, OR clear the buffer.
                                // For now, let's just let it read but skip emission.
                            }

                            val bitmap = mjpegStream.readMjpegFrame()
                            if (bitmap != null) {
                                val finish = System.currentTimeMillis()
                                framesRead++
                                if (finish - lastExternalFrameMs >= framePeriodMs) {
                                    lastExternalFrameMs = finish
                                    _frames.tryEmit(bitmap)
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CameraFrameProvider", "Error reading frame in loop", e)
                            break
                        }
                    }
                    android.util.Log.d("CameraFrameProvider", "Stream loop exited. isActive: $isActive")
                } else {
                    android.util.Log.e("CameraFrameProvider", "HTTP Error: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                android.util.Log.e("CameraFrameProvider", "MJPEG Stream connection error", e)
            }
        }
    }

    fun stopExternalStream() {
        mjpegJob?.cancel()
        mjpegJob = null
        webSocket?.cancel()
        webSocket = null
    }
}
