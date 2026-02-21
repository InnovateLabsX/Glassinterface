"""Bounding-box based distance and direction estimator.

Uses a pinhole-camera heuristic:
    distance = (visible_height × focal_length) / bbox_pixel_height

Accounts for partial-body visibility (person sitting, body cut off by
frame edge) by scaling the reference height based on what fraction of
the object is likely visible.
"""

from __future__ import annotations

from glass_engine.config import GlassConfig
from glass_engine.models import Detection


# Margin (normalised) — if a bbox edge is within this distance of the
# frame boundary, the object is considered partially visible.
_EDGE_MARGIN = 0.05


class DistanceEstimator:
    """Estimates relative distance and direction from bounding-box geometry.

    Parameters:
        config: Engine configuration with reference heights and zone boundaries.
    """

    def __init__(self, config: GlassConfig | None = None) -> None:
        self._config = config or GlassConfig()

    # ── Public API ───────────────────────────────────────────────────

    def estimate(
        self,
        detections: list[Detection],
        frame_height: int = 640,
    ) -> list[Detection]:
        """Enrich each detection with ``distance`` and ``direction``.

        Args:
            detections:   Raw detections from the object detector.
            frame_height: Pixel height of the original frame (used to
                          convert normalised bbox back to pixels).

        Returns:
            The same list of detections, mutated in-place with distance
            and direction values filled in.
        """
        cfg = self._config
        for det in detections:
            # ── Distance ────────────────────────────────────────────
            bbox_height_norm = det.height
            bbox_height_px = bbox_height_norm * frame_height

            ref_h = cfg.REFERENCE_HEIGHTS.get(det.label)
            if ref_h is None:
                category = cfg.get_label_category(det.label)
                ref_h = cfg.CATEGORY_REFERENCE_HEIGHTS.get(
                    category, cfg.DEFAULT_REFERENCE_HEIGHT
                )

            # Scale reference height for partial visibility
            visible_h = ref_h * self._visibility_fraction(det)

            if bbox_height_px > 0:
                det.distance = round(
                    (visible_h * cfg.FOCAL_SCALE) / bbox_height_px, 2
                )
            else:
                det.distance = 999.0  # effectively "unknown / far"

            # ── Direction ───────────────────────────────────────────
            cx = det.center_x
            if cx < cfg.LEFT_BOUNDARY:
                det.direction = "LEFT"
            elif cx > cfg.RIGHT_BOUNDARY:
                det.direction = "RIGHT"
            else:
                det.direction = "CENTER"

        return detections

    # ── Helpers ──────────────────────────────────────────────────────

    @staticmethod
    def _visibility_fraction(det: Detection) -> float:
        """Estimate what fraction of the object's real height is visible.

        Heuristics:
          - If the bbox bottom is near the frame bottom AND the top is
            near the frame top, the person is probably sitting / upper
            body only → use ~50 % of reference height.
          - If only the bottom is cut off, the person is likely standing
            but feet are out of frame → use ~85 %.
          - If only the top is cut off (rare), use ~70 %.
          - If bbox covers >75 % of frame height, person is very close
            and likely partially visible → use ~60 %.
          - Otherwise, assume full body visible → 1.0.
        """
        y1, y2 = det.bbox[1], det.bbox[3]
        h = det.height

        top_clipped = y1 < _EDGE_MARGIN
        bottom_clipped = y2 > (1.0 - _EDGE_MARGIN)

        if top_clipped and bottom_clipped:
            # Person fills the frame vertically — very close, partial body
            return 0.50
        if h > 0.75:
            # Large bbox — likely very close, upper body dominant
            return 0.55
        if bottom_clipped:
            # Feet cut off
            return 0.85
        if top_clipped:
            # Head cut off (less common)
            return 0.70

        # Full body visible — check aspect ratio for sitting detection
        aspect = det.width / max(h, 0.01)
        if det.label == "person" and aspect > 0.9 and h < 0.5:
            # Wide + short bbox → likely sitting
            return 0.50

        return 1.0
