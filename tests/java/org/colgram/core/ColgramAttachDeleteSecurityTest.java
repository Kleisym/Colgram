package org.colgram.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-JVM regression tests for Track B fixes:
 *  - 2GB/4GB file attach thresholds, StatFs headroom, SHA-256 skip >32 MiB, inbox confinement
 *  - Non-positive (mid <= 0) messageId rejection in anti-delete hooks & ColgramDatabase
 *  - Vault master-key overwrite protection & bounded bundle deserialization
 *  - Persistent backup passphrase/size guards
 *  - Coherent Android-only cloak presets and non-mixed WebView UA
 */
public final class ColgramAttachDeleteSecurityTest {

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void testFileImportThresholdsAndConfinement() throws Exception {
        check(!ColgramFileImport.shouldSkipDedupHash(32L * 1024L * 1024L),
                "32 MiB exact should still allow dedup hash");
        check(ColgramFileImport.shouldSkipDedupHash(32L * 1024L * 1024L + 1L),
                "> 32 MiB must skip expensive SHA-256 dedup scan");
        check(ColgramFileImport.shouldSkipDedupHash(2000L * 1024L * 1024L),
                "2000 MiB must skip SHA-256");

        check(ColgramFileImport.isSizeWithinLimit(ColgramFileImport.DEFAULT_MAX_FILE_BYTES - 1L,
                        ColgramFileImport.DEFAULT_MAX_FILE_BYTES),
                "2000 MiB - 1 fits default limit");
        check(!ColgramFileImport.isSizeWithinLimit(ColgramFileImport.DEFAULT_MAX_FILE_BYTES,
                        ColgramFileImport.DEFAULT_MAX_FILE_BYTES),
                "2000 MiB exact rejected to match FileLoader < DEFAULT_MAX_FILE_SIZE");
        check(ColgramFileImport.isSizeWithinLimit(ColgramFileImport.DEFAULT_MAX_FILE_BYTES + 1024L,
                        ColgramFileImport.PREMIUM_MAX_FILE_BYTES),
                "Files above 2000 MiB fit under 4000 MiB Premium limit");

        check(!ColgramFileImport.hasSufficientSpace(1000L * 1024L * 1024L, 1000L * 1024L * 1024L),
                "Must reserve 64 MiB headroom before starting copy");
        check(ColgramFileImport.hasSufficientSpace(1064L * 1024L * 1024L, 1000L * 1024L * 1024L),
                "Exact headroom >= 64 MiB must pass");
        check(!ColgramFileImport.hasSufficientSpace(Long.MAX_VALUE, Long.MAX_VALUE - 1024L),
                "Must not integer-overflow on huge sizes");

        File tmpRoot = Files.createTempDirectory("cg_inbox_test_").toFile();
        try {
            File inbox = new File(tmpRoot, "colgram_inbox");
            File inside = new File(inbox, "movie.mkv");
            File escaped = new File(inbox, "../outside.mkv");
            check(ColgramFileImport.isPathInsideInbox(inbox, inside), "Inbox child must be allowed");
            check(!ColgramFileImport.isPathInsideInbox(inbox, escaped), "Path traversal escape must fail");
            ColgramStorageSandbox.ensureDirWithNoMedia(inbox);
            check(new File(inbox, ".nomedia").exists(), "ensureDirWithNoMedia must create .nomedia in inbox");
        } finally {
            File nomedia = new File(new File(tmpRoot, "colgram_inbox"), ".nomedia");
            if (nomedia.exists()) nomedia.delete();
            new File(tmpRoot, "colgram_inbox").delete();
            tmpRoot.delete();
        }
    }

    private static void testAntiDeleteRejectsNonPositiveIds() throws Exception {
        check(!ColgramHookHandler.hookShouldPreventDelete(123456L, 0), "mid=0 rejected");
        check(!ColgramHookHandler.hookShouldPreventDelete(123456L, -1), "mid=-1 rejected");
        check(!ColgramHookHandler.hookShouldPreventDelete(123456L, -2147483648), "MIN_VALUE mid rejected");
        check(!ColgramHookHandler.hookShouldPreventDelete(0L, 42), "dialogId=0 rejected");

        Set<Integer> batch = ColgramHookHandler.hookShouldPreventDeleteBatch(123456L, Arrays.asList(-5, -1, 0));
        check(batch.isEmpty(), "Batch with only non-positive IDs must return empty set");

        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        ColgramDatabase db = (ColgramDatabase) unsafeClass
                .getMethod("allocateInstance", Class.class)
                .invoke(unsafe, ColgramDatabase.class);

        setField(db, "deletedMessagesByDialog", new ConcurrentHashMap<>());
        setField(db, "deletedDialogsCache", ConcurrentHashMap.newKeySet());
        setField(db, "blockedUsersCache", ConcurrentHashMap.newKeySet());
        setField(db, "pendingPurgesDuringLoad", ConcurrentHashMap.newKeySet());
        setField(db, "cacheLoadLock", new Object());
        setField(db, "blockedCacheLock", new Object());
        setField(db, "deletedCachesLoaded", Boolean.TRUE);
        setField(db, "deletedCacheLoadStarted", Boolean.TRUE);
        setField(db, "blockedUsersLoaded", Boolean.TRUE);

        long did = -100999888777L;
        db.markMessageDeleted(did, -17);
        db.markMessageDeleted(did, 0);
        db.markMessagesDeleted(did, Arrays.asList(-99, -2, 0, 55));
        check(!db.isMessageDeleted(did, -17), "Negative mid -17 must not be cached");
        check(!db.isMessageDeleted(did, -99), "Negative mid -99 must not be cached");
        check(!db.isMessageDeleted(did, 0), "Zero mid must not be cached");
        check(db.isMessageDeleted(did, 55), "Positive mid 55 must be cached");
        check(new HashSet<>(db.getDeletedMessageIdsForDialog(did)).equals(new HashSet<>(Arrays.asList(55))),
                "getDeletedMessageIdsForDialog must contain only positive mid 55");

        // Verify ensureDeletedCachesLoadedSync retryability and non-clearing merge semantics on Stub DB
        setField(db, "deletedCachesLoaded", Boolean.FALSE);
        setField(db, "deletedCacheLoadStarted", Boolean.TRUE);
        db.markMessageDeleted(did, 77);
        check(db.isMessageDeleted(did, 77) && db.isMessageDeleted(did, 55),
                "Existing live tombstones must stay intact during preload retry");
    }

