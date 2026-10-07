# title: Keyword Alerts
# name: Flags messages containing words you care about
# author: Colgram
# version: 1.0
# command: alert

"""Keyword Alerts for Colgram.

Scans every incoming message for a set of keywords and reports a match back
into the chat. Matching is case-insensitive and word-boundary aware, so "cat"
does not fire on "category".

Usage in any chat:
    .alert add <word>     add a keyword
    .alert remove <word>  remove a keyword
    .alert list           show all keywords
    .alert clear          remove every keyword
"""

import re

_state = {"keywords": []}


def _compile(word):
    return re.compile(r"\b%s\b" % re.escape(word), re.IGNORECASE)


def on_message(dialog_id, text):
    """Return an alert string when a keyword is present, otherwise None."""
    if not _state["keywords"] or not text:
        return None

    hits = [w for w in _state["keywords"] if _compile(w).search(str(text))]
    if not hits:
        return None

    return "Keyword alert: %s" % ", ".join(sorted(set(hits)))


def on_command(dialog_id, args):
    args = (args or "").strip()
    if not args:
        return "Usage: .alert add <word> | .alert remove <word> | .alert list | .alert clear"

    parts = args.split(None, 1)
    action = parts[0].lower()
    value = parts[1].strip() if len(parts) > 1 else ""

    if action == "add":
        if not value:
            return "Usage: .alert add <word>"
        lowered = value.lower()
        if any(w.lower() == lowered for w in _state["keywords"]):
            return "Already watching: %s" % value
        _state["keywords"].append(value)
        return "Now watching %d keyword(s): %s" % (
            len(_state["keywords"]), ", ".join(_state["keywords"]))

    if action == "remove":
        lowered = value.lower()
        before = len(_state["keywords"])
        _state["keywords"] = [w for w in _state["keywords"] if w.lower() != lowered]
        if len(_state["keywords"]) == before:
            return "Not found: %s" % value
        return "Removed. %d keyword(s) remain." % len(_state["keywords"])

    if action == "list":
        if not _state["keywords"]:
            return "No keywords set."
        return "Watching:\n" + "\n".join("- %s" % w for w in _state["keywords"])

    if action == "clear":
        _state["keywords"] = []
        return "All keywords cleared."

    return "Unknown action: %s" % action
