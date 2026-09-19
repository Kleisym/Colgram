package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ColgramConfig — Central configuration management for privacy, cloaking,
 * storage sandboxing, ghost mode, and proxy bypass settings.
 */
public class ColgramConfig {
    private static final String PREFS_NAME = "colgram_secure_config";

    // Cloaking
    private static final String KEY_CLOAK_ENABLED = "cloak_enabled";
    private static final String KEY_SPOOF_MODEL = "spoof_device_model";
    private static final String KEY_SPOOF_SYSTEM_VERSION = "spoof_system_version";
    private static final String KEY_SPOOF_LANG = "spoof_system_lang";

    // Message Vault
    private static final String KEY_ANTI_DELETE_ENABLED = "anti_delete_enabled";
    private static final String KEY_EDIT_HISTORY_ENABLED = "edit_history_enabled";
    private static final String KEY_PRESERVE_MEDIA = "preserve_deleted_media";

    // Ghost Mode
    private static final String KEY_GHOST_READ = "ghost_read_receipts";
    private static final String KEY_GHOST_TYPING = "ghost_typing_indicator";
    private static final String KEY_GHOST_ONLINE = "ghost_offline_mode";
    private static final String KEY_BYPASS_FLAG_SECURE = "bypass_flag_secure";

    // Storage Sandbox
    private static final String KEY_SANDBOX_STORAGE_ENABLED = "sandbox_storage_enabled";
    private static final String KEY_CUSTOM_STORAGE_DIR = "custom_storage_dir";

    // Network & Proxies
    private static final String KEY_BUILTIN_PROXY_ENABLED = "builtin_proxy_enabled";
    private static final String KEY_DOH_ENABLED = "doh_resolver_enabled";
    private static final String KEY_UPDATE_REPO = "github_update_repo";

    // Defaults
    public static final String DEFAULT_MODEL = "Google Pixel 8 Pro";
    public static final String DEFAULT_SYSTEM_VERSION = "SDK 34 (Android 14)";
    public static final String DEFAULT_LANG = "en";
    public static final String DEFAULT_STORAGE_FOLDER = "Colgram";
    public static final String DEFAULT_UPDATE_REPO = "Colgram/Colgram";

    private static SharedPreferences prefs;

    public static void init(Context context) {
        if (prefs == null && context != null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    // --- Cloaking ---
    public static boolean isCloakEnabled() {
        return prefs == null || prefs.getBoolean(KEY_CLOAK_ENABLED, true);
    }

    public static void setCloakEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_CLOAK_ENABLED, enabled).apply();
    }

    public static String getSpoofDeviceModel() {
        return prefs != null ? prefs.getString(KEY_SPOOF_MODEL, DEFAULT_MODEL) : DEFAULT_MODEL;
    }

    public static void setSpoofDeviceModel(String model) {
        if (prefs != null) prefs.edit().putString(KEY_SPOOF_MODEL, model).apply();
    }

    public static String getSpoofSystemVersion() {
        return prefs != null ? prefs.getString(KEY_SPOOF_SYSTEM_VERSION, DEFAULT_SYSTEM_VERSION) : DEFAULT_SYSTEM_VERSION;
    }

    public static void setSpoofSystemVersion(String version) {
        if (prefs != null) prefs.edit().putString(KEY_SPOOF_SYSTEM_VERSION, version).apply();
    }

    public static String getSpoofLang() {
        return prefs != null ? prefs.getString(KEY_SPOOF_LANG, DEFAULT_LANG) : DEFAULT_LANG;
    }

    public static void setSpoofLang(String lang) {
        if (prefs != null) prefs.edit().putString(KEY_SPOOF_LANG, lang).apply();
    }

    // --- Message Vault ---
    public static boolean isAntiDeleteEnabled() {
        return prefs == null || prefs.getBoolean(KEY_ANTI_DELETE_ENABLED, true);
    }

    public static void setAntiDeleteEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_ANTI_DELETE_ENABLED, enabled).apply();
    }

    public static boolean isEditHistoryEnabled() {
        return prefs == null || prefs.getBoolean(KEY_EDIT_HISTORY_ENABLED, true);
    }

    public static void setEditHistoryEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_EDIT_HISTORY_ENABLED, enabled).apply();
    }

    public static boolean isPreserveMediaEnabled() {
        return prefs == null || prefs.getBoolean(KEY_PRESERVE_MEDIA, true);
    }

    public static void setPreserveMediaEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_PRESERVE_MEDIA, enabled).apply();
    }

    // --- Ghost Mode ---
    public static boolean isGhostReadEnabled() {
        return prefs != null && prefs.getBoolean(KEY_GHOST_READ, true);
    }

    public static void setGhostReadEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_GHOST_READ, enabled).apply();
    }

    public static boolean isGhostTypingEnabled() {
        return prefs != null && prefs.getBoolean(KEY_GHOST_TYPING, true);
    }

    public static void setGhostTypingEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_GHOST_TYPING, enabled).apply();
    }

    public static boolean isGhostOnlineEnabled() {
        return prefs != null && prefs.getBoolean(KEY_GHOST_ONLINE, false);
    }

    public static void setGhostOnlineEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_GHOST_ONLINE, enabled).apply();
    }

    public static boolean isBypassFlagSecureEnabled() {
        return prefs == null || prefs.getBoolean(KEY_BYPASS_FLAG_SECURE, true);
    }

    public static void setBypassFlagSecureEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_BYPASS_FLAG_SECURE, enabled).apply();
    }

    // --- Storage Sandbox ---
    public static boolean isSandboxStorageEnabled() {
        return prefs == null || prefs.getBoolean(KEY_SANDBOX_STORAGE_ENABLED, true);
    }

    public static void setSandboxStorageEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_SANDBOX_STORAGE_ENABLED, enabled).apply();
    }

    public static String getCustomStorageDir() {
        return prefs != null ? prefs.getString(KEY_CUSTOM_STORAGE_DIR, DEFAULT_STORAGE_FOLDER) : DEFAULT_STORAGE_FOLDER;
    }

    // --- Network & Proxies ---
    public static boolean isBuiltinProxyEnabled() {
        return prefs == null || prefs.getBoolean(KEY_BUILTIN_PROXY_ENABLED, true);
    }

    public static void setBuiltinProxyEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_BUILTIN_PROXY_ENABLED, enabled).apply();
    }

    public static boolean isDohEnabled() {
        return prefs == null || prefs.getBoolean(KEY_DOH_ENABLED, true);
    }

    public static void setDohEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DOH_ENABLED, enabled).apply();
    }

    public static String getUpdateRepo() {
        return prefs != null ? prefs.getString(KEY_UPDATE_REPO, DEFAULT_UPDATE_REPO) : DEFAULT_UPDATE_REPO;
    }
}
