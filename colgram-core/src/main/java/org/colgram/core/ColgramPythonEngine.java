package org.colgram.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramPythonEngine — Mobile CPython (Chaquopy) Runtime & Telegram Bridge.
 * 
 * Embeds full Python 3.11 with:
 * 1. Standard library (os, sys, math, json, re, urllib, collections, etc.).
 * 2. Telegram API reflection bridge (sendMessage, deleteLastMessages, markRead).
 * 3. Real stdout/stderr capture and expression evaluation.
 * 4. Resilient fallback to Java-based evaluator if native runtime is unavailable.
 */
public class ColgramPythonEngine {

    private static final String TAG = "ColgramPythonEngine";
    private static final ExecutorService pyExecutor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Context appContext = null;
    private static volatile boolean pythonInitialized = false;
    /** Guards against two concurrent ensureInitialized() calls both starting the runtime. */
    private static volatile boolean pythonStarting = false;
    /**
     * Delay applied before the interpreter actually boots. Long enough for the
     * native MTProto connection pool to finish its own socket setup, so the two
     * descriptor tables never overlap.
     */
    private static final long STARTUP_SETTLE_MS = 5000L;

    // Reflection handles for Chaquopy
    private static Class<?> pythonClass = null;
    private static Object pythonInstance = null;
    private static Method getModuleMethod = null;
    private static Method callAttrMethod = null;

    // Active environment fallback variables
    private static final Map<String, Object> fallbackScope = new HashMap<>();

