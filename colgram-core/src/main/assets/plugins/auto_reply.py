# title: Auto Reply
# name: Replies to private messages while you are away
# author: Colgram
# version: 1.0
# command: autoreply

"""Auto Reply for Colgram.

Replies once per sender with a canned message while auto-reply is enabled.
Deliberately rate-limited to one reply per sender per cooldown window, so a
fast typist cannot turn this into an accidental flood.

Usage in any chat:
    .autoreply on <message>   enable with a custom reply
    .autoreply off            disable
    .autoreply status         show current state
"""

import time

_COOLDOWN_SECONDS = 300
_state = {
    "enabled": False,
    "reply": "I'm away right now — I'll get back to you when I'm back.",
    "last_reply": {},
}


def _is_private(dialog_id):
    """Telegram user ids are positive; groups/channels are negative."""
    return dialog_id > 0


def on_message(dialog_id, text):
    """Return a reply string to send, or None to stay silent."""
    if not _state["enabled"]:
        return None
    if not _is_private(dialog_id):
        return None

    now = time.time()
    last = _state["last_reply"].get(dialog_id, 0)
    if now - last < _COOLDOWN_SECONDS:
        return None

    _state["last_reply"][dialog_id] = now
    return _state["reply"]


def on_command(dialog_id, args):
    args = (args or "").strip()

    if not args or args.lower() == "status":
        return (
            "Auto reply: %s\nCooldown: %ds per sender\nReplies sent: %d\nMessage: %s"
            % (
                "ON" if _state["enabled"] else "OFF",
                _COOLDOWN_SECONDS,
                len(_state["last_reply"]),
                _state["reply"],
            )
        )

    lowered = args.lower()
    if lowered == "off":
        _state["enabled"] = False
        return "Auto reply is OFF."

    if lowered.startswith("on"):
        custom = args[2:].strip()
        if custom:
            _state["reply"] = custom
        _state["enabled"] = True
        _state["last_reply"].clear()
        return "Auto reply is ON.\nMessage: %s" % _state["reply"]

    return "Usage: .autoreply on <message> | .autoreply off | .autoreply status"
