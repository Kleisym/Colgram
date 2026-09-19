package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
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
        public boolean isEnabled = true;

        public PluginInfo(String name, String fileName, String description, String author, String version, String command, boolean isEnabled) {
            this.name = name;
            this.fileName = fileName;
            this.description = description;
            this.author = author;
            this.version = version;
            this.command = command;
            this.isEnabled = isEnabled;
        }
    }

    private static final List<PluginInfo> loadedPlugins = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, PluginInfo> activeCommands = Collections.synchronizedMap(new HashMap<>());
    private static Context appContext = null;
    private static File internalPluginsDir = null;
    private static File externalPluginsDir = null;

    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        try {
            // 1. Internal secure plugins directory (zero permissions needed on Android 10-14)
            internalPluginsDir = new File(appContext.getFilesDir(), "plugins");
            if (!internalPluginsDir.exists()) {
                internalPluginsDir.mkdirs();
            }

            // 2. Optional external directory for user-dropped files
            try {
                File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                externalPluginsDir = new File(docsDir, "Colgram/Plugins");
                if (!externalPluginsDir.exists()) {
                    externalPluginsDir.mkdirs();
                }
            } catch (Throwable ignored) {}

            // 3. Create default built-in plugins if none exist
            createDefaultPluginsIfEmpty(internalPluginsDir);

            // 4. Reload
            reloadPlugins();

        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize ColgramPluginManager", t);
        }
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

        scanDir(internalPluginsDir, prefs);
        if (externalPluginsDir != null && externalPluginsDir.exists()) {
            scanDir(externalPluginsDir, prefs);
        }
    }

    private static void scanDir(File dir, SharedPreferences prefs) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".py"));
        if (files == null) return;

        for (File f : files) {
            try {
                String fileName = f.getName();
                String name = fileName.replace(".py", "");
                String description = "Colgram Script Plugin";
                String author = "Colgram Team";
                String version = "1.0";
                String command = "";

                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("# name:")) description = line.substring(7).trim();
                    else if (line.startsWith("# title:")) name = line.substring(8).trim();
                    else if (line.startsWith("# author:")) author = line.substring(9).trim();
                    else if (line.startsWith("# version:")) version = line.substring(10).trim();
                    else if (line.startsWith("# command:")) command = line.substring(10).trim().toLowerCase();
                }
                r.close();

                boolean isEnabled = prefs == null || prefs.getBoolean("plugin_enabled_" + fileName, true);
                PluginInfo p = new PluginInfo(name, fileName, description, author, version, command, isEnabled);
                loadedPlugins.add(p);

                if (isEnabled && !command.isEmpty()) {
                    activeCommands.put(command, p);
                }

            } catch (Throwable t) {
                Log.e(TAG, "Error parsing plugin: " + f.getName(), t);
            }
        }
    }

    public static boolean hookOnSendMessage(long dialogId, int replyToMsgId, String text) {
        if (text == null || !text.startsWith(".")) return false;

        String[] parts = text.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        // 1. Check custom active plugins
        if (activeCommands.containsKey(cmd)) {
            PluginInfo p = activeCommands.get(cmd);
            if (p != null && p.isEnabled) {
                executePluginCommand(dialogId, p, cmd, args);
                return true;
            }
        }

        // 2. Built-in command handlers
        if ("spam".equals(cmd)) {
            handleSpamCommand(dialogId, args);
            return true;
        } else if ("info".equals(cmd)) {
            handleInfoCommand(dialogId);
            return true;
        } else if ("calc".equals(cmd) || "py".equals(cmd) || "eval".equals(cmd)) {
            ColgramPythonEngine.sendMessage(dialogId, "🐍 Результат: " + ColgramPythonEngine.executeCode(args));
            return true;
        } else if ("rev".equals(cmd)) {
            ColgramPythonEngine.sendMessage(dialogId, new StringBuilder(args).reverse().toString());
            return true;
        } else if ("shrug".equals(cmd)) {
            ColgramPythonEngine.sendMessage(dialogId, args + " ¯\\_(ツ)_/¯");
            return true;
        } else if ("flip".equals(cmd)) {
            ColgramPythonEngine.sendMessage(dialogId, "(╯°□°)╯︵ ┻━┻");
            return true;
        }

        return false;
    }

    private static void handleSpamCommand(long dialogId, String args) {
        new Thread(() -> {
            try {
                String[] parts = args.split("\\s+", 2);
                int count = Integer.parseInt(parts[0]);
                String msg = parts.length > 1 ? parts[1] : "Spam test";
                count = Math.min(Math.max(1, count), 50); // limit 1..50 for safety
                for (int i = 0; i < count; i++) {
                    ColgramPythonEngine.sendMessage(dialogId, msg);
                    Thread.sleep(300);
                }
            } catch (Throwable t) {
                ColgramPythonEngine.sendMessage(dialogId, "Использование: `.spam <кол-во> <текст>`");
            }
        }).start();
    }

    private static void handleInfoCommand(long dialogId) {
        String info = "ℹ️ **Colgram Chat Info**\n"
                + "• Dialog ID: `" + dialogId + "`\n"
                + "• Client: `Colgram v11.1.3`\n"
                + "• Plugins Loaded: `" + loadedPlugins.size() + "`\n"
                + "• Engine: `CPython 3.11 Embedded`\n"
                + "• DPI Bypass: `" + (ColgramDpiBypass.isRunning() ? "Active (127.0.0.1:9876)" : "Offline") + "`";
        ColgramPythonEngine.sendMessage(dialogId, info);
    }

    private static void executePluginCommand(long dialogId, PluginInfo plugin, String cmd, String args) {
        new Thread(() -> {
            try {
                String pySnippet = "import " + plugin.name + "\n"
                        + "if hasattr(" + plugin.name + ", 'on_command'):\n"
                        + "    res = " + plugin.name + ".on_command('" + cmd + "', '''" + args.replace("'", "\\'") + "''')\n"
                        + "    if res: print(res)";
                String output = ColgramPythonEngine.executeCode(pySnippet);
                if (output != null && !output.trim().isEmpty() && !output.startsWith("Executed")) {
                    ColgramPythonEngine.sendMessage(dialogId, output.trim());
                }
            } catch (Throwable t) {
                Log.e(TAG, "Plugin execution error", t);
            }
        }).start();
    }

    public static void hookOnMessageReceived(long dialogId, int messageId, String text, boolean isOut) {
        // Broadcast incoming events to active plugins
    }

    public static boolean installPlugin(String fileName, String code) {
        try {
            if (internalPluginsDir == null) return false;
            File target = new File(internalPluginsDir, fileName);
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

    public static boolean deletePlugin(String fileName) {
        try {
            boolean deleted = false;
            if (internalPluginsDir != null) {
                File f = new File(internalPluginsDir, fileName);
                if (f.exists()) deleted = f.delete();
            }
            if (externalPluginsDir != null) {
                File f2 = new File(externalPluginsDir, fileName);
                if (f2.exists()) deleted |= f2.delete();
            }
            reloadPlugins();
            return deleted;
        } catch (Throwable t) {
            Log.e(TAG, "deletePlugin error", t);
            return false;
        }
    }

    public static void togglePlugin(String fileName, boolean enabled) {
        if (appContext != null) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("plugin_enabled_" + fileName, enabled)
                    .apply();
        }
        for (PluginInfo p : loadedPlugins) {
            if (p.fileName.equals(fileName)) {
                p.isEnabled = enabled;
                if (!p.command.isEmpty()) {
                    if (enabled) activeCommands.put(p.command, p);
                    else activeCommands.remove(p.command);
                }
                break;
            }
        }
    }

    public static String getPluginCode(String fileName) {
        try {
            File f = internalPluginsDir != null ? new File(internalPluginsDir, fileName) : null;
            if (f == null || !f.exists()) {
                if (externalPluginsDir != null) f = new File(externalPluginsDir, fileName);
            }
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
        if (internalPluginsDir != null && new File(internalPluginsDir, fileName).exists()) return true;
        if (externalPluginsDir != null && new File(externalPluginsDir, fileName).exists()) return true;
        return false;
    }

    public static List<PluginInfo> getLoadedPlugins() {
        return new ArrayList<>(loadedPlugins);
    }

    private static void createDefaultPluginsIfEmpty(File dir) {
        if (dir == null) return;
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
}
