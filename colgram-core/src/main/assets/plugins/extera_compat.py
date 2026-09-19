# title: exteraGram Compatibility Shim
# name: Provides a partial exteraPlugins API so exteraGram plugins can run
# author: Colgram
# version: 1.0
# command: ecompat

"""exteraGram compatibility shim for Colgram.

WHY THIS EXISTS
---------------
exteraGram plugins are written against `exteraPlugins`, a module that only
exists inside exteraGram and exposes a large surface: MTProto client access,
raw dialog objects, custom cell factories, UI hooks. That surface cannot be
reproduced here, and claiming otherwise would be dishonest.

What CAN be reproduced is the subset that most published plugins actually use:
command registration, sending and editing messages, dialog ids, simple storage,
and scheduling. This shim implements that subset. Plugins that only do those
things will run unmodified. Plugins that touch raw MTProto, custom UI cells, or
client internals will import successfully but fail at the specific call, and
they fail loudly with a clear message rather than silently doing nothing.

USAGE IN A PLUGIN
-----------------
    import exteraPlugins
    exteraPlugins.add_command("hello", handler, "says hi")

Colgram installs this shim into sys.modules as `exteraPlugins` before any
plugin is imported, so a plain `import exteraPlugins` resolves to it.
"""

import time
import sys
import threading
import types as _types

__all__ = [
    "add_command", "on_command", "send_message", "edit_message",
    "delete_message", "get_dialog_id", "store", "schedule",
    "set_context", "set_bridge", "run_command", "list_commands",
    "on_command_handler", "on_command_info", "UnsupportedFeature",
]

# Populated by the Java side via set_context().
_context = {
    "send": None,      # callable(dialog_id, text)
    "edit": None,      # callable(dialog_id, msg_id, text)
    "delete": None,    # callable(dialog_id, msg_id)
    "dialog_id": 0,
}

# command -> (handler, description)
_commands = {}

# Simple persistent-ish key/value store (process lifetime).
_store = {}

# background timers we have started, so they can be cancelled
_timers = {}


class UnsupportedFeature(NotImplementedError):
    """Raised when a plugin uses an exteraGram feature Colgram cannot provide."""


def set_context(send=None, edit=None, delete=None):
    """Called by the Colgram Java bridge to wire real actions in."""
    if send is not None:
        _context["send"] = send
    if edit is not None:
        _context["edit"] = edit
    if delete is not None:
        _context["delete"] = delete


def set_bridge(send, edit, delete):
    """Install the real Java-backed actions.

    Separate from set_context() because the bridge is installed once at command
    dispatch time with three concrete callables, while set_context() is a loose
    partial setter that plugins and tests may poke at individually.
    """
    _context["send"] = send
    _context["edit"] = edit
    _context["delete"] = delete


def add_command(command, handler, description=""):
    """Register a dot-command. This is the most-used exteraGram entry point."""
    cmd = str(command).lstrip(".").lower()
    _commands[cmd] = (handler, description or "")
    return cmd


def on_command(command, description=""):
    """Decorator form: @on_command('hello')"""
    def decorator(fn):
        add_command(command, fn, description)
        return fn
    return decorator


def run_command(command, args, dialog_id=None):
    """Invoked by Colgram when a registered command is typed. Internal."""
    entry = _commands.get(str(command).lstrip(".").lower())
    if entry is None:
        return None
    handler = entry[0]
    if dialog_id is not None:
        _context["dialog_id"] = dialog_id
    try:
        return handler(args)
    except TypeError:
        # Tolerate handlers that take no arguments.
        return handler()


def list_commands():
    return sorted(_commands.keys())


def send_message(text, dialog_id=None):
    fn = _context.get("send")
    if fn is None:
        raise UnsupportedFeature(
            "send_message needs the Colgram bridge; call set_context() first")
    dlg = dialog_id if dialog_id is not None else _context.get("dialog_id", 0)
    return fn(dlg, str(text))


def edit_message(msg_id, text, dialog_id=None):
    fn = _context.get("edit")
    if fn is None:
        raise UnsupportedFeature(
            "edit_message is not available in this Colgram build")
    dlg = dialog_id if dialog_id is not None else _context.get("dialog_id", 0)
    return fn(dlg, int(msg_id), str(text))


def delete_message(msg_id, dialog_id=None):
    fn = _context.get("delete")
    if fn is None:
        raise UnsupportedFeature(
            "delete_message is not available in this Colgram build")
    dlg = dialog_id if dialog_id is not None else _context.get("dialog_id", 0)
    return fn(dlg, int(msg_id))


def get_dialog_id():
    return _context.get("dialog_id", 0)


