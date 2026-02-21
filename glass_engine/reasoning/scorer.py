"""Dynamic risk scoring engine (Level 2).

Replaces the rigid if/elif rule table with a continuous risk formula:

    risk = distance_factor × velocity_factor × object_priority × context_weight

The score is mapped to CRITICAL / WARNING / INFO / suppress via
configurable thresholds.
"""

from __future__ import annotations

from glass_engine.config import ContextMode, GlassConfig
from glass_engine.models import Alert, Detection

import time


class RiskScorer:
    """Computes continuous risk scores and generates prioritised alerts.

    Parameters:
        config: Engine configuration with risk thresholds and context weights.
    """

    def __init__(self, config: GlassConfig | None = None) -> None:
        self._config = config or GlassConfig()
        self._context = ContextMode.OUTDOOR  # default

    @property
    def context(self) -> ContextMode:
        return self._context

    @context.setter
    def context(self, mode: ContextMode) -> None:
        self._context = mode

    # ── Public API ───────────────────────────────────────────────────

    def score(self, detections: list[Detection]) -> list[Alert]:
        """Score each detection and return alerts for those above threshold.

        Args:
            detections: Enriched detections with distance, velocity, etc.

        Returns:
            Alerts sorted by urgency (highest risk first).
        """
        cfg = self._config
        now = time.time()
        alerts: list[Alert] = []

        for det in detections:
            risk = self._compute_risk(det)
            det.risk_score = risk

            priority = self._risk_to_priority(risk)
            if priority is None:
                continue  # suppressed

            alert = self._make_alert(priority, det, risk, now)
            alerts.append(alert)

        # Sort by risk score descending (highest risk first)
        alerts.sort(key=lambda a: -a.risk_score)
        return alerts

    # ── Risk Formula ─────────────────────────────────────────────────

    def _compute_risk(self, det: Detection) -> float:
        """Compute the composite risk score for a single detection."""
        cfg = self._config

        # Factor 1: Distance (closer = higher risk)
        # Clamp to [0, 1] — beyond MAX_RISK_RANGE, risk contribution is 0
        distance_factor = max(0.0, min(1.0, 1.0 - det.distance / cfg.MAX_RISK_RANGE))

        # Factor 2: Velocity (approaching = boost)
        # vel < 0 means approaching; clamp boost to [1.0, 2.0]
        velocity_boost = max(0.0, min(1.0, -det.velocity / 2.0))
        velocity_factor = 1.0 + velocity_boost

        # Factor 3: Object priority (safety labels = 1.0, others = 0.5)
        object_priority = 1.0 if det.label in cfg.SAFETY_LABELS else 0.5

        # Factor 4: Context weight (mode × label category)
        context_weight = cfg.get_context_weight(self._context, det.label)

        return distance_factor * velocity_factor * object_priority * context_weight

    def _risk_to_priority(self, risk: float) -> str | None:
        """Map continuous risk score to a discrete priority tier."""
        cfg = self._config
        if risk >= cfg.RISK_CRITICAL:
            return "CRITICAL"
        if risk >= cfg.RISK_WARNING:
            return "WARNING"
        if risk >= cfg.RISK_INFO:
            return "INFO"
        return None  # suppress

    # ── Alert Construction ───────────────────────────────────────────

    def _make_alert(
        self,
        priority: str,
        det: Detection,
        risk: float,
        now: float,
    ) -> Alert:
        """Build an Alert from a scored detection."""
        label_cap = det.label.capitalize()
        direction_text = self._direction_text(det.direction)

        if priority == "CRITICAL":
            if det.approaching:
                msg = f"{label_cap} approaching fast {direction_text}, {det.distance:.1f} metres!"
            else:
                msg = f"{label_cap} very close {direction_text}, {det.distance:.1f} metres!"
        elif priority == "WARNING":
            msg = f"{label_cap} {direction_text}, about {det.distance:.1f} metres."
        else:
            msg = f"{label_cap} nearby {direction_text}, {det.distance:.1f} metres."

        # Append navigation instruction for warning and critical alerts
        if priority in ("CRITICAL", "WARNING"):
            hint = self._get_navigation_hint(det)
            msg = f"{msg} {hint}"

        return Alert(
            priority=priority,
            message=msg,
            label=det.label,
            distance=det.distance,
            direction=det.direction,
            velocity=det.velocity,
            approaching=det.approaching,
            risk_score=risk,
            timestamp=now,
        )

    @staticmethod
    def _direction_text(direction: str) -> str:
        return {
            "LEFT": "to your left",
            "RIGHT": "to your right",
            "CENTER": "ahead",
        }.get(direction, "nearby")

    @staticmethod
    def _get_navigation_hint(det: Detection) -> str:
        """Provide explicit navigational guidance based on obstacle location."""
        if not det.approaching and det.distance > 3.0:
             return "Proceed with caution."

        if det.direction == "CENTER":
            if det.risk_score > 1.0: # Extremely critical
                return "Hold."
            return "Move right."
        elif det.direction == "LEFT":
            return "Move right."
        elif det.direction == "RIGHT":
            return "Move left."
        return "Hold."

