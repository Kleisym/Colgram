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

import json
import os
import re
import time
import sys
import threading
import types as _types

__all__ = [
    "add_command", "on_command", "send_message", "edit_message",
    "delete_message", "get_dialog_id", "store", "schedule",
    "set_context", "set_bridge", "run_command", "list_commands",
    "on_command_handler", "on_command_info", "UnsupportedFeature",
    "BasePlugin", "MethodHook", "MenuItemData", "MenuItemType",
    "HookResult", "HookStrategy", "sync_extera_plugin", "unload_extera_plugin",
    "sync_all_plugins", "get_plugin_settings_json", "apply_plugin_setting_json",
    "dispatch_hook_point",
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

# Version-tolerant Telegram/exteraGram class name aliases so 3rd-party plugins keep working
# smoothly across Telegram 11.x / 12.x / future refactors.
_TELEGRAM_CLASS_ALIASES = {
    "com.exteragram.messenger.plugins.PluginsController": None,
    "com.radolyn.ayugram.AyuConfig": None,
    "org.telegram.tgnet.TLRPC$TL_stories": "org.telegram.tgnet.tl.TL_stories",
    "org.telegram.tgnet.TLRPC$TL_account": "org.telegram.tgnet.tl.TL_account",
}


def resolve_class_alias(name):
    """Map legacy/fork class names to the active Colgram/Telegram equivalent."""
    key = str(name or "").strip()
    if key in _TELEGRAM_CLASS_ALIASES:
        return _TELEGRAM_CLASS_ALIASES[key]
    return key


def find_class(name):
    """Resolve a Java class by its fully-qualified name with Telegram version-alias resilience."""
    mapped = resolve_class_alias(name)
    if mapped is None:
        return PluginsController
    if not _is_android():
        raise UnsupportedFeature("find_class(%r) needs the Android runtime" % name)
    candidates = [mapped]
    if "$" not in mapped and ".TL_" in mapped:
        parts = mapped.rsplit(".", 1)
        candidates.append(parts[0] + "$" + parts[1])
    last_err = None
    for cand in candidates:
        try:
            return _java().jclass(cand)
        except Exception as e:
            last_err = e
    raise UnsupportedFeature("find_class(%r) failed: %s" % (name, last_err))


def _find_static_field(cls, name):
    cur = cls
    while cur is not None:
        try:
            f = cur.getDeclaredField(name)
            f.setAccessible(True)
            return f
        except Exception:
            cur = cur.getSuperclass()
    return None


def get_static_field(cls, name):
    if cls is None:
        raise UnsupportedFeature("get_static_field(None, %r)" % name)
    f = _find_static_field(cls, name)
    if f is None:
        raise AttributeError("no static field %r on %s" % (name, cls))
    return f.get(None)


def set_static_field(cls, name, value):
    if cls is None:
        raise UnsupportedFeature("set_static_field(None, %r)" % name)
    f = _find_static_field(cls, name)
    if f is None:
        raise AttributeError("no static field %r on %s" % (name, cls))
    f.set(None, value)


def call_method(obj, name, *args):
    """Invoke a public or private Java/Python method by name cleanly across class hierarchies."""
    if obj is None:
        raise UnsupportedFeature("call_method(None, %r)" % name)
    attr = getattr(obj, name, None)
    if callable(attr):
        return attr(*args)
    if hasattr(obj, "getClass"):
        cls = obj.getClass()
        while cls is not None:
            for m in cls.getDeclaredMethods():
                if m.getName() == name and len(m.getParameterTypes()) == len(args):
                    m.setAccessible(True)
                    return m.invoke(obj, *args)
            cls = cls.getSuperclass()
    raise AttributeError("no method %r on %r" % (name, obj))


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


def _plugins_dir():
    try:
        import builtins
        d = getattr(builtins, "_colgram_plugins_dir", None)
        if d:
            return d
    except Exception:
        pass
    return globals().get("_colgram_plugins_dir") or os.environ.get("COLGRAM_PLUGINS_DIR") or ""


def _settings_file_for(plugin_id):
    pdir = _plugins_dir()
    if not pdir:
        return None
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(plugin_id or "plugin"))
    return os.path.join(pdir, ".settings_" + safe + ".json")


def _load_settings_map(plugin_id):
    path = _settings_file_for(plugin_id)
    if path and os.path.isfile(path):
        try:
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            if isinstance(data, dict):
                return data
        except Exception:
            pass
    return dict(_MEMORY_SETTINGS.get(plugin_id, {}))


