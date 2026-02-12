"""GlassInterfaceSDK — single entry-point facade for the AI engine.

The app layer only needs to import this class. It wires together
detection, distance estimation, reasoning, and alert management
into one ``process_frame()`` call.
"""

from __future__ import annotations

import time
from typing import Optional

import numpy as np

from glass_engine.config import GlassConfig
from glass_engine.models import FrameResult
from glass_engine.detection.detector import ObjectDetector
from glass_engine.distance.estimator import DistanceEstimator
from glass_engine.reasoning.engine import ReasoningEngine
from glass_engine.alert.manager import AlertManager


class GlassInterfaceSDK:
    """Production facade for the GlassInterface vision pipeline.

    Usage::

        from glass_engine import GlassInterfaceSDK

        sdk = GlassInterfaceSDK()
        result = sdk.process_frame(frame_bgr)
        print(result.to_json())

    Parameters:
        config: Optional custom configuration; uses defaults if omitted.
    """

    def __init__(self, config: Optional[GlassConfig] = None) -> None:
        self._config = config or GlassConfig()
        self._detector = ObjectDetector(self._config)
        self._estimator = DistanceEstimator(self._config)
        self._reasoner = ReasoningEngine(self._config)
        self._alert_mgr = AlertManager(self._config)

    # ── Public API ───────────────────────────────────────────────────

    def process_frame(self, frame: np.ndarray) -> FrameResult:
        """Run the full vision pipeline on a single frame.

        Pipeline stages:
            1. Object detection  (YOLOv8n)
            2. Distance estimation (bbox heuristic)
            3. Reasoning           (priority rules)
            4. Alert management    (cooldown + dedup)

        Args:
            frame: HxWx3 uint8 BGR numpy array.

        Returns:
            ``FrameResult`` containing all detections and filtered alerts.
        """
        t0 = time.perf_counter()
        h, w = frame.shape[:2]

        # Stage 1 — Detect
        detections = self._detector.detect(frame)

        # Stage 2 — Distance + Direction
        detections = self._estimator.estimate(detections, frame_height=h)

        # Stage 3 — Reasoning
        alerts = self._reasoner.evaluate(detections)

        # Stage 4 — Alert filtering
        alerts = self._alert_mgr.filter(alerts)

        elapsed_ms = (time.perf_counter() - t0) * 1000.0

        return FrameResult(
            detections=detections,
            alerts=alerts,
            processing_time_ms=round(elapsed_ms, 2),
        )

    def reset_alerts(self) -> None:
        """Clear alert cooldowns (call on scene change or user request)."""
        self._alert_mgr.reset()

    def release(self) -> None:
        """Free all resources (model memory, etc.)."""
        self._detector.release()
        self._alert_mgr.reset()
