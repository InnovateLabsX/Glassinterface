"""Unit tests for the velocity tracking logic."""

import pytest
import time
from glass_engine.models import Detection
from glass_engine.tracking.tracker import ObjectTracker


class TestObjectTracker:
    
    def test_id_fallback(self):
        """Detections without IDs get assigned fallback IDs."""
        tracker = ObjectTracker()
        det = Detection("person", 0.9, (0.1, 0.1, 0.2, 0.2), distance=10.0)
        
        results = tracker.update([det])
        
        assert results[0].id is not None
        assert results[0].id >= 10000
        assert results[0].velocity == 0.0

    def test_id_persistence(self):
        """Detections with existing IDs retain them."""
        tracker = ObjectTracker()
        
        # Frame 1
        det1 = Detection("person", 0.9, (0.1, 0.1, 0.2, 0.2), id=42)
        tracker.update([det1])
        
        # Frame 2
        det2 = Detection("person", 0.9, (0.11, 0.11, 0.21, 0.21), id=42)
        tracker.update([det2])
        
        assert det2.id == 42

    def test_velocity_approaching(self):
        """Object moving closer (distance decreasing) has negative velocity."""
        tracker = ObjectTracker()
        
        # Frame 1: 10m
        det1 = Detection("car", 0.9, (0.1, 0.1, 0.2, 0.2), distance=10.0, id=1)
        tracker.update([det1])
        time.sleep(0.1) # ensure timestamp difference
        
        # Frame 2: 9m (approaching)
        det2 = Detection("car", 0.9, (0.1, 0.1, 0.2, 0.2), distance=9.0, id=1)
        # Manually inject timestamp delay for test stability if needed, 
        # but tracker uses time.time(), so a small sleep is good.
        tracker.update([det2])
        
        assert det2.velocity < 0
        assert det2.approaching is True

    def test_velocity_moving_away(self):
        """Object moving away (distance increasing) has positive velocity."""
        tracker = ObjectTracker()
        
        # Frame 1: 5m
        det1 = Detection("person", 0.9, (0.1, 0.1, 0.2, 0.2), distance=5.0, id=2)
        tracker.update([det1])
        time.sleep(0.1)
        
        # Frame 2: 6m
        det2 = Detection("person", 0.9, (0.1, 0.1, 0.2, 0.2), distance=6.0, id=2)
        tracker.update([det2])
        
        assert det2.velocity > 0
        assert det2.approaching is False

    def test_lost_track_cleanup(self):
        """Tracks are removed after max_age misses."""
        tracker = ObjectTracker(max_age=1)
        
        # Frame 1: Detect
        det = Detection("person", 0.9, (0.1, 0.1, 0.2, 0.2), id=3)
        tracker.update([det])
        assert len(tracker.tracks) == 1
        
        # Frame 2: Miss
        tracker.update([])
        assert len(tracker.tracks) == 1 # Kept alive (misses=1)
        
        # Frame 3: Miss (exceeds max_age=1)
        tracker.update([])
        assert len(tracker.tracks) == 0
