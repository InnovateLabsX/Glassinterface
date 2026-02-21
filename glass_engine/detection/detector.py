"""YOLOv8-nano object detector wrapper.

Uses the Ultralytics library to load YOLOv8n and run inference on
numpy frames, returning a list of ``Detection`` instances.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

import numpy as np

from glass_engine.config import GlassConfig
from glass_engine.models import Detection

if TYPE_CHECKING:
    from ultralytics import YOLO


class ObjectDetector:
    """Thin wrapper around a YOLOv8 model for single-frame inference.

    Parameters:
        config: Engine configuration (model path, thresholds, etc.).

    Example::

        detector = ObjectDetector()
        detections = detector.detect(frame_bgr)
    """

    def __init__(self, config: GlassConfig | None = None) -> None:
        self._config = config or GlassConfig()
        self._model: YOLO | None = None

    # ── Lazy model loading ───────────────────────────────────────────

    def _ensure_model(self) -> YOLO:
        """Load the YOLO model on first use (avoids slow import at init)."""
        if self._model is None:
            from ultralytics import YOLO
            self._model = YOLO(self._config.MODEL_PATH)
        return self._model

    # ── Public API ───────────────────────────────────────────────────

    def detect(self, frame: np.ndarray) -> list[Detection]:
        """Run detection on a single BGR numpy frame.

        Args:
            frame: HxWx3 uint8 BGR image (as returned by ``cv2.imread``).

        Returns:
            Filtered list of ``Detection`` objects with normalised bounding
            boxes and no distance/direction yet.
        """
        model = self._ensure_model()
        h, w = frame.shape[:2]

        results = model.track(
            source=frame,
            imgsz=self._config.MODEL_INPUT_SIZE,
            conf=self._config.CONFIDENCE_THRESHOLD,
            iou=self._config.NMS_IOU_THRESHOLD,
            persist=True,        # Keep tracks between frames
            tracker=self._config.TRACKER_CONFIG_PATH, # Use tuned tracker config
            verbose=False,
        )

        detections: list[Detection] = []
        for result in results:
            boxes = result.boxes
            if boxes is None:
                continue
            for box in boxes:
                # Pixel coordinates → normalised [0, 1]
                x1, y1, x2, y2 = box.xyxy[0].tolist()
                conf = float(box.conf[0])
                cls_id = int(box.cls[0])
                label = model.names.get(cls_id, f"class_{cls_id}")
                
                # Extract tracking ID if available
                track_id = int(box.id[0]) if box.id is not None else None

                detections.append(Detection(
                    label=label,
                    confidence=conf,
                    bbox=(x1 / w, y1 / h, x2 / w, y2 / h),
                    id=track_id,
                ))

        return detections

    def release(self) -> None:
        """Free model resources."""
        self._model = None
