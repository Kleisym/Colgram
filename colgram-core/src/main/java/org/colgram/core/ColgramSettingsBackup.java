package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Portable JSON backup and restore for Colgram settings stored in {@code colgram_secure_config}.
 * Handles all primitive preference types (boolean, int, long, float, string, string_set) cleanly.
 */
public final class ColgramSettingsBackup {
    public static final int SCHEMA_VERSION = 1;
    private static final String PREFS_NAME = "colgram_secure_config";

    private ColgramSettingsBackup() {}

    /** Exports all persisted Colgram settings into a formatted JSON string. */
    public static String exportToJson(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return serializeEntries(prefs.getAll(), System.currentTimeMillis());
    }

    /** Serializes a raw preferences map into formatted backup JSON (also used by host unit tests). */
    public static String serializeEntries(Map<String, ?> all, long exportedAtMillis) {
        try {
            JSONObject root = new JSONObject();
            root.put("app", "Colgram");
            root.put("schema", SCHEMA_VERSION);
            root.put("exported_at", exportedAtMillis);

            JSONObject entries = new JSONObject();
            if (all != null) {
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    if (key == null || value == null) continue;
                    JSONObject item = new JSONObject();
                    if (value instanceof Boolean) {
                        item.put("type", "bool");
                        item.put("value", value);
                    } else if (value instanceof Integer) {
                        item.put("type", "int");
                        item.put("value", value);
                    } else if (value instanceof Long) {
                        item.put("type", "long");
                        item.put("value", value);
                    } else if (value instanceof Float) {
                        item.put("type", "float");
                        item.put("value", ((Float) value).doubleValue());
                    } else if (value instanceof String) {
                        item.put("type", "string");
                        item.put("value", value);
                    } else if (value instanceof Set<?>) {
                        item.put("type", "string_set");
                        JSONArray arr = new JSONArray();
                        for (Object element : (Set<?>) value) {
                            if (element != null) arr.put(String.valueOf(element));
                        }
                        item.put("value", arr);
                    } else {
                        continue;
                    }
                    entries.put(key, item);
                }
            }
            root.put("entries", entries);
            return root.toString(2);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Colgram settings backup", e);
        }
    }

    /**
     * Imports settings from a JSON backup string into {@code colgram_secure_config}.
     * Returns the count of restored entries.
     */
    public static int importFromJson(Context context, String json) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        int count = applyJsonToEditor(json, editor);
        editor.apply();
        ColgramConfig.init(context.getApplicationContext());
        return count;
    }

    /** Parses and validates backup JSON and stages mutations onto a {@link SharedPreferences.Editor}. */
    static int applyJsonToEditor(String json, SharedPreferences.Editor editor) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty settings backup JSON");
        }
        try {
            JSONObject root = new JSONObject(json);
            int schema = root.optInt("schema", -1);
            if (schema <= 0 || schema > SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported backup schema: " + schema);
            }
            JSONObject entries = root.optJSONObject("entries");
            if (entries == null) {
                throw new IllegalArgumentException("Missing 'entries' object in backup JSON");
            }
            int restored = 0;
            Iterator<String> keys = entries.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key == null || key.isEmpty()) continue;
                JSONObject item = entries.optJSONObject(key);
                if (item == null) continue;
                String type = item.optString("type", "");
                if (!item.has("value") || item.isNull("value")) continue;
                switch (type) {
                    case "bool":
                        editor.putBoolean(key, item.getBoolean("value"));
                        restored++;
                        break;
                    case "int":
                        editor.putInt(key, item.getInt("value"));
                        restored++;
                        break;
                    case "long":
                        editor.putLong(key, item.getLong("value"));
                        restored++;
                        break;
                    case "float":
                        editor.putFloat(key, (float) item.getDouble("value"));
                        restored++;
                        break;
                    case "string":
                        editor.putString(key, item.getString("value"));
                        restored++;
                        break;
                    case "string_set":
                        JSONArray arr = item.getJSONArray("value");
                        Set<String> set = new HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            set.add(arr.getString(i));
                        }
                        editor.putStringSet(key, set);
                        restored++;
                        break;
                    default:
                        break;
                }
            }
            return restored;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid settings backup JSON: " + e.getMessage(), e);
        }
    }

    /** Writes backup JSON cleanly to {@code target} in UTF-8. */
    public static File exportToFile(Context context, File target) throws IOException {
        if (target == null) {
            throw new IOException("Backup target file is null");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create backup directory: " + parent);
        }
        String json = exportToJson(context);
        File tmp = new File(parent != null ? parent : target.getAbsoluteFile().getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
        if (target.exists() && !target.delete()) {
            // Fall back to direct overwrite if delete fails.
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                if (tmp.exists()) tmp.delete();
            }
        }
        return target;
    }

    private static final String ENC_PREFIX = "COLGRAM_ENC1:";
    private static final byte[] BACKUP_AAD = "ColgramBackupV1".getBytes(StandardCharsets.UTF_8);

    public static boolean isEncryptedBackup(String payload) {
        return payload != null && payload.trim().startsWith(ENC_PREFIX);
    }

    /** Exports settings wrapped in AES-256-GCM using PBKDF2-HMAC-SHA256 from {@code passphrase}. */
    public static String exportEncrypted(Context context, char[] passphrase) throws IOException {
        if (passphrase == null || passphrase.length == 0) {
            return exportToJson(context);
        }
        try {
            byte[] plain = exportToJson(context).getBytes(StandardCharsets.UTF_8);
            byte[] salt = ColgramCryptoVault.newSalt();
            byte[] key = ColgramCryptoVault.deriveKey(passphrase, salt);
            try {
                byte[] sealed = ColgramCryptoVault.encryptWithAad(key, plain, BACKUP_AAD);
                byte[] combined = new byte[salt.length + sealed.length];
                System.arraycopy(salt, 0, combined, 0, salt.length);
                System.arraycopy(sealed, 0, combined, salt.length, sealed.length);
                return ENC_PREFIX + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP);
            } finally {
                ColgramCryptoVault.zeroize(key);
                ColgramCryptoVault.zeroize(plain);
            }
        } catch (Exception e) {
            throw new IOException("Encrypted backup failed: " + e.getMessage(), e);
        }
    }

    /** Decrypts and imports either an encrypted backup payload or legacy plain JSON. */
    public static int importAuto(Context context, String payload, char[] passphrase) throws IOException {
        if (isEncryptedBackup(payload)) {
            if (passphrase == null || passphrase.length == 0) {
                throw new IOException("Passphrase required for encrypted backup");
            }
            try {
                String b64 = payload.trim().substring(ENC_PREFIX.length()).trim();
                byte[] combined = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
                if (combined.length < 16 + 30) {
                    throw new IOException("Corrupt encrypted backup");
                }
                byte[] salt = java.util.Arrays.copyOfRange(combined, 0, 16);
                byte[] sealed = java.util.Arrays.copyOfRange(combined, 16, combined.length);
                byte[] key = ColgramCryptoVault.deriveKey(passphrase, salt);
                byte[] plain = null;
                try {
                    plain = ColgramCryptoVault.decryptWithAad(key, sealed, BACKUP_AAD);
                    return importFromJson(context, new String(plain, StandardCharsets.UTF_8));
                } finally {
                    ColgramCryptoVault.zeroize(key);
                    ColgramCryptoVault.zeroize(plain);
                }
            } catch (IOException ioe) {
                throw ioe;
            } catch (Exception e) {
                throw new IOException("Wrong passphrase or corrupt encrypted backup", e);
            }
        }
        return importFromJson(context, payload);
    }

    /** Reads backup JSON from {@code source} and restores settings into Colgram config. */
    public static int importFromFile(Context context, File source) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("Backup file not found");
        }
        byte[] data;
        try (FileInputStream fis = new FileInputStream(source);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            data = bos.toByteArray();
        }
        return importFromJson(context, new String(data, StandardCharsets.UTF_8));
    }
}
