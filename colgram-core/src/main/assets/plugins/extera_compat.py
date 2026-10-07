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


# =====================================================================================
# exteraGram plugin modules: base_plugin, hook_utils, android_utils, client_utils
# =====================================================================================
#
# A real exteraGram .plugin file is plain Python (no archive, no JS) that begins with:
#
#     from base_plugin   import BasePlugin, MethodHook
#     from hook_utils    import find_class, get_private_field, set_private_field
#     from android_utils import log, run_on_ui_thread
#     from client_utils  import run_on_queue, get_last_fragment
#     from java import jarray, jclass          <- Chaquopy, already works
#
# Without these four modules the import fails immediately and NO exteraGram plugin can
# run at all. This section synthesises them into sys.modules, so `from base_plugin import
# BasePlugin` resolves.
#
# What is real here, built on Chaquopy's Java bridge:
#   find_class, get_private_field, set_private_field  -> genuine Java reflection
#   log, run_on_ui_thread, run_on_queue, get_last_fragment -> genuine dispatch
#   BasePlugin + the on_plugin_load / on_plugin_unload lifecycle
#
# What is NOT real: arbitrary METHOD INTERCEPTION.
#
#   hook_method / hook_all_methods in exteraGram are backed by a Java-side hooking layer
#   compiled into that client. Colgram has no such layer - its hooks are injected at BUILD
#   time by scripts/apply-patches.py into named methods. Intercepting an arbitrary Java
#   method at runtime would need bytecode instrumentation (DexMaker/ASM), which is a
#   project of its own, not a shim.
#
#   So hooking is wired to the hook points Colgram actually exposes, and raises a clear
#   UnsupportedFeature for anything else. That is deliberate: a hook that silently never
#   fires is far worse than one that says why.

_COLGRAM_HOOK_POINTS = {
    # exteraGram-ish name -> Colgram's real hook point
    "send_message": "on_send_message",
    "message_received": "on_message_received",
    "message_edited": "on_message_edited",
    "message_deleted": "on_message_deleted",
    "menu_item_selected": "on_menu_item_selected",
}

_installed_hooks = []   # list of (target_name, callback)


def _java():
    """Import Chaquopy's java module lazily so the shim stays importable off-device."""
    import java
    return java


def _is_android():
    try:
        _java()
        return True
    except Exception:
        return False


# ------------------------------------------------------------------ hook_utils --------

def find_class(name):
    """Resolve a Java class by its fully-qualified name.

    exteraGram returns a wrapper it can hook; Colgram returns the real java.lang.Class,
    which is what plugins actually use it for (field access, instanceof, statics).
    """
    if not _is_android():
        raise UnsupportedFeature("find_class(%r) needs the Android runtime" % name)
    try:
        return _java().jclass(name)
    except Exception as e:
        raise UnsupportedFeature("find_class(%r) failed: %s" % (name, e))


def _find_field(obj, name):
    """Walk the class hierarchy for a declared field.

    getDeclaredField() only sees fields declared on THAT class, so a field inherited
    from a superclass raises NoSuchFieldException unless we walk up. Every one of these
    classes is deep in a hierarchy, so walking is required, not defensive.
    """
    cls = obj.getClass()
    while cls is not None:
        try:
            f = cls.getDeclaredField(name)
            f.setAccessible(True)
            return f
        except Exception:
            cls = cls.getSuperclass()
    return None


def get_private_field(obj, name):
    if obj is None:
        raise UnsupportedFeature("get_private_field(None, %r)" % name)
    f = _find_field(obj, name)
    if f is None:
        raise AttributeError("no field %r on %s" % (name, obj.getClass().getName()))
    return f.get(obj)


def set_private_field(obj, name, value):
    if obj is None:
        raise UnsupportedFeature("set_private_field(None, %r)" % name)
    f = _find_field(obj, name)
    if f is None:
        raise AttributeError("no field %r on %s" % (name, obj.getClass().getName()))
    f.set(obj, value)
    return value


def hook_method(method, hook, priority=0):
    """Register a hook against a Colgram hook point.

    `method` may be a hook-point name (see _COLGRAM_HOOK_POINTS) or an object with a
    __name__. Anything else raises - see the module header for why.
    """
    name = None
    if isinstance(method, str):
        name = method
    else:
        name = getattr(method, "__name__", None)
    key = _COLGRAM_HOOK_POINTS.get(name, name)
    if key not in _COLGRAM_HOOK_POINTS.values():
        raise UnsupportedFeature(
            "hook_method(%r): Colgram cannot intercept arbitrary Java methods - its hooks "
            "are injected at build time. Available hook points: %s"
            % (name, ", ".join(sorted(_COLGRAM_HOOK_POINTS))))
    _installed_hooks.append((key, hook))
    log("Colgram: hook registered on %s" % key)
    return hook


