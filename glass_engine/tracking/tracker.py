"""Tracker integration and velocity estimation.

Takes detections with IDs assigned by the YOLOv8 tracker (e.g. BoT-SORT)
and computes velocity based on distance changes over time.
"""

from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass, field
import numpy as np

from glass_engine.models import Detection


@dataclass
class TrackState:
    """State for a single tracked object to compute velocity."""
    id: int
    label: str
    misses: int = 0         # number of frames not seen
    history: deque = field(default_factory=lambda: deque(maxlen=5))  # (timestamp, distance) samples

    def update(self, det: Detection, now: float) -> None:
        """Update track with a new detection."""
        self.misses = 0
        self.history.append((now, det.distance))

    def predict_velocity(self) -> float:
        """Compute velocity in m/s using linear regression over history.

        Returns:
            Velocity in m/s. Negative = approaching, Positive = moving away.
        """
        if len(self.history) < 2:
            return 0.0

        # Extract arrays
        times = np.array([t for t, _ in self.history])
        dists = np.array([d for _, d in self.history])

        # Simple linear regression: distance = slope * time + intercept
        # slope is velocity (m/s)
        try:
            slope, _ = np.polyfit(times, dists, 1)
            return float(slope)
        except np.linalg.LinAlgError:
            return 0.0


class ObjectTracker:
    """Computes velocity for detections tracked by the underlying model."""

    def __init__(self, max_age: int = 3):
        self.max_age = max_age
        # We need a fallback ID for detections that the model hasn't assigned an ID to yet
        self.next_fallback_id = 10000 
        self.tracks: dict[int, TrackState] = {}

    def update(self, detections: list[Detection]) -> list[Detection]:
        """Update track states and compute velocity for new detections.

        Args:
            detections: List of detections from the current frame. They
                        should already have `id` populated by the detector.

        Returns:
            The same list of detections, with `velocity` and `approaching`
            computed based on track history.
        """
        now = time.time()
        seen_ids = set()

        for det in detections:
            # If the model didn't assign an ID (e.g. low confidence track), assign a temporary one
            if det.id is None:
                det.id = self.next_fallback_id
                self.next_fallback_id += 1
                
            track_id = det.id
            seen_ids.add(track_id)

            if track_id not in self.tracks:
                self.tracks[track_id] = TrackState(id=track_id, label=det.label)
                
            track = self.tracks[track_id]
            track.update(det, now)

            # Populate detection fields derived from tracking
            det.velocity = track.predict_velocity()
            det.approaching = det.velocity < -0.5  # threshold: moving closer faster than 0.5 m/s

        # Handle lost tracks
        # Remove tracks that haven't been seen for `max_age` frames
        lost_ids = []
        for track_id, track in self.tracks.items():
             if track_id not in seen_ids:
                 track.misses += 1
             
             if track.misses > self.max_age:
                 lost_ids.append(track_id)
        
        for track_id in lost_ids:
            del self.tracks[track_id]

        return detections