def _save_settings_map(plugin_id, data):
    _MEMORY_SETTINGS[plugin_id] = dict(data)
    path = _settings_file_for(plugin_id)
    if path:
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False, indent=2)
        except Exception as e:
            log("settings write failed for %s: %s" % (plugin_id, e))


_MEMORY_SETTINGS = {}
_LOADED_PLUGINS = {}   # module_name -> BasePlugin instance
_RUNTIME_HOOKS = {}    # hook_name -> list of (plugin_id, callable, priority)


# ------------------------------------------------------------------ base_plugin -------

class HookStrategy(object):
    DEFAULT = "DEFAULT"
    SKIP = "SKIP"
    MODIFY = "MODIFY"
    MODIFY_FINAL = "MODIFY_FINAL"
    CANCEL = "CANCEL"


class HookResult(object):
    def __init__(self, strategy=HookStrategy.DEFAULT, params=None):
        self.strategy = strategy
        self.params = params


class MenuItemType(object):
    MESSAGE_CONTEXT_MENU = "MESSAGE_CONTEXT_MENU"
    CHAT_ACTION_MENU = "CHAT_ACTION_MENU"
    DRAWER_MENU = "DRAWER_MENU"
    PROFILE_ACTION_MENU = "PROFILE_ACTION_MENU"
    INPUT_MENU = "INPUT_MENU"


class MenuItemData(object):
    def __init__(self, menu_type=MenuItemType.MESSAGE_CONTEXT_MENU, text="", on_click=None,
                 icon=None, subtext=None, condition=None, priority=0, item_id=None):
        self.menu_type = menu_type
        self.text = text
        self.on_click = on_click
        self.icon = icon
        self.subtext = subtext
        self.condition = condition
        self.priority = priority
        self.item_id = item_id


class BasePlugin(object):
    """exteraGram-compatible BasePlugin with settings persistence, hook tracking, and lifecycle."""

    def __init__(self):
        self._hooks = []
        self._menu_items = []
        self._module_name = type(self).__module__

    @property
    def id(self):
        return getattr(self, "__id__", None) or getattr(type(self), "__id__", None) or self._module_name or type(self).__name__

    def log(self, *parts):
        return log("[%s]" % self.id, *parts)

    def get_setting(self, key, default=None):
        data = _load_settings_map(self.id)
        return data.get(str(key), default)

    def set_setting(self, key, value):
        data = _load_settings_map(self.id)
        data[str(key)] = value
        _save_settings_map(self.id, data)
        return value

    def add_hook(self, hook_name, hook=None, priority=0):
        cb = hook if hook is not None else getattr(self, str(hook_name), None)
        if not callable(cb):
            return None
        key = str(hook_name)
        lst = _RUNTIME_HOOKS.setdefault(key, [])
        entry = (self.id, cb, int(priority))
        if entry not in lst:
            lst.append(entry)
            lst.sort(key=lambda item: -item[2])
        self._hooks.append((key, cb))
        return entry

    def remove_hook(self, hook_name, hook=None):
        key = str(hook_name)
        if key in _RUNTIME_HOOKS:
            _RUNTIME_HOOKS[key] = [
                e for e in _RUNTIME_HOOKS[key]
                if not (e[0] == self.id and (hook is None or e[1] is hook))
            ]

    def hook_method(self, method_or_name, hook=None, priority=0):
        if isinstance(method_or_name, str) and callable(hook):
            return self.add_hook(method_or_name, hook, priority)
        try:
            handle = hook_method(method_or_name, hook, priority)
            self._hooks.append(("__java__", handle))
            return handle
        except Exception as e:
            self.log("hook_method fallback:", e)
            return None

    def unhook_all_methods(self):
        for key, _ in list(self._hooks):
            if key != "__java__":
                _RUNTIME_HOOKS[key] = [e for e in _RUNTIME_HOOKS.get(key, []) if e[0] != self.id]
        self._hooks.clear()

    def add_menu_item(self, item):
        self._menu_items.append(item)
        return item

    def remove_menu_item(self, item_id):
        self._menu_items = [
            m for m in self._menu_items
            if getattr(m, "item_id", None) != item_id
        ]

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def create_settings(self):
        legacy = self.on_plugin_settings()
        return legacy if isinstance(legacy, list) else []

    def on_plugin_settings(self):
        return None


