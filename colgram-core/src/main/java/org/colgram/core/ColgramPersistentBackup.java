package org.colgram.core;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * ColgramPersistentBackup — AES-256-GCM Encrypted Account & Data Persistence.
 *
 * Preserves all active native accounts (slots 0..4), encrypted vaulted accounts, bot tokens,
 * Colgram settings (Colemailer / PM Mute / Proxy / Defender), and Anti-Delete/Edit vault DB
 * across app updates, flavor switches, and full uninstall + reinstall using documented Android
 * APIs (MediaStore Documents/ColgramBackup + SAF ACTION_OPEN_DOCUMENT + BackupAgent).
 *
 * Pure-Java binary snapshot codec & AES-256-GCM envelope methods are intentionally free of
 * Android framework dependencies so they run directly in host JVM unit tests.
 */
public final class ColgramPersistentBackup {

    private static final String TAG = "ColgramPersistentBackup";

    public static final int REQ_RESTORE_PERSISTENT_BACKUP = 9473;
    public static final int REQ_EXPORT_PERSISTENT_BACKUP = 9474;

    public static final String BACKUP_SUBDIR = "ColgramBackup";
    public static final String BACKUP_FILENAME = "colgram_full_vault.cgb";

    private static final int HEADER_LEN = ColgramBackupCodec.HEADER_LEN;
    private static final int MAX_ENTRY_BYTES = ColgramBackupCodec.MAX_ENTRY_BYTES;
    private static final int MAX_TOTAL_BYTES = ColgramBackupCodec.MAX_TOTAL_BYTES;
    private static final int MAX_VAULT_DB_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ACCOUNT_SLOTS = 5;

    private static final long AUTO_BACKUP_DEBOUNCE_MS = 6_000L;
    private static final long AUTO_BACKUP_MIN_INTERVAL_MS = 15L * 60L * 1000L;