    /**
     * Registers the application context only. The CPython runtime is deliberately
     * NOT started here.
     *
     * Why: Chaquopy's CPython links its own libssl/libcrypto/libsqlite3 and its
     * _socket module opens file descriptors outside Android's fdsan bookkeeping.
     * Starting it while Telegram's native MTProto stack (libtmessages.so,
     * tgnet::ConnectionSocket) is still establishing its own sockets makes both
     * stacks race over the same fd numbers. bionic's fdsan then aborts the
     * process with:
     *
     *     fdsan: attempted to close file descriptor N,
     *     expected to be unowned, actually owned by SocketImpl 0x...
     *
     * which surfaces as a SIGABRT inside libtmessages.49.so about two seconds
     * after launch. The runtime is therefore started lazily, via
     * ensureInitialized(), from the points that actually need Python.
     */
    public static synchronized void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        fallbackScope.put("version", "3.11-colgram-cpython");
        fallbackScope.put("client", "Colgram");
    }

    /**
     * Starts the embedded CPython runtime if it is not already running.
     * Safe to call from any thread; the first caller wins and later callers
     * simply observe pythonInitialized == true.
     *
     * Callers must be genuine Python entry points (plugin execution, the
     * antispam screen, script console). Never call this from app startup.
     */
    public static synchronized void ensureInitialized() {
        if (pythonInitialized || pythonStarting) return;
        if (appContext == null) return;
        pythonStarting = true;

        pyExecutor.execute(() -> {
            try {
                // Settle delay: keep the interpreter out of the window in which
                // the MTProto socket layer is still bringing its connections up.
                try {
                    Thread.sleep(STARTUP_SETTLE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // Initialize Chaquopy AndroidPlatform & Python
                Class<?> platformClass = Class.forName("com.chaquo.python.android.AndroidPlatform");
                pythonClass = Class.forName("com.chaquo.python.Python");

                Method isStartedMethod = pythonClass.getMethod("isStarted");
                boolean isStarted = (boolean) isStartedMethod.invoke(null);

                if (!isStarted) {
                    Object platform = platformClass.getConstructor(Context.class).newInstance(appContext);
                    Method startMethod = pythonClass.getMethod("start", Class.forName("com.chaquo.python.PythonPlatform"));
                    startMethod.invoke(null, platform);
                }

                Method getInstanceMethod = pythonClass.getMethod("getInstance");
                pythonInstance = getInstanceMethod.invoke(null);

                getModuleMethod = pythonClass.getMethod("getModule", String.class);
                Class<?> pyObjectClass = Class.forName("com.chaquo.python.PyObject");
                callAttrMethod = pyObjectClass.getMethod("callAttr", String.class, Object[].class);

                // Setup Python runner and Telegram bridge
                setupPythonEnvironment();

                // Apply sys.path entries that were registered while the runtime
                // was still dormant (plugin directories from ColgramPluginManager.init).
                applyPendingPaths();

                pythonInitialized = true;
                Log.d(TAG, "Full Mobile CPython 3.11 (Chaquopy) initialized successfully!");

            } catch (Throwable t) {
                Log.w(TAG, "Chaquopy CPython initialization deferred or falling back: " + t.getMessage());
                pythonInitialized = false;
            } finally {
                // Always release the in-flight latch: on success the interpreter is
                // live and later calls short-circuit on pythonInitialized; on failure
                // we allow a future entry point to retry.
                pythonStarting = false;
            }
        });
    }

    /** True once the embedded interpreter is live and usable. */
    public static boolean isPythonReady() {
        return pythonInitialized;
    }

    private static void setupPythonEnvironment() {
        try {
            String bootstrap = 
                "import sys, io, builtins\n" +
                "_colgram_globals = {'__name__': '__main__'}\n" +
                "\n" +
                "def _execute_code(code_str):\n" +
                "    old_stdout = sys.stdout\n" +
                "    old_stderr = sys.stderr\n" +
                "    captured_out = sys.stdout = io.StringIO()\n" +
                "    captured_err = sys.stderr = io.StringIO()\n" +
                "    try:\n" +
                "        try:\n" +
                "            code_obj = compile(code_str, '<colgram>', 'eval')\n" +
                "            res = eval(code_obj, _colgram_globals)\n" +
                "            out = captured_out.getvalue()\n" +
                "            if res is not None:\n" +
                "                return f\"{out}{repr(res)}\" if out else repr(res)\n" +
                "            return out if out else 'None'\n" +
                "        except SyntaxError:\n" +
                "            code_obj = compile(code_str, '<colgram>', 'exec')\n" +
                "            exec(code_obj, _colgram_globals)\n" +
                "            out = captured_out.getvalue()\n" +
                "            err = captured_err.getvalue()\n" +
                "            if err:\n" +
                "                return f\"{out}\\nError: {err}\" if out else f\"Error: {err}\"\n" +
                "            return out if out else 'Executed successfully.'\n" +
                "    except Exception as e:\n" +
                "        return f\"{type(e).__name__}: {e}\"\n" +
                "    finally:\n" +
                "        sys.stdout = old_stdout\n" +
                "        sys.stderr = old_stderr\n" +
                "\n" +
                "builtins._execute_code = _execute_code\n" +
                "\n" +
                "# Install the exteraGram compatibility shim as `exteraPlugins` before any\n" +
                "# plugin is imported, so a plugin doing `import exteraPlugins` resolves to\n" +
                "# Colgram's partial implementation instead of failing at import time.\n" +
                "# The shim raises a clear UnsupportedFeature for APIs it cannot provide,\n" +
                "# rather than silently no-op'ing.\n" +
                "#\n" +
                "# The location comes from `_colgram_plugins_dir`, injected below as a\n" +
                "# Python global — os.environ cannot see a Java system property.\n" +
                "try:\n" +
                "    import types as _types, os as _os\n" +
                "    _shim_path = _os.path.join(_colgram_plugins_dir, 'extera_compat.py') if _colgram_plugins_dir else ''\n" +
                "    if _shim_path and _os.path.isfile(_shim_path):\n" +
                "        with open(_shim_path, 'r', encoding='utf-8') as _fh:\n" +
                "            _shim_src = _fh.read()\n" +
                "        _ext = _types.ModuleType('exteraPlugins')\n" +
                "        exec(compile(_shim_src, _shim_path, 'exec'), _ext.__dict__)\n" +
                "        sys.modules['exteraPlugins'] = _ext\n" +
                "except Exception as _e:\n" +
                "    pass\n";

            // Inject the plugins directory as a Python global before the bootstrap runs,
            // so the shim loader above can find extera_compat.py.
            String pluginsDir = System.getProperty("COLGRAM_PLUGINS_DIR", "");
            String prelude = "_colgram_plugins_dir = " + pyStr(pluginsDir) + "\n";

            Object builtinsMod = getModuleMethod.invoke(pythonInstance, "builtins");
            // Run the prelude first so `_colgram_plugins_dir` exists as a global, then the
            // bootstrap that consumes it.
            callAttrMethod.invoke(builtinsMod, "exec", new Object[]{ prelude });
            callAttrMethod.invoke(builtinsMod, "exec", new Object[]{ bootstrap });

        } catch (Throwable t) {
            Log.e(TAG, "Failed to bootstrap Python runner", t);
        }
    }

    /** Render a Java string as a safe single-quoted Python literal. */
    private static String pyStr(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else {
                sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    /**
     * Appends a filesystem path to Python's sys.path (e.g. for plugins directory).
     */
    public static void addPythonPath(String path) {
        if (path == null) return;
        // The interpreter may not be up yet (startup registration happens before
        // the lazy runtime boots). Remember the path and apply it on start.
        if (!pythonInitialized || pythonInstance == null) {
            synchronized (pendingPaths) {
                if (!pendingPaths.contains(path)) pendingPaths.add(path);
            }
            return;
        }
        try {
            Object sysMod = getModuleMethod.invoke(pythonInstance, "sys");
            Object pathObj = callAttrMethod.invoke(sysMod, "get", new Object[]{ "path" });
            callAttrMethod.invoke(pathObj, "append", new Object[]{ path });
        } catch (Throwable ignored) {}
    }

    /** sys.path entries registered before the interpreter finished booting. */
    private static final java.util.List<String> pendingPaths = new java.util.ArrayList<>();

    /** Flush deferred sys.path registrations once the interpreter is live. */
    private static void applyPendingPaths() {
        java.util.List<String> copy;
        synchronized (pendingPaths) {
            if (pendingPaths.isEmpty()) return;
            copy = new java.util.ArrayList<>(pendingPaths);
            pendingPaths.clear();
        }
        for (String p : copy) {
            try {
                Object sysMod = getModuleMethod.invoke(pythonInstance, "sys");
                Object pathObj = callAttrMethod.invoke(sysMod, "get", new Object[]{ "path" });
                callAttrMethod.invoke(pathObj, "append", new Object[]{ p });
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Is `cmd` registered by a plugin through the exteraGram compatibility shim?
     *
     * exteraGram plugins register commands with exteraPlugins.add_command() instead of
     * Colgram's header-comment convention, so they never appear in the Java-side command
     * map. This asks the shim directly.
     */
    public static boolean isShimCommandRegistered(String cmd) {
        if (!pythonInitialized || pythonInstance == null || cmd == null) return false;
        try {
            Object mod = getModuleMethod.invoke(pythonInstance, "exteraPlugins");
            if (mod == null) return false;
            Object commands = callAttrMethod.invoke(mod, "list_commands", new Object[]{});
            if (commands == null) return false;
            // PyObject list -> String and compare, case-insensitively like the dispatcher.
            String rendered = commands.toString();
            return rendered != null && rendered.toLowerCase().contains(cmd.toLowerCase());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Status of the exteraGram compatibility shim: "active", "loaded" or "missing".
     *
     * Used by the plugins screen so the user can tell whether the shim that lets
     * exteraGram plugins run is actually installed in this build.
     */
    public static String getShimStatus() {
        if (!pythonInitialized || pythonInstance == null) return "missing";
        try {
            Object mod = getModuleMethod.invoke(pythonInstance, "exteraPlugins");
            return mod == null ? "missing" : "active";
        } catch (Throwable t) {
            return "missing";
        }
    }

    /**
     * Number of dot-commands registered through the exteraGram shim.
     * Returns -1 when the shim is not present.
     */
    public static int getShimCommandCount() {
        if (!pythonInitialized || pythonInstance == null) return -1;
        try {
            Object mod = getModuleMethod.invoke(pythonInstance, "exteraPlugins");
            if (mod == null) return -1;
            Object commands = callAttrMethod.invoke(mod, "list_commands", new Object[]{});
            if (commands == null) return 0;
            Object len = callAttrMethod.invoke(commands, "__len__", new Object[]{});
            return len == null ? 0 : Integer.parseInt(len.toString());
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Invoke a command registered through the exteraGram shim.
     * Returns the handler's string result, or null if the command is unknown.
     */
    public static String runShimCommand(long dialogId, String cmd, String args) {
        if (cmd == null) return null;
        // Real execution path: bring the interpreter up on demand.
        ensureInitialized();
        if (!pythonInitialized || pythonInstance == null) return null;
        try {
            Object mod = getModuleMethod.invoke(pythonInstance, "exteraPlugins");
            // Wire the real bridge actions and the dialog context, so a plugin calling
            // exteraPlugins.send_message() reaches an actual chat instead of raising
            // UnsupportedFeature.
            callAttrMethod.invoke(mod, "set_bridge",
                    new Object[]{ makeCallback("send"), makeCallback("edit"), makeCallback("delete") });
            Object out = callAttrMethod.invoke(mod, "run_command",
                    new Object[]{ cmd, args == null ? "" : args, dialogId });
            return out == null ? null : out.toString();
        } catch (Throwable t) {
            Log.w(TAG, "shim command ." + cmd + " failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Executes arbitrary Python code or plugin script asynchronously.
     */
    public static void executeScript(final String script, final ScriptCallback callback) {
        pyExecutor.execute(() -> {
            try {
                final String result = runPythonCode(script);
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess(result));
                }
            } catch (final Throwable t) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(t.getMessage()));
                }
            }
        });
    }

    /**
     * Executes a command prefixed with '.' (e.g. .ping, .eval <expr>, .id, .del <n>)
     * Returns true if the command was intercepted and handled.
     */
    public static boolean handleCommand(final long dialogId, final int replyToMsgId, final String text) {
        if (text == null || !text.startsWith(".")) return false;

        String[] parts = text.trim().split("\\s+", 2);
        String cmd = parts[0].substring(1).toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "ping":
                long start = System.currentTimeMillis();
                sendMessage(dialogId, "🏓 Pong! `" + (System.currentTimeMillis() - start) + "ms`");
                return true;

            case "eval":
            case "py":
                if (args.isEmpty()) {
                    sendMessage(dialogId, "⚠️ Usage: `.py <python code>` or `.eval <expr>`");
                } else {
                    executeScript(args, new ScriptCallback() {
                        @Override
                        public void onSuccess(String output) {
                            sendMessage(dialogId, "🐍 **CPython Output:**\n```\n" + output + "\n```");
                        }

                        @Override
                        public void onError(String error) {
                            sendMessage(dialogId, "❌ **CPython Error:**\n```\n" + error + "\n```");
                        }
                    });
                }
                return true;

            case "id":
                sendMessage(dialogId, "🆔 **Chat ID:** `" + dialogId + "`");
                return true;

            case "del":
                int count = 1;
                try {
                    if (!args.isEmpty()) count = Integer.parseInt(args.trim());
                } catch (Exception ignored) {}
                deleteLastMessages(dialogId, count);
                return true;

            case "help":
                String help = "⚡ **Colgram CPython & Plugin System**\n\n" +
                        "• `.py <code>` — Run mobile CPython 3.11 (with stdlib)\n" +
                        "• `.eval <expr>` — Evaluate Python expression\n" +
                        "• `.ping` — Check client latency\n" +
                        "• `.id` — Get current Chat ID\n" +
                        "• `.del <count>` — Delete own messages in bulk\n" +
                        "• `.plugins` — List installed plugins in `/Documents/Colgram/Plugins/`\n" +
                        "• `.help` — Show this guide";
                sendMessage(dialogId, help);
                return true;

            case "plugins":
                String pluginList = ColgramPluginManager.getLoadedPluginsSummary();
                sendMessage(dialogId, pluginList);
                return true;

            default:
                return ColgramPluginManager.dispatchCommand(dialogId, cmd, args);
        }
    }

    /**
     * Mobile Python Expression & Script Evaluator.
     * Uses real CPython (Chaquopy) if available, or falls back to Java evaluator.
     */
    public static String executeCode(String code) {
        return runPythonCode(code);
    }

    public static String runPythonCode(String code) {
        if (code == null || code.trim().isEmpty()) return "None";

        // Lazily bring the interpreter up on first real use. This is the single
        // choke point for executeCode()/executeScript()/console input, so the
        // runtime only costs anything once the user actually runs Python.
        ensureInitialized();

        // 1. Try real CPython runtime
        if (pythonInitialized && pythonInstance != null) {
            try {
                Object builtinsMod = getModuleMethod.invoke(pythonInstance, "builtins");
                Object result = callAttrMethod.invoke(builtinsMod, "_execute_code", new Object[]{ code });
                if (result != null) {
                    return String.valueOf(result);
                }
            } catch (Throwable t) {
                Log.e(TAG, "CPython execution error, using fallback", t);
            }
        }

        // 2. Resilient Fallback Evaluator (Arithmetic, Print, Variable assign)
        String trimmed = code.trim();

        if (trimmed.matches("^[0-9\\+\\-\\*/\\%\\(\\)\\s\\.\\^]+$")) {
            try {
                return evaluateArithmetic(trimmed);
            } catch (Exception ignored) {}
        }

        if (trimmed.startsWith("print(") && trimmed.endsWith(")")) {
            String inner = trimmed.substring(6, trimmed.length() - 1);
            return inner.replaceAll("^[\"']|[\"']$", "");
        }

        if (trimmed.contains("=") && !trimmed.contains("==")) {
            String[] kv = trimmed.split("=", 2);
            String var = kv[0].trim();
            String val = kv[1].trim();
            fallbackScope.put(var, val);
            return var + " = " + val;
        }

        if (fallbackScope.containsKey(trimmed)) {
            return String.valueOf(fallbackScope.get(trimmed));
        }

        return "Executed (fallback): " + trimmed;
    }

    private static String evaluateArithmetic(String rawExpr) {
        final String expr = rawExpr.replaceAll("\\s+", "");
        try {
            double result = new Object() {
                int pos = -1, ch;

                void nextChar() {
                    ch = (++pos < expr.length()) ? expr.charAt(pos) : -1;
                }

                boolean eat(int charToEat) {
                    while (ch == ' ') nextChar();
                    if (ch == charToEat) {
                        nextChar();
                        return true;
                    }
                    return false;
                }

                double parse() {
                    nextChar();
                    double x = parseExpression();
                    if (pos < expr.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                    return x;
                }

                double parseExpression() {
                    double x = parseTerm();
                    for (;;) {
                        if (eat('+')) x += parseTerm();
                        else if (eat('-')) x -= parseTerm();
                        else return x;
                    }
                }

                double parseTerm() {
                    double x = parseFactor();
                    for (;;) {
                        if (eat('*')) x *= parseFactor();
                        else if (eat('/')) x /= parseFactor();
                        else return x;
                    }
                }

                double parseFactor() {
                    if (eat('+')) return parseFactor();
                    if (eat('-')) return -parseFactor();
                    double x;
                    int startPos = this.pos;
                    if (eat('(')) {
                        x = parseExpression();
                        eat(')');
                    } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                        while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                        x = Double.parseDouble(expr.substring(startPos, this.pos));
                    } else {
                        throw new RuntimeException("Unexpected: " + (char) ch);
                    }
                    return x;
                }
            }.parse();

            if (result == (long) result) return String.valueOf((long) result);
            return String.valueOf(result);
        } catch (Exception e) {
            return "SyntaxError: " + e.getMessage();
        }
    }

    /**
     * Edits an existing message via SendMessagesHelper reflection.
     *
     * SendMessagesHelper.editMessage() requires a BaseFragment, so this uses
     * ColgramUiBridge.reflectionRootFragment() (the activity currently on screen) and
     * bails out silently when there is none — an edit from a background plugin has no
     * UI anchor and would otherwise NPE inside Telegram.
     */
    public static void editMessage(long dialogId, int messageId, String newText) {
        try {
            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
            int currentAccount = (int) ucClass.getField("selectedAccount").get(null);

            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, currentAccount);
            Object msg = mcClass.getMethod("getMessage", int.class, long.class, int.class, boolean.class)
                    .invoke(mc, currentAccount, dialogId, messageId, false);
            if (msg == null) return;

            Class<?> msgObjClass = Class.forName("org.telegram.messenger.MessageObject");
            Object messageObject = msgObjClass.getConstructor(int.class, Object.class, Object.class, boolean.class)
                    .newInstance(currentAccount, msg, null, false);

            Object fragment = reflectionRootFragment();
            if (fragment == null) return;

            Class<?> smhClass = Class.forName("org.telegram.messenger.SendMessagesHelper");
            Object smh = smhClass.getMethod("getInstance", int.class).invoke(null, currentAccount);

            Class<?> baseFragmentClass = Class.forName("org.telegram.ui.ActionBar.BaseFragment");
            smhClass.getMethod("editMessage", msgObjClass, String.class, boolean.class,
                            baseFragmentClass, java.util.ArrayList.class, int.class, int.class)
                    .invoke(smh, messageObject, newText, false, fragment, null, 0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "editMessage via reflection failed: " + t.getMessage());
        }
    }

    /**
     * Deletes a single message via MessagesController.deleteMessages() reflection.
     */
    public static void deleteMessage(long dialogId, int messageId) {
        try {
            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
            int currentAccount = (int) ucClass.getField("selectedAccount").get(null);

            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, currentAccount);

            java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
            ids.add(messageId);

            Class<?> encryptedChatClass = Class.forName("org.telegram.tgnet.TLRPC$EncryptedChat");
            // Public overload: (ArrayList, ArrayList, EncryptedChat, long, int, boolean, int)
            // mode 0 == MODE_DEFAULT, forAll=false so it behaves like a normal local delete.
            mcClass.getMethod("deleteMessages", java.util.ArrayList.class, java.util.ArrayList.class,
                            encryptedChatClass, long.class, int.class, boolean.class, int.class)
                    .invoke(mc, ids, null, null, dialogId, 0, false, 0);
        } catch (Throwable t) {
            Log.w(TAG, "deleteMessage via reflection failed: " + t.getMessage());
        }
    }

    /**
     * The BaseFragment Telegram currently has on screen, or null when nothing is open.
     *
     * Needed because SendMessagesHelper.editMessage() demands a fragment — it dereferences
     * fragment.getParentActivity() immediately. The activity tracked by ColgramUiBridge is
     * not a BaseFragment, so this checks whether it is fragment-shaped before handing it
     * back, and returns null rather than a wrong-typed object.
     */
    private static Object reflectionRootFragment() {
        try {
            Class<?> bridge = Class.forName("org.colgram.core.ColgramUiBridge");
            Object activity = bridge.getMethod("currentActivity").invoke(null);
            if (activity == null) return null;
            // Only return it if Telegram would accept it as a BaseFragment. Any activity
            // that also implements this shape is the LaunchActivity's fragment host.
            Class<?> baseFragmentClass;
            try {
                baseFragmentClass = Class.forName("org.telegram.ui.ActionBar.BaseFragment");
            } catch (Throwable t) {
                return null;
            }
            return baseFragmentClass.isInstance(activity) ? activity : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Build a Python-callable that forwards to the matching Java action.
     *
     * Chaquopy wraps a Java object and exposes its public methods to Python by name, so an
     * object exposing {@code __call__(Object...)} becomes callable as {@code fn(a, b)}.
     * This is why the bridge does not need {@code org.python.core} — which colgram-core
     * cannot compile against, since Chaquopy is only on the app module.
     *
     * The returned proxy accepts either two or three positional arguments (Python passes
     * only what the caller supplied), so argument handling is tolerant of both shapes.
     */
    private static Object makeCallback(final String kind) {
        return java.lang.reflect.Proxy.newProxyInstance(
                ColgramPythonEngine.class.getClassLoader(),
                new Class<?>[]{ PyCallable.class },
                (proxy, method, rawArgs) -> {
                    if ("__call__".equals(method.getName())) {
                        Object[] a = (Object[]) rawArgs[0];
                        if ("send".equals(kind) && a.length >= 2) {
                            sendMessage(asLong(a[0]), String.valueOf(a[1]));
                        } else if ("edit".equals(kind) && a.length >= 3) {
                            editMessage(asLong(a[0]), (int) asLong(a[1]), String.valueOf(a[2]));
                        } else if ("delete".equals(kind) && a.length >= 2) {
                            deleteMessage(asLong(a[0]), (int) asLong(a[1]));
                        }
                        return null;
                    }
                    if ("toString".equals(method.getName())) return "ColgramPyBridge(" + kind + ")";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == rawArgs[0];
                    return null;
                });
    }

    /** Marker interface so the proxy has a stable, Chaquopy-visible method set. */
    public interface PyCallable {
        Object __call__(Object... args);
    }

    /** Best-effort numeric coercion: Chaquopy may hand back Integer, Long or a String. */
    private static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Sends a message in Telegram via SendMessagesHelper reflection.
     */
    public static void sendMessage(long dialogId, String message) {
        try {
            Class<?> smhClass = Class.forName("org.telegram.messenger.SendMessagesHelper");
            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
            int currentAccount = (int) ucClass.getField("selectedAccount").get(null);

            Method getInstance = smhClass.getMethod("getInstance", int.class);
            Object smh = getInstance.invoke(null, currentAccount);

            Method send = null;
            for (Method m : smhClass.getMethods()) {
                if (m.getName().equals("sendMessage") && m.getParameterTypes().length >= 4) {
                    if (m.getParameterTypes()[0] == CharSequence.class && m.getParameterTypes()[1] == long.class) {
                        send = m;
                        break;
                    }
                }
            }

            if (send != null) {
                Object[] args = new Object[send.getParameterTypes().length];
                args[0] = message;
                args[1] = dialogId;
                send.invoke(smh, args);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Deletes recent messages via MessagesController reflection.
     */
    public static void deleteLastMessages(long dialogId, int count) {
        try {
            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
            int currentAccount = (int) ucClass.getField("selectedAccount").get(null);

            Method getInstance = mcClass.getMethod("getInstance", int.class);
            Object mc = getInstance.invoke(null, currentAccount);

            for (Method m : mcClass.getMethods()) {
                if (m.getName().equals("deleteMessages") && m.getParameterTypes().length >= 2) {
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public interface ScriptCallback {
        void onSuccess(String output);
        void onError(String error);
    }
}
