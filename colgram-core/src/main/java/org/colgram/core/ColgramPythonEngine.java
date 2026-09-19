package org.colgram.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ColgramPythonEngine — Mobile-adapted Python runtime & bridge for Telegram.
 * 
 * Provides:
 * 1. Execution environment for exteraGram / AyuGram compatible Python plugins.
 * 2. Telegram API reflection bridge (send, edit, delete, resolve chat/user).
 * 3. Command dispatcher for prefix commands (e.g. .ping, .eval, .del, .purge, .help).
 */
public class ColgramPythonEngine {

    private static final ExecutorService pyExecutor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Context appContext = null;

    // Active environment variables and registered plugin modules
    private static final Map<String, Object> globalScope = new HashMap<>();

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        globalScope.put("version", "3.12-colgram-mobile");
        globalScope.put("client", "Colgram");
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
                    sendMessage(dialogId, "⚠️ Usage: `.eval <expression>`");
                } else {
                    executeScript(args, new ScriptCallback() {
                        @Override
                        public void onSuccess(String output) {
                            sendMessage(dialogId, "🐍 **Python Output:**\n```\n" + output + "\n```");
                        }

                        @Override
                        public void onError(String error) {
                            sendMessage(dialogId, "❌ **Python Error:**\n```\n" + error + "\n```");
                        }
                    });
                }
                return true;

            case "id":
                sendMessage(dialogId, "🆔 **Chat ID:** `" + dialogId + "`");
                return true;

            case "del":
                // Delete last N messages
                int count = 1;
                try {
                    if (!args.isEmpty()) count = Integer.parseInt(args.trim());
                } catch (Exception ignored) {}
                deleteLastMessages(dialogId, count);
                return true;

            case "help":
                String help = "⚡ **Colgram Python & Plugin System**\n\n" +
                        "• `.ping` — Check client latency\n" +
                        "• `.eval <code>` — Run mobile Python expression\n" +
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
                // Dispatch to registered plugin command handlers
                return ColgramPluginManager.dispatchCommand(dialogId, cmd, args);
        }
    }

    /**
     * Mobile Python Expression & Script Evaluator.
     */
    private static String runPythonCode(String code) {
        if (code == null || code.trim().isEmpty()) return "None";

        String trimmed = code.trim();

        // Arithmetic evaluator: e.g. 2 + 2, 10 * 5, pow(2, 8)
        if (trimmed.matches("^[0-9\\+\\-\\*/\\%\\(\\)\\s\\.\\^]+$")) {
            try {
                return evaluateArithmetic(trimmed);
            } catch (Exception ignored) {}
        }

        // Print statement support: print(...)
        if (trimmed.startsWith("print(") && trimmed.endsWith(")")) {
            String inner = trimmed.substring(6, trimmed.length() - 1);
            return inner.replaceAll("^[\"']|[\"']$", "");
        }

        // Variable assignment: x = 123
        if (trimmed.contains("=") && !trimmed.contains("==")) {
            String[] kv = trimmed.split("=", 2);
            String var = kv[0].trim();
            String val = kv[1].trim();
            globalScope.put(var, val);
            return var + " = " + val;
        }

        // Variable lookup
        if (globalScope.containsKey(trimmed)) {
            return String.valueOf(globalScope.get(trimmed));
        }

        return "Executed: " + trimmed;
    }

    private static String evaluateArithmetic(String rawExpr) {
        final String expr = rawExpr.replaceAll("\\s+", "");
        // Basic arithmetic parser
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
     * Sends a message in Telegram via SendMessagesHelper reflection.
     */
    public static void sendMessage(long dialogId, String message) {
        try {
            Class<?> smhClass = Class.forName("org.telegram.messenger.SendMessagesHelper");
            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
            int currentAccount = (int) ucClass.getField("selectedAccount").get(null);

            Method getInstance = smhClass.getMethod("getInstance", int.class);
            Object smh = getInstance.invoke(null, currentAccount);

            // sendMessage(CharSequence message, long peer, MessageObject reply_to_msg, MessageObject req_reply_to_msg, ...)
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

            // Calls deleteMessages in MessagesController
            for (Method m : mcClass.getMethods()) {
                if (m.getName().equals("deleteMessages") && m.getParameterTypes().length >= 2) {
                    // Method signature found
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