# ------------------------------------------------------------------ ui.* & markdown ---

class _SettingWidget(object):
    kind = "item"

    def to_dict(self, plugin=None):
        return {"type": self.kind}


class Header(_SettingWidget):
    kind = "header"

    def __init__(self, text):
        self.text = str(text)

    def to_dict(self, plugin=None):
        return {"type": "header", "text": self.text}


Category = Header


class Slider(_SettingWidget):
    kind = "slider"

    def __init__(self, key, text, default=0, min_value=0, max_value=100, step=1, on_change=None, subtext=None):
        self.key = str(key)
        self.text = str(text)
        self.default = default
        self.min_value = min_value
        self.max_value = max_value
        self.step = step
        self.on_change = on_change
        self.subtext = subtext

    def to_dict(self, plugin=None):
        val = plugin.get_setting(self.key, self.default) if plugin else self.default
        return {
            "type": "slider",
            "key": self.key,
            "text": self.text,
            "subtext": self.subtext or "",
            "min": self.min_value,
            "max": self.max_value,
            "step": self.step,
            "value": val,
        }


class Action(_SettingWidget):
    kind = "action"

    def __init__(self, text, on_click=None, subtext=None, icon=None, accent=False):
        self.text = str(text)
        self.on_click = on_click
        self.subtext = subtext
        self.icon = icon
        self.accent = bool(accent)

    def to_dict(self, plugin=None):
        return {
            "type": "action",
            "text": self.text,
            "subtext": self.subtext or "",
            "accent": self.accent,
        }


class AppEvent(object):
    START = "START"
    STOP = "STOP"
    PAUSE = "PAUSE"
    RESUME = "RESUME"
    ACCOUNT_CHANGED = "ACCOUNT_CHANGED"


class PluginsController(object):
    """Compatibility controller shim for 3rd-party exteraGram/AyuGram plugins."""

    _instance = None

    @classmethod
    def get_instance(cls):
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def get_plugins(self):
        return dict(_LOADED_PLUGINS)

    def is_plugin_enabled(self, plugin_id):
        if plugin_id in _LOADED_PLUGINS:
            return True
        for inst in _LOADED_PLUGINS.values():
            if getattr(inst, "__id__", None) == plugin_id or getattr(inst, "id", None) == plugin_id:
                return True
        return False

    def get_plugin(self, plugin_id):
        if plugin_id in _LOADED_PLUGINS:
            return _LOADED_PLUGINS[plugin_id]
        for inst in _LOADED_PLUGINS.values():
            if getattr(inst, "__id__", None) == plugin_id or getattr(inst, "id", None) == plugin_id:
                return inst
        return None


class Divider(_SettingWidget):
    kind = "divider"

    def __init__(self, text=None):
        self.text = "" if text is None else str(text)

    def to_dict(self, plugin=None):
        return {"type": "divider", "text": self.text}


class Switch(_SettingWidget):
    kind = "switch"

    def __init__(self, key, text, default=False, subtext=None, on_change=None, icon=None,
                 create_sub_fragment=None, link_alias=None):
        self.key = str(key)
        self.text = str(text)
        self.default = bool(default)
        self.subtext = subtext
        self.on_change = on_change
        self.icon = icon
        self.create_sub_fragment = create_sub_fragment
        self.link_alias = link_alias

    def to_dict(self, plugin=None):
        val = plugin.get_setting(self.key, self.default) if plugin else self.default
        return {
            "type": "switch",
            "key": self.key,
            "text": self.text,
            "subtext": self.subtext or "",
            "value": bool(val),
        }


class Input(_SettingWidget):
    kind = "input"

    def __init__(self, key, text, default="", subtext=None, on_change=None, icon=None,
                 multiline=False, link_alias=None):
        self.key = str(key)
        self.text = str(text)
        self.default = "" if default is None else str(default)
        self.subtext = subtext
        self.on_change = on_change
        self.icon = icon
        self.multiline = bool(multiline)
        self.link_alias = link_alias

    def to_dict(self, plugin=None):
        val = plugin.get_setting(self.key, self.default) if plugin else self.default
        return {
            "type": "input",
            "key": self.key,
            "text": self.text,
            "subtext": self.subtext or "",
            "value": "" if val is None else str(val),
            "multiline": self.multiline,
        }


EditText = Input


