"""Centralised configuration for the GlassInterface AI engine.

Every tunable threshold, label list, and model parameter lives here so
the rest of the codebase never contains magic numbers.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path


class ContextMode(Enum):
    """Operating context for risk weight adjustment."""
    INDOOR = "indoor"
    OUTDOOR = "outdoor"
    WALKING = "walking"
    STATIONARY = "stationary"


@dataclass
class GlassConfig:
    """All engine knobs in one place.

    Create a default instance with ``GlassConfig()`` or override any field::

        cfg = GlassConfig(CONFIDENCE_THRESHOLD=0.60, CRITICAL_DISTANCE=2.0)
    """

    # ── Object Detection ─────────────────────────────────────────────
    MODEL_PATH: str = "yolov8s.pt"
    MODEL_INPUT_SIZE: int = 640
    CONFIDENCE_THRESHOLD: float = 0.45
    NMS_IOU_THRESHOLD: float = 0.50
    TRACKER_CONFIG_PATH: str = "custom_tracker.yaml"

    # ── Distance Estimation ──────────────────────────────────────────
    FOCAL_SCALE: float = 400.0  # pseudo-focal length; use 'c' key in demo.py to calibrate
    REFERENCE_HEIGHTS: dict[str, float] = field(default_factory=lambda: {
        # ── People & Animals ──
        "person":     1.70,
        "cat":        0.30,
        "dog":        0.50,
        "horse":      1.60,
        "sheep":      0.80,
        "cow":        1.40,
        "elephant":   3.00,
        "bear":       1.50,
        "zebra":      1.40,
        "giraffe":    5.00,
        "bird":       0.20,
        # ── Vehicles ──
        "car":        1.50,
        "bicycle":    1.10,
        "motorcycle": 1.10,
        "bus":        3.00,
        "truck":      3.50,
        "train":      3.50,
        "boat":       2.00,
        "airplane":   4.00,
        # ── Street objects ──
        "traffic light":  0.60,
        "fire hydrant":   0.50,
        "stop sign":      0.75,
        "parking meter":  1.20,
        "bench":          0.50,
        # ── Indoor furniture ──
        "chair":      0.90,
        "couch":      0.85,
        "bed":        0.60,
        "dining table": 0.75,
        "toilet":     0.45,
        "potted plant": 0.40,
        # ── Electronics ──
        "tv":         0.50,
        "laptop":     0.25,
        "mouse":      0.04,
        "remote":     0.18,
        "keyboard":   0.05,
        "cell phone": 0.14,
        "microwave":  0.35,
        "oven":       0.80,
        "refrigerator": 1.70,
        # ── Kitchen / Food ──
        "bottle":     0.25,
        "wine glass": 0.22,
        "cup":        0.12,
        "fork":       0.18,
        "knife":      0.20,
        "spoon":      0.18,
        "bowl":       0.10,
        "banana":     0.18,
        "apple":      0.08,
        "sandwich":   0.08,
        "orange":     0.08,
        "broccoli":   0.15,
        "carrot":     0.18,
        "hot dog":    0.06,
        "pizza":      0.04,
        "donut":      0.04,
        "cake":       0.12,
        # ── Accessories ──
        "backpack":   0.50,
        "umbrella":   0.90,
        "handbag":    0.35,
        "tie":        0.50,
        "suitcase":   0.60,
        "frisbee":    0.03,
        "skis":       1.70,
        "snowboard":  1.50,
        "sports ball": 0.22,
        "kite":       0.80,
        "baseball bat":  0.80,
        "baseball glove": 0.25,
        "skateboard": 0.10,
        "surfboard":  2.00,
        "tennis racket":  0.68,
        # ── Misc ──
        "book":       0.22,
        "clock":      0.30,
        "vase":       0.30,
        "scissors":   0.18,
        "teddy bear": 0.35,
        "hair drier": 0.25,
        "toothbrush": 0.18,
    })
    CATEGORY_REFERENCE_HEIGHTS: dict[str, float] = field(default_factory=lambda: {
        "person": 1.70,
        "vehicle": 1.50,
        "animal": 0.60,
        "furniture": 0.80,
        "other": 0.30,
    })
    DEFAULT_REFERENCE_HEIGHT: float = 0.30  # fallback for completely unknown labels

    # ── Direction Zones ──────────────────────────────────────────────
    LEFT_BOUNDARY: float = 0.33
    RIGHT_BOUNDARY: float = 0.66

    # ── Reasoning Engine ─────────────────────────────────────────────
    CRITICAL_DISTANCE: float = 1.5   # metres
    WARNING_DISTANCE: float = 3.0
    INFO_DISTANCE: float = 5.0       # show non-safety objects at wider range

    SAFETY_LABELS: set[str] = field(default_factory=lambda: {
        "person", "car", "bicycle", "motorcycle", "bus", "truck", "stairs",
    })

    # ── Alert Manager ────────────────────────────────────────────────
    COOLDOWN_CRITICAL: float = 3.0   # seconds
    COOLDOWN_WARNING: float = 8.0
    COOLDOWN_INFO: float = 10.0
    MAX_ALERTS_PER_FRAME: int = 3

    # ── Risk Scoring (Level 2) ───────────────────────────────────────
    RISK_CRITICAL: float = 0.7       # risk ≥ this → CRITICAL
    RISK_WARNING: float = 0.4        # risk ≥ this → WARNING
    RISK_INFO: float = 0.15          # risk ≥ this → INFO; below → suppress
    MAX_RISK_RANGE: float = 8.0      # distance beyond which risk = 0

    # ── Context Weights ──────────────────────────────────────────────
    # Keys: (ContextMode, label_category) → weight multiplier
    # Categories: "person", "vehicle", "furniture", "animal", "other"
    CONTEXT_WEIGHTS: dict[tuple[str, str], float] = field(default_factory=lambda: {
        # INDOOR
        ("indoor", "person"):    1.0,
        ("indoor", "vehicle"):   0.5,
        ("indoor", "furniture"): 1.2,
        ("indoor", "animal"):    0.8,
        ("indoor", "other"):     0.8,
        # OUTDOOR
        ("outdoor", "person"):    1.2,
        ("outdoor", "vehicle"):   1.5,
        ("outdoor", "furniture"): 0.3,
        ("outdoor", "animal"):    1.2,
        ("outdoor", "other"):     0.8,
        # WALKING
        ("walking", "person"):    1.5,
        ("walking", "vehicle"):   1.5,
        ("walking", "furniture"): 0.8,
        ("walking", "animal"):    1.3,
        ("walking", "other"):     0.8,
        # STATIONARY
        ("stationary", "person"):    0.8,
        ("stationary", "vehicle"):   0.8,
        ("stationary", "furniture"): 1.0,
        ("stationary", "animal"):    0.7,
        ("stationary", "other"):     0.8,
    })

    # ── Hazard Memory ────────────────────────────────────────────────
    HAZARD_MEMORY_FRAMES: int = 10   # frames before demoting priority
    HAZARD_FORGET_FRAMES: int = 3    # frames absent before resetting

    # ── Label Categories ─────────────────────────────────────────────
    LABEL_CATEGORIES: dict[str, str] = field(default_factory=lambda: {
        # Person
        "person": "person",
        # Vehicles
        "car": "vehicle", "bicycle": "vehicle", "motorcycle": "vehicle",
        "bus": "vehicle", "truck": "vehicle", "train": "vehicle",
        "boat": "vehicle", "airplane": "vehicle",
        # Animals
        "cat": "animal", "dog": "animal", "horse": "animal",
        "sheep": "animal", "cow": "animal", "elephant": "animal",
        "bear": "animal", "zebra": "animal", "giraffe": "animal",
        "bird": "animal",
        # Furniture
        "chair": "furniture", "couch": "furniture", "bed": "furniture",
        "dining table": "furniture", "toilet": "furniture", "bench": "furniture",
    })
    DEFAULT_LABEL_CATEGORY: str = "other"

    def get_label_category(self, label: str) -> str:
        """Return the category for a COCO label."""
        return self.LABEL_CATEGORIES.get(label, self.DEFAULT_LABEL_CATEGORY)

    def get_context_weight(self, mode: ContextMode, label: str) -> float:
        """Return the context weight for a mode + label combination."""
        cat = self.get_label_category(label)
        return self.CONTEXT_WEIGHTS.get((mode.value, cat), 0.8)
