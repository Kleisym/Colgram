package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * ColgramHookHandler — Central hook router.
 * All surgical hooks injected into Telegram's original classes call
 * into this static bridge, maintaining separation of concerns and
 * clean compatibility across upstream Telegram updates.
 */
public class ColgramHookHandler {

    private static final String PREFS_NAME = "colgram_prefs";
    private static Context appContext;

    public static void init(Context context) {
        if (context == null || ColgramProcess.isMasqueProcess(context)) return;
        try {
            appContext = context.getApplicationContext();
            ColgramCrashGuard.install(appContext);
            ColgramOptimizer.optimizeStartup(appContext);
            ColgramConfig.init(appContext);
            ColgramShieldConfig.init(appContext);
            hookIoExecutor.execute(() -> {
                try {
                    java.io.File cacheDir = appContext.getCacheDir();
                    if (cacheDir != null) {
                        ColgramNativeDesync.setTempDir(cacheDir.getAbsolutePath());
                    }
                    ColgramNativeDesync.runSelfTest();
                } catch (Throwable ignored) {}
            });
            ColgramUnlimitedMemory.restore(appContext);
            ColgramStorageSandbox.init(appContext);
            ColgramDatabase.getInstance(appContext);
            // Registers the context only. The CPython runtime itself is started
            // lazily by ColgramPythonEngine.ensureInitialized() on first real use:
            // booting an interpreter (with its own libssl/libsqlite3 sockets) inside
            // the application-startup window makes it race Telegram's native MTProto
            // ConnectionSocket over the process descriptor table, which fdsan turns
            // into a SIGABRT in libtmessages.49.so.
            ColgramPythonEngine.init(appContext);
            ColgramPluginManager.init(appContext);

            // Proxy / DPI-bypass work is socket-heavy (local listener + TCP probes
            // + Telegram proxy reconfiguration). ColgramProxyManager defers it
            // internally; starting the bypass engine again here would defeat that,
            // so this method no longer calls ColgramDpiBypass.start() directly.
            ColgramProxyManager.activateBuiltinProxy(appContext);
            ColgramProxyDoctor.init(appContext);
            ColgramSecurityDefender.runStartupAutoHeal(appContext);
            ColgramOptimizer.scheduleAfterStartup(ColgramHookHandler::invokeBridgeStartScheduler);
            // WARP that was left on: re-establish it on every start (consent is already
            // granted system-wide after the first dialog; prepare() returns null then).
            if (ColgramConfig.isWarpEnabled()) {
                final long restoreEpoch = ColgramRouteEpoch.snapshot();
                new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {}
                    if (!ColgramRouteEpoch.isCurrent(restoreEpoch) || !ColgramConfig.isWarpEnabled()) return;
                    try {
                        ColgramWarpTunnel.bringUp(appContext, restoreEpoch);
                    } catch (Throwable t) {
                        ColgramRouteEpoch.runIfCurrent(restoreEpoch, () -> {
                            ColgramConfig.setWarpEnabled(false);
                            ColgramProxyManager.notifyProxySettingsChanged();
                        });
                        android.util.Log.w("ColgramHookHandler", "WARP restore failed: " + t.getMessage());
                    }
                }, "colgram-warp-restore").start();
            }
        } catch (Throwable t) {
            android.util.Log.e("ColgramHookHandler", "Error during Colgram initialization", t);
        }
    }

    /**
     * HOOK: Called from SendMessagesHelper before dispatching an outgoing message.
     * @return true if the message was handled as a plugin command and should NOT be sent.
     */
    public static boolean hookOnSendMessage(long dialogId, int replyToMsgId, String text) {
        return hookOnSendMessage(resolveSelectedAccount(), dialogId, replyToMsgId, text, 0L);
    }

    /** Same hook with the replied-to message's author, so reply-targeted commands work. */
    public static boolean hookOnSendMessage(long dialogId, int replyToMsgId, String text, long replySenderId) {
        return hookOnSendMessage(resolveSelectedAccount(), dialogId, replyToMsgId, text, replySenderId);
    }

    private static char firstNonWhitespaceChar(String text) {
        if (text == null) return '\0';
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c > ' ') {
                return c;
            }
        }
        return '\0';
    }

    /** Zero-allocation fast check whether outgoing text starts with a command prefix ('.' or '/'). */
    public static boolean startsWithCommandPrefix(String text) {
        char first = firstNonWhitespaceChar(text);
        return first == '.' || first == '/';
    }

    /** Zero-allocation fast check whether outgoing text starts with a dot command ('.'). */
    public static boolean startsWithDotCommand(String text) {
        return firstNonWhitespaceChar(text) == '.';
    }

    /** Account-scoped outgoing hook so commands never cross accounts when selectedAccount changes. */
    public static boolean hookOnSendMessage(int account, long dialogId, int replyToMsgId, String text, long replySenderId) {
        char first = firstNonWhitespaceChar(text);
        if (first != '.' && first != '/') {
            return false;
        }
        int resolvedAcc = account >= 0 && account < 5 ? account : resolveSelectedAccount();
        if (invokeBridgeChatCommand(resolvedAcc, dialogId, replyToMsgId, text, replySenderId)) {
            return true;
        }
        if (first != '.') {
            return false;
        }
        return ColgramPluginManager.hookOnSendMessage(resolvedAcc, dialogId, replyToMsgId, text, replySenderId);
    }

    /** Routes fresh incoming MTProto messages into native Colemailer counters, mute rules, leads, and Auto-Sub. */
    public static void notifyColemailerIncoming(int account, Object tlMessage) {
        if (tlMessage == null) return;
        try {
            initBridgeHandles();
            if (cachedBridgeOnIncomingMethod != null) {
                cachedBridgeOnIncomingMethod.invoke(null, account, tlMessage);
            }
        } catch (Throwable ignored) {}
    }

    private static void invokeBridgeStartScheduler() {
        if (!ColgramConfig.isBroadcasterEnabled()) {
            return;
        }
        try {
            initBridgeHandles();
            if (cachedBridgeStartSchedulerMethod != null) {
                cachedBridgeStartSchedulerMethod.invoke(null);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean invokeBridgeChatCommand(int account, long dialogId, int replyToMsgId, String text, long replySenderId) {
        char first = firstNonWhitespaceChar(text);
        if (first != '.' && first != '/') return false;
        String trimmed = text.trim();
        if (first == '/') {
            if ("/defender".equalsIgnoreCase(trimmed) || "/audit".equalsIgnoreCase(trimmed)) {
                dispatchDefenderCommand(trimmed);
                return true;
            }
            return false;
        }
        if (".defender".equalsIgnoreCase(trimmed) || ".защита".equalsIgnoreCase(trimmed)
                || ".audit".equalsIgnoreCase(trimmed) || ".clean".equalsIgnoreCase(trimmed)) {
            dispatchDefenderCommand(trimmed);
            return true;
        }
        try {
            initBridgeHandles();
            if (cachedBridgeHandleCmd5Method != null) {
                Object res = cachedBridgeHandleCmd5Method.invoke(null, account, dialogId, replyToMsgId, text, replySenderId);
                return Boolean.TRUE.equals(res);
            }
            if (cachedBridgeHandleCmd4Method != null) {
                Object res = cachedBridgeHandleCmd4Method.invoke(null, dialogId, replyToMsgId, text, replySenderId);
                return Boolean.TRUE.equals(res);
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void dispatchDefenderCommand(String trimmed) {
        final Context ctx = appContext;
        hookIoExecutor.execute(() -> {
            String report = ColgramSecurityDefender.handleCommand(ctx, trimmed);
            if (ctx != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        android.widget.Toast.makeText(ctx, report, android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {}
                });
            }
        });
    }

    /**
     * Built-in spam verdict for one inbound message. No api_id, no userbot: the score is computed
     * in-process and the caller only has to act on it.
     */
    public static int colgramSpamVerdict(long dialogId, long senderId, String text, boolean isGroup) {
        return colgramSpamVerdict(0, dialogId, senderId, text, isGroup);
    }

    public static int colgramSpamVerdict(int account, long dialogId, long senderId, String text, boolean isGroup) {
        try {
            // Never score 1-on-1 private chats, Saved Messages, self, or system dialogs.
            if (!isGroup || dialogId >= 0L || senderId == 0L) {
                return ColgramSpamGuard.VERDICT_OK;
            }
            long selfId = resolveSelfUserId(account);
            if (selfId > 0L && (dialogId == selfId || senderId == selfId)) {
                return ColgramSpamGuard.VERDICT_OK;
            }
            if (ColgramColemailerEngine.getInstance().isWhitelisted(senderId)) {
                return ColgramSpamGuard.VERDICT_OK;
            }
            boolean isContact = isTelegramContact(account, senderId);
            return ColgramSpamGuard.judge(dialogId, senderId, text, isContact, isGroup);
        } catch (Throwable t) {
            return ColgramSpamGuard.VERDICT_OK;
        }
    }

    private static final Object REFLECTION_LOCK = new Object();
    private static volatile boolean bridgeInitAttempted = false;
    private static volatile java.lang.reflect.Method cachedBridgeOnIncomingMethod;
    private static volatile java.lang.reflect.Method cachedBridgeStartSchedulerMethod;
    private static volatile java.lang.reflect.Method cachedBridgeHandleCmd5Method;
    private static volatile java.lang.reflect.Method cachedBridgeHandleCmd4Method;

    private static volatile boolean userConfigInitAttempted = false;
    private static volatile java.lang.reflect.Field cachedUcSelectedAccountField;
    private static volatile java.lang.reflect.Method cachedUcGetInstanceMethod;
    private static volatile java.lang.reflect.Method cachedUcGetClientUserIdMethod;

    private static volatile boolean contactsInitAttempted = false;
    private static volatile java.lang.reflect.Method cachedCcGetInstanceMethod;
    private static volatile java.lang.reflect.Method cachedCcIsContactMethod;

    private static volatile boolean messagesInitAttempted = false;
    private static volatile java.lang.reflect.Method cachedMcGetInstanceMethod;
    private static volatile java.lang.reflect.Method cachedMcGetUserMethod;
    private static volatile java.lang.reflect.Method cachedMcGetChatMethod;

    private static volatile boolean tlInitAttempted = false;
    private static volatile java.lang.reflect.Field cachedUserFirstNameField;
    private static volatile java.lang.reflect.Field cachedUserLastNameField;
    private static volatile java.lang.reflect.Field cachedUserUsernameField;
    private static volatile java.lang.reflect.Field cachedUserBotField;
    private static volatile java.lang.reflect.Field cachedChatTitleField;
    private static volatile java.lang.reflect.Field cachedChatBroadcastField;
    private static volatile java.lang.reflect.Field cachedChatMegagroupField;

    private static void initBridgeHandles() {
        if (bridgeInitAttempted) return;
        synchronized (REFLECTION_LOCK) {
            if (bridgeInitAttempted) return;
            try {
                Class<?> bridge = Class.forName("org.telegram.messenger.ColgramNativeColemailerBridge");
                try {
                    Class<?> msgCls = Class.forName("org.telegram.tgnet.TLRPC$Message");
                    cachedBridgeOnIncomingMethod = bridge.getMethod("onIncomingMessage", int.class, msgCls);
                } catch (Throwable ignored) {}
                try {
                    cachedBridgeStartSchedulerMethod = bridge.getMethod("startScheduler");
                } catch (Throwable ignored) {}
                try {
                    cachedBridgeHandleCmd5Method = bridge.getMethod("handleChatCommand", int.class, long.class, int.class, String.class, long.class);
                } catch (Throwable ignored) {}
                try {
                    cachedBridgeHandleCmd4Method = bridge.getMethod("handleChatCommand", long.class, int.class, String.class, long.class);
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {
            } finally {
                bridgeInitAttempted = true;
            }
        }
    }

    private static void initUserConfigHandles() {
        if (userConfigInitAttempted) return;
        synchronized (REFLECTION_LOCK) {
            if (userConfigInitAttempted) return;
            try {
                Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
                cachedUcSelectedAccountField = ucClass.getField("selectedAccount");
                cachedUcGetInstanceMethod = ucClass.getMethod("getInstance", int.class);
                cachedUcGetClientUserIdMethod = ucClass.getMethod("getClientUserId");
            } catch (Throwable ignored) {
            } finally {
                userConfigInitAttempted = true;
            }
        }
    }

    private static void initContactsHandles() {
        if (contactsInitAttempted) return;
        synchronized (REFLECTION_LOCK) {
            if (contactsInitAttempted) return;
            try {
                Class<?> ccClass = Class.forName("org.telegram.messenger.ContactsController");
                cachedCcGetInstanceMethod = ccClass.getMethod("getInstance", int.class);
                cachedCcIsContactMethod = ccClass.getMethod("isContact", long.class);
            } catch (Throwable ignored) {
            } finally {
                contactsInitAttempted = true;
            }
        }
    }

    private static void initMessagesHandles() {
        if (messagesInitAttempted) return;
        synchronized (REFLECTION_LOCK) {
            if (messagesInitAttempted) return;
            try {
                Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
                cachedMcGetInstanceMethod = mcClass.getMethod("getInstance", int.class);
                cachedMcGetUserMethod = mcClass.getMethod("getUser", Long.class);
                cachedMcGetChatMethod = mcClass.getMethod("getChat", Long.class);
            } catch (Throwable ignored) {
            } finally {
                messagesInitAttempted = true;
            }
        }
    }

    private static void initTlHandles() {
        if (tlInitAttempted) return;
        synchronized (REFLECTION_LOCK) {
            if (tlInitAttempted) return;
            try {
                Class<?> userClass = Class.forName("org.telegram.tgnet.TLRPC$User");
                cachedUserFirstNameField = userClass.getField("first_name");
                cachedUserLastNameField = userClass.getField("last_name");
                cachedUserUsernameField = userClass.getField("username");
                cachedUserBotField = userClass.getField("bot");
            } catch (Throwable ignored) {}
            try {
                Class<?> chatClass = Class.forName("org.telegram.tgnet.TLRPC$Chat");
                cachedChatTitleField = chatClass.getField("title");
                cachedChatBroadcastField = chatClass.getField("broadcast");
                cachedChatMegagroupField = chatClass.getField("megagroup");
            } catch (Throwable ignored) {}
            tlInitAttempted = true;
        }
    }

    private static boolean isTelegramContact(int account, long userId) {
        try {
            initContactsHandles();
            if (cachedCcGetInstanceMethod != null && cachedCcIsContactMethod != null) {
                Object cc = cachedCcGetInstanceMethod.invoke(null, account);
                if (cc != null) {
                    return Boolean.TRUE.equals(cachedCcIsContactMethod.invoke(cc, userId));
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static int resolveSelectedAccount() {
        try {
            initUserConfigHandles();
            if (cachedUcSelectedAccountField != null) {
                int acc = cachedUcSelectedAccountField.getInt(null);
                return acc >= 0 && acc < 5 ? acc : 0;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static long resolveSelfUserId(int account) {
        if (account < 0 || account >= 5) return 0L;
        try {
            initUserConfigHandles();
            if (cachedUcGetInstanceMethod != null && cachedUcGetClientUserIdMethod != null) {
                Object uc = cachedUcGetInstanceMethod.invoke(null, account);
                if (uc != null) {
                    Object id = cachedUcGetClientUserIdMethod.invoke(uc);
                    if (id instanceof Long) return ((Long) id).longValue();
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    /**
     * HOOK: Called from MessagesController / MessagesStorage when a new message is received or created.
     *
     * Deduplicated on (account, dialog, message id) because more than one transport can report the same
     * arrival: the bot synchroniser dispatches a batch it injected itself, and Telegram's own
     * update path stores the same dialog through MessagesStorage. Without this a plugin - and an
     * auto-reply is a plugin that answers - would fire twice for one message.
     */
    public static void hookOnMessageReceived(long dialogId, int messageId, String text, boolean isOut) {
        hookOnMessageReceived(resolveSelectedAccount(), dialogId, messageId, text, 0L, isOut);
    }

    public static void hookOnMessageReceived(int account, long dialogId, int messageId, String text, boolean isOut) {
        hookOnMessageReceived(account, dialogId, messageId, text, 0L, isOut);
    }

    public static void hookOnMessageReceived(int account, long dialogId, int messageId, String text, long senderId, boolean isOut) {
        if (dialogId == 0L || ColgramAutoReplyPolicy.isEncryptedOrFolderDialog(dialogId)) return;
        int resolvedAcc = account >= 0 && account < 5 ? account : resolveSelectedAccount();
        colgramMessageLog(resolvedAcc, dialogId, messageId, text, isOut);
        if (isOut) return;
        if (!colgramRememberDispatch(resolvedAcc, dialogId, messageId)) return;
        // Strict Saved Messages / self-sender / system dialog isolation: never dispatch inbound
        // plugins, auto-reply, or keyword alerts on Saved Messages, own messages, or Telegram system chats.
        long selfUserId = resolveSelfUserId(resolvedAcc);
        if (selfUserId <= 0L || dialogId == selfUserId
                || (senderId != 0L && senderId == selfUserId)
                || ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(dialogId)) {
            return;
        }
        ColgramPluginManager.hookOnMessageReceived(resolvedAcc, dialogId, messageId, text, false);
        colgramAutoReply(resolvedAcc, dialogId, senderId);
        colgramKeywordAlerts(resolvedAcc, dialogId, text, false);
    }

    /** Per-(account, dialog) timestamp of the last automatic answer. */
    private static final java.util.HashMap<String, Long> colgramAutoReplyAt = new java.util.HashMap<>();
    private static final java.util.concurrent.ScheduledExecutorService hookIoExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {}
                    r.run();
                }, "colgram-hook-io");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.concurrent.ExecutorService editHistoryExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {}
                    r.run();
                }, "colgram-edit-history");
                t.setDaemon(true);
                return t;
            });

    /**
     * Built-in keyword alerts (was plugins/keyword_alerts.py).
     *
     * A keyword configured in settings that shows up in any message raises a heads-up
     * notification-style toast with the dialog it came from. Outgoing messages are skipped:
     * the point is to catch other people talking about the things you watch for.
     */
    private static void colgramKeywordAlerts(int account, long dialogId, String text, boolean isOut) {
        if (isOut || !ColgramConfig.isKeywordAlertsEnabled()) return;
        if (!ColgramConfig.keywordAlertMatches(text)) return;
        final String message = text == null ? "" : text;
        final long dialog = dialogId;
        final int acct = account >= 0 ? account : resolveSelectedAccount();
        hookIoExecutor.execute(() -> {
            String title = "🔔 Ключевое слово";
            String who = Long.toString(dialog);
            try {
                initMessagesHandles();
                initTlHandles();
                if (cachedMcGetInstanceMethod != null) {
                    Object mc = cachedMcGetInstanceMethod.invoke(null, acct);
                    if (mc != null) {
                        if (dialog > 0 && cachedMcGetUserMethod != null) {
                            Object user = cachedMcGetUserMethod.invoke(mc, dialog);
                            if (user != null && cachedUserFirstNameField != null && cachedUserUsernameField != null) {
                                who = (String) firstNonNull(
                                        cachedUserFirstNameField.get(user),
                                        cachedUserUsernameField.get(user),
                                        "чат " + dialog);
                            }
                        } else if (dialog <= 0 && cachedMcGetChatMethod != null) {
                            Object chat = cachedMcGetChatMethod.invoke(mc, -dialog);
                            if (chat != null && cachedChatTitleField != null) {
                                who = String.valueOf(cachedChatTitleField.get(chat));
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                who = Long.toString(dialog);
            }
            String body = who + ": " + (message.length() > 120 ? message.substring(0, 120) + "…" : message);
            final String line = title + "\n" + body;
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> {
                try {
                    android.content.Context ctx = appContext;
                    if (ctx == null) return;
                    android.widget.Toast.makeText(ctx, line, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            });
        });
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) {
            if (v != null && !String.valueOf(v).isEmpty()) return v;
        }
        return null;
    }

    /** One buffered JSONL writer for the built-in message log with periodic flush. */
    private static final Object messageLogLock = new Object();
    private static java.io.BufferedWriter messageLogWriter = null;
    private static File messageLogFile = null;
    private static int messageLogPendingWrites = 0;
    private static int messageLogTotalWrites = 0;
    private static long messageLogLastFlushMs = 0L;
    private static java.util.concurrent.ScheduledFuture<?> messageLogFlushTask = null;

    private static void closeMessageLogWriterLocked() {
        if (messageLogWriter != null) {
            try { messageLogWriter.close(); } catch (Throwable ignored) {}
            messageLogWriter = null;
        }
        messageLogPendingWrites = 0;
    }

    private static void flushMessageLogSafe() {
        synchronized (messageLogLock) {
            messageLogFlushTask = null;
            if (messageLogWriter != null && messageLogPendingWrites > 0) {
                try {
                    messageLogWriter.flush();
                    messageLogPendingWrites = 0;
                    messageLogLastFlushMs = System.currentTimeMillis();
                } catch (Throwable ignored) {
                    closeMessageLogWriterLocked();
                }
            }
        }
    }

    private static String jsonEscapeLog(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '"') sb.append("\\\"");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Built-in message log (was plugins/message_logger.py).
     *
     * Every dispatched message lands as one JSON line in files/messages_log.jsonl inside the
     * app sandbox - incoming and outgoing, with the dialog id and a direction flag. The file
     * is append-only JSONL so it stays readable while the app is running and never needs a
     * schema migration.
     */
    private static void colgramMessageLog(int account, long dialogId, int messageId, String text, boolean isOut) {
        if (!ColgramConfig.isMessageLoggerEnabled() || appContext == null) return;
        final int acct = account;
        final long dialog = dialogId;
        final int mid = messageId;
        final String safeText = jsonEscapeLog(text);
        final boolean out = isOut;
        final long tsSec = System.currentTimeMillis() / 1000L;
        hookIoExecutor.execute(() -> {
            synchronized (messageLogLock) {
                try {
                    if (messageLogFile == null) {
                        java.io.File dir = new java.io.File(appContext.getFilesDir(), "Colgram");
                        if (!dir.exists()) dir.mkdirs();
                        messageLogFile = new java.io.File(dir, "messages_log.jsonl");
                    } else if (messageLogWriter != null && (messageLogTotalWrites++ & 63) == 0 && !messageLogFile.exists()) {
                        closeMessageLogWriterLocked();
                    }
                    if (messageLogFile.isFile()
                            && messageLogFile.length() >= ColgramStorageCapEnforcer.MESSAGES_LOG_ROTATE_BYTES) {
                        closeMessageLogWriterLocked();
                        ColgramStorageCapEnforcer.rotateMessagesLogIfNeeded(messageLogFile.getParentFile());
                    }
                    if (messageLogWriter == null) {
                        java.io.File parent = messageLogFile.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        messageLogWriter = new java.io.BufferedWriter(
                                new java.io.OutputStreamWriter(
                                        new java.io.FileOutputStream(messageLogFile, true),
                                        java.nio.charset.StandardCharsets.UTF_8),
                                8192);
                    }
                    messageLogWriter.write("{\"ts\":");
                    messageLogWriter.write(Long.toString(tsSec));
                    messageLogWriter.write(",\"account\":");
                    messageLogWriter.write(Integer.toString(acct));
                    messageLogWriter.write(",\"dialog\":");
                    messageLogWriter.write(Long.toString(dialog));
                    messageLogWriter.write(",\"id\":");
                    messageLogWriter.write(Integer.toString(mid));
                    messageLogWriter.write(",\"out\":");
                    messageLogWriter.write(Boolean.toString(out));
                    messageLogWriter.write(",\"text\":\"");
                    messageLogWriter.write(safeText);
                    messageLogWriter.write("\"}\n");
                    messageLogPendingWrites++;
                    long now = System.currentTimeMillis();
                    if (messageLogPendingWrites >= 16 || (now - messageLogLastFlushMs) >= 2000L) {
                        messageLogWriter.flush();
                        messageLogPendingWrites = 0;
                        messageLogLastFlushMs = now;
                        if (messageLogFlushTask != null) {
                            messageLogFlushTask.cancel(false);
                            messageLogFlushTask = null;
                        }
                    } else if (messageLogFlushTask == null || messageLogFlushTask.isDone()) {
                        messageLogFlushTask = hookIoExecutor.schedule(
                                ColgramHookHandler::flushMessageLogSafe,
                                2000L,
                                java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                } catch (Throwable ignored) {
                    closeMessageLogWriterLocked();
                }
            }
        });
    }

    /**
     * Native auto-reply: answer an inbound message with the configured text.
     *
     * It rides the same dispatch that feeds plugins, so it sees exactly the messages a plugin
     * would, and the dedupe above guarantees one answer per arrival. The cooldown is what keeps
     * this from becoming a spam cannon in a busy chat: five minutes per dialog by default.
     */
    private static void colgramAutoReply(int accountArg, long dialogId, long senderId) {
        if (!ColgramConfig.isAutoReplyEnabled()) return;
        long now = System.currentTimeMillis();
        long selfUserId = 0L;
        boolean isBotPeer = false;
        boolean isChannel = false;
        boolean isContact = false;
        String peerName = "";
        String peerUsername = "";
        int account = accountArg >= 0 && accountArg < 5 ? accountArg : resolveSelectedAccount();

        try {
            selfUserId = resolveSelfUserId(account);
            if (selfUserId <= 0L || dialogId == selfUserId || (senderId != 0L && senderId == selfUserId)) {
                return;
            }
            initMessagesHandles();
            initTlHandles();
            Object mc = cachedMcGetInstanceMethod != null ? cachedMcGetInstanceMethod.invoke(null, account) : null;
            if (mc != null) {
                if (dialogId > 0 && cachedMcGetUserMethod != null) {
                    Object user = cachedMcGetUserMethod.invoke(mc, dialogId);
                    if (user != null) {
                        if (cachedUserBotField != null) {
                            isBotPeer = cachedUserBotField.getBoolean(user);
                        }
                        Object fn = cachedUserFirstNameField != null ? cachedUserFirstNameField.get(user) : null;
                        Object ln = cachedUserLastNameField != null ? cachedUserLastNameField.get(user) : null;
                        Object un = cachedUserUsernameField != null ? cachedUserUsernameField.get(user) : null;
                        StringBuilder sb = new StringBuilder();
                        if (fn != null) sb.append(fn);
                        if (ln != null && !String.valueOf(ln).isEmpty()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(ln);
                        }
                        peerName = sb.toString();
                        peerUsername = un != null ? String.valueOf(un) : "";
                    }
                    isContact = isTelegramContact(account, dialogId);
                } else if (dialogId <= 0 && cachedMcGetChatMethod != null) {
                    Object chat = cachedMcGetChatMethod.invoke(mc, -dialogId);
                    if (chat != null) {
                        Object title = cachedChatTitleField != null ? cachedChatTitleField.get(chat) : null;
                        peerName = title != null ? String.valueOf(title) : "";
                        try {
                            if (cachedChatBroadcastField != null && cachedChatMegagroupField != null) {
                                boolean broadcast = cachedChatBroadcastField.getBoolean(chat);
                                boolean megagroup = cachedChatMegagroupField.getBoolean(chat);
                                isChannel = broadcast && !megagroup;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        final String arKey = account + ":" + dialogId;
        long last = 0L;
        synchronized (colgramAutoReplyAt) {
            Long prev = colgramAutoReplyAt.get(arKey);
            if (prev != null) last = prev;
        }

        if (!ColgramAutoReplyPolicy.shouldReply(
                ColgramConfig.isAutoReplyEnabled(),
                dialogId,
                selfUserId,
                false,
                isBotPeer,
                ColgramConfig.isAutoReplyToBots(),
                isChannel,
                ColgramConfig.isAutoReplyInGroups(),
                isContact,
                ColgramConfig.getAutoReplyAudience(),
                ColgramConfig.getAutoReplyBlacklist(),
                now,
                last,
                ColgramConfig.getAutoReplyCooldownSec())) {
            return;
        }

        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = cal.get(java.util.Calendar.MINUTE);
        int nowMin = hour * 60 + minute;

        String rawTemplate = ColgramAutoReplyPolicy.selectReplyText(
                ColgramConfig.isAutoReplyScheduleEnabled(),
                ColgramConfig.isAutoReplyOnlyWhenAway(),
                ColgramConfig.getAutoReplyStartMin(),
                ColgramConfig.getAutoReplyEndMin(),
                nowMin,
                ColgramConfig.getAutoReplyText(),
                ColgramConfig.getAutoReplyAwayText());
        if (rawTemplate == null || rawTemplate.trim().isEmpty()) {
            return;
        }

        synchronized (colgramAutoReplyAt) {
            colgramAutoReplyAt.put(arKey, now);
            if (colgramAutoReplyAt.size() > 256) colgramAutoReplyAt.clear();
        }
        final int targetAccount = account;
        final String finalText = ColgramAutoReplyPolicy.renderTemplate(rawTemplate, peerName, peerUsername, hour, minute);
        hookIoExecutor.execute(() -> ColgramPythonEngine.sendMessage(targetAccount, dialogId, finalText));
    }

    /** Bounded LRU of recently dispatched inbound messages; false means "already handled". */
    private static final java.util.LinkedHashMap<String, Long> colgramDispatchedInbound =
            new java.util.LinkedHashMap<String, Long>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                    return size() > 512;
                }
            };

    private static final long COLGRAM_DISPATCH_DEDUPE_MS = 5 * 60 * 1000L;

    private static boolean colgramRememberDispatch(int account, long dialogId, int messageId) {
        String key = account + ":" + dialogId + ":" + messageId;
        long now = System.currentTimeMillis();
        synchronized (colgramDispatchedInbound) {
            Long seen = colgramDispatchedInbound.get(key);
            if (seen != null && now - seen < COLGRAM_DISPATCH_DEDUPE_MS) return false;
            colgramDispatchedInbound.put(key, now);
        }
        return true;
    }

    /**
     * HOOK: Called from ConnectionsManager before native initConnection.
     * Spoofs hardware, OS, and client parameters.
     */
    public static Map<String, String> hookInitConnection(
            String model,
            String sysVersion,
            String appVersion,
            String lang) {
        return ColgramCloak.getCloakedConnectionParams(model, sysVersion, appVersion, lang);
    }

    /**
     * HOOK: Called from MessagesController when UpdateDeleteMessages or
     * UpdateDeleteChannelMessages arrives.
     *
     * @return true if deletion should be BLOCKED (message preserved locally).
     */
    public static boolean hookShouldPreventDelete(long dialogId, int messageId) {
        if (dialogId == 0L || messageId <= 0) {
            return false;
        }
        if (isForcePurged(dialogId, messageId)) {
            unmarkMessageDeleted(dialogId, messageId);
            return false;
        }
        if (!ColgramConfig.isAntiDeleteEnabled()) {
            return false;
        }

        if (appContext != null) {
            ColgramDatabase.getInstance(appContext).markMessageDeleted(dialogId, messageId);
        }

        // Return true to tell Telegram's storage layer NOT to delete the row
        return true;
    }

    /**
     * Batched anti-delete check + tombstone write so bulk deletes/clears run in ONE SQLite transaction.
     *
     * @return set of messageIds whose deletion MUST be prevented.
     */
    public static java.util.Set<Integer> hookShouldPreventDeleteBatch(
            long dialogId, java.util.Collection<Integer> messageIds) {
        if (dialogId == 0L || messageIds == null || messageIds.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        if (!ColgramConfig.isAntiDeleteEnabled()) {
            return java.util.Collections.emptySet();
        }
        java.util.ArrayList<Integer> toPreserve = null;
        java.util.HashSet<Integer> prevented = null;
        for (Integer midObj : messageIds) {
            if (midObj == null) continue;
            int mid = midObj.intValue();
            if (mid <= 0) continue;
            if (isForcePurged(dialogId, mid)) {
                unmarkMessageDeleted(dialogId, mid);
                continue;
            }
            if (toPreserve == null) {
                toPreserve = new java.util.ArrayList<>(messageIds.size());
                prevented = new java.util.HashSet<>(messageIds.size());
            }
            if (prevented.add(mid)) {
                toPreserve.add(mid);
            }
        }
        if (toPreserve != null && !toPreserve.isEmpty() && appContext != null) {
            ColgramDatabase.getInstance(appContext).markMessagesDeleted(dialogId, toPreserve);
        }
        return prevented != null ? prevented : java.util.Collections.emptySet();
    }

    /**
     * HOOK: Called when a chat/dialog clear or remote delete arrives (or when
     * preserving an entire chat history locally).
     *
     * @return true if local dialog history should be preserved and badged.
     */
    public static boolean hookShouldPreventDialogDelete(long dialogId, String title) {
        if (!ColgramConfig.isAntiDeleteEnabled() || dialogId == 0) {
            return false;
        }
        if (appContext != null) {
            ColgramDatabase.getInstance(appContext).markDialogDeleted(dialogId, title);
        }
        return true;
    }

    /** Removes the local deleted-message tombstone (for local-only deletion). */
    public static void unmarkMessageDeleted(long dialogId, int messageId) {
        if (appContext != null) {
            ColgramDatabase.getInstance(appContext).removeMessageDeleted(dialogId, messageId);
        }
    }

    /** Removes the local deleted-dialog marker when the user dismisses it manually. */
    public static void unmarkDialogDeleted(long dialogId) {
        if (appContext != null) {
            ColgramDatabase.getInstance(appContext).clearDialogDeleted(dialogId);
        }
    }

    /**
     * HOOK: Called from MessagesController / DialogCell when rendering a dialog.
     */
    public static boolean isDialogMarkedDeleted(long dialogId) {
        if (!ColgramConfig.isAntiDeleteEnabled() || appContext == null || dialogId == 0) {
            return false;
        }
        return ColgramDatabase.getInstance(appContext).isDialogDeleted(dialogId);
    }

    /**
     * HOOK: Called from MessagesController when UpdateEditMessage arrives,
     * BEFORE the new revision overwrites the stored message.
     *
     * The incoming update carries only the NEW text. The caller is expected to
     * have read the PREVIOUS revision out of local storage and pass it here as
     * oldText. A null or blank oldText means "nothing recoverable" and is skipped
     * so we never pollute the history with empty or duplicate rows.
     *
     * @param oldText previous revision text, or null if unavailable
     * @param editDate timestamp of the edit
     */
    private static final int LIVE_SNAPSHOT_CAP = 2000;
    private static final java.util.Map<String, String> LIVE_TEXT_SNAPSHOTS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > LIVE_SNAPSHOT_CAP;
                }
            });

    private static final class LiveMediaSnapshot {
        final int mediaType;
        final String localPath;
        final String fileName;
        final long fileSize;
        final String mimeType;
        final long remoteId;
        LiveMediaSnapshot(int mediaType, String localPath, String fileName, long fileSize, String mimeType, long remoteId) {
            this.mediaType = mediaType;
            this.localPath = localPath;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.mimeType = mimeType;
            this.remoteId = remoteId;
        }
    }

    private static final java.util.Map<String, LiveMediaSnapshot> LIVE_MEDIA_SNAPSHOTS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, LiveMediaSnapshot>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, LiveMediaSnapshot> eldest) {
                    return size() > LIVE_SNAPSHOT_CAP;
                }
            });

    private static final java.util.Map<String, String> LIVE_ENTITIES_SNAPSHOTS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > LIVE_SNAPSHOT_CAP;
                }
            });

    public static void recordLiveMessageSnapshot(long dialogId, int messageId, String text) {
        recordLiveMessageSnapshot(dialogId, messageId, text, null);
    }

    public static void recordLiveMessageSnapshot(long dialogId, int messageId, String text, String entitiesJson) {
        if (dialogId == 0 || messageId == 0 || text == null) return;
        String key = dialogId + ":" + messageId;
        LIVE_TEXT_SNAPSHOTS.put(key, text);
        if (entitiesJson != null && !entitiesJson.isEmpty()) {
            LIVE_ENTITIES_SNAPSHOTS.put(key, entitiesJson);
        } else {
            LIVE_ENTITIES_SNAPSHOTS.remove(key);
        }
    }

    public static boolean hasLiveMessageSnapshot(long dialogId, int messageId) {
        if (dialogId == 0 || messageId == 0) return false;
        return LIVE_TEXT_SNAPSHOTS.containsKey(dialogId + ":" + messageId);
    }

    public static void recordLiveMediaSnapshot(long dialogId, int messageId, int mediaType,
                                               String localPath, String fileName, long fileSize,
                                               String mimeType, long remoteId) {
        if (dialogId == 0 || messageId == 0 || remoteId == 0) return;
        LIVE_MEDIA_SNAPSHOTS.put(dialogId + ":" + messageId,
                new LiveMediaSnapshot(mediaType, localPath, fileName, fileSize, mimeType, remoteId));
    }

    public static boolean hasLiveMediaSnapshot(long dialogId, int messageId) {
        if (dialogId == 0 || messageId == 0) return false;
        return LIVE_MEDIA_SNAPSHOTS.containsKey(dialogId + ":" + messageId);
    }

    public static String getLiveMediaPath(long dialogId, int messageId) {
        if (dialogId == 0 || messageId == 0) return null;
        LiveMediaSnapshot snap = LIVE_MEDIA_SNAPSHOTS.get(dialogId + ":" + messageId);
        return snap != null ? snap.localPath : null;
    }

    /**
     * Captures rapid (<1s) sequential edits accurately by reading the live in-memory previous
     * revision BEFORE falling back to SQLite (which lags behind async storageQueue writes).
     */
    public static void hookOnMessageEditedWithNew(long dialogId, int messageId,
                                                  String fallbackStoredText, String newText,
                                                  long editTimestampMs) {
        hookOnMessageEditedWithNew(dialogId, messageId, fallbackStoredText, null, newText, null, editTimestampMs);
    }

    public static void hookOnMessageEditedWithNew(long dialogId, int messageId,
                                                  String fallbackStoredText, String fallbackEntitiesJson,
                                                  String newText, String newEntitiesJson,
                                                  long editTimestampMs) {
        processMessageEditWithNew(
                dialogId, messageId, fallbackStoredText, fallbackEntitiesJson,
                newText, newEntitiesJson, editTimestampMs, false);
    }

    /**
     * Captures live text snapshots synchronously in FIFO order while offloading SQLite
     * deduplication/insert onto the background edit-history executor.
     */
    public static void hookOnMessageEditedWithNewAsync(long dialogId, int messageId,
                                                       String fallbackStoredText, String fallbackEntitiesJson,
                                                       String newText, String newEntitiesJson,
                                                       long editTimestampMs) {
        processMessageEditWithNew(
                dialogId, messageId, fallbackStoredText, fallbackEntitiesJson,
                newText, newEntitiesJson, editTimestampMs, true);
    }

    private static void processMessageEditWithNew(long dialogId, int messageId,
                                                  String fallbackStoredText, String fallbackEntitiesJson,
                                                  String newText, String newEntitiesJson,
                                                  long editTimestampMs, boolean asyncPersist) {
        if (!ColgramConfig.isEditHistoryEnabled() || appContext == null) {
            return;
        }
        if (dialogId == 0 || messageId == 0) {
            return;
        }
        String key = dialogId + ":" + messageId;
        String livePrev = LIVE_TEXT_SNAPSHOTS.get(key);
        String livePrevEnt = LIVE_ENTITIES_SNAPSHOTS.get(key);
        String prevText = (livePrev != null && !livePrev.isEmpty()) ? livePrev : fallbackStoredText;
        String prevEnt = (livePrev != null && !livePrev.isEmpty()) ? livePrevEnt : fallbackEntitiesJson;
        String safeNew = newText == null ? "" : newText;
        String safeNewEnt = newEntitiesJson == null ? "" : newEntitiesJson;
        String safePrevEnt = prevEnt == null ? "" : prevEnt;
        boolean textChanged = prevText != null && !prevText.equals(safeNew);
        boolean entChanged = prevText != null && !safePrevEnt.equals(safeNewEnt);
        if (prevText != null && !prevText.trim().isEmpty() && (textChanged || entChanged)) {
            final long ts = editTimestampMs > 0 ? editTimestampMs : System.currentTimeMillis();
            final Context ctx = appContext;
            if (asyncPersist) {
                editHistoryExecutor.execute(() ->
                        ColgramDatabase.getInstance(ctx).saveMessageEdit(dialogId, messageId, prevText, prevEnt, ts));
            } else {
                ColgramDatabase.getInstance(ctx).saveMessageEdit(dialogId, messageId, prevText, prevEnt, ts);
            }
        }
        if (!safeNew.isEmpty()) {
            LIVE_TEXT_SNAPSHOTS.put(key, safeNew);
            if (!safeNewEnt.isEmpty()) {
                LIVE_ENTITIES_SNAPSHOTS.put(key, safeNewEnt);
            } else {
                LIVE_ENTITIES_SNAPSHOTS.remove(key);
            }
        } else {
            LIVE_TEXT_SNAPSHOTS.remove(key);
            LIVE_ENTITIES_SNAPSHOTS.remove(key);
        }
    }

    public static void hookOnMediaChangedWithNew(long dialogId, int messageId,
                                                 String oldMediaKind, String oldMediaId, String oldLocalPath,
                                                 String newMediaKind, String newMediaId, String newLocalPath,
                                                 long editTimestampMs) {
        int oldType = mediaTypeFromKind(oldMediaKind);
        int newType = mediaTypeFromKind(newMediaKind);
        long oldRem = oldMediaId != null ? (Math.abs((long) oldMediaId.hashCode()) + 1L) : 1L;
        long newRem = newMediaId != null ? (Math.abs((long) newMediaId.hashCode()) + 1L) : (oldRem + 1L);
        if (oldLocalPath != null && !oldLocalPath.isEmpty()) {
            File fOld = new File(oldLocalPath);
            recordLiveMediaSnapshot(dialogId, messageId, oldType, oldLocalPath, fOld.getName(),
                    fOld.isFile() ? fOld.length() : 0L, "", oldRem);
        }
        File fNew = newLocalPath != null ? new File(newLocalPath) : null;
        hookOnMediaChangedWithNew(dialogId, messageId, newType, newLocalPath,
                fNew != null ? fNew.getName() : newMediaKind,
                (fNew != null && fNew.isFile()) ? fNew.length() : 0L, "", newRem, editTimestampMs);
    }

    private static int mediaTypeFromKind(String kind) {
        if (kind == null) return 0;
        String k = kind.toLowerCase(java.util.Locale.US);
        if (k.contains("photo") || k.contains("image")) return 1;
        if (k.contains("video")) return 2;
        if (k.contains("anim") || k.contains("gif")) return 3;
        if (k.contains("voice") || k.contains("audio")) return 4;
        return 5;
    }

    public static void hookOnMediaChangedWithNew(long dialogId, int messageId,
                                                 int newMediaType, String newLocalPath, String newFileName,
                                                 long newFileSize, String newMimeType, long newRemoteId,
                                                 long editTimestampMs) {
        if (!ColgramConfig.isEditHistoryEnabled() || appContext == null) {
            return;
        }
        if (dialogId == 0 || messageId == 0) return;
        String key = dialogId + ":" + messageId;
        LiveMediaSnapshot prev = LIVE_MEDIA_SNAPSHOTS.get(key);
        if (prev != null && prev.remoteId != 0 && prev.remoteId != newRemoteId) {
            String sandboxCopy = prev.localPath != null
                    ? colgramCopyRevisionFile(prev.localPath, prev.fileName)
                    : null;
            if (sandboxCopy == null) sandboxCopy = prev.localPath;
            long ts = editTimestampMs > 0 ? editTimestampMs : System.currentTimeMillis();
            ColgramDatabase.getInstance(appContext).saveMediaRevision(
                    dialogId, messageId, prev.mediaType, sandboxCopy, prev.fileName,
                    prev.fileSize, prev.mimeType, prev.remoteId, ts);
        }
        if (newRemoteId != 0) {
            LIVE_MEDIA_SNAPSHOTS.put(key, new LiveMediaSnapshot(
                    newMediaType, newLocalPath, newFileName, newFileSize, newMimeType, newRemoteId));
        }
    }

    public static void hookOnMessageEdited(long dialogId, int messageId, String oldText, long editDate) {
        if (!ColgramConfig.isEditHistoryEnabled() || appContext == null) {
            return;
        }
        if (dialogId == 0 || messageId == 0 || oldText == null || oldText.trim().isEmpty()) {
            return;
        }
        ColgramDatabase.getInstance(appContext).saveMessageEdit(dialogId, messageId, oldText, editDate);
    }

    private static final long FORCE_PURGE_TTL_MS = 30_000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> FORCE_PURGED_KEYS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void markForcePurged(long dialogId, int messageId) {
        if (dialogId == 0 || messageId == 0) return;
        long now = System.currentTimeMillis();
        FORCE_PURGED_KEYS.put(dialogId + ":" + messageId, now);
        if (dialogId > 0) {
            FORCE_PURGED_KEYS.put("0:" + messageId, now);
        }
        if (FORCE_PURGED_KEYS.size() > 512) {
            FORCE_PURGED_KEYS.entrySet().removeIf(e -> now - e.getValue() > FORCE_PURGE_TTL_MS);
        }
    }

    /** Completely removes deleted tombstones and saved edit/media revisions for a message batch. */
    public static void purgeMessagesCompletely(long dialogId, java.util.Collection<Integer> messageIds) {
        if (dialogId == 0 || messageIds == null || messageIds.isEmpty()) return;
        java.util.ArrayList<Integer> positiveIds = null;
        for (Integer id : messageIds) {
            if (id != null && id != 0) {
                markForcePurged(dialogId, id);
                if (id > 0) {
                    if (positiveIds == null) {
                        positiveIds = new java.util.ArrayList<>(messageIds.size());
                    }
                    positiveIds.add(id);
                }
            }
        }
        if (positiveIds != null && appContext != null) {
            final ColgramDatabase db = ColgramDatabase.getInstance(appContext);
            final java.util.ArrayList<Integer> toPurge = positiveIds;
            db.purgeMessagesMemoryOnly(dialogId, toPurge);
            hookIoExecutor.execute(() -> db.purgeMessagesFromDiskOnly(dialogId, toPurge));
        }
    }

    public static boolean isForcePurged(long dialogId, int messageId) {
        if (messageId == 0) return false;
        long now = System.currentTimeMillis();
        Long ts = FORCE_PURGED_KEYS.get(dialogId + ":" + messageId);
        if (ts != null && now - ts <= FORCE_PURGE_TTL_MS) return true;
        if (dialogId >= 0) {
            Long ts0 = FORCE_PURGED_KEYS.get("0:" + messageId);
            if (ts0 != null && now - ts0 <= FORCE_PURGE_TTL_MS) return true;
        }
        return false;
    }

    /**
     * TTL-backed check so local deletion + asynchronous server TL_updateDeleteMessages echo both
     * recognize the intentional double-delete purge without premature single-use consumption!
     */
    public static boolean consumeForcePurged(long dialogId, int messageId) {
        return isForcePurged(dialogId, messageId);
    }

    /**
     * HOOK: Called from ChatMessageCell when drawing or binding a message bubble.
     */
    public static boolean isMessageMarkedDeleted(long dialogId, int messageId) {
        if (!ColgramConfig.isAntiDeleteEnabled() || appContext == null || dialogId == 0L || messageId <= 0) {
            return false;
        }
        return ColgramDatabase.getInstance(appContext).isMessageDeleted(dialogId, messageId);
    }

    /**
     * Returns all locally preserved deleted message IDs for a dialog so storage cleanup/sync
     * never wipes anti-deleted messages or their attached media rows from SQLite.
     */
    public static java.util.List<Integer> getPreservedDeletedMessageIds(long dialogId) {
        if (!ColgramConfig.isAntiDeleteEnabled() || appContext == null || dialogId == 0) {
            return new java.util.ArrayList<>();
        }
        return ColgramDatabase.getInstance(appContext).getDeletedMessageIdsForDialog(dialogId);
    }

    /**
     * HOOK: Called when user clicks "История правок" in context menu.
     *
     * NOTE ON MODULE BOUNDARIES: colgram-core compiles BEFORE TMessagesProj, so it
     * cannot reference org.telegram.ui.* classes. The Telegram-native BottomSheet
     * (ColgramEditHistorySheet) is therefore invoked directly by the patched
     * ChatActivity, which lives inside TMessagesProj. This method exists only as a
     * context/data accessor and a fallback dialog host.
     */
    public static void showEditHistory(Context context, long dialogId, int messageId) {
        // Fallback path only — the primary UI is the Telegram-native sheet invoked
        // from the patched ChatActivity.
        ColgramEditHistoryDialog.show(context, dialogId, messageId);
    }

    /**
     * Returns the edit history entries for a message, for use by the Telegram-native
     * history sheet that lives inside TMessagesProj.
     */
    public static java.util.List<ColgramDatabase.MessageEditEntry> getEditHistory(long dialogId, int messageId) {
        if (appContext == null) return new java.util.ArrayList<>();
        return ColgramDatabase.getInstance(appContext).getEditHistory(dialogId, messageId);
    }

    // --- Media revision history -------------------------------------------------
    //
    // Telegram does not version attachments. When a message's photo/video/document is
    // replaced by an edit, the old file reference is destroyed and only the caption
    // change would be recorded by hookOnMessageEdited. These three methods let the
    // patched MessagesController capture the outgoing attachment before it is lost,
    // and let the UI list / open the old revision afterwards.
    //
    // Module-boundary note: TMessagesProj resolves the actual local path (it owns
    // FileLoader / MessageObject) and passes it in as a plain String. colgram-core
    // never touches TLRPC or FileLoader.

    /**
     * Records an attachment that is about to be replaced by an edit.
     *
     * @param localPath path to a SANDBOX COPY of the old file, or null if the caller
     *                  could not produce one. A null path still records the revision
     *                  (name/size/type) so the history shows what used to be there;
     *                  {@link ColgramDatabase.MediaRevision#isRetrievable()} then
     *                  reports false and the UI offers no "open" action.
     */
    public static void hookOnMediaReplaced(long dialogId, int messageId, int mediaType,
                                           String localPath, String fileName, long fileSize,
                                           String mimeType, long remoteId, long editDate) {
        if (!ColgramConfig.isEditHistoryEnabled() || appContext == null) return;
        if (dialogId == 0 || messageId == 0) return;
        ColgramDatabase.getInstance(appContext).saveMediaRevision(
                dialogId, messageId, mediaType, localPath, fileName, fileSize,
                mimeType, remoteId, editDate);
    }

    /**
     * Offloads sandbox file copy (SHA-256 + GZIP index flush) and SQLite media revision
     * insert onto the serialized background edit-history executor so MTProto stageQueue stays unblocked.
     */
    public static void hookOnMediaReplacedAsync(long dialogId, int messageId, int mediaType,
                                                String sourcePath, String fileName, long fileSize,
                                                String mimeType, long remoteId, long editDate) {
        if (!ColgramConfig.isEditHistoryEnabled() || appContext == null) return;
        if (dialogId == 0 || messageId == 0) return;
        final Context ctx = appContext;
        editHistoryExecutor.execute(() -> {
            String sandboxPath = null;
            if (sourcePath != null) {
                sandboxPath = colgramCopyRevisionFile(sourcePath, fileName);
                if (sandboxPath == null) {
                    sandboxPath = sourcePath;
                }
            }
            ColgramDatabase.getInstance(ctx).saveMediaRevision(
                    dialogId, messageId, mediaType, sandboxPath, fileName, fileSize,
                    mimeType, remoteId, editDate);
        });
    }

    /** All stored media revisions of a message, oldest first. */
    public static java.util.List<ColgramDatabase.MediaRevision> getMediaRevisions(long dialogId, int messageId) {
        if (appContext == null) return new java.util.ArrayList<>();
        return ColgramDatabase.getInstance(appContext).getMediaRevisions(dialogId, messageId);
    }

    /**
     * Copies a file into the Colgram sandbox so a revision survives Telegram's own
     * cache eviction. Returns the destination path, or null on any failure.
     *
     * Deliberately tolerant: a revision whose copy failed is still worth recording,
     * so the caller must handle null rather than treat it as an error.
     */
    public static String colgramCopyRevisionFile(String sourcePath, String suggestedName) {
        if (appContext == null || sourcePath == null || sourcePath.isEmpty()) return null;
        try {
            File src = new File(sourcePath);
            if (!src.exists() || !src.isFile() || src.length() == 0) return null;

            File dir = new File(appContext.getFilesDir(), "media_history");
            if (!dir.exists() && !dir.mkdirs()) return null;
            ColgramStorageSandbox.ensureDirWithNoMedia(dir);

            String name = (suggestedName == null || suggestedName.trim().isEmpty())
                    ? src.getName() : suggestedName.trim();
            // Strip anything path-like so a crafted name cannot escape the sandbox.
            name = name.replace('/', '_').replace('\\', '_').replace("..", "_");
            if (name.length() > 120) {
                String ext = "";
                int dot = name.lastIndexOf('.');
                if (dot > 0) ext = name.substring(dot);
                name = name.substring(0, Math.max(1, 120 - ext.length())) + ext;
            }

            File dst = ColgramStorageOptimizer.copyOrDeduplicateToDir(
                    src, dir, System.currentTimeMillis() + "_" + name);
            return dst != null && dst.exists() && dst.length() > 0 ? dst.getAbsolutePath() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Existence probe used to gate the "Media History" menu entry. */
    public static boolean hasMediaRevisions(long dialogId, int messageId) {
        if (appContext == null) return false;
        return ColgramDatabase.getInstance(appContext).hasMediaRevisions(dialogId, messageId);
    }

    /**
     * HOOK: Called from FileLoader and AndroidUtilities when resolving storage paths.
     */
    public static File hookGetDirectory(int type) {
        if (!ColgramConfig.isSandboxStorageEnabled() || appContext == null) {
            return null; // Let Telegram handle default path
        }
        return ColgramStorageSandbox.getSandboxedDirectory(appContext, type);
    }

    /**
     * HOOK: Called from FileLoader when deleting media files.
     * Prevents unlinking media for preserved messages.
     */
    public static boolean shouldPreventMediaDeletion(File file) {
        if (!ColgramConfig.isPreserveMediaEnabled() || file == null || !file.exists()) {
            return false;
        }
        // Only protect preserved revision archives, wallpapers, and explicitly vaulted media —
        // never block Telegram's Clear Cache / LRU eviction on ordinary temporary cache files.
        String p = file.getAbsolutePath().replace('\\', '/').toLowerCase(java.util.Locale.US);
        return p.contains("/media_history/")
                || p.contains("/colgram_wallpapers/")
                || p.contains("/colgram_vault/")
                || p.contains("/colgram_preserved/")
                || p.contains("/.colgram_cas/");
    }

    /**
     * HOOK: Called from MessagesController before sending messages.readHistory / channels.readHistory.
     */
    public static boolean shouldPreventReadReceipt(long dialogId) {
        return ColgramGhostMode.shouldPreventReadReceipt(dialogId);
    }

    /**
     * HOOK: Called from MessagesController before sending messages.setTyping.
     */
    public static boolean shouldPreventTypingStatus(long dialogId) {
        return ColgramGhostMode.shouldPreventTypingStatus(dialogId);
    }

    /**
     * HOOK: Called from ConnectionsManager / AccountInstance before sending online status ping.
     */
    public static boolean shouldStayOffline() {
        return ColgramGhostMode.shouldStayOffline();
    }

    /**
     * HOOK: Called from BaseFragment / ChatActivity window setup.
     */
    public static boolean shouldBypassFlagSecure() {
        return ColgramGhostMode.shouldBypassFlagSecure();
    }

    /** Suppresses outgoing screenshot notification packets in private & secret chats. */
    public static boolean shouldHideScreenshotNotify() {
        return ColgramConfig.isHideScreenshotNotifyEnabled();
    }

    /** Allows viewing, re-opening, saving, and forwarding view-once / TTL media without expiry. */
    public static boolean shouldUnlockOneTimeMedia() {
        return ColgramConfig.isSaveOneTimeMediaEnabled();
    }

    /** Views stories anonymously without reporting stories.readStories to Telegram servers. */
    public static boolean shouldUseStealthStories() {
        return ColgramConfig.isStealthStoriesEnabled();
    }

    /** Strips original sender attribution ("Forwarded from") by default when forwarding. */
    public static boolean shouldForwardNoQuote() {
        return ColgramConfig.isForwardNoQuoteEnabled();
    }

    /** Masks the user's own phone number inside local UI surfaces (drawer/settings/profile). */
    public static boolean shouldHidePhoneLocally() {
        return ColgramConfig.isHidePhoneLocalEnabled();
    }

    /** Formats a phone string safely when local phone hiding is enabled. */
    public static String maskLocalPhone(String phone) {
        if (!shouldHidePhoneLocally() || phone == null || phone.isEmpty()) {
            return phone;
        }
        return "••••••••";
    }

    /** Requires confirmation before starting an outgoing voice/video call. */
    public static boolean shouldConfirmCalls() {
        return ColgramConfig.isConfirmCallsEnabled();
    }

    /** Requires confirmation before sending a sticker or GIF tap. */
    public static boolean shouldConfirmStickers() {
        return ColgramConfig.isConfirmStickersEnabled();
    }

    /** Prompts before opening an external hyperlink from chat or profile. */
    public static boolean shouldConfirmLinks() {
        return ColgramConfig.isConfirmLinksEnabled();
    }

    // --- Per-user notification blocking -----------------------------------------

    /**
     * HOOK: Called when a message arrives, to decide whether this sender's message should
     * be kept silent.
     *
     * Scope, deliberately: this suppresses NOTIFICATIONS only. The message is still stored,
     * still appears in the chat, and I can still reply normally. The point is that a
     * specific user pinging me inside a group does not produce a buzz, a badge or a
     * notification row.
     *
     * @param senderUserId the author of the incoming message, 0 when unknown
     */
    public static boolean shouldSilenceNotificationsFrom(long senderUserId) {
        if (senderUserId == 0 || appContext == null) return false;
        // MessageObject.getFromChatId() resolves from_id, which for a channel post is the
        // CHANNEL id, not a user. Restrict this to the user id space so a muted user can
        // never accidentally collide with a channel.
        if (senderUserId < 0) return false;
        return ColgramDatabase.getInstance(appContext).isNotificationsBlockedFrom(senderUserId);
    }

    public static void setNotificationsBlocked(long userId, boolean blocked) {
        if (userId == 0 || appContext == null) return;
        if (blocked) {
            ColgramDatabase.getInstance(appContext).blockNotificationsFrom(userId);
        } else {
            ColgramDatabase.getInstance(appContext).unblockNotificationsFrom(userId);
        }
    }

    public static boolean isNotificationsBlocked(long userId) {
        if (userId == 0 || appContext == null) return false;
        return ColgramDatabase.getInstance(appContext).isNotificationsBlockedFrom(userId);
    }

    // NOTE: the settings / plugins screens are the Telegram-native BaseFragment
    // implementation deployed from scripts/templates/ into org.telegram.ui. The old
    // Activity-based colgram-core versions were deleted because they hardcoded a dark
    // palette (white-on-dark text that was unreadable in the light theme) and had
    // drifted out of sync with the themed screens. Entry points live in the patcher,
    // which calls presentFragment(new ColgramSettingsActivity()) etc.

    // --- Phone number auto-hide -------------------------------------------------

    /**
     * HOOK: called from MessagesController after privacy rules are first synced.
     *
     * Returns true exactly once per account, so the caller issues a single
     * account.setPrivacy(phone -> nobody) request instead of repeating it on every
     * rules update. Once marked, this returns false forever for that account.
     */
    public static boolean shouldAutoHidePhoneNumber(int account) {
        if (appContext == null) return false;
        // Respect the user-level toggle: if the feature is off, never fire.
        if (!ColgramConfig.isAutoHidePhoneEnabled()) return false;
        try {
            SharedPreferences p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return !p.getBoolean("phone_hidden_" + account, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static long normalizeEpochMillis(long ts) {
        if (ts <= 0L) return System.currentTimeMillis();
        return ts < 100_000_000_000L ? ts * 1000L : ts;
    }

    /** HOOK: records that the phone-number privacy request has been accepted. */
    public static void markPhoneNumberHidden(int account) {
        if (appContext == null) return;
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean("phone_hidden_" + account, true).apply();
        } catch (Throwable ignored) {}
    }

    // --- Built-in Defender Bot hooks ("Бот-защитник Colgram") -----------------

    /** True when WebRTC direct P2P / STUN IP negotiation must be blocked to prevent IP leaks in calls. */
    public static boolean shouldBlockVoipP2p() {
        return ColgramConfig.isDefenderBlockVoipP2pEnabled()
                || ColgramConfig.isMaxAnonymityEnabled()
                || ColgramConfig.isStrictProxySecurityEnabled();
    }

    /** True when Mini App / WebView geolocation access must be denied unconditionally. */
    public static boolean shouldBlockBotLocation() {
        return ColgramConfig.isDefenderBlockBotLocationEnabled() || ColgramConfig.isMaxAnonymityEnabled();
    }

    /** True when WebView file access, mixed HTTP content, and 3rd-party cookies must be blocked. */
    public static boolean shouldHardenWebView() {
        return ColgramConfig.isDefenderWebViewHardeningEnabled()
                || ColgramConfig.isMaxAnonymityEnabled()
                || ColgramConfig.isStrictProxySecurityEnabled();
    }

    /** Blocks foreign upstream Telegram APK popups signed by DrKLO/Telegram's incompatible keystore. */
    public static boolean shouldBlockForeignAppUpdate() {
        return true;
    }

    /** Strips UTM/fbclid/yclid/ref tracking parameters from external links when enabled. */
    public static String sanitizeOutgoingOrOpenedUrl(String rawUrl) {
        if (rawUrl == null || !ColgramConfig.isDefenderStripTrackersEnabled()) {
            return rawUrl;
        }
        ColgramSecurityPolicy.SanitizedUrlResult result = ColgramSecurityPolicy.sanitizeTrackingUrl(rawUrl);
        if (result.strippedCount > 0) {
            ColgramConfig.addDefenderTrackersStripped(result.strippedCount);
        }
        return result.url;
    }

    /** Replaces real Build.MODEL / manufacturer inside WebView and Mini App User-Agents with the spoof profile. */
    public static String cloakWebViewUserAgent(String originalUa, boolean isBot, String appVersionName, String perfClass) {
        boolean cloak = ColgramConfig.isCloakEnabled()
                || ColgramConfig.isDefenderWebViewHardeningEnabled()
                || ColgramConfig.isMaxAnonymityEnabled();
        return ColgramSecurityPolicy.buildCloakedWebViewUserAgent(
                originalUa,
                ColgramCloak.getEffectiveDeviceModel(),
                ColgramCloak.getEffectiveSystemVersion(),
                cloak,
                isBot,
                appVersionName,
                perfClass);
    }

    /** Returns JS shim spoofing Client Hints / navigator / WebGL renderer for WebViews when cloaking is active. */
    public static String buildWebViewSpoofJs() {
        boolean cloak = ColgramConfig.isCloakEnabled()
                || ColgramConfig.isDefenderWebViewHardeningEnabled()
                || ColgramConfig.isMaxAnonymityEnabled();
        return ColgramSecurityPolicy.buildWebViewSpoofJs(
                ColgramCloak.getEffectiveDeviceModel(),
                ColgramCloak.getEffectiveSystemVersion(),
                cloak);
    }

    // --- Anti-Ping Collapsed Stubs, Interaction Cache, Shadow Vault & Hard Cap hooks ---

    private static final java.util.Set<String> expandedSpamSessionKeys =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static void recordCollapsedSpam(long dialogId, int messageId, long senderId, String reason) {
        if (appContext == null || dialogId == 0L || messageId == 0) return;
        ColgramDatabase.getInstance(appContext).recordCollapsedSpam(dialogId, messageId, senderId, reason);
    }

    public static boolean isMessageCollapsedSpam(long dialogId, int messageId) {
        if (appContext == null || dialogId == 0L || messageId == 0) return false;
        if (!ColgramShieldConfig.isCollapseSpamPingsEnabled()) return false;
        if (expandedSpamSessionKeys.contains(dialogId + ":" + messageId)) return false;
        return ColgramDatabase.getInstance(appContext).isCollapsedSpam(dialogId, messageId);
    }

    public static String getCollapsedSpamReason(long dialogId, int messageId) {
        if (appContext == null || dialogId == 0L || messageId == 0) return null;
        return ColgramDatabase.getInstance(appContext).getCollapsedSpamReason(dialogId, messageId);
    }

    public static void toggleExpandCollapsedSpam(long dialogId, int messageId) {
        if (dialogId == 0L || messageId == 0) return;
        String key = dialogId + ":" + messageId;
        if (!expandedSpamSessionKeys.add(key)) {
            expandedSpamSessionKeys.remove(key);
        }
    }

    public static void recordOutgoingUserInteraction(long userId) {
        ColgramSpamGuard.recordOutgoingInteraction(userId);
    }

    /** Captures zlib-compressed metadata into Lazy Shadow Vault without network traffic. */
    public static void captureShadowMessageMetadata(long dialogId, int messageId, long senderId,
                                                  int dateSec, int mediaType, long mediaSize,
                                                  String metaJson, String previewPath) {
        if (appContext == null || dialogId == 0L || messageId <= 0) return;
        ColgramShieldConfig.init(appContext);
        if (!ColgramShieldConfig.isLazyShadowVaultEnabled()) return;
        final long did = dialogId;
        final int mid = messageId;
        final long sid = senderId;
        final int dt = dateSec;
        final int mt = mediaType;
        final long ms = mediaSize;
        final String mj = metaJson;
        final String pp = previewPath;
        hookIoExecutor.execute(() -> {
            try {
                ColgramDatabase.getInstance(appContext).upsertShadowMessage(
                        did, mid, sid, dt, mt, ms, mj, pp, false);
            } catch (Throwable ignored) {}
        });
    }

    /** Creates an offline <=720px WebP micro-preview ONLY from an already-loaded local image file. */
    public static void captureLazyShadowPhotoPreviewFromLoadedFile(long dialogId, int messageId,
                                                                   long senderId, int dateSec,
                                                                   long origSize, String metaJson,
                                                                   File alreadyLoadedImage) {
        if (appContext == null || dialogId == 0L || messageId <= 0) return;
        if (alreadyLoadedImage == null || !alreadyLoadedImage.isFile() || alreadyLoadedImage.length() <= 0L) return;
        ColgramShieldConfig.init(appContext);
        if (!ColgramShieldConfig.isLazyShadowVaultEnabled()) return;
        final long did = dialogId;
        final int mid = messageId;
        final long sid = senderId;
        final int dt = dateSec;
        final long os = origSize > 0L ? origSize : alreadyLoadedImage.length();
        final String mj = metaJson;
        final File src = alreadyLoadedImage;
        hookIoExecutor.execute(() -> {
            try {
                File dir = ColgramStorageSandbox.getShadowPreviewsDir(appContext);
                if (dir == null) return;
                File dest = new File(dir, did + "_" + mid + ".webp");
                if (!dest.isFile() || dest.length() <= 512L) {
                    File tmp = new File(dir, did + "_" + mid + ".webp.cgtmp");
                    try {
                        ColgramStorageOptimizer.ImageTranscoder transcoder =
                                ColgramUnlimitedMemory.createWebpTranscoder(72, 720);
                        boolean ok = transcoder.transcode(src, tmp, ColgramStorageOptimizer.PRESET_BALANCED);
                        if (ok && tmp.isFile() && tmp.length() > 256L) {
                            ColgramStorageOptimizer.atomicReplaceFile(tmp, dest);
                        }
                    } finally {
                        if (tmp.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            tmp.delete();
                        }
                    }
                }
                String path = dest.isFile() ? dest.getAbsolutePath() : "";
                ColgramDatabase.getInstance(appContext).upsertShadowMessage(
                        did, mid, sid, dt, 1, os, mj, path, false);
            } catch (Throwable ignored) {}
        });
    }

    public static String getShadowPreviewPath(long dialogId, int messageId) {
        if (appContext == null || dialogId == 0L || messageId <= 0) return null;
        String p = ColgramDatabase.getInstance(appContext).getShadowPreviewPath(dialogId, messageId);
        if (p != null && !p.isEmpty() && new File(p).isFile()) {
            return p;
        }
        return null;
    }

    public static void notifyMediaFileLoaded(File file) {
        if (appContext == null || file == null) return;
        ColgramStorageCapEnforcer.onFileWritten(appContext, file, file.isFile() ? file.length() : 0L);
    }

    public static void notifyMediaFileDeleted(File file, long sizeBytes) {
        ColgramStorageCapEnforcer.onFileDeleted(file, sizeBytes);
    }
}
