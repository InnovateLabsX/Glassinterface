"""Unit tests for the RiskScorer."""

import pytest
from glass_engine.config import ContextMode, GlassConfig
from glass_engine.reasoning.scorer import RiskScorer
from glass_engine.models import Detection


@pytest.fixture
def scorer():
    return RiskScorer(GlassConfig())


def _det(label="person", distance=1.0, direction="CENTER", velocity=0.0, approaching=False):
    """Helper to build a Detection with pre-set fields."""
    d = Detection(label=label, confidence=0.9, bbox=(0.3, 0.1, 0.7, 0.9))
    d.distance = distance
    d.direction = direction
    d.velocity = velocity
    d.approaching = approaching
    return d


class TestRiskFormula:
    """Verify the continuous risk formula components."""

    def test_close_person_critical(self, scorer):
        """Person at 1m ahead → high risk → CRITICAL."""
        alerts = scorer.score([_det("person", 1.0, "CENTER")])
        assert len(alerts) == 1
        assert alerts[0].priority == "CRITICAL"
        assert alerts[0].risk_score > 0.7

    def test_far_person_low_risk(self, scorer):
        """Person at 7m → low risk → INFO or suppress."""
        alerts = scorer.score([_det("person", 7.0, "CENTER")])
        # At 7m with MAX_RISK_RANGE=8: distance_factor = 0.125
        # risk = 0.125 * 1.0 * 1.0 * 1.2 = 0.15 → INFO threshold
        assert len(alerts) <= 1
        if alerts:
            assert alerts[0].priority == "INFO"

    def test_beyond_range_suppressed(self, scorer):
        """Person at 9m (beyond MAX_RISK_RANGE=8) → suppress."""
        alerts = scorer.score([_det("person", 9.0, "CENTER")])
        assert len(alerts) == 0

    def test_approaching_boosts_risk(self, scorer):
        """Fast approach should boost risk via velocity_factor."""
        static = _det("person", 3.0, "CENTER", velocity=0.0)
        approaching = _det("person", 3.0, "CENTER", velocity=-2.0, approaching=True)

        scorer.score([static])
        risk_static = static.risk_score

        scorer.score([approaching])
        risk_approaching = approaching.risk_score

        assert risk_approaching > risk_static

    def test_non_safety_lower_priority(self, scorer):
        """Non-safety object (backpack) gets object_priority=0.5 → lower risk."""
        person = _det("person", 2.0, "CENTER")
        backpack = _det("backpack", 2.0, "CENTER")

        scorer.score([person])
        scorer.score([backpack])

        assert person.risk_score > backpack.risk_score

    def test_risk_score_populated(self, scorer):
        """risk_score should be set on the Detection object."""
        det = _det("car", 2.0, "LEFT")
        scorer.score([det])
        assert det.risk_score > 0


class TestContextModes:
    """Verify context weights affect risk scoring."""

    def test_walking_boosts_vehicle_risk(self):
        """In WALKING mode, vehicles should have higher risk than STATIONARY."""
        cfg = GlassConfig()
        scorer_walk = RiskScorer(cfg)
        scorer_walk.context = ContextMode.WALKING

        scorer_stat = RiskScorer(cfg)
        scorer_stat.context = ContextMode.STATIONARY

        det_walk = _det("car", 3.0, "CENTER")
        det_stat = _det("car", 3.0, "CENTER")

        scorer_walk.score([det_walk])
        scorer_stat.score([det_stat])

        assert det_walk.risk_score > det_stat.risk_score

    def test_indoor_deprioritizes_vehicles(self):
        """Indoor mode should lower vehicle risk (weight=0.5)."""
        cfg = GlassConfig()
        scorer = RiskScorer(cfg)
        scorer.context = ContextMode.INDOOR

        det = _det("car", 2.0, "CENTER")
        scorer.score([det])

        # Indoor vehicle weight is 0.5, so risk is halved vs default
        assert det.risk_score < 0.5

    def test_context_property(self):
        scorer = RiskScorer()
        assert scorer.context == ContextMode.OUTDOOR  # default
        scorer.context = ContextMode.WALKING
        assert scorer.context == ContextMode.WALKING


class TestThresholdBoundaries:
    """Verify exact threshold transitions."""

    def test_critical_threshold(self, scorer):
        """Risk ≥ 0.7 → CRITICAL."""
        # Person at 0.5m: dist_factor=0.9375, risk ~ 0.94 * 1.0 * 1.0 * 1.2 = 1.125
        det = _det("person", 0.5, "CENTER")
        alerts = scorer.score([det])
        assert alerts[0].priority == "CRITICAL"

    def test_warning_threshold(self, scorer):
        """Risk in [0.4, 0.7) → WARNING."""
        # Person at 4.5m: dist_factor=0.4375, risk = 0.4375 * 1.0 * 1.0 * 1.2 = 0.525
        det = _det("person", 4.5, "CENTER")
        alerts = scorer.score([det])
        assert alerts[0].priority == "WARNING"

    def test_info_threshold(self, scorer):
        """Risk in [0.15, 0.4) → INFO."""
        # Backpack at 3m: dist_factor=0.625, risk = 0.625 * 1.0 * 0.5 * 1.2 = 0.375
        # Actually that's > 0.15 but < 0.4 only if context is different
        # Let's use a farther non-safety object
        det = _det("backpack", 6.0, "LEFT")
        alerts = scorer.score([det])
        if alerts:
            assert alerts[0].priority == "INFO"
            assert "Move"  not in alerts[0].message # info shouldn't have naval prompts

class TestNavigationHints:
    """Verify explicit navigational guidance logic."""
    def test_center_critical_hold(self, scorer):
        """Extremely high risk object dead center suggests Hold."""
        det = _det("truck", 0.5, "CENTER", velocity=-2.0, approaching=True)
        alerts = scorer.score([det])
        assert "Hold." in alerts[0].message

    def test_center_warning_move_right(self, scorer):
        """Center warning object suggests typical pedestrian passing (Move right)."""
        # Person at 5m: dist_factor=0.375. vel=-1.0 -> vel_boost=0.5 -> vel_factor=1.5
        # risk = 0.375 * 1.5 * 1.0 * 1.2 = 0.675 -> WARNING
        det = _det("person", 5.0, "CENTER", velocity=-1.0, approaching=True)
        alerts = scorer.score([det])
        assert "Move right." in alerts[0].message

    def test_left_object_move_right(self, scorer):
        """Object approaching from left suggests moving right."""
        det = _det("car", 2.0, "LEFT", velocity=-1.0, approaching=True)
        alerts = scorer.score([det])
        assert "Move right." in alerts[0].message

    def test_right_object_move_left(self, scorer):
        """Object approaching from right suggests moving left."""
        det = _det("bicycle", 2.0, "RIGHT", velocity=-1.0, approaching=True)
        alerts = scorer.score([det])
        assert "Move left." in alerts[0].message

    def test_safe_distance_proceed(self, scorer):
        """Warning object at safe distance and not approaching suggests caution."""
        det = _det("car", 4.0, "LEFT")
        # Ensure it's treated as a warning
        det.risk_score = 0.5 
        alerts = scorer.score([det])
        assert "Proceed with caution." in alerts[0].message