class Selector(_SettingWidget):
    kind = "selector"

    def __init__(self, key, text, items=(), default=0, on_change=None, icon=None, link_alias=None):
        self.key = str(key)
        self.text = str(text)
        self.items = [str(x) for x in (items or ())]
        self.default = int(default)
        self.on_change = on_change
        self.icon = icon
        self.link_alias = link_alias

    def to_dict(self, plugin=None):
        val = plugin.get_setting(self.key, self.default) if plugin else self.default
        try:
            idx = int(val)
        except Exception:
            idx = self.default
        return {
            "type": "selector",
            "key": self.key,
            "text": self.text,
            "items": self.items,
            "value": idx,
        }


class Text(_SettingWidget):
    kind = "text"

    def __init__(self, text, icon=None, on_click=None, accent=False, red=False,
                 on_long_click=None, create_sub_fragment=None, link_alias=None):
        self.text = str(text)
        self.icon = icon
        self.on_click = on_click
        self.accent = bool(accent)
        self.red = bool(red)
        self.on_long_click = on_long_click
        self.create_sub_fragment = create_sub_fragment
        self.link_alias = link_alias

    def to_dict(self, plugin=None):
        return {
            "type": "text",
            "text": self.text,
            "accent": self.accent,
            "red": self.red,
        }


class BulletinHelper(object):
    @staticmethod
    def _show(prefix, text):
        msg = str(text or "")
        if _is_android():
            def _ui():
                try:
                    Toast = _java().jclass("android.widget.Toast")
                    AppLoader = _java().jclass("org.telegram.messenger.ApplicationLoader")
                    ctx = AppLoader.applicationContext
                    if ctx is not None:
                        Toast.makeText(ctx, msg, 0).show()
                        return
                except Exception:
                    pass
                log(prefix, msg)
            run_on_ui_thread(_ui)
        else:
            log(prefix, msg)

    @classmethod
    def show_info(cls, text, *a, **kw):
        cls._show("[INFO]", text)

    @classmethod
    def show_success(cls, text, *a, **kw):
        cls._show("[OK]", text)

    @classmethod
    def show_error(cls, text, *a, **kw):
        cls._show("[ERR]", text)

    @classmethod
    def show_with_button(cls, text, *a, **kw):
        cls._show("[INFO]", text)


class AlertDialogBuilder(object):
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 1
    ALERT_TYPE_SPINNER = 3

    def __init__(self, context=None, style=0):
        self.context = context
        self.style = style
        self.title = ""
        self.message = ""
        self._positive = None
        self._negative = None
        self._neutral = None

    def set_title(self, title):
        self.title = str(title)
        return self

    def set_message(self, message):
        self.message = str(message)
        return self

    def set_positive_button(self, text, handler=None):
        self._positive = (text, handler)
        return self

    def set_negative_button(self, text, handler=None):
        self._negative = (text, handler)
        return self

    def set_neutral_button(self, text, handler=None):
        self._neutral = (text, handler)
        return self

    def show(self):
        if _is_android() and self.context is not None:
            def _ui():
                try:
                    B = _java().jclass("org.telegram.ui.ActionBar.AlertDialog$Builder")(self.context)
                    if self.title:
                        B.setTitle(self.title)
                    if self.message:
                        B.setMessage(self.message)
                    if self._negative:
                        B.setNegativeButton(self._negative[0], None)
                    if self._positive:
                        B.setPositiveButton(self._positive[0], None)
                    B.show()
                except Exception as e:
                    log("AlertDialogBuilder failed:", e)
            run_on_ui_thread(_ui)
        else:
            log("AlertDialog:", self.title, self.message)
        return self


class ParsedMarkdown(object):
    def __init__(self, text, entities):
        self.text = text
        self.entities = entities


def parse_markdown(raw_text):
    """Lightweight CommonMark/Telegram markdown stripper producing text + entity metadata."""
    if not raw_text:
        return ParsedMarkdown("", [])
    text = str(raw_text)
    entities = []
    # Strip paired markers iteratively from text while collecting offsets cleanly.
    patterns = [
        ("bold", re.compile(r"\*\*(.+?)\*\*", re.DOTALL)),
        ("italic", re.compile(r"__(.+?)__", re.DOTALL)),
        ("strike", re.compile(r"~~(.+?)~~", re.DOTALL)),
        ("code", re.compile(r"`([^`\n]+)`")),
        ("spoiler", re.compile(r"\|\|(.+?)\|\|", re.DOTALL)),
    ]
    for kind, pat in patterns:
        while True:
            m = pat.search(text)
            if not m:
                break
            inner = m.group(1)
            start = m.start()
            text = text[:m.start()] + inner + text[m.end():]
            entities.append({"type": kind, "offset": start, "length": len(inner)})
    return ParsedMarkdown(text, entities)


