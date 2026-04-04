# Changelog

All notable changes to this project will be documented in this file.

## [0.6.1] - 2026-04-05

### Fixed
- **Voice Interruption / Continuous Shouting**: Fixed a bug where continuous `TTSManager` spatial alerts were drowning out the user's voice input, causing continuous "shouting" and ignored speech recognition. TTS is now actively muted while the microphone is listening, and immediately halted upon tapping the mic FAB.

---

## [0.6.0] - 2026-04-05

### Added
- **Voice Assistant (`core:voice`)**: Push-to-talk voice input using Android `SpeechRecognizer`. 11 voice commands — "save face", "save this", "save contact", "save location", "save time", "save note", "what do you see", "who is this", "list memories", "help".
- **Face Recognition (`FaceRecognitionEngine.kt`)**: On-device face detection via Google ML Kit with landmark-based embeddings for face re-identification. Runs in parallel with YOLOv8 object detection.
- **Memory System (`core:memory`)**: Room SQLite database with 6 entity types — saved faces, saved objects, contacts, locations, timestamps, and free-form notes. Includes face embedding search via cosine similarity.
- **Memory Browser (`feature:memory`)**: Tabbed Compose UI to view, browse, and delete all saved memories with thumbnails and timestamps.
- **Mic FAB on MainScreen**: Floating action button with pulsing animation when listening, plus voice feedback banner for TTS responses.
- **Scene Description**: Say "what do you see" and the assistant reads out all detected objects with distances and directions.
- **GPS Location Saving**: "Save location" command stores current GPS coordinates with a custom label.
- **Face Name Overlay**: Recognized face names now appear in bounding box labels on the camera overlay.

### Changed
- **MainViewModel**: Complete rewrite integrating voice command handling, face recognition pipeline, memory CRUD, and location services.
- **MainActivity**: Now requests `RECORD_AUDIO` alongside `CAMERA` permission at startup.
- **BoundingBoxOverlay**: Updated to display face name tags alongside object labels.

### Dependencies
- Added Room 2.6.1, ML Kit Face Detection 16.1.7, Play Services Location 21.3.0.

### Performance
- Bitmap pooling in `LocalAIEngine` to eliminate GC pressure during frame enhancement.
- Pre-decode frame throttling (~8 FPS gate) in `CameraFrameProvider` to prevent inference saturation.
- `RGB_565` bitmap decoding for 50% memory reduction per frame.
- Per-label TTS cooldowns with priority-based scaling in `TTSManager`.

---

## [0.5.0] - 2026-04-01

### Added
- **ESP32 WebSocket Support (`CameraFrameProvider.kt`)**: Implemented OkHttp WebSocket client to natively receive binary JPEGs from custom ESP32 robot sketches. Dual-supports standard HTTP MJPEG and WebSockets dynamically.
- `android.build.pageAlign=true` added to gradle properties to support Android 15's 16KB page size requirement for native libraries.

### Fixed
- Fixed bug where `MainScreen` UI was strictly bound to `CameraX` local camera, obscuring the ESP32 external stream from the user.
- Fixed `MjpegInputStream` relying on single-pass EOF sequence checking.

---

## [0.4.1] - 2026-03-30

### Fixed
- **Build-from-source broken for new cloners**: `.gitignore` had a blanket `*.tflite` rule that excluded the essential `app/src/main/assets/yolov8s.tflite` model from version control. Anyone cloning the repo would get a build with no ML model, crashing `LocalAIEngine` at runtime.
- Added `.gitignore` negation rule to force-track the Android asset model while still ignoring other exported TFLite files.

---

## [0.4.0] - 2026-02-21

### Fixed
- **Bounding Box Rendering**: TFLite model outputs normalized `[0..1]` coordinates, but `extractBoxes()` was dividing by 640 — making every box sub-pixel and invisible. Now auto-detects coordinate system.
- **TTS Alert Messages**: `RiskScorer.makeAlert()` had broken string interpolation — TTS was reading literal `String.format(...)` instead of actual distance values like *"2.3 metres"*.

### Added
- **Object Tracking (`CentroidTracker.kt`)**: Persistent cross-frame tracking IDs, velocity estimation from bbox height changes, and approaching-object detection.
- **Tracking ID Overlay**: Bounding boxes now display `#ID label 87%` with distance and direction.
- **Navigation Hints**: Alert messages include directional guidance (e.g., *"Person ahead, 2.3 metres. Move right."*).

### Changed
- Detection confidence threshold lowered from 0.45 → 0.35 for safety-critical blind navigation.
- Detection pipeline expanded: Detect → Distance → **Track** → Score → Alert.
- Default server URL changed to ESP32-CAM default (`http://192.168.4.1:81/stream`).

---

## [0.3.0] - 2026-02-21

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
