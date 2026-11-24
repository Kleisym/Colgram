package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ColgramPluginManager — ExteraGram/AyuGram-compatible plugin engine and store manager.
 * 
 * Supports both internal app storage (zero permissions needed) and external storage.
 * Handles live command routing, on-the-fly Python script loading, and marketplace installation.
 */
public class ColgramPluginManager {

    private static final String TAG = "ColgramPluginManager";
    private static final String PREFS_NAME = "colgram_plugins_prefs";

    public static class PluginInfo {
        public final String name;
        public final String fileName;
        public final String description;
        public final String author;
        public final String version;
        public final String command;
        public final boolean builtIn;
        public boolean isEnabled = true;

        public PluginInfo(String name, String fileName, String description, String author, String version, String command, boolean isEnabled, boolean builtIn) {
            this.name = name;
            this.fileName = fileName;
            this.description = description;
            this.author = author;
            this.version = version;
            this.command = command;
            this.isEnabled = isEnabled;
            this.builtIn = builtIn;
        }
    }

    private static final List<PluginInfo> loadedPlugins = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, PluginInfo> activeCommands = Collections.synchronizedMap(new HashMap<>());
    private static Context appContext = null;
    private static File internalPluginsDir = null;
    private static File externalPluginsDir = null;
    private static File legacyPublicPluginsDir = null;

    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        internalPluginsDir = new File(appContext.getFilesDir(), "plugins");
        // Defer filesystem setup, shim writes, and plugin directory scanning off the cold-start UI thread.
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            try {
                if (!internalPluginsDir.exists()) {
                    internalPluginsDir.mkdirs();
                }
                try {
                    new File(internalPluginsDir, ".nomedia").createNewFile();
                } catch (Throwable ignored) {}
                try {
                    File sandboxRoot = ColgramStorageSandbox.getSandboxRootDir(appContext);
                    externalPluginsDir = new File(sandboxRoot, "Plugins");
                    if (!externalPluginsDir.exists()) {
                        externalPluginsDir.mkdirs();
                    }
                    try {
                        new File(externalPluginsDir, ".nomedia").createNewFile();
                    } catch (Throwable ignored) {}

                    // Do NOT create public /sdcard/Documents/Colgram/Plugins if it doesn't exist;
                    // if a legacy folder already exists on disk, drop .nomedia and read existing plugins only.
                    File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    if (docsDir != null) {
                        File legacyRoot = new File(docsDir, "Colgram");
                        if (legacyRoot.isDirectory()) {
                            try {
                                new File(legacyRoot, ".nomedia").createNewFile();
                            } catch (Throwable ignored) {}
                            File legacyPlugins = new File(legacyRoot, "Plugins");
                            if (legacyPlugins.isDirectory() && !legacyPlugins.equals(externalPluginsDir)) {
                                try {
                                    new File(legacyPlugins, ".nomedia").createNewFile();
                                } catch (Throwable ignored) {}
                                legacyPublicPluginsDir = legacyPlugins;
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                installExteraCompatShim(internalPluginsDir);
                installBundledCatalog();
                reloadPlugins();
            } catch (Throwable err) {
                Log.e(TAG, "Failed to initialize ColgramPluginManager", err);
            }
        }, "colgram-plugins-init");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Copy every plugin the APK ships in assets/plugins into the live plugins directory.
     *
     * The four features that used to ship as Python plugins (auto reply, keyword alerts,
     * message logger, chat exporter) are native built-ins now, so they are deliberately NOT
     * installed any more - and stale copies from an older build are removed, because a
     * leftover auto_reply.py answered alongside the native auto-reply and made every incoming
     * message produce two replies. Only the exteraGram compatibility shim remains here, since
     * it is infrastructure for imported .plugin files, not a feature.
     */
    public static void installBundledCatalog() {
        if (appContext == null || internalPluginsDir == null) return;
        try {
            for (String name : new String[]{"auto_reply.py", "keyword_alerts.py",
                    "message_logger.py", "chat_exporter.py"}) {
                File stale = new File(internalPluginsDir, name);
                if (stale.exists() && stale.delete()) {
                    Log.i(TAG, "removed stale bundled plugin " + name + " (now a built-in feature)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "installBundledCatalog failed: " + t.getMessage());
        }
    }

    /** Packaged features are part of Colgram, not removable marketplace plugins. */
    public static boolean isBundledFeature(String fileName) {
        return "auto_reply.py".equals(fileName)
                || "keyword_alerts.py".equals(fileName)
                || "message_logger.py".equals(fileName)
                || "chat_exporter.py".equals(fileName);
    }

    public static synchronized void reloadPlugins() {
        loadedPlugins.clear();
        activeCommands.clear();

        if (internalPluginsDir == null) return;

        SharedPreferences prefs = appContext != null ? appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;

        // Register internal plugins directory in Python sys.path
        ColgramPythonEngine.addPythonPath(internalPluginsDir.getAbsolutePath());
        if (externalPluginsDir != null && externalPluginsDir.exists()) {
            ColgramPythonEngine.addPythonPath(externalPluginsDir.getAbsolutePath());
        }
        if (legacyPublicPluginsDir != null && legacyPublicPluginsDir.exists()) {
            ColgramPythonEngine.addPythonPath(legacyPublicPluginsDir.getAbsolutePath());
        }

        normalizePluginExtensions(internalPluginsDir);
        normalizePluginExtensions(externalPluginsDir);
        normalizePluginExtensionsToTarget(legacyPublicPluginsDir, internalPluginsDir);
        scanDir(internalPluginsDir, prefs);
        if (externalPluginsDir != null && externalPluginsDir.exists()) {
            scanDir(externalPluginsDir, prefs);
        }
        if (legacyPublicPluginsDir != null && legacyPublicPluginsDir.exists()) {
            scanDir(legacyPublicPluginsDir, prefs);
        }
        if (ColgramPythonEngine.isPythonReady()) {
            syncActiveExteraPlugins();
        }
    }

    /** Returns sanitized module names (without .py) for every currently enabled plugin. */
    public static java.util.List<String> getEnabledPythonModules() {
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (loadedPlugins) {
            for (PluginInfo p : loadedPlugins) {
                if (p != null && p.isEnabled && p.fileName != null && p.fileName.endsWith(".py")
                        && !"extera_compat.py".equals(p.fileName)) {
                    String mod = moduleNameFor(p.fileName);
                    if (mod != null && !out.contains(mod)) {
                        out.add(mod);
                    }
                }
            }
        }
        return out;
    }

    /** Synchronize active ExteraGram BasePlugin instances with the enabled plugin set. */
    public static void syncActiveExteraPlugins() {
        ColgramPythonEngine.syncExteraPluginsAsync(getEnabledPythonModules());
    }

    public static String moduleNameFor(String fileName) {
        if (fileName == null) return null;
        String stored = canonicalName(fileName);
        if (stored == null || !stored.endsWith(".py")) return null;
        String mod = stored.substring(0, stored.length() - 3);
        return mod.matches("[A-Za-z_][A-Za-z0-9_]*") ? mod : null;
    }

    public static String getPluginSettingsSchemaJson(String fileName) {
        String mod = moduleNameFor(fileName);
        if (mod == null) return "[]";
        return ColgramPythonEngine.getExteraSettingsJson(mod);
    }

    public static boolean updatePluginSetting(String fileName, String key, String valueJson) {
        String mod = moduleNameFor(fileName);
        if (mod == null) return false;
        return ColgramPythonEngine.updateExteraSetting(mod, key, valueJson);
    }

    /**
     * Give every {@code *.plugin} file a {@code *.py} twin.
     *
     * ".plugin" is how a Colgram plugin is meant to be shared and dropped in — a recognisable
     * extension instead of a bare .py — but the interpreter imports modules by file name, so the
     * extension itself cannot be loaded. Copying on scan means the user drops one file in and it
     * works; the original stays untouched so re-sharing it keeps working. A twin is refreshed
     * whenever the source is newer, so editing the .plugin still takes effect.
     */
    private static void normalizePluginExtensions(File dir) {
        normalizePluginExtensionsToTarget(dir, dir);
    }

    private static void normalizePluginExtensionsToTarget(File srcDir, File targetDir) {
        if (srcDir == null || !srcDir.exists() || targetDir == null || !targetDir.exists()) return;
        File[] files = srcDir.listFiles((d, name) -> name.toLowerCase().endsWith(".plugin"));
        if (files == null) return;
        for (File src : files) {
            try {
                String base = src.getName().substring(0, src.getName().length() - ".plugin".length());
                File twin = new File(targetDir, base + ".py");
                if (twin.exists() && twin.lastModified() >= src.lastModified()) continue;
                java.io.InputStream in = new java.io.FileInputStream(src);
                java.io.OutputStream out = new java.io.FileOutputStream(twin, false);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                Log.i(TAG, "installed plugin " + src.getName() + " as " + twin.getName());
            } catch (Throwable t) {
                Log.w(TAG, "cannot expand " + src.getName(), t);
            }
        }
    }

    private static void scanDir(File dir, SharedPreferences prefs) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".py"));
        if (files == null) return;

        for (File f : files) {
            try {
                String fileName = f.getName();
                if ("extera_compat.py".equals(fileName)) continue;
                boolean alreadyLoaded = false;
                synchronized (loadedPlugins) {
                    for (PluginInfo existing : loadedPlugins) {
                        if (existing != null && fileName.equals(existing.fileName)) {
                            alreadyLoaded = true;
                            break;
                        }
                    }
                }
                if (alreadyLoaded) continue;
                String name = fileName.replace(".py", "");
                String description = "Colgram Script Plugin";
                String author = "Colgram Team";
                String version = "1.0";
                String command = "";

                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                StringBuilder head = new StringBuilder();
                int headLines = 0;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (headLines++ < 120) head.append(line).append('\n');
                    if (line.startsWith("# name:")) description = line.substring(7).trim();
                    else if (line.startsWith("# title:")) name = line.substring(8).trim();
                    else if (line.startsWith("# author:")) author = line.substring(9).trim();
                    else if (line.startsWith("# version:")) version = line.substring(10).trim();
                    else if (line.startsWith("# command:")) command = line.substring(10).trim().toLowerCase();
                }
                r.close();

                // exteraGram plugin metadata (https://plugins.exteragram.app): Python string
                // constants such as __name__ = "Weather" declared at module top level.
                String h = head.toString();
                String v;
                if ((v = stringConst(h, "__name__")) != null) name = v;
                if ((v = stringConst(h, "__description__")) != null) description = v;
                if ((v = stringConst(h, "__author__")) != null) author = v;
                if ((v = stringConst(h, "__version__")) != null) version = v;
                if ((v = stringConst(h, "__command__")) != null) command = v.toLowerCase();
                if ((v = stringConst(h, "__min_version__")) != null && !description.contains("extera")) {
                    description = description + " (extera " + v + "+)";
                }

                boolean isEnabled = prefs == null || prefs.getBoolean("plugin_enabled_" + fileName, true);
                boolean builtIn = dir.equals(internalPluginsDir) && isBundledFeature(fileName);
                PluginInfo p = new PluginInfo(name, fileName, description, author, version, command, isEnabled, builtIn);
                loadedPlugins.add(p);

                if (isEnabled && !command.isEmpty()) {
                    activeCommands.put(command, p);
                }

            } catch (Throwable t) {
                Log.e(TAG, "Error parsing plugin: " + f.getName(), t);
            }
        }
    }

    /** Pull a module-level string constant (`__name__ = "..."`) out of the file head. */
    private static String stringConst(String head, String varName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^" + varName + "\\s*=\\s*[\"'](.+?)[\"']", java.util.regex.Pattern.MULTILINE)
                .matcher(head);
        return m.find() ? m.group(1) : null;
    }

    public static boolean hookOnSendMessage(long dialogId, int replyToMsgId, String text) {
        return hookOnSendMessage(ColgramPythonEngine.resolveSelectedAccount(), dialogId, replyToMsgId, text, 0L);
    }

    /**
     * Outgoing-message interceptor with the replied-to sender attached.
     *
     * The sender id is what makes .мут usable in a group: the command is typed as a reply, and
     * the person being muted is the author of the quoted message, not the chat itself.
     */
    public static boolean hookOnSendMessage(long dialogId, int replyToMsgId, String text, long replySenderId) {
        return hookOnSendMessage(ColgramPythonEngine.resolveSelectedAccount(), dialogId, replyToMsgId, text, replySenderId);
    }

    public static boolean hookOnSendMessage(int account, long dialogId, int replyToMsgId, String text, long replySenderId) {
        if (text != null && handleMuteCommand(dialogId, text, replySenderId)) return true;
        if (text == null || !text.startsWith(".") || dialogId == 0L) return false;
        final int safeAcc = account >= 0 && account < 5 ? account : ColgramPythonEngine.resolveSelectedAccount();

        String[] parts = text.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        // 1. Check custom active plugins
        if (activeCommands.containsKey(cmd)) {
            PluginInfo p = activeCommands.get(cmd);
            if (p != null && p.isEnabled) {
                executePluginCommand(safeAcc, dialogId, p, cmd, args);
                return true;
            }
        }

        // 2. Built-in command handlers
        if ("spam".equals(cmd)) {
            handleSpamCommand(safeAcc, dialogId, args);
            return true;
        } else if ("export".equals(cmd)) {
            handleExportCommand(dialogId);
            return true;
        } else if ("info".equals(cmd)) {
            handleInfoCommand(safeAcc, dialogId);
            return true;
        } else if ("calc".equals(cmd) || "py".equals(cmd) || "eval".equals(cmd)) {
            final long targetDialog = dialogId;
            final String codeArg = args;
            new Thread(() -> {
                String res = ColgramPythonEngine.executePluginCode(codeArg);
                if (res == null) {
                    res = ColgramPythonEngine.executeCode(codeArg);
                }
                ColgramPythonEngine.sendMessage(safeAcc, targetDialog, "🐍 Результат: " + res);
            }, "colgram-py-cmd").start();
            return true;
        } else if ("ping".equals(cmd) || "id".equals(cmd) || "del".equals(cmd) || "help".equals(cmd) || "plugins".equals(cmd)) {
            return ColgramPythonEngine.handleCommand(safeAcc, dialogId, 0, "." + cmd + (args == null || args.isEmpty() ? "" : (" " + args)));
        } else if ("rev".equals(cmd)) {
            ColgramPythonEngine.sendMessage(safeAcc, dialogId, new StringBuilder(args).reverse().toString());
            return true;
        } else if ("shrug".equals(cmd)) {
            ColgramPythonEngine.sendMessage(safeAcc, dialogId, args + " ¯\\_(ツ)_/¯");
            return true;
        } else if ("flip".equals(cmd)) {
            ColgramPythonEngine.sendMessage(safeAcc, dialogId, "(╯°□°)╯︵ ┻━┻");
            return true;
        }

        if (ColgramPythonEngine.isPythonReady() && ColgramPythonEngine.isShimCommandRegistered(cmd)) {
            new Thread(() -> dispatchExteraShimCommand(safeAcc, dialogId, cmd, args), "colgram-extera-cmd").start();
            return true;
        }

        return false;
    }

    public static boolean dispatchCommand(long dialogId, String cmd, String args) {
        return dispatchCommand(ColgramPythonEngine.resolveSelectedAccount(), dialogId, cmd, args);
    }

    public static boolean dispatchCommand(int account, long dialogId, String cmd, String args) {
        if (dialogId == 0L) return false;
        final int safeAcc = account >= 0 && account < 5 ? account : ColgramPythonEngine.resolveSelectedAccount();
        if (activeCommands.containsKey(cmd)) {
            PluginInfo p = activeCommands.get(cmd);
            if (p != null && p.isEnabled) {
                executePluginCommand(safeAcc, dialogId, p, cmd, args);
                return true;
            }
        }
        return dispatchExteraShimCommand(safeAcc, dialogId, cmd, args);
    }

    private static boolean dispatchExteraShimCommand(int account, long dialogId, String cmd, String args) {
        try {
            if (dialogId == 0L || !ColgramPythonEngine.isShimCommandRegistered(cmd)) {
                return false;
            }
            String result = ColgramPythonEngine.runShimCommand(account, dialogId, cmd, args);
            if (result != null && !result.isEmpty()) {
                ColgramPythonEngine.sendMessage(account, dialogId, result);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "extera shim dispatch failed for ." + cmd + ": " + t.getMessage());
            return false;
        }
    }

    public static String getLoadedPluginsSummary() {
        if (loadedPlugins.isEmpty()) {
            return "🧩 **Плагины Colgram**\nНет установленных плагинов.\nОткрой Настройки -> Плагины для установки из Маркетплейса.";
        }
        StringBuilder sb = new StringBuilder("🧩 **Установленные плагины Colgram (" + loadedPlugins.size() + "):**\n\n");
        for (PluginInfo p : loadedPlugins) {
            sb.append(p.isEnabled ? "✅ " : "⏸️ ").append("**").append(p.name).append("** (v").append(p.version).append(")\n");
            if (p.command != null && !p.command.isEmpty()) {
                sb.append("   • Команда: `.").append(p.command).append("`\n");
            }
            sb.append("   • ").append(p.description).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * .мут / .unmute — silence or restore a sender's pings without leaving the chat.
     *
     * The target is the author of the quoted message when the command is sent as a reply, and
     * the private-chat peer otherwise. It feeds the same notifications-block list the
     * NotificationsController hook consults, so a muted sender's messages still arrive but never
     * ring, buzz or light the screen. The command message itself is swallowed (returns true) so
     * the chat is not littered with ".мут".
     */
    private static boolean handleMuteCommand(long dialogId, String text, long replySenderId) {
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase();
        boolean mute;
        if (lower.equals(".мут") || lower.equals(".mute") || lower.startsWith(".мут ") || lower.startsWith(".mute ")) {
            mute = true;
        } else if (lower.equals(".unmute") || lower.equals(".размут")
                || lower.startsWith(".unmute ") || lower.startsWith(".размут ")) {
            mute = false;
        } else {
            return false;
        }
        long target = replySenderId != 0 ? replySenderId : (dialogId > 0 ? dialogId : 0);
        if (target == 0) {
            toast("`.мут` — в личке или ответом на сообщение того, кого заглушить");
            return true;
        }
        ColgramHookHandler.setNotificationsBlocked(target, mute);
        toast(mute ? "Пинги от пользователя отключены" : "Пинги от пользователя включены");
        return true;
    }

    private static void toast(final String text) {
        try {
            final Context ctx = ColgramPythonEngine.appContext();
            if (ctx == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {
        }
    }

    private static void handleSpamCommand(int account, long dialogId, String args) {
        new Thread(() -> {
            try {
                String[] parts = args.split("\\s+", 2);
                int count = Integer.parseInt(parts[0]);
                String msg = parts.length > 1 ? parts[1] : "Spam test";
                count = Math.min(Math.max(1, count), 50); // limit 1..50 for safety
                for (int i = 0; i < count; i++) {
                    ColgramPythonEngine.sendMessage(account, dialogId, msg);
                    Thread.sleep(300);
                }
            } catch (Throwable t) {
                ColgramPythonEngine.sendMessage(account, dialogId, "Использование: `.spam <кол-во> <текст>`");
            }
        }).start();
    }

    /**
     * Built-in chat export (the former chat_exporter.py plugin, now native).
     */
    private static void handleExportCommand(long dialogId) {
        toast("Собираю экспорт диалога…");
        new Thread(() -> {
            final java.io.File file = ColgramChatExport.exportDialog(dialogId);
            if (file != null) {
                toast("Экспорт готов: " + file.getAbsolutePath());
            } else {
                toast("Экспорт не удался: в кэше нет сообщений этого диалога");
            }
        }, "colgram-export").start();
    }

    private static void handleInfoCommand(int account, long dialogId) {
        String appVer = "12.10.6";
        try {
            Object v = Class.forName("org.telegram.messenger.BuildVars")
                    .getField("BUILD_VERSION_STRING").get(null);
            if (v != null && !v.toString().trim().isEmpty()) {
                appVer = v.toString().trim();
            }
        } catch (Throwable ignored) {}
        String info = "ℹ️ **Colgram Chat Info**\n"
                + "• Dialog ID: `" + dialogId + "`\n"
                + "• Client: `Colgram v" + appVer + "`\n"
                + "• Plugins Loaded: `" + loadedPlugins.size() + "`\n"
                + "• Engine: `CPython 3.11 Embedded`\n"
                + "• DPI Bypass: `" + (ColgramDpiBypass.isRunning() ? "Встроенный маршрут активен" : "Offline") + "`";
        ColgramPythonEngine.sendMessage(account, dialogId, info);
    }

    private static final java.util.concurrent.ExecutorService PLUGIN_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "colgram-plugin-worker");
                t.setDaemon(true);
                return t;
            });

    private static void executePluginCommand(int account, long dialogId, PluginInfo plugin, String cmd, String args) {
        if (dialogId == 0L) return;
        PLUGIN_EXECUTOR.execute(() -> {
            try {
                String module = plugin.fileName.substring(0, plugin.fileName.length() - 3);
                if (!module.matches("[A-Za-z_][A-Za-z0-9_]*")) return;
                String encoded = android.util.Base64.encodeToString(
                        args.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                String encodedCmd = android.util.Base64.encodeToString(
                        (cmd != null ? cmd : "").getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                String firstArg = plugin.builtIn
                        ? Long.toString(dialogId)
                        : "base64.b64decode('" + encodedCmd + "').decode('utf-8')";
                String pySnippet = "import base64, importlib\n"
                        + "_m = importlib.import_module('" + module + "')\n"
                        + "_f = getattr(_m, 'on_command', None)\n"
                        + "if _f is not None:\n"
                        + "    _a = base64.b64decode('" + encoded + "').decode('utf-8')\n"
                        + "    _r = _f(" + firstArg + ", _a)\n"
                        + "    if _r: print(_r)\n";
                String output = ColgramPythonEngine.executePluginCode(pySnippet);
                if (output != null && !output.trim().isEmpty() && !output.startsWith("Executed")) {
                    ColgramPythonEngine.sendMessage(account, dialogId, output.trim());
                }
            } catch (Throwable t) {
                Log.e(TAG, "Plugin execution error", t);
            }
        });
    }

    public static void hookOnMessageReceived(long dialogId, int messageId, String text, boolean isOut) {
        hookOnMessageReceived(ColgramPythonEngine.resolveSelectedAccount(), dialogId, messageId, text, isOut);
    }

    public static void hookOnMessageReceived(int account, long dialogId, int messageId, String text, boolean isOut) {
        if (isOut || text == null || dialogId == 0L
                || ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(dialogId)) return;
        final int safeAcc = account >= 0 && account < 5 ? account : ColgramPythonEngine.resolveSelectedAccount();
        // Fast bail-out BEFORE scheduling any worker thread if zero enabled plugins exist!
        List<PluginInfo> enabledPlugins = null;
        synchronized (loadedPlugins) {
            for (PluginInfo p : loadedPlugins) {
                if (p != null && p.isEnabled && p.fileName != null && p.fileName.endsWith(".py")) {
                    if (enabledPlugins == null) enabledPlugins = new ArrayList<>();
                    enabledPlugins.add(p);
                }
            }
        }
        if (enabledPlugins == null || enabledPlugins.isEmpty()) return;
        final List<PluginInfo> snapshot = enabledPlugins;
        final String messageText = text;
        PLUGIN_EXECUTOR.execute(() -> {
            for (PluginInfo p : snapshot) {
                String module = p.fileName;
                int dot = module.lastIndexOf('.');
                if (dot > 0) module = module.substring(0, dot);
                if (!module.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                try {
                    String b64 = android.util.Base64.encodeToString(
                            messageText.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                    String snippet = "import base64, importlib\n"
                            + "_m = importlib.import_module('" + module + "')\n"
                            + "_f = getattr(_m, 'on_message', None)\n"
                            + "if _f is not None:\n"
                            + "    _t = base64.b64decode('" + b64 + "').decode('utf-8')\n"
                            + "    _r = _f(" + dialogId + ", _t)\n"
                            + "    if _r:\n"
                            + "        print(_r)\n";
                    String out = ColgramPythonEngine.executePluginCode(snippet);
                    if (out == null) continue;
                    String reply = out.trim();
                    if (reply.isEmpty() || reply.startsWith("Executed")) continue;
                    ColgramPythonEngine.sendMessage(safeAcc, dialogId, reply);
                    Log.i(TAG, "plugin " + p.fileName + " replied to message " + messageId + " on acc=" + safeAcc);
                } catch (Throwable t) {
                    Log.w(TAG, "plugin " + p.fileName + " on_message failed: " + t.getMessage());
                }
            }
        });
    }

    public static boolean installPlugin(String fileName, String code) {
        return installPluginInternal(false, fileName, code);
    }

    private static boolean installPluginInternal(boolean bundled, String fileName, String code) {
        try {
            if (internalPluginsDir == null) return false;
            if (fileName == null || code == null || code.trim().isEmpty()) return false;
            // ".plugin" is the share/import extension (exteraGram ships its Python plugins as
            // .plugin files); the interpreter imports modules by file name, so it becomes .py.
            String base = fileName;
            String lower = base.toLowerCase();
            if (lower.endsWith(".plugin")) {
                base = base.substring(0, base.length() - ".plugin".length());
            } else if (lower.endsWith(".py")) {
                base = base.substring(0, base.length() - ".py".length());
            } else if (lower.endsWith(".txt") || lower.endsWith(".json")) {
                base = base.substring(0, base.lastIndexOf('.'));
            }
            // Sanitize rather than reject. exteraStore plugins commonly carry names like
            // "my-plugin-1.2", and rejecting them made installing a downloaded plugin look
            // impossible ("плагины нельзя загрузить"). A valid python module name is what the
            // interpreter needs, and a lossy rename loses nothing that matters.
            String pyName = moduleName(base) + ".py";
            if (!bundled && (isBundledFeature(pyName) || "extera_compat.py".equals(pyName))) return false;
            File target = new File(internalPluginsDir, pyName);
            try (FileWriter w = new FileWriter(target)) {
                w.write(code);
            }
            reloadPlugins();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "installPlugin error", t);
            return false;
        }
    }

    /**
     * Install a plugin that ships inside the APK, by file name.
     *
     * The bundled catalog entries deliberately carry no download URL, so the offline
     * fallback needs a way to install from the APK's own assets. Without this the
     * marketplace would list entries that cannot be installed whenever the network is
     * unavailable — which is exactly when the fallback catalog is shown.
     *
     * Looks in assets/plugins/ for the given name.
     */
    public static boolean installBundledPlugin(String fileName) {
        if (appContext == null || internalPluginsDir == null) return false;
        try {
            InputStream in = appContext.getAssets().open("plugins/" + fileName);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            String code = new String(bos.toByteArray(), "UTF-8");
            if (code.trim().isEmpty()) return false;
            return installPluginInternal(true, fileName, code);
        } catch (Throwable t) {
            Log.w(TAG, "installBundledPlugin(" + fileName + ") failed: " + t.getMessage());
            return false;
        }
    }

    public static boolean deletePlugin(String fileName) {
        try {
            if (isBundledFeature(fileName) || "extera_compat.py".equals(fileName)) return false;
            String mod = moduleNameFor(fileName);
            if (mod != null) {
                ColgramPythonEngine.unloadExteraPluginAsync(mod);
            }
            boolean deleted = false;
            // Delete BOTH extensions of the base name, not just the one the caller named.
            //
            // A plugin imported as .plugin is stored twice: the .plugin the user picked and the .py
            // twin the interpreter imports, because the extension is rewritten on install. The
            // listing carries the .py name - it is the one that was scanned - so deleting the name the
            // caller happened to use removed a file and left the plugin alive:
            //
            //   a plugin that was just deleted is still listed: colgram_import_probe.py
            //
            // which is a switch on the plugins screen that turns something back on. The twins are
            // created together and have to die together.
            for (String name : twinNames(fileName)) {
                if (internalPluginsDir != null) {
                    File f = new File(internalPluginsDir, name);
                    if (f.exists()) deleted |= f.delete();
                }
                if (externalPluginsDir != null) {
                    File f2 = new File(externalPluginsDir, name);
                    if (f2.exists()) deleted |= f2.delete();
                }
                if (legacyPublicPluginsDir != null) {
                    File f3 = new File(legacyPublicPluginsDir, name);
                    if (f3.exists()) deleted |= f3.delete();
                }
            }
            reloadPlugins();
            return deleted;
        } catch (Throwable t) {
            Log.e(TAG, "deletePlugin error", t);
            return false;
        }
    }

    /**
     * Every file name one plugin can be stored under: the name given, and its other extension.
     *
     * <p>Deliberately not transitive. {@code normalizePluginExtensions} copies .plugin to .py, but
     * the copy is a snapshot: editing the .plugin afterwards rewrites the .py rather than the .py
     * being a second source of truth, so there are exactly two files and no chain.
     */
    private static String[] twinNames(String fileName) {
        String lower = fileName.toLowerCase();
        String base;
        if (lower.endsWith(".plugin")) {
            base = fileName.substring(0, fileName.length() - ".plugin".length());
        } else if (lower.endsWith(".py")) {
            base = fileName.substring(0, fileName.length() - ".py".length());
        } else {
            return new String[]{fileName};
        }
        // Sanitized the same way installPlugin does, because that is what decided the stored name.
        //
        // exteraStore plugins are commonly named "my-plugin-1.2", and installPlugin rewrites that to
        // my_plugin_1_2.py. A delete that only stripped the extension looked for "my-plugin-1.2.py",
        // found nothing, and left the plugin installed with its toggle still on screen - the file the
        // user picked and the file the manager stored are not the same name, and only one of the two
        // paths knew that.
        String py = moduleName(base) + ".py";
        String plugin = moduleName(base) + ".plugin";
        // The original spelling too, so a file written before a rename is still reachable.
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.add(fileName);
        names.add(base + ".plugin");
        names.add(base + ".py");
        names.add(py);
        names.add(plugin);
        return names.toArray(new String[0]);
    }

    /**
     * The module name a plugin file is stored under: lower case, non-alphanumerics folded to _.
     *
     * <p>One implementation, because installPlugin and deletePlugin disagreeing about this is what
     * produced a plugin that could be installed and not removed. Everything that has to name a
     * stored plugin goes through here.
     */
    private static String moduleName(String base) {
        StringBuilder module = new StringBuilder();
        for (char c : base.toLowerCase().replace(' ', '_').toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') module.append(c);
            else if (module.length() == 0 || module.charAt(module.length() - 1) != '_') module.append('_');
        }
        while (module.length() > 0 && module.charAt(module.length() - 1) == '_') {
            module.setLength(module.length() - 1);
        }
        if (module.length() == 0) module.append("plugin");
        if (module.charAt(0) >= '0' && module.charAt(0) <= '9') module.insert(0, 'p');
        return module.toString();
    }

    /**
     * The name this plugin is stored and listed under, or the caller's name when it is already one.
     *
     * <p>A .py or .plugin name passes through the same sanitiser installPlugin uses, which makes this
     * idempotent: canonicalName("my_plugin.py") is "my_plugin.py".
     */
    private static String canonicalName(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        String base = fileName;
        if (lower.endsWith(".plugin")) base = fileName.substring(0, fileName.length() - 7);
        else if (lower.endsWith(".py")) base = fileName.substring(0, fileName.length() - 3);
        return moduleName(base) + ".py";
    }

    public static void togglePlugin(String fileName, boolean enabled) {
        // The prefs key and the in-memory entry must both be written under the name the plugin is
        // actually stored and listed as, which is the sanitized .py twin. Writing them under the name
        // the caller passed produces a switch that survives a restart in the wrong position - the flag
        // is read from plugin_enabled_my-plugin.plugin while the row it belongs to is my_plugin.py.
        String stored = canonicalName(fileName);
        if (appContext != null) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("plugin_enabled_" + stored, enabled)
                    .apply();
        }
        boolean updated = false;
        for (PluginInfo p : loadedPlugins) {
            if (p.fileName.equals(stored)) {
                p.isEnabled = enabled;
                if (!p.command.isEmpty()) {
                    if (enabled) activeCommands.put(p.command, p);
                    else activeCommands.remove(p.command);
                }
                updated = true;
            }
        }
        if (updated) {
            // Ensure all duplicate entries remain consistent without early break
        }
        String mod = moduleNameFor(stored);
        if (mod != null && ColgramPythonEngine.isPythonReady()) {
            if (enabled) {
                syncActiveExteraPlugins();
            } else {
                ColgramPythonEngine.unloadExteraPluginAsync(mod);
            }
        }
    }

    public static String getPluginCode(String fileName) {
        try {
            // Every spelling the plugin may be stored under, not just the caller's. The UI asks for
            // code with the name it was given at import time and got an empty editor back, because
            // the file on disk is the sanitized .py twin.
            File f = findPluginFile(fileName);
            if (f != null && f.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(f));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                return sb.toString();
            }
        } catch (Throwable t) {
            Log.e(TAG, "getPluginCode error", t);
        }
        return "";
    }

    public static boolean isPluginInstalled(String fileName) {
        return findPluginFile(fileName) != null;
    }

    /**
     * The first file that actually holds this plugin, or null.
     *
     * <p>The caller says what the plugin is called and the manager decides what it is called on disk,
     * so every lookup has to try the same set installPlugin writes. Asking for one exact name is what
     * made "edit", "is it installed" and "delete" disagree with "import".
     */
    private static File findPluginFile(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return null;
        for (String name : twinNames(fileName)) {
            if (internalPluginsDir != null) {
                File f = new File(internalPluginsDir, name);
                if (f.exists()) return f;
            }
            if (externalPluginsDir != null) {
                File f = new File(externalPluginsDir, name);
                if (f.exists()) return f;
            }
            if (legacyPublicPluginsDir != null) {
                File f = new File(legacyPublicPluginsDir, name);
                if (f.exists()) return f;
            }
        }
        return null;
    }

    public static List<PluginInfo> getLoadedPlugins() {
        return new ArrayList<>(loadedPlugins);
    }

    /**
     * Write the bundled exteraGram compatibility shim into the plugins directory.
     *
     * The shim is read from assets/plugins/extera_compat.py and written as
     * extera_compat.py. It is refreshed on every start (unlike user plugins, which are
     * only created when the directory is empty) because it is infrastructure that must
     * track the Colgram version.
     *
     * Also exports COLGRAM_PLUGINS_DIR so the Python bootstrap can locate it.
     */
    private static void installExteraCompatShim(File dir) {
        if (dir == null || appContext == null) return;
        try {
            // Export the plugins dir for the Python bootstrap.
            System.setProperty("COLGRAM_PLUGINS_DIR", dir.getAbsolutePath());

            java.io.InputStream in = appContext.getAssets().open("plugins/extera_compat.py");
            File out = new File(dir, "extera_compat.py");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            fos.close();
            in.close();
            Log.i(TAG, "exteraGram compat shim installed at " + out.getAbsolutePath());
        } catch (Throwable t) {
            // Missing asset is not fatal: plugins simply will not have the shim.
            Log.w(TAG, "Could not install exteraGram compat shim: " + t.getMessage());
        }
    }

    private static void createDefaultPluginsIfEmpty(File dir) {        if (dir == null) return;
        File[] existing = dir.listFiles((d, n) -> n.endsWith(".py"));
        if (existing != null && existing.length > 0) return;

        // 1. Spammer Plugin
        writeDefault(new File(dir, "spammer.py"),
                "# name: Спамер сообщений\n# author: Colgram\n# version: 1.2\n# command: spam\n\n"
                        + "def on_command(cmd, args):\n"
                        + "    return 'Используйте: .spam <кол-во> <текст>'\n");

        // 2. Chat Info Plugin
        writeDefault(new File(dir, "chat_info.py"),
                "# name: Информация о чате и юзере\n# author: Colgram\n# version: 1.0\n# command: info\n\n"
                        + "def on_command(cmd, args):\n"
                        + "    return 'ℹ️ Чат и статус проверены'\n");

        // 3. AFK Bot Plugin
        writeDefault(new File(dir, "afk_bot.py"),
                "# name: Автоответчик AFK\n# author: Colgram\n# version: 1.1\n# command: afk\n\n"
                        + "is_afk = False\nreason = ''\n\n"
                        + "def on_command(cmd, args):\n"
                        + "    global is_afk, reason\n"
                        + "    is_afk = not is_afk\n"
                        + "    reason = args if is_afk else ''\n"
                        + "    return f'💤 Режим AFK {\"включен: \" + reason if is_afk else \"выключен\"}'\n");

        // 4. Voice Spoofer Plugin
        writeDefault(new File(dir, "voice_spoofer.py"),
                "# name: Голосовой спуфер\n# author: Colgram\n# version: 1.0\n# command: voice\n\n"
                        + "def on_command(cmd, args):\n"
                        + "    return '🎙 Голосовой спуфер активен'\n");
    }

    private static void writeDefault(File file, String content) {
        try (FileWriter w = new FileWriter(file)) {
            w.write(content);
        } catch (Throwable ignored) {}
    }

    // ==================================================================================
    // Marketplace
    // ==================================================================================

    /** A plugin listed in the remote catalog. */
    public static class CatalogEntry {
        public final String name;
        public final String author;
        public final String description;
        public final String version;
        public final String downloadUrl;

        public CatalogEntry(String name, String author, String description,
                            String version, String downloadUrl) {
            this.name = name;
            this.author = author;
            this.description = description;
            this.version = version;
            this.downloadUrl = downloadUrl;
        }
    }

    /**
     * Default catalog. A plain JSON array hosted in the repo, so publishing a plugin is
     * just a PR — no server to run. Shape:
     *   [{"name":"...","author":"...","description":"...","version":"1.0","url":"https://..."}]
     */
    public static final String DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/Kleisym/Colgram/main/plugins/catalog.json";

    public static String getCatalogUrl() {
        if (appContext == null) return DEFAULT_CATALOG_URL;
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("catalog_url", DEFAULT_CATALOG_URL);
    }

    public static void setCatalogUrl(String url) {
        if (appContext != null) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("catalog_url", url).apply();
        }
    }

    /**
     * Catalog that ships inside the APK, used when the remote catalog is unreachable.
     *
     * This exists because the remote catalog is a hard dependency on a network fetch
     * that can fail for reasons outside the user's control: the repo may be private
     * (raw.githubusercontent.com is unauthenticated and returns 404 for private
     * repos), the device may be offline, or GitHub may be rate-limiting. Without a
     * bundled fallback the marketplace shows an empty list in every one of those
     * cases, which reads to the user as "the feature is broken".
     *
     * Entries here are installed from bundled code, so `url` may be empty — the UI
     * is expected to treat a blank url as "install from the copy already on device".
     */
    private static final String BUILTIN_CATALOG_JSON =
            "[" +
            "{\"name\":\"Message Logger\"," +
            " \"author\":\"Colgram\"," +
            " \"description\":\"Saves incoming and outgoing messages to a local JSONL file, including edits and deletions.\"," +
            " \"version\":\"1.0\"," +
            " \"url\":\"https://raw.githubusercontent.com/Kleisym/Colgram/main/plugins/message_logger.py\"}," +
            "{\"name\":\"Auto Reply\"," +
            " \"author\":\"Colgram\"," +
            " \"description\":\"Replies to private messages with a canned response while you are away.\"," +
            " \"version\":\"1.0\"," +
            " \"url\":\"https://raw.githubusercontent.com/Kleisym/Colgram/main/plugins/auto_reply.py\"}," +
            "{\"name\":\"Chat Exporter\"," +
            " \"author\":\"Colgram\"," +
            " \"description\":\"Exports chat history to a portable HTML file.\"," +
            " \"version\":\"1.0\"," +
            " \"url\":\"https://raw.githubusercontent.com/Kleisym/Colgram/main/plugins/chat_exporter.py\"}," +
            "{\"name\":\"Keyword Alerts\"," +
            " \"author\":\"Colgram\"," +
            " \"description\":\"Watches chats for keywords and reports matches.\"," +
            " \"version\":\"1.0\"," +
            " \"url\":\"https://raw.githubusercontent.com/Kleisym/Colgram/main/plugins/keyword_alerts.py\"}" +
            "]";

    /**
     * Fetch and parse the remote catalog.
     *
     * Deliberately a tiny hand-rolled JSON reader: pulling in a JSON library for one
     * flat array of objects is not worth the dependency, and the schema is fixed.
     * Never throws — falls back to the bundled catalog so the marketplace is never
     * empty, then to an empty list.
     */
    public static List<CatalogEntry> fetchCatalog() {
        List<CatalogEntry> out = parseCatalog(httpGet(getCatalogUrl()));
        if (out.isEmpty()) {
            // Remote failed (offline, rate-limited, repo unreachable). Show the bundled
            // set rather than an empty screen.
            lastCatalogRemote = false;
            out = parseCatalog(BUILTIN_CATALOG_JSON);
        } else {
            lastCatalogRemote = true;
        }
        return out;
    }

    /**
     * Whether the most recent fetchCatalog() call returned the live remote catalog.
     *
     * The marketplace UI needs this to be honest with the user: an offline fallback and a
     * live catalog look identical in the list, and showing "12 plugins" without saying the
     * network failed hides the fact that nothing new is available.
     */
    private static volatile boolean lastCatalogRemote = false;

    public static boolean lastCatalogWasRemote() {
        return lastCatalogRemote;
    }

    /**
     * File name a catalog entry installs as.
     *
     * The catalog schema carries a download URL and no explicit name, so derive it from
     * the URL's last path segment. Falls back to a slug of the display name when the URL
     * has no usable segment, so a bundled entry (blank URL) still gets a stable file name.
     */
    public static String fileNameFor(CatalogEntry entry) {
        if (entry == null) return "plugin.py";
        String url = entry.downloadUrl;
        if (url != null && !url.trim().isEmpty()) {
            String last = url.substring(url.lastIndexOf('/') + 1);
            int q = last.indexOf('?');
            if (q >= 0) last = last.substring(0, q);
            if (last.endsWith(".py")) return last;
            if (!last.isEmpty()) return last + ".py";
        }
        StringBuilder slug = new StringBuilder();
        String name = entry.name == null ? "plugin" : entry.name.toLowerCase(java.util.Locale.US);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) slug.append(c);
            else if (slug.length() > 0 && slug.charAt(slug.length() - 1) != '_') slug.append('_');
        }
        String s = slug.toString();
        while (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) s = "plugin";
        return s + ".py";
    }

