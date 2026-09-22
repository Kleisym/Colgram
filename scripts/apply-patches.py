#!/usr/bin/env python3
"""
Colgram Python Injection Engine
Replaces brittle git apply with robust semantic code injection.
Directly hooks Telegram source without line number dependencies.
"""

import os
import re
import sys
import shutil
import zipfile
import urllib.request
import subprocess

# Every patch that failed to apply during this run, in order.
#
# Why this exists: patch_file() returns False on an unmatched anchor, but 110 of its 114
# call sites invoke it as a bare statement and throw the result away, and main() always
# exited 0. A Telegram release that moves one anchor therefore deletes a feature and CI
# still builds and publishes an APK that looks fine. That is how "one build for every
# Telegram version" silently stopped being true, and it is why several fixes reported today
# as working had never actually reached a build.
PATCH_MISSES = []

# Misses that are known-benign, with the reason. Anything NOT listed here fails the run.
# Keeping this explicit is the point: a new miss cannot be waved through by accident, and a
# stale entry becomes visible the moment it stops firing.
ALLOWED_MISSES = {
    # The injection is a callable that returns the file unchanged when Colgram init is
    # already present, so patch_file reports "not matched" on every converged run.
    "ApplicationLoader.onCreate Colgram initialization (after native load)",
}


def patch_file(filepath, search_pattern, replacement, description):
    if not os.path.exists(filepath):
        print(f" [!] File not found: {filepath}")
        PATCH_MISSES.append(f"MISSING FILE: {description}")
        return False

    with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()

    if replacement and replacement.strip() in content:
        print(f" [=] Already patched: {description}")
        return True

    if callable(search_pattern):
        new_content = search_pattern(content)
        if new_content == content:
            print(f" [!] Pattern not matched for: {description}")
            PATCH_MISSES.append(description)
            return False
    elif isinstance(search_pattern, str):
        if search_pattern not in content:
            print(f" [!] Anchor string not found for: {description}")
            PATCH_MISSES.append(description)
            return False
        new_content = content.replace(search_pattern, replacement, 1)
    else:
        # Regex
        new_content, count = search_pattern.subn(replacement, content, count=1)
        if count == 0:
            print(f" [!] Regex not matched for: {description}")
            PATCH_MISSES.append(description)
            return False

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)

    print(f" [+] Successfully patched: {description}")
    return True

