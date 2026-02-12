"""Data models for the GlassInterface AI engine pipeline."""

from __future__ import annotations

import time
from dataclasses import dataclass, field, asdict
from typing import Optional
import json


@dataclass
class Detection:
    """A single detected object from the object detector.

    Attributes:
        label:      COCO class name (e.g. "person", "car").
        confidence: Detection confidence score in [0, 1].
        bbox:       Bounding box as (x1, y1, x2, y2) normalized to [0, 1].
        distance:   Estimated distance in metres (filled by DistanceEstimator).
        direction:  Spatial direction — "LEFT", "CENTER", or "RIGHT".
    """

    label: str
    confidence: float
    bbox: tuple[float, float, float, float]
    distance: float = 0.0
    direction: str = "CENTER"

    @property
    def center_x(self) -> float:
        """Horizontal center of the bounding box (normalised)."""
        return (self.bbox[0] + self.bbox[2]) / 2.0

    @property
    def center_y(self) -> float:
        """Vertical center of the bounding box (normalised)."""
        return (self.bbox[1] + self.bbox[3]) / 2.0

    @property
    def width(self) -> float:
        """Width of the bounding box (normalised)."""
        return self.bbox[2] - self.bbox[0]

    @property
    def height(self) -> float:
        """Height of the bounding box (normalised)."""
        return self.bbox[3] - self.bbox[1]

    def to_dict(self) -> dict:
        return {
            "label": self.label,
            "confidence": round(self.confidence, 3),
            "bbox": [round(v, 4) for v in self.bbox],
            "distance": round(self.distance, 2),
            "direction": self.direction,
        }


@dataclass
class Alert:
    """A structured alert message to be spoken or displayed.

    Attributes:
        priority:  "CRITICAL", "WARNING", or "INFO".
        message:   TTS-ready human string (e.g. "Person ahead, 2 metres").
        label:     Object class that triggered this alert.
        distance:  Distance in metres.
        direction: "LEFT", "CENTER", or "RIGHT".
        timestamp: Unix epoch seconds when the alert was generated.
    """

    priority: str
    message: str
    label: str
    distance: float
    direction: str
    timestamp: float = field(default_factory=time.time)

    # Priority ordering for sorting (lower = more urgent).
    _PRIORITY_ORDER = {"CRITICAL": 0, "WARNING": 1, "INFO": 2}

    def urgency(self) -> int:
        """Numeric urgency — lower is more urgent."""
        return self._PRIORITY_ORDER.get(self.priority, 99)

    def to_dict(self) -> dict:
        return {
            "priority": self.priority,
            "message": self.message,
            "label": self.label,
            "distance": round(self.distance, 2),
            "direction": self.direction,
            "timestamp": round(self.timestamp, 3),
        }


@dataclass
class FrameResult:
    """Complete pipeline output for a single processed frame.

    Attributes:
        detections:         All objects detected in the frame.
        alerts:             Filtered, prioritised alerts for the app layer.
        processing_time_ms: End-to-end inference time in milliseconds.
    """

    detections: list[Detection] = field(default_factory=list)
    alerts: list[Alert] = field(default_factory=list)
    processing_time_ms: float = 0.0

    def to_dict(self) -> dict:
        return {
            "processing_time_ms": round(self.processing_time_ms, 2),
            "detections": [d.to_dict() for d in self.detections],
            "alerts": [a.to_dict() for a in self.alerts],
        }

    def to_json(self) -> str:
        """Serialise the full result to a JSON string."""
        return json.dumps(self.to_dict(), indent=2)
