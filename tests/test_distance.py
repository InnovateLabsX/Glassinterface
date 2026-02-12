"""Unit tests for the DistanceEstimator."""

import pytest
from glass_engine.config import GlassConfig
from glass_engine.distance.estimator import DistanceEstimator
from glass_engine.models import Detection


@pytest.fixture
def estimator():
    return DistanceEstimator(GlassConfig())


class TestDistanceEstimation:
    """Tests for the inverse-bbox-height distance formula with visibility scaling."""

    def test_known_person_full_body(self, estimator):
        """Person bbox covering ~28% of frame, aspect>0.9 + h<0.5 → sitting heuristic.
        vis=0.50, distance = 1.70 * 0.50 * 400 / (0.28 * 640) ≈ 1.90m
        """
        det = Detection(label="person", confidence=0.9, bbox=(0.3, 0.1, 0.6, 0.38))
        result = estimator.estimate([det], frame_height=640)
        assert 1.5 < result[0].distance < 2.5

    def test_close_person_large_bbox(self, estimator):
        """Person covering 80% of frame → visibility ~55%, much closer estimate."""
        det = Detection(label="person", confidence=0.9, bbox=(0.1, 0.1, 0.9, 0.9))
        # h=0.8 > 0.75 → vis=0.55, distance = 1.70*0.55*400 / (0.8*640) ≈ 0.73m
        result = estimator.estimate([det], frame_height=640)
        assert result[0].distance < 1.5

    def test_person_fills_frame(self, estimator):
        """Person bbox from top edge to bottom edge → vis=0.50 (both clipped)."""
        det = Detection(label="person", confidence=0.9, bbox=(0.2, 0.01, 0.8, 0.98))
        # top_clipped + bottom_clipped → vis=0.50
        result = estimator.estimate([det], frame_height=640)
        assert result[0].distance < 1.0

    def test_car_distance(self, estimator):
        """Car with medium bbox, not clipped."""
        det = Detection(label="car", confidence=0.85, bbox=(0.2, 0.4, 0.8, 0.7))
        # h=0.3, vis=1.0, distance = 1.50 * 400 / (0.3 * 640) ≈ 3.13m
        result = estimator.estimate([det], frame_height=640)
        assert 2.5 < result[0].distance < 4.0

    def test_unknown_label_uses_default(self, estimator):
        """Unknown labels use DEFAULT_REFERENCE_HEIGHT = 1.0."""
        det = Detection(label="backpack", confidence=0.7, bbox=(0.3, 0.3, 0.6, 0.6))
        result = estimator.estimate([det], frame_height=640)
        assert result[0].distance > 0

    def test_zero_height_bbox(self, estimator):
        """A zero-height bbox should return 999.0 (far / unknown)."""
        det = Detection(label="person", confidence=0.9, bbox=(0.3, 0.5, 0.6, 0.5))
        result = estimator.estimate([det], frame_height=640)
        assert result[0].distance == 999.0


class TestVisibilityFraction:
    """Tests for the _visibility_fraction heuristic."""

    def test_full_body_visible(self, estimator):
        """Bbox in the middle of the frame → 1.0."""
        det = Detection(label="person", confidence=0.9, bbox=(0.3, 0.2, 0.6, 0.6))
        assert DistanceEstimator._visibility_fraction(det) == 1.0

    def test_both_edges_clipped(self, estimator):
        """Bbox touching both top and bottom → 0.50."""
        det = Detection(label="person", confidence=0.9, bbox=(0.2, 0.02, 0.8, 0.97))
        assert DistanceEstimator._visibility_fraction(det) == 0.50

    def test_large_bbox(self, estimator):
        """Bbox > 75% of frame but not touching edges → 0.55."""
        det = Detection(label="person", confidence=0.9, bbox=(0.1, 0.1, 0.9, 0.9))
        assert DistanceEstimator._visibility_fraction(det) == 0.55

    def test_bottom_clipped(self, estimator):
        """Bbox bottom near frame bottom → 0.85 (feet cut off)."""
        det = Detection(label="person", confidence=0.9, bbox=(0.3, 0.3, 0.7, 0.97))
        assert DistanceEstimator._visibility_fraction(det) == 0.85

    def test_sitting_person(self, estimator):
        """Wide, short person bbox → 0.50 (sitting heuristic)."""
        det = Detection(label="person", confidence=0.9, bbox=(0.2, 0.3, 0.8, 0.65))
        # width=0.6, height=0.35, aspect=1.71>0.9, h<0.5 → sitting
        assert DistanceEstimator._visibility_fraction(det) == 0.50


class TestDirectionClassification:
    """Tests for LEFT / CENTER / RIGHT zone classification."""

    def test_left_zone(self, estimator):
        det = Detection(label="person", confidence=0.9, bbox=(0.0, 0.1, 0.2, 0.9))
        result = estimator.estimate([det], frame_height=640)
        assert result[0].direction == "LEFT"

    def test_center_zone(self, estimator):
        det = Detection(label="person", confidence=0.9, bbox=(0.35, 0.1, 0.65, 0.9))
        result = estimator.estimate([det], frame_height=640)
        assert result[0].direction == "CENTER"

    def test_right_zone(self, estimator):
        det = Detection(label="person", confidence=0.9, bbox=(0.75, 0.1, 0.95, 0.9))
        result = estimator.estimate([det], frame_height=640)
        assert result[0].direction == "RIGHT"

    def test_multiple_detections(self, estimator):
        """All detections in a batch get distance + direction."""
        dets = [
            Detection(label="person", confidence=0.9, bbox=(0.0, 0.1, 0.2, 0.9)),
            Detection(label="car", confidence=0.8, bbox=(0.4, 0.3, 0.6, 0.7)),
        ]
        result = estimator.estimate(dets, frame_height=640)
        assert len(result) == 2
        assert all(d.distance > 0 for d in result)
        assert result[0].direction == "LEFT"
        assert result[1].direction == "CENTER"