def inject_hooks(repo_path):
    print("[*] Performing semantic code injection into Telegram source...")

    # 1. ApplicationLoader.java -> Initialize Colgram core, LAST.
    #
    # ⚠️ ORDERING IS LOAD-BEARING. This used to be injected right after
    # `applicationContext = getApplicationContext();`, which sits BEFORE all of:
    #
    #   * super.onCreate()                      - Application base class init
    #   * AndroidUtilities.getHelloWorld()      - class-loads AndroidUtilities, whose
    #                                             static block reads
    #                                             ApplicationLoader.applicationContext
    #                                             and calls checkDisplaySize()
    #   * NativeLoader.initNativeLibs()         - this is what actually loads
    #                                             libtmessages.49.so
    #   * ConnectionsManager.native_setJava()   - installs the Java<->native bridge
    #
    # So Colgram was doing storage/database/plugin setup (it writes files and spawns
    # threads) in a window where the native library did not exist yet and the
    # framework had not finished bootstrapping. Upstream's own comment marks
    # AndroidUtilities as must-be-initialized-first for exactly this reason.
    #
    # ColgramHookHandler.init() only records state and hands off to subsystems that
    # already defer their own socket/disk work by 5-10s, so nothing needs it early.
    # Running it at the very end of onCreate is both correct and safe.
    #
    # ColgramUiBridge.install(this) rides along here: it registers an
    # Application.ActivityLifecycleCallbacks, and it needs `this` to be a fully
    # constructed Application. Several plugin-facing Telegram APIs (notably
    # SendMessagesHelper.editMessage) require a live BaseFragment and fail silently
    # without one; colgram-core cannot reference Activity/BaseFragment at compile
    # time, so it tracks the top activity through these callbacks instead.
    app_loader = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "ApplicationLoader.java")

    def app_loader_replacer(content):
        # A callable, not a plain anchor string, because this patch has to be able to land
        # SEVERAL times: `patch_file` short-circuits with "[=] Already patched" whenever the
        # replacement text is already present, so an anchor-string form could add the Colgram
        # init block once and then never append anything to it again.
        #
        # 🔴 Every piece below carries its OWN marker and is inserted INDEPENDENTLY.
        #
        # The previous shape returned early as soon as the IPv4 piece was present, which made
        # any later piece unreachable on an existing checkout - the patch reported success
        # while the new code was simply never written. That is the same trap as the theme
        # wrapper: a guard that tests for an earlier piece can never admit a later one.
        # One ordered list, one insert point, one marker each.
        anchor = "        LauncherIconController.tryFixLauncherIconIfNeeded();"

        piece_init = (
                 "        LauncherIconController.tryFixLauncherIconIfNeeded();\n"
                 "        ProxyRotationController.init();\n"
                 "\n"
                 "        // Colgram: initialise LAST, once AndroidUtilities, the native library and\n"
                 "        // the Java<->native bridge all exist. See the note in apply-patches.py -\n"
                 "        // this used to run before super.onCreate() and that was a real bug.\n"
                 "        try {\n"
                 "            org.colgram.core.ColgramHookHandler.init(applicationContext);\n"
                 "            org.colgram.core.ColgramUiBridge.install(this);\n"
                 "        } catch (Throwable ignore) {\n"
                 "\n"
                 "        }\n")

        piece_ipv4 = ("\n"
                "        // Colgram: pin outbound HTTP dials to IPv4 where the device has no usable\n"
                "        // IPv6 route. See ColgramBotSync.colgramIpv4ProxyPort for why neither a\n"
                "        // global property nor a custom SocketFactory can do this on Android.\n"
                "        try {\n"
                "            org.colgram.core.ColgramBotSync.applyIpv4Policy();\n"
                "        } catch (Throwable ignore) {\n"
                "\n"
                "        }\n")

        piece_service = ("\n"
                "        // Colgram: keep the process resident so bot chats keep syncing.\n"
                "        //\n"
                "        // Without push (FCM is blocked for this package) the process dies as soon as\n"
                "        // the user leaves the app, and everything downstream follows: chats render\n"
                "        // empty until a restart, re-entry is a cold start, and a rebooted phone\n"
                "        // syncs nothing. A foreground service is the supported way to stay alive;\n"
                "        // ColgramBootReceiver brings it back after a reboot.\n"
                "        try {\n"
                "            org.colgram.core.ColgramForegroundService.start(this);\n"
                "        } catch (Throwable ignore) {\n"
                "\n"
                "        }\n")

        pieces = [
            ("ColgramUiBridge.install(this)", piece_init),
            ("ColgramBotSync.applyIpv4Policy()", piece_ipv4),
            ("ColgramForegroundService.start(this)", piece_service),
        ]

        def _match_block(s, brace_pos):
            depth = 0
            i = brace_pos
            while i < len(s):
                if s[i] == "{":
                    depth += 1
                elif s[i] == "}":
                    depth -= 1
                    if depth == 0:
                        return i
                i += 1
            return -1

        def _after_trycatch(s, marker):
            """Index just past the try/catch that contains `marker`."""
            at = s.index(marker)
            try_kw = s.rindex("try", 0, at)
            end = _match_block(s, s.index("{", try_kw))
            if end == -1:
                return -1
            # Naive brace-matching from `try {` lands on the closing brace of the TRY BLOCK,
            # i.e. mid-statement, between `try { ... }` and `catch (...) {`. Inserting there
            # produced a stray second catch. Consume the catch block too.
            tail = s[end + 1:]
            lead = len(tail) - len(tail.lstrip())
            if tail.lstrip().startswith("catch"):
                ck = end + 1 + lead
                ce = _match_block(s, s.index("{", ck))
                if ce != -1:
                    end = ce
            return end + 1

        if "ColgramUiBridge.install(this)" not in content:
            # Fresh file: all three pieces at once.
            if anchor not in content:
                print(" [!] ApplicationLoader anchor not found - Colgram init NOT injected")
                return content
            return content.replace(anchor, piece_init + piece_ipv4 + piece_service, 1)

        # Already initialised. Add whichever pieces are missing, after the LAST Colgram
        # try/catch so the ordering stays init -> ipv4 -> service.
        for marker, piece in pieces[1:]:
            if marker in content:
                continue
            insert_at = -1
            for prev_marker, _ in reversed(pieces):
                if prev_marker in content:
                    insert_at = _after_trycatch(content, prev_marker)
                    if insert_at != -1:
                        break
            if insert_at == -1:
                print(" [!] could not locate an insertion point for " + marker)
                continue
            content = content[:insert_at] + piece + content[insert_at:]

        return content

    patch_file(
        app_loader,
        app_loader_replacer,
        None,
        "ApplicationLoader.onCreate Colgram initialization (after native load)"
    )

    # 2. ConnectionsManager.java -> Hardware & OS Cloaking
    conn_manager = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "tgnet", "ConnectionsManager.java")
    
    def cloak_replacer(content):
        if "org.colgram.core.ColgramHookHandler.hookInitConnection" in content:
            return content
        target = "init(SharedConfig.buildVersion()"
        if target not in content:
            return content
        inject_code = """
        java.util.Map<String, String> cloaked = org.colgram.core.ColgramHookHandler.hookInitConnection(deviceModel, systemVersion, appVersion, langCode);
        if (cloaked != null) {
            if (cloaked.containsKey("device_model")) deviceModel = cloaked.get("device_model");
            if (cloaked.containsKey("system_version")) systemVersion = cloaked.get("system_version");
            if (cloaked.containsKey("app_version")) appVersion = cloaked.get("app_version");
            if (cloaked.containsKey("lang_code")) langCode = cloaked.get("lang_code");
        }
        """
        return content.replace(target, inject_code + "\n        " + target, 1)

    patch_file(conn_manager, cloak_replacer, "org.colgram.core.ColgramHookHandler.hookInitConnection", "ConnectionsManager MTProto Cloaking")

    # 2b. ConnectionsManager.java -> QR Login Token Updates Hook
    def conn_qr_replacer(content):
        if "ColgramQRLoginBottomSheet.onLoginTokenUpdate" in content:
            return content
        target1 = "FileLog.dumpUnparsedMessage(message, messageId, currentAccount);"
        inject1 = """FileLog.dumpUnparsedMessage(message, messageId, currentAccount);
            if (constructor == 0x564fe691 || message instanceof org.telegram.tgnet.tl.TL_update.TL_updateLoginToken) {
                org.telegram.ui.ColgramQRLoginBottomSheet.onLoginTokenUpdate(currentAccount);
            }"""
        if target1 in content:
            content = content.replace(target1, inject1, 1)
        return content

    patch_file(conn_manager, conn_qr_replacer, "org.telegram.ui.ColgramQRLoginBottomSheet.onLoginTokenUpdate(currentAccount);", "ConnectionsManager QR Login Token Hook")

    # 3. FileLoader.java -> Storage Sandbox & Media Lock
    file_loader = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FileLoader.java")
    patch_file(
        file_loader,
        "public static File getDirectory(int type) {",
        "public static File getDirectory(int type) {\n        File sandboxed = org.colgram.core.ColgramHookHandler.hookGetDirectory(type);\n        if (sandboxed != null) return sandboxed;",
        "FileLoader.getDirectory Sandbox Redirect"
    )
    # FileLoader.deleteFiles -> media preservation guard.
    #
    # ⚠️ Anchoring on a bare "if (!file.delete()) {" is dangerous: that exact text
    # appears TWICE in deleteFiles() — once for `file` and once for the `q_` thumbnail.
    # A first-match replace therefore guards the main file but leaves the thumbnail
    # deletable, which is why "media preservation" left q_*.jpg files vanishing.
    # Anchor on the enclosing `else if (file.exists()) {` branch instead, which is
    # unique, and carry the original branch body through the replacement.
    #
    # The guard must be `continue`, not `return`: it sits inside a `for` loop over the
    # file list, and returning would abort preservation for every remaining file.
    def media_lock_replacer(content):
        old = """                } else if (file.exists()) {
                    try {
                        if (!file.delete()) {
                            file.deleteOnExit();
                        }"""
        new = """                } else if (file.exists()) {
                    if (org.colgram.core.ColgramHookHandler.shouldPreventMediaDeletion(file)) {
                        continue;
                    }
                    try {
                        if (!file.delete()) {
                            file.deleteOnExit();
                        }"""
        if "shouldPreventMediaDeletion(file)) {\n                        continue;" in content:
            return content                       # already in canonical form
        if old in content:
            return content.replace(old, new, 1)
        # Fall back to the legacy single-line shape so an already-patched tree
        # converges instead of silently keeping the broken indentation.
        legacy = "if (org.colgram.core.ColgramHookHandler.shouldPreventMediaDeletion(file)) continue;\n                if (!file.delete()) {"
        if legacy in content:
            return content.replace(legacy,
                    "if (org.colgram.core.ColgramHookHandler.shouldPreventMediaDeletion(file)) {\n                        continue;\n                    }\n                    if (!file.delete()) {", 1)
        print(" [!] FileLoader.deleteFiles media-lock anchor not found")
        return content
    patch_file(
        file_loader,
        media_lock_replacer,
        "if (org.colgram.core.ColgramHookHandler.shouldPreventMediaDeletion(file)) {",
        "FileLoader.deleteFiles Media Lock"
    )

    # 4. FlagSecureReason.java -> FLAG_SECURE Bypass
    flag_secure = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FlagSecureReason.java")
    patch_file(
        flag_secure,
        "public static boolean isSecuredNow(Window window) {",
        "public static boolean isSecuredNow(Window window) {\n        if (org.colgram.core.ColgramHookHandler.shouldBypassFlagSecure()) return false;",
        "FlagSecureReason Bypass"
    )

    # 5. ChatMessageCell.java -> Visual cue for deleted messages
    #
    # HISTORY, because this one was wrong twice:
    #
    #   v1 set setAlpha(0.65f) here. That is what made retained messages read as "just
    #      greyed out" — and worse, it stomped the alpha Telegram's own selection and
    #      animation code manages, so a message could sit at partial opacity with a blue
    #      highlight that never cleared. Looked stuck-selected. Removed.
    #
    #   v2 forced setAlpha(1.0f) to undo v1. Still wrong, just less visible: this method
    #      is called on every bind, and overwriting the alpha here fights the animation
    #      code that owns it. Any setAlpha we do from this hook is a bug.
    #
    # v3 (now): do nothing to the view alpha at all. The cue is purely textual — the 🗑
    # prefix in the time string, done in patch 30 below. Nothing else touches the cell.
    #
    # Keeping a no-op patch entry would be dead weight, so the entry is gone entirely —
    # but the path constant has to stay, because patch 30 genuinely does patch this file.
    chat_cell = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Cells", "ChatMessageCell.java")

    # 6. MessagesController.java -> Ghost Mode (Suppress Read & Typing)
    messages_controller = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesController.java")
    patch_file(
        messages_controller,
        "public void markDialogAsRead(long dialogId, int maxPositiveId, int maxNegativeId, int maxDate, boolean popup, long threadId, int countDiff, boolean readNow, int scheduledCount) {",
        """public void markDialogAsRead(long dialogId, int maxPositiveId, int maxNegativeId, int maxDate, boolean popup, long threadId, int countDiff, boolean readNow, int scheduledCount) {
        // GHOST MODE — suppress only the SERVER read receipt, never the local state.
        //
        // Returning here outright (the previous behaviour) was wrong and is why stealth
        // mode felt broken: markDialogAsRead is also what clears the local unread counter
        // and persists processPendingRead(). Bailing at the top left every chat permanently
        // unread in the UI, with a badge that could never be dismissed, while the peer saw
        // nothing — the worst of both worlds.
        //
        // Correct behaviour: run the normal local bookkeeping so the chat actually reads as
        // read on this device, and skip only the outgoing ReadTask that would tell the
        // server (and therefore the sender) that we read it.
        final boolean colgramGhostRead = org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId);""",
        "MessagesController Ghost Read Receipt"
    )
    # 6d. MessagesController.java -> Ghost mode: never stage the server read task.
    #
    # This is the second half of the ghost-read fix above. The local bookkeeping has run,
    # so the dialog's unread counter is already cleared; the only thing left to suppress is
    # the ReadTask, which is what actually sends messages.readHistory to the server.
    patch_file(
        messages_controller,
        """        if (createReadTask) {
            Utilities.stageQueue.postRunnable(() -> {
                ReadTask currentReadTask;
                if (threadId != 0) {""",
        """        if (colgramGhostRead) {
            // Ghost mode: skip the outgoing receipt entirely. Local read state above has
            // already been updated, so the chat reads correctly on this device.
            return;
        }

        if (createReadTask) {
            Utilities.stageQueue.postRunnable(() -> {
                ReadTask currentReadTask;
                if (threadId != 0) {""",
        "MessagesController Ghost Read Task Suppression"
    )

    # 6e. MessagesController.java -> Keep the local unread counter honest in ghost mode.
    #
    # markDialogAsRead's early `return` above bypasses the notification/badge refresh
    # further down. Push a dialogsNeedReload so the UI reflects the cleared counter rather
    # than showing a stale badge until the next full reload.
    patch_file(
        messages_controller,
        "org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId);\n        boolean createReadTask;",
        "org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId);\n        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);\n        boolean createReadTask;",
        "MessagesController Ghost Read Refresh Dialogs"
    )

    patch_file(
        messages_controller,
        "public boolean sendTyping(long dialogId, long threadMsgId, int action, int classGuid) {",
        "public boolean sendTyping(long dialogId, long threadMsgId, int action, int classGuid) {\n        if (org.colgram.core.ColgramHookHandler.shouldPreventTypingStatus(dialogId)) return false;",
        "MessagesController Ghost Typing Suppression (int)"
    )
    patch_file(
        messages_controller,
        "public boolean sendTyping(long dialogId, long threadMsgId, int action, String emojicon, int classGuid) {",
        "public boolean sendTyping(long dialogId, long threadMsgId, int action, String emojicon, int classGuid) {\n        if (org.colgram.core.ColgramHookHandler.shouldPreventTypingStatus(dialogId)) return false;",
        "MessagesController Ghost Typing Suppression (String)"
    )

    # 6f. MessagesController.java -> Ghost mode: actually keep the account offline.
    #
    # THIS PATCH WAS MISSING, and its absence is the whole reason "invisibility works
    # badly". ColgramGhostMode.shouldStayOffline() has existed from the start but NOTHING
    # called it — the hook was dead code. Read-receipt and typing suppression do not help
    # at all if the account still broadcasts presence.
    #
    # Telegram's real online signal is the account.updateStatus RPC: `offline = false` is
    # what flips you to "online" for every contact. It is sent on screen-on / app
    # foreground and re-sent at most every 55 seconds, so a user who looks invisible in
    # the UI is in fact broadcasting presence the entire time.
    #
    # There is no setOnline() helper, so the correct lever is the `ignoreSetOnline` flag
    # that already guards this whole block (line 10527): it is exactly the "do not touch
    # presence right now" switch Telegram uses itself. With ghost-online ON we set it
    # before the check, so neither the `offline = false` nor the `offline = true` branch
    # runs and the account keeps whatever state it last had — nobody sees us come online.
    # When OFF, behaviour is verbatim upstream.
    patch_file(
        messages_controller,
        """        if (getUserConfig().isClientActivated()) {
            if (!ignoreSetOnline && getConnectionsManager().getPauseTime() == 0 && ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePausedStageQueue) {""",
        """        if (getUserConfig().isClientActivated()) {
            if (org.colgram.core.ColgramHookHandler.shouldStayOffline()) {
                // Ghost mode: never announce presence. ignoreSetOnline is the same latch
                // Telegram uses internally to suppress the updateStatus RPC entirely, so
                // neither the online nor the offline branch below will run.
                ignoreSetOnline = true;
            }
            if (!ignoreSetOnline && getConnectionsManager().getPauseTime() == 0 && ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePausedStageQueue) {""",
        "MessagesController Ghost Online Suppression"
    )

    # 6b. MessagesController.java -> Anti-Delete at the REAL removal site.
    #
    # This is the single most important anti-delete patch. Upstream deleteMessages()
    # is what actually destroys a message, and it does two harmful things at once:
    #
    #   1. obj.deleted = true on the live MessageObject. This is an internal lifecycle
    #      flag, not styling. It makes ChatMessageCell bail out of the share button
    #      (checkNeedDrawShareButton), suppress name/time/caption drawing, and skip
    #      context-menu and selection paths — so the bubble greys out, stays visually
    #      "selected", cannot be replied to, and any action needing the real id fails
    #      with MESSAGE_ID_INVALID.
    #   2. markMessagesAsDeleted() + updateDialogsWithDeletedMessages() wipe the row
    #      from SQLite, so on the next reload the message is gone for real.
    #
    # The old anti-delete patch only touched ChatActivity.processDeletedMessages, which
    # is the UI-side notification — by then the MessageObject had already been tombstoned
    # and storage already scheduled for deletion. That is why retained messages were
    # broken rather than preserved.
    #
    # Correct behaviour when anti-delete is ON: record the id for tombstone styling and
    # leave the message completely untouched — fully replyable, deletable, forwardable.
    # When it is OFF we fall through to upstream behaviour verbatim.
    patch_file(
        messages_controller,
        """            } else {
                if (channelId == 0) {
                    for (int a = 0; a < messages.size(); a++) {
                        Integer id = messages.get(a);
                        MessageObject obj = dialogMessagesByIds.get(id);
                        if (obj != null) {
                            obj.deleted = true;
                        }
                    }
                } else {
                    markDialogMessageAsDeleted(dialogId, messages);
                }
                getMessagesStorage().markMessagesAsDeleted(dialogId, messages, true, forAll, 0, topicId);
                getMessagesStorage().updateDialogsWithDeletedMessages(dialogId, channelId, messages, null);
            }""",
            """            } else {
                final boolean colgramKeep = org.colgram.core.ColgramConfig.isAntiDeleteEnabled();
                final boolean colgramWipe = org.colgram.core.ColgramConfig.isAntiDeleteWipeEnabled();
                if (channelId == 0) {
                    for (int a = 0; a < messages.size(); a++) {
                        Integer id = messages.get(a);
                        MessageObject obj = dialogMessagesByIds.get(id);
                        if (obj != null && !colgramKeep) {
                            obj.deleted = true;
                        }
                    }
                } else {
                    if (!colgramKeep) {
                        markDialogMessageAsDeleted(dialogId, messages);
                    }
                }
                if (colgramKeep) {
                    // Record for tombstone styling only. The MessageObject keeps its
                    // normal state so reply, forward, selection and delete all work.
                    for (int a = 0; a < messages.size(); a++) {
                        org.colgram.core.ColgramHookHandler.hookShouldPreventDelete(
                                channelId != 0 ? -channelId : dialogId, messages.get(a));
                    }
                    if (colgramWipe) {
                        // Tombstone only — the row stays in SQLite so the message survives a
                        // reload and still reads as a message, not a hole in the timeline.
                        // This is the AyuGram behaviour.
                        getMessagesStorage().markMessagesAsDeleted(dialogId, messages, true, false, 0, topicId);
                    }
                } else {
                    getMessagesStorage().markMessagesAsDeleted(dialogId, messages, true, forAll, 0, topicId);
                    getMessagesStorage().updateDialogsWithDeletedMessages(dialogId, channelId, messages, null);
                }
            }""",
        "MessagesController Anti-Delete Preserve Message In Storage"
    )

    # 7. LoginActivity.java -> Suppress phone call permission requests completely
    login_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LoginActivity.java")
    patch_file(
        login_activity,
        "private boolean checkPermissions = true;",
        "private boolean checkPermissions = false; // Colgram: suppress call permission nags",
        "LoginActivity Disable checkPermissions Field"
    )
    patch_file(
        login_activity,
        "private boolean checkShowPermissions = true;",
        "private boolean checkShowPermissions = false; // Colgram: suppress call permission nags",
        "LoginActivity Disable checkShowPermissions Field"
    )
    patch_file(
        login_activity,
        "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && AndroidUtilities.isSimAvailable()) {",
        "checkPermissions = false;\n                        if (false) {",
        "LoginActivity Suppress Call Permissions (onConfirm)"
    )
    patch_file(
        login_activity,
        "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && simcardAvailable) {",
        "simcardAvailable = false; checkPermissions = false;\n            if (false) {",
        "LoginActivity Suppress Call Permissions (Primary)"
    )
    patch_file(
        login_activity,
        "if (checkShowPermissions && (!allowCall || !allowReadPhoneNumbers)) {",
        "checkShowPermissions = false;\n                        if (false) {",
        "LoginActivity Suppress Call Permissions (Secondary)"
    )

    # 8. SendMessagesHelper.java -> Intercept outgoing messages for Python commands and plugins
    send_messages_helper = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "SendMessagesHelper.java")
    patch_file(
        send_messages_helper,
        "public void sendMessage(SendMessageParams sendMessageParams) {",
        """public void sendMessage(SendMessageParams sendMessageParams) {
        if (sendMessageParams != null && sendMessageParams.message != null) {
            int replyId = sendMessageParams.replyToMsg != null ? sendMessageParams.replyToMsg.getId() : 0;
            if (org.colgram.core.ColgramHookHandler.hookOnSendMessage(sendMessageParams.peer, replyId, sendMessageParams.message)) {
                return;
            }
        }""",
        "SendMessagesHelper Plugin & Python Command Interceptor"
    )

    # 9. TLRPC.java -> Inject TL_auth_importBotAuthorization for native bot login
    tlrpc_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "tgnet", "TLRPC.java")
    tl_import_auth_target = """    public static class TL_auth_importAuthorization extends TLObject {
        public static final int constructor = 0xa57a7dad;

        public long id;
        public byte[] bytes;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return auth_Authorization.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeByteArray(bytes);
        }
    }"""
    tl_import_bot_auth_code = """    public static class TL_auth_importAuthorization extends TLObject {
        public static final int constructor = 0xa57a7dad;

        public long id;
        public byte[] bytes;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return auth_Authorization.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeByteArray(bytes);
        }
    }

    public static class TL_auth_importBotAuthorization extends TLObject {
        public static final int constructor = 0x67a3ff2c;

        public int flags;
        public int api_id;
        public String api_hash;
        public String bot_auth_token;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return auth_Authorization.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt32(api_id);
            stream.writeString(api_hash);
            stream.writeString(bot_auth_token);
        }
    }"""
    patch_file(
        tlrpc_file,
        tl_import_auth_target,
        tl_import_bot_auth_code,
        "TLRPC Inject TL_auth_importBotAuthorization"
    )

    # 10. ColgramBotLoginBottomSheet.java & ColgramQRLoginBottomSheet.java -> Deploy Telegram-Native BottomSheets
    bot_sheet_template = os.path.join(os.path.dirname(__file__), "templates", "ColgramBotLoginBottomSheet.java")
    bot_sheet_dest = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramBotLoginBottomSheet.java")
    if os.path.exists(bot_sheet_template):
        shutil.copyfile(bot_sheet_template, bot_sheet_dest)
        print(" [+] Deployed Telegram-Native ColgramBotLoginBottomSheet.java")

    qr_sheet_template = os.path.join(os.path.dirname(__file__), "templates", "ColgramQRLoginBottomSheet.java")
    qr_sheet_dest = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramQRLoginBottomSheet.java")
    if os.path.exists(qr_sheet_template):
        shutil.copyfile(qr_sheet_template, qr_sheet_dest)
        print(" [+] Deployed Telegram-Native ColgramQRLoginBottomSheet.java")

    # 11. LoginActivity.java -> Make onAuthSuccess public
    patch_file(
        login_activity,
        "private void onAuthSuccess(TLRPC.TL_auth_authorization res) {",
        "public void onAuthSuccess(TLRPC.TL_auth_authorization res) {",
        "LoginActivity Make onAuthSuccess Public"
    )

    # 12. LoginActivity.java -> Inject QR Login & Bot Token Login buttons between subtitleView and countryButton
    alt_login_btn = """addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 8, 32, 0));

            LinearLayout altButtonsRow = new LinearLayout(context);
            altButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
            altButtonsRow.setGravity(Gravity.CENTER);
            boolean isRuLang = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            int accentBtnColor = Theme.getColor(Theme.key_featuredStickers_addButton);
            if (accentBtnColor == 0) accentBtnColor = 0xFF2AABEE;

            TextView qrLoginBtn = new TextView(context);
            qrLoginBtn.setText(isRuLang ? "📷 Вход по QR" : "📷 QR Log in");
            qrLoginBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            qrLoginBtn.setTypeface(AndroidUtilities.bold());
            qrLoginBtn.setTextColor(accentBtnColor);
            qrLoginBtn.setGravity(Gravity.CENTER);
            qrLoginBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
            qrLoginBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), accentBtnColor & 0x18ffffff, accentBtnColor & 0x33ffffff));
            qrLoginBtn.setOnClickListener(v -> {
                org.telegram.ui.ColgramQRLoginBottomSheet.show(LoginActivity.this, currentAccount);
            });
            altButtonsRow.addView(qrLoginBtn, LayoutHelper.createLinear(0, 36, 1.0f, Gravity.CENTER, 0, 0, 6, 0));

            TextView botLoginBtn = new TextView(context);
            botLoginBtn.setText(isRuLang ? "🤖 Токен бота" : "🤖 Bot Token");
            botLoginBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            botLoginBtn.setTypeface(AndroidUtilities.bold());
            botLoginBtn.setTextColor(accentBtnColor);
            botLoginBtn.setGravity(Gravity.CENTER);
            botLoginBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
            botLoginBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), accentBtnColor & 0x18ffffff, accentBtnColor & 0x33ffffff));
            botLoginBtn.setOnClickListener(v -> {
                org.telegram.ui.ColgramBotLoginBottomSheet.show(LoginActivity.this, currentAccount);
            });
            altButtonsRow.addView(botLoginBtn, LayoutHelper.createLinear(0, 36, 1.0f, Gravity.CENTER, 6, 0, 0, 0));

            addView(altButtonsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 14, 32, 6));"""

    def login_btn_replacer(content):
        # Remove legacy vertical button layout if present
        legacy_pattern = """addView(phoneOutlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 8, 16, 8));
            LinearLayout altLoginButtonsLayout = new LinearLayout(context);"""
        if legacy_pattern in content:
            import re
            content = re.sub(
                r'LinearLayout altLoginButtonsLayout = new LinearLayout\(context\);.*?addView\(altLoginButtonsLayout, LayoutHelper\.createLinear\(LayoutHelper\.MATCH_PARENT, LayoutHelper\.WRAP_CONTENT, Gravity\.CENTER_HORIZONTAL, 16, 2, 76, 4\)\);',
                '',
                content,
                flags=re.DOTALL
            )
        if "altButtonsRow" in content:
            return content
        target = "addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 8, 32, 0));"
        if target in content:
            return content.replace(target, alt_login_btn, 1)
        return content

    patch_file(
        login_activity,
        login_btn_replacer,
        alt_login_btn,
        "LoginActivity QR Login & Bot Token Buttons"
    )

    # 13. LoginActivity.java -> back button on VIEW_PHONE_INPUT
    #
    # HISTORY: an earlier Colgram build pushed a NEW IntroActivity onto the stack while
    # leaving the LoginActivity alive, so re-entering "Start Messaging" stacked a second
    # LoginActivity and backing out got stuck in a growing back stack.
    #
    # That patch is now OBSOLETE AND HARMFUL. Current upstream already guards it:
    #
    #     if (activityMode == MODE_LOGIN && (parentLayout == null
    #             || parentLayout.getFragmentStack().size() <= 1)) {
    #         presentFragment(new IntroActivity(), true);
    #         return false;
    #     }
    #
    # i.e. it only pushes IntroActivity when there is nothing to pop. The old anchor no
    # longer matches this code, and forcing our own replacement would duplicate the
    # guard. So we only record whether upstream is already correct, and stay out of it.
    login_activity_onback_ok = False
    if os.path.exists(login_activity):
        with open(login_activity, "r", encoding="utf-8", errors="ignore") as f:
            _la = f.read()
        login_activity_onback_ok = "getFragmentStack().size() <= 1" in _la
    if login_activity_onback_ok:
        print(" [=] LoginActivity back navigation already correct upstream - no patch needed")
    else:
        print(" [!] LoginActivity back navigation differs from the expected upstream form; "
              "inspect onBackPressed(boolean) manually.")

    # 14. UserConfig.java -> Unlock all account slots
    user_config = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "UserConfig.java")
    patch_file(
        user_config,
        "public static int getMaxAccountCount() {\n        return hasPremiumOnAccounts() ? 5 : 3;\n    }",
        "public static int getMaxAccountCount() {\n        return UserConfig.MAX_ACCOUNT_COUNT;\n    }",
        "UserConfig Unlock Max Account Count"
    )
    patch_file(
        user_config,
        "public static boolean hasPremiumOnAccounts() {",
        "public static boolean hasPremiumOnAccounts() {\n        if (true) return true;",
        "UserConfig Has Premium on Accounts"
    )

    # 14. UserInfoActivity.java -> Bypass account limit check on Add Account
    user_info_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "UserInfoActivity.java")
    patch_file(
        user_info_activity,
        "if (!UserConfig.hasPremiumOnAccounts()) {\n                freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT);\n            }",
        "// Colgram: all account slots unlocked without premium",
        "UserInfoActivity Unlock Add Account"
    )

    # 15. IntroActivity.java -> Full Instant Language Switching & Top-Right Language Badge
    intro_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "IntroActivity.java")
    if os.path.exists(intro_activity):
        def intro_lang_replacer(content):
            # ---------------------------------------------------------------------------
            # REPAIR: badge alignment, applied even when the badge already exists.
            #
            # Must run BEFORE the early return below, otherwise a tree that already has the
            # badge can never receive a correction - the guard keys on the very marker the
            # badge introduces.
            # ---------------------------------------------------------------------------
            # Regex rather than a literal: the literal form was brittle to whitespace and
            # silently failed to match once already.
            #
            # It must accept the PRISTINE upstream form as well as the previously-patched
            # one. The old pattern required `themeMargin + 64 + 8` in the right margin, but
            # that string was produced by a LATER patch — so on a fresh clone this regex
            # never matched, the later patch then wrote `top = 16` (a hard-coded margin that
            # ignores statusBarHeight), and the badge rode under the status bar on every CI
            # build while the local checkout looked correct. Anchoring on both forms makes
            # this patch order-independent and convergent.
            badge_re = re.compile(
                r'frameContainerView\.addView\(langBadge,\s*LayoutHelper\.createFrame\('
                r'\s*LayoutHelper\.WRAP_CONTENT,\s*32,\s*Gravity\.TOP \| Gravity\.RIGHT,'
                r'\s*0,\s*[^,()]+,\s*(?:\d+|themeMargin \+ 64 \+ 8),\s*0\)\);')
            good_badge = (
                '        // Vertically centred against the theme switcher beside it.\n'
                '        //\n'
                '        // The switcher is positioned as `dp(themeMargin) + statusBarHeight`, so a\n'
                '        // hard-coded top margin here put the badge at a different height and it\n'
                '        // rode up under the status bar / notch. Both margins are DP;\n'
                '        // statusBarHeight is in PIXELS, hence the density division.\n'
                '        float colgramBadgeTop = themeMargin + 16;\n'
                '        if (!AndroidUtilities.isTablet()) {\n'
                '            colgramBadgeTop += AndroidUtilities.statusBarHeight / AndroidUtilities.density;\n'
                '        }\n'
                '        frameContainerView.addView(langBadge, LayoutHelper.createFrame('
                'LayoutHelper.WRAP_CONTENT, 32, Gravity.TOP | Gravity.RIGHT, 0, colgramBadgeTop, '
                'themeMargin + 64 + 8, 0));')
            if 'colgramBadgeTop' not in content:
                content = badge_re.sub(good_badge, content, count=1)

            # Give the switch-language line an explicit, theme-aware colour.
            #
            # It had none, so it inherited the platform default and could end up low-contrast
            # on the intro's dark background - observed as a hard-to-read red line above the
            # main button. key_windowBackgroundWhiteBlueText is a link colour (semantically
            # right for "switch language") and is covered by the dark-surface contrast guard.
            # ---------------------------------------------------------------------------
            # updateColors(): remove the two hardcoded reds.
            #
            #   startMessagingButtonBackground.setColors({0xFFD32F2F, 0xFF8B0000})
            #   switchLanguageTextView.setTextColor(0xFFEF5350)
            #
            # Neither follows the theme, so the intro ignored light/dark and clashed with the
            # cyber palette - the switch-language line rendered as a raw red regardless of
            # what the user had selected. Both now derive from the active accent, which is
            # also what the cyber palette overrides, so the screen finally matches the theme.
            # ---------------------------------------------------------------------------
            if 'colgramAccent' not in content:
                new_col = (
                    '        // Derived from the active accent instead of a hardcoded red, so the\n'
                    '        // intro follows the theme (including the cyber palette).\n'
                    '        int colgramAccent = Theme.getColor(Theme.key_chats_actionBackground);\n'
                    '        int colgramAccentDark = (colgramAccent & 0xFF000000)\n'
                    '                | ((int) (((colgramAccent >> 16) & 0xFF) * 0.7f) << 16)\n'
                    '                | ((int) (((colgramAccent >> 8) & 0xFF) * 0.7f) << 8)\n'
                    '                | (int) ((colgramAccent & 0xFF) * 0.7f);\n'
                    '        startMessagingButtonBackground.setColors(new int[]{colgramAccent, colgramAccentDark});\n')
                # Accept BOTH the pristine upstream line and the hardcoded-red line. Only the
                # red one was handled, and it is produced by a LATER patch — so on a fresh
                # clone this block found nothing, the later patch then wrote raw reds, and the
                # intro ignored the theme in every CI build.
                for old_col in (
                    '        startMessagingButtonBackground.setColors(new int[]{0xFFD32F2F, 0xFF8B0000});\n',
                    '        startMessagingButtonBackground.setColors(new int[]{getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButton2)});\n',
                ):
                    if old_col in content:
                        content = content.replace(old_col, new_col, 1)

                new_lang = (
                    '        // Was 0xFFEF5350 - a raw red that ignored the theme entirely.\n'
                    '        switchLanguageTextView.setTextColor(\n'
                    '                Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));\n')
                for old_lang in (
                    '        switchLanguageTextView.setTextColor(0xFFEF5350);\n',
                    '        switchLanguageTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));\n',
                ):
                    if old_lang in content:
                        content = content.replace(old_lang, new_lang, 1)
                        break

            if "langBadge.setText" in content:
                return content
            
            # 1. Start messaging button text & click listener with language persistence
            old_btn_pattern = """        startMessagingButton.setText(LocaleController.getString(R.string.StartMessaging));
        startMessagingButton.setGravity(Gravity.CENTER);
        startMessagingButton.setTypeface(AndroidUtilities.bold());
        startMessagingButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        startMessagingButton.setPadding(dp(34), 0, dp(34), 0);
        frameContainerView.addView(startMessagingButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 16, 0, 16, 76));
        startMessagingButton.setOnClickListener(view -> {
            if (startPressed) {
                return;
            }
            startPressed = true;

            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), true);
            destroyed = true;
        });"""
            new_btn_code = """        boolean isRuStart = LocaleController.getInstance().getCurrentLocaleInfo() != null
                && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);

        startMessagingButton.setText(isRuStart ? "Начать общение" : "Start Messaging");
        startMessagingButton.setGravity(Gravity.CENTER);
        startMessagingButton.setTypeface(AndroidUtilities.bold());
        startMessagingButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        startMessagingButton.setPadding(dp(34), 0, dp(34), 0);
        frameContainerView.addView(startMessagingButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 16, 0, 16, 76));
        startMessagingButton.setOnClickListener(view -> {
            if (startPressed) {
                return;
            }
            startPressed = true;

            // Do NOT call applyLanguage() here.
            //
            // applyLanguage() is a full locale reload: it re-parses resources, resets the
            // connection's lang code, and can trigger a remote language fetch. Running that
            // synchronously on the UI thread immediately before a fragment transition
            // stalls the transition — which is the "second press does nothing until
            // restart" bug.
            //
            // It is also redundant: the language was already applied when the user picked
            // it in the intro (see the switch-language handler below). We only need to make
            // sure the chosen language is persisted so it survives the restart.
            LocaleController.LocaleInfo cur = LocaleController.getInstance().getCurrentLocaleInfo();
            if (cur != null) {
                MessagesController.getGlobalMainSettings().edit().putString("language", cur.getKey()).apply();
            }

            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);
            destroyed = true;
        });"""
            if old_btn_pattern in content:
                content = content.replace(old_btn_pattern, new_btn_code, 1)

            # Colgram re-entry fix.
            #
            # `destroyed` is IntroActivity's guard for its EGL/render callbacks — see the
            # comment at the frame callback: "If display or surface already destroyed".
            # Upstream sets it true when we navigate away. An earlier Colgram revision
            # changed it to false, so the intro activity kept its render thread alive after
            # being left. Pressing "Начать общение" a second time then ran a fresh instance
            # alongside the stale thread, and the fragment transition deadlocked — the login
            # form never appeared until the process was restarted.
            #
            # Restore true, and guard any stray false that survived from an earlier run.
            if "destroyed = true;" in content and content.count("destroyed = false;") > 0:
                content = content.replace(
                    "presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);\n            destroyed = false;",
                    "presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);\n            destroyed = true;"
                )

            # 2. Switch language text view & top-right language badge
            old_switch_pattern = """        switchLanguageTextView = new TextView(context);
        switchLanguageTextView.setGravity(Gravity.CENTER);
        switchLanguageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        frameContainerView.addView(switchLanguageTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));
        switchLanguageTextView.setOnClickListener(v -> {
            if (startPressed || localeInfo == null) {
                return;
            }
            startPressed = true;

            AlertDialog loaderDialog = new AlertDialog(v.getContext(), AlertDialog.ALERT_TYPE_SPINNER);
            loaderDialog.setCanCancel(false);
            loaderDialog.showDelayed(1000);

            NotificationCenter.getGlobalInstance().addObserver(new NotificationCenter.NotificationCenterDelegate() {
                @Override
                public void didReceivedNotification(int id, int account, Object... args) {
                    if (id == NotificationCenter.reloadInterface) {
                        loaderDialog.dismiss();

                        NotificationCenter.getGlobalInstance().removeObserver(this, id);
                        AndroidUtilities.runOnUIThread(()->{
                            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), true);
                            destroyed = true;
                        }, 100);
                    }
                }
            }, NotificationCenter.reloadInterface);
            LocaleController.getInstance().applyLanguage(localeInfo, true, false, currentAccount);
        });"""
            new_switch_code = """        switchLanguageTextView = new TextView(context);
        switchLanguageTextView.setGravity(Gravity.CENTER);
        switchLanguageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        switchLanguageTextView.setText(isRuStart ? "Continue in English" : "Продолжить на русском");
        frameContainerView.addView(switchLanguageTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));
        switchLanguageTextView.setOnClickListener(v -> {
            if (startPressed) {
                return;
            }
            startPressed = true;

            boolean currentlyRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                    && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            String targetCode = currentlyRu ? "en" : "ru";
            LocaleController.LocaleInfo targetInfo = null;
            for (LocaleController.LocaleInfo info : LocaleController.getInstance().languages) {
                if (info != null && targetCode.equalsIgnoreCase(info.shortName)) {
                    targetInfo = info;
                    break;
                }
            }
            if (targetInfo != null) {
                LocaleController.getInstance().applyLanguage(targetInfo, true, false, currentAccount);
                MessagesController.getGlobalMainSettings().edit().putString("language", targetInfo.getKey()).apply();
            }
            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);
            destroyed = true;
        });

        // Top-right language badge
        TextView langBadge = new TextView(context);
        langBadge.setText(isRuStart ? "🇷🇺 RU" : "🇬🇧 EN");
        langBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        langBadge.setTypeface(AndroidUtilities.bold());
        langBadge.setTextColor(0xFFFFFFFF);
        langBadge.setGravity(Gravity.CENTER);
        langBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        langBadge.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), 0x22FFFFFF, 0x44FFFFFF));
        frameContainerView.addView(langBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, Gravity.TOP | Gravity.RIGHT, 0, 16, 16, 0));
        langBadge.setOnClickListener(v -> {
            boolean nowRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                    && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            String target = nowRu ? "en" : "ru";
            LocaleController.LocaleInfo targetInfo = null;
            for (LocaleController.LocaleInfo info : LocaleController.getInstance().languages) {
                if (info != null && target.equalsIgnoreCase(info.shortName)) {
                    targetInfo = info;
                    break;
                }
            }
            if (targetInfo != null) {
                LocaleController.getInstance().applyLanguage(targetInfo, true, false, currentAccount);
                MessagesController.getGlobalMainSettings().edit().putString("language", targetInfo.getKey()).apply();
                langBadge.setText(target.equals("ru") ? "🇷🇺 RU" : "🇬🇧 EN");
                startMessagingButton.setText(target.equals("ru") ? "Начать общение" : "Start Messaging");
                switchLanguageTextView.setText(target.equals("ru") ? "Continue in English" : "Продолжить на русском");
            }
        });"""
            if old_switch_pattern in content:
                content = content.replace(old_switch_pattern, new_switch_code, 1)

            # 3. Fast checkContinueText without network request
            start_tag = "private void checkContinueText() {"
            end_tag = "ConnectionsManager.RequestFlagWithoutLogin);"
            idx1 = content.find(start_tag)
            if idx1 != -1:
                idx2 = content.find(end_tag, idx1)
                if idx2 != -1:
                    brace_idx = content.find("}", idx2 + len(end_tag))
                    if brace_idx != -1:
                        end_pos = brace_idx + 1
                        new_check = """private void checkContinueText() {
        boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
        if (startMessagingButton != null) {
            startMessagingButton.setText(isRu ? "Начать общение" : "Start Messaging");
        }
        if (switchLanguageTextView != null) {
            switchLanguageTextView.setText(isRu ? "Continue in English" : "Продолжить на русском");
        }
    }"""
                        content = content[:idx1] + new_check + content[end_pos:]

            # 4. onResume resets startPressed & destroyed
            target_resume = "public void onResume() {\n        super.onResume();"
            if target_resume in content and "startPressed = false;" not in content:
                content = content.replace(target_resume, target_resume + "\n        startPressed = false;\n        destroyed = false;", 1)

            return content

        # The marker MUST name a symbol only the current version emits. With
        # "langBadge.setText" the guard was satisfied by every build that had the badge at
        # all, so the callable below - and every repair inside it - was dead code.
        patch_file(intro_activity, intro_lang_replacer, "colgramBadgeTop", "IntroActivity Instant Language Switcher & Badge")

    # 16. LoginActivity.java -> Visual Alert and Auto-Rotation on -1000 Connection Error in PhoneView
    phone_error_target = """fillNextCodeParams(params, (TLRPC.auth_SentCode) response);
                    }
                } else {
                    if (error.text != null) {"""
    phone_error_replacement = """fillNextCodeParams(params, (TLRPC.auth_SentCode) response);
                    }
                } else {
                    if (error != null && (error.code == -1000 || error.text == null)) {
                        try {
                            android.widget.Toast.makeText(getParentActivity(), "Ошибка соединения с Telegram (-1000). Переключаем прокси...", android.widget.Toast.LENGTH_SHORT).show();
                            org.colgram.core.ColgramProxyManager.switchToNextProxy();
                        } catch (Throwable ignored) {}
                    }
                    if (error.text != null) {"""
    patch_file(
        login_activity,
        phone_error_target,
        phone_error_replacement,
        "LoginActivity Handle -1000 Connection Error In PhoneView"
    )

    phone_error_target2 = """} else if (error.code != -1000) {
                            AlertsCreator.processError(currentAccount, error, LoginActivity.this, req, phoneInputData.phoneNumber);
                        }"""
    phone_error_replacement2 = """} else {
                            if (error.code != -1000) {
                                AlertsCreator.processError(currentAccount, error, LoginActivity.this, req, phoneInputData.phoneNumber);
                            } else {
                                try {
                                    android.widget.Toast.makeText(getParentActivity(), "Ошибка соединения с Telegram (-1000). Переключаем прокси...", android.widget.Toast.LENGTH_SHORT).show();
                                    org.colgram.core.ColgramProxyManager.switchToNextProxy();
                                } catch (Throwable ignored) {}
                            }
                        }"""
    # 17. ConnectionsManager.java -> Suppress proxy promo check (sponsor channels)
    patch_file(
        conn_manager,
        "accountInstance.getMessagesController().checkPromoInfo(true);",
        "// Colgram: Suppressed proxy promo check\n                    // accountInstance.getMessagesController().checkPromoInfo(true);",
        "ConnectionsManager Suppress checkPromoInfo"
    )

    # 18. MessagesController.java -> Suppress checkPromoInfo completely (kill proxy sponsor channels)
    def promo_suppressor(content):
        target = "private void checkPromoInfoInternal(boolean reset) {"
        if target not in content:
            return content
        inject = "private void checkPromoInfoInternal(boolean reset) {\n        if (true) return; // Colgram: kill proxy sponsor channels"
        return content.replace(target, inject, 1)
    patch_file(messages_controller, promo_suppressor, "if (true) return; // Colgram: kill proxy sponsor channels", "MessagesController Suppress checkPromoInfo")

    # 19. DialogsActivity.java -> Make Proxy Button Always Visible In Header & Popup Menu
    dialogs_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "DialogsActivity.java")
    if os.path.exists(dialogs_activity):
        patch_file(
            dialogs_activity,
            "final boolean proxyVisible = proxyEnabled && !TextUtils.isEmpty(proxyAddress)",
            "final boolean proxyVisible = true; // Colgram: proxy menu item always visible\n            final boolean proxyVisibleOld = proxyEnabled && !TextUtils.isEmpty(proxyAddress)",
            "DialogsActivity Proxy Menu Item Always Visible"
        )
        def header_proxy_updater(content):
            target_replacement = """downloadsItem.setVisibility(View.GONE);

            org.telegram.ui.ActionBar.ActionBarMenuItem colgramProxyItem = menu.addItem(2, proxyDrawable);
            if (colgramProxyItem != null) {
                colgramProxyItem.setContentDescription(getString(R.string.ProxySettings));
                colgramProxyItem.setOnClickListener(v -> org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity()));
                colgramProxyItem.setOnLongClickListener(v -> {
                    presentFragment(new ProxyListActivity());
                    return true;
                });
            }

            updateProxyButton(false, false);"""
            if "org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity())" in content:
                return content
            if "colgramProxyItem.setOnClickListener(v -> presentFragment(new ProxyListActivity()));" in content:
                return content.replace(
                    "colgramProxyItem.setOnClickListener(v -> presentFragment(new ProxyListActivity()));",
                    """colgramProxyItem.setOnClickListener(v -> org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity()));
                colgramProxyItem.setOnLongClickListener(v -> {
                    presentFragment(new ProxyListActivity());
                    return true;
                });"""
                )
            target = "downloadsItem.setVisibility(View.GONE);"
            inject = """downloadsItem.setVisibility(View.GONE);

            org.telegram.ui.ActionBar.ActionBarMenuItem colgramProxyItem = menu.addItem(2, proxyDrawable);
            if (colgramProxyItem != null) {
                colgramProxyItem.setContentDescription(getString(R.string.ProxySettings));
                colgramProxyItem.setOnClickListener(v -> org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity()));
                colgramProxyItem.setOnLongClickListener(v -> {
                    presentFragment(new ProxyListActivity());
                    return true;
                });
            }"""
            if target in content:
                return content.replace(target, inject, 1)
            return content
        patch_file(
            dialogs_activity,
            header_proxy_updater,
            "org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity())",
            "DialogsActivity Header Proxy Button 1-Tap Toggle"
        )

        def options_menu_injector(content):
            target = """        io.add(R.drawable.outline_saved_24, getString(R.string.SavedMessages), () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
            presentFragment(new ChatActivity(args));
        });"""
            if "🧩 Плагины и Маркетплейс" in content:
                return content
            inject = """
        io.addGap();
        io.add(R.drawable.msg_customize, "🧩 Плагины и Маркетплейс", () -> {
            presentFragment(new ColgramPluginsActivity());
        });
        io.add(R.drawable.msg_settings, "⚙️ Настройки Colgram", () -> {
            presentFragment(new ColgramSettingsActivity());
        });
        io.add(R.drawable.msg_send, "✉️ Временная почта (Temp Mail)", () -> {
            presentFragment(new ColgramTempMailActivity());
        });
        io.add(R.drawable.msg_download, "📥 Версии Telegram", () -> {
            presentFragment(new ColgramVersionsActivity());
        });
        io.add(R.drawable.msg_policy, "🛡 Анти-спам (юзербот)", () -> {
            presentFragment(new ColgramAntiSpamActivity());
        });
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            io.add(R.drawable.msg_retry, "🔄 Синхронизировать чаты бота", () -> {
                org.colgram.core.ColgramBotSync.syncBotDialogs(getParentActivity(), currentAccount, true);
            });
            io.add(R.drawable.msg_edit, "✉️ Написать от имени бота", () -> {
                org.colgram.core.ColgramBotSync.showStartChatDialog(getParentActivity(), currentAccount);
            });
        }"""
            return content.replace(target, target + inject, 1)

        patch_file(
            dialogs_activity,
            options_menu_injector,
            "🧩 Плагины и Маркетплейс",
            "DialogsActivity Options Menu: Colgram Entries"
        )

    # 49. UserConfig.java -> more accounts. HARD CAP 5, imposed by the native library.
    #
    # ⚠️ The Java value must NEVER exceed the value the native tgnet library was
    # compiled with, or the process corrupts its own memory. The native library is NOT
    # built from this tree: download_official_binaries() pulls libtmessages.49.so
    # straight out of telegram.org/dl/android/apk and disables externalNativeBuild, so
    # the shipped .so is byte-identical to the official one, compiled with:
    #
    #     jni/tgnet/Defines.h:  #define MAX_ACCOUNT_COUNT 5
    #
    # which sizes a GLOBAL array in jni/tgnet/ConnectionsManager.cpp:
    #
    #     JNIEnv *jniEnv[MAX_ACCOUNT_COUNT];                                  // 5 slots
    #     ...
    #     javaVm->AttachCurrentThread(&jniEnv[networkManager->instanceNum], nullptr);
    #
    # Every account's network thread stores its JNIEnv* at jniEnv[instanceNum]. With
    # Java raised to 10, accounts 5..9 write FIVE POINTERS PAST THE END of that array,
    # straight into whatever globals sit next to it. Native then dereferences the
    # clobbered slots from its own callbacks
    # (TgNetWrapper.cpp: jniEnv[instanceNum]->CallStaticVoidMethod(..., onUpdate, ...))
    # and the process dies with SIGSEGV inside libtmessages.49.so about 3s after launch.
    #
    # This is the long-standing "app dies right after the icon" crash. It is memory
    # corruption, which is why the signature MOVED between runs - one tombstone shows a
    # null deref (fault addr 0x32) on a native tgnet pthread, the next shows ArtMethod
    # corruption during ART's GC stack walk (FindOatMethodFor, fault addr 0x76). Both
    # were blamed on the wrong thing for a long time: first an fdsan socket race, then
    # IntroActivity.setVisibility(GONE), then "the native storage layer cannot hold that
    # many SQLite handles". None of those were it. The array bound is.
    #
    # Stock is Java 4 / native 5 - upstream keeps one slot spare, which is why official
    # Telegram is fine. 5 is therefore the highest value that cannot overflow, and it
    # still gives one more account than stock. Going beyond 5 REQUIRES rebuilding
    # libtmessages from jni/ with a larger constant; raising only the Java side is
    # guaranteed memory corruption.
    user_config = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "UserConfig.java")
    patch_file(
        user_config,
        "public final static int MAX_ACCOUNT_COUNT = 4;",
        "public final static int MAX_ACCOUNT_COUNT = 5; // Colgram: HARD CAP - native tgnet jniEnv[] is 5 (see apply-patches.py note 49)",
        "UserConfig Raise Account Count"
    )

    # 50. MessagesController.java -> Unlimited Pinned Dialogs
    #
    # The pin cap arrives from server config (pinned_dialogs_count_max) but that block is
    # commented out upstream, so the fields keep whatever they were initialised with.
    # Folder-scoped pins already default to 100; the main-dialog fields default to 5.
    #
    # IMPORTANT: the two assignment lines appear TWICE each in the initialiser (upstream
    # has a duplicated pair at 1646/1647 and 1648/1649). patch_file's replace(..., 1)
    # would only rewrite the first of each pair, leaving the second to overwrite it at
    # runtime — so the cap would silently survive. Both pairs are rewritten by replacing
    # the whole 4-line block in one shot.
    #
    # This only lifts the CLIENT-side guard. Telegram's server still enforces its own
    # pinned-dialog limit, which a client cannot override.
    patch_file(
        messages_controller,
        '''maxPinnedDialogsCountDefault = mainPreferences.getInt("maxPinnedDialogsCountDefault", 5);
        maxPinnedDialogsCountPremium = mainPreferences.getInt("maxPinnedDialogsCountPremium", 5);
        maxPinnedDialogsCountDefault = mainPreferences.getInt("maxPinnedDialogsCountDefault", 5);
        maxPinnedDialogsCountPremium = mainPreferences.getInt("maxPinnedDialogsCountPremium", 5);''',
        '''maxPinnedDialogsCountDefault = mainPreferences.getInt("maxPinnedDialogsCountDefault", 1000); // Colgram: unlimited pins
        maxPinnedDialogsCountPremium = mainPreferences.getInt("maxPinnedDialogsCountPremium", 1000); // Colgram: unlimited pins
        maxPinnedDialogsCountDefault = mainPreferences.getInt("maxPinnedDialogsCountDefault", 1000); // Colgram: unlimited pins
        maxPinnedDialogsCountPremium = mainPreferences.getInt("maxPinnedDialogsCountPremium", 1000); // Colgram: unlimited pins''',
        "MessagesController Unlimited Pins"
    )
    patch_file(
        messages_controller,
        '''maxFolderPinnedDialogsCountDefault = mainPreferences.getInt("maxFolderPinnedDialogsCountDefault", 100);
        maxFolderPinnedDialogsCountPremium = mainPreferences.getInt("maxFolderPinnedDialogsCountPremium", 100);''',
        '''maxFolderPinnedDialogsCountDefault = mainPreferences.getInt("maxFolderPinnedDialogsCountDefault", 1000); // Colgram: unlimited pins
        maxFolderPinnedDialogsCountPremium = mainPreferences.getInt("maxFolderPinnedDialogsCountPremium", 1000); // Colgram: unlimited pins''',
        "MessagesController Unlimited Folder Pins"
    )

    # 51. DialogsActivity.java -> Remove the Pin Guard Entirely
    #
    # Belt and braces: the guard computes maxPinnedCount from the (now large) config
    # values, but for a folder-scoped filter it computes 100 - alwaysShow.size(), which can
    # still block. Replacing the condition with an unconditional allow removes the last
    # client-side stop. Unpinning is never affected, and the server remains the authority.
    if os.path.exists(dialogs_activity):
        patch_file(
            dialogs_activity,
            "hasPinAction[0] = !(newPinnedSecretCount + pinnedSecretCount > maxPinnedCount || newPinnedCount + pinnedCount - alreadyAdded > maxPinnedCount);",
            "hasPinAction[0] = true; // Colgram: no client-side pin cap",
            "DialogsActivity Remove Pin Cap"
        )

    # 52. NotificationsController.java -> Per-User Notification Blocking
    #
    # Enforced at the single place that decides whether an incoming message becomes a
    # notification. Placing the filter here (rather than in the notification builder or the
    # UI) means a silenced sender produces no notification, no sound and no badge from ANY
    # code path — push, in-app update, or edited message.
    #
    # Scope is intentionally notification-only: the message is still stored and rendered,
    # and replying works normally. The user asked for exactly that ("не буду это видеть...
    # без проблем отвечать").
    #
    # The anchor is the first guard in the per-message loop, which already exists to skip
    # message kinds that should not notify, so this composes with Telegram's own logic
    # instead of bypassing it.
    notifications_controller = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "NotificationsController.java")
    if os.path.exists(notifications_controller):
        patch_file(
            notifications_controller,
            """                if (messageObject.messageOwner != null && (messageObject.isImportedForward() ||
                        messageObject.messageOwner.action instanceof TLRPC.TL_messageActionSetMessagesTTL ||
                        messageObject.messageOwner.silent && (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionContactSignUp || messageObject.messageOwner.action instanceof TLRPC.TL_messageActionUserJoined)) ||
                        MessageObject.isTopicActionMessage(messageObject)) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("skipped message because 1");
                    }
                    continue;
                }""",
            """                // Colgram: keep silent for senders the user has muted, even when they ping
                // in a group or channel. Notification only - the message is still stored
                // and shown when the chat is opened.
                if (messageObject.messageOwner != null
                        && org.colgram.core.ColgramHookHandler.shouldSilenceNotificationsFrom(messageObject.getFromChatId())) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("colgram: notification suppressed for sender " + messageObject.getFromChatId());
                    }
                    continue;
                }
                if (messageObject.messageOwner != null && (messageObject.isImportedForward() ||
                        messageObject.messageOwner.action instanceof TLRPC.TL_messageActionSetMessagesTTL ||
                        messageObject.messageOwner.silent && (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionContactSignUp || messageObject.messageOwner.action instanceof TLRPC.TL_messageActionUserJoined)) ||
                        MessageObject.isTopicActionMessage(messageObject)) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("skipped message because 1");
                    }
                    continue;
                }""",
            "NotificationsController Per-User Silent Senders"
        )

    # 53. ProfileActivity.java -> Per-User "Mute group pings" row
    #
    # Adds a Colgram-only row to another user's profile that silences their group/channel
    # pings on this device. Three edits are needed and all three are required for the row
    # to work: the field declaration, the row index assignment, and the bind + click
    # handler.
    #
    # Kept as a NEW field rather than reusing an existing row index: reusing one would mean
    # the row disappears whenever that other feature is conditionally hidden.
    profile_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ProfileActivity.java")
    if os.path.exists(profile_activity):
        # (a) field
        patch_file(
            profile_activity,
            "    private int unblockRow;",
            "    private int unblockRow;\n    /** Colgram: silences this user's pings in groups/channels. */\n    private int colgramMutePingsRow = -1;",
            "ProfileActivity Colgram Mute Row Field"
        )
        # (b) reset alongside the other rows
        patch_file(
            profile_activity,
            "        unblockRow = -1;",
            "        unblockRow = -1;\n        colgramMutePingsRow = -1;",
            "ProfileActivity Colgram Mute Row Reset"
        )
        # (c) assign the index in the other-user branch
        patch_file(
            profile_activity,
            """                if (user != null && !isBot && currentEncryptedChat == null && user.id != getUserConfig().getClientUserId()) {
                    if (userBlocked) {
                        unblockRow = rowCount++;
                        lastSectionRow = rowCount++;
                    }
                }""",
            """                if (user != null && !isBot && currentEncryptedChat == null && user.id != getUserConfig().getClientUserId()) {
                    if (userBlocked) {
                        unblockRow = rowCount++;
                        lastSectionRow = rowCount++;
                    }
                    // Colgram: always offer the mute-pings toggle for a real user, blocked
                    // or not — muting their pings is independent of blocking them.
                    colgramMutePingsRow = rowCount++;
                }""",
            "ProfileActivity Colgram Mute Row Index"
        )
        # (d) bind the cell text.
        #
        # There is exactly ONE `case VIEW_TYPE_TEXT:` handler in ProfileActivity (verified),
        # and the other-user branch reuses it — that is why unblockRow / sendMessageRow /
        # addToContactsRow all bind there even though they are only assigned for other users.
        # So inserting near notificationRow/privacyRow works for the other-user case too.
        #
        # The anchor is the notificationRow/privacyRow *pair*, not a bare `position == unblockRow`:
        # the latter appears twice (rows builder + bind) and patch_file replaces only the
        # first occurrence. The pair is unique in the file.
        patch_file(
            profile_activity,
            """                    } else if (position == notificationRow) {
                        textCell.setTextAndIcon(LocaleController.getString(R.string.NotificationsAndSounds), R.drawable.msg2_notifications, true);
                    } else if (position == privacyRow) {
                        textCell.setTextAndIcon(LocaleController.getString(R.string.PrivacySettings), R.drawable.msg2_secret, true);""",
            """                    } else if (position == colgramMutePingsRow) {
                        boolean muted = org.colgram.core.ColgramHookHandler.isNotificationsBlocked(userId);
                        textCell.setTextAndValue("Молчать о пингах в группах",
                                muted ? "включено" : "выключено", true);
                    } else if (position == notificationRow) {
                        textCell.setTextAndIcon(LocaleController.getString(R.string.NotificationsAndSounds), R.drawable.msg2_notifications, true);
                    } else if (position == privacyRow) {
                        textCell.setTextAndIcon(LocaleController.getString(R.string.PrivacySettings), R.drawable.msg2_secret, true);""",
            "ProfileActivity Colgram Mute Row Bind"
        )
        # (e) click handler, next to the other per-user actions.
        #
        # Anchored on the *second* half of the unblockRow handler (the bulletin block) rather
        # than `} else if (position == unblockRow) {` alone, which would also match the bind
        # site. This anchor is unique.
        patch_file(
            profile_activity,
            """            } else if (position == unblockRow) {
                getMessagesController().unblockPeer(userId);
                if (BulletinFactory.canShowBulletin(ProfileActivity.this)) {
                    BulletinFactory.createBanBulletin(ProfileActivity.this, false).show();
                }
            }""",
            """            } else if (position == colgramMutePingsRow) {
                final long colgramTarget = userId;
                boolean colgramMuted = org.colgram.core.ColgramHookHandler.isNotificationsBlocked(colgramTarget);
                org.colgram.core.ColgramHookHandler.setNotificationsBlocked(colgramTarget, !colgramMuted);
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
                Toast.makeText(getParentActivity(),
                        !colgramMuted ? "Уведомления от этого человека заглушены"
                                      : "Уведомления от этого человека снова включены",
                        Toast.LENGTH_SHORT).show();
            } else if (position == unblockRow) {
                getMessagesController().unblockPeer(userId);
                if (BulletinFactory.canShowBulletin(ProfileActivity.this)) {
                    BulletinFactory.createBanBulletin(ProfileActivity.this, false).show();
                }
            }""",
            "ProfileActivity Colgram Mute Row Click"
        )

    # 20. MessagesStorage.java -> Anti-Delete (Preserve Deleted Messages In Local DB)
    messages_storage = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesStorage.java")
    if os.path.exists(messages_storage):
        def anti_delete_injector(content):
            target = "public ArrayList<Long> markMessagesAsDeleted(long dialogId, ArrayList<Integer> messages, boolean useQueue, boolean deleteFiles, int mode, int topicId) {"
            if target not in content:
                return content
            inject = """public ArrayList<Long> markMessagesAsDeleted(long dialogId, ArrayList<Integer> messages, boolean useQueue, boolean deleteFiles, int mode, int topicId) {
        // Anti-delete: pull the retained ids back out of the list before storage touches
        // them. An id that reaches this method's body is gone from SQLite for good, so
        // filtering here — not at the call site — is the only thing that actually keeps
        // the message alive across a reload.
        //
        // Two modes, and the difference matters:
        //   isAntiDeleteEnabled()        — interception is on at all
        //   isAntiDeleteWipeEnabled()    — ON  => row survives but its content is blanked
        //                                        (AyuGram-style tombstone: the message stays
        //                                        in place, unreadable, permanently)
        //                                  OFF => row is left completely intact, full text
        //                                        kept forever. Use this for a local archive.
        //
        // We honour the wipe flag by NOT filtering when the user wants a full archive, and
        // by filtering (thereby preserving the row) when they want a tombstone.
        if (org.colgram.core.ColgramConfig.isAntiDeleteEnabled() && messages != null) {
            java.util.ArrayList<Integer> toRemove = new java.util.ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                int mid = messages.get(i);
                if (org.colgram.core.ColgramHookHandler.hookShouldPreventDelete(dialogId, mid)) {
                    toRemove.add(mid);
                }
            }
            messages.removeAll(toRemove);
            if (messages.isEmpty()) {
                return new java.util.ArrayList<>();
            }
        }"""
            return content.replace(target, inject, 1)
        patch_file(messages_storage, anti_delete_injector, "messages.removeAll(toRemove);", "MessagesStorage Anti-Delete Preservation")

    # 21. MessagesController.java -> Save Message Edit History (text + media)
    #
    # 🔴 SELF-GUARDED. Do NOT rely on patch_file's generic guard here: that guard keys
    # on `replacement.strip() in content`, and this replacement is a 100-line block
    # assembled inside the callable, so `.strip()` can never match. Worse, the removal
    # of the previous text-only variant means a naive re-run APPENDS a second copy.
    # Measured: two runs produced hookOnMediaReplaced x2 / hookOnMessageEdited x3.
    # The guard below is therefore explicit and keyed on the media marker.
    if os.path.exists(messages_controller):
        def edit_history_injector(content):
            target = "} else if (baseUpdate instanceof TL_update.TL_updateEditChannelMessage || baseUpdate instanceof TL_update.TL_updateEditMessage) {"
            if target not in content:
                return content

            # Already upgraded to the media-capturing revision? Nothing to do.
            if "org.colgram.core.ColgramHookHandler.hookOnMediaReplaced(" in content:
                return content

            # Remove the older text-only variant so the two never stack.
            old_text_only = """
                try {
                    TLRPC.Message colgramEditMsg = (baseUpdate instanceof TL_update.TL_updateEditChannelMessage) ? ((TL_update.TL_updateEditChannelMessage) baseUpdate).message : ((TL_update.TL_updateEditMessage) baseUpdate).message;
                    if (colgramEditMsg != null && org.colgram.core.ColgramConfig.isEditHistoryEnabled()) {
                        long did = colgramEditMsg.dialog_id != 0 ? colgramEditMsg.dialog_id : (colgramEditMsg.peer_id != null ? org.telegram.messenger.MessageObject.getPeerId(colgramEditMsg.peer_id) : 0);
                        // Read the PREVIOUS revision from local storage BEFORE the update overwrites it.
                        // The incoming update carries only the NEW text, so the old text must come from the DB.
                        String colgramPrevText = null;
                        try {
                            TLRPC.Message colgramStored = getMessagesStorage().getMessage(did, colgramEditMsg.id);
                            if (colgramStored != null && colgramStored.message != null && !colgramStored.message.equals(colgramEditMsg.message)) {
                                colgramPrevText = colgramStored.message;
                            }
                        } catch (Throwable ignoreInner) {}
                        org.colgram.core.ColgramHookHandler.hookOnMessageEdited(did, colgramEditMsg.id, colgramPrevText, colgramEditMsg.date);
                    }
                } catch (Throwable ignore) {}"""
            removed_old = old_text_only in content
            if removed_old:
                content = content.replace(old_text_only, "", 1)

            inject = """
                try {
                    TLRPC.Message colgramEditMsg = (baseUpdate instanceof TL_update.TL_updateEditChannelMessage) ? ((TL_update.TL_updateEditChannelMessage) baseUpdate).message : ((TL_update.TL_updateEditMessage) baseUpdate).message;
                    if (colgramEditMsg != null && org.colgram.core.ColgramConfig.isEditHistoryEnabled()) {
                        long did = colgramEditMsg.dialog_id != 0 ? colgramEditMsg.dialog_id : (colgramEditMsg.peer_id != null ? org.telegram.messenger.MessageObject.getPeerId(colgramEditMsg.peer_id) : 0);

                        // One storage read serves BOTH the text and the media capture.
                        TLRPC.Message colgramStored = null;
                        try {
                            colgramStored = getMessagesStorage().getMessage(did, colgramEditMsg.id);
                        } catch (Throwable ignoreInner) {}

                        // ---- 1. text revision -------------------------------------------------
                        // Read the PREVIOUS revision from local storage BEFORE the update
                        // overwrites it. The update carries only the NEW text.
                        //
                        // \u26a0\ufe0f Record a text revision ONLY when storage returned a row.
                        // MessagesStorage.getMessage() queries messages_v2 and returns null
                        // for a message that was never persisted (or has been evicted).
                        // Recording anyway would bank an empty revision that renders as a
                        // blank "previous version" in the history sheet. No previous value,
                        // no revision.
                        String colgramPrevText = null;
                        if (colgramStored != null && colgramStored.message != null
                                && !colgramStored.message.equals(colgramEditMsg.message)) {
                            colgramPrevText = colgramStored.message;
                            org.colgram.core.ColgramHookHandler.hookOnMessageEdited(did, colgramEditMsg.id, colgramPrevText, colgramEditMsg.date);
                        }

                        // ---- 2. media revision ------------------------------------------------
                        // Telegram has no attachment history: when an edit swaps the photo or
                        // video, the old document reference is simply gone. Detect that the
                        // stored message HAD media and the incoming one either has none or a
                        // DIFFERENT one, then snapshot it before it is lost.
                        //
                        // API notes (all verified against this tree — do not "tidy" them):
                        //   * FileLoader.getPathToMessage is an INSTANCE method; reach it via
                        //     getFileLoader(). There is no FileLoader.getMediaId.
                        //   * The media identity is Document.id / Photo.id directly. Both
                        //     classes expose `public long id`, so read it off the TL object.
                        try {
                            boolean colgramHadMedia = colgramStored != null && colgramStored.media != null;
                            if (colgramHadMedia) {
                                // Identity: document id, else the largest photo size's id.
                                long colgramOldId = 0;
                                long colgramNewId = 0;
                                if (colgramStored.media.document != null) {
                                    colgramOldId = colgramStored.media.document.id;
                                } else if (colgramStored.media.photo != null) {
                                    colgramOldId = colgramStored.media.photo.id;
                                }
                                if (colgramEditMsg.media != null) {
                                    if (colgramEditMsg.media.document != null) {
                                        colgramNewId = colgramEditMsg.media.document.id;
                                    } else if (colgramEditMsg.media.photo != null) {
                                        colgramNewId = colgramEditMsg.media.photo.id;
                                    }
                                }
                                if (colgramOldId == 0 || colgramOldId != colgramNewId) {
                                    String colgramPath = null;
                                    String colgramName = null;
                                    long colgramSize = 0;
                                    String colgramMime = null;
                                    int colgramType = 3;
                                    try {
                                        // getPathToMessage() honours the message's stored attachPath,
                                        // but the PUBLIC MessagesStorage.getMessage() does NOT run
                                        // readAttachPath on the deserialized row (the private
                                        // getMessageInternal() does). So attachPath is usually null
                                        // here and this first attempt often resolves to nothing.
                                        java.io.File colgramFile = getFileLoader().getPathToMessage(colgramStored);
                                        if (colgramFile != null && colgramFile.exists() && colgramFile.length() > 0) {
                                            colgramPath = colgramFile.getAbsolutePath();
                                            colgramSize = colgramFile.length();
                                        } else {
                                            // Fallback: derive the canonical cache path straight from
                                            // the media object. getPathToAttach() computes the path
                                            // from the document/photo id, so it still resolves after
                                            // an attachPath hint has been lost.
                                            TLObject colgramAttach = null;
                                            if (colgramStored.media.document != null) {
                                                colgramAttach = colgramStored.media.document;
                                            } else if (colgramStored.media.photo != null) {
                                                colgramAttach = colgramStored.media.photo;
                                            }
                                            if (colgramAttach != null) {
                                                java.io.File colgramAttachFile = getFileLoader().getPathToAttach(colgramAttach, true);
                                                if (colgramAttachFile != null && colgramAttachFile.exists() && colgramAttachFile.length() > 0) {
                                                    colgramPath = colgramAttachFile.getAbsolutePath();
                                                }
                                            }
                                        }
                                    } catch (Throwable ignorePath) {}
                                    if (colgramStored.media instanceof TLRPC.TL_messageMediaPhoto) {
                                        colgramType = 1;
                                        colgramMime = "image/*";
                                        colgramName = "photo_" + colgramOldId + ".jpg";
                                    } else if (colgramStored.media instanceof TLRPC.TL_messageMediaDocument) {
                                        TLRPC.Document colgramDoc = colgramStored.media.document;
                                        if (colgramDoc != null) {
                                            colgramMime = colgramDoc.mime_type;
                                            colgramName = org.telegram.messenger.FileLoader.getDocumentFileName(colgramDoc);
                                            if (colgramDoc.size > 0) colgramSize = colgramDoc.size;
                                            if (org.telegram.messenger.MessageObject.isVideoDocument(colgramDoc)) {
                                                colgramType = 2;
                                            }
                                        }
                                        if (colgramName == null || colgramName.trim().isEmpty()) {
                                            colgramName = "document_" + colgramOldId;
                                        }
                                    } else if (colgramStored.media instanceof TLRPC.TL_messageMediaGame) {
                                        colgramType = 3;
                                        colgramName = colgramStored.media.game != null
                                                ? colgramStored.media.game.title : "game";
                                    } else {
                                        colgramType = 3;
                                        colgramName = "attachment_" + colgramOldId;
                                    }
                                    // Copy into the sandbox so the revision outlives Telegram's
                                    // own cache eviction. A null result is acceptable: the row is
                                    // still recorded, just without an openable copy.
                                    String colgramSandboxPath = null;
                                    if (colgramPath != null) {
                                        colgramSandboxPath = org.colgram.core.ColgramHookHandler
                                                .colgramCopyRevisionFile(colgramPath, colgramName);
                                        if (colgramSandboxPath == null) {
                                            // Fall back to the live cache path: usable now, may
                                            // vanish later. Better than nothing.
                                            colgramSandboxPath = colgramPath;
                                        }
                                    }
                                    org.colgram.core.ColgramHookHandler.hookOnMediaReplaced(
                                            did, colgramEditMsg.id, colgramType,
                                            colgramSandboxPath, colgramName, colgramSize,
                                            colgramMime, colgramOldId, colgramEditMsg.date);
                                }
                            }
                        } catch (Throwable ignoreMedia) {}
                    }
                } catch (Throwable ignore) {}"""
            return content.replace(target, target + inject, 1)
        patch_file(messages_controller, edit_history_injector, "colgramCopyRevisionFile", "MessagesController Save Edit History")


    # 24. Theme.java -> Inject Colgram Cyber Red Colors
    theme_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ActionBar", "Theme.java")
    if os.path.exists(theme_file):
        COLGRAM_GETCOLOR_WRAPPER = (
        "    // Bump this whenever the wrapper or the cyber palette changes shape, and change\n"
        "    // the patch_file marker below to match. Without it the marker stays satisfied by\n"
        "    // an OLDER wrapper, patch_file reports 'already patched', the injector never\n"
        "    // re-runs, and edits to the palette silently never reach a build. That exact trap\n"
        "    // swallowed two separate theme fixes in this file before it was pinned down.\n"
        "    private static final int COLGRAM_THEME_PATCH = 3;\n"
        "\n"
        "public static int getColor(int key, boolean[] isDefault, boolean ignoreAnimation) {\n"
        "        boolean[] colgramIsDefault = new boolean[1];\n"
        "        int colgramResolved = colgramGetColorInternal(key, colgramIsDefault, ignoreAnimation);\n"
        "        if (isDefault != null) {\n"
        "            isDefault[0] = colgramIsDefault[0];\n"
        "        }\n"
        "        final boolean colgramCyber = org.colgram.core.ColgramConfig.isCyberThemeEnabled();\n"
        # ---------------------------------------------------------------------------
        # The cyber palette, applied as a COMPLETE set.
        #
        # It used to override only the backgrounds (windowBackgroundWhite, actionBarDefault,
        # the input field) and none of the text keys. So on a light base theme the app drew
        # the cyber dark background and then resolved black text from the light palette on
        # top of it - black on near-black, i.e. "chats, profile, settings are invisible".
        #
        # A palette is only coherent if foreground and background are decided together, so
        # every text/icon key is overridden here too. Anything not listed falls through to
        # the contrast guard below, which now knows the cyber background is dark.
        # ---------------------------------------------------------------------------
        "        if (colgramCyber) {\n"
        "            int colgramCyberColor = colgramCyberOverride(key);\n"
        "            if (colgramCyberColor != 0) {\n"
        "                return colgramCyberColor;\n"
        "            }\n"
        "        }\n"
        "        final boolean colgramDarkSurface = colgramCyber || isCurrentThemeDark();\n"
        # ---------------------------------------------------------------------------
        # What a "palette hole" actually is.
        #
        # This used to test `colgramResolved == 0`, and that is why the dark theme kept
        # merging into itself: upstream NEVER returns 0 for an unknown key. It falls
        # through to getDefaultColor(), which answers from the LIGHT palette. So every
        # key a dark theme omits resolved to a light value — dark grey text on dark grey
        # background — and the whole hole-filling block below was unreachable in practice.
        #
        # The honest signal is upstream's own out-parameter: it sets isDefault[0] = true
        # on exactly that fall-through path. Testing it replaces a guess with the theme
        # system telling us the truth.
        #
        # Night-palette filling is now gated on the surface actually being dark. Filling
        # unconditionally would hand a LIGHT theme dark values for its own missing keys,
        # which is the same bug mirrored.
        # ---------------------------------------------------------------------------
        "        final boolean colgramHole = colgramIsDefault[0] || colgramResolved == 0;\n"
        "        if (colgramHole && colgramDarkSurface && colgramNightHasKey(key)) {\n"
        "            return colgramNightColor(key);\n"
        "        }\n"
        "        if (colgramHole && colgramIsReadableKey(key)) {\n"
        "            return colgramDarkSurface ? 0xffffffff : 0xff000000;\n"
        "        }\n"
        "        return colgramGuard(key, colgramResolved);\n"
        "    }\n"
        "\n"
        "    /**\n"
        "     * The single readability guard, shared by BOTH colour paths.\n"
        "     *\n"
        "     * getColor(key, ResourcesProvider) returns provider.getColor(key) directly and\n"
        "     * never reaches the wrapper, so a provider-scoped screen (a themed chat, a sheet,\n"
        "     * a settings section header) previously got no repair at all. Keeping one method\n"
        "     * is what stops the two paths disagreeing about what readable means.\n"
        "     */\n"
        "    private static int colgramGuard(int key, int color) {\n"
        "        boolean colgramCyber = org.colgram.core.ColgramConfig.isCyberThemeEnabled();\n"
        "        if ((colgramCyber || isCurrentThemeDark()) && colgramLooksLikeForeground(key)) {\n"
        "            if (colgramBestContrast(key, color, colgramCyber) < COLGRAM_MIN_CONTRAST) {\n"
        "                return colgramReadableForeground(colgramCyber);\n"
        "            }\n"
        "        }\n"
        "        return color;\n"
        "    }\n"
        "\n"
        "    /**\n"
        "     * Is this key a foreground (text / icon / stroke) rather than a fill?\n"
        "     *\n"
        "     * Colour keys carry no name at runtime, so this cannot be answered by pattern\n"
        "     * matching, and the explicit allowlist it replaces covered about 25 of ~800 keys -\n"
        "     * which is why most of the UI stayed grey-on-grey even after the guard existed.\n"
        "     *\n"
        "     * The light palette is the oracle: it was authored for a white background, so its\n"
        "     * fills are light and its text and icons are dark. A key whose light default is dark\n"
        "     * was therefore a foreground, and it is still a foreground under night — where\n"
        "     * keeping that dark value on a dark surface is exactly the reported bug.\n"
        "     *\n"
        "     * Transparent values are excluded: they are not a paint at all, and treating them as\n"
        "     * foreground would invent a colour for deliberately invisible layers.\n"
        "     */\n"
        "    private static boolean colgramLooksLikeForeground(int key) {\n"
        "        if (colgramIsForegroundKey(key)) {\n"
        "            return true;\n"
        "        }\n"
        "        int light = getDefaultColor(key);\n"
        "        if ((light >>> 24) < 0x20) {\n"
        "            return false;\n"
        "        }\n"
        "        return colgramLuma(light) < 128;\n"
        "    }\n"
        "\n"
        "    /**\n"
        "     * Best contrast a foreground colour gets against any surface it could plausibly\n"
        "     * be drawn on.\n"
        "     *\n"
        "     * The guard used to measure everything against windowBackgroundWhite alone. That\n"
        "     * is wrong twice over: a colour inside a chat, a dialog sheet or an action bar sits\n"
        "     * on its own background, so a readable colour could be 'repaired' against the wrong\n"
        "     * surface, and an unreadable one could slip through because it happened to contrast\n"
        "     * well with the one surface we happened to check.\n"
        "     *\n"
        "     * Colour keys carry no name at runtime (they are sequential colorsCount++ ints and\n"
        "     * Theme exposes no key->String), so we cannot know which family a key belongs to.\n"
        "     * Taking the MAXIMUM over the candidate surfaces is the conservative answer: we only\n"
        "     * intervene when the colour is unreadable everywhere, which can never break a place\n"
        "     * where it already works.\n"
        "     */\n"
        "    private static int colgramBestContrast(int key, int color, boolean cyber) {\n"
        "        if (cyber) {\n"
        "            return colgramContrast(color, COLGRAM_CYBER_BG);\n"
        "        }\n"
        "        int best = colgramContrast(color, colgramGetColorInternal(key_windowBackgroundWhite, null, true));\n"
        "        best = Math.max(best, colgramContrast(color, colgramGetColorInternal(key_dialogBackground, null, true)));\n"
        "        best = Math.max(best, colgramContrast(color, colgramGetColorInternal(key_actionBarDefault, null, true)));\n"
        "        return best;\n"
        "    }\n"
        "\n"
        "    /** A foreground colour that is readable on every dark surface we test against. */\n"
        "    private static int colgramReadableForeground(boolean cyber) {\n"
        "        if (cyber) {\n"
        "            return COLGRAM_CYBER_TEXT;\n"
        "        }\n"
        "        int candidate = colgramGetColorInternal(key_windowBackgroundWhiteBlackText, null, true);\n"
        "        if (colgramBestContrast(0, candidate, false) >= COLGRAM_MIN_CONTRAST) {\n"
        "            return candidate;\n"
        "        }\n"
        "        return 0xffffffff;\n"
        "    }\n"
        "\n"
        "    // Cyber palette. One place, so the whole scheme can be re-tuned without hunting\n"
        "    // for stray literals. Backgrounds and foregrounds are chosen TOGETHER - a palette\n"
        "    // that overrides only the background is how the UI ended up invisible.\n"
        "    private static final int COLGRAM_CYBER_BG        = 0xff0e0f12;\n"
        "    private static final int COLGRAM_CYBER_SURFACE   = 0xff16181e;\n"
        "    private static final int COLGRAM_CYBER_FIELD     = 0xff22252d;\n"
        "    private static final int COLGRAM_CYBER_ACCENT    = 0xffff3344;\n"
        "    private static final int COLGRAM_CYBER_TEXT      = 0xffe8eaed;\n"
        "    private static final int COLGRAM_CYBER_TEXT_DIM  = 0xff9aa0a6;\n"
        "    private static final int COLGRAM_CYBER_HINT      = 0xff6b7280;\n"
        "    private static final int COLGRAM_CYBER_DIVIDER   = 0xff2a2e37;\n"
        "    /**\n"
        "     * The cyber palette, applied as a COMPLETE set: foreground and background are\n"
        "     * decided together.\n"
        "     *\n"
        "     * Returns 0 when the key is not part of the scheme, which is the sentinel the\n"
        "     * callers test against - 0 is never a valid colour here.\n"
        "     *\n"
        "     * Why one method and not two inlined copies: the scheme must be applied on BOTH\n"
        "     * colour paths. getColor(key) reaches the wrapper, but\n"
        "     * getColor(key, ResourcesProvider) returns provider.getColor(key) directly when a\n"
        "     * provider is present, so a provider-scoped screen (a themed chat, a sheet) would\n"
        "     * otherwise get the cyber background and NOT the cyber text - black on near-black.\n"
        "     * Two copies of a palette drift; one cannot.\n"
        "     */\n"
        "    private static int colgramCyberOverride(int key) {\n"
        "        // Backgrounds\n"
        "        if (key == key_windowBackgroundWhite || key == key_windowBackgroundGray\n"
        "                || key == key_windowBackgroundGrayShadow) {\n"
        "            return COLGRAM_CYBER_BG;\n"
        "        }\n"
        "        if (key == key_actionBarDefault || key == key_actionBarDefaultSelector\n"
        "                || key == key_actionBarWhiteSelector) {\n"
        "            return COLGRAM_CYBER_SURFACE;\n"
        "        }\n"
        "        if (key == key_windowBackgroundWhiteInputField) {\n"
        "            return COLGRAM_CYBER_FIELD;\n"
        "        }\n"
        "        // Accents\n"
        "        if (key == key_windowBackgroundWhiteInputFieldActivated\n"
        "                || key == key_chats_actionBackground\n"
        "                || key == key_chats_actionPressedBackground\n"
        "                || key == key_dialogFloatingButton\n"
        "                || key == key_switchTrackChecked\n"
        "                || key == key_checkboxCheck) {\n"
        "            return COLGRAM_CYBER_ACCENT;\n"
        "        }\n"
        "        // The blue accent family. These were missing entirely, which is why turning\n"
        "        // Cyber on left every section header, link and blue icon stock blue next to a\n"
        "        // red background - the reported 'cyber is broken and ugly'. HeaderCell paints\n"
        "        // its label from windowBackgroundWhiteBlueHeader, so without this the palette\n"
        "        // covered backgrounds and body text but not the one thing that names a section.\n"
        "        if (key == key_windowBackgroundWhiteBlueHeader\n"
        "                || key == key_windowBackgroundWhiteBlueText\n"
        "                || key == key_windowBackgroundWhiteBlueText4\n"
        "                || key == key_windowBackgroundWhiteBlueIcon\n"
        "                || key == key_windowBackgroundWhiteValueText\n"
        "                || key == key_dialogTextLink\n"
        "                || key == key_featuredStickers_addButton) {\n"
        "            return COLGRAM_CYBER_ACCENT;\n"
        "        }\n"
        "        // Primary text and icons - the keys whose absence made the UI invisible.\n"
        "        if (key == key_windowBackgroundWhiteBlackText\n"
        "                || key == key_actionBarDefaultTitle\n"
        "                || key == key_actionBarDefaultIcon) {\n"
        "            return COLGRAM_CYBER_TEXT;\n"
        "        }\n"
        "        // Secondary text\n"
        "        if (key == key_windowBackgroundWhiteGrayText\n"
        "                || key == key_windowBackgroundWhiteGrayText2\n"
        "                || key == key_windowBackgroundWhiteGrayText3\n"
        "                || key == key_windowBackgroundWhiteGrayText4\n"
        "                || key == key_windowBackgroundWhiteGrayText5\n"
        "                || key == key_windowBackgroundWhiteGrayText6\n"
        "                || key == key_windowBackgroundWhiteGrayText7\n"
        "                || key == key_windowBackgroundWhiteGrayText8\n"
        "                || key == key_actionBarDefaultSubtitle) {\n"
        "            return COLGRAM_CYBER_TEXT_DIM;\n"
        "        }\n"
        "        if (key == key_windowBackgroundWhiteHintText) {\n"
        "            return COLGRAM_CYBER_HINT;\n"
        "        }\n"
        "        // Separators and inactive controls: invisible on the dark panel otherwise.\n"
        "        if (key == key_divider || key == key_graySection\n"
        "                || key == key_switchTrack || key == key_radioBackground) {\n"
        "            return COLGRAM_CYBER_DIVIDER;\n"
        "        }\n"
        "        return 0;\n"
        "    }\n"
        "\n"
        "    private static boolean colgramIsReadableKey(int key) {\n"
        "        return key == key_windowBackgroundWhiteBlackText\n"
        "                || key == key_windowBackgroundWhiteGrayText\n"
        "                || key == key_windowBackgroundWhiteGrayText2\n"
        "                || key == key_windowBackgroundWhiteGrayText3\n"
        "                || key == key_windowBackgroundWhiteGrayText4\n"
        "                || key == key_windowBackgroundWhiteGrayText5\n"
        "                || key == key_windowBackgroundWhiteGrayText6\n"
        "                || key == key_windowBackgroundWhiteGrayText7\n"
        "                || key == key_windowBackgroundWhiteGrayText8\n"
        "                || key == key_windowBackgroundWhiteHintText\n"
        "                || key == key_windowBackgroundWhiteValueText\n"
        "                || key == key_windowBackgroundWhiteLinkText\n"
        "                || key == key_windowBackgroundWhiteBlueText\n"
        "                || key == key_windowBackgroundWhiteBlueText2\n"
        "                || key == key_windowBackgroundWhiteBlueText3\n"
        "                || key == key_windowBackgroundWhiteBlueText4\n"
        "                || key == key_windowBackgroundWhiteBlueText5\n"
        "                || key == key_windowBackgroundWhiteBlueText6\n"
        "                || key == key_windowBackgroundWhiteBlueText7\n"
        "                || key == key_windowBackgroundWhiteGrayIcon\n"
        "                || key == key_windowBackgroundWhiteBlueIcon\n"
        "                || key == key_windowBackgroundWhiteInputField\n"
        "                || key == key_windowBackgroundWhiteInputFieldActivated;\n"
        "    }\n"
        "\n"
        # Night palette: fills colour holes from the complete dark palette in assets.
        # Without this a partial fetched theme resolves dozens of keys to 0 and the UI
        # renders as one flat dark rectangle.
        "    private static volatile int[] colgramNightColors;\n"
        "    private static volatile boolean colgramNightLoading;\n"
        "\n"
        "    private static int[] colgramGetNightPalette() {\n"
        "        if (colgramNightColors != null) {\n"
        "            return colgramNightColors;\n"
        "        }\n"
        "        if (colgramNightLoading) {\n"
        "            return null;\n"
        "        }\n"
        "        synchronized (Theme.class) {\n"
        "            if (colgramNightColors != null || colgramNightLoading) {\n"
        "                return colgramNightColors;\n"
        "            }\n"
        "            colgramNightLoading = true;\n"
        "        }\n"
        "        new Thread(() -> {\n"
        "            try {\n"
        "                android.content.Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;\n"
        "                java.io.BufferedReader br = new java.io.BufferedReader(\n"
        "                        new java.io.InputStreamReader(ctx.getAssets().open(\"night.attheme\")));\n"
        "                java.util.HashMap<Integer, Integer> map = new java.util.HashMap<>();\n"
        "                String line;\n"
        "                while ((line = br.readLine()) != null) {\n"
        "                    line = line.trim();\n"
        "                    if (line.isEmpty() || line.startsWith(\"#\")) {\n"
        "                        continue;\n"
        "                    }\n"
        "                    int eq = line.indexOf('=');\n"
        "                    if (eq <= 0) {\n"
        "                        continue;\n"
        "                    }\n"
        "                    int k2 = ThemeColors.stringKeyToInt(line.substring(0, eq));\n"
        "                    if (k2 < 0) {\n"
        "                        continue;\n"
        "                    }\n"
        "                    try {\n"
        "                        map.put(k2, Integer.parseInt(line.substring(eq + 1).trim()));\n"
        "                    } catch (Exception ignored) {}\n"
        "                }\n"
        "                br.close();\n"
        "                int max = 0;\n"
        "                for (int k3 : map.keySet()) {\n"
        "                    max = Math.max(max, k3);\n"
        "                }\n"
        "                int[] arr = new int[max + 1];\n"
        "                for (java.util.Map.Entry<Integer, Integer> e : map.entrySet()) {\n"
        "                    arr[e.getKey()] = e.getValue();\n"
        "                }\n"
        "                colgramNightColors = arr;\n"
        "                org.telegram.messenger.FileLog.d(\"ColgramTheme night palette loaded, \" + map.size() + \" keys\");\n"
        "            } catch (Throwable t) {\n"
        "                org.telegram.messenger.FileLog.e(t);\n"
        "                colgramNightColors = new int[0];\n"
        "            }\n"
        "        }, \"colgram-night-palette\").start();\n"
        "        return null;\n"
        "    }\n"
        "\n"
        "    private static boolean colgramNightHasKey(int key) {\n"
        "        int[] night = colgramGetNightPalette();\n"
        "        return night != null && key >= 0 && key < night.length && night[key] != 0;\n"
        "    }\n"
        "\n"
        "    private static int colgramNightColor(int key) {\n"
        "        return colgramNightColors[key];\n"
        "    }\n"
        "\n"
        # Contrast guard: the last line of defence against "everything merges". The two
        # resolvers above only fire on a literal 0; a partial theme can instead resolve a
        # foreground to a valid-but-invisible dark value. Surfaces take no part in this.
        "    private static final int COLGRAM_MIN_CONTRAST = 48;\n"
        "\n"
        "    private static int colgramLuma(int color) {\n"
        "        int r = (color >> 16) & 0xFF;\n"
        "        int g = (color >> 8) & 0xFF;\n"
        "        int b = color & 0xFF;\n"
        "        return (r * 299 + g * 587 + b * 114) / 1000;\n"
        "    }\n"
        "\n"
        "    private static int colgramContrast(int a, int b) {\n"
        "        return Math.abs(colgramLuma(a) - colgramLuma(b));\n"
        "    }\n"
        "\n"
        "    private static boolean colgramIsForegroundKey(int key) {\n"
        "        return key == key_windowBackgroundWhiteBlackText\n"
        "                || key == key_windowBackgroundWhiteGrayText\n"
        "                || key == key_windowBackgroundWhiteGrayText2\n"
        "                || key == key_windowBackgroundWhiteGrayText3\n"
        "                || key == key_windowBackgroundWhiteGrayText4\n"
        "                || key == key_windowBackgroundWhiteGrayText5\n"
        "                || key == key_windowBackgroundWhiteGrayText6\n"
        "                || key == key_windowBackgroundWhiteGrayText7\n"
        "                || key == key_windowBackgroundWhiteGrayText8\n"
        "                || key == key_windowBackgroundWhiteHintText\n"
        "                || key == key_windowBackgroundWhiteValueText\n"
        "                || key == key_windowBackgroundWhiteLinkText\n"
        "                || key == key_windowBackgroundWhiteBlueText\n"
        "                || key == key_windowBackgroundWhiteBlueText2\n"
        "                || key == key_windowBackgroundWhiteBlueText3\n"
        "                || key == key_windowBackgroundWhiteBlueText4\n"
        "                || key == key_windowBackgroundWhiteBlueText5\n"
        "                || key == key_windowBackgroundWhiteBlueText6\n"
        "                || key == key_windowBackgroundWhiteBlueText7\n"
        "                || key == key_windowBackgroundWhiteGrayIcon\n"
        "                || key == key_windowBackgroundWhiteBlueIcon\n"
        "                || key == key_actionBarDefaultIcon\n"
        "                || key == key_actionBarDefaultTitle\n"
        "                || key == key_actionBarDefaultSubtitle\n"
        "                || key == key_actionBarDefaultSelector;\n"
        "    }\n"
        "\n"
        "    private static int colgramGetColorInternal(int key, boolean[] isDefault, boolean ignoreAnimation) {"
)

        def theme_cyber_injector(content):
            target = "public static int getColor(int key, ResourcesProvider provider) {"
            if target not in content:
                return content

            # --- Injection 1: the ResourcesProvider overload -------------------
            # This overload returns provider.getColor(key) directly when a provider is
            # present, so it never reaches the wrapper below. It must therefore apply
            # the same palette, and delegate to the shared method so the two paths
            # cannot drift apart.
            inject = """
        if (org.colgram.core.ColgramConfig.isCyberThemeEnabled()) {
            // Delegate to the shared palette so this path and the
            // getColor(key, isDefault, ignoreAnimation) wrapper cannot disagree.
            // This overload returns provider.getColor(key) directly when a provider is
            // present, so without the delegation a provider-scoped screen received the
            // cyber background but NOT the cyber text colour - black on near-black.
            int colgramCyberColor = colgramCyberOverride(key);
            if (colgramCyberColor != 0) {
                return colgramCyberColor;
            }
        }"""

            if "colgramCyberOverride(key)" not in content:
                # Not present at all -> inject. But strip any OLDER inline block first,
                # otherwise the old if-chain stays above the new delegation and wins.
                content = re.sub(
                    r"\n        if \(org\.colgram\.core\.ColgramConfig\.isCyberThemeEnabled\(\)\) \{"
                    r".*?\n        \}\n(?=        if \(provider != null\) \{)",
                    "\n",
                    content, count=1, flags=re.S)
                content = content.replace(target, target + inject, 1)
            else:
                # Already delegating. Repair a doubled block if an earlier run stacked one.
                doubled = inject + inject
                while doubled in content:
                    content = content.replace(doubled, inject, 1)

            # --- Injection 1b: route the provider result through the shared guard ----
            #
            # Upstream returns provider.getColor(key) with no further processing, so every
            # screen that draws through a provider skipped the readability repair entirely.
            provider_raw = (
                "        if (provider != null) {\n"
                "            return provider.getColor(key);\n"
                "        }"
            )
            provider_guarded = (
                "        if (provider != null) {\n"
                "            return colgramGuard(key, provider.getColor(key));\n"
                "        }"
            )
            if provider_guarded not in content:
                content = content.replace(provider_raw, provider_guarded, 1)
            if provider_guarded not in content:
                print(" [!] ResourcesProvider colour path not guarded - "
                      "provider-scoped screens will keep grey-on-grey")

            # --- Injection 2: the boolean[] overload (the wrapper) --------------
            #
            # Two cases, and conflating them is what made this patch unreachable on a
            # fresh clone:
            #
            #   FRESH  - upstream's own method body is still in place, and
            #            colgramGetColorInternal does NOT exist yet (it is introduced BY
            #            the wrapper). A regex that spans "getColor(...) { ... }
            #            private static int colgramGetColorInternal(...) {" therefore
            #            cannot match - there is no second marker to stop at. The wrapper
            #            constant is designed for exactly this: it ENDS with the renamed
            #            signature, so a plain replace of the public signature line leaves
            #            the upstream body sitting immediately after it, now owned by
            #            colgramGetColorInternal.
            #
            #   PATCHED - both signatures are present, so the regex can span and swap the
            #            old wrapper without touching the body.
            anchor = "public static int getColor(int key, boolean[] isDefault, boolean ignoreAnimation) {"
            if anchor in content and "COLGRAM_THEME_PATCH = 3" not in content:
                # Drop any older version marker first: the wrapper is inserted at the
                # marker's own line, and the PATCHED branch below replaces from
                # `public static int getColor(` onward — so a stale constant sitting above
                # that line would survive and collide with the new one.
                content = re.sub(
                    r"[ \t]*private static final int COLGRAM_THEME_PATCH = \d+;[ \t]*\r?\n",
                    "", content)
                if "private static int colgramGetColorInternal(int key, boolean[] isDefault, boolean ignoreAnimation) {" in content:
                    # PATCHED: swap the stale wrapper out.
                    content = re.sub(
                        r"public static int getColor\(int key, boolean\[\] isDefault, boolean ignoreAnimation\) \{.*?"
                        r"private static int colgramGetColorInternal\(int key, boolean\[\] isDefault, boolean ignoreAnimation\) \{",
                        COLGRAM_GETCOLOR_WRAPPER,
                        content, count=1, flags=re.S)
                else:
                    # FRESH: rename upstream's method by prefixing the wrapper.
                    content = content.replace(anchor, COLGRAM_GETCOLOR_WRAPPER, 1)
                if "COLGRAM_THEME_PATCH = 3" not in content:
                    print(" [!] FATAL: Theme wrapper did not land - cyber palette NOT applied")
            return content
        # The marker MUST name something only the current version emits. It was
        # "COLGRAM_CYBER_BG", which the PREVIOUS wrapper already contained — so once any
        # wrapper had ever shipped, patch_file reported "already patched" forever and the
        # injector never ran again. Every edit made to the wrapper since then had simply
        # never reached a build. Same class of defect as the dead IntroActivity anchors.
        # It then became "colgramLooksLikeForeground", which did the same thing to the NEXT
        # edit — so the marker is now an explicit version constant that has to be bumped.
        patch_file(theme_file, theme_cyber_injector, "COLGRAM_THEME_PATCH = 3", "Theme Inject Colgram Cyber Red Colors")

        # 24b. The cyber overrides above ALSO get applied at the real choke point, and a
        # zero-valued readable colour is repaired there. See COLGRAM_GETCOLOR_WRAPPER.
        #
        # Why: getColor(int key) delegates to getColor(int, boolean[], boolean), NOT to
        # getColor(int, ResourcesProvider) - so ordinary UI never reached the 2-arg
        # injection at all, which is why the cyber theme looked like it did nothing.
        # And a theme that omits a text key resolves it to 0 = transparent black, which is
        # invisible on any dark background. That was the black-on-black login screen.
        # Probe evidence from the device:/n        #     name=Classic dark=true cyber=true wbw=0xff000000 wbwbt=0x0

        # 24.1. Deploy Telegram-Native UI Screens from templates
        template_dir = os.path.join(os.path.dirname(__file__), "templates")
        ui_dest_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui")
        os.makedirs(ui_dest_dir, exist_ok=True)
        for name in ["ColgramSettingsActivity.java", "ColgramPluginsActivity.java", "ColgramTempMailActivity.java", "ColgramVersionsActivity.java", "ColgramEditHistorySheet.java", "ColgramAntiSpamActivity.java", "ColgramFloatWindowManager.java"]:
            src_t = os.path.join(template_dir, name)
            dst_t = os.path.join(ui_dest_dir, name)
            if os.path.exists(src_t):
                shutil.copyfile(src_t, dst_t)
                print(f" [+] Deployed Telegram-Native {name}")
            else:
                print(f" [!] Warning: template {src_t} not found")

        # ---------------------------------------------------------------------------
        # 24.2 Colgram file sandbox: stop requesting the media library.
        #
        # Telegram asks for READ_MEDIA_IMAGES + READ_MEDIA_VIDEO + READ_MEDIA_AUDIO +
        # READ_EXTERNAL_STORAGE, i.e. every photo, video and audio file on the device. With
        # ColgramStorageSandbox enabled we do not ask at all: the user picks specific files
        # through the Storage Access Framework (which needs no permission), Colgram copies them
        # into its own private inbox, and Telegram's normal attachment path gets local paths.
        # ---------------------------------------------------------------------------
        base_fragment = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org",
                                     "telegram", "ui", "ActionBar", "BaseFragment.java")

        def basefragment_import_result(content):
            marker = "public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {"
            if "ColgramFileImport.handleActivityResult" in content:
                return content
            if marker not in content:
                print(" [!] BaseFragment.onActivityResultFragment anchor missing - "
                      "Colgram file import results will NOT be delivered")
                return content
            inject = (marker + "\n"
                      "        // Colgram: the SAF picker result. LaunchActivity routes EVERY\n"
                      "        // fragment result through here (LaunchActivity:6663), so this one hook\n"
                      "        // catches the picker regardless of which screen launched it.\n"
                      "        if (org.colgram.core.ColgramFileImport.handleActivityResult(\n"
                      "                org.telegram.messenger.ApplicationLoader.applicationContext,\n"
                      "                requestCode, resultCode, data)) {\n"
                      "            return;\n"
                      "        }")
            return content.replace(marker, inject, 1)

        patch_file(base_fragment, basefragment_import_result,
                   "ColgramFileImport.handleActivityResult",
                   "BaseFragment forward Colgram SAF picker result")

        attach_alert = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org",
                                    "telegram", "ui", "Components", "ChatAttachAlert.java")

        def attach_sandbox_routes(content):
            changed = False
            for num, mime, comment, guard in [
                (3, "audio/*",
                 "Colgram: no READ_MEDIA_AUDIO request. Pick through SAF instead.",
                 "                    if (!musicEnabled && checkCanRemoveRestrictionsByBoosts()) {\n"),
                (4, "*/*",
                 "Colgram: no READ_MEDIA_IMAGES / READ_MEDIA_VIDEO request. Pick through SAF.",
                 "                    if (!documentsEnabled && checkCanRemoveRestrictionsByBoosts()) {\n"),
            ]:
                head = "                } else if (num == %s) {\n" % num
                idx = content.find(head)
                if idx < 0:
                    print(" [!] ChatAttachAlert branch num==%s not found" % num)
                    continue
                # No body-shape check here: on a re-run the injected block legitimately sits
                # between `head` and the guard, so testing for the guard at this point failed
                # every second run and printed a false "unexpected body". The single real
                # validation happens below, after the previous copy has been stripped.
                inject = ('                    if (org.colgram.core.ColgramConfig.isSandboxStorageEnabled()) {\n'
                          '                        // ' + comment + '\n'
                          '                        org.colgram.core.ColgramFileImport.pickFiles(activity, "' + mime + '", true, paths -> {\n'
                          '                            if (paths == null || paths.isEmpty()) {\n'
                          '                                return;\n'
                          '                            }\n'
                          '                            if (documentsDelegate != null) {\n'
                          '                                documentsDelegate.didSelectFiles(paths, "", null, null, true, 0, 0, 0, false, 0);\n'
                          '                            } else if (baseFragment instanceof ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) {\n'
                          '                                ((ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) baseFragment)\n'
                          '                                        .didSelectFiles(paths, "", null, null, true, 0, 0, 0, false, 0);\n'
                          '                            }\n'
                          '                        });\n'
                          '                        return;\n'
                          '                    }\n')
                # Remove every previously injected copy before placing this one, then
                # recompute the insertion point. Without the strip, patch_file's
                # "already patched" short-circuit (and the marker it tests) meant a fix to
                # THIS block's placement never reached an existing checkout — which is
                # exactly how the misplaced-inside-the-guard version kept surviving.
                content = content.replace(inject, "")
                idx = content.find(head)
                after = idx + len(head)
                if not content[after:].startswith(guard):
                    print(" [!] ChatAttachAlert branch num==%s lost its expected body" % num)
                    continue
                # Insert at `after`, NOT at the end of the guard line. Inserting after the
                # guard put this block INSIDE `if (!documentsEnabled && ...)` — so in an
                # ordinary chat, where documentsEnabled is true, the whole branch was skipped
                # and execution fell straight through to requestPermissions(READ_MEDIA_*).
                # The SAF picker therefore only ever ran in a restricted channel, which made
                # the entire storage-sandbox feature dead code in exactly the case it exists
                # for.
                content = content[:after] + inject + content[after:]
                changed = True
            return content

        # Applied directly rather than through patch_file(), whose "already patched"
        # short-circuit tests a substring that the MISPLACED injection also satisfies — so
        # the marker could never distinguish "present" from "present in the right place",
        # and the fix below would never reach an already-patched checkout. attach_sandbox_routes
        # is idempotent on its own (it strips previous copies before re-inserting), so it can
        # simply be run every time and report what it actually changed.
        if os.path.exists(attach_alert):
            with open(attach_alert, "r", encoding="utf-8", errors="ignore") as f:
                _aa_before = f.read()
            _aa_after = attach_sandbox_routes(_aa_before)
            if _aa_after == _aa_before:
                print(" [=] Already patched: ChatAttachAlert documents + music use Colgram SAF import")
            elif _aa_after:
                with open(attach_alert, "w", encoding="utf-8", newline="") as f:
                    f.write(_aa_after)
                print(" [+] Successfully patched: ChatAttachAlert documents + music use Colgram SAF import")
            else:
                print(" [!] ChatAttachAlert SAF routing could not be applied")

        # 24b. PushListenerController -> do not initialize Firebase at all.
        #
        # Removing FirebaseInitProvider from the manifest was NOT enough, and the device log
        # proved it: this call initializes Firebase by hand on every launch. It then asks for
        # an FCM token, which makes Firebase Installations POST our package name to
        # googleapis.com over a DIRECT connection that bypasses any proxy — a per-launch
        # "Colgram is installed on this device" beacon, while the README claims Firebase was
        # stripped.
        #
        # Push cannot work in this build regardless: Google answers 403 PERMISSION_DENIED /
        # API_KEY_ANDROID_APP_BLOCKED for org.colgram.messenger, so the token request always
        # fails after it has already been sent. Skipping it removes the leak and a pointless
        # round trip without taking away anything that ever worked.
        push_listener = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org",
                                     "telegram", "messenger", "PushListenerController.java")

        PRISTINE_FB_INIT = "                    FirebaseApp.initializeApp(ApplicationLoader.applicationContext);\n"
        FIREBASE_SKIP = (
            "                    // Colgram: Firebase is deliberately not initialized here.\n"
            "                    // Firebase Installations beacons this package name to Google on every\n"
            "                    // launch over an unproxied connection, and the API key blocks us\n"
            "                    // anyway (403 API_KEY_ANDROID_APP_BLOCKED), so push never worked and\n"
            "                    // the request only leaked the install. Pair with the\n"
            "                    // FirebaseInitProvider removal in the manifest - the provider alone\n"
            "                    // does not stop it, because this line initializes it explicitly.\n"
            "                    // The `if (true)` is deliberate: a bare `return` here makes every\n"
            "                    // statement below it unreachable, which javac rejects as an error,\n"
            "                    // whereas an if-then that always completes abruptly still lets the\n"
            "                    // enclosing block complete normally (JLS 14.21).\n"
            "                    if (true) {\n"
            "                        SharedConfig.pushStringStatus = \"__FIREBASE_DISABLED__\";\n"
            "                        return;\n"
            "                    }\n"
        )

        def push_skip_firebase(content):
            # Normalise first, then apply. This block has already had three textual shapes
            # (pristine, a bare `return` that does not compile, and the current if-true form
            # with two different comment lengths). Matching them literally means a checkout
            # whose copy differs by a single comment line can never be repaired: the anchor is
            # consumed, the marker does not match, and the patch reports a miss forever.
            # So: cut any Colgram block back to the pristine line by SHAPE, then re-emit.
            content = re.sub(
                r" *// Colgram: Firebase is deliberately not initialized here\..*?"
                r"\n *\}\n(?= *FirebaseMessaging\.getInstance\(\)\.getToken\(\))",
                PRISTINE_FB_INIT, content, count=1, flags=re.S)
            if PRISTINE_FB_INIT not in content:
                return content
            return content.replace(PRISTINE_FB_INIT, FIREBASE_SKIP, 1)

        patch_file(
            push_listener,
            push_skip_firebase,
            FIREBASE_SKIP,
            "PushListenerController Do Not Initialize Firebase"
        )

        # 25. SettingsActivity.java -> Deep Integration of Colgram Settings, Plugins, TempMail, Versions
        settings_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "SettingsActivity.java")
        # The `if os.path.exists(settings_activity)` guard that used to wrap this `def` is
        # gone on purpose: a `def` is a statement, not a block that can be conditionally
        # declared at module level the way this was written, and the guard was redundant
        # anyway - patch_file() already checks the file exists and reports a miss. Keeping
        # the guard forced the whole function body one level deeper and is what produced
        # the IndentationError that stopped this script from parsing at all.
        def settings_items_injector(content):
            target = "items.add(SettingCell.Factory.of(10, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_language, getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName()));"
            if target not in content:
                return content
            inject = """items.add(SettingCell.Factory.of(10, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_language, getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName()));

        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Colgram"));
        items.add(SettingCell.Factory.of(101, 0xFFFF3344, 0xFFCC1122, R.drawable.msg_settings, "Настройки Colgram", "Анонимность, защита от удаления, обход блокировок"));
        items.add(SettingCell.Factory.of(102, 0xFF9C27B0, 0xFF673AB7, R.drawable.msg_customize, "Плагины и Маркетплейс", "Каталог расширений exteraGram, Python скрипты"));
        items.add(SettingCell.Factory.of(103, 0xFF00BCD4, 0xFF009688, R.drawable.msg_send, "Временная почта (Temp Mail)", "Быстрая анонимная регистрация без спама"));
        items.add(SettingCell.Factory.of(104, 0xFF4CAF50, 0xFF2E7D32, R.drawable.msg_download, "Версии Telegram и обновления", "Переключение каналов и загрузка APK"));
        items.add(UItem.asShadow(null));"""
            return content.replace(target, inject, 1)

        patch_file(settings_activity, settings_items_injector, "items.add(SettingCell.Factory.of(101", "SettingsActivity Inject Native Colgram Section in List")

        def settings_clicks_injector(content):
            target = """            case 10:
                presentSettingFragment(new LanguageSelectActivity());
                break;"""
            if target not in content:
                return content
            inject = """            case 10:
                presentSettingFragment(new LanguageSelectActivity());
                break;
            case 101:
                presentSettingFragment(new ColgramSettingsActivity());
                break;
            case 102:
                presentSettingFragment(new ColgramPluginsActivity());
                break;
            case 103:
                presentSettingFragment(new ColgramTempMailActivity());
                break;
            case 104:
                presentSettingFragment(new ColgramVersionsActivity());
                break;"""
            return content.replace(target, inject, 1)

        patch_file(settings_activity, settings_clicks_injector, "case 101:", "SettingsActivity Route Colgram Items Clicks")

        # 26. DialogsActivity.java & ContactsActivity.java -> Complete Permission Suppression
        if os.path.exists(dialogs_activity):
            patch_file(
                dialogs_activity,
                "if (hasNotNotificationsPermission || hasNotContactsPermission || hasNotStoragePermission)",
                "if (false && (hasNotNotificationsPermission || hasNotContactsPermission || hasNotStoragePermission))",
                "DialogsActivity Suppress Startup Permission Dialogs"
            )
            patch_file(
                dialogs_activity,
                "private void askForPermissons(boolean alert) {",
                """private void askForPermissons(boolean alert) {
        if (true) return;""",
                "DialogsActivity Suppress askForPermissons"
            )

    contacts_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ContactsActivity.java")
    if os.path.exists(contacts_activity):
        patch_file(
            contacts_activity,
            "private void askForPermissons(boolean alert) {",
            """private void askForPermissons(boolean alert) {
        if (true) return;""",
            "ContactsActivity Suppress askForPermissons"
        )

    # 27. MessagesController.java -> Fix Bot Account Loading Dialogs (Handle BOT_METHOD_INVALID)
    if os.path.exists(messages_controller):
        bot_dialogs_target = "public void loadDialogs(final int folderId, int offset, int count, boolean fromCache, Runnable onEmptyCallback) {"
        bot_dialogs_inject = """public void loadDialogs(final int folderId, int offset, int count, boolean fromCache, Runnable onEmptyCallback) {
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            loadingDialogs.put(folderId, false);
            dialogsEndReached.put(folderId, true);
            serverDialogsEndReached.put(folderId, true);
            if (fromCache) {
                getMessagesStorage().getDialogs(folderId, offset == 0 ? 0 : nextDialogsCacheOffset.get(folderId, 0), count, folderId == 0 && offset == 0);
            }
            org.colgram.core.ColgramBotSync.syncBotDialogs(ApplicationLoader.applicationContext, currentAccount);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
            return;
        }"""
        patch_file(
            messages_controller,
            bot_dialogs_target,
            bot_dialogs_inject,
            "MessagesController Bot Dialogs Cache Load"
        )

        bot_error_target = """                    if (onEmptyCallback != null && dialogsRes.dialogs.isEmpty()) {
                        AndroidUtilities.runOnUIThread(onEmptyCallback);
                    }
                }
            });"""
        bot_error_replacement = """                    if (onEmptyCallback != null && dialogsRes.dialogs.isEmpty()) {
                        AndroidUtilities.runOnUIThread(onEmptyCallback);
                    }
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        loadingDialogs.put(folderId, false);
                        dialogsEndReached.put(folderId, true);
                        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
                    });
                }
            });"""
        patch_file(
            messages_controller,
            bot_error_target,
            bot_error_replacement,
            "MessagesController Handle Dialogs Load Error"
        )

        def mc_qr_replacer(content):
            if "ColgramQRLoginBottomSheet.onLoginTokenUpdate" in content:
                return content
            # 🔴 CRLF trap — the original target was written as
            #     'FileLog.d(...);\n            }'
            # with a BARE \n. Every Telegram source here is CRLF-only, so that literal
            # could never match and this hook silently never applied (measured: 0
            # occurrences of ColgramQRLoginBottomSheet in MessagesController, while
            # ConnectionsManager had its half). Build the anchor from nl.join instead.
            nl = "\r\n" if "\r\n" in content else "\n"
            target = nl.join([
                '                FileLog.d("process update " + baseUpdate.getClass().getSimpleName());',
                '            }',
            ])
            inject = nl.join([
                '                FileLog.d("process update " + baseUpdate.getClass().getSimpleName());',
                '            }',
                '            if (baseUpdate instanceof TL_update.TL_updateLoginToken) {',
                '                org.telegram.ui.ColgramQRLoginBottomSheet.onLoginTokenUpdate(currentAccount);',
                '                continue;',
                '            }',
            ])
            if target in content:
                return content.replace(target, inject, 1)
            print(" [!] MessagesController process-update anchor not found — QR login hook NOT injected")
            return content

        patch_file(messages_controller, mc_qr_replacer, "ColgramQRLoginBottomSheet.onLoginTokenUpdate", "MessagesController QR Login Token Hook")

    # 28. ChatActivity.java -> Fallback User on Opening Bot Chat / Missing User Cache
    chat_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChatActivity.java")
    if os.path.exists(chat_activity):
        user_fallback_target = """                if (currentUser != null) {
                    getMessagesController().putUser(currentUser, true);
                } else {
                    return false;
                }"""
        user_fallback_replacement = """                if (currentUser != null) {
                    getMessagesController().putUser(currentUser, true);
                } else {
                    TLRPC.TL_user fallbackUser = new TLRPC.TL_user();
                    fallbackUser.id = userId;
                    fallbackUser.first_name = "User " + userId;
                    fallbackUser.phone = "";
                    currentUser = fallbackUser;
                    getMessagesController().putUser(currentUser, true);
                }"""
        patch_file(
            chat_activity,
            user_fallback_target,
            user_fallback_replacement,
            "ChatActivity Fallback User Object On Open"
        )

    # 29. ChatActivity.java -> Retain Deleted Messages in UI (Anti-Delete AyuGram Style)
    #
    # CRITICAL DESIGN NOTE — why we do NOT set MessageObject.deleted = true:
    #
    # MessageObject.deleted is Telegram's internal "this message no longer exists" state,
    # not a display flag. Setting it makes the client treat the bubble as a tombstone:
    #   - checkNeedDrawShareButton() bails early            (ChatMessageCell:18652)
    #   - name/subtitle/time/caption drawing is suppressed  (21227, 21987, 23605, 23936)
    #   - selection and context-menu paths skip the bubble
    #   - getPeerId()-based round trips no longer agree with the server view, so any
    #     action that needs the real message id (reply, edit, forward) fails with
    #     MESSAGE_ID_INVALID and the bubble stays highlighted because the touch/selection
    #     state is cleared by a code path we just returned out of.
    #
    # That is exactly the bug report: messages only greyed out/kept selected, reply
    # silently failing, "message id invalid" on tap, and unable to delete them.
    #
    # The correct model for anti-delete is: the message stays a FULLY NORMAL message in
    # every functional respect, and we only add a visual marker for it. The "was deleted"
    # fact lives in Colgram's own DB and is consulted at draw time. So we record the
    # deletion and let Telegram's normal removal path run.
    #
    # AyuGram does it this way too: it never mutates MessageObject.deleted for retained
    # messages, it keeps a local store and marks the bubble in the cell.
    if os.path.exists(chat_activity):
        anti_delete_ui_target = "private void processDeletedMessages(ArrayList<Integer> markAsDeletedMessages, long channelId, boolean sent, boolean thanos) {"
        anti_delete_ui_replacement = """private void processDeletedMessages(ArrayList<Integer> markAsDeletedMessages, long channelId, boolean sent, boolean thanos) {
        // ANTI-DELETE: record the ids so the cells can draw the tombstone styling, then
        // FALL THROUGH to upstream's normal handling. We must not return early: the code
        // below clears replyingMessageObject when the message being replied to is deleted
        // and rebuilds the grouping, and collapsing the view without it leaves a dangling
        // reply bar and stale group layout. The actual preservation happens earlier, in
        // MessagesController.deleteMessages(), which no longer tombstones the object or
        // wipes storage when anti-delete is on.
        if (org.colgram.core.ColgramConfig.isAntiDeleteEnabled() && markAsDeletedMessages != null) {
            for (int msg_id : markAsDeletedMessages) {
                org.colgram.core.ColgramHookHandler.hookShouldPreventDelete(dialog_id, msg_id);
            }
        }"""
        patch_file(
            chat_activity,
            anti_delete_ui_target,
            anti_delete_ui_replacement,
            "ChatActivity Anti-Delete Message Retention"
        )

    # 30. ChatMessageCell.java -> Prepend 🗑 to time string for deleted messages
    #
    # Anchored on the CURRENT upstream text. Two deliberate constraints:
    #
    #   * `currentMessageObject.deleted` is NOT part of the condition. With the corrected
    #     anti-delete implementation a retained message never enters that state, and
    #     including it would double-mark anything genuinely tombstoned by another path.
    #   * The prefix is gated on isAntiDeleteHighlightEnabled(), so a user who wants the
    #     interception to be completely invisible gets exactly that.
    #
    # TextUtils.concat on the time string is safe here because only `currentTimeString`
    # is rewritten; nothing downstream depends on it being a plain String.
    if os.path.exists(chat_cell):
        cell_time_target = """        } else {
            currentTimeString = timeString;
        }"""
        cell_time_replacement = """        } else {
            currentTimeString = timeString;
        }
        if (currentMessageObject != null
                && org.colgram.core.ColgramConfig.isAntiDeleteHighlightEnabled()
                && org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(currentMessageObject.getDialogId(), currentMessageObject.getId())) {
            currentTimeString = TextUtils.concat("🗑 ", currentTimeString);
        }"""
        patch_file(
            chat_cell,
            cell_time_target,
            cell_time_replacement,
            "ChatMessageCell Prepend Deleted Icon"
        )

    # 31. ChatActivity.java -> Edit History Context Menu Option & Action
    #
    # 🔴 IDEMPOTENCY TRAP — read before touching this patch.
    # This patch and patch 31.1 BOTH inject the edit-history arm. They are safe on a
    # fresh clone (this one runs first, then 31.1 upgrades it to the canonical merged
    # block) but NOT on a re-run: once 31.1 has merged the history arm with the
    # wallpaper arm into a single `if`, patch_file's generic guard — which keys on
    # `replacement.strip() in content` — can no longer find its own output, because
    # the trailing `icons.add(R.drawable.msg_edit);\n        }` it used to end with is
    # now followed by the wallpaper `if` instead of by the closing brace. The guard
    # misses, the anchor still matches, and a SECOND history arm is appended.
    # Measured: options.add(9988) went 1 -> 2 on the second run, which is exactly the
    # duplicate context-menu row this patch was audited to remove.
    #
    # Fix: bail out when the canonical merged block is already present. Only 31.1 may
    # create or repair that block; this patch's sole job is the fresh-clone first move.
    if os.path.exists(chat_activity):
        _ca_probe = open(chat_activity, "r", encoding="utf-8", errors="ignore").read()
        if "org.colgram.core.ColgramConfig.isChatWallpaperEnabled()" in _ca_probe:
            print(" [=] Already patched: ChatActivity Edit History Menu Option (superseded by merged block)")
        else:
            menu_edit_target = """fillMessageMenu(
        MessageObject primaryMessage,

        ArrayList<Integer> icons,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options
    ) {"""
            menu_edit_replacement = """fillMessageMenu(
        MessageObject primaryMessage,

        ArrayList<Integer> icons,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options
    ) {
        if (selectedObject != null && org.colgram.core.ColgramConfig.isEditHistoryEnabled()) {
            boolean isRuLang = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            items.add(isRuLang ? "История изменений" : "Edit History");
            options.add(9988);
            icons.add(R.drawable.msg_edit);
        }"""
            patch_file(
                chat_activity,
                menu_edit_target,
                menu_edit_replacement,
                "ChatActivity Edit History Menu Option"
            )

        process_option_target = """private void processSelectedOption(int option) {
        if (selectedObject == null || getParentActivity() == null) {
            return;
        }"""
        process_option_replacement = """private void processSelectedOption(int option) {
        if (selectedObject == null || getParentActivity() == null) {
            return;
        }
        if (option == 9988) {
            // Colgram: open the Telegram-native edit-history BottomSheet.
            // This lives in TMessagesProj (not colgram-core) because colgram-core
            // compiles before TMessagesProj and cannot see org.telegram.ui.* classes.
            org.telegram.ui.ColgramEditHistorySheet.show(getParentActivity(), selectedObject.getDialogId(), selectedObject.getId());
            return;
        }
        if (option == 9987) {
            // Colgram: open Telegram's own per-chat wallpaper picker for this dialog.
            try {
                presentFragment(new org.telegram.ui.WallpapersListActivity(org.telegram.ui.WallpapersListActivity.TYPE_COLOR, selectedObject.getDialogId()));
            } catch (Throwable ignoreWallpaper) {}
            return;
        }"""
        patch_file(
            chat_activity,
            process_option_target,
            process_option_replacement,
            "ChatActivity Handle Edit History Option"
        )

    # 31.0. provider_paths.xml -> let FileProvider serve recovered media revisions
    #
    # The edit-history sheet hands a sandbox copy of a replaced photo/video to an
    # external viewer via FileProvider.getUriForFile(). That throws
    # IllegalArgumentException("Failed to find configured root that contains ...")
    # unless a <files-path> entry covers the directory. Colgram stores revisions in
    # files/media_history/, which upstream's provider_paths.xml does not cover — the
    # stock entries are media / logs / cache / external_files only.
    provider_paths = os.path.join(repo_path, "TMessagesProj", "src", "main", "res",
                                  "xml", "provider_paths.xml")
    if os.path.exists(provider_paths):
        def provider_paths_replacer(content):
            if "media_history" in content:
                return content                      # already added
            entry = '    <files-path name="media_history" path="/media_history/"/>\n'
            # Insert just before </paths> so we never depend on the exact ordering of
            # the upstream entries.
            if "</paths>" in content:
                return content.replace("</paths>", entry + "</paths>", 1)
            # No closing tag (malformed or restructured upstream): bail loudly rather
            # than emit a broken XML that fails the resource merge 20 minutes later.
            print(" [!] provider_paths.xml has no </paths> — media-history URIs will fail")
            return content
        patch_file(provider_paths, provider_paths_replacer,
                   "media_history", "FileProvider media-history path")

    # 31.1. ChatActivity.java -> "Chat Wallpaper" menu entry in the message context menu
    #
    # ⚠️ THIS PATCH MUST NOT USE A PLAIN STRING ANCHOR. See the block comment below for
    # the duplicate-menu-entry bug that shipped because it did.
    if os.path.exists(chat_activity):
        def chat_wallpaper_menu_replacer(content):
            # The canonical merged shape: edit history AND wallpaper inside ONE `if`.
            #
            # Why one `if` and not two: every item added here must push to the SAME
            # index across all three parallel lists (items / options / icons). Two
            # separate `if` blocks that both push would still be index-parallel, but a
            # test that *removes* one of them can silently desynchronise the triples.
            # One block, three matched pushes, is the only shape that is safe to patch
            # incrementally.
            #
            # 🔴 LINE ENDINGS ARE LOAD-BEARING. This file and every Telegram source file
            # in the tree are CRLF-only (verified: 3770 CRLF / 0 bare LF here, 47302 / 0
            # in ChatActivity.java). A triple-quoted literal in a CRLF source file yields
            # \r\n, but a hand-written trailing '...}\n' yields a bare \n — so the
            # assembled block carries MIXED endings and can never match anything on
            # disk. That is a silent no-match: patch_file reports success (or the
            # callable just returns content unchanged) while nothing is injected.
            # Build every line from a LIST and join with the real newline instead.
            nl = "\r\n" if "\r\n" in content else "\n"

            history_arm = nl.join([
                '        if (selectedObject != null && org.colgram.core.ColgramConfig.isEditHistoryEnabled()) {',
                '            boolean isRuLang = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);',
                '            items.add(isRuLang ? "История изменений" : "Edit History");',
                '            options.add(9988);',
                '            icons.add(R.drawable.msg_edit);',
                '',
            ])
            wallpaper_arm = nl.join([
                '            if (org.colgram.core.ColgramConfig.isChatWallpaperEnabled()) {',
                '                items.add(isRuLang ? "Обои чата" : "Chat Wallpaper");',
                '                options.add(9987);',
                '                icons.add(R.drawable.msg_colors);',
                '            }',
                '',
            ])
            closing = '        }' + nl
            canonical = history_arm + wallpaper_arm + closing
            history_only = history_arm + closing

            # --- Repair pass: collapse any duplicated blocks already on disk ---------
            # Two shapes can be present on an already-damaged tree:
            #   (a) history_only + canonical      — an orphan arm followed by the merged
            #                                        block. Produced by the old string-anchor
            #                                        version of this patch.
            #   (b) history_only + history_only + canonical
            #                                     — an extra orphan from patch 31 re-firing
            #                                        on a re-run (see the note on patch 31).
            # Collapse repeatedly until neither shape remains, so the pass is convergent
            # regardless of how many orphans accumulated.
            dupe = history_only + canonical          # orphan, then the real one
            orphan_stack = history_only + history_only + canonical
            repaired = 0
            while orphan_stack in content:
                content = content.replace(orphan_stack, canonical, 1)
                repaired += 1
            while dupe in content:
                content = content.replace(dupe, canonical, 1)
                repaired += 1
            if repaired:
                print(f" [+] Repaired duplicated ChatActivity menu block ({repaired} orphan(s) collapsed)")

            if canonical in content:
                return content                        # already canonical

            # --- Insert pass: upgrade a history-only block in place -------------------
            # Anchor on the ARM, not on the whole `if` block. The old patch anchored on
            # the complete `...}\n        }` block, which matched BOTH copies, so
            # `replace(old, new, 1)` rewrote the FIRST occurrence and left the second —
            # producing the duplicate. Anchoring on the arm makes the insertion
            # idempotent and positional.
            if history_only in content:
                return content.replace(history_only, canonical, 1)

            # --- Fresh-clone pass: the block does not exist yet ----------------------
            # `fillMessageMenu` is the upstream method this menu is built in. Assert on
            # the method signature so a renamed upstream method fails LOUDLY here
            # instead of silently inserting nothing.
            #
            # Same CRLF trap: build the anchor and the insertion from the joined list.
            method_anchor = nl.join([
                '    ) {',
                '        final MessageObject message = selectedObject;',
            ])
            if method_anchor in content:
                method_fixed = nl.join([
                    '    ) {',
                ]) + canonical + '        final MessageObject message = selectedObject;'
                return content.replace(method_anchor, method_fixed, 1)
            print(" [!] ChatActivity fillMessageMenu anchor not found — menu entry NOT injected")
            return content

        chat_wallpaper_menu_replacer.__name__ = "chat_wallpaper_menu_replacer"
        patch_file(
            chat_activity,
            chat_wallpaper_menu_replacer,
            "org.colgram.core.ColgramConfig.isChatWallpaperEnabled()",
            "ChatActivity Chat Wallpaper Menu Option"
        )

    # 32. SessionCell.java & SessionBottomSheet.java -> Spoof Active Session Device Display & ConnectionsManager
    session_cell = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Cells", "SessionCell.java")
    if os.path.exists(session_cell):
        patch_file(
            session_cell,
            "final TLRPC.TL_authorization session = (TLRPC.TL_authorization) object;",
            """final TLRPC.TL_authorization session = (TLRPC.TL_authorization) object;
            if ((session.flags & 1) != 0 && org.colgram.core.ColgramConfig.isCloakEnabled()) {
                session.device_model = org.colgram.core.ColgramConfig.getSpoofDeviceModel();
                session.system_version = org.colgram.core.ColgramConfig.getSpoofSystemVersion();
            }""",
            "SessionCell Spoof Active Device Display"
        )

    session_sheet = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "SessionBottomSheet.java")
    if os.path.exists(session_sheet):
        patch_file(
            session_sheet,
            "timeView.setText(timeText);",
            """timeView.setText(timeText);
        if ((session.flags & 1) != 0 && org.colgram.core.ColgramConfig.isCloakEnabled()) {
            session.device_model = org.colgram.core.ColgramConfig.getSpoofDeviceModel();
            session.system_version = org.colgram.core.ColgramConfig.getSpoofSystemVersion();
        }""",
            "SessionBottomSheet Spoof Active Device Display"
        )

    if os.path.exists(conn_manager):
        patch_file(
            conn_manager,
            "deviceModel = Build.MANUFACTURER + Build.MODEL;",
            "deviceModel = org.colgram.core.ColgramConfig.getSpoofDeviceModel();",
            "ConnectionsManager Spoof deviceModel Directly"
        )
        patch_file(
            conn_manager,
            'systemVersion = "SDK " + Build.VERSION.SDK_INT;',
            "systemVersion = org.colgram.core.ColgramConfig.getSpoofSystemVersion();",
            "ConnectionsManager Spoof systemVersion Directly"
        )

    # 33. UserConfig.java -> Safe Account Bounds & Safe Defaults
    if os.path.exists(user_config):
        user_max_target = "public final static int MAX_ACCOUNT_DEFAULT_COUNT = 3;"
        user_max_replacement = "public final static int MAX_ACCOUNT_DEFAULT_COUNT = 4;"
        patch_file(
            user_config,
            user_max_target,
            user_max_replacement,
            "UserConfig Set MAX_ACCOUNT_DEFAULT_COUNT to 4"
        )
        patch_file(
            user_config,
            "public boolean syncContacts = true;",
            "public boolean syncContacts = false; // Colgram: anonymous by default",
            "UserConfig Default syncContacts to false"
        )
        patch_file(
            user_config,
            "public boolean suggestContacts = true;",
            "public boolean suggestContacts = false; // Colgram: anonymous by default",
            "UserConfig Default suggestContacts to false"
        )
        patch_file(
            user_config,
            'syncContacts = preferences.getBoolean("syncContacts", true);',
            'syncContacts = preferences.getBoolean("syncContacts", false);',
            "UserConfig Load syncContacts default false"
        )
        patch_file(
            user_config,
            'suggestContacts = preferences.getBoolean("suggestContacts", true);',
            'suggestContacts = preferences.getBoolean("suggestContacts", false);',
            "UserConfig Load suggestContacts default false"
        )
        patch_file(
            user_config,
            'return currentUser != null && currentUser.phone != null ? currentUser.phone : "";',
            'if (currentUser != null && currentUser.phone == null) currentUser.phone = "";\n            return currentUser != null && currentUser.phone != null ? currentUser.phone : "";',
            "UserConfig Safe ClientPhone"
        )
        patch_file(
            user_config,
            "public boolean isPremium() {",
            "public boolean isPremium() {\n        if (true) return true;",
            "UserConfig Unlock Client-Side Premium"
        )




    # 34. BuildVars.java -> Official Telegram Android credentials & disable SafetyNet check
    build_vars_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "BuildVars.java")
    if os.path.exists(build_vars_file):
        def build_vars_injector(content):
            import re
            content = re.sub(r'public static int APP_ID = \d+;[^\n]*', 'public static int APP_ID = 6; // Official Telegram for Android', content)
            content = re.sub(r'public static String APP_HASH = "[^"]+";', 'public static String APP_HASH = "eb06d4abfb49dc3eeb1aeb98ae0f581e";', content)
            content = re.sub(r'public static String SAFETYNET_KEY = "[^"]*";', 'public static String SAFETYNET_KEY = "";', content)
            return content
        patch_file(build_vars_file, build_vars_injector, "APP_ID = 6", "BuildVars Set APP_ID & APP_HASH to Official Android & Clear SafetyNet")


    # 35. ProxyListActivity.java -> Silent 1-Tap Proxy Toggle (Never ask for input on empty list)
    proxy_list_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ProxyListActivity.java")
    if os.path.exists(proxy_list_file):
        patch_file(
            proxy_list_file,
            "if (SharedConfig.currentProxy == null) {\n                    if (!proxyList.isEmpty()) {",
            """if (SharedConfig.currentProxy == null) {
                    if (proxyList.isEmpty()) {
                        org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
                    }
                    if (!proxyList.isEmpty()) {""",
            "ProxyListActivity Silent 1-Tap Proxy Toggle"
        )


    # 36. Browser.java -> Route links through in-app browser for proxy anonymity
    browser_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "browser", "Browser.java")
    if os.path.exists(browser_file):
        def browser_proxy_injector(c):
            target = "if (allowCustom && !(uri != null && MessagesController.getInstance(currentAccount).isWebBrowserOpenInApp(uri.toString()) || isInstantViewOpen())"
            if target not in c:
                return c
            inject = """if (org.colgram.core.ColgramConfig.isProxyBrowserEnabled()) {
                openInTelegramBrowser(context, uri.toString(), inCaseLoading);
                return;
            }
            if (allowCustom && !(uri != null && MessagesController.getInstance(currentAccount).isWebBrowserOpenInApp(uri.toString()) || isInstantViewOpen())"""
            return c.replace(target, inject, 1)
        patch_file(browser_file, browser_proxy_injector, "org.colgram.core.ColgramConfig.isProxyBrowserEnabled()", "Browser Force In-App Browser for Privacy")

    # 37. LoginActivity.java -> Always visible Proxy Button
    login_activity_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LoginActivity.java")
    if os.path.exists(login_activity_file):
        patch_file(
            login_activity_file,
            "showProxyButton(false, animated);",
            "showProxyButton(true, animated);",
            "LoginActivity Keep Proxy Button Always Visible"
        )
        patch_file(
            login_activity_file,
            "proxyButtonView.setOnClickListener(v -> presentFragment(new ProxyListActivity()));",
            """proxyButtonView.setOnClickListener(v -> {
            org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
            presentFragment(new ProxyListActivity());
        });""",
            "LoginActivity Auto-Populate Proxy on Click"
        )

    # 38. IntroActivity.java -> Colgram Dark Black Intro Screen with Red Airplane Logo
    intro_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "IntroActivity.java")
    if os.path.exists(intro_file):
        patch_file(
            intro_file,
            'ssb.setSpan(new ImageSpan(logoDrawable), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);\n        titles[0] = ssb;',
            'titles[0] = "Colgram";',
            "IntroActivity Set Title to Colgram"
        )
        # The replacement is the BILINGUAL form the checkout actually ships. It used to write
        # a Russian-only literal, while the file had since been hand-improved to switch on the
        # active language — so a fresh clone silently lost the English subtitle, and the patch
        # reported a permanent miss locally because its own target had moved past it.
        patch_file(
            intro_file,
            'LocaleController.getString(R.string.Page1Message),',
            'ru ? "Быстрый, приватный и свободный мессенджер" : "A fast, private and free messenger",',
            "IntroActivity Set Subtitle to Colgram"
        )
        # "IntroActivity Show Colgram Red Airplane" REMOVED as obsolete.
        #
        # It added an ImageView overlay on top of the native intro TextureView. The anchor no
        # longer matches upstream, so it has not applied for a long time — and the intro still
        # shows the Colgram plane, verified on device with no `colgramLogo` present anywhere in
        # the built tree. The branding therefore comes from the splash/launcher assets written
        # by prepare_branding and the native intro, not from this patch.
        #
        # It is deleted rather than re-anchored because re-anchoring it would draw a SECOND
        # logo on top of the native one, and because the native view must never be hidden —
        # see the invalid-surface abort documented below.
        # NOTE: the day/night switcher used to be hidden here with
        # themeFrameLayout.setVisibility(View.GONE). That is deliberately NOT done any more.
        # The stock paragraph around this frame also carries the native intro surface; a
        # GONE child of it is the same invalid-surface hazard described above, and it was a
        # second contributing cause of the startup abort. Leaving the switcher visible is
        # harmless (it is a themed 64x64 control) and keeps the native view tree intact.
        # Colgram branding on the intro screen.
        #
        # HISTORY — this block used to force fragmentView to 0xFF000000 (pure black) while
        # also hardcoding header/message text to white/grey. That works on the intro screen
        # alone, but LoginActivity REUSES this same container via setIntroView(), and the
        # phone-input form draws its title, subtitle and phone field with
        # Theme.key_windowBackgroundWhiteBlackText — a colour that assumes a LIGHT
        # background. Worse, IntroActivity.updateColors() rewrites the pager text back to
        # the theme colour on every theme event, so even the hardcoded white got stomped.
        # Net result: near-black text on a pure-black background — an unreadable login
        # screen, and the phone field invisible.
        #
        # The fix is to stop fighting the theme. We brand in Colgram red (accents) and let
        # the background and all body text follow the active theme, so every form on this
        # container stays legible in both light and dark mode.
        # These three patches used to hard-code Colgram red into the intro and to place the
        # language badge with a fixed top margin. They are GONE, deliberately:
        #
        #   * they wrote raw colours that ignore light/dark and the cyber palette, which is
        #     the opposite of what the surrounding comment asks for;
        #   * the badge one produced `top = 16`, the exact value that puts the badge under the
        #     status bar;
        #   * the intro patch above now consumes both the pristine upstream form and the red
        #     form, so it no longer depends on these running first.
        #
        # Leaving them in meant a fresh clone got reds and a misplaced badge while the local
        # checkout — where they simply failed to match — looked correct. That split is the
        # worst possible failure mode, because CI is what ships.
        #
        # Their replacements live in the intro patch near "colgramBadgeTop" / "colgramAccent".

        # 38b. strings.xml -> the in-app name still said "Telegram".
        #
        # The manifest label was already rebranded (android:label="Colgram"), so the
        # launcher and recents show Colgram. But the MAIN SCREEN's action bar title does
        # not read the manifest - it is built from R.string.AppName
        # (DialogsActivity: new SpannableStringBuilder(getString(R.string.AppName))),
        # which is still "Telegram". Hence "why does it say Telegram, not Colgram".
        strings_xml = os.path.join(repo_path, "TMessagesProj", "src", "main", "res", "values", "strings.xml")
        patch_file(
            strings_xml,
            '<string name="AppName">Telegram</string>',
            '<string name="AppName">Colgram</string>',
            "strings.xml AppName -> Colgram"
        )
        patch_file(
            strings_xml,
            '<string name="AppNameBeta">Telegram Beta</string>',
            '<string name="AppNameBeta">Colgram Beta</string>',
            "strings.xml AppNameBeta -> Colgram Beta"
        )

        # BUG #2 (dark themes rendering black-on-black) is closed, so the temporary
        # IntroActivity theme probe that used to live here has been removed. It served its
        # purpose: it proved the palette really does resolve to 0 for a partial dark theme
        # and that the cyber override was rewriting surfaces without covering foregrounds.
        # Both of those are now handled permanently inside Theme.getColor (see
        # COLGRAM_GETCOLOR_WRAPPER), which is where the fix belongs - not in a log line.
        #
        # Keep the themed background. The old patch replaced this line with 0xFF000000;
        # that is the root cause of the black-on-black login form, so we explicitly assert
        # the themed form is what is present and do NOT mutate it.
        intro_bg_ok = False
        if os.path.exists(intro_file):
            with open(intro_file, "r", encoding="utf-8", errors="ignore") as f:
                _intro = f.read()
            intro_bg_ok = "fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));" in _intro
        if intro_bg_ok:
            print(" [=] IntroActivity background stays theme-driven - login form stays legible")
        else:
            print(" [!] IntroActivity background was force-darkened; login text will be "
                  "unreadable. Re-run with a clean upstream checkout.")

        # updateColors() is the second half of the black-on-black bug: it re-asserts the
        # background and text colours on every theme event, so any one-shot fix to the
        # constructor gets overwritten the moment the theme changes. Neutralise the
        # hardcoded black here and let the themed values flow through.
        #
        # Anchored on the background line ALONE. It used to span three lines including
        # `switchLanguageTextView.setTextColor(0xFFEF5350)`, but the accent patch earlier in
        # this run rewrites that exact line — so the span could never match once that patch
        # had done its job, and this fix silently stopped applying. Only the background is
        # this patch's business.
        patch_file(
            intro_file,
            '        fragmentView.setBackgroundColor(0xFF000000);\n',
            '        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));\n',
            "IntroActivity updateColors Keeps Themed Background"
        )

        # Reset startPressed when the intro comes back to the foreground.
        #
        # THE "second press of Начать общение does nothing" BUG.
        #
        # `startPressed` is a plain instance field, set true on the first press and never
        # cleared anywhere. The IntroActivity instance stays alive in the fragment stack
        # after navigating to the login form, so on returning to it the field is still true
        # and the click handler hits its own guard: `if (startPressed) return;`. The button
        # silently does nothing until the whole process is restarted and a fresh instance
        # is built with startPressed = false.
        #
        # Clearing it in onResume is the right place: the intro is only ever re-shown when
        # the user comes back to it, which is exactly when the button must work again.
        patch_file(
            intro_file,
            '''    public void onResume() {
        super.onResume();
        if (justCreated) {''',
            '''    public void onResume() {
        super.onResume();
        // Colgram: re-arm the Start Messaging button. Without this, returning to the intro
        // leaves startPressed true and the button no-ops until the app is restarted.
        startPressed = false;
        if (justCreated) {''',
            "IntroActivity Re-arm Start Button on Resume"
        )


    # 41. DialogsActivity.java -> Bot Account Chat Initiator on Floating Button
    dialogs_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "DialogsActivity.java")
    if os.path.exists(dialogs_activity):
        bot_write_contacts_target = """    private void openWriteContacts() {
        Bundle args = new Bundle();
        args.putBoolean("destroyAfterSelect", true);
        presentFragment(new ContactsActivity(args));
    }"""
        bot_write_contacts_replacement = """    private void openWriteContacts() {
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            org.colgram.core.ColgramBotSync.showStartChatDialog(getParentActivity(), currentAccount, DialogsActivity.this);
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("destroyAfterSelect", true);
        presentFragment(new ContactsActivity(args));
    }"""
        patch_file(
            dialogs_activity,
            bot_write_contacts_target,
            bot_write_contacts_replacement,
            "DialogsActivity Bot Account openWriteContacts Hook"
        )

    # 42. DialogsEmptyCell.java -> Custom Bot Account Empty State
    dialogs_empty_cell = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Cells", "DialogsEmptyCell.java")
    if os.path.exists(dialogs_empty_cell):
        empty_cell_target = """        if (icon != 0) {
            imageView.setVisibility(VISIBLE);"""
        empty_cell_replacement = """        if (org.telegram.messenger.UserConfig.getInstance(currentAccount).getCurrentUser() != null && org.telegram.messenger.UserConfig.getInstance(currentAccount).getCurrentUser().bot) {
            boolean isRuBot = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            titleView.setText(isRuBot ? "Аккаунт бота" : "Bot Account");
            help = isRuBot ? "Здесь будут отображаться сообщения от пользователей.\\n\\nНажмите на карандаш внизу или сюда, чтобы написать пользователю."
                           : "Messages from users will appear here.\\n\\nTap the compose button below or here to message a user.";
            setOnClickListener(v -> {
                org.colgram.core.ColgramBotSync.showStartChatDialog(org.telegram.messenger.AndroidUtilities.getActivity(), currentAccount);
            });
        }
        if (icon != 0) {
            imageView.setVisibility(VISIBLE);"""
        patch_file(
            dialogs_empty_cell,
            empty_cell_target,
            empty_cell_replacement,
            "DialogsEmptyCell Custom Bot Account Empty State"
        )

    # 43. BulletinFactory.java -> Suppress BOT_METHOD_INVALID error bulletins
    bulletin_factory = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Components", "BulletinFactory.java")
    if os.path.exists(bulletin_factory):
        patch_file(
            bulletin_factory,
            "if (!LaunchActivity.isActive) return new Bulletin.EmptyBulletin();",
            'if (!LaunchActivity.isActive || (error != null && error.text != null && error.text.contains("BOT_METHOD_INVALID"))) return new Bulletin.EmptyBulletin();',
            "BulletinFactory Suppress BOT_METHOD_INVALID in makeForError"
        )
        patch_file(
            bulletin_factory,
            """    public void showForError(TLRPC.TL_error error, boolean top) {
        if (!LaunchActivity.isActive) return;""",
            """    public void showForError(TLRPC.TL_error error, boolean top) {
        if (!LaunchActivity.isActive || (error != null && error.text != null && error.text.contains("BOT_METHOD_INVALID"))) return;""",
            "BulletinFactory Suppress BOT_METHOD_INVALID in showForError(TL_error)"
        )
        patch_file(
            bulletin_factory,
            """    public void showForError(String errorCode, boolean top) {
        if (!LaunchActivity.isActive) return;""",
            """    public void showForError(String errorCode, boolean top) {
        if (!LaunchActivity.isActive || (errorCode != null && errorCode.contains("BOT_METHOD_INVALID"))) return;""",
            "BulletinFactory Suppress BOT_METHOD_INVALID in showForError(String)"
        )
        patch_file(
            bulletin_factory,
            """    public static void showError(TLRPC.TL_error error) {
        if (!LaunchActivity.isActive) return;
        if (error != null && error.code == 406) return;""",
            """    public static void showError(TLRPC.TL_error error) {
        if (!LaunchActivity.isActive) return;
        if (error != null && (error.code == 406 || (error.text != null && error.text.contains("BOT_METHOD_INVALID")))) return;""",
            "BulletinFactory Suppress BOT_METHOD_INVALID in showError"
        )

    # 44. AlertsCreator.java -> Suppress BOT_METHOD_INVALID in processError
    alerts_creator = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Components", "AlertsCreator.java")
    if os.path.exists(alerts_creator):
        patch_file(
            alerts_creator,
            "if (error == null || error.code == 406 || error.text == null) {",
            'if (error == null || error.code == 406 || error.text == null || error.text.contains("BOT_METHOD_INVALID")) {',
            "AlertsCreator Suppress BOT_METHOD_INVALID in processError"
        )

    # 45. DialogsActivity.java -> Start Bot Updates Poller in onResume
    if os.path.exists(dialogs_activity):
        bot_resume_target = "public void onResume() {\n        super.onResume();"
        bot_resume_inject = """public void onResume() {\n        super.onResume();
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            org.colgram.core.ColgramBotSync.startBotUpdatesPoller(getParentActivity(), currentAccount);
        }"""
        patch_file(
            dialogs_activity,
            bot_resume_target,
            bot_resume_inject,
            "DialogsActivity Start Bot Updates Poller in onResume"
        )

    # 46. ChangeNameActivity.java -> Bot Profile Name Update Hook
    change_name = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChangeNameActivity.java")
    if os.path.exists(change_name):
        cname_target = """        if (currentUser.first_name != null && currentUser.first_name.equals(newFirst) && currentUser.last_name != null && currentUser.last_name.equals(newLast)) {
            return;
        }"""
        cname_inject = """        if (currentUser.first_name != null && currentUser.first_name.equals(newFirst) && currentUser.last_name != null && currentUser.last_name.equals(newLast)) {
            return;
        }
        if (currentUser.bot) {
            org.colgram.core.ColgramBotSync.updateBotName(getParentActivity(), currentAccount, newFirst);
            finishFragment();
            return;
        }"""
        patch_file(change_name, cname_target, cname_inject, "ChangeNameActivity Bot Name Update Hook")

    # 47. ChangeBioActivity.java -> Bot Profile Bio/Description Update Hook
    #
    # TWO fixes here, both required:
    #
    # (a) The early guard. saveName() begins with
    #         final TLRPC.UserFull userFull = ...getUserFull(getClientUserId());
    #         if (getParentActivity() == null || userFull == null) return;
    #     On a bot account the client never performs an MTProto users.getFullUser for
    #     itself, so getUserFull() returns null and the method returns before reaching any
    #     of our code. The save button therefore did nothing at all, silently — this is the
    #     "I can't change the bot's description" report. Guarding the bot branch BEFORE the
    #     null check is what makes the edit reachable.
    #
    # (b) userFull may legitimately be null for a bot, so the confirmation callback must
    #     not dereference it unconditionally.
    change_bio = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChangeBioActivity.java")
    if os.path.exists(change_bio):
        cbio_guard_target = """        if (getParentActivity() == null || userFull == null) {
            return;
        }"""
        cbio_guard_replacement = """        if (getParentActivity() == null) {
            return;
        }
        final TLRPC.User colgramSelf = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (colgramSelf != null && colgramSelf.bot) {
            // Bots have no MTProto UserFull on the client, so the normal path below would
            // always bail out. Route straight to the Bot API.
            final String botNewName = firstNameField.getText().toString().replace("\\n", "");
            org.colgram.core.ColgramBotSync.updateBotDescription(getParentActivity(), currentAccount, botNewName, () -> {
                if (userFull != null) {
                    userFull.about = botNewName;
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.userInfoDidLoad, colgramSelf.id, userFull);
                }
                finishFragment();
            });
            return;
        }
        if (userFull == null) {
            return;
        }"""
        patch_file(change_bio, cbio_guard_target, cbio_guard_replacement, "ChangeBioActivity Bot Guard Before UserFull Null Check")

        # The original bot hook is now unreachable (the guard above returns first), but it
        # also referenced a bot-only path that is already handled. Replace it so we do not
        # leave a dead duplicate whose callback dereferences userFull.
        cbio_target = """        final String newName = firstNameField.getText().toString().replace("\\n", "");
        if (currentName.equals(newName)) {
            finishFragment();
            return;
        }
        final TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (currentUser != null && currentUser.bot) {
            org.colgram.core.ColgramBotSync.updateBotDescription(getParentActivity(), currentAccount, newName, () -> {
                userFull.about = newName;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.userInfoDidLoad, currentUser.id, userFull);
                finishFragment();
            });
            return;
        }"""
        cbio_replacement = """        final String newName = firstNameField.getText().toString().replace("\\n", "");
        if (currentName.equals(newName)) {
            finishFragment();
            return;
        }
        final TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (currentUser != null && currentUser.bot) {
            // Unreachable in practice (handled above), kept as a defensive fallback.
            org.colgram.core.ColgramBotSync.updateBotDescription(getParentActivity(), currentAccount, newName, () -> {
                if (userFull != null) {
                    userFull.about = newName;
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.userInfoDidLoad, currentUser.id, userFull);
                }
                finishFragment();
            });
            return;
        }"""
        patch_file(change_bio, cbio_target, cbio_replacement, "ChangeBioActivity Bot Description Null-Safe Callback")

    # 47b. UserInfoActivity.java -> bot accounts can actually save their profile.
    #
    # ⚠️ THIS IS THE SCREEN THE USER ACTUALLY REACHES. The ChangeNameActivity /
    # ChangeBioActivity hooks above sit on screens you cannot get to from a profile:
    # ProfileActivity's own-profile Edit item opens UserInfoActivity -
    #     } else if (id == edit_profile) { presentFragment(new UserInfoActivity()); }
    # - while ChangeBioActivity is referenced ONLY from a commented-out upstream //TODO
    # line (no entry point at all) and ChangeNameActivity appears solely in the settings
    # SEARCH index. Hence "I go into the bot account to edit settings and I cannot".
    #
    # Reaching it would not have been enough either: this screen saves via MTProto
    #     TL_account.updateProfile req1 = new TL_account.updateProfile();
    # and a bot account answers BOT_METHOD_INVALID - the same error Colgram already
    # suppresses in BulletinFactory.
    #
    # Fix: when the signed-in account is a bot, route name + description through the Bot
    # API (setMyName / setMyDescription + setMyShortDescription) and skip the MTProto
    # request, which cannot succeed.
    user_info = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "UserInfoActivity.java")
    patch_file(
        user_info,
        "            TL_account.updateProfile req1 = new TL_account.updateProfile();",
        "            // Colgram: bot accounts cannot use MTProto account.updateProfile - it\n"
        "            // answers BOT_METHOD_INVALID. Route the name and description through the\n"
        "            // Bot API instead and skip the MTProto request, which cannot succeed.\n"
        "            final TLRPC.User colgramSelfUser = getUserConfig().getCurrentUser();\n"
        "            if (colgramSelfUser != null && colgramSelfUser.bot) {\n"
        "                final String colgramNewFirst = firstNameEdit.getText().toString();\n"
        "                final String colgramNewLast = lastNameEdit.getText().toString();\n"
        "                final String colgramNewBio = bioEdit.getText().toString();\n"
        "                org.colgram.core.ColgramBotSync.updateBotName(getParentActivity(), currentAccount,\n"
        "                        (colgramNewFirst + \" \" + colgramNewLast).trim());\n"
        "                org.colgram.core.ColgramBotSync.updateBotDescription(getParentActivity(), currentAccount,\n"
        "                        colgramNewBio, null);\n"
        "                user.first_name = colgramNewFirst;\n"
        "                user.last_name = colgramNewLast;\n"
        "                userFull.about = colgramNewBio;\n"
        "                userFull.flags = TextUtils.isEmpty(colgramNewBio) ? (userFull.flags & ~2) : (userFull.flags | 2);\n"
        "                getMessagesStorage().updateUserInfo(userFull, false);\n"
        "                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.userInfoDidLoad, user.id, userFull);\n"
        "                finishFragment();\n"
        "                return;\n"
        "            }\n"
        "            TL_account.updateProfile req1 = new TL_account.updateProfile();",
        "UserInfoActivity Bot Profile Save Via Bot API"
    )

    # 47c. UserInfoActivity.java -> bot profile LOADS instead of hanging on "Loading...".
    #
    # The save path above is useless if the screen never populates. setValue() does:
    #
    #     TLRPC.UserFull userFull = getMessagesController().getUserFull(selfId);
    #     if (userFull == null) {
    #         getMessagesController().loadUserInfo(..., true, getClassGuid());
    #         return;                       // <- fields stay unset
    #     }
    #
    # and the UI renders R.string.Loading ("Загрузка...") until that resolves. A bot account
    # has no usable MTProto UserFull, so loadUserInfo() never delivers: the profile shows a
    # permanent "Загрузка..." and the description can never be read or changed.
    #
    # Fix: for a bot, seed the name from the local user, pull name + description from the
    # Bot API (getMe) on a background thread, and stop waiting on MTProto entirely.
    patch_file(
        user_info,
        "        final long selfId = getUserConfig().getClientUserId();\n"
        "        TLRPC.UserFull userFull = getMessagesController().getUserFull(selfId);\n"
        "        if (userFull == null) {\n"
        "            getMessagesController().loadUserInfo(getUserConfig().getCurrentUser(), true, getClassGuid());\n"
        "            return;\n"
        "        }",
        "        final long selfId = getUserConfig().getClientUserId();\n"
        "        TLRPC.UserFull userFull = getMessagesController().getUserFull(selfId);\n"
        "\n"
        "        // Colgram: a bot account has no usable MTProto UserFull, so getUserFull()\n"
        "        // stays null, loadUserInfo() never delivers, and the bio sits on\n"
        "        // R.string.Loading forever. Seed from the local user, then fill name and\n"
        "        // description from the Bot API instead of waiting on MTProto.\n"
        "        final TLRPC.User colgramBotSelf = getUserConfig().getCurrentUser();\n"
        "        if (colgramBotSelf != null && colgramBotSelf.bot && userFull == null) {\n"
        "            valueSet = true;\n"
        "            firstNameEdit.setText(currentFirstName = colgramBotSelf.first_name);\n"
        "            lastNameEdit.setText(currentLastName = colgramBotSelf.last_name);\n"
        "            checkDone(true);\n"
        "            new Thread(() -> {\n"
        "                final org.json.JSONObject colgramBot = org.colgram.core.ColgramBotSync.fetchBotProfile(\n"
        "                        org.telegram.messenger.ApplicationLoader.applicationContext, currentAccount);\n"
        "                if (colgramBot == null) return;\n"
        "                final String colgramBotName = colgramBot.optString(\"first_name\", \"\");\n"
        "                final String colgramBotDesc = colgramBot.optString(\"description\", \"\");\n"
        "                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {\n"
        "                    if (!colgramBotName.isEmpty()) {\n"
        "                        firstNameEdit.setText(currentFirstName = colgramBotName);\n"
        "                    }\n"
        "                    if (!colgramBotDesc.isEmpty()) {\n"
        "                        bioEdit.setText(currentBio = colgramBotDesc);\n"
        "                    }\n"
        "                    checkDone(true);\n"
        "                });\n"
        "            }, \"colgram-bot-profile\").start();\n"
        "            return;\n"
        "        }\n"
        "\n"
        "        if (userFull == null) {\n"
        "            getMessagesController().loadUserInfo(getUserConfig().getCurrentUser(), true, getClassGuid());\n"
        "            return;\n"
        "        }",
        "UserInfoActivity Bot Profile Load Via Bot API"
    )

    # Upgrade pass for the line above.
    #
    # The load patch originally wrote the fetched description unconditionally. getMe does not
    # return a description, so that call blanked the bio field on every visit — and because
    # the patch's anchor is the PRISTINE upstream code, editing its replacement could not
    # reach a checkout that already carried the old form. This second pass repairs existing
    # trees; the pristine path still handles fresh clones and CI.
    patch_file(
        user_info,
        "                    bioEdit.setText(currentBio = colgramBotDesc);\n",
        "                    if (!colgramBotDesc.isEmpty()) {\n"
        "                        bioEdit.setText(currentBio = colgramBotDesc);\n"
        "                    }\n",
        "UserInfoActivity Bot Description Blank Guard"
    )

    # 47d. ConnectionsManager.onProxyError() -> rotate the proxy IMMEDIATELY.
    #
    # This is THE reason Colgram "never cycled through the proxies".
    #
    # Telegram's native layer calls onProxyError() (TgNetWrapper.cpp:383) the moment a
    # proxy connection fails. Upstream only shows an alert:
    #
    #     public static void onProxyError() {
    #         AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
    #                 .postNotificationName(NotificationCenter.needShowAlert, 3));
    #     }
    #
    # and Colgram did not reference it AT ALL. The only rotation trigger was a 5-minute
    # periodic health check in ColgramProxyManager, one step per check. The pool holds 8
    # entries (7 hardcoded MTProto proxies + the in-process local DPI bypass at the END),
    # so a user on a censored network whose remote proxies are all dead waited up to
    # ~40 minutes to reach the one entry that actually works - which reads exactly as
    # "it never tried the others".
    #
    # Wiring the native failure signal to the rotator turns that into a few seconds.
    # ColgramProxyManager.switchToNextProxy() debounces (3s) so a flapping proxy cannot
    # spin the whole pool, and falls back to the local DPI bypass once it wraps.
    cm_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "tgnet", "ConnectionsManager.java")
    patch_file(
        cm_file,
        "    public static void onProxyError() {\n"
        "        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needShowAlert, 3));\n"
        "    }",
        "    public static void onProxyError() {\n"
        "        // Colgram: native reports a failed proxy connection here. Rotate immediately\n"
        "        // instead of waiting for the 5-minute health check - see apply-patches.py 47d.\n"
        "        try {\n"
        "            org.colgram.core.ColgramProxyManager.switchToNextProxy();\n"
        "        } catch (Throwable ignore) {\n"
        "\n"
        "        }\n"
        "        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needShowAlert, 3));\n"
        "    }",
        "ConnectionsManager onProxyError Rotates Proxy"
    )

    # 48. ChangeUsernameActivity.java -> Bot Username Notice Hook
    change_user = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChangeUsernameActivity.java")
    if os.path.exists(change_user):
        cuser_target = "final TL_account.updateUsername req = new TL_account.updateUsername();"
        cuser_inject = """TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (currentUser != null && currentUser.bot) {
            boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            android.widget.Toast.makeText(getParentActivity(), isRu ? "Юзернейм бота можно изменить только через @BotFather" : "Bot username can only be changed via @BotFather", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final TL_account.updateUsername req = new TL_account.updateUsername();"""
        patch_file(change_user, cuser_target, cuser_inject, "ChangeUsernameActivity Bot Username Notice Hook")

    # 49. qr_logo.svg -> Black Color for Colgram Airplane in QR Code
    qr_svg = os.path.join(repo_path, "TMessagesProj", "src", "main", "res", "raw", "qr_logo.svg")
    if os.path.exists(qr_svg):
        patch_file(qr_svg, 'fill="#50A7EA"', 'fill="#000000"', "QR Code Black Logo SVG")

    # 51. MessagesController.java -> Auto-hide the phone number on first login
    #
    # Telegram's default is "phone visible to everybody". When an account is signed in
    # with a phone number, its number is then enumerable by anyone who has it. Colgram
    # forces the privacy setting to "Nobody" once, at first sync, for accounts that
    # actually have a phone number attached.
    #
    # The request must be issued from Telegrams own code: colgram-core compiles before
    # TMessagesProj and therefore cannot reference TLRPC or ConnectionsManager. The core
    # only supplies the one-shot flag.
    msgs_ctrl = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesController.java")
    if os.path.exists(msgs_ctrl):
        phone_target = "                            getContactsController().setPrivacyRules(update.rules, ContactsController.PRIVACY_RULES_TYPE_PHONE);"
        phone_inject = """                            getContactsController().setPrivacyRules(update.rules, ContactsController.PRIVACY_RULES_TYPE_PHONE);
                            if (org.colgram.core.ColgramHookHandler.shouldAutoHidePhoneNumber(currentAccount)) {
                                try {
                                    // TL_account is already imported directly in this file
                                    // (org.telegram.tgnet.tl.TL_account), so reference it
                                    // unqualified - TLRPC.TL_account_* does not exist.
                                    TL_account.setPrivacy colgramPhoneReq = new TL_account.setPrivacy();
                                    colgramPhoneReq.key = new TLRPC.TL_inputPrivacyKeyPhoneNumber();
                                    // "Nobody" is represented by DisallowAll; there is no
                                    // TL_inputPrivacyValueAllowNobody class.
                                    colgramPhoneReq.rules.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
                                    getConnectionsManager().sendRequest(colgramPhoneReq, (response, error) -> {
                                        if (error == null && response instanceof TL_account.privacyRules) {
                                            getContactsController().setPrivacyRules(((TL_account.privacyRules) response).rules, ContactsController.PRIVACY_RULES_TYPE_PHONE);
                                            org.colgram.core.ColgramHookHandler.markPhoneNumberHidden(currentAccount);
                                        }
                                    });
                                } catch (Throwable ignorePhone) {}
                            }"""
        patch_file(msgs_ctrl, phone_target, phone_inject, "MessagesController Auto-Hide Phone Number")

    # 52. BotWebViewSheet.java -> Pin mini-app to a floating window
    #
    # Telegram already ships a complete PiP framework (org.telegram.messenger.pip:
    # PipActivityController / PipSource), used for video. PipSource is generic over a
    # plain View, so a mini-app's WebView can be registered as a PiP source with no new
    # infrastructure. This adds a menu button that pins the current mini-app as a
    # floating window; several can coexist because the controller keeps one source per
    # tagPrefix and reuses the same activity.
    ids_xml = os.path.join(repo_path, "TMessagesProj", "src", "main", "res", "values", "ids.xml")
    if os.path.exists(ids_xml):
        patch_file(
            ids_xml,
            '    <item name="menu_collapse_bot" type="id"/>',
            '    <item name="menu_collapse_bot" type="id"/>\n    <item name="colgram_menu_pin_miniapp" type="id"/>',
            "ids.xml Pin MiniApp Menu Id"
        )

    bot_sheet = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "bots", "BotWebViewSheet.java")
    if os.path.exists(bot_sheet):
        pin_menu_target = "        optionsItem = menu.addItem(0, optionsIcon = new BotFullscreenButtons.OptionsIcon(getContext()));"
        pin_menu_inject = """        colgramPinItem = menu.addItem(R.id.colgram_menu_pin_miniapp, R.drawable.menu_video_pip);
        optionsItem = menu.addItem(0, optionsIcon = new BotFullscreenButtons.OptionsIcon(getContext()));"""
        patch_file(bot_sheet, pin_menu_target, pin_menu_inject, "BotWebViewSheet Pin MiniApp Menu Item")

        pin_click_target = """                } else if (id == R.id.menu_collapse_bot) {
                    forceExpnaded = true;
                    dismiss(true, null);
                }"""
        # NOTE: the PiP call lives HERE, in patched Telegram code, not in colgram-core.
        # colgram-core compiles before TMessagesProj, so it cannot reference
        # org.telegram.messenger.pip.* — that would be a module-boundary violation.
        # The core only supplies the enable flag (see ColgramConfig.isMiniAppPipEnabled).
        pin_click_inject = """                } else if (id == R.id.menu_collapse_bot) {
                    forceExpnaded = true;
                    dismiss(true, null);
                } else if (id == R.id.colgram_menu_pin_miniapp) {
                    colgramPinMiniAppToFloatingWindow();
                }"""
        patch_file(bot_sheet, pin_click_target, pin_click_inject, "BotWebViewSheet Pin MiniApp Click Handler")

        # Field declaration for the menu item we added above.
        field_target = "    private ActionBarMenuItem optionsItem;"
        field_inject = """    private ActionBarMenuItem optionsItem;
    private ActionBarMenuItem colgramPinItem;"""
        patch_file(bot_sheet, field_target, field_inject, "BotWebViewSheet Pin Menu Field")

        # The implementation. Registers the mini-app WebView with Telegram's own PiP
        # controller, which is what makes it float above other apps like a desktop window.
        #
        # Follows the exact pattern used by PipVideoOverlay.setPhotoViewer():
        # check permissions -> build a PipSource against the parent activity -> the
        # controller takes over. Sources are keyed by tagPrefix, so each mini-app gets
        # its own window and they do not replace one another.
        impl_target = "    public void setFullscreen(boolean fullscreen, boolean animated) {"
        impl_inject = """    /**
     * Colgram: pin this mini-app into a floating window.
     *
     * Two-tier strategy, because the platform allows different things depending on
     * permissions:
     *
     *   1. ColgramFloatWindowManager — a real draggable overlay window. This IS the
     *      "several at once, like a desktop" path the feature is asked for. It needs the
     *      draw-over-other-apps permission; each window is an independent
     *      WindowManager.addView using TYPE_APPLICATION_OVERLAY, which Android does not
     *      cap at one.
     *
     *   2. Telegram's PipSource / PipActivityController — used when the overlay permission
     *      is missing. No permission needed, but the controller keeps a single
     *      maxPrioritySource and calls onLoseMaxPriority() on every other source, and
     *      Android itself allows only one system PiP window per task. One window only.
     *
     * The user is told which one they got, rather than the button silently doing less
     * than it says.
     */
    private void colgramPinMiniAppToFloatingWindow() {
        if (!org.colgram.core.ColgramConfig.isMiniAppPipEnabled()) {
            return;
        }
        // BotWebViewSheet extends Dialog, not BaseFragment - there is no
        // getParentActivity(). Resolve the activity from the context instead.
        final android.app.Activity activity = AndroidUtilities.findActivity(getContext());
        if (activity == null || webViewContainer == null) {
            return;
        }

        // --- Tier 1: real multi-window overlay ---
        if (org.telegram.ui.ColgramFloatWindowManager.isSupported(activity)) {
            final org.telegram.ui.ColgramFloatWindowManager.FloatWindow floatWindow =
                    org.telegram.ui.ColgramFloatWindowManager.open(
                            activity, webViewContainer, "Mini App");
            if (floatWindow != null) {
                // The sheet must let go of the view: the window owns it now.
                dismiss(true, null);
                android.widget.Toast.makeText(activity,
                        "Мини-приложение закреплено как плавающее окно ("
                                + org.telegram.ui.ColgramFloatWindowManager.getWindowCount()
                                + " открыто)",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // --- Tier 2: system PiP, single window ---
        try {
            final int permission = org.telegram.messenger.pip.utils.PipUtils.checkPermissions(activity);
            if (permission != org.telegram.messenger.pip.utils.PipPermissions.PIP_GRANTED_PIP
                    && permission != org.telegram.messenger.pip.utils.PipPermissions.PIP_GRANTED_OVERLAY) {
                AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(activity,
                        "Разрешите «Поверх других окон» или «Картинку в картинке» в настройках системы",
                        android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            if (colgramPipSource != null) {
                colgramPipSource.destroy();
                colgramPipSource = null;
            }
            final android.view.View content = webViewContainer;
            colgramPipSource = new org.telegram.messenger.pip.PipSource.Builder(activity, colgramPipDelegate)
                    // Keyed per view instance: two windows for the SAME bot would otherwise
                    // share a tag and replace each other in the controller's source map.
                    .setTagPrefix("colgram-miniapp-" + botId + "-" + System.identityHashCode(content))
                    .setPriority(1)
                    .setContentView(content)
                    .setContentRatio(Math.max(1, content.getWidth()), Math.max(1, content.getHeight()))
                    .build();
            android.widget.Toast.makeText(activity,
                    "Открыто в системном окне (одно за раз). Дайте доступ «Поверх других окон» для нескольких",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
        }
    }

    private org.telegram.messenger.pip.PipSource colgramPipSource;

    /**
     * IPipSourceDelegate for the pinned mini-app.
     *
     * The interface has mandatory members (pipCreatePrimaryWindowViewBitmap and friends),
     * so every one of them has to exist - they are not default methods. The WebView is
     * re-parented into the PiP view, and a placeholder fills the original slot.
     */
    private final org.telegram.messenger.pip.source.IPipSourceDelegate colgramPipDelegate =
            new org.telegram.messenger.pip.source.IPipSourceDelegate() {
                @Override
                public android.graphics.Bitmap pipCreatePrimaryWindowViewBitmap() {
                    return null;
                }

                @Override
                public android.view.View pipCreatePictureInPictureView() {
                    return webViewContainer;
                }

                @Override
                public void pipHidePrimaryWindowView(Runnable firstFrameCallback) {
                    if (webViewContainer != null) {
                        webViewContainer.setVisibility(android.view.View.INVISIBLE);
                    }
                    if (firstFrameCallback != null) {
                        firstFrameCallback.run();
                    }
                }

                @Override
                public android.graphics.Bitmap pipCreatePictureInPictureViewBitmap() {
                    return null;
                }

                @Override
                public void pipShowPrimaryWindowView(Runnable firstFrameCallback) {
                    if (webViewContainer != null) {
                        webViewContainer.setVisibility(android.view.View.VISIBLE);
                    }
                    if (firstFrameCallback != null) {
                        firstFrameCallback.run();
                    }
                }
            };

    public void setFullscreen(boolean fullscreen, boolean animated) {"""
        patch_file(bot_sheet, impl_target, impl_inject, "BotWebViewSheet Pin MiniApp Implementation")

