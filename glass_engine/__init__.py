"""GlassInterface AI Engine — On-device assistive vision system."""

from glass_engine.sdk import GlassInterfaceSDK
from glass_engine.config import GlassConfig
from glass_engine.models import Detection, Alert, FrameResult

__version__ = "0.1.0"
__all__ = ["GlassInterfaceSDK", "GlassConfig", "Detection", "Alert", "FrameResult"]
