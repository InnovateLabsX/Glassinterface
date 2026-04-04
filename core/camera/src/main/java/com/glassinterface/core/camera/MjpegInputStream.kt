package com.glassinterface.core.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Parses a continuous MJPEG (Motion JPEG) HTTP stream into discrete Bitmap frames.
 * Highly optimized chunk-based parser using PushbackInputStream to avoid byte-by-byte lock overhead.
 */
class MjpegInputStream(inputStream: InputStream) {

    private val pushbackStream = PushbackInputStream(BufferedInputStream(inputStream, 65536), 8192)
    private val buffer = ByteArray(4096)
    private val frameBuffer = ByteArrayOutputStream(250000)

    @Throws(IOException::class)
    fun readMjpegFrame(): Bitmap? {
        frameBuffer.reset()
        var foundSOI = false
        var lastByte = -1

        // 1. Fast forward to FF D8 (Start Of Image)
        while (!foundSOI) {
            val count = pushbackStream.read(buffer)
            if (count == -1) return null
            
            var soiIndex = -1
            for (i in 0 until count) {
                val b = buffer[i].toInt() and 0xFF
                if (lastByte == 0xFF && b == 0xD8) {
                    soiIndex = i
                    break
                }
                lastByte = b
            }
            
            if (soiIndex != -1) {
                foundSOI = true
                frameBuffer.write(0xFF)
                frameBuffer.write(0xD8)
                
                val unreadCount = count - 1 - soiIndex
                if (unreadCount > 0) {
                    pushbackStream.unread(buffer, soiIndex + 1, unreadCount)
                }
            }
        }

        // 2. Read chunked until FF D9 (End Of Image)
        var bytesRead = 0
        lastByte = -1
        while (true) {
            val count = pushbackStream.read(buffer)
            if (count == -1) break

            var eofIndex = -1
            for (i in 0 until count) {
                val b = buffer[i].toInt() and 0xFF
                if (lastByte == 0xFF && b == 0xD9) {
                    eofIndex = i
                    break
                }
                lastByte = b
            }

            if (eofIndex != -1) {
                // Found FF D9
                frameBuffer.write(buffer, 0, eofIndex + 1)
                
                // Push back the overshoot bytes so next frame doesn't lose them
                val unreadCount = count - 1 - eofIndex
                if (unreadCount > 0) {
                    pushbackStream.unread(buffer, eofIndex + 1, unreadCount)
                }
                break
            } else {
                frameBuffer.write(buffer, 0, count)
            }
            
            bytesRead += count
            if (bytesRead > 1000000) {
                // Failsafe, frame too big
                return null
            }
        }

        val frameData = frameBuffer.toByteArray()
        return BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
    }
}