    /** Parse a flat JSON array of catalog objects. Never throws. */
    private static List<CatalogEntry> parseCatalog(String json) {
        List<CatalogEntry> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            int i = 0;
            while (true) {
                int objStart = json.indexOf('{', i);
                if (objStart < 0) break;
                int objEnd = json.indexOf('}', objStart);
                if (objEnd < 0) break;
                String obj = json.substring(objStart + 1, objEnd);
                String name = jsonField(obj, "name");
                // A name is the minimum for a usable row. An empty url is allowed and
                // means "this entry ships with the app" — see BUILTIN_CATALOG_JSON.
                if (name != null && !name.isEmpty()) {
                    out.add(new CatalogEntry(
                            name,
                            orEmpty(jsonField(obj, "author")),
                            orEmpty(jsonField(obj, "description")),
                            orEmpty(jsonField(obj, "version")),
                            orEmpty(jsonField(obj, "url"))));
                }
                i = objEnd + 1;
            }
        } catch (Throwable t) {
            Log.e(TAG, "catalog parse error", t);
        }
        return out;
    }


    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Extract a flat string field from a JSON object body. */
    private static String jsonField(String obj, String key) {
        String needle = "\"" + key + "\"";
        int k = obj.indexOf(needle);
        if (k < 0) return null;
        int colon = obj.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int q1 = obj.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int p = q1 + 1; p < obj.length(); p++) {
            char c = obj.charAt(p);
            if (c == '\\' && p + 1 < obj.length()) {
                sb.append(obj.charAt(++p));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Install a plugin straight from a URL — this is what makes the catalog work, and
     * also what backs "install from link" in the UI.
     */
    public static boolean installPluginFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String code = httpGet(url);
        if (code == null || code.trim().isEmpty()) return false;
        String fileName = fileNameFromUrl(url);
        if (fileName == null) return false;
        return installPlugin(fileName, code);
    }

    /** Derive a safe on-disk filename from a plugin URL. */
    private static String fileNameFromUrl(String url) {
        try {
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int slash = path.lastIndexOf('/');
            String base = slash >= 0 ? path.substring(slash + 1) : path;
            if (base.isEmpty()) return null;
            // Only permit a bare filename — never let a crafted URL escape the plugin dir.
            if (base.contains("..") || base.contains("/") || base.contains("\\")) return null;
            if (!base.endsWith(".py") && !base.endsWith(".plugin") && !base.endsWith(".json") && !base.endsWith(".txt")) {
                base = base + ".py";
            }
            return base;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Proxy/DoH/relay-aware HTTP GET returning the body as text, or null on any failure. */
    private static String httpGet(String urlStr) {
        if (urlStr == null || urlStr.trim().isEmpty()) return null;
        try {
            ColgramHttp.Response resp = ColgramHttp.get(urlStr);
            if (resp != null && resp.code >= 200 && resp.code < 300 && resp.body != null) {
                return resp.body;
            }
        } catch (Throwable t) {
            Log.e(TAG, "httpGet failed for " + urlStr, t);
        }
        return null;
    }
}
