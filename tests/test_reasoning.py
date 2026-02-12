"""Unit tests for the ReasoningEngine."""

import pytest
from glass_engine.config import GlassConfig
from glass_engine.reasoning.engine import ReasoningEngine
from glass_engine.models import Detection


@pytest.fixture
def engine():
    return ReasoningEngine(GlassConfig())


def _det(label="person", distance=1.0, direction="CENTER", conf=0.9):
    """Helper to create a Detection with distance/direction pre-set."""
    d = Detection(label=label, confidence=conf, bbox=(0.3, 0.1, 0.7, 0.9))
    d.distance = distance
    d.direction = direction
    return d


class TestCriticalAlerts:
    """Rule 1: safety label + < 1.5m + CENTER → CRITICAL."""

    def test_person_close_center(self, engine):
        alerts = engine.evaluate([_det("person", 1.0, "CENTER")])
        assert len(alerts) == 1
        assert alerts[0].priority == "CRITICAL"
        assert "Person" in alerts[0].message

    def test_car_close_center(self, engine):
        alerts = engine.evaluate([_det("car", 1.2, "CENTER")])
        assert alerts[0].priority == "CRITICAL"

    def test_not_critical_if_left(self, engine):
        """Even close, LEFT direction → WARNING, not CRITICAL."""
        alerts = engine.evaluate([_det("person", 1.0, "LEFT")])
        assert alerts[0].priority == "WARNING"

    def test_not_critical_if_far(self, engine):
        """Person at 2.5m CENTER → WARNING, not CRITICAL."""
        alerts = engine.evaluate([_det("person", 2.5, "CENTER")])
        assert alerts[0].priority == "WARNING"


class TestWarningAlerts:
    """Rule 2: safety label + < 3.0m → WARNING."""

    def test_person_medium_distance(self, engine):
        alerts = engine.evaluate([_det("person", 2.5, "RIGHT")])
        assert len(alerts) == 1
        assert alerts[0].priority == "WARNING"

    def test_bicycle_warning(self, engine):
        alerts = engine.evaluate([_det("bicycle", 2.0, "LEFT")])
        assert alerts[0].priority == "WARNING"


class TestInfoAlerts:
    """Rule 3: any label + < 2.0m → INFO."""

    def test_non_safety_close(self, engine):
        """A backpack (non-safety) at 1.5m → INFO."""
        alerts = engine.evaluate([_det("backpack", 1.5, "CENTER")])
        assert len(alerts) == 1
        assert alerts[0].priority == "INFO"


class TestSuppression:
    """Rule 4: far objects are dropped entirely."""

    def test_far_person_suppressed(self, engine):
        """Person at 5m → no alert."""
        alerts = engine.evaluate([_det("person", 5.0, "CENTER")])
        assert len(alerts) == 0

    def test_far_non_safety_suppressed(self, engine):
        """Backpack at 3m → no alert."""
        alerts = engine.evaluate([_det("backpack", 3.0, "LEFT")])
        assert len(alerts) == 0


class TestSorting:
    """Alerts should be sorted by urgency (CRITICAL first)."""

    def test_critical_before_warning(self, engine):
        dets = [
            _det("car", 2.5, "LEFT"),       # WARNING
            _det("person", 1.0, "CENTER"),   # CRITICAL
        ]
        alerts = engine.evaluate(dets)
        assert alerts[0].priority == "CRITICAL"
        assert alerts[1].priority == "WARNING"

    def test_multiple_priorities(self, engine):
        dets = [
            _det("backpack", 1.5, "CENTER"),  # INFO
            _det("person", 1.0, "CENTER"),    # CRITICAL
            _det("car", 2.5, "RIGHT"),        # WARNING
        ]
        alerts = engine.evaluate(dets)
        priorities = [a.priority for a in alerts]
        assert priorities == ["CRITICAL", "WARNING", "INFO"]