    private static final Object BACKUP_LOCK = new Object();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "colgram-persistent-backup");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledFuture<?> pendingAutoBackup = null;
    private static volatile long lastAutoBackupAtMs = 0L;
    private static volatile String lastAutoBackupFingerprint = "";
    private static volatile boolean startupRestoreAttempted = false;

    private static final String[] SNAPSHOT_PREF_FILES = {
            "userconfing.xml",
            "userconfig1.xml", "userconfig2.xml", "userconfig3.xml", "userconfig4.xml",
            "colgram_secure_config.xml",
            "mainconfig.xml",
            "colgram_proxies.xml",
            "colgram_account_vault.xml",
            "colgram_bot_tokens_global.xml",
            "colgram_bot_account_0.xml", "colgram_bot_account_1.xml",
            "colgram_bot_account_2.xml", "colgram_bot_account_3.xml",
            "colgram_bot_account_4.xml"
    };

    private ColgramPersistentBackup() {}

    public static final class BackupResult {
        public final boolean success;
        public final int fileCount;
        public final int accountCount;
        public final String location;
        public final String error;

        public BackupResult(boolean success, int fileCount, int accountCount, String location, String error) {
            this.success = success;
            this.fileCount = fileCount;
            this.accountCount = accountCount;
            this.location = location != null ? location : "";
            this.error = error != null ? error : "";
        }
    }

    public static final class RestoreResult {
        public final boolean success;
        public final int restoredFiles;
        public final int restoredVaultAccounts;
        public final String error;

        public RestoreResult(boolean success, int restoredFiles, int restoredVaultAccounts, String error) {
            this.success = success;
            this.restoredFiles = restoredFiles;
            this.restoredVaultAccounts = restoredVaultAccounts;
            this.error = error != null ? error : "";
        }
    }

    // =========================================================================================
    // Delegated Pure-Java Snapshot Codec & AES-256-GCM Envelope
    // =========================================================================================

    static boolean isAllowedRelativePath(String path) {
        return ColgramBackupCodec.isAllowedRelativePath(path);
    }

    public static byte[] encodeSnapshot(Map<String, byte[]> entries, long createdAtMs) throws Exception {
        return ColgramBackupCodec.encodeSnapshot(entries, createdAtMs);
    }

    public static Map<String, byte[]> decodeSnapshot(byte[] gzipped) throws Exception {
        return ColgramBackupCodec.decodeSnapshot(gzipped);
    }

    public static boolean isPassphraseProtected(byte[] envelope) {
        return ColgramBackupCodec.isPassphraseProtected(envelope);
    }

    public static byte[] encryptEnvelope(byte[] snapshotPlain, char[] keyMaterial, boolean userPassphrase)
            throws GeneralSecurityException {
        return ColgramBackupCodec.encryptEnvelope(snapshotPlain, keyMaterial, userPassphrase);
    }

    public static byte[] decryptEnvelope(byte[] envelope, char[] keyMaterial)
            throws GeneralSecurityException {
        return ColgramBackupCodec.decryptEnvelope(envelope, keyMaterial);
    }

    private static final String PREFS_LOCAL_BACKUP = "colgram_local_backup_meta";
    private static final String KEY_LOCAL_WRAPPED = "local_wrapped_key_b64";
    private static final String KEY_LOCAL_IV = "local_wrapped_iv_b64";
    private static final String KEY_LAST_AUTO_FP = "last_auto_backup_fp";
    private static final String KEYSTORE_LOCAL_ALIAS = "colgram_persistent_backup_v1";
    private static final String PRIVATE_BACKUP_DIR = "colgram_private_backup";

    /**
     * Generates a random 256-bit local key wrapped inside AndroidKeyStore for app-private
     * automatic snapshots. Never derives encryption keys from public Build.* fields.
     */
    private static byte[] getOrCreateLocalKeystoreSecret(Context context, boolean createIfMissing) {
        if (context == null || Build.VERSION.SDK_INT < 23) {
            return null;
        }
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            javax.crypto.SecretKey ksKey = null;
            if (ks.containsAlias(KEYSTORE_LOCAL_ALIAS)) {
                java.security.KeyStore.Entry entry = ks.getEntry(KEYSTORE_LOCAL_ALIAS, null);
                if (entry instanceof java.security.KeyStore.SecretKeyEntry) {
                    ksKey = ((java.security.KeyStore.SecretKeyEntry) entry).getSecretKey();
                }
            } else if (createIfMissing) {
                javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore");
                kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                        KEYSTORE_LOCAL_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                                | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                ksKey = kg.generateKey();
            }
            if (ksKey == null) {
                return null;
            }
            android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_LOCAL_BACKUP, Context.MODE_PRIVATE);
            String wrappedB64 = prefs.getString(KEY_LOCAL_WRAPPED, null);
            String ivB64 = prefs.getString(KEY_LOCAL_IV, null);
            if (wrappedB64 != null && ivB64 != null) {
                byte[] iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP);
                byte[] wrapped = android.util.Base64.decode(wrappedB64, android.util.Base64.NO_WRAP);
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                c.init(javax.crypto.Cipher.DECRYPT_MODE, ksKey, new javax.crypto.spec.GCMParameterSpec(128, iv));
                return c.doFinal(wrapped);
            }
            if (!createIfMissing) {
                return null;
            }
            byte[] secret = new byte[32];
            new java.security.SecureRandom().nextBytes(secret);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, ksKey);
            byte[] wrapped = c.doFinal(secret);
            prefs.edit()
                    .putString(KEY_LOCAL_WRAPPED, android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP))
                    .putString(KEY_LOCAL_IV, android.util.Base64.encodeToString(c.getIV(), android.util.Base64.NO_WRAP))
                    .apply();
            return secret;
        } catch (Throwable t) {
            Log.w(TAG, "Keystore local backup key unavailable: " + t.getMessage());
            return null;
        }
    }

    private static char[] bytesToHexChars(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return hex;
    }

    /**
     * Never blocks ApplicationLoader.onCreate() UI thread or trusts un-passphrased files on shared storage.
     * On first launch when no local account exists, dispatches an app-private Keystore-protected check off UI thread.
     */
    public static boolean restoreIfFreshInstallSync(Context context) {
        if (context == null || startupRestoreAttempted) return false;
        startupRestoreAttempted = true;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            EXECUTOR.execute(() -> restorePrivateBackupIfFreshInstall(app));
            return false;
        }
        return restorePrivateBackupIfFreshInstall(app);
    }

    private static boolean restorePrivateBackupIfFreshInstall(Context app) {
        try {
            if (hasAnyLocalAccount(app)) {
                return false;
            }
            File privFile = new File(new File(app.getFilesDir(), PRIVATE_BACKUP_DIR), BACKUP_FILENAME);
            if (!privFile.isFile() || !privFile.canRead() || privFile.length() <= HEADER_LEN) {
                return false;
            }
            byte[] secret = getOrCreateLocalKeystoreSecret(app, false);
            if (secret == null || secret.length == 0) {
                return false;
            }
            char[] keyChars = bytesToHexChars(secret);
            byte[] blob = readFileBytes(privFile);
            try {
                RestoreResult res = restoreFromBytesInternal(app, blob, keyChars, true);
                if (res.success) {
                    Log.i(TAG, "AUTO_RESTORE_PRIVATE_OK files=" + res.restoredFiles
                            + " vaultAccounts=" + res.restoredVaultAccounts);
                    return true;
                }
            } finally {
                Arrays.fill(secret, (byte) 0);
                Arrays.fill(keyChars, '\0');
            }
        } catch (Throwable t) {
            Log.w(TAG, "restorePrivateBackupIfFreshInstall skipped: " + t.getMessage());
        }
        return false;
    }

    /** Debounced off-UI-thread backup to app-private Keystore-protected snapshot (never shared storage without passphrase). */
    public static void scheduleAutoBackup(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (BACKUP_LOCK) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastAutoBackupAtMs;
            long delay = (lastAutoBackupAtMs > 0L && elapsed < AUTO_BACKUP_MIN_INTERVAL_MS)
                    ? Math.max(AUTO_BACKUP_DEBOUNCE_MS, AUTO_BACKUP_MIN_INTERVAL_MS - elapsed)
                    : AUTO_BACKUP_DEBOUNCE_MS;
            if (pendingAutoBackup != null && !pendingAutoBackup.isDone()) {
                pendingAutoBackup.cancel(false);
            }
            pendingAutoBackup = EXECUTOR.schedule(() -> {
                try {
                    BackupResult r = backupPrivateSnapshot(app);
                    if (r.success) {
                        lastAutoBackupAtMs = System.currentTimeMillis();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Auto-backup error: " + t.getMessage());
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    static String computeMetadataFingerprint(File prefsDir, File filesDir, File dbFile) {
        StringBuilder sb = new StringBuilder(512);
        if (prefsDir != null) {
            for (String name : SNAPSHOT_PREF_FILES) {
                appendFileStat(sb, "p/" + name, new File(prefsDir, name));
            }
        }
        if (filesDir != null) {
            appendDatDirStats(sb, filesDir, "f/");
            for (int a = 1; a < MAX_ACCOUNT_SLOTS; a++) {
                appendDatDirStats(sb, new File(filesDir, "account" + a), "f/a" + a + "/");
            }
        }
        if (dbFile != null) {
            appendFileStat(sb, "d/db", dbFile);
            appendFileStat(sb, "d/wal", new File(dbFile.getAbsolutePath() + "-wal"));
        }
        String raw = sb.toString();
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return new String(bytesToHexChars(md.digest(raw.getBytes(StandardCharsets.UTF_8))));
        } catch (Throwable ignored) {
            return raw;
        }
    }

    private static void appendDatDirStats(StringBuilder sb, File dir, String prefix) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        Arrays.sort(files, (a, b) -> {
            String na = a != null ? a.getName() : "";
            String nb = b != null ? b.getName() : "";
            return na.compareTo(nb);
        });
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String n = f.getName();
            if ((n.startsWith("tgnet") || n.startsWith("dc")) && n.endsWith(".dat")) {
                appendFileStat(sb, prefix + n, f);
            }
        }
    }

    private static void appendFileStat(StringBuilder sb, String tag, File f) {
        if (f == null || !f.isFile()) return;
        sb.append(tag).append(':').append(f.length()).append(':').append(f.lastModified()).append(';');
    }

    static boolean shouldSkipAutoBackup(String previousFingerprint, String currentFingerprint, File existingBackupFile) {
        return existingBackupFile != null
                && existingBackupFile.isFile()
                && existingBackupFile.length() > HEADER_LEN
                && previousFingerprint != null
                && !previousFingerprint.isEmpty()
                && previousFingerprint.equals(currentFingerprint);
    }

    private static BackupResult backupPrivateSnapshot(Context app) {
        synchronized (BACKUP_LOCK) {
            File dataDir = new File(app.getApplicationInfo().dataDir);
            File prefsDir = new File(dataDir, "shared_prefs");
            File filesDir = app.getFilesDir();
            File dbFile = app.getDatabasePath("colgram_vault.db");
            File outDir = new File(filesDir, PRIVATE_BACKUP_DIR);
            File outFile = new File(outDir, BACKUP_FILENAME);

            String currentFp = computeMetadataFingerprint(prefsDir, filesDir, dbFile);
            String prevFp = lastAutoBackupFingerprint;
            if (prevFp.isEmpty()) {
                try {
                    String stored = app.getSharedPreferences(PREFS_LOCAL_BACKUP, Context.MODE_PRIVATE)
                            .getString(KEY_LAST_AUTO_FP, "");
                    if (stored != null && !stored.isEmpty()) {
                        prevFp = stored;
                        lastAutoBackupFingerprint = stored;
                    }
                } catch (Throwable ignored) {}
            }
            if (shouldSkipAutoBackup(prevFp, currentFp, outFile)) {
                lastAutoBackupAtMs = System.currentTimeMillis();
                return new BackupResult(true, 0, 0, outFile.getAbsolutePath(), "");
            }

            byte[] secret = getOrCreateLocalKeystoreSecret(app, true);
            if (secret == null || secret.length == 0) {
                return new BackupResult(false, 0, 0, "", "KeyStore unavailable for private backup");
            }
            char[] keyMat = bytesToHexChars(secret);
            Arrays.fill(secret, (byte) 0);
            try {
                int activeAccounts = countLocalActiveAccounts(app);
                Map<String, byte[]> decryptedVault = ColgramAccountManager.exportDecryptedVaultBundles(app);
                int totalAccounts = activeAccounts + decryptedVault.size();
                if (totalAccounts == 0) {
                    return new BackupResult(false, 0, 0, "", "Нет активных аккаунтов для сохранения");
                }
                Map<String, byte[]> entries = collectSnapshotEntries(app, decryptedVault);
                if (entries.isEmpty()) {
                    return new BackupResult(false, 0, 0, "", "Нет данных для копирования");
                }
                byte[] plain = encodeSnapshot(entries, System.currentTimeMillis());
                byte[] encrypted;
                try {
                    encrypted = encryptEnvelope(plain, keyMat, false);
                } finally {
                    ColgramCryptoVault.zeroize(plain);
                }
                writeAtomic(outFile, encrypted);
                notifySystemBackupAgent();
                lastAutoBackupAtMs = System.currentTimeMillis();
                String postFp = computeMetadataFingerprint(prefsDir, filesDir, dbFile);
                lastAutoBackupFingerprint = postFp;
                try {
                    app.getSharedPreferences(PREFS_LOCAL_BACKUP, Context.MODE_PRIVATE)
                            .edit().putString(KEY_LAST_AUTO_FP, postFp).apply();
                } catch (Throwable ignored) {}
                return new BackupResult(true, entries.size(), totalAccounts, outFile.getAbsolutePath(), "");
            } catch (Throwable t) {
                Log.w(TAG, "backupPrivateSnapshot failed: " + t.getMessage());
                return new BackupResult(false, 0, 0, "", t.getMessage());
            } finally {
                Arrays.fill(keyMat, '\0');
            }
        }
    }

    /** Creates an encrypted full snapshot with an explicit passphrase and writes it to Documents/ColgramBackup. */
    public static BackupResult backupNow(Context context, char[] optionalPassphrase) {
        if (context == null) {
            return new BackupResult(false, 0, 0, "", "No context");
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (optionalPassphrase == null || optionalPassphrase.length == 0) {
            return new BackupResult(false, 0, 0, "",
                    "Для экспорта в Documents/" + BACKUP_SUBDIR + " требуется указать мастер-пароль");
        }
        synchronized (BACKUP_LOCK) {
            try {
                int activeAccounts = countLocalActiveAccounts(app);
                Map<String, byte[]> decryptedVault = ColgramAccountManager.exportDecryptedVaultBundles(app);
                int totalAccounts = activeAccounts + decryptedVault.size();
                if (totalAccounts == 0) {
                    // Safety guard: never overwrite an existing good backup with an empty install!
                    return new BackupResult(false, 0, 0, "", "Нет активных аккаунтов для сохранения");
                }

                Map<String, byte[]> entries = collectSnapshotEntries(app, decryptedVault);
                if (entries.isEmpty()) {
                    return new BackupResult(false, 0, 0, "", "Нет данных для копирования");
                }
                byte[] plain = encodeSnapshot(entries, System.currentTimeMillis());
                char[] keyMat = Arrays.copyOf(optionalPassphrase, optionalPassphrase.length);
                byte[] encrypted;
                try {
                    encrypted = encryptEnvelope(plain, keyMat, true);
                } finally {
                    ColgramCryptoVault.zeroize(plain);
                    Arrays.fill(keyMat, '\0');
                }

                String savedLocation = writeLocalExternalBackup(app, encrypted);
                notifySystemBackupAgent();
                lastAutoBackupAtMs = System.currentTimeMillis();
                Log.i(TAG, "PERSISTENT_BACKUP_OK accounts=" + totalAccounts
                        + " files=" + entries.size() + " bytes=" + encrypted.length
                        + " path=" + savedLocation);
                return new BackupResult(true, entries.size(), totalAccounts, savedLocation, "");
            } catch (Throwable t) {
                Log.w(TAG, "backupNow failed: " + t.getMessage());
                return new BackupResult(false, 0, 0, "", t.getMessage());
            }
        }
    }

    /** Builds encrypted backup bytes for manual SAF ACTION_CREATE_DOCUMENT export (requires passphrase). */
    public static byte[] buildEncryptedBackupBytes(Context context, char[] optionalPassphrase) throws Exception {
        if (optionalPassphrase == null || optionalPassphrase.length == 0) {
            throw new IllegalArgumentException("Для экспорта резервной копии требуется пароль");
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (BACKUP_LOCK) {
            Map<String, byte[]> decryptedVault = ColgramAccountManager.exportDecryptedVaultBundles(app);
            Map<String, byte[]> entries = collectSnapshotEntries(app, decryptedVault);
            if (entries.isEmpty()) {
                throw new IllegalStateException("Нет данных для резервной копии");
            }
            byte[] plain = encodeSnapshot(entries, System.currentTimeMillis());
            char[] keyMat = Arrays.copyOf(optionalPassphrase, optionalPassphrase.length);
            try {
                return encryptEnvelope(plain, keyMat, true);
            } finally {
                ColgramCryptoVault.zeroize(plain);
                Arrays.fill(keyMat, '\0');
            }
        }
    }

    /** Restores accounts, vaulted accounts, bots, settings, and Anti-Delete DB from a SAF document URI. */
    public static RestoreResult restoreFromUri(Context context, Uri uri, char[] optionalPassphrase) {
        if (context == null || uri == null) {
            return new RestoreResult(false, 0, 0, "Не выбран файл копии");
        }
        try {
            byte[] blob = readUriBytes(context, uri);
            return restoreFromBytes(context, blob, optionalPassphrase);
        } catch (Throwable t) {
            return new RestoreResult(false, 0, 0, "Ошибка чтения файла: " + t.getMessage());
        }
    }

    public static byte[] readUriBytes(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream(32768)) {
            if (in == null) throw new IllegalStateException("Не удалось открыть файл");
            byte[] buf = new byte[16384];
            int n;
            long total = 0L;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_TOTAL_BYTES) throw new SecurityException("Файл копии слишком большой");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static RestoreResult restoreFromBytes(Context context, byte[] encryptedBlob, char[] optionalPassphrase) {
        return restoreFromBytesInternal(context, encryptedBlob, optionalPassphrase, false);
    }

    private static RestoreResult restoreFromBytesInternal(Context context, byte[] encryptedBlob,
                                                          char[] optionalPassphrase, boolean allowPrivateKey) {
        if (context == null || encryptedBlob == null || encryptedBlob.length <= HEADER_LEN) {
            return new RestoreResult(false, 0, 0, "Повреждённый или пустой файл копии");
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (BACKUP_LOCK) {
            boolean needsPass = isPassphraseProtected(encryptedBlob);
            if ((optionalPassphrase == null || optionalPassphrase.length == 0) && !allowPrivateKey) {
                return new RestoreResult(false, 0, 0, "Для расшифровки резервной копии требуется мастер-пароль");
            }
            char[] keyMat = Arrays.copyOf(optionalPassphrase, optionalPassphrase.length);
            byte[] plain = null;
            try {
                plain = decryptEnvelope(encryptedBlob, keyMat);
                Map<String, byte[]> entries = decodeSnapshot(plain);
                if (entries.isEmpty()) {
                    return new RestoreResult(false, 0, 0, "Резервная копия не содержит данных");
                }
                File dataDir = new File(app.getApplicationInfo().dataDir);
                File filesDir = app.getFilesDir();
                File prefsDir = new File(dataDir, "shared_prefs");
                File dbDir = new File(dataDir, "databases");
                if (!prefsDir.exists()) prefsDir.mkdirs();
                if (!filesDir.exists()) filesDir.mkdirs();
                if (!dbDir.exists()) dbDir.mkdirs();
                String filesCanon = filesDir.getCanonicalPath();
                String filesPrefix = filesCanon.endsWith(File.separator) ? filesCanon : filesCanon + File.separator;

                int restoredFiles = 0;
                Map<String, byte[]> rawVaultBundles = new LinkedHashMap<>();

                for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                    String rel = e.getKey();
                    byte[] data = e.getValue();
                    if (rel.startsWith("vault_raw/")) {
                        String vk = rel.substring("vault_raw/".length());
                        if (vk.endsWith(".bin")) vk = vk.substring(0, vk.length() - 4);
                        if (!vk.isEmpty() && data != null && data.length > 0) {
                            rawVaultBundles.put(vk, data);
                        }
                        continue;
                    }
                    File target;
                    if (rel.startsWith("shared_prefs/")) {
                        String name = rel.substring("shared_prefs/".length());
                        if (name.contains("/") || name.contains("\\") || "..".equals(name)) continue;
                        target = new File(prefsDir, name);
                    } else if (rel.startsWith("files/")) {
                        String sub = rel.substring("files/".length());
                        target = new File(filesDir, sub);
                        if (!target.getCanonicalPath().startsWith(filesPrefix)) continue;
                    } else if (rel.startsWith("databases/")) {
                        String name = rel.substring("databases/".length());
                        if (name.contains("/") || name.contains("\\") || "..".equals(name)) continue;
                        target = new File(dbDir, name);
                        // Remove stale WAL/SHM journals before replacing SQLite database file.
                        new File(dbDir, name + "-wal").delete();
                        new File(dbDir, name + "-shm").delete();
                    } else {
                        continue;
                    }
                    writeAtomic(target, data);
                    restoredFiles++;
                }

                int restoredVault = 0;
                if (!rawVaultBundles.isEmpty()) {
                    restoredVault = ColgramAccountManager.importDecryptedVaultBundles(app, rawVaultBundles);
                }
                return new RestoreResult(restoredFiles > 0 || restoredVault > 0, restoredFiles, restoredVault, "");
            } catch (GeneralSecurityException secErr) {
                String msg = needsPass
                        ? "Неверный пароль или файл повреждён"
                        : "Не удалось расшифровать копию (требуется пароль или файл повреждён)";
                return new RestoreResult(false, 0, 0, msg);
            } catch (Throwable t) {
                Log.w(TAG, "restoreFromBytes failed", t);
                return new RestoreResult(false, 0, 0, "Ошибка восстановления: " + t.getMessage());
            } finally {
                ColgramCryptoVault.zeroize(plain);
                Arrays.fill(keyMat, '\0');
            }
        }
    }

    /** True if an auto-readable backup currently exists in Documents/ColgramBackup. */
    public static boolean hasReadableBackup(Context context) {
        if (context == null) return false;
        try {
            byte[] b = readLocalExternalBackup(context.getApplicationContext());
            return b != null && b.length > HEADER_LEN;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Standard Android SAF picker intent pre-opened to Documents/ColgramBackup. */
    public static Intent buildOpenBackupIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Uri initial = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2F" + BACKUP_SUBDIR);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
            } catch (Throwable ignored) {}
        }
        return intent;
    }

    /** Standard Android SAF save intent for cross-device encrypted backup export. */
    public static Intent buildCreateBackupIntent() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, BACKUP_FILENAME);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Uri initial = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2F" + BACKUP_SUBDIR);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
            } catch (Throwable ignored) {}
        }
        return intent;
    }

    // =========================================================================================
    // Internal Collection & Storage Helpers
    // =========================================================================================

    private static boolean hasAnyLocalAccount(Context app) {
        return countLocalActiveAccounts(app) > 0;
    }

    private static int countLocalActiveAccounts(Context app) {
        int count = 0;
        File prefsDir = new File(app.getApplicationInfo().dataDir, "shared_prefs");
        for (int a = 0; a < MAX_ACCOUNT_SLOTS; a++) {
            String prefName = a == 0 ? "userconfing" : ("userconfig" + a);
            File f = new File(prefsDir, prefName + ".xml");
            if (f.isFile() && f.length() > 64) {
                try {
                    String text = new String(readFileBytes(f), StandardCharsets.UTF_8);
                    if (text.contains("name=\"user\"") && !text.contains("name=\"user\"></string>")) {
                        count++;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return count;
    }

    private static Map<String, byte[]> collectSnapshotEntries(Context app, Map<String, byte[]> decryptedVault) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        File dataDir = new File(app.getApplicationInfo().dataDir);
        File prefsDir = new File(dataDir, "shared_prefs");
        File filesDir = app.getFilesDir();

        // 1. Account configs, bot tokens, Colgram secure config, proxies, vault metadata
        for (String name : SNAPSHOT_PREF_FILES) {
            addFileIfPresent(entries, "shared_prefs/" + name, new File(prefsDir, name), MAX_ENTRY_BYTES);
        }

        // 2. Native MTProto session auth keys & DC configs (slots 0..4)
        collectDatFiles(entries, filesDir, "files/");
        for (int a = 1; a < MAX_ACCOUNT_SLOTS; a++) {
            File accDir = new File(filesDir, "account" + a);
            if (accDir.isDirectory()) {
                collectDatFiles(entries, accDir, "files/account" + a + "/");
            }
        }

        // 3. Decrypted vaulted accounts (re-encrypted inside our reinstall-stable envelope)
        if (decryptedVault != null) {
            for (Map.Entry<String, byte[]> v : decryptedVault.entrySet()) {
                String safeKey = v.getKey().replaceAll("[^a-zA-Z0-9_-]", "_");
                if (!safeKey.isEmpty() && v.getValue() != null && v.getValue().length > 0) {
                    entries.put("vault_raw/" + safeKey + ".bin", v.getValue());
                }
            }
        }

        // 4. Colgram Anti-Delete / Edit History SQLite vault (checkpoint WAL first)
        try {
            File dbFile = app.getDatabasePath("colgram_vault.db");
            if (dbFile != null && dbFile.isFile() && dbFile.length() > 0 && dbFile.length() <= MAX_VAULT_DB_BYTES) {
                try {
                    ColgramDatabase.getInstance(app).compactOwnDatabase();
                } catch (Throwable ignored) {}
                addFileIfPresent(entries, "databases/colgram_vault.db", dbFile, MAX_VAULT_DB_BYTES);
            }
        } catch (Throwable ignored) {}

        return entries;
    }

    private static void collectDatFiles(Map<String, byte[]> entries, File dir, String prefix) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName();
            if ((n.startsWith("tgnet") || n.startsWith("dc")) && n.endsWith(".dat")) {
                addFileIfPresent(entries, prefix + n, f, 2 * 1024 * 1024);
            }
        }
    }

    private static void addFileIfPresent(Map<String, byte[]> entries, String key, File file, int maxBytes) {
        if (file == null || !file.isFile()) return;
        long len = file.length();
        if (len <= 0L || len > maxBytes) return;
        try {
            entries.put(key, readFileBytes(file));
        } catch (Throwable ignored) {}
    }

    private static String writeLocalExternalBackup(Context app, byte[] data) throws Exception {
        String writtenPath = "";
        // Android 10+ (API 29+): MediaStore Documents/ColgramBackup requires ZERO runtime permissions.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver cr = app.getContentResolver();
                Uri collection = MediaStore.Files.getContentUri("external");
                String relPath = Environment.DIRECTORY_DOCUMENTS + "/" + BACKUP_SUBDIR + "/";
                Uri existing = findMediaStoreBackupUri(cr, collection);
                if (existing != null) {
                    try (OutputStream os = cr.openOutputStream(existing, "wt")) {
                        if (os != null) {
                            os.write(data);
                            os.flush();
                            writtenPath = "Documents/" + BACKUP_SUBDIR + "/" + BACKUP_FILENAME;
                        }
                    } catch (Throwable overwriteErr) {
                        try { cr.delete(existing, null, null); } catch (Throwable ignored) {}
                        existing = null;
                    }
                }
                if (existing == null || writtenPath.isEmpty()) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILENAME);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
                    Uri inserted = cr.insert(collection, values);
                    if (inserted != null) {
                        try (OutputStream os = cr.openOutputStream(inserted, "w")) {
                            if (os != null) {
                                os.write(data);
                                os.flush();
                                writtenPath = "Documents/" + BACKUP_SUBDIR + "/" + BACKUP_FILENAME;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore backup write fallback: " + t.getMessage());
            }
        }

        // Legacy / direct Documents/ColgramBackup path only when MediaStore didn't already persist it
        if (writtenPath.isEmpty()) {
            try {
                File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                if (docs != null) {
                    File dir = new File(docs, BACKUP_SUBDIR);
                    if (dir.exists() || dir.mkdirs()) {
                        File out = new File(dir, BACKUP_FILENAME);
                        writeAtomic(out, data);
                        writtenPath = out.getAbsolutePath();
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (writtenPath.isEmpty()) {
            throw new IllegalStateException("Не удалось записать копию в Documents/" + BACKUP_SUBDIR);
        }
        return writtenPath;
    }

    private static byte[] readLocalExternalBackup(Context app) {
        // 1. Direct Documents/ColgramBackup file (fast path on Android <=10 or granted storage)
        try {
            File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (docs != null) {
                File f = new File(new File(docs, BACKUP_SUBDIR), BACKUP_FILENAME);
                if (f.isFile() && f.canRead() && f.length() > HEADER_LEN) {
                    return readFileBytes(f);
                }
            }
        } catch (Throwable ignored) {}

        // 2. MediaStore query on Android 10+
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver cr = app.getContentResolver();
                Uri collection = MediaStore.Files.getContentUri("external");
                Uri uri = findMediaStoreBackupUri(cr, collection);
                if (uri != null) {
                    return readUriBytes(app, uri);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Uri findMediaStoreBackupUri(ContentResolver cr, Uri collection) {
        if (Build.VERSION.SDK_INT < 29) return null;
        String[] proj = new String[]{MediaStore.MediaColumns._ID};
        String sel = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        String[] args = new String[]{BACKUP_FILENAME};
        try (Cursor c = cr.query(collection, proj, sel, args, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return ContentUris.withAppendedId(collection, id);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void notifySystemBackupAgent() {
        try {
            Class<?> agent = Class.forName("org.telegram.messenger.BackupAgent");
            agent.getMethod("requestBackup").invoke(null);
        } catch (Throwable ignored) {}
    }

    private static byte[] readFileBytes(File f) throws Exception {
        long declared = f.length();
        if (declared > MAX_TOTAL_BYTES) {
            throw new SecurityException("Файл копии слишком большой: " + declared);
        }
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(512, Math.min(declared, 1024 * 1024)))) {
            byte[] buf = new byte[16384];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_TOTAL_BYTES) {
                    throw new SecurityException("Файл копии превышает допустимый лимит");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void writeAtomic(File target, byte[] data) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".cgtmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
            fos.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            // fallback direct overwrite below
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(data);
                fos.getFD().sync();
            } finally {
                if (tmp.exists()) tmp.delete();
            }
        }
    }
}