def download_official_binaries(repo_path):
    print("[*] Setting up precompiled official native libraries...")
    apk_url = "https://telegram.org/dl/android/apk"
    temp_apk = os.path.join(repo_path, "official_temp.apk")
    jni_libs_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "jniLibs")

    # 1. Disable externalNativeBuild and configure jniLibs unconditionally in TMessagesProj/build.gradle
    tmessages_gradle = os.path.join(repo_path, "TMessagesProj", "build.gradle")
    if os.path.exists(tmessages_gradle):
        with open(tmessages_gradle, "r", encoding="utf-8") as f:
            content = f.read()

        lines = content.split('\n')
        out_lines = []
        in_block = False
        brace_count = 0

        for line in lines:
            if 'externalNativeBuild {' in line:
                in_block = True
                brace_count = line.count('{') - line.count('}')
                continue
            if in_block:
                brace_count += line.count('{') - line.count('}')
                if brace_count <= 0:
                    in_block = False
                continue
            out_lines.append(line)

        gradle_content = '\n'.join(out_lines)
        gradle_content = gradle_content.replace(
            "sourceSets.main.jniLibs.srcDirs = ['./jni/']",
            "sourceSets.main.jniLibs.srcDirs = ['src/main/jniLibs']"
        )
        with open(tmessages_gradle, "w", encoding="utf-8") as f:
            f.write(gradle_content)
        print(" [+] Cleanly configured Gradle jniLibs and removed externalNativeBuild")

    # 2. Configure packagingOptions in all app and library modules to pickFirst on .so files
    for module in ["TMessagesProj", "TMessagesProj_AppStandalone"]:
        gradle_path = os.path.join(repo_path, module, "build.gradle")
        if os.path.exists(gradle_path):
            with open(gradle_path, "r", encoding="utf-8") as f:
                content = f.read()
            packaging_code = """
    packagingOptions {
        jniLibs {
            pickFirsts += ['**/*.so']
        }
    }
"""
            if "pickFirsts += ['**/*.so']" not in content:
                content = content.replace("android {", "android {" + packaging_code, 1)
                with open(gradle_path, "w", encoding="utf-8") as f:
                    f.write(content)
                print(f" [+] Added packagingOptions to {module}/build.gradle")

    # 3. Download official APK with retries
    has_so_files = os.path.exists(jni_libs_dir) and any(f.endswith('.so') for _, _, files in os.walk(jni_libs_dir) for f in files)
    if has_so_files:
        print(" [+] Precompiled native .so libraries already present in jniLibs, skipping download.")
        return

    success = False
    for attempt in range(4):
        try:
            print(f" -> Downloading official Telegram APK (attempt {attempt+1}/4)...")
            if os.path.exists(temp_apk):
                os.remove(temp_apk)

            # Try curl first
            curl_res = subprocess.run(
                ["curl", "-L", "--retry", "3", "--retry-delay", "2", "-s", "-o", temp_apk, apk_url],
                capture_output=True
            )
            if curl_res.returncode != 0 or not os.path.exists(temp_apk) or os.path.getsize(temp_apk) < 30 * 1024 * 1024:
                req = urllib.request.Request(apk_url, headers={'User-Agent': 'Mozilla/5.0'})
                with urllib.request.urlopen(req, timeout=120) as resp, open(temp_apk, 'wb') as out_file:
                    shutil.copyfileobj(resp, out_file)

            if os.path.exists(temp_apk) and os.path.getsize(temp_apk) > 30 * 1024 * 1024:
                with zipfile.ZipFile(temp_apk, 'r') as zip_ref:
                    if os.path.exists(jni_libs_dir):
                        shutil.rmtree(jni_libs_dir)
                    os.makedirs(jni_libs_dir, exist_ok=True)

                    for file_info in zip_ref.infolist():
                        if file_info.filename.startswith("lib/"):
                            if "liblanguage_id" in file_info.filename or not file_info.filename.endswith(".so"):
                                continue
                            rel_path = file_info.filename[len("lib/"):]
                            target_file = os.path.join(jni_libs_dir, rel_path)
                            os.makedirs(os.path.dirname(target_file), exist_ok=True)
                            with zip_ref.open(file_info) as src, open(target_file, 'wb') as dst:
                                shutil.copyfileobj(src, dst)
                    print(" [+] Successfully extracted prebuilt .so libraries to jniLibs!")
                    success = True
                    break
            else:
                print(f" [!] Attempt {attempt+1} produced small/invalid file.")
        except Exception as e:
            print(f" [!] Attempt {attempt+1} error: {e}")
        finally:
            if os.path.exists(temp_apk):
                os.remove(temp_apk)

    if not success:
        print(" [!] FATAL: Failed to download official Telegram APK for native .so extraction.")
        sys.exit(1)