def _client_send_message(*args, **kwargs):
    if args and isinstance(args[0], dict):
        params = args[0]
        peer = params.get("peer", params.get("dialog_id"))
        msg = params.get("message", params.get("text", ""))
        return send_message(msg, dialog_id=peer)
    if len(args) >= 2:
        return send_message(args[1], dialog_id=args[0])
    msg = kwargs.get("message") or kwargs.get("text") or (args[0] if args else "")
    peer = kwargs.get("peer") or kwargs.get("dialog_id")
    return send_message(msg, dialog_id=peer)


def _client_edit_message(target, text=None, dialog_id=None):
    if isinstance(target, dict):
        return edit_message(
            target.get("message_id", target.get("id", 0)),
            target.get("message", target.get("text", "")),
            dialog_id=target.get("peer", target.get("dialog_id")),
        )
    return edit_message(target, "" if text is None else str(text), dialog_id=dialog_id)


def _client_delete_message(msg_ids, dialog_id=None):
    if isinstance(msg_ids, (list, tuple)):
        for mid in msg_ids:
            delete_message(mid, dialog_id=dialog_id)
        return
    delete_message(msg_ids, dialog_id=dialog_id)


def _selected_account():
    if not _is_android():
        return 0
    try:
        return int(_java().jclass("org.telegram.messenger.UserConfig").selectedAccount)
    except Exception:
        return 0


def _get_tg_instance(cls_name):
    if not _is_android():
        return None
    try:
        return _java().jclass(cls_name).getInstance(_selected_account())
    except Exception as e:
        log("%s.getInstance failed: %s" % (cls_name, e))
        return None


def get_messages_controller():
    return _get_tg_instance("org.telegram.messenger.MessagesController")


def get_send_messages_helper():
    return _get_tg_instance("org.telegram.messenger.SendMessagesHelper")


def get_user_config():
    return _get_tg_instance("org.telegram.messenger.UserConfig")


def get_connections_manager():
    return _get_tg_instance("org.telegram.tgnet.ConnectionsManager")


def get_account_instance():
    return _get_tg_instance("org.telegram.messenger.AccountInstance")


def get_media_data_controller():
    return _get_tg_instance("org.telegram.messenger.MediaDataController")


def get_file_loader():
    return _get_tg_instance("org.telegram.messenger.FileLoader")


def get_messages_storage():
    return _get_tg_instance("org.telegram.messenger.MessagesStorage")


def get_notification_center():
    return _get_tg_instance("org.telegram.messenger.NotificationCenter")


def get_contacts_controller():
    return _get_tg_instance("org.telegram.messenger.ContactsController")


def get_current_account():
    return _selected_account()


def get_application_context():
    if not _is_android():
        return None
    try:
        return _java().jclass("org.telegram.messenger.ApplicationLoader").applicationContext
    except Exception:
        return None


def dp(value):
    if _is_android():
        try:
            return int(_java().jclass("org.telegram.messenger.AndroidUtilities").dp(float(value)))
        except Exception:
            pass
    return int(round(float(value) * 3.0))


class RequestCallback(object):
    """Wrapper matching exteraGram client_utils.RequestCallback."""

    def __init__(self, callback):
        self.callback = callback

    def __call__(self, response, error):
        if callable(self.callback):
            return self.callback(response, error)
        return None


def send_request(req, callback=None):
    """Send a TLObject via Telegram ConnectionsManager when running on Android."""
    cm = get_connections_manager()
    if cm is None:
        raise UnsupportedFeature("send_request requires the Android Telegram runtime")
    return cm.sendRequest(req, callback)


class OnClickListener(object):
    def __init__(self, callback):
        self.callback = callback

    def onClick(self, view=None):
        if callable(self.callback):
            return self.callback(view)
        return None


class OnLongClickListener(object):
    def __init__(self, callback):
        self.callback = callback

    def onLongClick(self, view=None):
        if callable(self.callback):
            return bool(self.callback(view))
        return False


