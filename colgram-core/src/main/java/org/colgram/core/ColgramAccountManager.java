package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * ColgramAccountManager — Encrypted Unlimited Account & Pin Vault.
 *
 * Telegram's native JNI layer (libtmessages.49.so) allocates jniEnv[5], capping simultaneous
 * loaded native slots to 5 (0..4). This manager removes the practical cap cleanly by:
 * 1) Encrypting inactive account sessions (userconfig XML + tgnet*.dat + bot token) with
 *    AES-256-GCM backed by an AndroidKeyStore master key inside app-private filesDir/colgram_vault.
 * 2) Supporting unlimited pinned accounts (active and vaulted) sorted via ColgramAccountVaultPolicy.
 * 3) Auto-archiving an unpinned LRU slot whenever adding an account beyond 5, and hot-swapping
 *    any vaulted account back in on tap without losing credentials.
 */
public class ColgramAccountManager {

    private static final String TAG = "ColgramAccountManager";
    private static final String PREFS_VAULT = "colgram_account_vault";
    private static final String KEY_WRAPPED_MASTER = "wrapped_master_v1";
    private static final String KEY_WRAPPED_IV = "wrapped_iv_v1";
    private static final String KEY_FALLBACK_SALT = "fallback_salt_v1";
    private static final String KEY_VAULT_INDEX = "vault_index_v1";
    private static final String KEY_PIN_COUNTER = "pin_counter";
    private static final String KEY_PIN_PREFIX = "pin_order_";
    private static final String KEYSTORE_ALIAS = "colgram_account_vault_master_v1";
    private static final String VAULT_DIR_NAME = "colgram_vault";

    private static volatile File sessionsDir = null;
    private static volatile byte[] cachedMasterKey = null;
    private static final Object MASTER_LOCK = new Object();

    public static final class VaultEntry {
        public final String vaultKey;
        public final long userId;
        public final String name;
        public final String username;
        public final String phone;
        public final boolean isBot;
        public final long savedAt;

        public VaultEntry(String vaultKey, long userId, String name, String username,
                          String phone, boolean isBot, long savedAt) {
            this.vaultKey = vaultKey;
            this.userId = userId;
            this.name = name != null ? name : "";
            this.username = username != null ? username : "";
            this.phone = phone != null ? phone : "";
            this.isBot = isBot;
            this.savedAt = savedAt;
        }
    }

    public static final class VaultBundle {
        public final VaultEntry entry;
        public final byte[] userConfigXml;
        public final Map<String, byte[]> tgnetFiles;
        public final String botToken;

        VaultBundle(VaultEntry entry, byte[] userConfigXml,
                    Map<String, byte[]> tgnetFiles, String botToken) {
            this.entry = entry;
            this.userConfigXml = userConfigXml;
            this.tgnetFiles = tgnetFiles;
            this.botToken = botToken;
        }
    }

    public static void init(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try {
            File privDir = new File(app.getFilesDir(), VAULT_DIR_NAME);
            if (!privDir.exists()) privDir.mkdirs();
            sessionsDir = privDir;
        } catch (Throwable t) {
            Log.w(TAG, "init vault dir failed: " + t.getMessage());
        }
    }

