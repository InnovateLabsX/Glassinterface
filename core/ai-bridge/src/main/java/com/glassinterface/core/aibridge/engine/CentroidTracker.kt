package com.glassinterface.core.aibridge.engine

import android.graphics.RectF
import com.glassinterface.core.common.BoundingBox
import kotlin.math.sqrt

/**
 * Lightweight centroid-based object tracker for frame-to-frame ID persistence.
 *
 * How it works:
 * 1. Each detection is assigned a unique ID based on centroid proximity to previously tracked objects.
 * 2. If a new detection is within [MAX_DISTANCE] of a previous centroid with the same label, it's matched.
 * 3. Velocity is estimated from bounding-box height change across frames (height ↑ = approaching).
 * 4. Objects not seen for [maxDisappeared] frames are deregistered.
 *
 * This is critical for blind navigation: without tracking, the app cannot determine
 * whether a car is approaching or stationary.
 */
class CentroidTracker(
    private val maxDisappeared: Int = 15,
    private val maxDistance: Float = 0.15f // Normalized [0..1] coordinate space
) {
    private var nextObjectId = 0
    private val objects = mutableMapOf<Int, TrackedObject>()

    data class TrackedObject(
        val id: Int,
        val label: String,
        var centroidX: Float,
        var centroidY: Float,
        var bboxHeight: Float,
        var prevBboxHeight: Float = 0f,
        var disappeared: Int = 0,
        var frameCount: Int = 0
    ) {
        /**
         * Velocity estimated from bbox height change.
         * Positive = getting larger = approaching.
         * Returns m/s approximation based on height growth rate.
         */
        val velocity: Float
            get() {
                if (prevBboxHeight <= 0f || frameCount < 2) return 0f
                val heightDelta = bboxHeight - prevBboxHeight
                // Normalize: a large positive delta means fast approach
                return -(heightDelta / prevBboxHeight) * 5f // Scale factor for intuitive m/s range
            }

        val approaching: Boolean
            get() = velocity < -0.3f // Negative velocity = approaching in our convention
    }

    /**
     * Update tracker with a new frame's detections.
     * Returns a new list of [BoundingBox] enriched with [trackingId], [velocity], and [approaching].
     */
    fun update(detections: List<BoundingBox>): List<BoundingBox> {
        if (detections.isEmpty()) {
            // Mark all objects as disappeared
            val toRemove = mutableListOf<Int>()
            objects.forEach { (id, obj) ->
                obj.disappeared++
                if (obj.disappeared > maxDisappeared) {
                    toRemove.add(id)
                }
            }
            toRemove.forEach { objects.remove(it) }
            return emptyList()
        }

        // Compute centroids for current detections
        val inputCentroids = detections.map { det ->
            val cx = (det.rect.left + det.rect.right) / 2f
            val cy = (det.rect.top + det.rect.bottom) / 2f
            val height = det.rect.bottom - det.rect.top
            Triple(cx, cy, height)
        }

        // If no existing tracked objects, register all new
        if (objects.isEmpty()) {
            return detections.mapIndexed { i, det ->
                val (cx, cy, height) = inputCentroids[i]
                val id = register(det.label, cx, cy, height)
                det.copy(trackingId = id)
            }
        }

        // Match detections to existing objects using centroid distance
        val objectIds = objects.keys.toList()
        val trackedList = objects.values.toList()

        // Build cost matrix: distance between each tracked object and each detection
        val usedDetections = BooleanArray(detections.size)
        val usedObjects = BooleanArray(objectIds.size)
        val matches = mutableListOf<Pair<Int, Int>>() // (objectIndex, detectionIndex)

        // Greedy matching: find closest pairs
        data class Match(val objIdx: Int, val detIdx: Int, val distance: Float)
        val allPairs = mutableListOf<Match>()

        for (oi in trackedList.indices) {
            for (di in detections.indices) {
                // Only match same label
                if (trackedList[oi].label != detections[di].label) continue
                val dx = trackedList[oi].centroidX - inputCentroids[di].first
                val dy = trackedList[oi].centroidY - inputCentroids[di].second
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < maxDistance) {
                    allPairs.add(Match(oi, di, dist))
                }
            }
        }

        // Sort by distance and greedily assign
        allPairs.sortBy { it.distance }
        for (match in allPairs) {
            if (usedObjects[match.objIdx] || usedDetections[match.detIdx]) continue
            usedObjects[match.objIdx] = true
            usedDetections[match.detIdx] = true
            matches.add(match.objIdx to match.detIdx)
        }

        // Build result
        val result = MutableList<BoundingBox?>(detections.size) { null }

        // Update matched objects
        for ((objIdx, detIdx) in matches) {
            val obj = trackedList[objIdx]
            val (cx, cy, height) = inputCentroids[detIdx]
            obj.prevBboxHeight = obj.bboxHeight
            obj.centroidX = cx
            obj.centroidY = cy
            obj.bboxHeight = height
            obj.disappeared = 0
            obj.frameCount++

            result[detIdx] = detections[detIdx].copy(
                trackingId = obj.id,
                velocity = obj.velocity,
                approaching = obj.approaching
            )
        }

        // Increment disappeared count for unmatched objects
        for (oi in trackedList.indices) {
            if (!usedObjects[oi]) {
                val obj = trackedList[oi]
                obj.disappeared++
                if (obj.disappeared > maxDisappeared) {
                    objects.remove(obj.id)
                }
            }
        }

        // Register new objects for unmatched detections
        for (di in detections.indices) {
            if (!usedDetections[di]) {
                val (cx, cy, height) = inputCentroids[di]
                val id = register(detections[di].label, cx, cy, height)
                result[di] = detections[di].copy(trackingId = id)
            }
        }

        return result.filterNotNull()
    }

    private fun register(label: String, cx: Float, cy: Float, height: Float): Int {
        val id = nextObjectId++
        objects[id] = TrackedObject(
            id = id,
            label = label,
            centroidX = cx,
            centroidY = cy,
            bboxHeight = height
        )
        return id
    }
}
