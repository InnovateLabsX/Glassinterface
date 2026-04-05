# Changelog

All notable changes to this project will be documented in this file.

## [0.6.8] - 2026-04-06

### Fixed
- **Assistant TTS Interruption**: Fixed a critical bug in continuous listening mode where automated background spatial alerts (e.g. "person ahead") would forcefully cancel and flush the TTS queue while the Gemini Voice Assistant was in the middle of answering a conversational query. The assistant now temporarily halts incoming routine spatial reports until it finishes speaking.

## [0.6.7] - 2026-04-06

### Fixed
- **Face Recognition Concurrency**: Fixed a critical race condition where the continuous inference loop overwrote the `lastFrame` while the user was speaking the "save face" command, leading to corrupted saves. Explicit `captureFrame()` clones are now generated for voice intents.
- **ESP32 Camera ML Kit Compatibility**: Fixed ML Kit Face Detection failing silently on external ESP32-CAM feeds by re-routing the WebSocket binary decoder from `RGB_565` to universally-compatible `ARGB_8888` bitmaps.
- **Corrupted Face Matching**: Fixed Face Embeddings saving structurally broken arrays (`FloatArray(128)`) when no face was present. Empty fallbacks now correctly map to a dimensionally accurate zero-state `FloatArray(20)`.
- **Landmark Tolerance Constraints**: Relaxed ML Kit feature-extraction from a strict 6-landmark minimum down to a 3-landmark minimum, allowing robust face identification off low-resolution wearable camera angles. Also fixed variable-length vector crashes by zero-padding all missing landmarks implicitly to enforce strict `EMBEDDING_SIZE=20` arrays.

---

## [0.6.6] - 2026-04-06

### Fixed
- **API 429 Spam Protection**: Fixed a logic flaw where brief ambient noises ("umm", "ah") under 3 words were being forwarded as unrecognized text directly to Google Gemini's servers, rapidly breaching the 15-RPM Free-Tier limit. Small non-question sounds now trigger a native "I didn't quite catch that" response rather than engaging the AI.
- **Wake Word Precision Chopping**: Fixed a destructive bug where substring searches for the wake word "hi" were intercepting other words (e.g. "t**hi**s"), incorrectly splitting sentences in half. Upgraded wake word tracking to use strict lexical Token Regex Boundaries `\b(word)\b`.
- **Lexical Aliasing Paths**: Corrected an alias mapping bug where "save this person" triggered Object Save instead of Face Save. 

### Added
- **Native Wake Word "Hey"**: Explicitly mapped `"hey"` and `"ok"` as valid wake words alongside "hey gi", drastically improving casual trigger rates on headsets.
- **In-App Commands Menu**: Added a native `View Voice Commands Guide` overlay inside the Settings screen, providing explicit examples of conversational aliasing structures and capabilities natively to the user.

---

## [0.6.5] - 2026-04-06

### Added
- **Gemini Spatial Recall Integration**: Upgraded the `MemoryRepository` to natively supply raw text snapshots of your most recent Notes, Objects, and Locations to the Gemini AI API.
- **Conversational Object Finding**: You can now ask questions like "Where did I put the keys?" or "Find my jacket." Unrecognized memory lookup queries are safely forwarded to Gemini which dynamically interprets your memory database to provide walking navigation advice toward the object.
- **Graceful Capture Fallbacks**: Previously, if ML Kit couldn't isolate a bounding box or face when you explicitly tried to save one, the system would reject the command entirely. It now gracefully steps back to capturing a raw standard camera screenshot instead and logs it as standard scene context.
- **Robust Location Fallbacks**: GPS requests failing in locations without satellite tracking no longer crash or exit—they gracefully save initialized zero-coordinate origin bookmarks with your desired label.

---

## [0.6.4] - 2026-04-06

### Added
- **Always-On Wake Word**: Say "Hi" or "Hey Gi" anytime to activate the assistant without touching the device. Full hands-free voice continuous listening.

---

## [0.6.3] - 2026-04-05

### Added
- **True Hands-Free Accessibility**: Built specifically for visually impaired users. You can now activate the voice assistant without hunting for the microphone button on the screen.
- **Shake-to-Wake**: Shake the phone twice rapidly to activate the microphone. (Enabled in Settings).
- **Proximity Wave**: Wave your hand over the top of the phone to talk. (Enabled in Settings).
- **Bluetooth Headset Hooks**: Intercepts the *Play/Pause* or *Call* button on Bluetooth earbuds to trigger the voice assistant.
- **Tap Anywhere**: The entire screen acts as a giant microphone button.

---

## [0.6.2] - 2026-04-05

### Added
- **Gemini AI Conversational Assistant**: Free-form Q&A powered by Google Gemini 2.0 Flash via REST API. Ask anything — unrecognized commands are automatically routed to Gemini with full scene context (what the camera sees) and saved memories.
- **7 New Voice Commands**: "what time is it", "battery level", "repeat that", "stop/be quiet", "read this" (placeholder), "navigate to [place]", "ask Gemini [question]".
- **Scene-Aware Q&A**: Gemini receives real-time object detection data so it can answer questions like "is it safe to cross?" or "what's in front of me?".
- **Multi-Turn Conversations**: 10-turn conversation history for context-aware follow-up questions.
- **Gemini API Key Setting**: New text field in Settings screen for the API key (free from aistudio.google.com).
- **Navigation Advice**: "Navigate to [place]" routes to Gemini with scene context for safety-aware walking directions.
- **Battery & Time**: Offline commands for device battery level and current date/time.
- **Repeat & Stop**: "Repeat that" replays last response, "stop/be quiet" silences TTS immediately.

### Changed
- **VoiceCommandParser**: Unrecognized speech now routes to Gemini instead of error message. Added safety question detection ("is it safe", "should I", etc.).
- **Help Command**: Updated to list all 18+ available commands including Gemini Q&A.

---

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
