# title: Chat Exporter
# name: Exports chat history to a portable HTML file
# author: Colgram
# version: 1.0
# command: export

"""Chat Exporter for Colgram.

Renders the current chat to a single self-contained HTML file with inline
styles, so it opens correctly in any browser with no external assets and no
network access.

Usage in any chat:
    .export start   begin collecting messages
    .export save    write the collected transcript to HTML
    .export clear   discard what has been collected
"""

import html
import os
import time

_state = {"messages": [], "started": False}


def _sandbox_dir():
    base = os.environ.get("COLGRAM_DATA_DIR") or os.getcwd()
    out = os.path.join(base, "exports")
    try:
        os.makedirs(out, exist_ok=True)
    except OSError:
        out = os.getcwd()
    return out


def on_message(dialog_id, text):
    """Collect messages while an export is in progress."""
    if _state["started"]:
        _state["messages"].append((time.time(), dialog_id, text))


def _render(dialog_id):
    rows = []
    for ts, dlg, text in _state["messages"]:
        stamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts))
        rows.append(
            '<div class="m"><div class="t">%s</div><div class="x">%s</div></div>'
            % (html.escape(stamp), html.escape(str(text)))
        )

    body = "\n".join(rows) if rows else '<div class="m"><div class="x">No messages.</div></div>'
    title = "Colgram export - dialog %s" % dialog_id

    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title>
<style>
body{margin:0;padding:24px;background:#f4f4f5;color:#18181b;
     font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
h1{font-size:17px;font-weight:500;margin:0 0 4px}
.sub{color:#71717a;font-size:12px;margin-bottom:20px}
.m{background:#fff;border:1px solid #e4e4e7;border-radius:10px;
   padding:12px 14px;margin-bottom:8px}
.t{color:#71717a;font-size:11px;margin-bottom:4px}
.x{white-space:pre-wrap;word-wrap:break-word}
</style>
</head>
<body>
<h1>%s</h1>
<div class="sub">%d messages</div>
%s
</body>
</html>
""" % (html.escape(title), html.escape(title), len(_state["messages"]), body)


def on_command(dialog_id, args):
    args = (args or "").strip().lower()

    if args == "start":
        _state["messages"] = []
        _state["started"] = True
        return "Export started. New messages will be collected. Send `.export save` when done."

    if args == "clear":
        _state["messages"] = []
        _state["started"] = False
        return "Export buffer cleared."

    if args in ("save", "stop", ""):
        _state["started"] = False
        if not _state["messages"]:
            return "Nothing collected yet. Send `.export start` first."
        name = "chat_%s_%d.html" % (abs(dialog_id), int(time.time()))
        path = os.path.join(_sandbox_dir(), name)
        try:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(_render(dialog_id))
        except OSError as exc:
            return "Could not write export: %s" % exc
        return "Exported %d messages to:\n%s" % (len(_state["messages"]), path)

    return "Usage: .export start | .export save | .export clear"