    private static File vaultDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), VAULT_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // -----------------------------------------------------------------------------------------
    // Unlimited Account Pinning (keyed by stableAccountKey so pins survive slot swaps)
    // -----------------------------------------------------------------------------------------

    public static String accountPinKey(long userId, int slot) {
        return ColgramAccountVaultPolicy.stableAccountKey(userId, slot);
    }

    public static boolean isAccountPinned(Context context, long userId, int slot) {
        return getAccountPinOrder(context, userId, slot) > 0;
    }

    public static int getAccountPinOrder(Context context, long userId, int slot) {
        if (context == null) return 0;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE);
        int order = prefs.getInt(KEY_PIN_PREFIX + accountPinKey(userId, slot), 0);
        if (order <= 0 && userId > 0 && slot >= 0) {
            order = prefs.getInt(KEY_PIN_PREFIX + "slot_" + slot, 0);
        }
        return order;
    }

    public static boolean toggleAccountPin(Context context, long userId, int slot) {
        if (context == null) return false;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE);
        String key = KEY_PIN_PREFIX + accountPinKey(userId, slot);
        int cur = prefs.getInt(key, 0);
        if (cur > 0) {
            prefs.edit().remove(key).apply();
            ColgramPersistentBackup.scheduleAutoBackup(context);
            return false;
        }
        int next = prefs.getInt(KEY_PIN_COUNTER, 0) + 1;
        prefs.edit().putInt(KEY_PIN_COUNTER, next).putInt(key, next).apply();
        ColgramPersistentBackup.scheduleAutoBackup(context);
        return true;
    }

    public static boolean toggleVaultEntryPin(Context context, String vaultKey, long userId) {
        if (context == null) return false;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE);
        String key = KEY_PIN_PREFIX + (userId > 0 ? ("uid_" + userId) : vaultKey);
        int cur = prefs.getInt(key, 0);
        if (cur > 0) {
            prefs.edit().remove(key).apply();
            ColgramPersistentBackup.scheduleAutoBackup(context);
            return false;
        }
        int next = prefs.getInt(KEY_PIN_COUNTER, 0) + 1;
        prefs.edit().putInt(KEY_PIN_COUNTER, next).putInt(key, next).apply();
        ColgramPersistentBackup.scheduleAutoBackup(context);
        return true;
    }

    public static boolean isVaultEntryPinned(Context context, String vaultKey, long userId) {
        if (context == null) return false;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE);
        int ord = userId > 0 ? prefs.getInt(KEY_PIN_PREFIX + "uid_" + userId, 0) : 0;
        if (ord <= 0 && vaultKey != null) {
            ord = prefs.getInt(KEY_PIN_PREFIX + vaultKey, 0);
        }
        return ord > 0;
    }

    public static int getVaultEntryPinOrder(Context context, String vaultKey, long userId) {
        if (context == null) return 0;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE);
        int ord = userId > 0 ? prefs.getInt(KEY_PIN_PREFIX + "uid_" + userId, 0) : 0;
        if (ord <= 0 && vaultKey != null) {
            ord = prefs.getInt(KEY_PIN_PREFIX + vaultKey, 0);
        }
        return ord;
    }

    public static List<Integer> sortActiveAccounts(Context context, List<Integer> slots,
                                                   long[] userIds, long[] loginTimes) {
        if (slots == null || slots.isEmpty()) return new ArrayList<>();
        int n = slots.size();
        boolean[] pinned = new boolean[n];
        int[] orders = new int[n];
        long[] lastUsed = new long[n];
        for (int i = 0; i < n; i++) {
            int acc = slots.get(i);
            long uid = (userIds != null && acc >= 0 && acc < userIds.length) ? userIds[acc] : 0L;
            int ord = getAccountPinOrder(context, uid, acc);
            pinned[i] = ord > 0;
            orders[i] = ord;
            lastUsed[i] = (loginTimes != null && acc >= 0 && acc < loginTimes.length)
                    ? loginTimes[acc] : acc;
        }
        Integer[] perm = ColgramAccountVaultPolicy.order(pinned, orders, lastUsed);
        List<Integer> out = new ArrayList<>(n);
        for (int idx : perm) out.add(slots.get(idx));
        return out;
    }

    public static List<VaultEntry> sortVaultEntries(Context context, List<VaultEntry> raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        int n = raw.size();
        boolean[] pinned = new boolean[n];
        int[] orders = new int[n];
        long[] savedAt = new long[n];
        for (int i = 0; i < n; i++) {
            VaultEntry e = raw.get(i);
            int ord = getVaultEntryPinOrder(context, e.vaultKey, e.userId);
            pinned[i] = ord > 0;
            orders[i] = ord;
            savedAt[i] = e.savedAt;
        }
        Integer[] perm = ColgramAccountVaultPolicy.order(pinned, orders, savedAt);
        List<VaultEntry> out = new ArrayList<>(n);
        for (int idx : perm) out.add(raw.get(idx));
        return out;
    }

    // -----------------------------------------------------------------------------------------
    // Archive / List / Restore / Delete Encrypted Accounts
    // -----------------------------------------------------------------------------------------

    public static String configPrefsName(int accountIndex) {
        return accountIndex == 0 ? "userconfing" : ("userconfig" + accountIndex);
    }

    private static File sharedPrefsXmlFile(Context context, String prefsName) {
        return new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"), prefsName + ".xml");
    }

    private static File slotFilesDir(Context context, int accountIndex) {
        File base = context.getApplicationContext().getFilesDir();
        return accountIndex == 0 ? base : new File(base, "account" + accountIndex);
    }

    public static boolean archiveSlot(Context context, int accountIndex, long userId,
                                      String name, String username, String phone, boolean isBot) {
        if (context == null || accountIndex < 0) return false;
        Context app = context.getApplicationContext();
        try {
            File xmlFile = sharedPrefsXmlFile(app, configPrefsName(accountIndex));
            byte[] xmlBytes = xmlFile.exists() ? readFileBytes(xmlFile) : new byte[0];
            File tgDir = slotFilesDir(app, accountIndex);
            Map<String, byte[]> tgnetFiles = new HashMap<>();
            if (tgDir.exists()) {
                File[] files = tgDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.getName().endsWith(".dat")) {
                            tgnetFiles.put(f.getName(), readFileBytes(f));
                        }
                    }
                }
            }
            if (xmlBytes.length == 0 && tgnetFiles.isEmpty()) {
                return false;
            }
            String botToken = ColgramBotSync.getBotToken(app, accountIndex);
            String vaultKey = sanitizeKey(ColgramAccountVaultPolicy.stableAccountKey(userId, accountIndex));
            VaultEntry entry = new VaultEntry(vaultKey, userId, name, username, phone, isBot,
                    System.currentTimeMillis());

            byte[] plainBundle = serializeBundle(entry, xmlBytes, tgnetFiles, botToken);
            byte[] key = getOrCreateMasterKey(app);
            byte[] encrypted = ColgramCryptoVault.encryptWithAad(
                    key, plainBundle, vaultKey.getBytes(StandardCharsets.UTF_8));
            ColgramCryptoVault.zeroize(plainBundle);

            File target = new File(vaultDir(app), vaultKey + ".cgv");
            writeAtomic(target, encrypted);
            upsertIndexEntry(app, entry);
            ColgramPersistentBackup.scheduleAutoBackup(app);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "archiveSlot failed for slot " + accountIndex, t);
            return false;
        }
    }

    public static VaultBundle readVaultBundle(Context context, String vaultKey) {
        if (context == null || vaultKey == null || vaultKey.isEmpty()) return null;
        Context app = context.getApplicationContext();
        try {
            String safeKey = sanitizeKey(vaultKey);
            File src = new File(vaultDir(app), safeKey + ".cgv");
            if (!src.exists()) return null;
            byte[] encrypted = readFileBytes(src);
            byte[] key = getOrCreateMasterKey(app);
            byte[] plain = ColgramCryptoVault.decryptWithAad(
                    key, encrypted, safeKey.getBytes(StandardCharsets.UTF_8));
            try {
                return deserializeBundle(plain);
            } finally {
                ColgramCryptoVault.zeroize(plain);
            }
        } catch (Throwable t) {
            Log.e(TAG, "readVaultBundle failed for " + vaultKey, t);
            return null;
        }
    }

    public static boolean writeVaultBundleToSlot(Context context, VaultBundle bundle, int targetSlot) {
        if (context == null || bundle == null || targetSlot < 0) return false;
        Context app = context.getApplicationContext();
        try {
            File xmlTarget = sharedPrefsXmlFile(app, configPrefsName(targetSlot));
            if (bundle.userConfigXml != null && bundle.userConfigXml.length > 0) {
                writeAtomic(xmlTarget, bundle.userConfigXml);
            }

            File dir = slotFilesDir(app, targetSlot);
            if (!dir.exists()) dir.mkdirs();
            // Clear stale SQLite/tgnet files for the target slot before writing restored session.
            File[] existing = dir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    if (f.isFile() && (f.getName().endsWith(".dat")
                            || f.getName().startsWith("cache4.db"))) {
                        f.delete();
                    }
                }
            }
            if (bundle.tgnetFiles != null) {
                for (Map.Entry<String, byte[]> entry : bundle.tgnetFiles.entrySet()) {
                    String fname = new File(entry.getKey()).getName();
                    if (!fname.isEmpty() && entry.getValue() != null) {
                        writeAtomic(new File(dir, fname), entry.getValue());
                    }
                }
            }

            if (bundle.botToken != null && !bundle.botToken.isEmpty()) {
                ColgramBotSync.saveBotToken(app, targetSlot, bundle.botToken);
            } else {
                clearBotTokenForSlot(app, targetSlot);
            }

            // IMPORTANT: do NOT call getSharedPreferences(targetSlot).edit().commit() AFTER writeAtomic(),
            // because Android's ContextImpl caches old SharedPreferences in memory and would overwrite disk!
            if (targetSlot != 0) {
                app.getSharedPreferences("userconfing", Context.MODE_PRIVATE)
                        .edit().putInt("selectedAccount", targetSlot).commit();
            } else {
                patchSelectedAccountInXml(xmlTarget, 0);
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "writeVaultBundleToSlot failed", t);
            return false;
        }
    }

    public static void clearBotTokenForSlot(Context context, int slot) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            app.getSharedPreferences("colgram_bot_account_" + slot, Context.MODE_PRIVATE)
                    .edit().clear().commit();
            app.getSharedPreferences("colgram_bot_tokens_global", Context.MODE_PRIVATE)
                    .edit().remove("token_account_" + slot).commit();
        } catch (Throwable ignored) {}
    }

    public static List<VaultEntry> listVaultedAccounts(Context context) {
        List<VaultEntry> list = new ArrayList<>();
        if (context == null) return list;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_VAULT_INDEX, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            File dir = vaultDir(app);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String vk = sanitizeKey(o.optString("vaultKey", ""));
                if (vk.isEmpty() || !new File(dir, vk + ".cgv").exists()) continue;
                list.add(new VaultEntry(
                        vk,
                        o.optLong("userId", 0L),
                        o.optString("name", ""),
                        o.optString("username", ""),
                        o.optString("phone", ""),
                        o.optBoolean("isBot", false),
                        o.optLong("savedAt", 0L)
                ));
            }
        } catch (Throwable t) {
            Log.w(TAG, "listVaultedAccounts parse error: " + t.getMessage());
        }
        return sortVaultEntries(app, list);
    }

    public static VaultEntry findVaultEntryByUserId(Context context, long userId) {
        if (context == null || userId <= 0) return null;
        for (VaultEntry e : listVaultedAccounts(context)) {
            if (e.userId == userId) return e;
        }
        return null;
    }

    public static boolean removeVaultAccount(Context context, String vaultKey) {
        if (context == null || vaultKey == null || vaultKey.isEmpty()) return false;
        Context app = context.getApplicationContext();
        String safeKey = sanitizeKey(vaultKey);
        File file = new File(vaultDir(app), safeKey + ".cgv");
        boolean deleted = !file.exists() || file.delete();
        SharedPreferences prefs = app.getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE);
        try {
            JSONArray src = new JSONArray(prefs.getString(KEY_VAULT_INDEX, "[]"));
            JSONArray dst = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                if (o != null && !safeKey.equals(sanitizeKey(o.optString("vaultKey", "")))) {
                    dst.put(o);
                }
            }
            prefs.edit().putString(KEY_VAULT_INDEX, dst.toString()).apply();
        } catch (Throwable ignored) {}
        return deleted;
    }

    /**
     * Exports all locally archived vault bundles as decrypted serialized payloads so
     * {@link ColgramPersistentBackup} can re-encrypt them under its reinstall-stable key
     * (surviving AndroidKeyStore wipe on uninstall).
     */
    public static Map<String, byte[]> exportDecryptedVaultBundles(Context context) {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        if (context == null) return out;
        Context app = context.getApplicationContext();
        List<VaultEntry> entries = listVaultedAccounts(app);
        if (entries.isEmpty()) return out;
        try {
            byte[] key = getOrCreateMasterKey(app);
            File dir = vaultDir(app);
            for (VaultEntry e : entries) {
                String safeKey = sanitizeKey(e.vaultKey);
                File src = new File(dir, safeKey + ".cgv");
                if (!src.isFile()) continue;
                try {
                    byte[] enc = readFileBytes(src);
                    byte[] plain = ColgramCryptoVault.decryptWithAad(
                            key, enc, safeKey.getBytes(StandardCharsets.UTF_8));
                    out.put(safeKey, plain);
                } catch (Throwable err) {
                    Log.w(TAG, "exportDecryptedVaultBundles skipped " + safeKey + ": " + err.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "exportDecryptedVaultBundles failed: " + t.getMessage());
        }
        return out;
    }

    /**
     * Re-imports decrypted vault bundles onto a freshly installed instance, sealing them under
     * this installation's own AndroidKeyStore master key and rebuilding the vault index.
     */
    public static int importDecryptedVaultBundles(Context context, Map<String, byte[]> rawBundles) {
        if (context == null || rawBundles == null || rawBundles.isEmpty()) return 0;
        Context app = context.getApplicationContext();
        int imported = 0;
        try {
            byte[] key = getOrCreateMasterKey(app);
            File dir = vaultDir(app);
            for (Map.Entry<String, byte[]> e : rawBundles.entrySet()) {
                byte[] plain = e.getValue();
                if (plain == null || plain.length == 0) continue;
                try {
                    VaultBundle bundle = deserializeBundle(plain);
                    String safeKey = sanitizeKey(bundle.entry.vaultKey);
                    byte[] enc = ColgramCryptoVault.encryptWithAad(
                            key, plain, safeKey.getBytes(StandardCharsets.UTF_8));
                    writeAtomic(new File(dir, safeKey + ".cgv"), enc);
                    upsertIndexEntry(app, bundle.entry);
                    imported++;
                } catch (Throwable err) {
                    Log.w(TAG, "importDecryptedVaultBundles skipped " + e.getKey() + ": " + err.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "importDecryptedVaultBundles failed: " + t.getMessage());
        }
        return imported;
    }

    /** Backwards-compatible helper: exports encrypted snapshot and returns true if saved. */
    public static boolean exportSession(Context context, int accountIndex, String sessionName) {
        return archiveSlot(context, accountIndex, 0L, sessionName, "", "", false);
    }

    /** Backwards-compatible session key listing. */
    public static List<String> listSavedSessions() {
        List<String> list = new ArrayList<>();
        if (sessionsDir != null && sessionsDir.exists()) {
            File[] files = sessionsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".cgv"));
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    list.add(n.substring(0, n.length() - 4));
                }
            }
        }
        return list;
    }

    // -----------------------------------------------------------------------------------------
    // Key management & serialization
    // -----------------------------------------------------------------------------------------

    private static byte[] getOrCreateMasterKey(Context context) throws Exception {
        byte[] existing = cachedMasterKey;
        if (existing != null && existing.length == 32) {
            return existing;
        }
        synchronized (MASTER_LOCK) {
            if (cachedMasterKey != null && cachedMasterKey.length == 32) {
                return cachedMasterKey;
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE);
            String wrappedB64 = prefs.getString(KEY_WRAPPED_MASTER, null);
            String ivB64 = prefs.getString(KEY_WRAPPED_IV, null);

            SecretKey ksKey = Build.VERSION.SDK_INT >= 23 ? loadOrCreateKeyStoreAesKey() : null;
            if (ksKey != null && wrappedB64 != null && ivB64 != null) {
                try {
                    byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
                    byte[] wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP);
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, ksKey, new GCMParameterSpec(128, iv));
                    cachedMasterKey = c.doFinal(wrapped);
                    return cachedMasterKey;
                } catch (Throwable t) {
                    Log.w(TAG, "KeyStore unwrap retry/fallback: " + t.getMessage());
                }
            }

            if (ksKey != null) {
                byte[] fresh = new byte[32];
                new SecureRandom().nextBytes(fresh);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, ksKey);
                byte[] wrapped = c.doFinal(fresh);
                prefs.edit()
                        .putString(KEY_WRAPPED_MASTER, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                        .putString(KEY_WRAPPED_IV, Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                        .apply();
                cachedMasterKey = fresh;
                return cachedMasterKey;
            }

            // Software fallback only when hardware KeyStore cannot initialize.
            String saltB64 = prefs.getString(KEY_FALLBACK_SALT, null);
            byte[] salt;
            if (saltB64 == null) {
                salt = ColgramCryptoVault.newSalt();
                prefs.edit().putString(KEY_FALLBACK_SALT,
                        Base64.encodeToString(salt, Base64.NO_WRAP)).apply();
            } else {
                salt = Base64.decode(saltB64, Base64.NO_WRAP);
            }
            String material = context.getPackageName() + "|colgram_vault_v1";
            cachedMasterKey = ColgramCryptoVault.deriveKey(material.toCharArray(), salt);
            return cachedMasterKey;
        }
    }

    private static SecretKey loadOrCreateKeyStoreAesKey() {
        if (Build.VERSION.SDK_INT < 23) return null;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEYSTORE_ALIAS)) {
                KeyStore.Entry e = ks.getEntry(KEYSTORE_ALIAS, null);
                if (e instanceof KeyStore.SecretKeyEntry) {
                    return ((KeyStore.SecretKeyEntry) e).getSecretKey();
                }
            }
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        } catch (Throwable t) {
            Log.w(TAG, "AndroidKeyStore unavailable: " + t.getMessage());
            return null;
        }
    }

    private static void upsertIndexEntry(Context context, VaultEntry entry) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE);
        try {
            JSONArray src = new JSONArray(prefs.getString(KEY_VAULT_INDEX, "[]"));
            JSONArray dst = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                if (o == null) continue;
                String vk = o.optString("vaultKey", "");
                long uid = o.optLong("userId", 0L);
                if (entry.vaultKey.equals(vk) || (entry.userId > 0 && uid == entry.userId)) {
                    continue;
                }
                dst.put(o);
            }
            JSONObject obj = new JSONObject();
            obj.put("vaultKey", entry.vaultKey);
            obj.put("userId", entry.userId);
            obj.put("name", entry.name);
            obj.put("username", entry.username);
            obj.put("phone", entry.phone);
            obj.put("isBot", entry.isBot);
            obj.put("savedAt", entry.savedAt);
            dst.put(obj);
            prefs.edit().putString(KEY_VAULT_INDEX, dst.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "upsertIndexEntry failed", t);
        }
    }

    private static byte[] serializeBundle(VaultEntry entry, byte[] xmlBytes,
                                          Map<String, byte[]> tgnetFiles, String botToken)
            throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(1); // bundle version
        out.writeUTF(entry.vaultKey);
        out.writeLong(entry.userId);
        out.writeUTF(entry.name);
        out.writeUTF(entry.username);
        out.writeUTF(entry.phone);
        out.writeBoolean(entry.isBot);
        out.writeLong(entry.savedAt);
        out.writeUTF(botToken != null ? botToken : "");

        out.writeInt(xmlBytes != null ? xmlBytes.length : 0);
        if (xmlBytes != null && xmlBytes.length > 0) out.write(xmlBytes);

        int count = tgnetFiles != null ? tgnetFiles.size() : 0;
        out.writeInt(count);
        if (tgnetFiles != null) {
            for (Map.Entry<String, byte[]> f : tgnetFiles.entrySet()) {
                out.writeUTF(f.getKey());
                byte[] bytes = f.getValue();
                out.writeInt(bytes != null ? bytes.length : 0);
                if (bytes != null && bytes.length > 0) out.write(bytes);
            }
        }
        out.flush();
        return baos.toByteArray();
    }

    private static VaultBundle deserializeBundle(byte[] data) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int version = in.readInt();
        if (version != 1) throw new IllegalStateException("Unsupported vault bundle v" + version);
        String vaultKey = in.readUTF();
        long userId = in.readLong();
        String name = in.readUTF();
        String username = in.readUTF();
        String phone = in.readUTF();
        boolean isBot = in.readBoolean();
        long savedAt = in.readLong();
        String botToken = in.readUTF();

        int xmlLen = in.readInt();
        byte[] xmlBytes = new byte[Math.max(0, xmlLen)];
        if (xmlLen > 0) in.readFully(xmlBytes);

        int count = in.readInt();
        Map<String, byte[]> files = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String fname = in.readUTF();
            int len = in.readInt();
            byte[] buf = new byte[Math.max(0, len)];
            if (len > 0) in.readFully(buf);
            files.put(fname, buf);
        }
        VaultEntry entry = new VaultEntry(vaultKey, userId, name, username, phone, isBot, savedAt);
        return new VaultBundle(entry, xmlBytes, files, botToken.isEmpty() ? null : botToken);
    }

    private static String sanitizeKey(String raw) {
        if (raw == null || raw.isEmpty()) return "acct_0";
        return raw.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static byte[] readFileBytes(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(512, f.length()))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void patchSelectedAccountInXml(File xmlFile, int slot) {
        try {
            if (!xmlFile.exists()) return;
            String xml = new String(readFileBytes(xmlFile), StandardCharsets.UTF_8);
            String replaced = xml.replaceAll(
                    "<int\\s+name=\"selectedAccount\"\\s+value=\"-?\\d+\"\\s*/>",
                    "<int name=\"selectedAccount\" value=\"" + slot + "\" />");
            if (replaced.equals(xml) && replaced.contains("</map>")) {
                replaced = replaced.replace("</map>",
                        "    <int name=\"selectedAccount\" value=\"" + slot + "\" />\n</map>");
            }
            writeAtomic(xmlFile, replaced.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "patchSelectedAccountInXml failed: " + t.getMessage());
        }
    }

    private static void writeAtomic(File target, byte[] data) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
            fos.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            // fallback overwrite below
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