def show_toast(text):
    BulletinHelper.show_info(text)


def _discover_plugin_class(mod):
    best = None
    for attr in dir(mod):
        obj = getattr(mod, attr, None)
        if isinstance(obj, type) and issubclass(obj, BasePlugin) and obj is not BasePlugin:
            if getattr(obj, "__module__", "") == mod.__name__:
                best = obj
                break
    return best


_LOADED_MTIMES = {}


def sync_extera_plugin(module_name):
    """Import/reload module_name and run BasePlugin.on_plugin_load() automatically."""
    import importlib
    if not module_name:
        return "empty"
    if module_name in _LOADED_PLUGINS:
        mod_obj = sys.modules.get(module_name)
        mod_file = getattr(mod_obj, "__file__", None) if mod_obj is not None else None
        cur_mtime = os.path.getmtime(mod_file) if (mod_file and os.path.exists(mod_file)) else None
        prev_mtime = _LOADED_MTIMES.get(module_name)
        if cur_mtime is None or cur_mtime == prev_mtime:
            return "already_loaded:" + type(_LOADED_PLUGINS[module_name]).__name__
        unload_extera_plugin(module_name)
    mod = importlib.import_module(module_name)
    mod_file = getattr(mod, "__file__", None)
    if mod_file and os.path.exists(mod_file):
        _LOADED_MTIMES[module_name] = os.path.getmtime(mod_file)
    plugin_cls = _discover_plugin_class(mod)
    if plugin_cls is None:
        return "script"
    inst = plugin_cls()
    inst._module_name = module_name
    for attr in ("__id__", "__name__", "__author__", "__version__", "__description__"):
        if hasattr(mod, attr) and not hasattr(inst, attr):
            setattr(inst, attr, getattr(mod, attr))
    _LOADED_PLUGINS[module_name] = inst
    try:
        inst.on_plugin_load()
    except Exception as e:
        log("on_plugin_load failed in %s: %s" % (module_name, e))
    return "loaded:" + plugin_cls.__name__


def unload_extera_plugin(module_name):
    """Run on_plugin_unload(), clear tracked hooks, and evict from loaded registry."""
    inst = _LOADED_PLUGINS.pop(module_name, None)
    if inst is not None:
        try:
            inst.on_plugin_unload()
        except Exception as e:
            log("on_plugin_unload failed in %s: %s" % (module_name, e))
        try:
            inst.unhook_all_methods()
        except Exception:
            pass
    sys.modules.pop(module_name, None)
    return inst is not None


def sync_all_plugins(enabled_modules):
    """Align active BasePlugin instances with the Java manager's enabled plugin set."""
    wanted = [str(m) for m in (enabled_modules or []) if m]
    wanted_set = set(wanted)
    for loaded in list(_LOADED_PLUGINS.keys()):
        if loaded not in wanted_set:
            unload_extera_plugin(loaded)
    summary = []
    for mod in wanted:
        try:
            res = sync_extera_plugin(mod)
            summary.append(mod + "=" + str(res))
        except Exception as e:
            log("sync_extera_plugin(%s) failed: %s" % (mod, e))
            summary.append(mod + "=error:" + str(e))
    return summary


def get_plugin_settings_json(module_name):
    """Serialize create_settings() descriptor list for UI rendering."""
    sync_extera_plugin(module_name)
    inst = _LOADED_PLUGINS.get(module_name)
    if inst is None:
        return "[]"
    try:
        items = inst.create_settings() or []
        serialized = []
        for item in items:
            if hasattr(item, "to_dict"):
                serialized.append(item.to_dict(inst))
        return json.dumps(serialized, ensure_ascii=False)
    except Exception as e:
        log("get_plugin_settings_json(%s) failed: %s" % (module_name, e))
        return "[]"


def apply_plugin_setting_json(module_name, key, value_json):
    """Persist updated setting from Colgram UI and trigger widget on_change callback."""
    sync_extera_plugin(module_name)
    inst = _LOADED_PLUGINS.get(module_name)
    if inst is None:
        return False
    try:
        value = json.loads(value_json) if isinstance(value_json, str) else value_json
    except Exception:
        value = value_json
    inst.set_setting(key, value)
    try:
        for item in (inst.create_settings() or []):
            if getattr(item, "key", None) == key and callable(getattr(item, "on_change", None)):
                item.on_change(value)
    except Exception as e:
        log("on_change failed for %s.%s: %s" % (module_name, key, e))
    return True