def inject_core(repo_path, core_source_dir):
    print("[*] Injecting colgram-core module into project...")
    target_core_dir = os.path.join(repo_path, "colgram-core")

    # Sync file-by-file rather than wiping the destination tree. A bulk rmtree of the
    # module both trips delete-guard hooks and destroys anything the build generated
    # into the tree that we would immediately have to rebuild.
    #
    # The copy is deliberately MIRRORED, not merely additive: a plain additive copy can
    # never propagate a *deletion*. If a file is removed from colgram-core/ in the repo
    # (e.g. a test artifact that had leaked into assets/), its stale twin in
    # Telegram-Src/colgram-core/ survives forever and keeps getting packaged into the
    # APK - "clean" does not help, because the build tree is the source of truth for
    # Gradle and the file genuinely is still on disk. So: after copying, walk the
    # destination and delete anything under a source-managed directory that the source
    # no longer has.
    #
    # Scope note: pruning is confined to directories this module owns. The root of
    # target_core_dir is skipped outright (see below), so Gradle's build/, .gradle/ and
    # any other root-level output can never be touched. Deeper inside the tree we are
    # definitionally walking a source-managed directory, so removing an entry there is
    # safe - and removing a stale *directory* (not just stale files) matters, because a
    # runtime folder such as assets/antispam/storage/ otherwise survives a file-only
    # sweep and keeps shipping its contents (bot.db) into the APK.
    if not os.path.isdir(core_source_dir):
        print(f" [!] FATAL: core source dir not found: {core_source_dir}")
        return

    # ---- Pass 1: enumerate exactly what the source owns ----------------------------
    # Build the authoritative set of relative paths present in the source. Doing this
    # BEFORE touching the destination is what makes the prune correct: we can then ask
    # "is this destination path part of the source tree?" as a set lookup instead of
    # re-deriving ownership directory-by-directory during the walk (which gets it wrong
    # when a shallow directory is visited before its deeper siblings are known).
    src_files = set()
    src_dirs = set()
    for root, dirs, files in os.walk(core_source_dir):
        rel_root = os.path.relpath(root, core_source_dir)
        if rel_root == ".":
            rel_root = ""
        for d in dirs:
            src_dirs.add(os.path.join(rel_root, d) if rel_root else d)
        for f in files:
            if f.endswith(".pyc"):
                continue
            src_files.add(os.path.join(rel_root, f) if rel_root else f)

    # ---- Pass 2: copy the source in -------------------------------------------------
    copied = 0
    for rel in sorted(src_files):
        src_file = os.path.join(core_source_dir, rel)
        dst_file = os.path.join(target_core_dir, rel)
        os.makedirs(os.path.dirname(dst_file), exist_ok=True)
        shutil.copyfile(src_file, dst_file)
        copied += 1

    # ---- Pass 3: prune destination entries the source does not own ------------------
    # Depth-limited to paths that mirror a source directory. Gradle's own output lives
    # at the module root (build/, .gradle/, local.properties) and is never descended
    # into, because the scan starts only inside directories that exist in src_dirs.
    #
    # A stale *directory* (not just a stale file) must be removable: a runtime folder
    # such as assets/antispam/storage/ would otherwise survive a file-only sweep and
    # keep shipping its contents (bot.db) into the APK.
    removed = []
    protected_roots = {"build", ".gradle", ".idea", "__pycache__"}
    scan_roots = {""} | src_dirs
    for rel_dir in sorted(scan_roots, key=lambda p: p.count(os.sep)):
        abs_dir = os.path.join(target_core_dir, rel_dir) if rel_dir else target_core_dir
        if not os.path.isdir(abs_dir):
            continue
        for entry in os.listdir(abs_dir):
            if entry.startswith(".") or entry in protected_roots:
                continue
            rel_entry = os.path.join(rel_dir, entry) if rel_dir else entry
            abs_entry = os.path.join(abs_dir, entry)
            if os.path.isdir(abs_entry):
                if rel_entry in src_dirs:
                    continue  # still owned by the source, keep
                # Not owned by the source. Only prune it when it sits under a directory
                # the source actually manages; a dir at the module root is off-limits
                # because that is where Gradle and local tooling keep their state.
                if rel_dir == "":
                    continue
                shutil.rmtree(abs_entry, ignore_errors=True)
            elif os.path.isfile(abs_entry):
                if rel_entry in src_files:
                    continue
                # Same rule for files: never delete anything at the module root that the
                # source does not have. local.properties (sdk.dir) lives there and is
                # local build configuration, not module source - removing it breaks the
                # build outright. .pyc anywhere is also always preserved.
                if rel_entry.endswith(".pyc"):
                    continue
                if rel_dir == "":
                    continue
                os.remove(abs_entry)
            else:
                continue
            removed.append(rel_entry)

    for rel in sorted(removed):
        print(f" [-] pruned stale path: {rel}")
    if removed:
        print(f" [+] colgram-core: pruned {len(removed)} stale path(s) deleted from source")
    print(f" [+] colgram-core synced to {target_core_dir} ({copied} files)")

    # Add module to settings.gradle
    settings_gradle = os.path.join(repo_path, "settings.gradle")
    if os.path.exists(settings_gradle):
        with open(settings_gradle, "r", encoding="utf-8") as f:
            content = f.read()
        if "':colgram-core'" not in content:
            with open(settings_gradle, "a", encoding="utf-8") as f:
                f.write("\ninclude ':colgram-core'\n")
            print(" [+] Injected ':colgram-core' into settings.gradle")

    # Add dependency to TMessagesProj/build.gradle
    tmessages_gradle = os.path.join(repo_path, "TMessagesProj", "build.gradle")
    if os.path.exists(tmessages_gradle):
        with open(tmessages_gradle, "r", encoding="utf-8") as f:
            content = f.read()
        if "project(':colgram-core')" not in content:
            content = content.replace(
                "dependencies {",
                "dependencies {\n    implementation project(':colgram-core')"
            )
            with open(tmessages_gradle, "w", encoding="utf-8") as f:
                f.write(content)
            print(" [+] Added colgram-core dependency to TMessagesProj/build.gradle")

