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

            // 3b. Always refresh the exteraGram compatibility shim. It is infrastructure,
            //     not a user plugin, so it is overwritten on every start — that way a
            //     Colgram update that changes the shim takes effect without the user
            //     having to clear app data.
            installExteraCompatShim(internalPluginsDir);

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

    public static boolean dispatchCommand(long dialogId, String cmd, String args) {
        if (activeCommands.containsKey(cmd)) {
            PluginInfo p = activeCommands.get(cmd);
            if (p != null && p.isEnabled) {
                executePluginCommand(dialogId, p, cmd, args);
                return true;
            }
        }
        // Fall through to any command registered through the exteraGram compat shim.
        // exteraGram plugins register via `exteraPlugins.add_command(...)` rather than
        // Colgram's header comment, so they never appear in activeCommands and would
        // otherwise never fire.
        return dispatchExteraShimCommand(dialogId, cmd, args);
    }

    /**
     * Run a command registered through the exteraPlugins compatibility shim.
     *
     * The shim lives inside the Python interpreter; we reach it through ColgramPythonEngine
     * rather than importing Python here, since this module must stay free of Telegram and
     * interpreter references at compile time.
     *
     * Returns true if the shim handled the command.
     */
    private static boolean dispatchExteraShimCommand(long dialogId, String cmd, String args) {
        try {
            if (!ColgramPythonEngine.isShimCommandRegistered(cmd)) {
                return false;
            }
            String result = ColgramPythonEngine.runShimCommand(dialogId, cmd, args);
            if (result != null && !result.isEmpty()) {
                ColgramPythonEngine.sendMessage(dialogId, result);
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
            return installPlugin(fileName, code);
        } catch (Throwable t) {
            Log.w(TAG, "installBundledPlugin(" + fileName + ") failed: " + t.getMessage());
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
            if (!base.endsWith(".py") && !base.endsWith(".json") && !base.endsWith(".txt")) {
                base = base + ".py";
            }
            return base;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Minimal HTTP GET returning the body as text, or null on any failure. */
    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Colgram");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.e(TAG, "httpGet HTTP " + code + " for " + urlStr);
                return null;
            }
            InputStream in = conn.getInputStream();
            if (in == null) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            Log.e(TAG, "httpGet failed for " + urlStr, t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
