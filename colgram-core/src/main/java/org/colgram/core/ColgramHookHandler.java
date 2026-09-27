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
        if (context == null) return;
        try {
            appContext = context.getApplicationContext();
            ColgramOptimizer.optimizeStartup(appContext);
            ColgramConfig.init(appContext);
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
            // WARP that was left on: re-establish it on every start (consent is already
            // granted system-wide after the first dialog; prepare() returns null then).
            if (ColgramConfig.isWarpEnabled()) {
                new Thread(() -> {
                    try {
                        ColgramWarpTunnel.bringUp(appContext);
                    } catch (Throwable t) {
                        ColgramConfig.setWarpEnabled(false);
                        ColgramProxyManager.notifyProxySettingsChanged();
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
        return ColgramPluginManager.hookOnSendMessage(dialogId, replyToMsgId, text);
    }

    /** Same hook with the replied-to message's author, so reply-targeted commands work. */
    public static boolean hookOnSendMessage(long dialogId, int replyToMsgId, String text, long replySenderId) {
        return ColgramPluginManager.hookOnSendMessage(dialogId, replyToMsgId, text, replySenderId);
    }

    /**
     * Built-in spam verdict for one inbound message. No api_id, no userbot: the score is computed
     * in-process and the caller only has to act on it.
     */
    public static int colgramSpamVerdict(long dialogId, long senderId, String text, boolean isGroup) {
        try {
            boolean isContact = false;
            try {
                Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
                Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, 0);
                java.util.HashMap<?, ?> contacts =
                        (java.util.HashMap<?, ?>) mcClass.getField("contactsDict").get(mc);
                isContact = contacts != null && contacts.containsKey(senderId);
            } catch (Throwable ignored) {
            }
            return ColgramSpamGuard.judge(dialogId, senderId, text, isContact, isGroup);
        } catch (Throwable t) {
            return ColgramSpamGuard.VERDICT_OK;
        }
    }

    /**
     * HOOK: Called from MessagesController when a new message is received or created.
     *
     * Deduplicated on (dialog, message id) because more than one transport can report the same
     * arrival: the bot synchroniser dispatches a batch it injected itself, and Telegram's own
     * update path stores the same dialog through MessagesStorage. Without this a plugin - and an
     * auto-reply is a plugin that answers - would fire twice for one message.
     */
    public static void hookOnMessageReceived(long dialogId, int messageId, String text, boolean isOut) {
        if (!isOut && !colgramRememberDispatch(dialogId, messageId)) return;
        ColgramPluginManager.hookOnMessageReceived(dialogId, messageId, text, isOut);
        if (!isOut) colgramAutoReply(dialogId);
        colgramKeywordAlerts(dialogId, text, isOut);
        colgramMessageLog(dialogId, messageId, text, isOut);
    }

    /** Per-dialog timestamp of the last automatic answer. */
    private static final java.util.HashMap<Long, Long> colgramAutoReplyAt = new java.util.HashMap<>();

    /**
     * Built-in keyword alerts (was plugins/keyword_alerts.py).
     *
     * A keyword configured in settings that shows up in any message raises a heads-up
     * notification-style toast with the dialog it came from. Outgoing messages are skipped:
     * the point is to catch other people talking about the things you watch for.
     */
    private static void colgramKeywordAlerts(long dialogId, String text, boolean isOut) {
        if (isOut || !ColgramConfig.isKeywordAlertsEnabled()) return;
        if (!ColgramConfig.keywordAlertMatches(text)) return;
        final String message = text == null ? "" : text;
        final long dialog = dialogId;
        new Thread(() -> {
            String title = "🔔 Ключевое слово";
            String who;
            try {
                Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
                Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, 0);
                // getUser/getChat are declared with boxed Long parameters, so the lookup must
                // ask for Long.class: getMethod matches declared types exactly.
                if (dialog > 0) {
                    Object user = mcClass.getMethod("getUser", Long.class).invoke(mc, dialog);
                    who = user == null ? Long.toString(dialog) : (String)
                            firstNonNull(user.getClass().getField("first_name").get(user),
                                    user.getClass().getField("username").get(user), "чат " + dialog);
                } else {
                    Object chat = mcClass.getMethod("getChat", Long.class).invoke(mc, -dialog);
                    who = chat == null ? Long.toString(dialog) : String.valueOf(
                            chat.getClass().getField("title").get(chat));
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
        }, "colgram-keyword-alert").start();
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) {
            if (v != null && !String.valueOf(v).isEmpty()) return v;
        }
        return null;
    }

    /** One JSONL writer for the built-in message log; appends are cheap and crash-safe. */
    private static final Object messageLogLock = new Object();

    /**
     * Built-in message log (was plugins/message_logger.py).
     *
     * Every dispatched message lands as one JSON line in files/messages_log.jsonl inside the
     * app sandbox - incoming and outgoing, with the dialog id and a direction flag. The file
     * is append-only JSONL so it stays readable while the app is running and never needs a
     * schema migration.
     */
    private static void colgramMessageLog(long dialogId, int messageId, String text, boolean isOut) {
        if (!ColgramConfig.isMessageLoggerEnabled() || appContext == null) return;
        final long dialog = dialogId;
        final int mid = messageId;
        final String safeText = text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
        final boolean out = isOut;
        new Thread(() -> {
            synchronized (messageLogLock) {
                try {
                    java.io.File dir = new java.io.File(appContext.getFilesDir(), "Colgram");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File file = new java.io.File(dir, "messages_log.jsonl");
                    try (java.io.FileWriter w = new java.io.FileWriter(file, true)) {
                        w.write("{\"ts\":" + System.currentTimeMillis() / 1000L
                                + ",\"dialog\":" + dialog
                                + ",\"id\":" + mid
                                + ",\"out\":" + out
                                + ",\"text\":\"" + safeText + "\"}\n");
                    }
                } catch (Throwable ignored) {}
            }
        }, "colgram-msg-log").start();
    }

    /**
     * Native auto-reply: answer an inbound message with the configured text.
     *
     * It rides the same dispatch that feeds plugins, so it sees exactly the messages a plugin
     * would, and the dedupe above guarantees one answer per arrival. The cooldown is what keeps
     * this from becoming a spam cannon in a busy chat: five minutes per dialog by default.
     */
    private static void colgramAutoReply(long dialogId) {
        if (!ColgramConfig.isAutoReplyEnabled()) return;
        // Positive dialog ids are private chats; groups are negative and opt-in; channels are
        // negative too but cannot be answered unless you post in them, and the send simply fails.
        if (dialogId < 0 && !ColgramConfig.isAutoReplyInGroups()) return;
        long now = System.currentTimeMillis();
        long cooldown = ColgramConfig.getAutoReplyCooldownSec() * 1000L;
        synchronized (colgramAutoReplyAt) {
            Long last = colgramAutoReplyAt.get(dialogId);
            if (last != null && now - last < cooldown) return;
            colgramAutoReplyAt.put(dialogId, now);
            if (colgramAutoReplyAt.size() > 256) colgramAutoReplyAt.clear();
        }
        ColgramPythonEngine.sendMessage(dialogId, ColgramConfig.getAutoReplyText());
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

    private static boolean colgramRememberDispatch(long dialogId, int messageId) {
        String key = dialogId + ":" + messageId;
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
    public static void hookOnMessageEdited(long dialogId, int messageId, String oldText, long editDate) {
        if (!ColgramConfig.isEditHistoryEnabled() || appContext == null) {
            return;
        }
        if (dialogId == 0 || messageId == 0 || oldText == null || oldText.trim().isEmpty()) {
            return;
        }
        ColgramDatabase.getInstance(appContext).saveMessageEdit(dialogId, messageId, oldText, editDate);
    }

    /**
     * HOOK: Called from ChatMessageCell when drawing or binding a message bubble.
     */
    public static boolean isMessageMarkedDeleted(long dialogId, int messageId) {
        if (!ColgramConfig.isAntiDeleteEnabled() || appContext == null) {
            return false;
        }
        return ColgramDatabase.getInstance(appContext).isMessageDeleted(dialogId, messageId);
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

            File dst = new File(dir, System.currentTimeMillis() + "_" + name);
            // Guard against a collision if two edits land in the same millisecond.
            if (dst.exists()) dst = new File(dir, System.currentTimeMillis() + "_" + Math.abs(name.hashCode()) + "_" + name);

            java.io.InputStream in = null;
            java.io.OutputStream out = null;
            try {
                in = new java.io.FileInputStream(src);
                out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignore) {}
                if (out != null) try { out.close(); } catch (Throwable ignore) {}
            }
            return dst.exists() && dst.length() > 0 ? dst.getAbsolutePath() : null;
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
        if (!ColgramConfig.isPreserveMediaEnabled()) {
            return false;
        }
        // If media lock is active, do not allow physical unlinking of cached media
        return file != null && file.exists();
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

    /** HOOK: records that the phone-number privacy request has been accepted. */
    public static void markPhoneNumberHidden(int account) {
        if (appContext == null) return;
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean("phone_hidden_" + account, true).apply();
        } catch (Throwable ignored) {}
    }
}
