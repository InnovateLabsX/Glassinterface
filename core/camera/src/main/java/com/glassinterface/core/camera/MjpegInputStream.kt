package com.glassinterface.core.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Properties

/**
 * Parses a continuous MJPEG (Motion JPEG) HTTP stream into discrete Bitmap frames.
 * Ideal for streaming video from an ESP32-CAM over local Wi-Fi.
 */
class MjpegInputStream(inputStream: InputStream) : DataInputStream(BufferedInputStream(inputStream, HEADER_MAX_LENGTH)) {

    companion object {
        private val SOI_MARKER = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private val EOF_MARKER = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        private const val CONTENT_LENGTH = "Content-Length"
        private const val HEADER_MAX_LENGTH = 100000
        private const val FRAME_MAX_LENGTH = 100000 + HEADER_MAX_LENGTH
    }

    private var contentLen = -1

    @Throws(IOException::class)
    fun readMjpegFrame(): Bitmap? {
        mark(FRAME_MAX_LENGTH)
        val headerLen = getStartOfSequence(this, SOI_MARKER)
        reset()

        val header = ByteArray(headerLen)
        readFully(header)

        try {
            contentLen = parseContentLength(header)
            if (contentLen <= 0) {
                contentLen = getEndOfSequence(this, EOF_MARKER)
            }
        } catch (nfe: NumberFormatException) {
            contentLen = getEndOfSequence(this, EOF_MARKER)
        }

        reset()

        if (contentLen <= 0) {
            // Unlikely or stream corrupt, skip and return null to not crash
            skipBytes(headerLen)
            return null
        }

        val frameData = ByteArray(contentLen)
        skipBytes(headerLen)
        readFully(frameData)

        return BitmapFactory.decodeStream(ByteArrayInputStream(frameData))
    }

    @Throws(IOException::class)
    private fun getStartOfSequence(inputStream: DataInputStream, sequence: ByteArray): Int {
        var len = 0
        var matchIndex = 0
        while (true) {
            val b = inputStream.readByte()
            len++
            if (b == sequence[matchIndex]) {
                matchIndex++
                if (matchIndex == sequence.size) {
                    return len
                }
            } else {
                matchIndex = if (b == sequence[0]) 1 else 0
            }
            if (len >= FRAME_MAX_LENGTH) return len // Fallback
        }
    }

    @Throws(IOException::class)
    private fun getEndOfSequence(inputStream: DataInputStream, sequence: ByteArray): Int {
        var len = 0
        var matchIndex = 0
        while (true) {
            val b = inputStream.readByte()
            len++
            if (b == sequence[matchIndex]) {
                matchIndex++
                if (matchIndex == sequence.size) {
                    return len
                }
            } else {
                matchIndex = if (b == sequence[0]) 1 else 0
            }
            if (len >= FRAME_MAX_LENGTH) return -1
        }
    }

    @Throws(IOException::class, NumberFormatException::class)
    private fun parseContentLength(headerBytes: ByteArray): Int {
        val headerString = ByteArrayInputStream(headerBytes).bufferedReader().readText()
        val lines = headerString.split("\n", "\r\n")
        for (line in lines) {
            if (line.startsWith(CONTENT_LENGTH, ignoreCase = true)) {
                val parts = line.split(":")
                if (parts.size == 2) {
                    return parts[1].trim().toInt()
                }
            }
        }
        return 0
    }
}
