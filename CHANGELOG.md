# Changelog

All notable changes to the GlassInterface project will be documented in this file.

## [0.2.0] - 2026-02-21

### Added
- **Upgraded Object Detection**: Migrated the core detection model from `yolov8n` to the more robust `yolov8s`, supporting a wider variety of objects and better precision.
- **Native Object Tracking**: Integrated YOLOv8's built-in `BoT-SORT` tracker, replacing the custom, brittle IoU logic for persistent object IDs.
- **Tracker False-Positive Filtering**: Implemented a custom tuned BoT-SORT configuration (`custom_tracker.yaml`) alongside an increased confidence threshold (`0.45`) to aggressively drop phantom bounding boxes and background noise.
- **Categorical Distance Fallbacks**: Replaced the global unknown-object `0.30m` height fallback with category-specific estimates (e.g., vehicles: 1.5m, animals: 0.6m) to massively fix distance hallucination bugs on unknown objects.
- **Adaptive Navigation Prompts**: The Engine is now an active guide! It analyzes an object's spatial location relative to the user and their respective velocities to append explicit navigation instructions (`"Move right."`, `"Move left."`, `"Hold."`) to `WARNING` and `CRITICAL` alerts.
- **Android 14+ Installer Hotfix**: Replaced the solid color adaptive icon foreground with a standard vector drawable (`ic_launcher_foreground.xml`) to prevent a rendering bug that caused the Android Package Installer to force-close on modern devices (e.g., OnePlus Nord 5, Android 15/16).

### Changed
- The Android Client's NetworkAIEngine requires the LAN IP address of the Python AI server. Ensure `demo.py` is closed and the uvicorn REST API is running.

## [0.1.0] - 2026-02-12
### Added
- Initial Release of GlassInterface (Level 2 Architecture)
- Rule-based dynamic Risk Scoring engine (`RiskScorer`)
- Continuous Context modes (INDOOR, OUTDOOR, WALKING, STATIONARY)
- Distance Estimator utilizing bounding box scaling.
- Alert suppression and history accumulation subsystems.
- Real-time Android WebSocket / HTTP frontend bridge implementation.