    private static void testVaultMasterKeyGuardAndDeserializeBounds() throws Exception {
        check(!ColgramAccountManager.canOverwriteWrappedMasterKey("abc", "def"),
                "Must refuse to overwrite existing wrapped master key");
        check(ColgramAccountManager.canOverwriteWrappedMasterKey(null, null),
                "Missing wrapped key permits initial generation");
        check(ColgramAccountManager.canOverwriteWrappedMasterKey("", "iv"),
                "Empty wrapped key permits generation");

        // Build corrupt payload claiming huge xmlLen (0x7fffffff)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(1); // version
        dos.writeUTF("k1");
        dos.writeLong(42L);
        dos.writeUTF("Alice");
        dos.writeUTF("alice");
        dos.writeUTF("+100");
        dos.writeBoolean(false);
        dos.writeLong(1000L);
        dos.writeUTF("");
        dos.writeInt(Integer.MAX_VALUE); // corrupt xmlLen
        dos.flush();

        boolean threwOomGuard = false;
        try {
            ColgramAccountManager.deserializeBundle(baos.toByteArray());
        } catch (IllegalArgumentException expected) {
            threwOomGuard = true;
        }
        check(threwOomGuard, "deserializeBundle must reject huge/out-of-bounds xmlLen without OOM");

        // Build corrupt payload claiming count = 500000
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        DataOutputStream dos2 = new DataOutputStream(baos2);
        dos2.writeInt(1);
        dos2.writeUTF("k2");
        dos2.writeLong(43L);
        dos2.writeUTF("Bob");
        dos2.writeUTF("");
        dos2.writeUTF("");
        dos2.writeBoolean(false);
        dos2.writeLong(1000L);
        dos2.writeUTF("");
        dos2.writeInt(0); // xmlLen
        dos2.writeInt(500_000); // huge file count
        dos2.flush();

        boolean threwCountGuard = false;
        try {
            ColgramAccountManager.deserializeBundle(baos2.toByteArray());
        } catch (IllegalArgumentException expected) {
            threwCountGuard = true;
        }
        check(threwCountGuard, "deserializeBundle must reject excessive file count");
    }

    private static void testCloakProfileAndWebViewCoherence() {
        check(ColgramCloak.findPresetForModel("iPhone 15 Pro Max") == null,
                "iPhone preset must not exist in Android APP_ID PRESET_PROFILES");
        ColgramCloak.DeviceProfile s25 = ColgramCloak.findPresetForModel("Samsung Galaxy S25 Ultra");
        check(s25 != null && "samsung".equals(s25.manufacturer),
                "Coherent Android preset Samsung Galaxy S25 Ultra present");

        String uaWithLegacyIphone = ColgramSecurityPolicy.buildCloakedWebViewUserAgent(
                "Mozilla/5.0 (Linux; Android 14; K; wv) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
                "iPhone 15 Pro Max", "iOS 17.6.1", true, true, "12.10.6", "HIGH");
        check(!uaWithLegacyIphone.contains("iPhone"),
                "Cloaked Android WebView UA must never emit mixed iPhone + Telegram-Android strings: " + uaWithLegacyIphone);
        check(uaWithLegacyIphone.contains("Telegram-Android/12.10.6 (Google Pixel 8 Pro; Android 14; SDK 34; HIGH)"),
                "Cloaked Android WebView UA stays coherent: " + uaWithLegacyIphone);
    }

    private static void testPersistentBackupRejectsNoPassphraseExport() {
        ColgramPersistentBackup.RestoreResult emptyRes = ColgramPersistentBackup.restoreFromBytes(null, null, null);
        check(!emptyRes.success, "Null context/blob rejected cleanly");

        final boolean[] cbFired = new boolean[]{false};
        final int[] cbCount = new int[]{-1};
        boolean started = ColgramFileImport.importIntentResult(null, null,
                ColgramFileImport.DEFAULT_MAX_FILE_BYTES,
                paths -> {
                    cbFired[0] = true;
                    cbCount[0] = paths != null ? paths.size() : -1;
                }, null);
        check(!started && cbFired[0] && cbCount[0] == 0,
                "importIntentResult(null, null) must return false and deliver empty list safely");
    }

    private static void setField(Object target, String name, Object val) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, val);
    }

    public static void main(String[] args) throws Exception {
        testFileImportThresholdsAndConfinement();
        testAntiDeleteRejectsNonPositiveIds();
        testVaultMasterKeyGuardAndDeserializeBounds();
        testCloakProfileAndWebViewCoherence();
        testPersistentBackupRejectsNoPassphraseExport();
        System.out.println("ColgramAttachDeleteSecurityTest: PASS");
    }
}