class _Store:
    def get(self, key, default=None):
        return _store.get(key, default)

    def put(self, key, value):
        _store[key] = value
        return True

    def delete(self, key):
        return _store.pop(key, None) is not None

    def all(self):
        return dict(_store)


store = _Store()


def schedule(seconds, fn, *args, **kwargs):
    """Run fn after `seconds`. Returns a handle accepted by cancel()."""
    handle = {"cancelled": False}

    def _run():
        deadline = time.time() + float(seconds)
        while time.time() < deadline:
            if handle["cancelled"]:
                return
            time.sleep(min(0.25, max(0.0, deadline - time.time())))
        if not handle["cancelled"]:
            try:
                fn(*args, **kwargs)
            except Exception:
                pass

    t = threading.Thread(target=_run, daemon=True)
    handle["thread"] = t
    _timers[id(handle)] = handle
    t.start()
    return handle


def cancel(handle):
    if isinstance(handle, dict):
        handle["cancelled"] = True
        _timers.pop(id(handle), None)
        return True
    return False


# --- Explicitly unsupported surface -----------------------------------------
# These exist so that importing a plugin which references them does not fail at
# import time. Calling them raises with a clear message instead of silently
# returning None, which is what made broken plugins hard to diagnose before.

def _unsupported(name):
    def _raise(*_a, **_kw):
        raise UnsupportedFeature(
            "%s requires exteraGram's internal MTProto/UI layer and is not "
            "available in Colgram. Rewrite this plugin against Colgram's "
            "on_command()/on_message() API instead." % name)
    return _raise


class _UnsupportedNamespace:
    """Attribute access on unknown exteraGram APIs fails loudly, not silently."""

    def __init__(self, ns_name):
        self._ns_name = ns_name

    def __getattr__(self, item):
        raise UnsupportedFeature(
            "exteraPlugins.%s.%s requires exteraGram's internal MTProto/UI layer and "
            "is not available in Colgram. Rewrite this plugin against Colgram's "
            "on_command() API. Supported top-level APIs: add_command, on_command, "
            "send_message, edit_message, delete_message, get_dialog_id, store, schedule."
            % (self._ns_name, item))


client = _UnsupportedNamespace("client")
MTProto = _UnsupportedNamespace("MTProto")
ui = _UnsupportedNamespace("ui")


def _module_getattr(name):
    """Make unknown top-level exteraPlugins.X fail as UnsupportedFeature, not
    AttributeError.

    A module cannot normally intercept its own attribute lookups, so the Java
    bootstrap assigns __class__ to a ModuleType subclass that routes misses here.
    Without this a plugin reaching for a newer exteraGram API dies with a bare
    AttributeError and the real reason is invisible.
    """
    if name.startswith("__") and name.endswith("__"):
        raise AttributeError(name)
    raise UnsupportedFeature(
        "exteraPlugins.%s is not implemented in Colgram. Supported: add_command, "
        "on_command, send_message, edit_message, delete_message, get_dialog_id, "
        "store, schedule." % name)


class _ShimModule(_types.ModuleType):
    """ModuleType that turns unknown attribute lookups into UnsupportedFeature."""

    def __getattr__(self, name):
        # Dataclass/pickle/copy machinery probes dunder attributes; those must stay
        # normal AttributeErrors or unrelated stdlib code breaks.
        if name.startswith("__") and name.endswith("__"):
            raise AttributeError(name)
        return _module_getattr(name)


def _install_shim_module_class():
    """Swap this module's class for _ShimModule so unknown lookups fail loudly.

    Two call orders must both work:
      * the Java bootstrap creates the module and inserts it into sys.modules BEFORE
        exec'ing this file (so the name resolves here), and
      * a test or a plain `import` may exec the source into a bare namespace first and
        register it afterwards.
    When the name is not resolvable yet, the caller (or the import system) is asked to
    call this again once registration has happened. Failing softly here is safe: it only
    downgrades an unknown-attribute error from UnsupportedFeature to AttributeError.
    """
    try:
        mod = sys.modules.get(__name__)
        if mod is not None:
            mod.__class__ = _ShimModule
    except Exception:
        pass


_install_shim_module_class()


def on_command_handler(dialog_id, cmd, args):
    """Entry point the Colgram Java bridge calls for a registered command."""
    if str(cmd).lstrip(".").lower() not in _commands:
        return False
    result = run_command(cmd, args, dialog_id)
    if result:
        send_message(result)
    return True


def on_command_info(dialog_id, args):
    reg = list_commands()
    if not reg:
        return ("exteraGram compatibility shim is active, but no plugin has "
                "registered a command yet.")
    return ("exteraGram compatibility shim active.\n"
            "Registered commands: " + ", ".join("." + c for c in reg))