def configure_chaquopy_build(repo_path):
    print("[*] Configuring Chaquopy CPython plugin in root and application build.gradle...")

    # 1. Add Chaquopy classpath to root build.gradle
    root_gradle = os.path.join(repo_path, "build.gradle")
    if os.path.exists(root_gradle):
        with open(root_gradle, "r", encoding="utf-8") as f:
            content = f.read()

        if "com.chaquo.python:gradle" not in content:
            if "buildscript {" in content:
                content = content.replace(
                    "dependencies {",
                    "dependencies {\n        classpath 'com.chaquo.python:gradle:15.0.1'",
                    1
                )
                if "mavenCentral()" not in content:
                    content = content.replace(
                        "repositories {",
                        "repositories {\n        mavenCentral()",
                        1
                    )
            else:
                buildscript_block = """buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.chaquo.python:gradle:15.0.1'
    }
}
"""
                content = buildscript_block + content

            with open(root_gradle, "w", encoding="utf-8") as f:
                f.write(content)
            print(" [+] Configured Chaquopy classpath in root build.gradle")

    # 2. Apply Chaquopy plugin to TMessagesProj_AppStandalone (the application module)
    standalone_gradle = os.path.join(repo_path, "TMessagesProj_AppStandalone", "build.gradle")
    if os.path.exists(standalone_gradle):
        with open(standalone_gradle, "r", encoding="utf-8") as f:
            content = f.read()

        if "com.chaquo.python" not in content:
            # Apply plugin after android application plugin
            if "apply plugin: 'com.android.application'" in content:
                content = content.replace(
                    "apply plugin: 'com.android.application'",
                    "apply plugin: 'com.android.application'\napply plugin: 'com.chaquo.python'"
                )
            elif "id 'com.android.application'" in content or "id(\"com.android.application\")" in content:
                content = content.replace(
                    "id 'com.android.application'",
                    "id 'com.android.application'\n    id 'com.chaquo.python'"
                )

            # Add chaquopy config block before dependencies block
            chaquopy_block = """
chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

"""
            if "dependencies {" in content:
                content = content.replace("dependencies {", chaquopy_block + "dependencies {", 1)

            with open(standalone_gradle, "w", encoding="utf-8") as f:
                f.write(content)
            print(" [+] Applied Chaquopy plugin to TMessagesProj_AppStandalone/build.gradle")

    # 3. Ensure minSdkVersion 24 in TMessagesProj and TMessagesProj_AppStandalone
    for mod in ["TMessagesProj", "TMessagesProj_AppStandalone"]:
        mod_gradle = os.path.join(repo_path, mod, "build.gradle")
        if os.path.exists(mod_gradle):
            with open(mod_gradle, "r", encoding="utf-8") as f:
                m_content = f.read()
            m_content = re.sub(r'minSdkVersion\s+\d+', 'minSdkVersion 24', m_content)
            with open(mod_gradle, "w", encoding="utf-8") as f:
                f.write(m_content)
            print(f" [+] Ensured minSdkVersion 24 in {mod}/build.gradle")

    configure_build_performance(repo_path)

