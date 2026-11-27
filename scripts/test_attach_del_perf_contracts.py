#!/usr/bin/env python3
"""
Contract and dry-run regression tests for Track B:
  1. 2GB/4GB Downloads attach pipeline
  2. Deleted large-file infinite send/load loop elimination
  3. Saved Messages & big-chat SQLite performance
  4. PersistentBackup / AccountManager / Cloak / FloatWindow / EditHistory / JNI fixes
  5. Idempotent zero-miss dry-run verification of scripts/attach_del_perf_patches.py
"""

import importlib.util
import os
import shutil
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TELEGRAM_SRC = os.path.join(ROOT, "Telegram-Src")

TARGET_FILES = [
    "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertDocumentLayout.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertAudioLayout.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/FileUploadOperation.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/AlertsCreator.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/DeleteMessagesBottomSheet.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/SavedMessagesController.java",
    "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/FileRefController.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/AutoDeleteMediaTask.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java",
    "TMessagesProj/src/main/java/org/telegram/ui/SecretMediaViewer.java",
    "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java",
    "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java",
    "TMessagesProj/src/main/java/org/telegram/DispatchQueuePriority.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/RLottieDrawable.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/AnimatedFileDrawable.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/Utilities.java",
]


def read_text(rel: str) -> str:
    p = os.path.join(ROOT, *rel.split("/"))
    with open(p, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


class TrackBContractsTest(unittest.TestCase):
    def test_colgram_core_and_template_hardening_contracts(self):
        fi = read_text("colgram-core/src/main/java/org/colgram/core/ColgramFileImport.java")
        for token in (
            "LARGE_FILE_DEDUP_SKIP_BYTES = 32L * 1024L * 1024L",
            "OpenableColumns.SIZE",
            "StatFs",
            "takePersistableUriPermission",
            "cleanupRejectedStagedFile(",
            "shouldSkipDedupHash(",
            "hasSufficientSpace(",
            "ColgramStorageSandbox.ensureDirWithNoMedia(dir);",
            "importIntentResult(",
        ):
            self.assertIn(token, fi)
        self.assertIn('getFilesDir(), "colgram_inbox"', fi.split("cleanupRejectedStagedFile(", 1)[1].split("openStagedUriSync", 1)[0])
        import_async_slice = fi.split("private static boolean importUrisAsync(", 1)[1].split("public static String copyToSandbox(", 1)[0]
        self.assertIn("IMPORT_EXECUTOR.execute(() -> {", import_async_slice)
        self.assertIn("openStagedUriSync(", import_async_slice.split("IMPORT_EXECUTOR.execute(() -> {", 1)[1])
        self.assertNotIn("openStagedUriSync(", import_async_slice.split("IMPORT_EXECUTOR.execute(() -> {", 1)[0])

        um = read_text("colgram-core/src/main/java/org/colgram/core/ColgramUnlimitedMemory.java")
        self.assertIn('appCtx.getExternalFilesDir("colgram_inbox")', um)
        self.assertIn("isPowerSaveMode(appCtx)", um)
        self.assertIn("AUTO_SWEEP_MIN_INTERVAL_MS", um)

        fa = read_text("scripts/templates/ColgramFilesActivity.java")
        self.assertIn("IMPORT_EXECUTOR", fa)
        self.assertIn(".cgtmp", fa)
        self.assertNotIn("bgExecutor.shutdownNow();", fa)

        hh = read_text("colgram-core/src/main/java/org/colgram/core/ColgramHookHandler.java")
        self.assertIn("dialogId == 0L || messageId <= 0", hh)
        self.assertIn("if (mid <= 0) continue;", hh)
        self.assertIn("public static boolean startsWithCommandPrefix(String text)", hh)
        self.assertIn("public static boolean startsWithDotCommand(String text)", hh)
        self.assertIn("public static boolean canAutoRestartDpiBypass()", hh)
        self.assertIn("ColgramProxyManager.activateBuiltinProxy(appContext);", hh)
        self.assertIn("upsertShadowMessagesBatch(batch);", hh)
        self.assertIn("hookIoExecutor.execute(() -> db.purgeMessagesFromDiskOnly(dialogId, toPurge));", hh)

        db = read_text("colgram-core/src/main/java/org/colgram/core/ColgramDatabase.java")
        self.assertIn("DATABASE_VERSION = 7;", db)
        self.assertIn("peekPoisonedNonPositiveEntries()", db)
        self.assertIn("purgePoisonedNonPositiveEntries()", db)
        self.assertIn("purgeMessagesMemoryOnly(", db)
        self.assertIn("purgeMessagesFromDiskOnly(", db)
        self.assertIn("upsertShadowMessagesBatch(", db)
        self.assertIn("DeletedCacheRefreshListener", db)
        self.assertIn("deletedCacheLoadStarted = false;", db)
        cache_load_body = db.split("private void ensureDeletedCachesLoadedSync() {", 1)[1].split("prepareCacheForLookup()", 1)[0]
        self.assertNotIn(".delete(TABLE_DELETED", cache_load_body)
        self.assertNotIn("deletedMessagesByDialog.clear()", cache_load_body)

        sa = read_text("scripts/templates/ColgramSettingsActivity.java")
        self.assertNotIn("backupNow(appCtx, null)", sa)
        self.assertIn("promptDocumentsBackupPassphrase(", sa)

        ash = read_text("scripts/templates/ColgramAccountSwitcherHelper.java")
        self.assertNotIn("backupNow(ctx, null)", ash)

        pb = read_text("colgram-core/src/main/java/org/colgram/core/ColgramPersistentBackup.java")
        self.assertIn("KEYSTORE_LOCAL_ALIAS", pb)
        self.assertIn("PRIVATE_BACKUP_DIR", pb)
        self.assertIn("filesCanon", pb)
        self.assertIn("AUTO_BACKUP_MIN_INTERVAL_MS = 15L * 60L * 1000L;", pb)
        self.assertIn("computeMetadataFingerprint(", pb)
        self.assertIn("shouldSkipAutoBackup(", pb)
        self.assertNotIn("private static char[] deviceKeyMaterial()", pb)

        am = read_text("colgram-core/src/main/java/org/colgram/core/ColgramAccountManager.java")
        self.assertIn("canOverwriteWrappedMasterKey(", am)
        self.assertIn("MAX_VAULT_BUNDLE_FIELD_BYTES", am)
        self.assertIn("Failed to unwrap existing vault master key", am)

        ck = read_text("colgram-core/src/main/java/org/colgram/core/ColgramCloak.java")
        sp = read_text("colgram-core/src/main/java/org/colgram/core/ColgramSecurityPolicy.java")
        self.assertNotIn('"iPhone 15 Pro Max"', ck)
        self.assertIn("Keep Android WebView UA strictly coherent", sp)

        eh = read_text("colgram-core/src/main/java/org/colgram/core/ColgramEditHistoryDialog.java")
        self.assertIn("ThreadLocal<SimpleDateFormat>", eh)

        fw = read_text("scripts/templates/ColgramFloatWindowManager.java")
        self.assertNotIn("window.windowManager.removeViewImmediate(window.root);\n            window.windowManager.addView", fw.replace("\r\n", "\n"))
        self.assertIn("updateViewLayout(window.root, window.params)", fw)
        self.assertIn("onAnimationCancel(Animator animation)", fw)

        vw = read_text("scripts/templates/ColgramVideoWallpaperView.java")
        self.assertIn("Utilities.globalQueue.postRunnable(() -> {", vw)
        self.assertIn("releasePlayerAsync(preparedMp)", vw)
        self.assertIn("BACKGROUND_RELEASE_GRACE_MS", vw)
        self.assertIn("scheduleDelayedRelease()", vw)
        self.assertNotIn("ColgramPowerGuard", vw)

    def test_native_jni_hardening_contracts(self):
        jb = read_text("tools/warpgo/native/jni_bridge.c")
        self.assertIn("g_exchange_lock", jb)
        self.assertIn("pthread_mutex_lock(&g_exchange_lock)", jb)
        self.assertIn("new_jstring_from_utf8_safe", jb)
        self.assertIn("ExceptionCheck", jb)

        lj = read_text("vendor/colgram-llama/cpp/colgram_llama_jni.cpp")
        self.assertIn("std::call_once(g_backend_once", lj)
        self.assertIn("const bool hadVision = !rawBitmaps.empty();", lj)
        self.assertIn("if (!hadVision)", lj)
        self.assertIn("0x10FFFFu", lj)
        self.assertIn("native_init_impl", lj)

    def test_attach_del_perf_patches_dry_run_and_idempotency(self):
        if not os.path.isdir(os.path.join(TELEGRAM_SRC, "TMessagesProj")):
            self.skipTest("Telegram-Src checkout absent")

        spec = importlib.util.spec_from_file_location(
            "attach_del_perf_patches",
            os.path.join(ROOT, "scripts", "attach_del_perf_patches.py"),
        )
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        misses = []
        changed = []

        def patch_file(filepath, search_pattern, replacement, description=""):
            with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
                raw = f.read()
            if replacement and replacement.strip() in raw:
                return True
            if callable(search_pattern):
                new_c = search_pattern(raw)
                if new_c == raw:
                    misses.append(description)
                    return False
                with open(filepath, "w", encoding="utf-8", newline="") as f:
                    f.write(new_c)
                changed.append(description)
                return True
            if search_pattern not in raw:
                misses.append(description)
                return False
            with open(filepath, "w", encoding="utf-8", newline="") as f:
                f.write(raw.replace(search_pattern, replacement, 1))
            changed.append(description)
            return True

        def eol_patch(filepath, target, replacement, description=""):
            with open(filepath, "r", encoding="utf-8", errors="ignore", newline="") as f:
                raw = f.read()
            eol = "\r\n" if "\r\n" in raw else "\n"
            c = raw.replace("\r\n", "\n")
            t = target.replace("\r\n", "\n")
            r = replacement.replace("\r\n", "\n")
            if r in c:
                return True
            if t not in c:
                misses.append(description)
                return False
            with open(filepath, "w", encoding="utf-8", newline="") as f:
                f.write(c.replace(t, r, 1).replace("\n", eol))
            changed.append(description)
            return True

        with tempfile.TemporaryDirectory() as tmp:
            for rel in TARGET_FILES:
                src = os.path.join(TELEGRAM_SRC, *rel.split("/"))
                dst = os.path.join(tmp, *rel.split("/"))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)

            mod.apply(tmp, patch_file, eol_patch)
            self.assertEqual(misses, [], f"Dry-run pass 1 had misses: {misses}")

            def patched_text(rel):
                with open(os.path.join(tmp, *rel.split("/")), "r", encoding="utf-8", errors="ignore") as f:
                    return f.read()

            ca_text = patched_text("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java")
            ca_norm = ca_text.replace("\r\n", "\n")
            self.assertIn("// colgram-attach-composer-v2", ca_norm)
            self.assertIn("\n    public void onDestroy() {\n        // colgram-attach-composer-v2\n        if (colgramImportHud != null) {\n", ca_norm)
            self.assertIn("if (!buttonPressed && !sent", ca_norm)
            self.assertNotIn("        public void onDestroy() {\n        if (!buttonPressed", ca_norm)
            self.assertIn("colgramLaunchSafIntoDocumentComposer", ca_text)
            self.assertIn("colgramStageIntentResultToDocumentComposer", ca_text)
            self.assertIn("documentLayout.colgramOpenDirectory(", ca_text)
            self.assertIn("documentLayout.colgramAddImportedFiles(paths)", ca_text)
            self.assertIn("Math.max(totalBytes, bytesCopied)", ca_text)
            self.assertIn("colgramAppCtx", ca_text)
            self.assertNotIn("didSelectFiles(colgramPathsList,", ca_text)
            self.assertNotIn("ColgramFilesActivity.beginPick(", ca_text)
            self.assertIn("// colgram-nonblocking-audio (native audio composer)", ca_text)
            self.assertIn("// colgram-nonblocking-doc (native document composer)", ca_text)
            self.assertIn("AtomicBoolean colgramCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);", ca_norm)
            self.assertIn("colgramCancelled.set(true);", ca_norm)
            self.assertIn("isDestroyed()", ca_norm)

            dl_text = patched_text("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertDocumentLayout.java")
            self.assertIn("colgramAddImportedFiles(", dl_text)
            self.assertIn("colgramOpenDirectory(", dl_text)
            self.assertIn("parentAlert.updateCountButton(1)", dl_text)
            self.assertIn("MediaStore.Downloads.EXTERNAL_CONTENT_URI", dl_text)
            self.assertIn("delegate.didSelectFiles(files, caption, captionEntities, fmessages, notify, scheduleDate, scheduleRepeatPeriod,", dl_text)

            al_text = patched_text("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertAudioLayout.java")
            self.assertIn("COLGRAM_PICK_AUDIO_SAF", al_text)
            self.assertIn('colgramLaunchSafIntoDocumentComposer("audio/*")', al_text)

            chat_text = patched_text("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
            self.assertIn("chatAttachAlert.colgramStageIntentResultToDocumentComposer(data);", chat_text)
            self.assertIn("SendMessagesHelper.colgramCancelFileDownloads(currentAccount, existingObj);", chat_text)
            proc_deleted = chat_text.split("private void processDeletedMessages(ArrayList<Integer> markAsDeletedMessages, long channelId, boolean sent, boolean thanos)", 1)[1].split("if (!preserveIds.isEmpty())", 1)[0]
            self.assertNotIn("colgramCancelMessageTransfers", proc_deleted)
            cancel_opt = chat_text.split("case OPTION_CANCEL_SENDING:", 1)[1].split("case OPTION_VIEW_STATISTICS:", 1)[0]
            self.assertNotIn("for (int a = 0; a < selectedObjectGroup.messages.size(); a++)", cancel_opt)
            self.assertIn("selectedObjectGroup != null && !selectedObjectGroup.messages.isEmpty()", cancel_opt)

            self.assertIn("java.util.Locale.US", patched_text("TMessagesProj/src/main/java/org/telegram/messenger/SavedMessagesController.java"))
            self.assertIn("org.telegram.messenger.SendMessagesHelper.colgramCancelMessageTransfers(", patched_text("TMessagesProj/src/main/java/org/telegram/ui/Components/DeleteMessagesBottomSheet.java"))
            self.assertIn("org.telegram.messenger.Utilities.globalQueue.postRunnable(", patched_text("TMessagesProj/src/main/java/org/telegram/ui/SecretMediaViewer.java"))

            sm_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java")
            self.assertIn("colgramDocPrepExecutor.execute(colgramPrepTask);", sm_text)
            self.assertIn("colgramShouldCancelSending(", sm_text)
            self.assertIn("colgramCancellingIds", sm_text)
            self.assertIn("object.messageOwner.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;", sm_text)
            self.assertIn("ColgramHookHandler.startsWithCommandPrefix(sendMessageParams.message)", sm_text)
            self.assertIn("colgramRememberPendingReplyIfNeeded(", sm_text)
            self.assertIn("Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>())", sm_text)
            self.assertIn("ColgramHookHandler.canAutoRestartDpiBypass()", sm_text)
            cancel_helpers_slice = sm_text.split("public static void colgramCancelFileDownloads(", 1)[1].split("public void cancelSendingMessage(MessageObject object)", 1)[0]
            self.assertNotIn("catch (Throwable", cancel_helpers_slice)
            cancel_xfer_slice = sm_text.split("public static void colgramCancelMessageTransfers(", 1)[1].split("public void cancelSendingMessage(MessageObject object)", 1)[0]
            self.assertNotIn("colgramCancellingIds.add(", cancel_xfer_slice)
            self.assertIn("helper.cancelSendingMessage(msg);", cancel_xfer_slice)

            fuo_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/FileUploadOperation.java")
            self.assertIn("private volatile int state;", fuo_text)
            self.assertIn("colgramFailedNotified.compareAndSet(false, true)", fuo_text)
            cancel_body = fuo_text.split("public void cancel() {", 1)[1].split("private void cleanup() {", 1)[0]
            self.assertIn("cleanup();", cancel_body.split("Utilities.stageQueue.postRunnable", 1)[1].split("});", 1)[0])
            self.assertIn("colgramNotifyFailedOnce();", cancel_body)
            self.assertNotIn("AutoDeleteMediaTask.unlockFile", cancel_body)

            fl_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java")
            self.assertIn('tmpFile = File.createTempFile("colgram_dec_", ".tmp", outDir);', fl_text)
            self.assertIn("final File tmpFile;", fl_text)
            self.assertIn("colgramSkippedCancelOps.remove(operation)", fl_text)
            self.assertIn("Math.max(0, currentUploadOperationsCount - 1)", fl_text)
            self.assertIn("Math.max(0, currentUploadSmallOperationsCount - 1)", fl_text)
            cancel_ul_body = fl_text.split("public void cancelFileUpload(", 1)[1].split("public void checkUploadNewDataAvailable(", 1)[0]
            self.assertIn("if (enc) {\n                    uploadOperationPathsEnc.remove(location);\n                } else {\n                    uploadOperationPaths.remove(location);\n                }", cancel_ul_body.replace("\r\n", "\n"))

            mc_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
            self.assertIn("(channelId == 0) ? dialogMessagesByIds.get(id) : null;", mc_text)
            self.assertIn("delObj.getDialogId() != targetDid", mc_text)
            self.assertIn("SendMessagesHelper.getInstance(currentAccount).isSendingMessage(id)", mc_text)
            self.assertIn("SendMessagesHelper.colgramCancelFileDownloads(currentAccount, delObj);", mc_text)
            self.assertIn("if (colgramHasPositivePurge) {", mc_text)
            self.assertIn("if (taskId == 0 && (toSend == null || toSend.isEmpty())) {", mc_text)

            ms_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
            self.assertIn("colgram_purged_neg_mids_acc_", ms_text)
            self.assertIn("peekPoisonedNonPositiveEntries()", ms_text)
            self.assertIn("purgePoisonedNonPositiveEntries()", ms_text)
            self.assertIn('database.executeFast("DELETE FROM messages_topics WHERE uid = " + did + " AND mid != " + last_mid_i + " AND mid != " + last_mid + keepClause)', ms_text)
            self.assertIn('database.executeFast("DELETE FROM messages_topics WHERE uid = " + did + keepClauseFull)', ms_text)
            self.assertIn("if (!colgramHasPositive) {\n                return;\n            }", ms_text.replace("\r\n", "\n"))
            put7 = ms_text.replace("\r\n", "\n").split("public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, int mode, long threadMessageId) {", 1)[1].split("public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage", 1)[0]
            self.assertNotIn("colgramDispatchPlugins(", put7)
            put8 = ms_text.replace("\r\n", "\n").split("public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage, int mode, long threadMessageId) {", 1)[1].split("public void processEphemeralMessages(", 1)[0]
            self.assertTrue(
                "storageQueue.postRunnable(() -> {\n                if (mode == ChatActivity.MODE_DEFAULT) {\n                    colgramDispatchPlugins(messages);\n                }" in put8
                or "storageQueue.postRunnable(() -> {\n                if (mode == ChatActivity.MODE_DEFAULT) {\n                    colgramPreFilterSpamMentions(messages);\n                    colgramDispatchPlugins(messages);\n                }" in put8
            )

            nc_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java").replace("\r\n", "\n")
            post_int = nc_text.split("public void postNotificationNameInternal(", 1)[1].split("public void updateObserver(", 1)[0]
            self.assertIn("broadcasting++;\n        try {", post_int)
            self.assertIn("} finally {\n            broadcasting--;", post_int)

            uc_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java")
            self.assertIn("if (withFile && currentUser != null && ApplicationLoader.applicationContext != null)", uc_text)

            theme_text = patched_text("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java")
            self.assertIn("public static boolean colgramCanUseUiColorCache()", theme_text)
            self.assertIn("default boolean colgramDelegatesToGlobalTheme()", theme_text)
            self.assertIn("if (provider == null || provider.colgramDelegatesToGlobalTheme())", theme_text)
            self.assertIn("boolean colgramNeedsGuard = !provider.colgramReturnsGuardedColor();", theme_text)

            chat_full = patched_text("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
            self.assertIn("private SparseIntArray colgramGuardedColorCache;", chat_full)
            self.assertIn("final boolean canUseCache = Theme.colgramCanUseUiColorCache();", chat_full)
            self.assertIn("if (cache != null) {\n                cache.put(key, result);", chat_full.replace("\r\n", "\n"))
            self.assertIn("chatListView.setItemViewCacheSize(16);", chat_full)
            self.assertIn("chatListView.getRecycledViewPool().setMaxRecycledViews(0, 24);", chat_full)
            self.assertIn("chatLayoutManager.setInitialPrefetchItemCount(6);", chat_full)
            self.assertIn("private long colgramLastChatBlurMs;", chat_full)
            self.assertIn("if (!immediate && nowMs - colgramLastChatBlurMs < 16L)", chat_full)
            self.assertIn("colgramLastChatBlurMs = 0L;", chat_full)
            self.assertIn("private ColgramVideoWallpaperView colgramEnsureVideoWallpaperView()", chat_full)
            self.assertIn("colgramVideoWallpaperView = null;", chat_full)

            da_text = patched_text("TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java")
            self.assertIn("viewPage.listView.setItemViewCacheSize(12);", da_text)
            self.assertIn("viewPage.listView.getRecycledViewPool().setMaxRecycledViews(0, 20);", da_text)
            self.assertIn("viewPage.layoutManager.setInitialPrefetchItemCount(6);", da_text)

            self.assertIn('database.executeFast("PRAGMA synchronous = NORMAL")', ms_text)
            self.assertIn('database.executeFast("PRAGMA mmap_size = 67108864")', ms_text)
            self.assertIn('database.executeFast("PRAGMA cache_size = -16384")', ms_text)
            self.assertIn('database.executeFast("PRAGMA busy_timeout = 5000")', ms_text)
            self.assertEqual(ms_text.count('database.executeFast("PRAGMA secure_delete = ON")'), 2)

            dqp_text = patched_text("TMessagesProj/src/main/java/org/telegram/DispatchQueuePriority.java")
            self.assertIn("THREAD_PRIORITY_BACKGROUND", dqp_text)
            rl_text = patched_text("TMessagesProj/src/main/java/org/telegram/ui/Components/RLottieDrawable.java")
            self.assertEqual(rl_text.count("THREAD_PRIORITY_BACKGROUND"), 2)
            afd_text = patched_text("TMessagesProj/src/main/java/org/telegram/ui/Components/AnimatedFileDrawable.java")
            self.assertIn("THREAD_PRIORITY_BACKGROUND", afd_text)
            il_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java")
            self.assertIn('new DispatchQueue("thumbGeneratingQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND)', il_text)
            self.assertIn('new DispatchQueue("imageLoadQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND)', il_text)
            self.assertIn("maxSize = unlimited ? 56 : 44;", il_text)
            self.assertIn('new DispatchQueue("fileUploadQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND)', fl_text)
            util_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/Utilities.java")
            self.assertIn('stageQueue = new DispatchQueue("stageQueue");', util_text)
            self.assertEqual(util_text.count("THREAD_PRIORITY_BACKGROUND"), 6)

            changed.clear()
            mod.apply(tmp, patch_file, eol_patch)
            self.assertEqual(misses, [], f"Dry-run pass 2 had misses: {misses}")
            self.assertEqual(changed, [], f"Dry-run pass 2 was not idempotent: {changed}")

    def test_apply_patches_send_microdelay_and_superseded_contracts(self):
        ap = read_text("scripts/apply-patches.py").replace("\r\n", "\n")
        self.assertIn("'ChatActivity.java perf #1'", ap)
        self.assertIn("'DialogCell.java perf #16'", ap)
        self.assertIn("'ApplicationLoader.java perf #1 (defer BootJobService and Updater)'", ap)
        self.assertIn("colgramLastBlurInvalidateMs", ap)
        self.assertIn('"MessagesController.java alreadyDeletedPurge full DB purge": "if (colgramHasPositivePurge)"', ap)
        self.assertIn("if (withFile && currentUser != null && ApplicationLoader.applicationContext != null)", ap)

    def test_notification_center_finally_runtime_behavior_on_jvm(self):
        if not os.path.isdir(os.path.join(TELEGRAM_SRC, "TMessagesProj")):
            self.skipTest("Telegram-Src checkout absent")
        javac = shutil.which("javac")
        if not javac and os.name == "nt":
            cand = r"C:\colgram-tools\jdk-17.0.20.1+1\bin\javac.exe"
            if os.path.isfile(cand):
                javac = cand
        if not javac:
            self.skipTest("javac not available")
        java = os.path.join(os.path.dirname(javac), "java.exe" if os.name == "nt" else "java")

        spec = importlib.util.spec_from_file_location(
            "attach_del_perf_patches_nc",
            os.path.join(ROOT, "scripts", "attach_del_perf_patches.py"),
        )
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        def patch_file(filepath, search_pattern, replacement, description=""):
            with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
                raw = f.read()
            if replacement and replacement.strip() in raw:
                return True
            if callable(search_pattern):
                new_c = search_pattern(raw)
                if new_c != raw:
                    with open(filepath, "w", encoding="utf-8", newline="") as f:
                        f.write(new_c)
                return True
            if search_pattern in raw:
                with open(filepath, "w", encoding="utf-8", newline="") as f:
                    f.write(raw.replace(search_pattern, replacement, 1))
                return True
            return False

        def eol_patch(filepath, target, replacement, description=""):
            with open(filepath, "r", encoding="utf-8", errors="ignore", newline="") as f:
                raw = f.read()
            eol = "\r\n" if "\r\n" in raw else "\n"
            c = raw.replace("\r\n", "\n")
            t = target.replace("\r\n", "\n")
            r = replacement.replace("\r\n", "\n")
            if r in c:
                return True
            if t in c:
                with open(filepath, "w", encoding="utf-8", newline="") as f:
                    f.write(c.replace(t, r, 1).replace("\n", eol))
                return True
            return False

        with tempfile.TemporaryDirectory() as tmp:
            for rel in TARGET_FILES:
                src = os.path.join(TELEGRAM_SRC, *rel.split("/"))
                dst = os.path.join(tmp, *rel.split("/"))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)
            mod.apply(tmp, patch_file, eol_patch)

            nc_path = os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "NotificationCenter.java")
            with open(nc_path, "r", encoding="utf-8", errors="ignore") as f:
                nc_src = f.read().replace("\r\n", "\n")

            # Extract the exact try/finally dispatch block from patched NotificationCenter.java
            start_tok = "        broadcasting++;\n        try {"
            end_tok = "\n    public void updateObserver("
            s_idx = nc_src.find(start_tok)
            e_idx = nc_src.find(end_tok, s_idx)
            self.assertGreater(s_idx, 0)
            self.assertGreater(e_idx, s_idx)
            dispatch_block = nc_src[s_idx:e_idx]
            # Strip the closing method brace of postNotificationNameInternal
            dispatch_block = dispatch_block.rsplit("}", 1)[0]

            harness = f"""import java.util.*;
public class NcHarness {{
    interface NotificationCenterDelegate {{ void didReceivedNotification(int id, int acc, Object... args); }}
    static class SparseMap<T> {{
        private final ArrayList<Integer> keys = new ArrayList<>();
        private final ArrayList<T> vals = new ArrayList<>();
        void put(int k, T v) {{ int i = keys.indexOf(k); if (i >= 0) vals.set(i, v); else {{ keys.add(k); vals.add(v); }} }}
        T get(int k) {{ int i = keys.indexOf(k); return i >= 0 ? vals.get(i) : null; }}
        int size() {{ return keys.size(); }}
        int keyAt(int idx) {{ return keys.get(idx); }}
        void clear() {{ keys.clear(); vals.clear(); }}
    }}
    int currentAccount = 0;
    int broadcasting = 0;
    SparseMap<ArrayList<NotificationCenterDelegate>> observers = new SparseMap<>();
    SparseMap<ArrayList<NotificationCenterDelegate>> removeAfterBroadcast = new SparseMap<>();
    SparseMap<ArrayList<NotificationCenterDelegate>> addAfterBroadcast = new SparseMap<>();
    void addObserverInternal(NotificationCenterDelegate o, int id) {{
        if (broadcasting != 0) {{
            ArrayList<NotificationCenterDelegate> l = addAfterBroadcast.get(id);
            if (l == null) addAfterBroadcast.put(id, l = new ArrayList<>());
            l.add(o);
            return;
        }}
        ArrayList<NotificationCenterDelegate> l = observers.get(id);
        if (l == null) observers.put(id, l = new ArrayList<>());
        if (!l.contains(o)) l.add(o);
    }}
    void removeObserver(NotificationCenterDelegate o, int id) {{
        if (broadcasting != 0) {{
            ArrayList<NotificationCenterDelegate> l = removeAfterBroadcast.get(id);
            if (l == null) removeAfterBroadcast.put(id, l = new ArrayList<>());
            l.add(o);
            return;
        }}
        ArrayList<NotificationCenterDelegate> l = observers.get(id);
        if (l != null) l.remove(o);
    }}
    void post(int id, Object... args) {{
{dispatch_block}
    }}
    public static void main(String[] args) {{
        NcHarness nc = new NcHarness();
        NotificationCenterDelegate deferred = (id, acc, a) -> {{}};
        NotificationCenterDelegate throwing = (id, acc, a) -> {{
            nc.addObserverInternal(deferred, 2);
            throw new StackOverflowError("simulated");
        }};
        nc.addObserverInternal(throwing, 1);
        boolean caught = false;
        try {{
            nc.post(1);
        }} catch (StackOverflowError expected) {{
            caught = true;
        }}
        if (!caught) throw new AssertionError("Expected StackOverflowError");
        if (nc.broadcasting != 0) throw new AssertionError("broadcasting leaked: " + nc.broadcasting);
        if (nc.observers.get(2) == null || !nc.observers.get(2).contains(deferred)) {{
            throw new AssertionError("addAfterBroadcast failed to drain after finally");
        }}
    }}
}}
"""
            harness_path = os.path.join(tmp, "NcHarness.java")
            with open(harness_path, "w", encoding="utf-8") as f:
                f.write(harness)
            import subprocess
            c_res = subprocess.run([javac, "-encoding", "UTF-8", harness_path], capture_output=True, text=True)
            self.assertEqual(c_res.returncode, 0, c_res.stderr + c_res.stdout)
            r_res = subprocess.run([java, "-ea", "-cp", tmp, "NcHarness"], capture_output=True, text=True)
            self.assertEqual(r_res.returncode, 0, r_res.stderr + r_res.stdout)


if __name__ == "__main__":
    unittest.main()