def hook_all_methods(clazz, name, hook, priority=0):
    """See hook_method(). Same limits, same explicit failure."""
    return hook_method(name, hook, priority)


def call_hook_point(name, *args, **kwargs):
    """Called by Colgram when one of the real hook points fires."""
    out = None
    for target, cb in list(_installed_hooks):
        if target != name:
            continue
        try:
            out = cb(*args, **kwargs)
        except Exception as e:
            log("Colgram: hook %s raised %s: %s" % (name, type(e).__name__, e))
    return out


class MethodHook(object):
    """Marker base class. exteraGram plugins subclass this for typed hooks; Colgram
    accepts any callable, so subclassing is optional but must not explode."""

    priority = 0

    def before(self, *a, **kw):
        return None

    def after(self, *a, **kw):
        return None


# ---------------------------------------------------------------- android_utils -------

def log(*parts):
    """Log to logcat under a Colgram tag, and to stdout when running off-device."""
    msg = " ".join(str(p) for p in parts)
    try:
        if _is_android():
            _java().jclass("android.util.Log").i("ColgramPlugin", msg)
    except Exception:
        pass
    try:
        print("[ColgramPlugin]", msg)
    except Exception:
        pass
    return msg


def _runnable(fn):
    """Wrap a Python callable as a java.lang.Runnable.

    Chaquopy will not implicitly convert a Python function to a Java interface, so an
    explicit dynamic proxy is required - passing the bare function raises a conversion
    error at the call site.
    """
    from java import dynamic_proxy
    from java.lang import Runnable

    @dynamic_proxy(Runnable)
    class _PyRunnable(object):
        def __init__(self, f):
            self._f = f

        def run(self):
            try:
                self._f()
            except Exception as e:
                log("runnable raised %s: %s" % (type(e).__name__, e))

    return _PyRunnable(fn)


def run_on_ui_thread(fn):
    if not _is_android():
        fn()
        return
    try:
        au = _java().jclass("org.telegram.messenger.AndroidUtilities")
        au.runOnUIThread(_runnable(fn))
    except Exception as e:
        log("run_on_ui_thread failed (%s); running inline" % e)
        fn()


# ----------------------------------------------------------------- client_utils -------

def run_on_queue(fn):
    """Run off the UI thread. A plain daemon thread is correct here: these are short
    tasks (file IO, JSON, media prep) and daemon=True means they never block process exit.
    """
    import threading
    t = threading.Thread(target=fn, name="colgram-plugin-task")
    t.daemon = True
    t.start()
    return t


def get_last_fragment():
    """The fragment currently on top, as exteraGram exposes it."""
    if not _is_android():
        return None
    try:
        return _java().jclass("org.telegram.ui.LaunchActivity").getSafeLastFragment()
    except Exception as e:
        log("get_last_fragment failed: %s" % e)
        return None


# ------------------------------------------------------------------ base_plugin -------

class BasePlugin(object):
    """Minimal exteraGram BasePlugin.

    exteraGram calls on_plugin_load() once after construction and on_plugin_unload()
    before teardown. Both are no-ops here so a subclass that overrides only one of them
    still works, and __init__ is deliberately left alone - plugins commonly keep state in
    class attributes precisely so the engine's constructor stays untouched.
    """

    def __init__(self):
        pass

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def on_plugin_settings(self):
        return None


# ------------------------------------------------------- register the four modules ----

def _make_module(name, **members):
    mod = _types.ModuleType(name)
    for k, v in members.items():
        setattr(mod, k, v)
    sys.modules[name] = mod
    return mod


def _install_extera_modules():
    """Publish base_plugin / hook_utils / android_utils / client_utils.

    These must land in sys.modules, not just be reachable as attributes of this module:
    the plugin does `from base_plugin import BasePlugin`, and the import system only
    consults sys.modules and the path finder.
    """
    try:
        _make_module(
            "base_plugin",
            BasePlugin=BasePlugin,
            MethodHook=MethodHook,
        )
        _make_module(
            "hook_utils",
            find_class=find_class,
            get_private_field=get_private_field,
            set_private_field=set_private_field,
            hook_method=hook_method,
            hook_all_methods=hook_all_methods,
            MethodHook=MethodHook,
        )
        _make_module(
            "android_utils",
            log=log,
            run_on_ui_thread=run_on_ui_thread,
        )
        _make_module(
            "client_utils",
            run_on_queue=run_on_queue,
            get_last_fragment=get_last_fragment,
        )
        log("exteraGram modules ready: base_plugin, hook_utils, android_utils, client_utils")
    except Exception as e:
        # Never let shim installation break plugin loading as a whole.
        try:
            log("exteraGram module install failed: %s" % e)
        except Exception:
            pass


_install_extera_modules()