def configure_build_performance(repo_path):
    """
    Make the build both faster AND survivable on a 16 GB developer machine.

    ⚠️ FIRST, THE MEMORY BUDGET — this is what actually causes "the build hangs".

    A Gradle daemon that dies from OOM produces NO error output: the JVM process
    is killed by the OS, the daemon log just stops mid-sentence, and the CLI
    client waits forever on a dead socket. It looks like a hang and is routinely
    misdiagnosed as "CI is stuck". On this machine (16 GB physical RAM, plus an
    Android emulator holding ~5 GB) the upstream `-Xmx8g -XX:MaxMetaspaceSize=1g`
    plus parallel workers overcommits badly. So:
        org.gradle.jvmargs = -Xmx4g -XX:MaxMetaspaceSize=768m
        org.gradle.parallel = false
        org.gradle.workers.max = 2
    Serial is not a regression here — it is faster than being OOM-killed and
    having to restart from cold.

    Then the actual wall-clock wins, in order of impact:

      1. android.enableJetifier=true  -> rewrites the bytecode of EVERY resolved
         dependency on every build. Telegram's dependency graph is fully AndroidX
         (verified: no com.android.support:* entries anywhere in the build files),
         so Jetifier is pure overhead. The only android.support.* symbols in the
         tree are android.support.v4.media.*, which AndroidX still ships under
         that legacy package name on purpose and never needed rewriting.

      2. org.gradle.configuration-cache -> left OFF. This one was measured, not
         guessed: enabling it fails the application module outright, because
         Chaquopy's com.chaquo.python.OutputDirTask (generate<Flavor>PythonJniLibs)
         captures non-serializable Gradle internals (Project, DependencyHandler,
         SourceSetContainer, ClassLoader, Configuration) and Gradle discards the
         entry with "Configuration cache entry discarded with 442 problems". It IS
         clean for pure-Java modules such as :colgram-core, but the app module is
         the one that dominates build time, so a global switch buys nothing. The
         patcher therefore keeps it off and relies on the build cache plus
         incremental compilation instead.

      3. org.gradle.caching -> ON. Reuses task outputs across builds.

      4. Do NOT use --rerun-tasks when measuring. It forces every task to execute
         and defeats both caches; the old "32 minutes" figure was measured that
         way and is not representative of normal iteration.

    🔴 THE OTHER "HANG" — Gradle's transform-output cleanup on Windows.

    This one is NOT memory and NOT Chaquopy. Confirmed by live jstack on a frozen
    daemon (cpu counter identical across 10s samples):

        "included builds" ... RUNNABLE
          at sun.nio.fs.WindowsNativeDispatcher.DeleteFile0(Native Method)
          at ...DefaultDeleter.deleteRecursively(DefaultDeleter.java:134)   <- x7
          at ...RemovePreviousOutputsStep.cleanupExclusivelyOwnedOutputs(...)
          at ...AbstractTransformExecution.visitOutputs(...)

    Mechanism: with `org.gradle.caching=true`, every artifact transform goes
    through BuildCacheStep -> RemovePreviousOutputsStep, which calls
    `ensureEmptyDirectory()` on the transform's previous output. Android's
    `bundleLibRuntimeToDirRelease` transform emits ONE .dex PER CLASS —
    measured 6,342 files / 49 MB in `TMessagesProj/build/.transforms/<hash>/`.
    Gradle deletes those with a single-threaded native recursion, and on Windows
    (NTFS + Defender filters) that call can block for tens of minutes or never
    return. The daemon sits at 0% CPU with a live pid and empty log tail, which
    reads exactly like "the build is stuck".

    Fixes applied below:
      - the deletion-speed settings below are what we can control from here;
      - when it DOES wedge, clear `.transforms/` while NO daemon is running.

    ⚠️ When clearing `.transforms/` manually, do NOT use `rm -rf` — it timed out
    after 2 minutes on the 6,342-file dir. Use Python's shutil.rmtree, which did
    the same job in 6.6 seconds:

        python -c "import shutil;shutil.rmtree(r'...\\build\\.transforms\\<hash>')"

      5. android.enableR8.fullMode / optimizedResourceShrinking stay ON: they
         cost time but change the shipped artifact, so they are left alone.

    Only additive/idempotent edits are made; an existing user override wins.
    """
    print("[*] Applying Gradle build-performance settings...")
    gradle_props = os.path.join(repo_path, "gradle.properties")
    if not os.path.exists(gradle_props):
        return

    with open(gradle_props, "r", encoding="utf-8") as f:
        props = f.read()

    # key -> desired value. Comments are added once with the block below.
    desired = {
        # Jetifier: no legacy support libraries in this dependency graph.
        "android.enableJetifier": "false",
        # Reuse task outputs across builds instead of redoing them.
        "org.gradle.caching": "true",
        # ⚠️ PARALLEL MUST STAY OFF on a 16 GB machine.
        # It was briefly set to "true" here as a speed-up. That made the daemon
        # get OOM-killed mid-build (no exception, no "BUILD FAILED" — the process
        # simply vanishes and the CLI client hangs forever on a dead socket,
        # which is exactly the "build hangs" symptom users report). Measured:
        # 16 GB physical RAM, `org.gradle.jvmargs=-Xmx8g` + 1g metaspace, plus an
        # Android emulator eating ~5 GB. Serial execution is both faster and
        # survivable here. Do not turn this back on without measuring RSS.
        "org.gradle.parallel": "false",
        # Keep a warm daemon so the JVM + configuration survive between builds.
        "org.gradle.daemon": "true",
        # Configuration cache stays OFF: Chaquopy's OutputDirTask on the
        # application module is not serializable and makes Gradle discard the
        # entry (442 problems -> build failure). Measured, not assumed.
        "org.gradle.configuration-cache": "false",
        # 🔴 MUST STAY false. This is NOT just a "file-locking log" - it is Gradle's
        # file-system watch, i.e. a daemon-lifetime cache of the filesystem that Gradle
        # consults for UP-TO-DATE checks. On this Windows/MSYS setup that cache goes
        # stale, and the failure is silent and severe:
        #
        #   * Gradle reports `Task :colgram-core:compileReleaseJavaWithJavac UP-TO-DATE`
        #     IMMEDIATELY AFTER a real source edit, so the fix never compiles.
        #   * Worse, it reported UP-TO-DATE for that task while `colgram-core/build`
        #     DID NOT EXIST AT ALL - it believed outputs existed that were not on disk.
        #
        # Observed cost: a one-line reflection fix in ColgramBotSync.java was edited,
        # built, and shipped; the APK carried the old bytecode and the runtime error was
        # unchanged. Nothing in the build log hinted at it.
        #
        # The skill used to blame CRLF normalisation for this. It is not CRLF - it is
        # this property. Do not re-enable it to save I/O; a build that silently ships
        # stale code is not fast, it is wrong.
        "org.gradle.vfs.watch": "false",
        # 🔴 THE BIG ONE for build time on Windows (see the transform note above).
        # By default AGP dexes library classes PER CLASS FILE and writes one .dex
        # per class into `build/.transforms/<hash>/transformed/bundleLibRuntimeToDirRelease/`.
        # For :TMessagesProj that measured **8,852 files / 136 directories / 91 MB**,
        # produced through `DexFilePerClassFileConsumer.DirectoryConsumer` ->
        # `Files.createDirectories` -> a native `CreateDirectory0` syscall each.
        # A live jstack caught the worker wedged there with cpu=98796ms.
        #
        # `android.useFullClasspathForDexingTransform=true` switches D8 to dex the
        # full classpath in one pass and emit a single dex archive, instead of tens
        # of thousands of tiny files. This is the documented remedy for slow dexing
        # transforms; it is the difference between a build that crawls for 20+
        # minutes writing per-class files and one that just dexes.
        "android.useFullClasspathForDexingTransform": "true",
        # Cap the heap so the daemon is not the thing that kills the machine.
        # Upstream's -Xmx8g is more than this box can back on top of an emulator.
        "org.gradle.jvmargs": "-Xmx4g -XX:MaxMetaspaceSize=768m",
        # Keep the parallelism inside the daemon capped too.
        "org.gradle.workers.max": "2",
        "org.gradle.tooling.parallel": "false",
    }

    lines = props.splitlines()
    out = []
    seen = set()
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("#") or "=" not in stripped:
            out.append(line)
            continue
        key = stripped.split("=", 1)[0].strip()
        if key in desired:
            out.append(f"{key}={desired[key]}")
            seen.add(key)
        else:
            out.append(line)

    missing = [k for k in desired if k not in seen]
    if missing:
        out.append("")
        out.append("# --- Colgram build performance (see scripts/apply-patches.py) ---")
        for k in missing:
            out.append(f"{k}={desired[k]}")

    new_props = "\n".join(out) + "\n"

    # Remove the bogus `org.gradle.java.compile-incremental` property if an older
    # revision of this patcher left it behind. It does NOT exist in Gradle 8.13 —
    # verified by scanning every jar in the distribution for the byte string
    # (0 hits), while a real property like `vfs.watch` shows up in 3 classes.
    # Java incremental compilation is enabled by default and has no user-facing
    # property; setting a fake one is silently ignored, which makes it dangerous
    # to leave around: it looks like a speed-up in `gradle.properties` while
    # doing exactly nothing.
    cleaned = []
    for line in new_props.splitlines():
        if line.strip().startswith("org.gradle.java.compile-incremental"):
            print(" [-] Removed non-existent property org.gradle.java.compile-incremental")
            continue
        cleaned.append(line)
    new_props = "\n".join(cleaned) + "\n"

    if new_props != props:
        with open(gradle_props, "w", encoding="utf-8") as f:
            f.write(new_props)
        print(" [+] Gradle: Jetifier off, build cache + incremental javac on")
    else:
        print(" [=] Gradle build-performance settings already present")

