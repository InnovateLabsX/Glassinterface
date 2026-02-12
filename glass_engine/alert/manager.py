"""Alert manager — cooldown, deduplication, and rate-limiting.

Prevents the app layer (TTS) from being overwhelmed by repeated alerts
for the same object. Each label+direction pair has an independent cooldown
timer, and the total alerts per frame are capped.
"""

from __future__ import annotations

import time
from glass_engine.config import GlassConfig
from glass_engine.models import Alert


class AlertManager:
    """Filters alerts through cooldown and rate-limiting logic.

    Parameters:
        config: Engine configuration with cooldown durations and max alerts.
    """

    def __init__(self, config: GlassConfig | None = None) -> None:
        self._config = config or GlassConfig()
        # Key: (label, direction) → last alert timestamp
        self._cooldown_map: dict[tuple[str, str], float] = {}

    # ── Public API ───────────────────────────────────────────────────

    def filter(self, alerts: list[Alert]) -> list[Alert]:
        """Apply cooldown and rate-limiting to a sorted alert list.

        Args:
            alerts: Alerts sorted by urgency (CRITICAL first), as
                    returned by ``ReasoningEngine.evaluate``.

        Returns:
            A pruned list — same order, but with duplicates and
            cooldown-blocked alerts removed, capped at
            ``config.MAX_ALERTS_PER_FRAME``.
        """
        now = time.time()
        approved: list[Alert] = []

        for alert in alerts:
            if len(approved) >= self._config.MAX_ALERTS_PER_FRAME:
                break

            key = (alert.label, alert.direction)
            cooldown = self._cooldown_for(alert.priority)
            last_fired = self._cooldown_map.get(key, 0.0)

            if (now - last_fired) >= cooldown:
                approved.append(alert)
                self._cooldown_map[key] = now

        return approved

    def reset(self) -> None:
        """Clear all cooldown timers (e.g. on scene change)."""
        self._cooldown_map.clear()

    # ── Helpers ──────────────────────────────────────────────────────

    def _cooldown_for(self, priority: str) -> float:
        """Return the cooldown duration (seconds) for a given priority."""
        return {
            "CRITICAL": self._config.COOLDOWN_CRITICAL,
            "WARNING": self._config.COOLDOWN_WARNING,
            "INFO": self._config.COOLDOWN_INFO,
        }.get(priority, self._config.COOLDOWN_INFO)
