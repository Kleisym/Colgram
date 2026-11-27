package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ColgramShieldConfig — Volatile-cached configuration for Track 2 storage cap,
 * 3-tier Virtual Infinite Storage, Lazy Shadow Vault, Malware/Huge-File Download Shield,
 * and strict Anti-Ping / Spam-Collapse controls (Contract B).
 */
public final class ColgramShieldConfig {

    private static final String PREFS_NAME = "colgram_shield_config";

    private static final String KEY_STORAGE_HARD_CAP_MB = "storage_hard_cap_mb";
    private static final String KEY_STORAGE_CAP_ENABLED = "storage_cap_enabled";
    private static final String KEY_INFINITE_VIRTUAL_STORAGE = "infinite_virtual_storage_enabled";
    private static final String KEY_LAZY_SHADOW_VAULT = "lazy_shadow_vault_enabled";
    private static final String KEY_MALWARE_SHIELD = "malware_download_shield_enabled";
    private static final String KEY_AUTODOWNLOAD_MOBILE_MB = "autodownload_max_mobile_mb";
    private static final String KEY_AUTODOWNLOAD_WIFI_MB = "autodownload_max_wifi_mb";
    private static final String KEY_ANTIPING_STRANGERS_BIG_GROUPS = "anti_ping_strangers_big_groups";
    private static final String KEY_COLLAPSE_SPAM_PINGS = "collapse_spam_pings_enabled";
    private static final String KEY_AUTOCLEAR_SERVER_MENTIONS = "anti_ping_auto_clear_server_mentions";

    public static final int DEFAULT_STORAGE_HARD_CAP_MB = 1024;
    public static final int DEFAULT_AUTODOWNLOAD_MOBILE_MB = 15;
    public static final int DEFAULT_AUTODOWNLOAD_WIFI_MB = 50;

    private static volatile SharedPreferences prefs;
    private static volatile Object prefChangeListener;

    private static volatile int storageHardCapMb = DEFAULT_STORAGE_HARD_CAP_MB;
    private static volatile boolean storageCapEnabled = true;
    private static volatile boolean infiniteVirtualStorageEnabled = true;
    private static volatile boolean lazyShadowVaultEnabled = true;
    private static volatile boolean malwareDownloadShieldEnabled = true;
    private static volatile int autoDownloadMaxMobileMb = DEFAULT_AUTODOWNLOAD_MOBILE_MB;
    private static volatile int autoDownloadMaxWifiMb = DEFAULT_AUTODOWNLOAD_WIFI_MB;
    private static volatile boolean antiPingStrangersBigGroupsEnabled = true;
    private static volatile boolean collapseSpamPingsEnabled = true;
    private static volatile boolean antiPingAutoClearServerMentionsEnabled = true;

    private ColgramShieldConfig() {
    }

