"""Rule-based reasoning engine for safety-critical alerting.

Evaluates detections against a priority rule table and produces
structured ``Alert`` objects sorted by urgency.

Rule Table (evaluated top-down, first match wins per detection):
  1. CRITICAL  — safety label + distance < 1.5 m + CENTER
  2. WARNING   — safety label + distance < 3.0 m
  3. INFO      — any label + distance < 2.0 m
  4. Suppress  — everything else is dropped
"""

from __future__ import annotations

import time
from glass_engine.config import GlassConfig
from glass_engine.models import Alert, Detection


class ReasoningEngine:
    """Converts raw detections into prioritised alerts.

    Parameters:
        config: Engine configuration with distance thresholds and safety labels.
    """

    def __init__(self, config: GlassConfig | None = None) -> None:
        self._config = config or GlassConfig()

    # ── Public API ───────────────────────────────────────────────────

    def evaluate(self, detections: list[Detection]) -> list[Alert]:
        """Apply reasoning rules to a list of enriched detections.

        Args:
            detections: Detections that already have ``distance`` and
                        ``direction`` populated by the ``DistanceEstimator``.

        Returns:
            A list of ``Alert`` objects sorted by urgency (most critical first).
        """
        cfg = self._config
        now = time.time()
        alerts: list[Alert] = []

        for det in detections:
            is_safety = det.label in cfg.SAFETY_LABELS

            # Rule 1 — Collision imminent
            if (
                is_safety
                and det.distance < cfg.CRITICAL_DISTANCE
                and det.direction == "CENTER"
            ):
                alerts.append(self._make_alert(
                    priority="CRITICAL",
                    det=det,
                    template="{label} directly ahead, {dist} metres!",
                    now=now,
                ))
                continue

            # Rule 2 — Close approach
            if is_safety and det.distance < cfg.WARNING_DISTANCE:
                direction_text = self._direction_text(det.direction)
                alerts.append(self._make_alert(
                    priority="WARNING",
                    det=det,
                    template="{label} " + direction_text + ", about {dist} metres",
                    now=now,
                ))
                continue

            # Rule 3 — Nearby non-safety or far safety
            if det.distance < cfg.INFO_DISTANCE:
                direction_text = self._direction_text(det.direction)
                alerts.append(self._make_alert(
                    priority="INFO",
                    det=det,
                    template="{label} nearby " + direction_text + ", {dist} metres",
                    now=now,
                ))
                continue

            # Rule 4 — Suppress (do nothing)

        # Sort by urgency — CRITICAL first
        alerts.sort(key=lambda a: a.urgency())
        return alerts

    # ── Helpers ──────────────────────────────────────────────────────

    @staticmethod
    def _make_alert(
        priority: str,
        det: Detection,
        template: str,
        now: float,
    ) -> Alert:
        message = template.format(
            label=det.label.capitalize(),
            dist=round(det.distance, 1),
        )
        return Alert(
            priority=priority,
            message=message,
            label=det.label,
            distance=det.distance,
            direction=det.direction,
            timestamp=now,
        )

    @staticmethod
    def _direction_text(direction: str) -> str:
        return {
            "LEFT": "to your left",
            "RIGHT": "to your right",
            "CENTER": "ahead",
        }.get(direction, "nearby")
