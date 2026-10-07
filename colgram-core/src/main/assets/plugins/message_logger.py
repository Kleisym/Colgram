# title: Message Logger
# name: Logs incoming and outgoing messages to disk
# author: Colgram
# version: 1.0
# command: log

"""Message Logger for Colgram.

Writes every message that passes through the hook to a JSONL file inside the
Colgram storage sandbox, so the log survives app updates and is never written
to shared external storage.

Usage in any chat:
    .log on      start logging
    .log off     stop logging
    .log status  show where the log lives and how many lines it holds
"""

import json
import os
import time

_LOG_NAME = "message_log.jsonl"
_state = {"enabled": False, "path": None, "count": 0}


def _log_path():
    """Prefer the Colgram sandbox the Java side exposes; fall back to CWD."""
    base = os.environ.get("COLGRAM_DATA_DIR") or os.getcwd()
    try:
        os.makedirs(base, exist_ok=True)
    except OSError:
        base = os.getcwd()
    return os.path.join(base, _LOG_NAME)


def _record(dialog_id, text):
    if not _state["enabled"]:
        return
    if _state["path"] is None:
        _state["path"] = _log_path()
    entry = {
        "ts": time.time(),
        "dialog_id": dialog_id,
        "text": text,
    }
    try:
        with open(_state["path"], "a", encoding="utf-8") as fh:
            fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
        _state["count"] += 1
    except OSError:
        pass


def on_message(dialog_id, text):
    """Called by the Colgram hook for every incoming/outgoing message."""
    _record(dialog_id, text)


def on_command(dialog_id, args):
    args = (args or "").strip().lower()

    if args in ("on", "start", ""):
        _state["enabled"] = True
        _state["path"] = _log_path()
        return "Message logger is ON.\nWriting to: %s" % _state["path"]

    if args in ("off", "stop"):
        _state["enabled"] = False
        return "Message logger is OFF. Recorded %d entries this session." % _state["count"]

    if args == "status":
        path = _state["path"] or _log_path()
        exists = os.path.exists(path)
        size = os.path.getsize(path) if exists else 0
        return (
            "Logger: %s\nPath: %s\nFile exists: %s\nSize: %d bytes\nSession entries: %d"
            % ("ON" if _state["enabled"] else "OFF", path, exists, size, _state["count"])
        )

    return "Usage: .log on | .log off | .log status"
