package org.colgram.core;

import android.content.Context;
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

    private static Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        ColgramOptimizer.optimizeStartup(appContext);
        ColgramConfig.init(appContext);
        ColgramStorageSandbox.init(appContext);
        ColgramDatabase.getInstance(appContext);
        ColgramPythonEngine.init(appContext);
        ColgramPluginManager.init(appContext);

        // Start embedded DPI bypass engine and background connection doctor immediately
        ColgramProxyManager.activateBuiltinProxy(appContext);
        ColgramProxyDoctor.init(appContext);
    }

    /**
     * HOOK: Called from SendMessagesHelper before dispatching an outgoing message.
     * @return true if the message was handled as a plugin command and should NOT be sent.
     */
    public static boolean hookOnSendMessage(long dialogId, int replyToMsgId, String text) {
        return ColgramPluginManager.hookOnSendMessage(dialogId, replyToMsgId, text);
    }

    /**
     * HOOK: Called from MessagesController when a new message is received or created.
     */
    public static void hookOnMessageReceived(long dialogId, int messageId, String text, boolean isOut) {
        ColgramPluginManager.hookOnMessageReceived(dialogId, messageId, text, isOut);
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
     * before replacing the existing message in storage.
     */
    public static void hookOnMessageEdited(long dialogId, int messageId, String oldText, long editDate) {
        if (!ColgramConfig.isEditHistoryEnabled() || appContext == null) {
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
     */
    public static void showEditHistory(Context context, long dialogId, int messageId) {
        ColgramEditHistoryDialog.show(context, dialogId, messageId);
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

    /**
     * Opens the Colgram settings activity.
     */
    public static void openSettings(Context context) {
        ColgramSettingsActivity.start(context);
    }
}
