# GlassInterface AI Engine

**On-device assistive vision system** — detects objects, estimates distance, and generates safety-critical alerts in real-time.

## Architecture

```
Frame → ObjectDetector (YOLOv8n) → DistanceEstimator → ReasoningEngine → AlertManager → JSON
```

## Quick Start

```bash
# Install dependencies
pip install -r requirements.txt

# Run tests (no GPU needed)
pytest tests/ -v

# Live webcam demo
python demo.py

# Start API server
uvicorn server:app --host 0.0.0.0 --port 8000
```

## Usage (Python)

```python
from glass_engine import GlassInterfaceSDK
import cv2

sdk = GlassInterfaceSDK()
frame = cv2.imread("test.jpg")
result = sdk.process_frame(frame)

print(result.to_json())
# {
#   "processing_time_ms": 38.2,
#   "detections": [...],
#   "alerts": [{"priority": "WARNING", "message": "Person ahead, about 2 metres", ...}]
# }

sdk.release()
```

## Configuration

Override any threshold via `GlassConfig`:

```python
from glass_engine import GlassInterfaceSDK, GlassConfig

config = GlassConfig(
    CONFIDENCE_THRESHOLD=0.60,
    CRITICAL_DISTANCE=2.0,
    COOLDOWN_CRITICAL=5.0,
)
sdk = GlassInterfaceSDK(config=config)
```

## Reasoning Rules

| # | Rule | Priority | Condition |
|---|------|----------|-----------|
| 1 | Collision imminent | **CRITICAL** | Safety label + <1.5m + CENTER |
| 2 | Close approach | **WARNING** | Safety label + <3.0m |
| 3 | Nearby object | INFO | Any label + <2.0m |
| 4 | Suppress | — | Everything else |

**Safety labels:** person, car, bicycle, motorcycle, bus, truck, stairs

## Project Structure

```
glass_engine/
├── sdk.py              # Public facade
├── config.py           # All thresholds
├── models.py           # Detection, Alert, FrameResult
├── detection/detector.py   # YOLOv8n wrapper
├── distance/estimator.py   # BBox distance heuristic
├── reasoning/engine.py     # Priority rules
└── alert/manager.py        # Cooldown & dedup
```

## Future Roadmap

1. Scene context accumulator across frames
2. On-device SLM integration (Gemma 2B / Phi-3)
3. LLM-based intent parsing for voice commands
4. User preference learning