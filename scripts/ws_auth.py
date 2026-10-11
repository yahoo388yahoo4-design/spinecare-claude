"""Shared helper: open the SpineCare WebSocket with HTTP Basic auth taken from
SPINECARE_SERVER_USER / SPINECARE_SERVER_PASSWORD (same variables as the server)."""
import base64
import inspect
import os

import websockets


def connect(uri: str, **kw):
    user = os.environ.get("SPINECARE_SERVER_USER", "")
    password = os.environ.get("SPINECARE_SERVER_PASSWORD", "")
    headers = {}
    if user:
        headers["Authorization"] = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
    # websockets >= 14 renamed extra_headers -> additional_headers
    key = "additional_headers" if "additional_headers" in inspect.signature(websockets.connect).parameters else "extra_headers"
    return websockets.connect(uri, **{key: headers}, **kw)
