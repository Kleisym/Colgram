package org.colgram.core;

import android.content.Context;
import android.os.Environment;

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
 * ColgramPluginManager — Discovers, loads, and manages Python & modular plugins for Colgram.
 * 
 * Compatible with exteraGram / AyuGram plugin concepts:
 * - Scans `/Documents/Colgram/Plugins/` for `.py` and `.json` plugins.
 * - Dispatches message events, outgoing command interception, and UI extensions.
 */
public class ColgramPluginManager {

    public static class PluginInfo {
        public final String name;
        public final String fileName;
        public final String description;
        public final String author;
        public final String version;
        public boolean isEnabled = true;

        public PluginInfo(String name, String fileName, String description, String author, String version) {
            this.name = name;
            this.fileName = fileName;
            this.description = description;
            this.author = author;
            this.version = version;
        }
    }

    private static final List<PluginInfo> loadedPlugins = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, String> pluginCommands = Collections.synchronizedMap(new HashMap<>());
    private static Context appContext = null;
    private static File pluginsDir = null;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        try {
            File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            pluginsDir = new File(docsDir, "Colgram/Plugins");
            if (!pluginsDir.exists()) {
                pluginsDir.mkdirs();
            }

            // Create default starter plugins if empty
            createDefaultPlugins(pluginsDir);

            // Scan and load plugins
            reloadPlugins();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Scans the plugins directory and registers active plugins.
     */
    public static synchronized void reloadPlugins() {
        loadedPlugins.clear();
        pluginCommands.clear();

        if (pluginsDir == null || !pluginsDir.exists()) return;

        // Register plugins directory in Python sys.path
        ColgramPythonEngine.addPythonPath(pluginsDir.getAbsolutePath());

        File[] files = pluginsDir.listFiles((dir, name) -> name.endsWith(".py") || name.endsWith(".json"));
        if (files == null) return;

        for (File f : files) {
            try {
                String name = f.getName().replace(".py", "").replace(".json", "");
                String description = "Colgram Mobile Plugin";
                String author = "Community";
                String version = "1.0.0";

                // Parse plugin header comments: # name: ..., # author: ..., # command: ...
                if (f.getName().endsWith(".py")) {
                    BufferedReader r = new BufferedReader(new FileReader(f));
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("# name:")) description = line.substring(7).trim();
                        if (line.startsWith("# author:")) author = line.substring(9).trim();
                        if (line.startsWith("# version:")) version = line.substring(10).trim();
                        if (line.startsWith("# command:")) {
                            String cmd = line.substring(10).trim().toLowerCase();
                            pluginCommands.put(cmd, name);
                        }
                    }
                    r.close();
                }

                loadedPlugins.add(new PluginInfo(name, f.getName(), description, author, version));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Intercepts outgoing message text before sending.
     * Returns true if message was consumed/handled as a command (preventing original message send).
     */
    public static boolean hookOnSendMessage(long dialogId, int replyToMsgId, String text) {
        if (text == null) return false;

        // Check for Python / plugin prefix commands (e.g. .ping, .eval, .help)
        if (text.startsWith(".")) {
            return ColgramPythonEngine.handleCommand(dialogId, replyToMsgId, text);
        }

        return false;
    }

    /**
     * Dispatches command to registered plugin.
     */
    public static boolean dispatchCommand(long dialogId, String command, String args) {
        if (pluginCommands.containsKey(command)) {
            String pluginName = pluginCommands.get(command);
            ColgramPythonEngine.sendMessage(dialogId, "⚡ [Plugin: " + pluginName + "] Executing `." + command + " " + args + "`");
            return true;
        }
        return false;
    }

    /**
     * Dispatches incoming message event to active plugins.
     */
    public static void hookOnMessageReceived(long dialogId, int messageId, String text, boolean isOut) {
        // Plugin event hook for message inspection / auto-reaction / translation
    }

    /**
     * Returns a formatted summary of loaded plugins.
     */
    public static String getLoadedPluginsSummary() {
        if (loadedPlugins.isEmpty()) {
            return "📦 **Installed Plugins:** None.\nPlace `.py` plugins in `/Documents/Colgram/Plugins/`";
        }

        StringBuilder sb = new StringBuilder("📦 **Installed Plugins (" + loadedPlugins.size() + "):**\n\n");
        for (PluginInfo p : loadedPlugins) {
            sb.append("• **").append(p.name).append("** (v").append(p.version).append(")\n");
            sb.append("  ").append(p.description).append(" by ").append(p.author).append("\n");
        }
        sb.append("\n📁 _Location: /Documents/Colgram/Plugins/_");
        return sb.toString();
    }

    private static void createDefaultPlugins(File dir) {
        File starter = new File(dir, "echo_tool.py");
        if (!starter.exists()) {
            try (FileWriter w = new FileWriter(starter)) {
                w.write("# name: Echo & Text Transformer Tool\n");
                w.write("# author: Colgram\n");
                w.write("# version: 1.0\n");
                w.write("# command: echo\n\n");
                w.write("# Example Colgram / exteraGram compatible plugin\n");
                w.write("def on_command(cmd, args):\n");
                w.write("    return 'Echo: ' + args\n");
            } catch (Exception ignored) {}
        }
    }

    public static List<PluginInfo> getLoadedPlugins() {
        return new ArrayList<>(loadedPlugins);
    }
}