    public static void init(Context ctx) {
        if (ctx == null) return;
        if (prefs != null) return;
        synchronized (ColgramShieldConfig.class) {
            if (prefs != null) return;
            Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            SharedPreferences sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            // Preserve ci183 explicit Unlimited Memory opt-in across upgrade to ci190 hard-cap defaults.
            if (!sp.contains(KEY_STORAGE_CAP_ENABLED)) {
                try {
                    ColgramConfig.init(appCtx);
                    if (ColgramConfig.isUnlimitedMemoryEnabled()) {
                        sp.edit().putBoolean(KEY_STORAGE_CAP_ENABLED, false).apply();
                    }
                } catch (Throwable ignored) {}
            }
            prefs = sp;
            loadAll(sp);
            if (prefChangeListener == null) {
                prefChangeListener = (SharedPreferences.OnSharedPreferenceChangeListener) (p, key) -> {
                    if (p == null) return;
                    if (key == null) {
                        loadAll(p);
                    } else {
                        updateKey(p, key);
                    }
                };
            }
            sp.registerOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener) prefChangeListener);
        }
    }

    private static void loadAll(SharedPreferences p) {
        if (p == null) return;
        storageHardCapMb = Math.max(0, p.getInt(KEY_STORAGE_HARD_CAP_MB, DEFAULT_STORAGE_HARD_CAP_MB));
        storageCapEnabled = p.getBoolean(KEY_STORAGE_CAP_ENABLED, true);
        infiniteVirtualStorageEnabled = p.getBoolean(KEY_INFINITE_VIRTUAL_STORAGE, true);
        lazyShadowVaultEnabled = p.getBoolean(KEY_LAZY_SHADOW_VAULT, true);
        malwareDownloadShieldEnabled = p.getBoolean(KEY_MALWARE_SHIELD, true);
        autoDownloadMaxMobileMb = clampMobileMb(p.getInt(KEY_AUTODOWNLOAD_MOBILE_MB, DEFAULT_AUTODOWNLOAD_MOBILE_MB));
        autoDownloadMaxWifiMb = clampWifiMb(p.getInt(KEY_AUTODOWNLOAD_WIFI_MB, DEFAULT_AUTODOWNLOAD_WIFI_MB));
        antiPingStrangersBigGroupsEnabled = p.getBoolean(KEY_ANTIPING_STRANGERS_BIG_GROUPS, true);
        collapseSpamPingsEnabled = p.getBoolean(KEY_COLLAPSE_SPAM_PINGS, true);
        antiPingAutoClearServerMentionsEnabled = p.getBoolean(KEY_AUTOCLEAR_SERVER_MENTIONS, true);
    }

    private static void updateKey(SharedPreferences p, String key) {
        switch (key) {
            case KEY_STORAGE_HARD_CAP_MB:
                storageHardCapMb = Math.max(0, p.getInt(KEY_STORAGE_HARD_CAP_MB, DEFAULT_STORAGE_HARD_CAP_MB));
                break;
            case KEY_STORAGE_CAP_ENABLED:
                storageCapEnabled = p.getBoolean(KEY_STORAGE_CAP_ENABLED, true);
                break;
            case KEY_INFINITE_VIRTUAL_STORAGE:
                infiniteVirtualStorageEnabled = p.getBoolean(KEY_INFINITE_VIRTUAL_STORAGE, true);
                break;
            case KEY_LAZY_SHADOW_VAULT:
                lazyShadowVaultEnabled = p.getBoolean(KEY_LAZY_SHADOW_VAULT, true);
                break;
            case KEY_MALWARE_SHIELD:
                malwareDownloadShieldEnabled = p.getBoolean(KEY_MALWARE_SHIELD, true);
                break;
            case KEY_AUTODOWNLOAD_MOBILE_MB:
                autoDownloadMaxMobileMb = clampMobileMb(p.getInt(KEY_AUTODOWNLOAD_MOBILE_MB, DEFAULT_AUTODOWNLOAD_MOBILE_MB));
                break;
            case KEY_AUTODOWNLOAD_WIFI_MB:
                autoDownloadMaxWifiMb = clampWifiMb(p.getInt(KEY_AUTODOWNLOAD_WIFI_MB, DEFAULT_AUTODOWNLOAD_WIFI_MB));
                break;
            case KEY_ANTIPING_STRANGERS_BIG_GROUPS:
                antiPingStrangersBigGroupsEnabled = p.getBoolean(KEY_ANTIPING_STRANGERS_BIG_GROUPS, true);
                break;
            case KEY_COLLAPSE_SPAM_PINGS:
                collapseSpamPingsEnabled = p.getBoolean(KEY_COLLAPSE_SPAM_PINGS, true);
                break;
            case KEY_AUTOCLEAR_SERVER_MENTIONS:
                antiPingAutoClearServerMentionsEnabled = p.getBoolean(KEY_AUTOCLEAR_SERVER_MENTIONS, true);
                break;
            default:
                break;
        }
    }

    private static int clampMobileMb(int mb) {
        return Math.max(1, Math.min(50, mb));
    }

    private static int clampWifiMb(int mb) {
        return Math.max(1, Math.min(150, mb));
    }

    public static int getStorageHardCapMb() {
        return storageHardCapMb;
    }

    public static void setStorageHardCapMb(int mb) {
        int val = Math.max(0, mb);
        storageHardCapMb = val;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putInt(KEY_STORAGE_HARD_CAP_MB, val).apply();
        }
    }

    public static boolean isStorageCapEnabled() {
        return storageCapEnabled;
    }

    public static void setStorageCapEnabled(boolean v) {
        storageCapEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putBoolean(KEY_STORAGE_CAP_ENABLED, v).apply();
        }
    }

    public static long getStorageHardCapBytes() {
        if (!storageCapEnabled || storageHardCapMb <= 0 || ColgramConfig.isUnlimitedMemoryEnabled()) {
            return 0L;
        }
        return (long) storageHardCapMb * 1024L * 1024L;
    }

    public static boolean isInfiniteVirtualStorageEnabled() {
        return infiniteVirtualStorageEnabled;
    }

    public static void setInfiniteVirtualStorageEnabled(boolean v) {
        infiniteVirtualStorageEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putBoolean(KEY_INFINITE_VIRTUAL_STORAGE, v).apply();
        }
    }

    public static boolean isLazyShadowVaultEnabled() {
        return lazyShadowVaultEnabled;
    }

    public static void setLazyShadowVaultEnabled(boolean v) {
        lazyShadowVaultEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putBoolean(KEY_LAZY_SHADOW_VAULT, v).apply();
        }
    }

    public static boolean isMalwareDownloadShieldEnabled() {
        return malwareDownloadShieldEnabled;
    }

    public static void setMalwareDownloadShieldEnabled(boolean v) {
        malwareDownloadShieldEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putBoolean(KEY_MALWARE_SHIELD, v).apply();
        }
    }

    public static int getAutoDownloadMaxMobileMb() {
        return autoDownloadMaxMobileMb;
    }

    public static void setAutoDownloadMaxMobileMb(int mb) {
        int val = clampMobileMb(mb);
        autoDownloadMaxMobileMb = val;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putInt(KEY_AUTODOWNLOAD_MOBILE_MB, val).apply();
        }
    }

    public static int getAutoDownloadMaxWifiMb() {
        return autoDownloadMaxWifiMb;
    }

    public static void setAutoDownloadMaxWifiMb(int mb) {
        int val = clampWifiMb(mb);
        autoDownloadMaxWifiMb = val;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putInt(KEY_AUTODOWNLOAD_WIFI_MB, val).apply();
        }
    }

    public static boolean isAntiPingStrangersBigGroupsEnabled() {
        return antiPingStrangersBigGroupsEnabled;
    }

    public static void setAntiPingStrangersBigGroupsEnabled(boolean v) {
        antiPingStrangersBigGroupsEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putBoolean(KEY_ANTIPING_STRANGERS_BIG_GROUPS, v).apply();
        }
    }

    public static boolean isCollapseSpamPingsEnabled() {
        return collapseSpamPingsEnabled;
    }

    public static void setCollapseSpamPingsEnabled(boolean v) {
        collapseSpamPingsEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putBoolean(KEY_COLLAPSE_SPAM_PINGS, v).apply();
        }
    }

    public static boolean isAntiPingAutoClearServerMentionsEnabled() {
        return antiPingAutoClearServerMentionsEnabled;
    }

    public static void setAntiPingAutoClearServerMentionsEnabled(boolean v) {
        antiPingAutoClearServerMentionsEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) {
            sp.edit().putBoolean(KEY_AUTOCLEAR_SERVER_MENTIONS, v).apply();
        }
    }

    /** Resets volatile defaults for isolated JVM unit tests. */
    public static void resetForTest() {
        prefs = null;
        storageHardCapMb = DEFAULT_STORAGE_HARD_CAP_MB;
        storageCapEnabled = true;
        infiniteVirtualStorageEnabled = true;
        lazyShadowVaultEnabled = true;
        malwareDownloadShieldEnabled = true;
        autoDownloadMaxMobileMb = DEFAULT_AUTODOWNLOAD_MOBILE_MB;
        autoDownloadMaxWifiMb = DEFAULT_AUTODOWNLOAD_WIFI_MB;
        antiPingStrangersBigGroupsEnabled = true;
        collapseSpamPingsEnabled = true;
        antiPingAutoClearServerMentionsEnabled = true;
    }
}