def clone_required_submodules(repo_path):
    print("[*] Checking out required submodules for Gradle (media & jlatexmath)...")
    media_dir = os.path.join(repo_path, "TMessagesProj_Modules", "media")
    core_settings = os.path.join(media_dir, "core_settings.gradle")
    
    if not os.path.exists(core_settings):
        shutil.rmtree(media_dir, ignore_errors=True)
        print(" -> Cloning media submodule at pinned commit c822f1f33d30591fdbbf3919662be258f7cfbfc6...")
        subprocess.run(["git", "clone", "https://github.com/Arseny271/media.git", media_dir], check=True)
        subprocess.run(["git", "checkout", "c822f1f33d30591fdbbf3919662be258f7cfbfc6"], cwd=media_dir, check=True)
        print(" [+] Successfully checked out media submodule with core_settings.gradle")

    jlatex_dir = os.path.join(repo_path, "TMessagesProj", "lib", "jlatexmath")
    if not os.path.exists(os.path.join(jlatex_dir, "jlatexmath")):
        shutil.rmtree(jlatex_dir, ignore_errors=True)
        print(" -> Cloning jlatexmath submodule at pinned commit 919e50b2f6f64b04b712cdb13d558ff9ecf9c8ed...")
        subprocess.run(["git", "clone", "https://github.com/dkaraush/jlatexmath-android.git", jlatex_dir], check=True)
        subprocess.run(["git", "checkout", "919e50b2f6f64b04b712cdb13d558ff9ecf9c8ed"], cwd=jlatex_dir, check=True)
        print(" [+] Successfully checked out jlatexmath submodule")