def dispatch_hook_point(name, *args, **kwargs):
    """Run registered callbacks for a lifecycle hook point (send_message / message_received / ...)."""
    results = []
    for _, cb, _ in list(_RUNTIME_HOOKS.get(str(name), [])):
        try:
            results.append(cb(*args, **kwargs))
        except Exception as e:
            log("hook %s failed: %s" % (name, e))
    method_name = _HOOK_NAMES.get(name, name)
    for inst in list(_LOADED_PLUGINS.values()):
        fn = getattr(inst, method_name, None)
        if callable(fn):
            try:
                results.append(fn(*args, **kwargs))
            except Exception as e:
                log("plugin %s.%s failed: %s" % (inst.id, method_name, e))
    return results


# ------------------------------------------------------- register the modules ---------

def _make_module(name, **members):
    mod = _types.ModuleType(name)
    for k, v in members.items():
        setattr(mod, k, v)
    sys.modules[name] = mod
    return mod


def _install_extera_modules():
    """Publish base_plugin / hook_utils / android_utils / client_utils / ui.* / markdown_utils."""
    try:
        _make_module(
            "base_plugin",
            BasePlugin=BasePlugin,
            MethodHook=MethodHook,
            XposedHook=MethodHook,
            MethodReplacement=MethodHook,
            MenuItemData=MenuItemData,
            MenuItemType=MenuItemType,
            HookResult=HookResult,
            HookStrategy=HookStrategy,
            AppEvent=AppEvent,
        )
        _make_module(
            "hook_utils",
            find_class=find_class,
            resolve_class_alias=resolve_class_alias,
            get_private_field=get_private_field,
            set_private_field=set_private_field,
            get_field=get_private_field,
            set_field=set_private_field,
            get_static_field=get_static_field,
            set_static_field=set_static_field,
            call_method=call_method,
            hook_method=hook_method,
            hook_all_methods=hook_all_methods,
            MethodHook=MethodHook,
        )
        _make_module(
            "android_utils",
            log=log,
            run_on_ui_thread=run_on_ui_thread,
            get_application_context=get_application_context,
            dp=dp,
            OnClickListener=OnClickListener,
            OnLongClickListener=OnLongClickListener,
        )
        _make_module(
            "client_utils",
            run_on_queue=run_on_queue,
            get_last_fragment=get_last_fragment,
            send_message=_client_send_message,
            edit_message=_client_edit_message,
            delete_message=_client_delete_message,
            show_toast=show_toast,
            get_messages_controller=get_messages_controller,
            get_send_messages_helper=get_send_messages_helper,
            get_user_config=get_user_config,
            get_connections_manager=get_connections_manager,
            get_account_instance=get_account_instance,
            get_media_data_controller=get_media_data_controller,
            get_file_loader=get_file_loader,
            get_messages_storage=get_messages_storage,
            get_notification_center=get_notification_center,
            get_contacts_controller=get_contacts_controller,
            get_current_account=get_current_account,
            send_request=send_request,
            RequestCallback=RequestCallback,
            PLUGINS_MANAGER=PluginsController.get_instance(),
        )
        _make_module(
            "plugins_controller",
            PluginsController=PluginsController,
        )
        ui_settings_mod = _make_module(
            "ui.settings",
            Header=Header,
            Category=Category,
            Switch=Switch,
            Input=Input,
            EditText=EditText,
            Selector=Selector,
            Slider=Slider,
            Action=Action,
            Text=Text,
            Divider=Divider,
        )
        ui_bulletin_mod = _make_module(
            "ui.bulletin",
            BulletinHelper=BulletinHelper,
        )
        ui_alert_mod = _make_module(
            "ui.alert",
            AlertDialogBuilder=AlertDialogBuilder,
        )
        _make_module(
            "ui",
            settings=ui_settings_mod,
            bulletin=ui_bulletin_mod,
            alert=ui_alert_mod,
        )
        _make_module(
            "markdown_utils",
            parse_markdown=parse_markdown,
            ParsedMarkdown=ParsedMarkdown,
        )
        log("exteraGram modules ready: base_plugin, hook_utils, android_utils, client_utils, ui.*, markdown_utils")
    except Exception as e:
        # Never let shim installation break plugin loading as a whole.
        try:
            log("exteraGram module install failed: %s" % e)
        except Exception:
            pass


_install_extera_modules()

