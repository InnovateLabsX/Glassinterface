# Changelog

All notable changes to this project will be documented in this file.

## [2.0.0] - 2026-02-21

### Added
- **Native Android AI Engine (`LocalAIEngine.kt`)**: The application now runs AI inference entirely on-device, removing the need for a separate Python PC server.
- **TensorFlow Lite Integration**: YOLOv8s PyTorch model exported and optimized as an Integer-Quantized `.tflite` model packed into the APK assets.
- **Dual Camera Source Ingest**: Users can now toggle between the built-in Android camera (`CameraX`) or an external hardware camera (`ESP32-CAM`).
- **ESP32 MJPEG Streaming (`MjpegInputStream.kt`)**: Added native HTTP stream parsing to extract continuous JPEG frames from wearable camera hardware.
- Native Kotlin ports of the `DistanceEstimator` and `RiskScorer` logic algorithms, removing Python dependencies.
- Settings Screen UI update to toggle external camera stream URLs.

### Changed
- The `ai-integration` branch now serves as the legacy archive for the old Python Fast-API server and `glass_engine` codebase.
- Re-architected `MainViewModel.kt` to natively pull data off the TFLite output tensors and dispatch Coroutine HTTP network loops for video ingestion.

### Removed
- Removed the local Python server WebSocket protocol from the main branch.
- Removed legacy dependencies like `scipy`, `lapx`, `lap`, and `ultralytics` tracking from the main branch pipeline.

## [1.0.0] - Initial Release
- Basic Python AI server with YOLOv8n object detection.
- Simple Android Thin-Client viewing stream overlay with WebSocket connectivity.