def stamp_build_version(repo_path):
    """Append a Colgram build id to APP_VERSION_NAME so a build can be identified on a phone.

    Every Colgram APK so far has shipped the same versionName (12.10.3) and versionCode, so
    two builds that differ by dozens of fixes are indistinguishable in Settings -> About and
    in `dumpsys package`. That cost a full investigation cycle: half of a bug report turned
    out to describe a build that already contained the fixes being re-attempted.

    The stamp is stripped before being re-applied, so re-running the patcher converges
    instead of appending a second suffix on every pass.
    """
    props = os.path.join(repo_path, "gradle.properties")
    if not os.path.exists(props):
        print(" [!] gradle.properties not found - build stamp NOT applied")
        return
    stamp = os.environ.get("COLGRAM_BUILD_STAMP", "").strip()
    if not stamp:
        import datetime
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M")
    with open(props, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()
    m = re.search(r"^APP_VERSION_NAME=(.+)$", content, re.M)
    if not m:
        print(" [!] APP_VERSION_NAME not found - build stamp NOT applied")
        return
    base = m.group(1).split("-colgram.", 1)[0].strip()
    updated = re.sub(r"^APP_VERSION_NAME=.+$", f"APP_VERSION_NAME={base}-colgram.{stamp}",
                     content, count=1, flags=re.M)
    with open(props, "w", encoding="utf-8", newline="") as f:
        f.write(updated)
    print(f" [+] Build stamp applied: {base}-colgram.{stamp}")


def configure_package_and_branding(repo_path):
    print("[*] Configuring Colgram package identity and branding...")
    
    # 1. Update gradle.properties with unique package name
    gradle_props = os.path.join(repo_path, "gradle.properties")
    if os.path.exists(gradle_props):
        with open(gradle_props, "r", encoding="utf-8") as f:
            props = f.read()
        props = re.sub(r"APP_PACKAGE\s*=\s*.*", "APP_PACKAGE=org.colgram.messenger", props)
        with open(gradle_props, "w", encoding="utf-8") as f:
            f.write(props)
        print(" [+] Set APP_PACKAGE=org.colgram.messenger in gradle.properties")

    # 2. Update TMessagesProj_AppStandalone/build.gradle
    standalone_gradle = os.path.join(repo_path, "TMessagesProj_AppStandalone", "build.gradle")
    if os.path.exists(standalone_gradle):
        with open(standalone_gradle, "r", encoding="utf-8") as f:
            content = f.read()
        content = content.replace("defaultConfig.applicationId = APP_PACKAGE", 'defaultConfig.applicationId = "org.colgram.messenger"')
        with open(standalone_gradle, "w", encoding="utf-8") as f:
            f.write(content)
        print(" [+] Set applicationId = org.colgram.messenger in TMessagesProj_AppStandalone/build.gradle")

    # 3. Fix TMessagesProj_AppStandalone/src/main/AndroidManifest.xml (Icon, RoundIcon, Label)
    standalone_manifest = os.path.join(repo_path, "TMessagesProj_AppStandalone", "src", "main", "AndroidManifest.xml")
    if os.path.exists(standalone_manifest):
        with open(standalone_manifest, "r", encoding="utf-8") as f:
            m_content = f.read()
        old_app_tag = '<application android:name="org.telegram.messenger.ApplicationLoaderImpl" tools:replace="name">'
        new_app_tag = '<application android:name="org.telegram.messenger.ApplicationLoaderImpl" android:icon="@mipmap/ic_launcher_sa" android:roundIcon="@mipmap/ic_launcher_sa" android:label="Colgram" tools:replace="name,icon,roundIcon,label">'
        if old_app_tag in m_content:
            m_content = m_content.replace(old_app_tag, new_app_tag)
            with open(standalone_manifest, "w", encoding="utf-8") as f:
                f.write(m_content)
            print(" [+] Injected icon and label into TMessagesProj_AppStandalone AndroidManifest.xml")

    # 4. Fix TMessagesProj/src/main/AndroidManifest.xml (DefaultIcon & Application)
    main_manifest = os.path.join(repo_path, "TMessagesProj", "src", "main", "AndroidManifest.xml")
    if os.path.exists(main_manifest):
        with open(main_manifest, "r", encoding="utf-8") as f:
            m_content = f.read()
        
        # Set icon on DefaultIcon activity-alias
        old_alias = """        <activity-alias
            android:enabled="true"
            android:name="org.telegram.messenger.DefaultIcon"
            android:targetActivity="org.telegram.ui.LaunchActivity"
            android:exported="true">"""
        new_alias = """        <activity-alias
            android:enabled="true"
            android:name="org.telegram.messenger.DefaultIcon"
            android:targetActivity="org.telegram.ui.LaunchActivity"
            android:icon="@mipmap/ic_launcher_sa"
            android:roundIcon="@mipmap/ic_launcher_sa"
            android:label="Colgram"
            android:exported="true">"""
        if old_alias in m_content:
            m_content = m_content.replace(old_alias, new_alias)

        # Set icon on <application
        #
        # IDEMPOTENCY: this replace has no natural "already applied" guard, unlike
        # patch_file(). Running apply-patches.py more than once used to append the three
        # attributes again every time — 6 runs produced 18 duplicate attributes and an
        # AndroidManifest.xml that the manifest merger refuses to parse
        # ("duplicate attribute"). Guard on the marker we are about to insert, and also
        # collapse any duplicates a previous run already created.
        app_marker = 'android:name="org.telegram.messenger.ApplicationLoader"'
        branding_block = (
            '\n        android:icon="@mipmap/ic_launcher"'
            '\n        android:roundIcon="@mipmap/ic_launcher_round"'
            '\n        android:label="Colgram"'
        )
        if 'android:label="Colgram"' not in m_content:
            m_content = m_content.replace(
                app_marker,
                app_marker + branding_block,
                1
            )
        else:
            # Repair a manifest damaged by earlier runs: keep exactly one copy.
            dup = re.compile(
                r'(\s*android:icon="@mipmap/ic_launcher"\s*'
                r'android:roundIcon="@mipmap/ic_launcher_round"\s*'
                r'android:label="Colgram")+'
            )
            m_content = dup.sub(branding_block, m_content, count=1)
            print(" [=] Application branding already present (deduplicated)")

        # Strip phone, contacts, location, notifications, and account permissions from AndroidManifest.xml for full user privacy
        for perm in [
            "READ_PHONE_STATE", "READ_PHONE_NUMBERS",
            "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS",
            "MANAGE_ACCOUNTS", "READ_PROFILE", "MANAGE_OWN_CALLS",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "POST_NOTIFICATIONS"
        ]:
            m_content = m_content.replace(
                f'<uses-permission android:name="android.permission.{perm}" />',
                f'<!-- stripped {perm} for Colgram privacy -->'
            )
            m_content = m_content.replace(
                f'<uses-permission android:name="android.permission.{perm}"/>',
                f'<!-- stripped {perm} for Colgram privacy -->'
            )

        # ---------------------------------------------------------------------------
        # Background sync: foreground service + boot receiver.
        #
        # The fork has no working push (FCM is blocked for this package), so without a foreground
        # service the process dies the moment the user leaves the app and bot chats stop syncing.
        # That is the root of three separate reports: chats empty until a restart, a full reload on
        # re-entry, and no sync at all after a phone reboot.
        #
        # Guarded on our own service class name, and duplicates collapsed, because this file is
        # written with a plain open()/write() rather than patch_file() and therefore has no generic
        # idempotency guard of its own - the same trap that once produced 18 duplicate attributes
        # on <application>.
        # ---------------------------------------------------------------------------
        COLGRAM_FG_PERMISSIONS = [
            # A foreground service needs both the base permission and, from API 34, a typed one.
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            # To come back after a reboot.
            "android.permission.RECEIVE_BOOT_COMPLETED",
            # Best-effort: asks to be exempt from Doze so the heartbeat is not deferred.
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        ]
        for perm in COLGRAM_FG_PERMISSIONS:
            if perm not in m_content:
                m_content = m_content.replace(
                    '    <uses-permission android:name="android.permission.INTERNET" />',
                    '    <uses-permission android:name="android.permission.INTERNET" />\n'
                    '    <uses-permission android:name="%s" />' % perm,
                    1)

        # Collapse duplicates from any earlier run before adding ours.
        for perm in COLGRAM_FG_PERMISSIONS:
            decl = '    <uses-permission android:name="%s" />\n' % perm
            while m_content.count(decl) > 1:
                m_content = m_content.replace(decl + decl, decl, 1)

        if "org.colgram.core.ColgramForegroundService" not in m_content:
            bg_block = (
                '\n'
                '        <!-- Colgram: keeps the process alive so bot chats keep syncing without push.\n'
                '             dataSync is the declared type; API 34+ requires it and the matching\n'
                '             FOREGROUND_SERVICE_DATA_SYNC permission is added above. -->\n'
                '        <service\n'
                '            android:name="org.colgram.core.ColgramForegroundService"\n'
                '            android:enabled="true"\n'
                '            android:exported="false"\n'
                '            android:foregroundServiceType="dataSync"\n'
                '            android:stopWithTask="false" />\n'
                '\n'
                '        <!-- Colgram: bring the sync back after a reboot or an in-place update. -->\n'
                '        <receiver\n'
                '            android:name="org.colgram.core.ColgramBootReceiver"\n'
                '            android:enabled="true"\n'
                '            android:exported="true"\n'
                '            android:directBootAware="false">\n'
                '            <intent-filter android:priority="1000">\n'
                '                <action android:name="android.intent.action.BOOT_COMPLETED" />\n'
                '                <action android:name="android.intent.action.QUICKBOOT_POWERON" />\n'
                '                <action android:name="com.htc.intent.action.QUICKBOOT_POWERON" />\n'
                '                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />\n'
                '            </intent-filter>\n'
                '        </receiver>\n'
                '\n'
                '        <!-- Colgram: the boot broadcast cannot start a foreground service on\n'
                '             Android 12+, but a running job can. This is the bridge. BIND_JOB_SERVICE\n'
                '             is mandatory or the system refuses to start the job at all. -->\n'
                '        <service\n'
                '            android:name="org.colgram.core.ColgramBootJobService"\n'
                '            android:enabled="true"\n'
                '            android:exported="false"\n'
                '            android:permission="android.permission.BIND_JOB_SERVICE" />\n'
            )
            if '    </application>' in m_content:
                m_content = m_content.replace('    </application>', bg_block + '    </application>', 1)
                print(" [+] Injected Colgram foreground service + boot receiver into AndroidManifest.xml")
            else:
                print(" [!] FATAL: </application> not found - background sync NOT registered")
        elif "org.colgram.core.ColgramBootJobService" not in m_content:
            # The guard above keys on ColgramForegroundService, which an already-patched tree
            # contains — so a later addition to bg_block would never reach that tree, and the
            # boot bridge below would be missing from every build except a fresh clone. This
            # pass brings an existing tree up to the same manifest.
            job_block = (
                '\n'
                '        <!-- Colgram: the boot broadcast cannot start a foreground service on\n'
                '             Android 12+, but a running job can. This is the bridge. -->\n'
                '        <service\n'
                '            android:name="org.colgram.core.ColgramBootJobService"\n'
                '            android:enabled="true"\n'
                '            android:exported="false"\n'
                '            android:permission="android.permission.BIND_JOB_SERVICE" />\n'
            )
            if '    </application>' in m_content:
                m_content = m_content.replace('    </application>', job_block + '    </application>', 1)
                print(" [+] Added Colgram boot bridge JobService to AndroidManifest.xml")
            else:
                print(" [!] FATAL: </application> not found - boot bridge NOT registered")

        # Firebase Installations is still in the build. Analytics and Crashlytics were removed,
        # but FirebaseInitProvider is contributed by the com.google.gms.google-services plugin
        # during manifest merging — not by the source manifest — so it survived the strip. On
        # device it logs "FirebaseApp initialization successful" and then POSTS this package
        # name to googleapis.com on every launch, over a DIRECT connection that bypasses any
        # proxy. Google answers 403 because the API key blocks this app, so no data lands, but
        # the beacon itself announces "Colgram is installed on this device" to Google each time
        # the app starts, which is incompatible with what the README promises.
        # Push is already dead for the same 403 reason, so removing it costs nothing.
        if "com.google.firebase.provider.FirebaseInitProvider" not in m_content:
            fb_block = (
                '\n'
                '        <!-- Colgram: remove the Firebase Installations beacon. It is merged in by\n'
                '             the google-services plugin, not declared here, and it phones the package\n'
                '             name to Google on every launch over an unproxied connection. -->\n'
                '        <provider\n'
                '            android:name="com.google.firebase.provider.FirebaseInitProvider"\n'
                '            android:authorities="${applicationId}.firebaseinitprovider"\n'
                '            tools:node="remove" />\n'
            )
            if '    </application>' in m_content:
                m_content = m_content.replace('    </application>', fb_block + '    </application>', 1)
                print(" [+] Removed FirebaseInitProvider beacon from AndroidManifest.xml")
            else:
                print(" [!] FATAL: </application> not found - Firebase beacon NOT removed")

        with open(main_manifest, "w", encoding="utf-8") as f:
            f.write(m_content)
        print(" [+] Injected icon and label, stripped aggressive permissions in TMessagesProj AndroidManifest.xml")

    # 5. Patch google-services.json so GoogleServices plugin finds org.colgram.messenger
    import json
    import copy
    for root, _, files in os.walk(repo_path):
        for f in files:
            if f == "google-services.json":
                path = os.path.join(root, f)
                try:
                    with open(path, "r", encoding="utf-8") as jf:
                        data = json.load(jf)
                    clients = data.get("client", [])
                    has_colgram = any(c.get("client_info", {}).get("android_client_info", {}).get("package_name") == "org.colgram.messenger" for c in clients)
                    if not has_colgram and clients:
                        new_client = copy.deepcopy(clients[0])
                        new_client["client_info"]["android_client_info"]["package_name"] = "org.colgram.messenger"
                        clients.append(new_client)
                        data["client"] = clients
                        with open(path, "w", encoding="utf-8") as jf:
                            json.dump(data, jf, indent=2)
                        print(f" [+] Added org.colgram.messenger to {path}")
                except Exception as e:
                    print(f" [!] Error patching {path}: {e}")

def apply_custom_app_icon(repo_path, source_icon_path):
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    prebuilt_icons_dir = os.path.join(root_dir, "assets", "icons")

    target_dirs = [
        os.path.join(repo_path, "TMessagesProj", "src", "main", "res"),
        os.path.join(repo_path, "TMessagesProj_AppStandalone", "src", "main", "res")
    ]

    # 1. Copy pre-generated custom Colgram avatar icons across all mipmap densities
    if os.path.exists(prebuilt_icons_dir):
        print("[*] Copying pre-generated custom Colgram avatar icons...")
        for density_name in os.listdir(prebuilt_icons_dir):
            src_density = os.path.join(prebuilt_icons_dir, density_name)
            if not os.path.isdir(src_density):
                continue
            for res_dir in target_dirs:
                if not os.path.exists(res_dir):
                    continue
                dest_density = os.path.join(res_dir, density_name)
                os.makedirs(dest_density, exist_ok=True)
                for file_name in os.listdir(src_density):
                    shutil.copy2(os.path.join(src_density, file_name), os.path.join(dest_density, file_name))
        print(" [+] Custom Colgram avatar successfully applied across all mipmap densities!")

    # 1b. Overwrite Telegram's ALTERNATE launcher icons (icon_2..icon_6).
    #
    # Telegram ships six selectable app icons and lets the user pick one in settings.
    # The branding pass only recoloured ic_launcher and icon_2, so icons 3-6 stayed the
    # stock Telegram art — that is the "old icon shows up in some places" bug: the
    # launcher already had the right icon, but the in-app icon picker, the recents
    # thumbnail and any previously-selected alternate icon still rendered the old one.
    #
    # Generating all of them from the branded master means no stock art survives.
    master_icon = os.path.join(root_dir, "assets", "app_icon.png")
    if os.path.exists(master_icon):
        try:
            from PIL import Image
            have_pil = True
        except Exception:
            have_pil = False

        if have_pil:
            print("[*] Regenerating alternate launcher icons (icon_2..icon_6)...")
            # Standard mipmap sizes in px for a 48dp launcher icon.
            density_sizes = {
                "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
                "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
            }
            master = Image.open(master_icon).convert("RGBA")
            for res_dir in target_dirs:
                if not os.path.exists(res_dir):
                    continue
                for density_name, px in density_sizes.items():
                    dest_density = os.path.join(res_dir, density_name)
                    if not os.path.isdir(dest_density):
                        continue
                    square = master.resize((px, px), Image.LANCZOS)
                    # Round variant: same art, circular alpha mask.
                    mask = Image.new("L", (px, px), 0)
                    from PIL import ImageDraw
                    ImageDraw.Draw(mask).ellipse((0, 0, px - 1, px - 1), fill=255)
                    round_icon = square.copy()
                    round_icon.putalpha(mask)

                    for idx in range(2, 7):
                        square.save(os.path.join(dest_density, f"icon_{idx}_launcher.png"))
                        round_icon.save(os.path.join(dest_density, f"icon_{idx}_launcher_round.png"))
            print(" [+] All alternate launcher icons now use Colgram branding.")
        else:
            print(" [!] Pillow not available - alternate launcher icons left as-is.")

    # 2. Adaptive icon background -> solid pure black #000000
    for res_dir in target_dirs:
        bg_sa_xml = os.path.join(res_dir, "drawable", "icon_background_sa.xml")
        if os.path.exists(bg_sa_xml):
            with open(bg_sa_xml, "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android">\n    <solid android:color="#000000" />\n</shape>\n')
        bg_xml = os.path.join(res_dir, "drawable", "icon_background.xml")
        if os.path.exists(bg_xml):
            with open(bg_xml, "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android">\n    <solid android:color="#000000" />\n</shape>\n')

    # 3. Splash plane & black launch screen background
    splash_src = os.path.join(root_dir, "assets", "colgram_plane_splash.png")
    if os.path.exists(splash_src):
        for res_dir in target_dirs:
            if not os.path.exists(res_dir):
                continue
            drawable_dir = os.path.join(res_dir, "drawable")
            os.makedirs(drawable_dir, exist_ok=True)
            shutil.copy2(splash_src, os.path.join(drawable_dir, "colgram_plane_splash.png"))

            splash_bg_xml = os.path.join(drawable_dir, "colgram_splash_bg.xml")
            with open(splash_bg_xml, "w", encoding="utf-8") as f:
                f.write('''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@android:color/black" />
    <item
        android:gravity="center"
        android:width="160dp"
        android:height="160dp"
        android:drawable="@drawable/colgram_plane_splash" />
</layer-list>
''')

            tg_splash = os.path.join(drawable_dir, "tg_splash_320.xml")
            with open(tg_splash, "w", encoding="utf-8") as f:
                f.write('''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@android:color/black" />
    <item
        android:gravity="center"
        android:width="160dp"
        android:height="160dp"
        android:drawable="@drawable/colgram_plane_splash" />
</layer-list>
''')

    # 4. Patch styles.xml — Colgram splash branding ONLY.
    #
    # ⚠️ android:windowBackground and android:colorBackground are deliberately NOT
    # touched any more. An earlier revision forced both to
    # `@drawable/colgram_splash_bg` / `@android:color/black` in every variant, and that
    # drawable's first layer is pure black. This was the second half of the
    # black-on-black bug:
    #
    #   * android:windowBackground is what you see wherever a screen's own content is
    #     transparent, and Telegram leaves it transparent in places (the intro surface,
    #     transition gaps, list spacing).
    #   * In the LIGHT theme that was invisible, because the theme's own background is
    #     white and the window was black - opaque content covered it and the two never
    #     visibly disagreed. That is why this hid for so long.
    #   * In a DARK theme it is not invisible: transparent areas showed pure black
    #     instead of the theme colour, and IntroActivity feeds the same colour to the
    #     NATIVE intro renderer (Intro.setBackgroundColor(Theme.getColor(
    #     key_windowBackgroundWhite))), which then paints an opaque rectangle over the
    #     intro's title/subtitle TextViews. Net result: black-on-black, unreadable
    #     intro and login form.
    #
    # Same rule as the fragmentView bug fixed earlier, one level up: never hardcode a
    # background behind theme-driven content. Brand the SPLASH instead - that is what
    # windowSplashScreenBackground / windowSplashScreenAnimatedIcon are for.
    res_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "res")

    v31_styles = os.path.join(res_dir, "values-v31", "styles.xml")
    if os.path.exists(v31_styles):
        with open(v31_styles, "r", encoding="utf-8") as f:
            v31_content = f.read()
        v31_content = v31_content.replace(
            '<item name="android:windowSplashScreenAnimatedIcon">@drawable/tg_splash_320</item>\n        <item name="android:windowSplashScreenAnimationDuration">@integer/splash_screen_duration</item>\n        <item name="android:windowSplashScreenBackground">?android:windowBackground</item>',
            '<item name="android:windowSplashScreenAnimatedIcon">@drawable/colgram_plane_splash</item>\n        <item name="android:windowSplashScreenAnimationDuration">@integer/splash_screen_duration</item>\n        <item name="android:windowSplashScreenBackground">@android:color/black</item>'
        )
        with open(v31_styles, "w", encoding="utf-8") as f:
            f.write(v31_content)

    night_styles = os.path.join(res_dir, "values-night", "styles.xml")
    if os.path.exists(night_styles):
        with open(night_styles, "r", encoding="utf-8") as f:
            n_content = f.read()
        n_content = n_content.replace(
            '<item name="android:windowSplashScreenAnimatedIcon">@drawable/tg_splash_320</item>\n        <item name="android:windowSplashScreenAnimationDuration">@integer/splash_screen_duration</item>\n        <item name="android:windowSplashScreenBackground">#1f2732</item>',
            '<item name="android:windowSplashScreenAnimatedIcon">@drawable/colgram_plane_splash</item>\n        <item name="android:windowSplashScreenAnimationDuration">@integer/splash_screen_duration</item>\n        <item name="android:windowSplashScreenBackground">@android:color/black</item>'
        )
        with open(night_styles, "w", encoding="utf-8") as f:
            f.write(n_content)

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    core_dir = os.path.join(root_dir, "colgram-core")
    custom_icon = os.path.join(root_dir, "assets", "app_icon.png")

    target_repo = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root_dir, "Telegram")

    if not os.path.exists(target_repo):
        print(f"[!] Target Telegram repo not found at: {target_repo}")
        sys.exit(1)

    clone_required_submodules(target_repo)
    configure_package_and_branding(target_repo)
    stamp_build_version(target_repo)
    apply_custom_app_icon(target_repo, custom_icon)
    inject_core(target_repo, core_dir)
    configure_chaquopy_build(target_repo)
    download_official_binaries(target_repo)
    inject_hooks(target_repo)

    unexpected = [m for m in PATCH_MISSES if m not in ALLOWED_MISSES]
    stale = [m for m in ALLOWED_MISSES if m not in PATCH_MISSES]
    if PATCH_MISSES:
        print(f"\n[*] {len(PATCH_MISSES)} patch(es) did not apply:")
        for miss in PATCH_MISSES:
            tag = "allowed" if miss in ALLOWED_MISSES else "UNEXPECTED"
            print(f"    [{tag}] {miss}")
    if stale:
        # An allow-list entry that no longer fires is dead weight that hides the fact that the
        # patch it excuses was deleted or fixed. Say so instead of letting it rot silently.
        print(f"\n[*] {len(stale)} allow-list entry(ies) no longer match any miss - prune them:")
        for miss in stale:
            print(f"    [stale] {miss}")
    if unexpected:
        print(f"\n[!] FATAL: {len(unexpected)} patch(es) failed unexpectedly.")
        print("[!] An APK built now would silently be MISSING those features. Fix the anchors")
        print("[!] or, if a miss is genuinely harmless, add it to ALLOWED_MISSES with a reason.")
        sys.exit(1)

    print("\n[+] Colgram setup complete! Ready to build APK.")

if __name__ == "__main__":
    main()
