# GlassInterface

**On-device assistive vision for the visually impaired** — real-time object detection, face recognition, voice assistant, distance estimation, collision warnings, and spoken navigation alerts, running entirely on your Android phone.

> [!NOTE] 
> **Looking for the Python server & SDK?**
> The server-based Python architecture (`FastAPI`, `uvicorn`, `BoT-SORT`, `Ultralytics`) has been moved to the legacy branch:
> [👉 View `ai-integration` branch](https://github.com/AnuranjanJain/GlassInterface/tree/ai-integration)

## How It Works

```
Camera Frame → YOLOv8s TFLite + ML Kit Face Detection → Distance Estimator → Centroid Tracker → Risk Scorer → Overlay + TTS
```

1. **Detect** — YOLOv8s runs on-device via TensorFlow Lite (CPU/NNAPI) for objects, ML Kit for faces
2. **Recognize** — Detected faces are matched against saved embeddings using cosine similarity
3. **Estimate Distance** — Bounding box height → approximate distance in metres
4. **Track** — Centroid-based tracker assigns persistent IDs and estimates velocity
5. **Score Risk** — Objects scored by distance, approach speed, and direction
6. **Alert** — Risk-colored bounding boxes on screen + spoken TTS warnings
7. **Listen** — Voice assistant processes spoken commands for saving and recalling memories

## Features

- **Blind-Safe Navigation**: Color-coded bounding boxes (🟢 safe → 🟡 info → 🟠 warning → 🔴 critical) with spoken alerts like *"Person approaching fast ahead, 2.3 metres! Move right."*
- **🆕 Voice Assistant**: Push-to-talk microphone button. Say commands like *"what do you see"*, *"save face as John"*, *"save location"*, *"help"*.
- **🆕 Face Recognition**: On-device face detection + re-identification using ML Kit. Save faces and recognize them later — all offline.
- **🆕 Memory System**: Save faces, objects, contacts, locations, timestamps, and notes using voice commands. Browse everything in the Memory screen.
- **Object Tracking**: Persistent IDs across frames with velocity estimation — knows when objects are approaching vs stationary.
- **On-Device Inference**: No server, no Wi-Fi needed. Runs entirely on your phone's CPU/NNAPI.
- **Dual Camera**: Use the phone camera (CameraX) or a wearable ESP32-CAM via Wi-Fi WebSocket/MJPEG stream.
- **Adaptive Alerts**: Scene modes (INDOOR/OUTDOOR), configurable sensitivity, cooldown timers, and TTS speech rate.

## Voice Commands

| Command | What It Does |
|---------|-------------|
| "save face" / "save face as John" | Saves the detected face with a name |
| "who is this?" | Identifies the face against saved faces |
| "save this" | Saves the top detected object |
| "save contact [name]" | Saves a contact |
| "save location" | Saves current GPS coordinates |
| "save time" | Saves a timestamp with optional note |
| "save note [text]" | Saves a free-form note |
| "what do you see?" | Describes all detected objects aloud |
| "list memories" | Reads a summary of all saved items |
| "what time is it?" | Reads the current date and time |
| "battery level" | Reads device battery percentage |
| "navigate to [place]" | Gemini-powered safety-aware walking directions |
| "repeat that" | Replays the last spoken response |
| "stop" / "be quiet" | Silences TTS immediately |
| "ask Gemini [question]" | Free-form Q&A with Gemini AI |
| *any other question* | Automatically routed to Gemini with scene context |
| "help" | Lists available commands |

### Gemini Setup
1. Get a free API key from [aistudio.google.com](https://aistudio.google.com)
2. Open GlassInterface → **Settings** → paste key in **Gemini API Key** field
3. Done! Ask anything — the assistant is now context-aware

## Installation

### Option 1: Pre-compiled APK
1. Download `GlassInterface-V0.6.0.apk` from [Releases](https://github.com/AnuranjanJain/GlassInterface/releases).
2. Transfer to your Android device and install (enable "Install from unknown sources").

### Option 2: Build from Source
```bash
git clone https://github.com/AnuranjanJain/GlassInterface.git
cd GlassInterface
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

## ESP32-CAM Wearable Setup

GlassInterface can act as a wearable headset processor using an ESP32-CAM:

1. Flash ESP32-CAM with a standard `CameraWebServer` sketch.
2. Connect phone to the ESP32's Wi-Fi AP (or same local network).
3. Open GlassInterface → **Settings** → Enable **Use External Wi-Fi Camera**.
4. Enter the stream URL (default: `ws://192.168.4.1/Camera`).

## Project Structure

```text
app/                    # MainActivity, MainViewModel, Jetpack Compose UI
core/                   
├── ai-bridge/          # LocalAIEngine, FaceRecognitionEngine, CentroidTracker, RiskScorer
├── camera/             # CameraX FrameProvider, MjpegInputStream
├── common/             # AlertConfig, BoundingBox, DetectionResult data models
├── memory/             # Room database, entities, DAO, MemoryRepository
├── overlay/            # BoundingBoxOverlay (risk-colored Canvas drawing)
├── tts/                # TTSManager (cooldown-aware Text-to-Speech)
└── voice/              # VoiceInputManager, VoiceCommandParser, VoiceCommand
feature/
├── memory/             # Memory browser UI (faces, objects, contacts, locations, notes)
└── settings/           # Settings UI, DataStore repository
```

## Tech Stack

| Component | Technology |
|---|---|
| AI Model | YOLOv8s → TensorFlow Lite |
| Face Detection | Google ML Kit Face Detection |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Dagger Hilt |
| Database | Room (SQLite) |
| Camera | CameraX + WebSocket/MJPEG streaming |
| Voice | Android SpeechRecognizer |
| Audio | Android TTS |
| Location | Play Services Location |
| Build | Gradle (Kotlin DSL) |