#!/usr/bin/env python3
"""FastAPI server for integrating the GlassInterface engine with mobile apps.

Endpoints:
    POST /process   — Accept a base64-encoded frame, return FrameResult JSON.
    WS   /stream    — WebSocket for real-time frame streaming.
    GET  /health    — Health check.

Run:
    uvicorn server:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import base64
import io
import time

import cv2
import numpy as np
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File
from fastapi.responses import JSONResponse

from glass_engine.sdk import GlassInterfaceSDK
from glass_engine.config import GlassConfig

app = FastAPI(
    title="GlassInterface AI Engine",
    description="On-device assistive vision API",
    version="0.1.0",
)

# Global SDK instance
sdk = GlassInterfaceSDK()


# ── REST Endpoints ───────────────────────────────────────────────────

@app.get("/health")
async def health():
    """Health check."""
    return {"status": "ok", "engine_version": "0.1.0"}


@app.post("/process")
async def process_frame(file: UploadFile = File(...)):
    """Process a single frame uploaded as an image file.

    Returns the full FrameResult JSON.
    """
    contents = await file.read()
    nparr = np.frombuffer(contents, np.uint8)
    frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

    if frame is None:
        return JSONResponse(
            status_code=400,
            content={"error": "Invalid image data"},
        )

    result = sdk.process_frame(frame)
    return result.to_dict()


@app.post("/process_base64")
async def process_frame_base64(payload: dict):
    """Process a base64-encoded frame.

    Expected JSON body:
        { "frame": "<base64-encoded-image>" }
    """
    b64_data = payload.get("frame", "")
    try:
        img_bytes = base64.b64decode(b64_data)
        nparr = np.frombuffer(img_bytes, np.uint8)
        frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    except Exception as e:
        return JSONResponse(
            status_code=400,
            content={"error": f"Failed to decode frame: {e}"},
        )

    if frame is None:
        return JSONResponse(
            status_code=400,
            content={"error": "Invalid image data"},
        )

    result = sdk.process_frame(frame)
    return result.to_dict()


# ── WebSocket Endpoint ───────────────────────────────────────────────

@app.websocket("/stream")
async def websocket_stream(websocket: WebSocket):
    """Real-time frame streaming via WebSocket.

    Client sends raw JPEG bytes per message, server replies with
    FrameResult JSON.
    """
    await websocket.accept()
    try:
        while True:
            data = await websocket.receive_bytes()
            nparr = np.frombuffer(data, np.uint8)
            frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

            if frame is None:
                await websocket.send_json({"error": "Invalid frame"})
                continue

            result = sdk.process_frame(frame)
            await websocket.send_json(result.to_dict())
    except WebSocketDisconnect:
        pass


# ── Lifecycle ────────────────────────────────────────────────────────

@app.on_event("shutdown")
def shutdown():
    sdk.release()
