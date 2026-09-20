"""Colgram adapter for the anti-spam userbot ported from Kleisym/spamblock-mailing.

WHY THIS LAYER EXISTS
---------------------
The upstream bridge (spamblock_bridge.py) expects a Java callback object with an
exteraGram/Android shape:

    onStatusChange(text, running) / onLoggedIn(username, id) / onError(text)
    requestCode() -> str / requestPassword() -> str

Passing a Java object into Chaquopy from colgram-core is awkward: colgram-core has no
Chaquopy on its classpath, so it cannot construct a Java object the Python side will
recognise as implementing that interface. Passing a java.lang.reflect.Proxy works only
for methods Chaquopy can see, and the string-returning code/password requests would have
to block a Python thread waiting on a UI dialog.

Instead the flow is inverted: this module owns a plain-Python callback and exposes a
poll-able status record. Colgram reads the status through the normal
ColgramPythonEngine code-execution path and pushes credentials in through
`submit_credentials()`. The userbot thread never blocks on Java.

STATE IS PROCESS-GLOBAL AND MUTABLE
-----------------------------------
The bridge runs on its own thread with its own asyncio loop, so status writes must be
safe from that thread. A plain dict plus a lock is enough: readers only ever take a
consistent snapshot, and no operation spans more than one field.
"""

import threading
import traceback

import spamblock_bridge as _bridge

_lock = threading.Lock()

# Status record read by Colgram. `phase` is the machine-readable field; `text` is what the
# user sees. Keeping both means the UI never has to pattern-match a localised phrase.
_status = {
    "running": False,
    "phase": "stopped",   # stopped | connecting | waiting_code | waiting_password | running | error
    "text": "Остановлен",
    "username": "",
    "user_id": 0,
    "error": "",
}

# Credentials are handed over exactly once per request. The bridge's providers block until
# a value appears, so these are set before start and cleared after each successful read.
#
# `_pending_event` is a *wakeup* signal, not a handshake. It is set whenever a credential
# arrives or a provider parks, and the provider loop clears it itself while holding the
# lock before going back to sleep. Clearing it here in submit_credentials would race with
# the provider and could drop the wakeup, so don't.
_pending = {"code": None, "password": None}
_pending_event = threading.Event()


def _set(**fields):
    with _lock:
        _status.update(fields)


def get_status():
    """Snapshot the current status as a JSON-serialisable dict."""
    with _lock:
        return dict(_status)


def submit_credentials(code=None, password=None):
    """Supply the login code and/or 2FA password that a provider is waiting on.

    Called from Colgram's main thread (via the Python engine) while the bridge thread is
    parked inside requestCode()/requestPassword(). Returns True if a provider was parked.
    """
    with _lock:
        waiting = _pending_event.is_set()
        if code is not None:
            _pending["code"] = str(code).strip()
        if password is not None:
            _pending["password"] = str(password).strip()
    # Set (never clear) the wakeup. A code typed by the user can legitimately arrive a
    # moment before the bridge reaches its request, so the value is kept either way.
    _pending_event.set()
    return waiting


class _ColgramCallback:
    """Plain-Python implementation of the callback the upstream bridge expects."""

    def onStatusChange(self, text, running):
        # Map the free-text status onto a stable phase so the UI can stop guessing.
        phase = "running" if running else "stopped"
        low = (text or "").lower()
        if "код" in low:
            phase = "waiting_code"
        elif "2fa" in low or "парол" in low:
            phase = "waiting_password"
        elif "подключ" in low or "восстановл" in low or "ожидание сети" in low:
            phase = "connecting"
        elif "ошибк" in low:
            phase = "error"
        _set(running=bool(running), phase=phase, text=str(text or ""))
        # When the bridge parks on a code/password request, unblock any waiter.
        if phase in ("waiting_code", "waiting_password"):
            _pending_event.set()

    def onLoggedIn(self, username, user_id):
        _set(username=str(username or ""), user_id=int(user_id or 0),
             phase="running", running=True)

    def onError(self, text):
        _set(phase="error", running=False, error=str(text or ""), text="Ошибка: " + str(text or ""))

    def _take(self, key):
        """Block until a credential of `key` type is supplied, or time out.

        The wakeup event is cleared under the lock before sleeping, so a value written by
        submit_credentials between the check and the sleep cannot be lost.

        A timeout (rather than an indefinite wait) matters because the userbot thread is
        daemon-like: if the user never answers, we must not pin the interpreter forever.
        """
        deadline = 300.0
        step = 0.25
        waited = 0.0
        while waited < deadline:
            with _lock:
                val = _pending.get(key)
                if val:
                    _pending[key] = None
                    return str(val)
                # Nothing yet: clear the wakeup so the next wait actually blocks.
                _pending_event.clear()
            _pending_event.wait(step)
            waited += step
        return ""

    def requestCode(self):
        _set(phase="waiting_code", text="Ожидание кода из Telegram...", running=True)
        return self._take("code")

    def requestPassword(self):
        _set(phase="waiting_password", text="Ожидание пароля 2FA...", running=True)
        return self._take("password")


def start(api_id, api_hash, phone, password_2fa="", files_dir=""):
    """Start the userbot. Returns the status snapshot immediately.

    The upstream bridge blocks until the client is fully connected, so it is dispatched to
    a thread here; callers poll get_status() instead of waiting for a return value.
    """
    try:
        api_id = int(str(api_id).strip())
    except (TypeError, ValueError):
        _set(phase="error", running=False, error="api_id должен быть числом",
             text="api_id должен быть числом")
        return get_status()

    if not api_hash or not phone:
        _set(phase="error", running=False, error="api_hash и phone обязательны",
             text="api_hash и phone обязательны")
        return get_status()

    _set(phase="connecting", running=True, error="", text="Запуск...")

    def _run():
        try:
            _bridge.start_bot(str(api_id), str(api_hash), str(phone), str(password_2fa or ""),
                              str(files_dir or ""), _ColgramCallback())
        except Exception as e:
            traceback.print_exc()
            _set(phase="error", running=False, error="%s: %s" % (type(e).__name__, e),
                 text="Ошибка: %s" % e)

    t = threading.Thread(target=_run, name="ColgramAntiSpam", daemon=True)
    t.start()
    return get_status()


def stop():
    try:
        _bridge.stop_bot()
    except Exception as e:
        traceback.print_exc()
        _set(phase="error", error="%s: %s" % (type(e).__name__, e))
        return get_status()
    _set(phase="stopped", running=False, text="Остановлен")
    return get_status()


def is_running():
    try:
        return bool(_bridge.is_running())
    except Exception:
        return bool(get_status().get("running"))
