"""Integration tests for the full pipeline (with mocked detector)."""

import pytest
from unittest.mock import patch, MagicMock
import numpy as np

from glass_engine.sdk import GlassInterfaceSDK
from glass_engine.config import GlassConfig
from glass_engine.models import Detection, FrameResult


def _mock_detections() -> list[Detection]:
    """Simulate YOLOv8 detecting a close person and a far car."""
    return [
        Detection(label="person", confidence=0.92, bbox=(0.35, 0.1, 0.65, 0.9)),
        Detection(label="car", confidence=0.78, bbox=(0.7, 0.4, 0.95, 0.6)),
    ]


class TestPipeline:
    """End-to-end pipeline tests with a mocked detector."""

    @patch("glass_engine.sdk.ObjectDetector")
    def test_full_pipeline_output_structure(self, MockDetector):
        """FrameResult has correct shape and is JSON-serializable."""
        mock_instance = MockDetector.return_value
        mock_instance.detect.return_value = _mock_detections()

        sdk = GlassInterfaceSDK()
        frame = np.zeros((480, 640, 3), dtype=np.uint8)
        result = sdk.process_frame(frame)

        # Structural checks
        assert isinstance(result, FrameResult)
        assert isinstance(result.detections, list)
        assert isinstance(result.alerts, list)
        assert result.processing_time_ms >= 0

        # JSON serialization
        json_str = result.to_json()
        assert '"detections"' in json_str
        assert '"alerts"' in json_str

        # Dict serialization
        d = result.to_dict()
        assert "processing_time_ms" in d
        assert "detections" in d
        assert "alerts" in d

    @patch("glass_engine.sdk.ObjectDetector")
    def test_close_person_generates_alert(self, MockDetector):
        """A person covering most of the frame → should produce alert."""
        mock_instance = MockDetector.return_value
        mock_instance.detect.return_value = [
            Detection(label="person", confidence=0.95, bbox=(0.35, 0.1, 0.65, 0.9)),
        ]

        sdk = GlassInterfaceSDK()
        frame = np.zeros((480, 640, 3), dtype=np.uint8)
        result = sdk.process_frame(frame)

        assert len(result.alerts) >= 1
        assert result.alerts[0].label == "person"

    @patch("glass_engine.sdk.ObjectDetector")
    def test_empty_frame_no_alerts(self, MockDetector):
        """No detections → no alerts."""
        mock_instance = MockDetector.return_value
        mock_instance.detect.return_value = []

        sdk = GlassInterfaceSDK()
        frame = np.zeros((480, 640, 3), dtype=np.uint8)
        result = sdk.process_frame(frame)

        assert len(result.detections) == 0
        assert len(result.alerts) == 0

    @patch("glass_engine.sdk.ObjectDetector")
    def test_alert_cooldown(self, MockDetector):
        """Same detection on consecutive frames → second call is suppressed."""
        mock_instance = MockDetector.return_value
        mock_instance.detect.return_value = [
            Detection(label="person", confidence=0.9, bbox=(0.35, 0.1, 0.65, 0.9)),
        ]

        sdk = GlassInterfaceSDK()
        frame = np.zeros((480, 640, 3), dtype=np.uint8)

        r1 = sdk.process_frame(frame)
        r2 = sdk.process_frame(frame)  # immediate second call

        assert len(r1.alerts) >= 1
        # Second call should be suppressed by cooldown
        assert len(r2.alerts) == 0

    @patch("glass_engine.sdk.ObjectDetector")
    def test_release(self, MockDetector):
        """release() should free resources without error."""
        sdk = GlassInterfaceSDK()
        sdk.release()
        mock_instance = MockDetector.return_value
        mock_instance.release.assert_called_once()
