#!/usr/bin/env python3
"""Live webcam demo for the GlassInterface AI engine.

Opens the default camera, runs the full pipeline on every frame, and
displays a live preview with bounding boxes, distance labels, and
alert overlays.

Usage:
    python demo.py                 # default camera (index 0)
    python demo.py --camera 1      # specific camera index
    python demo.py --video path.mp4  # run on a video file

Keys:
    q — quit
    c — calibrate (stand at known distance, enter it in terminal)
    r — reset alert cooldowns
"""

from __future__ import annotations

import argparse
import sys
import cv2
import numpy as np

from glass_engine.sdk import GlassInterfaceSDK
from glass_engine.config import GlassConfig
from glass_engine.models import Detection, Alert

# ── Colour palette ────────────────────────────────────────────────────
COLORS = {
    "CRITICAL": (0, 0, 255),    # Red
    "WARNING":  (0, 165, 255),  # Orange
    "INFO":     (0, 255, 0),    # Green
}
DEFAULT_COLOR = (255, 255, 0)   # Cyan


def draw_detections(frame: np.ndarray, detections: list[Detection]) -> None:
    """Draw bounding boxes with labels and distance on the frame."""
    h, w = frame.shape[:2]
    for det in detections:
        x1 = int(det.bbox[0] * w)
        y1 = int(det.bbox[1] * h)
        x2 = int(det.bbox[2] * w)
        y2 = int(det.bbox[3] * h)

        cv2.rectangle(frame, (x1, y1), (x2, y2), DEFAULT_COLOR, 2)

        text = f"{det.label} {det.distance:.1f}m ({det.direction})"
        (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
        cv2.rectangle(frame, (x1, y1 - th - 8), (x1 + tw + 4, y1), DEFAULT_COLOR, -1)
        cv2.putText(frame, text, (x1 + 2, y1 - 4),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 1)


def draw_alerts(frame: np.ndarray, alerts: list[Alert]) -> None:
    """Draw alert banners at the top of the frame."""
    y = 30
    for alert in alerts:
        color = COLORS.get(alert.priority, DEFAULT_COLOR)
        text = f"[{alert.priority}] {alert.message}"
        cv2.putText(frame, text, (10, y),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)
        y += 30


def draw_help(frame: np.ndarray) -> None:
    """Draw key hints at the bottom of the frame."""
    h = frame.shape[0]
    text = "q: quit | c: calibrate | r: reset alerts"
    cv2.putText(frame, text, (10, h - 10),
                cv2.FONT_HERSHEY_SIMPLEX, 0.45, (180, 180, 180), 1)


def calibrate(sdk: GlassInterfaceSDK, detections: list[Detection], frame_height: int) -> None:
    """Interactive calibration: user stands at a known distance.

    Computes the correct FOCAL_SCALE from:
        focal_scale = actual_distance × bbox_px_height / reference_height
    """
    # Find the largest person detection
    person_dets = [d for d in detections if d.label == "person"]
    if not person_dets:
        print("[CALIBRATE] No person detected — stand in front of the camera and try again.")
        return

    det = max(person_dets, key=lambda d: d.height)
    bbox_height_px = det.height * frame_height
    ref_h = sdk._config.REFERENCE_HEIGHTS.get("person", 1.70)

    print(f"\n[CALIBRATE] Detected person — bbox covers {det.height*100:.1f}% of frame")
    try:
        actual = float(input("[CALIBRATE] Enter your ACTUAL distance from camera in metres: "))
    except (ValueError, EOFError):
        print("[CALIBRATE] Invalid input — calibration cancelled.")
        return

    if actual <= 0:
        print("[CALIBRATE] Distance must be positive — cancelled.")
        return

    # Reverse the formula: focal_scale = actual_distance × bbox_px_height / ref_h
    # But account for visibility fraction
    from glass_engine.distance.estimator import DistanceEstimator
    vis_frac = DistanceEstimator._visibility_fraction(det)
    visible_h = ref_h * vis_frac

    new_focal = (actual * bbox_height_px) / visible_h
    old_focal = sdk._config.FOCAL_SCALE

    print(f"[CALIBRATE] Old FOCAL_SCALE: {old_focal:.1f}")
    print(f"[CALIBRATE] New FOCAL_SCALE: {new_focal:.1f}")
    print(f"[CALIBRATE] Visibility fraction used: {vis_frac:.2f}")

    sdk._config.FOCAL_SCALE = new_focal
    # Propagate to estimator
    sdk._estimator._config.FOCAL_SCALE = new_focal
    print(f"[CALIBRATE] ✓ Updated! Distances should now be accurate.\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="GlassInterface live demo")
    parser.add_argument("--camera", type=int, default=0, help="Camera index")
    parser.add_argument("--video", type=str, default=None, help="Video file path")
    args = parser.parse_args()

    source = args.video if args.video else args.camera

    cap = cv2.VideoCapture(source)
    if not cap.isOpened():
        print(f"[ERROR] Cannot open video source: {source}")
        sys.exit(1)

    sdk = GlassInterfaceSDK()
    print("[GlassInterface] Engine started.")
    print("  q = quit | c = calibrate distance | r = reset alerts")

    last_result = None

    try:
        while True:
            ret, frame = cap.read()
            if not ret:
                if args.video:
                    print("[INFO] End of video.")
                break

            result = sdk.process_frame(frame)
            last_result = result

            # Draw visualisations
            draw_detections(frame, result.detections)
            draw_alerts(frame, result.alerts)
            draw_help(frame)

            # FPS / timing overlay
            fps_text = f"{result.processing_time_ms:.0f}ms"
            cv2.putText(frame, fps_text, (frame.shape[1] - 100, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)

            cv2.imshow("GlassInterface Demo", frame)

            # Print alerts to console
            for alert in result.alerts:
                print(f"  [{alert.priority}] {alert.message}")

            key = cv2.waitKey(1) & 0xFF
            if key == ord("q"):
                break
            elif key == ord("c") and last_result:
                calibrate(sdk, last_result.detections, frame.shape[0])
            elif key == ord("r"):
                sdk.reset_alerts()
                print("[INFO] Alert cooldowns reset.")

    finally:
        sdk.release()
        cap.release()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
