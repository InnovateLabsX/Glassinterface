# GlassInterface

**On-device assistive vision for the visually impaired** — real-time object detection, distance estimation, collision warnings, and spoken navigation alerts, running entirely on your Android phone.

> [!NOTE] 
> **Looking for the Python server & SDK?**
> The server-based Python architecture (`FastAPI`, `uvicorn`, `BoT-SORT`, `Ultralytics`) has been moved to the legacy branch:
> [👉 View `ai-integration` branch](https://github.com/AnuranjanJain/GlassInterface/tree/ai-integration)

## How It Works

```
Camera Frame → YOLOv8s TFLite → Distance Estimator → Centroid Tracker → Risk Scorer → Overlay + TTS
```

1. **Detect** — YOLOv8s runs on-device via TensorFlow Lite (CPU/NNAPI)
2. **Estimate Distance** — Bounding box height → approximate distance in metres
3. **Track** — Centroid-based tracker assigns persistent IDs and estimates velocity
4. **Score Risk** — Objects scored by distance, approach speed, and direction
5. **Alert** — Risk-colored bounding boxes on screen + spoken TTS warnings

## Features

- **Blind-Safe Navigation**: Color-coded bounding boxes (🟢 safe → 🟡 info → 🟠 warning → 🔴 critical) with spoken alerts like *"Person approaching fast ahead, 2.3 metres! Move right."*
- **Object Tracking**: Persistent IDs across frames with velocity estimation — knows when objects are approaching vs stationary.
- **On-Device Inference**: No server, no Wi-Fi needed. Runs entirely on your phone's CPU/NNAPI.
- **Dual Camera**: Use the phone camera (CameraX) or a wearable ESP32-CAM via Wi-Fi MJPEG stream.
- **Adaptive Alerts**: Scene modes (INDOOR/OUTDOOR), configurable sensitivity, cooldown timers, and TTS speech rate.

## Installation

### Option 1: Pre-compiled APK
1. Download the `.apk` from [Releases](https://github.com/AnuranjanJain/GlassInterface/releases).
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
4. Enter the stream URL (default: `http://192.168.4.1:81/stream`).

## Project Structure

```text
app/                    # MainActivity, MainViewModel, Jetpack Compose UI
core/                   
├── ai-bridge/          # LocalAIEngine, CentroidTracker, RiskScorer, DistanceEstimator
├── camera/             # CameraX FrameProvider, MjpegInputStream
├── common/             # AlertConfig, BoundingBox, DetectionResult data models
├── overlay/            # BoundingBoxOverlay (risk-colored Canvas drawing)
└── tts/                # TTSManager (cooldown-aware Text-to-Speech)
feature/
└── settings/           # Settings UI, DataStore repository
```

## Tech Stack

| Component | Technology |
|---|---|
| AI Model | YOLOv8s → TensorFlow Lite |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Dagger Hilt |
| Camera | CameraX + MJPEG streaming |
| Audio | Android TTS |
| Build | Gradle (Kotlin DSL) |