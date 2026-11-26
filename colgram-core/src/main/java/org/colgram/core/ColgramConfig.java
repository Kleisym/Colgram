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
    private static final String KEY_CLOAK_INIT_BUMP = "cloak_init_version_bump";
    private static final String KEY_CLOAK_REINIT_MIGRATED = "colgram_cloak_reinit_v2";

    // Message Vault
    private static final String KEY_ANTI_DELETE_ENABLED = "anti_delete_enabled";
    private static final String KEY_EDIT_HISTORY_ENABLED = "edit_history_enabled";
    private static final String KEY_CHAT_WALLPAPER_ENABLED = "chat_wallpaper_enabled";
    private static final String KEY_PRESERVE_MEDIA = "preserve_deleted_media";
    private static final String KEY_ANTI_DELETE_WIPE = "anti_delete_wipe_row";
    private static final String KEY_ANTI_DELETE_HIGHLIGHT = "anti_delete_highlight";

    // Ghost Mode
    private static final String KEY_GHOST_READ = "ghost_read_receipts";
    private static final String KEY_GHOST_TYPING = "ghost_typing_indicator";
    private static final String KEY_GHOST_ONLINE = "ghost_offline_mode";
    private static final String KEY_BYPASS_FLAG_SECURE = "bypass_flag_secure";

    // Storage Sandbox
    private static final String KEY_SANDBOX_STORAGE_ENABLED = "sandbox_storage_enabled";
    private static final String KEY_CUSTOM_STORAGE_DIR = "custom_storage_dir";

    // Privacy on login
    private static final String KEY_AUTO_HIDE_PHONE = "auto_hide_phone_number";

    // Network & Proxies
    private static final String KEY_BUILTIN_PROXY_ENABLED = "builtin_proxy_enabled";
    private static final String KEY_DOH_ENABLED = "doh_resolver_enabled";
    private static final String KEY_PROXY_BROWSER_ENABLED = "proxy_browser_enabled";
    private static final String KEY_UPDATE_REPO = "github_update_repo";
    private static final String KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled";

    // Theme
    private static final String KEY_CYBER_THEME_ENABLED = "cyber_theme_enabled";

    // Version switching
    private static final String KEY_PENDING_UPSTREAM_TAG = "pending_upstream_tag";

    // Mini-app floating windows
    private static final String KEY_MINIAPP_PIP_ENABLED = "miniapp_pip_enabled";
    private static final String KEY_MINIAPP_PIP_MAX = "miniapp_pip_max_windows";
    private static final String KEY_MINIAPP_PIP_POS_PREFIX = "miniapp_pip_pos_";

    // Defaults
    public static final String DEFAULT_MODEL = "Google Pixel 8 Pro";
    public static final String DEFAULT_SYSTEM_VERSION = "SDK 34 (Android 14)";
    public static final String DEFAULT_LANG = "en";
    public static final String DEFAULT_STORAGE_FOLDER = "Colgram";
    public static final String DEFAULT_UPDATE_REPO = "Kleisym/Colgram";
    public static final String DEFAULT_AUTO_REPLY_TEXT =
            "Привет, {first_name}! Сейчас занят, отвечу позже. ({time})";
    public static final String DEFAULT_AUTO_REPLY_AWAY_TEXT =
            "Сейчас не в сети (нерабочее время), {first_name}. Отвечу утром!";
    public static final String DEFAULT_QUICK_BAR_ITEMS = "ghost,proxy,koliti,saved,theme";

    private static volatile SharedPreferences prefs;

    // In-memory volatile caches populated in init() and kept synchronized via setters +
    // SharedPreferences.OnSharedPreferenceChangeListener so UI/network threads never block
    // on SharedPreferencesImpl locks during hot calls.
    private static volatile boolean cloakEnabled = true;
    private static volatile String spoofDeviceModel = DEFAULT_MODEL;
    private static volatile String spoofSystemVersion = DEFAULT_SYSTEM_VERSION;
    private static volatile String spoofLang = DEFAULT_LANG;
    private static volatile int cloakInitVersionBump = 0;
    private static volatile boolean antiDeleteEnabled = true;
    private static volatile boolean editHistoryEnabled = true;
    private static volatile boolean antiDeleteHighlightEnabled = true;
    private static volatile boolean antiDeleteWipeEnabled = false;
    private static volatile boolean chatWallpaperEnabled = true;
    private static volatile boolean preserveMediaEnabled = true;
    private static volatile boolean ghostReadEnabled = false;
    private static volatile boolean ghostTypingEnabled = false;
    private static volatile boolean ghostOnlineEnabled = false;
    private static volatile boolean bypassFlagSecureEnabled = true;
    private static volatile boolean autoHidePhoneEnabled = true;
    private static volatile boolean sandboxStorageEnabled = true;
    private static volatile String customStorageDir = DEFAULT_STORAGE_FOLDER;
    private static volatile boolean builtinProxyEnabled = true;
    private static volatile boolean dohEnabled = true;
    private static volatile boolean proxyBrowserEnabled = true;
    private static volatile String updateRepo = DEFAULT_UPDATE_REPO;
    private static volatile boolean autoUpdateEnabled = true;
    private static volatile boolean cyberThemeEnabled = false;
    private static volatile String pendingUpstreamTag = null;
    private static volatile boolean miniAppPipEnabled = true;
    private static volatile int miniAppPipMaxWindows = 0;
    private static volatile boolean autoReplyEnabled = false;
    private static volatile String autoReplyText = DEFAULT_AUTO_REPLY_TEXT;
    private static volatile boolean autoReplyInGroups = false;
    private static volatile int autoReplyCooldownSec = 300;
    private static volatile boolean autoReplyScheduleEnabled = false;
    private static volatile boolean autoReplyOnlyWhenAway = false;
    private static volatile int autoReplyStartMin = 22 * 60;
    private static volatile int autoReplyEndMin = 9 * 60;
    private static volatile String autoReplyAwayText = DEFAULT_AUTO_REPLY_AWAY_TEXT;
    private static volatile int autoReplyAudience = ColgramAutoReplyPolicy.AUDIENCE_ALL;
    private static volatile boolean autoReplyToBots = false;
    private static volatile String autoReplyBlacklist = "";
    private static volatile int liveWallpaperStyle = ColgramLiveWallpaper.STYLE_AURORA;
    private static volatile boolean keywordAlertsEnabled = false;
    private static volatile String keywordAlertsList = "";
    private static volatile boolean messageLoggerEnabled = false;
    private static volatile boolean warpEnabled = false;
    private static volatile String botTokenPrefill = "";
    private static volatile boolean autoProxyEnabled = false;
    private static volatile boolean strictProxySecurityEnabled = true;
    private static volatile String browserRouteMode = "LOCAL_DPI";
    private static volatile String browserRouteSocks = "";
    private static volatile boolean maxAnonymityEnabled = false;
    private static volatile boolean saveOneTimeMediaEnabled = true;
    private static volatile boolean hideScreenshotNotifyEnabled = true;
    private static volatile boolean stealthStoriesEnabled = true;
    private static volatile boolean forwardNoQuoteEnabled = false;
    private static volatile boolean hidePhoneLocalEnabled = false;
    private static volatile boolean antiDeleteSelfEnabled = true;
    private static volatile boolean confirmCallsEnabled = false;
    private static volatile boolean confirmStickersEnabled = false;
    private static volatile boolean confirmLinksEnabled = false;
    private static volatile boolean tlsMimicEnabled = true;
    private static volatile boolean ipv6BypassEnabled = false;
    private static volatile boolean dcRemapEnabled = false;
    private static volatile boolean dcRemapLoaded = false;
    private static volatile String relayUrl = "";
    private static volatile boolean spamGuardEnabled = true;
    private static volatile boolean notificationAdFilterEnabled = false;
    private static volatile boolean saveLeadsEnabled = false;
    private static volatile boolean broadcasterEnabled = false;
    private static volatile boolean autoSubEnabled = false;
    private static volatile String defaultMuteMode = "silent";
    private static volatile String helperBotToken = "";
    private static volatile String colemailerState = "";
    private static volatile boolean unlimitedMemoryConfigured = false;
    private static volatile boolean unlimitedMemoryEnabled = true;
    private static volatile boolean autoCompressStorageEnabled = true;
    private static volatile boolean alwaysBackgroundEnabled = true;
    private static volatile long lastAutoUpdateCheckMs = 0L;
    private static volatile boolean showMessageSecondsEnabled = false;
    private static volatile boolean showMessageIdEnabled = true;
    private static volatile boolean defenderEnabled = true;
    private static volatile boolean defenderAutoCloseEnabled = true;
    private static volatile boolean defenderBlockVoipP2pEnabled = true;
    private static volatile boolean defenderBlockBotLocationEnabled = true;
    private static volatile boolean defenderStripTrackersEnabled = true;
    private static volatile boolean defenderWebViewHardeningEnabled = true;
    private static volatile long defenderLastScanMs = 0L;
    private static volatile int defenderFixedTotal = 0;
    private static volatile int defenderTrackersStripped = 0;
    private static volatile int defenderP2pBlocked = 0;
    private static volatile boolean quickBarEnabled = true;
    private static volatile java.util.List<String> quickBarItems = parseQuickBarItems(DEFAULT_QUICK_BAR_ITEMS);

    private static final String NULL_SENTINEL = new String("");
    private static final Object DEFENDER_COUNTER_LOCK = new Object();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> pinnedEndpointsCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, String> miniAppPipPosCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> chatVideoWallpapersCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> chatImageWallpapersCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile Object prefChangeListener;

    public static void init(Context context) {
        if (prefs == null && context != null) {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            // Upgrade defaults once so anti-delete self-preserve & unlimited auto-compress storage are always ON by default.
            if (!sp.getBoolean("colgram_defaults_v3_migrated", false)) {
                sp.edit()
                        .putBoolean(KEY_ANTI_DELETE_ENABLED, true)
                        .putBoolean(KEY_ANTI_DELETE_SELF, true)
                        .putBoolean(KEY_ANTI_DELETE_WIPE, false)
                        .putBoolean(KEY_ANTI_DELETE_HIGHLIGHT, true)
                        .putBoolean(KEY_UNLIMITED_MEMORY, true)
                        .putBoolean("auto_compress_storage_enabled", true)
                        .putBoolean("colgram_defaults_v3_migrated", true)
                        .apply();
            }
            // One-time safety migration: lead auto-forwarding to Saved Messages must be strictly opt-in
            // so messages from other chats are NEVER silently forwarded into Saved Messages by default.
            if (!sp.getBoolean("colgram_save_leads_optin_v1", false)) {
                sp.edit()
                        .putBoolean(KEY_COLEMAILER_SAVE_LEADS, false)
                        .putBoolean("colgram_save_leads_optin_v1", true)
                        .apply();
            }
            if (!sp.getBoolean(KEY_LIVE_WALLPAPER_WAVES_MIGRATED, false)) {
                int cur = sp.getInt(KEY_LIVE_WALLPAPER_STYLE, ColgramLiveWallpaper.STYLE_AURORA);
                if (cur == ColgramLiveWallpaper.STYLE_OFF) {
                    cur = ColgramLiveWallpaper.STYLE_AURORA;
                }
                sp.edit()
                        .putInt(KEY_LIVE_WALLPAPER_STYLE, cur)
                        .putBoolean(KEY_LIVE_WALLPAPER_WAVES_MIGRATED, true)
                        .apply();
            }
            if (!sp.getBoolean(KEY_CLOAK_REINIT_MIGRATED, false)) {
                int nextBump = sp.getInt(KEY_CLOAK_INIT_BUMP, 0) + 1;
                if (nextBump > 90000) nextBump = 1;
                sp.edit()
                        .putInt(KEY_CLOAK_INIT_BUMP, nextBump)
                        .putBoolean(KEY_CLOAK_REINIT_MIGRATED, true)
                        .apply();
            }
            prefs = sp;
            loadAllCachedPrefs(sp);
            if (prefChangeListener == null) {
                prefChangeListener = (SharedPreferences.OnSharedPreferenceChangeListener) (sharedPreferences, key) -> {
                    if (sharedPreferences == null) return;
                    if (key == null) {
                        loadAllCachedPrefs(sharedPreferences);
                    } else {
                        updateCachedKey(sharedPreferences, key);
                    }
                };
            }
            sp.registerOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener) prefChangeListener);
        }
        // The WARP row's gate asks whether the MASQUE client exists without loading it, and needs a
        // Context to answer from the package. ColgramConfig.init is the one initialiser every entry
        // point already calls, so it is where that Context is available.
        if (context != null) {
            ColgramMasqueNative.setProbeContext(context.getApplicationContext());
            ColgramDesignConfig.init(context.getApplicationContext());
        }
    }

    private static void loadAllCachedPrefs(SharedPreferences p) {
        if (p == null) return;
        cloakEnabled = p.getBoolean(KEY_CLOAK_ENABLED, true);
        spoofDeviceModel = p.getString(KEY_SPOOF_MODEL, DEFAULT_MODEL);
        spoofSystemVersion = p.getString(KEY_SPOOF_SYSTEM_VERSION, DEFAULT_SYSTEM_VERSION);
        spoofLang = p.getString(KEY_SPOOF_LANG, DEFAULT_LANG);
        cloakInitVersionBump = p.getInt(KEY_CLOAK_INIT_BUMP, 0);
        antiDeleteEnabled = p.getBoolean(KEY_ANTI_DELETE_ENABLED, true);
        editHistoryEnabled = p.getBoolean(KEY_EDIT_HISTORY_ENABLED, true);
        antiDeleteHighlightEnabled = p.getBoolean(KEY_ANTI_DELETE_HIGHLIGHT, true);
        antiDeleteWipeEnabled = p.getBoolean(KEY_ANTI_DELETE_WIPE, false);
        chatWallpaperEnabled = p.getBoolean(KEY_CHAT_WALLPAPER_ENABLED, true);
        preserveMediaEnabled = p.getBoolean(KEY_PRESERVE_MEDIA, true);
        ghostReadEnabled = p.getBoolean(KEY_GHOST_READ, true);
        ghostTypingEnabled = p.getBoolean(KEY_GHOST_TYPING, true);
        ghostOnlineEnabled = p.getBoolean(KEY_GHOST_ONLINE, false);
        bypassFlagSecureEnabled = p.getBoolean(KEY_BYPASS_FLAG_SECURE, true);
        autoHidePhoneEnabled = p.getBoolean(KEY_AUTO_HIDE_PHONE, true);
        sandboxStorageEnabled = p.getBoolean(KEY_SANDBOX_STORAGE_ENABLED, true);
        customStorageDir = p.getString(KEY_CUSTOM_STORAGE_DIR, DEFAULT_STORAGE_FOLDER);
        builtinProxyEnabled = p.getBoolean(KEY_BUILTIN_PROXY_ENABLED, true);
        dohEnabled = p.getBoolean(KEY_DOH_ENABLED, true);
        proxyBrowserEnabled = p.getBoolean(KEY_PROXY_BROWSER_ENABLED, true);
        updateRepo = p.getString(KEY_UPDATE_REPO, DEFAULT_UPDATE_REPO);
        autoUpdateEnabled = p.getBoolean(KEY_AUTO_UPDATE_ENABLED, true);
        cyberThemeEnabled = p.getBoolean(KEY_CYBER_THEME_ENABLED, false);
        pendingUpstreamTag = p.getString(KEY_PENDING_UPSTREAM_TAG, null);
        miniAppPipEnabled = p.getBoolean(KEY_MINIAPP_PIP_ENABLED, true);
        miniAppPipMaxWindows = p.getInt(KEY_MINIAPP_PIP_MAX, 0);
        autoReplyEnabled = p.getBoolean(KEY_AUTO_REPLY_ENABLED, false);
        autoReplyText = p.getString(KEY_AUTO_REPLY_TEXT, DEFAULT_AUTO_REPLY_TEXT);
        autoReplyInGroups = p.getBoolean(KEY_AUTO_REPLY_GROUPS, false);
        autoReplyCooldownSec = p.getInt(KEY_AUTO_REPLY_COOLDOWN, 300);
        autoReplyScheduleEnabled = p.getBoolean(KEY_AUTO_REPLY_SCHEDULE, false);
        autoReplyOnlyWhenAway = p.getBoolean(KEY_AUTO_REPLY_ONLY_AWAY, false);
        autoReplyStartMin = p.getInt(KEY_AUTO_REPLY_START_MIN, 22 * 60);
        autoReplyEndMin = p.getInt(KEY_AUTO_REPLY_END_MIN, 9 * 60);
        autoReplyAwayText = p.getString(KEY_AUTO_REPLY_AWAY_TEXT, DEFAULT_AUTO_REPLY_AWAY_TEXT);
        autoReplyAudience = p.getInt(KEY_AUTO_REPLY_AUDIENCE, ColgramAutoReplyPolicy.AUDIENCE_ALL);
        autoReplyToBots = p.getBoolean(KEY_AUTO_REPLY_BOTS, false);
        autoReplyBlacklist = p.getString(KEY_AUTO_REPLY_BLACKLIST, "");
        liveWallpaperStyle = p.getInt(KEY_LIVE_WALLPAPER_STYLE, ColgramLiveWallpaper.STYLE_AURORA);
        keywordAlertsEnabled = p.getBoolean(KEY_KEYWORD_ALERTS, false);
        keywordAlertsList = p.getString(KEY_KEYWORD_ALERTS_LIST, "");
        messageLoggerEnabled = p.getBoolean(KEY_MESSAGE_LOGGER, false);
        warpEnabled = p.getBoolean(KEY_WARP_ENABLED, false);
        botTokenPrefill = p.getString("bot_token_prefill", DEFAULT_BOT_TOKEN);
        autoProxyEnabled = p.getBoolean(KEY_AUTO_PROXY, false);
        strictProxySecurityEnabled = p.getBoolean(KEY_STRICT_PROXY_SECURITY, true);
        browserRouteMode = p.getString(KEY_BROWSER_ROUTE, "LOCAL_DPI");
        browserRouteSocks = p.getString(KEY_BROWSER_ROUTE_SOCKS, "");
        maxAnonymityEnabled = p.getBoolean(KEY_MAX_ANONYMITY, false);
        saveOneTimeMediaEnabled = p.getBoolean(KEY_SAVE_ONETIME_MEDIA, true);
        hideScreenshotNotifyEnabled = p.getBoolean(KEY_HIDE_SCREENSHOT_NOTIFY, true);
        stealthStoriesEnabled = p.getBoolean(KEY_STEALTH_STORIES, true);
        forwardNoQuoteEnabled = p.getBoolean(KEY_FORWARD_NO_QUOTE, false);
        hidePhoneLocalEnabled = p.getBoolean(KEY_HIDE_PHONE_LOCAL, false);
        antiDeleteSelfEnabled = p.getBoolean(KEY_ANTI_DELETE_SELF, true);
        confirmCallsEnabled = p.getBoolean(KEY_CONFIRM_CALLS, false);
        confirmStickersEnabled = p.getBoolean(KEY_CONFIRM_STICKERS, false);
        confirmLinksEnabled = p.getBoolean(KEY_CONFIRM_LINKS, false);
        tlsMimicEnabled = p.getBoolean(KEY_TLS_MIMIC, true);
        ipv6BypassEnabled = p.getBoolean(KEY_IPV6_BYPASS, false);
        dcRemapEnabled = p.getBoolean(KEY_DC_REMAP, false);
        dcRemapLoaded = true;
        relayUrl = p.getString(KEY_RELAY_URL, "");
        spamGuardEnabled = p.getBoolean(KEY_SPAM_GUARD, true);
        notificationAdFilterEnabled = p.getBoolean(KEY_COLEMAILER_NOTIF_FILTER, false);
        saveLeadsEnabled = p.getBoolean(KEY_COLEMAILER_SAVE_LEADS, false);
        broadcasterEnabled = p.getBoolean(KEY_COLEMAILER_BROADCASTER, false);
        autoSubEnabled = p.getBoolean(KEY_COLEMAILER_AUTOSUB, false);
        defaultMuteMode = p.getString(KEY_COLEMAILER_MUTE_MODE, "silent");
        helperBotToken = p.getString(KEY_COLEMAILER_BOT_TOKEN, "");
        colemailerState = p.getString(KEY_COLEMAILER_STATE, "");
        unlimitedMemoryConfigured = p.contains(KEY_UNLIMITED_MEMORY);
        unlimitedMemoryEnabled = p.getBoolean(KEY_UNLIMITED_MEMORY, true);
        autoCompressStorageEnabled = p.getBoolean("auto_compress_storage_enabled", true);
        alwaysBackgroundEnabled = p.getBoolean(KEY_ALWAYS_BACKGROUND, true);
        lastAutoUpdateCheckMs = p.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L);
        showMessageSecondsEnabled = p.getBoolean(KEY_SHOW_MESSAGE_SECONDS, false);
        showMessageIdEnabled = p.getBoolean(KEY_SHOW_MESSAGE_ID, true);
        defenderEnabled = p.getBoolean(KEY_DEFENDER_ENABLED, true);
        defenderAutoCloseEnabled = p.getBoolean(KEY_DEFENDER_AUTO_CLOSE, true);
        defenderBlockVoipP2pEnabled = p.getBoolean(KEY_DEFENDER_BLOCK_VOIP_P2P, true);
        defenderBlockBotLocationEnabled = p.getBoolean(KEY_DEFENDER_BLOCK_BOT_LOCATION, true);
        defenderStripTrackersEnabled = p.getBoolean(KEY_DEFENDER_STRIP_TRACKERS, true);
        defenderWebViewHardeningEnabled = p.getBoolean(KEY_DEFENDER_WEBVIEW_HARDENING, true);
        defenderLastScanMs = p.getLong(KEY_DEFENDER_LAST_SCAN_MS, 0L);
        defenderFixedTotal = p.getInt(KEY_DEFENDER_FIXED_TOTAL, 0);
        defenderTrackersStripped = p.getInt(KEY_DEFENDER_TRACKERS_STRIPPED, 0);
        defenderP2pBlocked = p.getInt(KEY_DEFENDER_P2P_BLOCKED, 0);
        quickBarEnabled = p.getBoolean(KEY_QUICK_BAR_ENABLED, true);
        quickBarItems = parseQuickBarItems(p.getString(KEY_QUICK_BAR_ITEMS, DEFAULT_QUICK_BAR_ITEMS));
        pinnedEndpointsCache.clear();
        miniAppPipPosCache.clear();
        chatVideoWallpapersCache.clear();
        chatImageWallpapersCache.clear();
    }

    private static void updateCachedKey(SharedPreferences p, String key) {
        switch (key) {
            case KEY_CLOAK_ENABLED: cloakEnabled = p.getBoolean(KEY_CLOAK_ENABLED, true); break;
            case KEY_SPOOF_MODEL: spoofDeviceModel = p.getString(KEY_SPOOF_MODEL, DEFAULT_MODEL); break;
            case KEY_SPOOF_SYSTEM_VERSION: spoofSystemVersion = p.getString(KEY_SPOOF_SYSTEM_VERSION, DEFAULT_SYSTEM_VERSION); break;
            case KEY_SPOOF_LANG: spoofLang = p.getString(KEY_SPOOF_LANG, DEFAULT_LANG); break;
            case KEY_CLOAK_INIT_BUMP: cloakInitVersionBump = p.getInt(KEY_CLOAK_INIT_BUMP, 0); break;
            case KEY_ANTI_DELETE_ENABLED: antiDeleteEnabled = p.getBoolean(KEY_ANTI_DELETE_ENABLED, true); break;
            case KEY_EDIT_HISTORY_ENABLED: editHistoryEnabled = p.getBoolean(KEY_EDIT_HISTORY_ENABLED, true); break;
            case KEY_ANTI_DELETE_HIGHLIGHT: antiDeleteHighlightEnabled = p.getBoolean(KEY_ANTI_DELETE_HIGHLIGHT, true); break;
            case KEY_ANTI_DELETE_WIPE: antiDeleteWipeEnabled = p.getBoolean(KEY_ANTI_DELETE_WIPE, false); break;
            case KEY_CHAT_WALLPAPER_ENABLED: chatWallpaperEnabled = p.getBoolean(KEY_CHAT_WALLPAPER_ENABLED, true); break;
            case KEY_PRESERVE_MEDIA: preserveMediaEnabled = p.getBoolean(KEY_PRESERVE_MEDIA, true); break;
            case KEY_GHOST_READ: ghostReadEnabled = p.getBoolean(KEY_GHOST_READ, true); break;
            case KEY_GHOST_TYPING: ghostTypingEnabled = p.getBoolean(KEY_GHOST_TYPING, true); break;
            case KEY_GHOST_ONLINE: ghostOnlineEnabled = p.getBoolean(KEY_GHOST_ONLINE, false); break;
            case KEY_BYPASS_FLAG_SECURE: bypassFlagSecureEnabled = p.getBoolean(KEY_BYPASS_FLAG_SECURE, true); break;
            case KEY_AUTO_HIDE_PHONE: autoHidePhoneEnabled = p.getBoolean(KEY_AUTO_HIDE_PHONE, true); break;
            case KEY_SANDBOX_STORAGE_ENABLED: sandboxStorageEnabled = p.getBoolean(KEY_SANDBOX_STORAGE_ENABLED, true); break;
            case KEY_CUSTOM_STORAGE_DIR: customStorageDir = p.getString(KEY_CUSTOM_STORAGE_DIR, DEFAULT_STORAGE_FOLDER); break;
            case KEY_BUILTIN_PROXY_ENABLED: builtinProxyEnabled = p.getBoolean(KEY_BUILTIN_PROXY_ENABLED, true); break;
            case KEY_DOH_ENABLED: dohEnabled = p.getBoolean(KEY_DOH_ENABLED, true); break;
            case KEY_PROXY_BROWSER_ENABLED: proxyBrowserEnabled = p.getBoolean(KEY_PROXY_BROWSER_ENABLED, true); break;
            case KEY_UPDATE_REPO: updateRepo = p.getString(KEY_UPDATE_REPO, DEFAULT_UPDATE_REPO); break;
            case KEY_AUTO_UPDATE_ENABLED: autoUpdateEnabled = p.getBoolean(KEY_AUTO_UPDATE_ENABLED, true); break;
            case KEY_CYBER_THEME_ENABLED: cyberThemeEnabled = p.getBoolean(KEY_CYBER_THEME_ENABLED, false); break;
            case KEY_PENDING_UPSTREAM_TAG: pendingUpstreamTag = p.getString(KEY_PENDING_UPSTREAM_TAG, null); break;
            case KEY_MINIAPP_PIP_ENABLED: miniAppPipEnabled = p.getBoolean(KEY_MINIAPP_PIP_ENABLED, true); break;
            case KEY_MINIAPP_PIP_MAX: miniAppPipMaxWindows = p.getInt(KEY_MINIAPP_PIP_MAX, 0); break;
            case KEY_AUTO_REPLY_ENABLED: autoReplyEnabled = p.getBoolean(KEY_AUTO_REPLY_ENABLED, false); break;
            case KEY_AUTO_REPLY_TEXT: autoReplyText = p.getString(KEY_AUTO_REPLY_TEXT, DEFAULT_AUTO_REPLY_TEXT); break;
            case KEY_AUTO_REPLY_GROUPS: autoReplyInGroups = p.getBoolean(KEY_AUTO_REPLY_GROUPS, false); break;
            case KEY_AUTO_REPLY_COOLDOWN: autoReplyCooldownSec = p.getInt(KEY_AUTO_REPLY_COOLDOWN, 300); break;
            case KEY_AUTO_REPLY_SCHEDULE: autoReplyScheduleEnabled = p.getBoolean(KEY_AUTO_REPLY_SCHEDULE, false); break;
            case KEY_AUTO_REPLY_ONLY_AWAY: autoReplyOnlyWhenAway = p.getBoolean(KEY_AUTO_REPLY_ONLY_AWAY, false); break;
            case KEY_AUTO_REPLY_START_MIN: autoReplyStartMin = p.getInt(KEY_AUTO_REPLY_START_MIN, 22 * 60); break;
            case KEY_AUTO_REPLY_END_MIN: autoReplyEndMin = p.getInt(KEY_AUTO_REPLY_END_MIN, 9 * 60); break;
            case KEY_AUTO_REPLY_AWAY_TEXT: autoReplyAwayText = p.getString(KEY_AUTO_REPLY_AWAY_TEXT, DEFAULT_AUTO_REPLY_AWAY_TEXT); break;
            case KEY_AUTO_REPLY_AUDIENCE: autoReplyAudience = p.getInt(KEY_AUTO_REPLY_AUDIENCE, ColgramAutoReplyPolicy.AUDIENCE_ALL); break;
            case KEY_AUTO_REPLY_BOTS: autoReplyToBots = p.getBoolean(KEY_AUTO_REPLY_BOTS, false); break;
            case KEY_AUTO_REPLY_BLACKLIST: autoReplyBlacklist = p.getString(KEY_AUTO_REPLY_BLACKLIST, ""); break;
            case KEY_LIVE_WALLPAPER_STYLE: liveWallpaperStyle = p.getInt(KEY_LIVE_WALLPAPER_STYLE, ColgramLiveWallpaper.STYLE_AURORA); break;
            case KEY_KEYWORD_ALERTS: keywordAlertsEnabled = p.getBoolean(KEY_KEYWORD_ALERTS, false); break;
            case KEY_KEYWORD_ALERTS_LIST: keywordAlertsList = p.getString(KEY_KEYWORD_ALERTS_LIST, ""); break;
            case KEY_MESSAGE_LOGGER: messageLoggerEnabled = p.getBoolean(KEY_MESSAGE_LOGGER, false); break;
            case KEY_WARP_ENABLED: warpEnabled = p.getBoolean(KEY_WARP_ENABLED, false); break;
            case "bot_token_prefill": botTokenPrefill = p.getString("bot_token_prefill", DEFAULT_BOT_TOKEN); break;
            case KEY_AUTO_PROXY: autoProxyEnabled = p.getBoolean(KEY_AUTO_PROXY, false); break;
            case KEY_STRICT_PROXY_SECURITY: strictProxySecurityEnabled = p.getBoolean(KEY_STRICT_PROXY_SECURITY, true); break;
            case KEY_BROWSER_ROUTE: browserRouteMode = p.getString(KEY_BROWSER_ROUTE, "LOCAL_DPI"); break;
            case KEY_BROWSER_ROUTE_SOCKS: browserRouteSocks = p.getString(KEY_BROWSER_ROUTE_SOCKS, ""); break;
            case KEY_MAX_ANONYMITY: maxAnonymityEnabled = p.getBoolean(KEY_MAX_ANONYMITY, false); break;
            case KEY_SAVE_ONETIME_MEDIA: saveOneTimeMediaEnabled = p.getBoolean(KEY_SAVE_ONETIME_MEDIA, true); break;
            case KEY_HIDE_SCREENSHOT_NOTIFY: hideScreenshotNotifyEnabled = p.getBoolean(KEY_HIDE_SCREENSHOT_NOTIFY, true); break;
            case KEY_STEALTH_STORIES: stealthStoriesEnabled = p.getBoolean(KEY_STEALTH_STORIES, true); break;
            case KEY_FORWARD_NO_QUOTE: forwardNoQuoteEnabled = p.getBoolean(KEY_FORWARD_NO_QUOTE, false); break;
            case KEY_HIDE_PHONE_LOCAL: hidePhoneLocalEnabled = p.getBoolean(KEY_HIDE_PHONE_LOCAL, false); break;
            case KEY_ANTI_DELETE_SELF: antiDeleteSelfEnabled = p.getBoolean(KEY_ANTI_DELETE_SELF, true); break;
            case KEY_CONFIRM_CALLS: confirmCallsEnabled = p.getBoolean(KEY_CONFIRM_CALLS, false); break;
            case KEY_CONFIRM_STICKERS: confirmStickersEnabled = p.getBoolean(KEY_CONFIRM_STICKERS, false); break;
            case KEY_CONFIRM_LINKS: confirmLinksEnabled = p.getBoolean(KEY_CONFIRM_LINKS, false); break;
            case KEY_TLS_MIMIC: tlsMimicEnabled = p.getBoolean(KEY_TLS_MIMIC, true); break;
            case KEY_IPV6_BYPASS: ipv6BypassEnabled = p.getBoolean(KEY_IPV6_BYPASS, false); break;
            case KEY_DC_REMAP:
                dcRemapEnabled = p.getBoolean(KEY_DC_REMAP, false);
                dcRemapLoaded = true;
                break;
            case KEY_RELAY_URL: relayUrl = p.getString(KEY_RELAY_URL, ""); break;
            case KEY_SPAM_GUARD: spamGuardEnabled = p.getBoolean(KEY_SPAM_GUARD, true); break;
            case KEY_COLEMAILER_NOTIF_FILTER: notificationAdFilterEnabled = p.getBoolean(KEY_COLEMAILER_NOTIF_FILTER, false); break;
            case KEY_COLEMAILER_SAVE_LEADS: saveLeadsEnabled = p.getBoolean(KEY_COLEMAILER_SAVE_LEADS, false); break;
            case KEY_COLEMAILER_BROADCASTER: broadcasterEnabled = p.getBoolean(KEY_COLEMAILER_BROADCASTER, false); break;
            case KEY_COLEMAILER_AUTOSUB: autoSubEnabled = p.getBoolean(KEY_COLEMAILER_AUTOSUB, false); break;
            case KEY_COLEMAILER_MUTE_MODE: defaultMuteMode = p.getString(KEY_COLEMAILER_MUTE_MODE, "silent"); break;
            case KEY_COLEMAILER_BOT_TOKEN: helperBotToken = p.getString(KEY_COLEMAILER_BOT_TOKEN, ""); break;
            case KEY_COLEMAILER_STATE: colemailerState = p.getString(KEY_COLEMAILER_STATE, ""); break;
            case KEY_UNLIMITED_MEMORY:
                unlimitedMemoryConfigured = p.contains(KEY_UNLIMITED_MEMORY);
                unlimitedMemoryEnabled = p.getBoolean(KEY_UNLIMITED_MEMORY, true);
                break;
            case "auto_compress_storage_enabled":
                autoCompressStorageEnabled = p.getBoolean("auto_compress_storage_enabled", true);
                break;
            case KEY_ALWAYS_BACKGROUND: alwaysBackgroundEnabled = p.getBoolean(KEY_ALWAYS_BACKGROUND, true); break;
            case KEY_LAST_UPDATE_CHECK_MS: lastAutoUpdateCheckMs = p.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L); break;
            case KEY_SHOW_MESSAGE_SECONDS: showMessageSecondsEnabled = p.getBoolean(KEY_SHOW_MESSAGE_SECONDS, false); break;
            case KEY_SHOW_MESSAGE_ID: showMessageIdEnabled = p.getBoolean(KEY_SHOW_MESSAGE_ID, true); break;
            case KEY_DEFENDER_ENABLED: defenderEnabled = p.getBoolean(KEY_DEFENDER_ENABLED, true); break;
            case KEY_DEFENDER_AUTO_CLOSE: defenderAutoCloseEnabled = p.getBoolean(KEY_DEFENDER_AUTO_CLOSE, true); break;
            case KEY_DEFENDER_BLOCK_VOIP_P2P: defenderBlockVoipP2pEnabled = p.getBoolean(KEY_DEFENDER_BLOCK_VOIP_P2P, true); break;
            case KEY_DEFENDER_BLOCK_BOT_LOCATION: defenderBlockBotLocationEnabled = p.getBoolean(KEY_DEFENDER_BLOCK_BOT_LOCATION, true); break;
            case KEY_DEFENDER_STRIP_TRACKERS: defenderStripTrackersEnabled = p.getBoolean(KEY_DEFENDER_STRIP_TRACKERS, true); break;
            case KEY_DEFENDER_WEBVIEW_HARDENING: defenderWebViewHardeningEnabled = p.getBoolean(KEY_DEFENDER_WEBVIEW_HARDENING, true); break;
            case KEY_DEFENDER_LAST_SCAN_MS: defenderLastScanMs = p.getLong(KEY_DEFENDER_LAST_SCAN_MS, 0L); break;
            case KEY_DEFENDER_FIXED_TOTAL: defenderFixedTotal = p.getInt(KEY_DEFENDER_FIXED_TOTAL, 0); break;
            case KEY_DEFENDER_TRACKERS_STRIPPED: defenderTrackersStripped = p.getInt(KEY_DEFENDER_TRACKERS_STRIPPED, 0); break;
            case KEY_DEFENDER_P2P_BLOCKED: defenderP2pBlocked = p.getInt(KEY_DEFENDER_P2P_BLOCKED, 0); break;
            case KEY_QUICK_BAR_ENABLED: quickBarEnabled = p.getBoolean(KEY_QUICK_BAR_ENABLED, true); break;
            case KEY_QUICK_BAR_ITEMS:
                quickBarItems = parseQuickBarItems(p.getString(KEY_QUICK_BAR_ITEMS, DEFAULT_QUICK_BAR_ITEMS));
                break;
            default:
                if (key.startsWith("pin_")) {
                    pinnedEndpointsCache.put(key.substring(4), p.getString(key, ""));
                } else if (key.startsWith(KEY_MINIAPP_PIP_POS_PREFIX)) {
                    try {
                        int slot = Integer.parseInt(key.substring(KEY_MINIAPP_PIP_POS_PREFIX.length()));
                        String val = p.getString(key, null);
                        miniAppPipPosCache.put(slot, val != null ? val : NULL_SENTINEL);
                    } catch (NumberFormatException ignored) {}
                } else if (key.startsWith("colgram_video_wp_")) {
                    try {
                        long dialogId = Long.parseLong(key.substring("colgram_video_wp_".length()));
                        chatVideoWallpapersCache.put(dialogId, p.getString(key, ""));
                    } catch (NumberFormatException ignored) {}
                } else if (key.startsWith("colgram_img_wp_")) {
                    try {
                        long dialogId = Long.parseLong(key.substring("colgram_img_wp_".length()));
                        chatImageWallpapersCache.put(dialogId, p.getString(key, ""));
                    } catch (NumberFormatException ignored) {}
                }
                break;
        }
    }

    // --- Cloaking ---
    public static int getCloakInitVersionBump() {
        return cloakInitVersionBump;
    }

    public static void bumpCloakInitVersion() {
        int next = (cloakInitVersionBump + 1) % 90000;
        if (next <= 0) next = 1;
        cloakInitVersionBump = next;
        if (prefs != null) {
            prefs.edit().putInt(KEY_CLOAK_INIT_BUMP, next).apply();
        }
    }

    public static boolean isCloakEnabled() {
        return cloakEnabled;
    }

    public static void setCloakEnabled(boolean enabled) {
        if (prefs != null) {
            boolean changed = cloakEnabled != enabled;
            cloakEnabled = enabled;
            prefs.edit().putBoolean(KEY_CLOAK_ENABLED, enabled).apply();
            if (changed) {
                bumpCloakInitVersion();
            }
        }
    }

    public static String getSpoofDeviceModel() {
        return spoofDeviceModel;
    }

    public static void setSpoofDeviceModel(String model) {
        if (model == null || model.trim().isEmpty()) return;
        String trimmed = model.trim();
        boolean changed = !trimmed.equals(spoofDeviceModel);
        spoofDeviceModel = trimmed;
        ColgramCloak.DeviceProfile preset = ColgramCloak.findPresetForModel(trimmed);
        if (preset != null) {
            spoofSystemVersion = preset.systemVersion;
        }
        if (prefs != null) {
            SharedPreferences.Editor ed = prefs.edit().putString(KEY_SPOOF_MODEL, trimmed);
            if (preset != null) {
                ed.putString(KEY_SPOOF_SYSTEM_VERSION, preset.systemVersion);
            }
            ed.apply();
        }
        if (changed) {
            bumpCloakInitVersion();
        }
    }

    public static String getSpoofSystemVersion() {
        return spoofSystemVersion;
    }

    public static void setSpoofSystemVersion(String version) {
        if (version == null || version.trim().isEmpty()) return;
        String trimmed = version.trim();
        boolean changed = !trimmed.equals(spoofSystemVersion);
        spoofSystemVersion = trimmed;
        if (prefs != null) {
            prefs.edit().putString(KEY_SPOOF_SYSTEM_VERSION, trimmed).apply();
        }
        if (changed) {
            bumpCloakInitVersion();
        }
    }

    public static String getSpoofLang() {
        return spoofLang;
    }

    public static void setSpoofLang(String lang) {
        if (lang == null || lang.trim().isEmpty()) return;
        String trimmed = lang.trim();
        boolean changed = !trimmed.equals(spoofLang);
        spoofLang = trimmed;
        if (prefs != null) {
            prefs.edit().putString(KEY_SPOOF_LANG, trimmed).apply();
        }
        if (changed) {
            bumpCloakInitVersion();
        }
    }

    // --- Message Vault ---
    public static boolean isAntiDeleteEnabled() {
        return antiDeleteEnabled;
    }

    public static void setAntiDeleteEnabled(boolean enabled) {
        if (prefs != null) {
            antiDeleteEnabled = enabled;
            prefs.edit().putBoolean(KEY_ANTI_DELETE_ENABLED, enabled).apply();
        }
    }

    public static boolean isEditHistoryEnabled() {
        return editHistoryEnabled;
    }

    public static void setEditHistoryEnabled(boolean enabled) {
        if (prefs != null) {
            editHistoryEnabled = enabled;
            prefs.edit().putBoolean(KEY_EDIT_HISTORY_ENABLED, enabled).apply();
        }
    }

    /**
     * Whether a retained (anti-deleted) message is tinted so it reads as "this was deleted
     * by the other side". Purely `contentDescription`-style metadata — it never changes
     * message state. On by default; turn off for a completely invisible interception.
     */
    public static boolean isAntiDeleteHighlightEnabled() {
        return antiDeleteHighlightEnabled;
    }

    public static void setAntiDeleteHighlightEnabled(boolean enabled) {
        if (prefs != null) {
            antiDeleteHighlightEnabled = enabled;
            prefs.edit().putBoolean(KEY_ANTI_DELETE_HIGHLIGHT, enabled).apply();
        }
    }

    /**
     * Whether the tombstoned row is also blanked in SQLite.
     *
     * ON  — the message text is wiped locally but the row survives, so the message stays in
     *       place after a reload and still cannot be read (AyuGram-style tombstone).
     * OFF — the message is kept in full, text and all. This is the behaviour to use when
     *       what you actually want is a permanent local archive of everything the other
     *       side tried to retract.
     */
    public static boolean isAntiDeleteWipeEnabled() {
        return antiDeleteWipeEnabled;
    }

    public static void setAntiDeleteWipeEnabled(boolean enabled) {
        if (prefs != null) {
            antiDeleteWipeEnabled = enabled;
            prefs.edit().putBoolean(KEY_ANTI_DELETE_WIPE, enabled).apply();
        }
    }

    public static boolean isChatWallpaperEnabled() {
        return chatWallpaperEnabled;
    }

    public static void setChatWallpaperEnabled(boolean enabled) {
        if (prefs != null) {
            chatWallpaperEnabled = enabled;
            prefs.edit().putBoolean(KEY_CHAT_WALLPAPER_ENABLED, enabled).apply();
        }
    }

    public static boolean isPreserveMediaEnabled() {
        return preserveMediaEnabled;
    }

    public static void setPreserveMediaEnabled(boolean enabled) {
        if (prefs != null) {
            preserveMediaEnabled = enabled;
            prefs.edit().putBoolean(KEY_PRESERVE_MEDIA, enabled).apply();
        }
    }

    // --- Ghost Mode ---
    public static boolean isGhostReadEnabled() {
        return ghostReadEnabled;
    }

    public static void setGhostReadEnabled(boolean enabled) {
        if (prefs != null) {
            ghostReadEnabled = enabled;
            prefs.edit().putBoolean(KEY_GHOST_READ, enabled).apply();
        }
    }

    public static boolean isGhostTypingEnabled() {
        return ghostTypingEnabled;
    }

    public static void setGhostTypingEnabled(boolean enabled) {
        if (prefs != null) {
            ghostTypingEnabled = enabled;
            prefs.edit().putBoolean(KEY_GHOST_TYPING, enabled).apply();
        }
    }

    public static boolean isGhostOnlineEnabled() {
        return ghostOnlineEnabled;
    }

    public static void setGhostOnlineEnabled(boolean enabled) {
        if (prefs != null) {
            ghostOnlineEnabled = enabled;
            prefs.edit().putBoolean(KEY_GHOST_ONLINE, enabled).apply();
        }
    }

    public static boolean isBypassFlagSecureEnabled() {
        return bypassFlagSecureEnabled;
    }

    public static void setBypassFlagSecureEnabled(boolean enabled) {
        if (prefs != null) {
            bypassFlagSecureEnabled = enabled;
            prefs.edit().putBoolean(KEY_BYPASS_FLAG_SECURE, enabled).apply();
        }
    }

    // --- Privacy on login ---

    /**
     * Whether the account's phone number is forced to "Nobody" the first time the account
     * syncs after sign-in.
     *
     * Telegram's stock default is "visible to everybody". For an account signed in with a
     * phone number that means the number is enumerable by anyone who has it. The request
     * is issued once per account by the patched MessagesController, driven by
     * ColgramHookHandler.shouldAutoHidePhoneNumber(), which persists a per-account latch
     * so it never re-sends. Turning this off leaves the number exactly as Telegram left it.
     */
    public static boolean isAutoHidePhoneEnabled() {
        return autoHidePhoneEnabled;
    }

    public static void setAutoHidePhoneEnabled(boolean enabled) {
        if (prefs != null) {
            autoHidePhoneEnabled = enabled;
            prefs.edit().putBoolean(KEY_AUTO_HIDE_PHONE, enabled).apply();
        }
    }

    // --- Storage Sandbox ---
    public static boolean isSandboxStorageEnabled() {
        return sandboxStorageEnabled;
    }

    public static void setSandboxStorageEnabled(boolean enabled) {
        if (prefs != null) {
            sandboxStorageEnabled = enabled;
            prefs.edit().putBoolean(KEY_SANDBOX_STORAGE_ENABLED, enabled).apply();
        }
    }

    public static String getCustomStorageDir() {
        return customStorageDir;
    }

    // --- Network & Proxies ---
    /** Mandatory local base; availability is reported separately from Telegram connectivity. */
    private static final String KEY_DPI_BYPASS_ENABLED = "dpi_bypass_enabled";

    public static boolean isDpiBypassEnabled() {
        return true; // The local base is mandatory; old persisted false values do not disable it.
    }

    public static void setDpiBypassEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DPI_BYPASS_ENABLED, true).apply();
    }

    public static boolean isBuiltinProxyEnabled() {
        return builtinProxyEnabled;
    }

    public static void setBuiltinProxyEnabled(boolean enabled) {
        if (prefs != null) {
            builtinProxyEnabled = enabled;
            prefs.edit().putBoolean(KEY_BUILTIN_PROXY_ENABLED, enabled).apply();
        }
    }

    public static boolean isDohEnabled() {
        return dohEnabled;
    }

    public static void setDohEnabled(boolean enabled) {
        if (prefs != null) {
            dohEnabled = enabled;
            prefs.edit().putBoolean(KEY_DOH_ENABLED, enabled).apply();
        }
    }

    public static boolean isProxyBrowserEnabled() {
        return proxyBrowserEnabled;
    }

    public static void setProxyBrowserEnabled(boolean enabled) {
        if (prefs != null) {
            proxyBrowserEnabled = enabled;
            prefs.edit().putBoolean(KEY_PROXY_BROWSER_ENABLED, enabled).apply();
        }
    }

    public static String getUpdateRepo() {
        return updateRepo;
    }

    public static void setUpdateRepo(String repo) {
        if (prefs == null) return;
        String clean = repo != null ? repo.trim() : "";
        if (clean.isEmpty()) {
            updateRepo = DEFAULT_UPDATE_REPO;
            prefs.edit().remove(KEY_UPDATE_REPO).apply();
        } else {
            updateRepo = clean;
            prefs.edit().putString(KEY_UPDATE_REPO, clean).apply();
        }
    }

    public static boolean isAutoUpdateEnabled() {
        return autoUpdateEnabled;
    }

    public static void setAutoUpdateEnabled(boolean enabled) {
        if (prefs != null) {
            autoUpdateEnabled = enabled;
            prefs.edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply();
        }
    }

    /**
     * Whether to force Colgram's red/near-black palette over the user's own theme.
     *
     * Defaults to FALSE. It used to default to true, and came up true whenever prefs had
     * not finished initialising, which meant a stock dark install (Night / Dark Blue) had
     * its backgrounds rewritten to 0xff0e0f12 and its action bar to 0xff16181e while the
     * icon and text keys were NOT covered by the same override list. Those fell through to
     * the user's palette, which for a partial dark theme resolves to 0 - so backgrounds,
     * icons and labels all rendered as the same near-black rectangle. That was the
     * "everything merges" report.
     *
     * A cosmetic override must be opted into, so an unreadable UI can never be the default
     * state of a fresh install.
     */
    public static boolean isCyberThemeEnabled() {
        return cyberThemeEnabled;
    }

    public static void setCyberThemeEnabled(boolean enabled) {
        if (prefs != null) {
            cyberThemeEnabled = enabled;
            prefs.edit().putBoolean(KEY_CYBER_THEME_ENABLED, enabled).apply();
        }
    }

    // --- Version switching ---

    /**
     * The upstream DrKLO/Telegram tag the user picked in the version switcher.
     *
     * Switching Telegram versions cannot happen on-device — it requires re-cloning the
     * tag, re-applying every patch and rebuilding. We persist the choice so the CI
     * pipeline (or a local build) can consume it, and the UI sends the user to the run
     * that performs the rebuild.
     */
    public static String getPendingUpstreamTag() {
        return pendingUpstreamTag;
    }

    public static void setPendingUpstreamTag(String tag) {
        if (prefs != null) {
            pendingUpstreamTag = tag;
            prefs.edit().putString(KEY_PENDING_UPSTREAM_TAG, tag).apply();
        }
    }

    public static void clearPendingUpstreamTag() {
        if (prefs != null) {
            pendingUpstreamTag = null;
            prefs.edit().remove(KEY_PENDING_UPSTREAM_TAG).apply();
        }
    }

    // --- Mini-app floating windows ---

    /**
     * Whether a mini-app can be pinned into an Android picture-in-picture floating
     * window. Enabled by default; the UI reads this before offering the action.
     */
    public static boolean isMiniAppPipEnabled() {
        return miniAppPipEnabled;
    }

    public static void setMiniAppPipEnabled(boolean enabled) {
        if (prefs != null) {
            miniAppPipEnabled = enabled;
            prefs.edit().putBoolean(KEY_MINIAPP_PIP_ENABLED, enabled).apply();
        }
    }

    /**
     * How many mini-apps may float at the same time.
     *
     * Android's own PiP mode allows exactly one window per task, so simultaneous windows
     * are only possible through the draw-over-other-apps path
     * (WindowManager TYPE_APPLICATION_OVERLAY), where each window is an independent
     * addView. Telegram's PipVideoOverlay already uses exactly that mechanism.
     *
     * 0 means unlimited. The cap exists only so a runaway loop cannot stack windows
     * forever; the UI surfaces the real count.
     */
    public static int getMiniAppPipMaxWindows() {
        return miniAppPipMaxWindows;
    }

    public static void setMiniAppPipMaxWindows(int max) {
        if (prefs != null) {
            miniAppPipMaxWindows = max;
            prefs.edit().putInt(KEY_MINIAPP_PIP_MAX, max).apply();
        }
    }

    /**
     * Remembered on-screen position of the nth floating mini-app window, as "x,y".
     * Windows are draggable, so there is no sensible default beyond a cascade.
     */
    public static String getMiniAppPipPosition(int slot) {
        SharedPreferences p = prefs;
        if (p == null) return null;
        String cached = miniAppPipPosCache.get(slot);
        if (cached != null) {
            return cached == NULL_SENTINEL ? null : cached;
        }
        String val = p.getString(KEY_MINIAPP_PIP_POS_PREFIX + slot, null);
        miniAppPipPosCache.put(slot, val != null ? val : NULL_SENTINEL);
        return val;
    }

    public static void setMiniAppPipPosition(int slot, int x, int y) {
        if (prefs != null) {
            String val = x + "," + y;
            miniAppPipPosCache.put(slot, val);
            prefs.edit().putString(KEY_MINIAPP_PIP_POS_PREFIX + slot, val).apply();
        }
    }

    // --- Auto-reply (Business Responder) ---
    private static final String KEY_AUTO_REPLY_ENABLED = "auto_reply_enabled";
    private static final String KEY_AUTO_REPLY_TEXT = "auto_reply_text";
    private static final String KEY_AUTO_REPLY_GROUPS = "auto_reply_groups";
    private static final String KEY_AUTO_REPLY_COOLDOWN = "auto_reply_cooldown_sec";
    private static final String KEY_AUTO_REPLY_SCHEDULE = "auto_reply_schedule_enabled";
    private static final String KEY_AUTO_REPLY_ONLY_AWAY = "auto_reply_only_when_away";
    private static final String KEY_AUTO_REPLY_START_MIN = "auto_reply_start_min";
    private static final String KEY_AUTO_REPLY_END_MIN = "auto_reply_end_min";
    private static final String KEY_AUTO_REPLY_AWAY_TEXT = "auto_reply_away_text";
    private static final String KEY_AUTO_REPLY_AUDIENCE = "auto_reply_audience";
    private static final String KEY_AUTO_REPLY_BOTS = "auto_reply_to_bots";
    private static final String KEY_AUTO_REPLY_BLACKLIST = "auto_reply_blacklist_csv";
    private static final String KEY_LIVE_WALLPAPER_STYLE = "live_wallpaper_style";

    public static boolean isAutoReplyEnabled() {
        return autoReplyEnabled;
    }

    public static void setAutoReplyEnabled(boolean enabled) {
        if (prefs != null) {
            autoReplyEnabled = enabled;
            prefs.edit().putBoolean(KEY_AUTO_REPLY_ENABLED, enabled).apply();
        }
    }

    public static String getAutoReplyText() {
        return autoReplyText;
    }

    public static void setAutoReplyText(String text) {
        if (prefs != null) {
            autoReplyText = text != null ? text : DEFAULT_AUTO_REPLY_TEXT;
            prefs.edit().putString(KEY_AUTO_REPLY_TEXT, text).apply();
        }
    }

    /** When off, the reply only answers private chats; groups stay quiet. */
    public static boolean isAutoReplyInGroups() {
        return autoReplyInGroups;
    }

    public static void setAutoReplyInGroups(boolean enabled) {
        if (prefs != null) {
            autoReplyInGroups = enabled;
            prefs.edit().putBoolean(KEY_AUTO_REPLY_GROUPS, enabled).apply();
        }
    }

    /** Minimum seconds between two replies to the same dialog; 0 answers every message. */
    public static int getAutoReplyCooldownSec() {
        return autoReplyCooldownSec;
    }

    public static void setAutoReplyCooldownSec(int seconds) {
        if (prefs != null) {
            autoReplyCooldownSec = seconds;
            prefs.edit().putInt(KEY_AUTO_REPLY_COOLDOWN, seconds).apply();
        }
    }

    public static boolean isAutoReplyScheduleEnabled() {
        return autoReplyScheduleEnabled;
    }

    public static void setAutoReplyScheduleEnabled(boolean enabled) {
        if (prefs != null) {
            autoReplyScheduleEnabled = enabled;
            prefs.edit().putBoolean(KEY_AUTO_REPLY_SCHEDULE, enabled).apply();
        }
    }

    public static boolean isAutoReplyOnlyWhenAway() {
        return autoReplyOnlyWhenAway;
    }

    public static void setAutoReplyOnlyWhenAway(boolean enabled) {
        if (prefs != null) {
            autoReplyOnlyWhenAway = enabled;
            prefs.edit().putBoolean(KEY_AUTO_REPLY_ONLY_AWAY, enabled).apply();
        }
    }

    public static int getAutoReplyStartMin() {
        return autoReplyStartMin;
    }

    public static void setAutoReplyStartMin(int minutes) {
        if (prefs != null) {
            autoReplyStartMin = minutes;
            prefs.edit().putInt(KEY_AUTO_REPLY_START_MIN, minutes).apply();
        }
    }

    public static int getAutoReplyEndMin() {
        return autoReplyEndMin;
    }

    public static void setAutoReplyEndMin(int minutes) {
        if (prefs != null) {
            autoReplyEndMin = minutes;
            prefs.edit().putInt(KEY_AUTO_REPLY_END_MIN, minutes).apply();
        }
    }

    public static String getAutoReplyAwayText() {
        return autoReplyAwayText;
    }

    public static void setAutoReplyAwayText(String text) {
        if (prefs != null) {
            autoReplyAwayText = text != null ? text : DEFAULT_AUTO_REPLY_AWAY_TEXT;
            prefs.edit().putString(KEY_AUTO_REPLY_AWAY_TEXT, text).apply();
        }
    }

    public static int getAutoReplyAudience() {
        return autoReplyAudience;
    }

    public static void setAutoReplyAudience(int mode) {
        if (prefs != null) {
            autoReplyAudience = mode;
            prefs.edit().putInt(KEY_AUTO_REPLY_AUDIENCE, mode).apply();
        }
    }

    public static boolean isAutoReplyToBots() {
        return autoReplyToBots;
    }

    public static void setAutoReplyToBots(boolean enabled) {
        if (prefs != null) {
            autoReplyToBots = enabled;
            prefs.edit().putBoolean(KEY_AUTO_REPLY_BOTS, enabled).apply();
        }
    }

    public static String getAutoReplyBlacklist() {
        return autoReplyBlacklist;
    }

    public static void setAutoReplyBlacklist(String csv) {
        if (prefs != null) {
            String clean = csv == null ? "" : csv;
            autoReplyBlacklist = clean;
            prefs.edit().putString(KEY_AUTO_REPLY_BLACKLIST, clean).apply();
        }
    }

    private static final String KEY_LIVE_WALLPAPER_WAVES_MIGRATED = "live_wallpaper_waves_v2";

    public static int getLiveWallpaperStyle() {
        return liveWallpaperStyle;
    }

    public static void setLiveWallpaperStyle(int style) {
        if (prefs != null) {
            int clamped = Math.max(0, Math.min(4, style));
            liveWallpaperStyle = clamped;
            prefs.edit()
                    .putInt(KEY_LIVE_WALLPAPER_STYLE, clamped)
                    .putBoolean(KEY_LIVE_WALLPAPER_WAVES_MIGRATED, true)
                    .apply();
        }
    }

    // --- Keyword alerts (built-in, was plugins/keyword_alerts.py) ---

    public static boolean isKeywordAlertsEnabled() {
        return keywordAlertsEnabled;
    }

    public static void setKeywordAlertsEnabled(boolean enabled) {
        if (prefs != null) {
            keywordAlertsEnabled = enabled;
            prefs.edit().putBoolean(KEY_KEYWORD_ALERTS, enabled).apply();
        }
    }

    /** Comma-separated keyword list; matching is case-insensitive substring. */
    public static String getKeywordAlertsList() {
        return keywordAlertsList;
    }

    public static void setKeywordAlertsList(String keywords) {
        if (prefs != null) {
            String clean = keywords == null ? "" : keywords;
            keywordAlertsList = clean;
            prefs.edit().putString(KEY_KEYWORD_ALERTS_LIST, clean).apply();
        }
    }

    /** True when any comma-separated keyword occurs in the text (case-insensitive). */
    public static boolean keywordAlertMatches(String text) {
        String list = getKeywordAlertsList();
        if (list == null || list.trim().isEmpty() || text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String kw : list.split(",")) {
            String k = kw.trim().toLowerCase();
            if (!k.isEmpty() && lower.contains(k)) return true;
        }
        return false;
    }

    // --- Message logger (built-in, was plugins/message_logger.py) ---

    public static boolean isMessageLoggerEnabled() {
        return messageLoggerEnabled;
    }

    public static void setMessageLoggerEnabled(boolean enabled) {
        if (prefs != null) {
            messageLoggerEnabled = enabled;
            prefs.edit().putBoolean(KEY_MESSAGE_LOGGER, enabled).apply();
        }
    }

    // --- Bot token prefill ---
    // Pre-filled in the bot login dialog so the token does not have to be re-typed after
    // reinstall or data clear. Typed-in tokens still override it per account, and clearing
    // app data restores exactly this default. Keep in mind a token shipped in an APK is
    // readable by anyone who unpacks it - rotate it in @BotFather if the APK ever leaves
    // this machine.
    public static final String DEFAULT_BOT_TOKEN =
            "";

    // --- WARP toggle state -------------------------------------------------------
    // The switch reflects the USER'S CHOICE, not the live tunnel state: the tunnel comes
    // up asynchronously, and binding the row to isUp() made the toggle flip itself back
    // to grey until the screen was reopened.
    private static final String KEY_WARP_ENABLED = "warp_enabled";

    public static boolean isWarpEnabled() {
        return warpEnabled;
    }

    public static void setWarpEnabled(boolean enabled) {
        if (prefs != null) {
            warpEnabled = enabled;
            prefs.edit().putBoolean(KEY_WARP_ENABLED, enabled).apply();
        }
    }

    public static String getBotTokenPrefill() {
        return botTokenPrefill;
    }

    public static void setBotTokenPrefill(String token) {
        if (prefs != null) {
            String clean = token == null ? "" : token;
            botTokenPrefill = clean;
            prefs.edit().putString("bot_token_prefill", clean).apply();
        }
    }

    // --- Unlimited memory ---
    private static final String KEY_UNLIMITED_MEMORY = "unlimited_memory_enabled";

    // --- Built-in spam guard & Colemailer suite ---
    private static final String KEY_SPAM_GUARD = "spam_guard_enabled";
    private static final String KEY_COLEMAILER_NOTIF_FILTER = "colemailer_notif_filter_enabled";
    private static final String KEY_COLEMAILER_SAVE_LEADS = "colemailer_save_leads_enabled";
    private static final String KEY_COLEMAILER_BROADCASTER = "colemailer_broadcaster_enabled";
    private static final String KEY_COLEMAILER_AUTOSUB = "colemailer_autosub_enabled";
    private static final String KEY_COLEMAILER_MUTE_MODE = "colemailer_mute_mode";
    private static final String KEY_COLEMAILER_BOT_TOKEN = "colemailer_bot_token";
    private static final String KEY_COLEMAILER_STATE = "colemailer_state_v1";

    // --- Fetch relay ---
    private static final String KEY_RELAY_URL = "relay_url";

    // --- Built-in features that used to be Python plugins ---
    private static final String KEY_KEYWORD_ALERTS = "keyword_alerts_enabled";
    private static final String KEY_KEYWORD_ALERTS_LIST = "keyword_alerts_list";
    private static final String KEY_MESSAGE_LOGGER = "message_logger_enabled";

    // --- Auto proxy & strict proxy security ---
    private static final String KEY_AUTO_PROXY = "auto_proxy_enabled";
    private static final String KEY_IPV6_BYPASS = "ipv6_bypass_enabled";
    private static final String KEY_DC_REMAP = "dc_remap_enabled";
    private static final String KEY_TLS_MIMIC = "tls_mimic_enabled";
    private static final String KEY_STRICT_PROXY_SECURITY = "strict_proxy_security_enabled";
    private static final String KEY_BROWSER_ROUTE = "browser_route_mode";
    private static final String KEY_BROWSER_ROUTE_SOCKS = "browser_route_socks";

    // --- Max anonymity & AyuGram+ privacy controls ---
    private static final String KEY_MAX_ANONYMITY = "max_anonymity_enabled";
    private static final String KEY_SAVE_ONETIME_MEDIA = "save_onetime_media_enabled";
    private static final String KEY_HIDE_SCREENSHOT_NOTIFY = "hide_screenshot_notify_enabled";
    private static final String KEY_STEALTH_STORIES = "stealth_stories_enabled";
    private static final String KEY_FORWARD_NO_QUOTE = "forward_no_quote_enabled";
    private static final String KEY_HIDE_PHONE_LOCAL = "hide_phone_local_enabled";
    private static final String KEY_ANTI_DELETE_SELF = "anti_delete_self_enabled";
    private static final String KEY_CONFIRM_CALLS = "confirm_calls_enabled";
    private static final String KEY_CONFIRM_STICKERS = "confirm_stickers_enabled";
    private static final String KEY_CONFIRM_LINKS = "confirm_links_enabled";

    /**
     * Whether Colgram may switch to a third-party public proxy when direct local bypass is cut.
     *
     * Off by default: the local DPI bypass stays strictly on the user's own internet without
     * routing traffic through third-party proxies unless explicitly opted into.
     */
    public static boolean isAutoProxyEnabled() {
        return autoProxyEnabled;
    }

    public static void setAutoProxyEnabled(boolean enabled) {
        if (prefs != null) {
            autoProxyEnabled = enabled;
            prefs.edit().putBoolean(KEY_AUTO_PROXY, enabled).apply();
        }
    }

    /** Enforce DoH-first resolution, remote SOCKS5 DNS, no plaintext HTTP over third-party proxies, and no WebRTC P2P IP leaks. */
    public static boolean isStrictProxySecurityEnabled() {
        return strictProxySecurityEnabled;
    }

    public static void setStrictProxySecurityEnabled(boolean enabled) {
        if (prefs != null) {
            strictProxySecurityEnabled = enabled;
            prefs.edit().putBoolean(KEY_STRICT_PROXY_SECURITY, enabled).apply();
        }
    }

    public static String getBrowserRouteMode() {
        return browserRouteMode;
    }

    public static void setBrowserRouteMode(String mode) {
        if (prefs != null) {
            String clean = mode == null || mode.trim().isEmpty() ? "LOCAL_DPI" : mode.trim();
            browserRouteMode = clean;
            prefs.edit().putString(KEY_BROWSER_ROUTE, clean).apply();
        }
    }

    public static String getBrowserRouteSocks() {
        return browserRouteSocks;
    }

    public static void setBrowserRouteSocks(String socksIdentity) {
        if (prefs != null) {
            String clean = socksIdentity == null ? "" : socksIdentity.trim();
            browserRouteSocks = clean;
            prefs.edit().putString(KEY_BROWSER_ROUTE_SOCKS, clean).apply();
        }
    }

    public static boolean isMaxAnonymityEnabled() {
        return maxAnonymityEnabled;
    }

    /** Apply or clear the one-tap maximum anonymity preset across ghost mode, stealth, cloaking, and proxy hardening. */
    public static void setMaxAnonymityEnabled(boolean enabled) {
        if (prefs == null) return;
        maxAnonymityEnabled = enabled;
        SharedPreferences.Editor ed = prefs.edit().putBoolean(KEY_MAX_ANONYMITY, enabled);
        if (enabled) {
            ghostReadEnabled = true;
            ghostTypingEnabled = true;
            ghostOnlineEnabled = true;
            stealthStoriesEnabled = true;
            bypassFlagSecureEnabled = true;
            hideScreenshotNotifyEnabled = true;
            cloakEnabled = true;
            autoHidePhoneEnabled = true;
            hidePhoneLocalEnabled = true;
            strictProxySecurityEnabled = true;
            dohEnabled = true;
            autoProxyEnabled = false;
            forwardNoQuoteEnabled = true;
            confirmCallsEnabled = true;
            confirmLinksEnabled = true;
            ed.putBoolean(KEY_GHOST_READ, true)
                    .putBoolean(KEY_GHOST_TYPING, true)
                    .putBoolean(KEY_GHOST_ONLINE, true)
                    .putBoolean(KEY_STEALTH_STORIES, true)
                    .putBoolean(KEY_BYPASS_FLAG_SECURE, true)
                    .putBoolean(KEY_HIDE_SCREENSHOT_NOTIFY, true)
                    .putBoolean(KEY_CLOAK_ENABLED, true)
                    .putBoolean(KEY_AUTO_HIDE_PHONE, true)
                    .putBoolean(KEY_HIDE_PHONE_LOCAL, true)
                    .putBoolean(KEY_STRICT_PROXY_SECURITY, true)
                    .putBoolean(KEY_DOH_ENABLED, true)
                    .putBoolean(KEY_AUTO_PROXY, false)
                    .putBoolean(KEY_FORWARD_NO_QUOTE, true)
                    .putBoolean(KEY_CONFIRM_CALLS, true)
                    .putBoolean(KEY_CONFIRM_LINKS, true);
        }
        ed.apply();
    }

    public static boolean isSaveOneTimeMediaEnabled() {
        return saveOneTimeMediaEnabled;
    }

    public static void setSaveOneTimeMediaEnabled(boolean enabled) {
        if (prefs != null) {
            saveOneTimeMediaEnabled = enabled;
            prefs.edit().putBoolean(KEY_SAVE_ONETIME_MEDIA, enabled).apply();
        }
    }

    public static boolean isHideScreenshotNotifyEnabled() {
        return hideScreenshotNotifyEnabled;
    }

    public static void setHideScreenshotNotifyEnabled(boolean enabled) {
        if (prefs != null) {
            hideScreenshotNotifyEnabled = enabled;
            prefs.edit().putBoolean(KEY_HIDE_SCREENSHOT_NOTIFY, enabled).apply();
        }
    }

    public static boolean isStealthStoriesEnabled() {
        return stealthStoriesEnabled;
    }

    public static void setStealthStoriesEnabled(boolean enabled) {
        if (prefs != null) {
            stealthStoriesEnabled = enabled;
            prefs.edit().putBoolean(KEY_STEALTH_STORIES, enabled).apply();
        }
    }

    public static boolean isForwardNoQuoteEnabled() {
        return forwardNoQuoteEnabled;
    }

    public static void setForwardNoQuoteEnabled(boolean enabled) {
        if (prefs != null) {
            forwardNoQuoteEnabled = enabled;
            prefs.edit().putBoolean(KEY_FORWARD_NO_QUOTE, enabled).apply();
        }
    }

    public static boolean isHidePhoneLocalEnabled() {
        return hidePhoneLocalEnabled;
    }

    public static void setHidePhoneLocalEnabled(boolean enabled) {
        if (prefs != null) {
            hidePhoneLocalEnabled = enabled;
            prefs.edit().putBoolean(KEY_HIDE_PHONE_LOCAL, enabled).apply();
        }
    }

    public static boolean isAntiDeleteSelfEnabled() {
        return antiDeleteSelfEnabled;
    }

    public static void setAntiDeleteSelfEnabled(boolean enabled) {
        if (prefs != null) {
            antiDeleteSelfEnabled = enabled;
            prefs.edit().putBoolean(KEY_ANTI_DELETE_SELF, enabled).apply();
        }
    }

    public static boolean isConfirmCallsEnabled() {
        return confirmCallsEnabled;
    }

    public static void setConfirmCallsEnabled(boolean enabled) {
        if (prefs != null) {
            confirmCallsEnabled = enabled;
            prefs.edit().putBoolean(KEY_CONFIRM_CALLS, enabled).apply();
        }
    }

    public static boolean isConfirmStickersEnabled() {
        return confirmStickersEnabled;
    }

    public static void setConfirmStickersEnabled(boolean enabled) {
        if (prefs != null) {
            confirmStickersEnabled = enabled;
            prefs.edit().putBoolean(KEY_CONFIRM_STICKERS, enabled).apply();
        }
    }

    public static boolean isConfirmLinksEnabled() {
        return confirmLinksEnabled;
    }

    public static void setConfirmLinksEnabled(boolean enabled) {
        if (prefs != null) {
            confirmLinksEnabled = enabled;
            prefs.edit().putBoolean(KEY_CONFIRM_LINKS, enabled).apply();
        }
    }

    /**
     * Rewrite FakeTLS ClientHellos into browser fingerprints on the local mimic front.
     * TSPU recognises tgnet's fixed hello byte-for-byte, which is how public MTProto proxies
     * die on Russian networks even with a live TCP route. The rewriting only changes what a
     * DPI sees - the proxy server's own handshake math is preserved.
     */
    public static boolean isTlsMimicEnabled() {
        return tlsMimicEnabled;
    }

    public static void setTlsMimicEnabled(boolean enabled) {
        if (prefs != null) {
            tlsMimicEnabled = enabled;
            prefs.edit().putBoolean(KEY_TLS_MIMIC, enabled).apply();
        }
    }

    /**
     * Route Telegram over IPv6 only. Off by default: on a network whose IPv6 cannot reach
     * Telegram this breaks a connection IPv4 was carrying, so it is a choice, not a guess.
     * Where IPv6 does work it is the only bypass with no proxy and no third party in the path.
     */
    public static boolean isIpv6BypassEnabled() {
        return ipv6BypassEnabled;
    }

    public static void setIpv6BypassEnabled(boolean enabled) {
        if (prefs != null) {
            ipv6BypassEnabled = enabled;
            prefs.edit().putBoolean(KEY_IPV6_BYPASS, enabled).apply();
        }
    }

    /**
     * Dial whichever of Telegram's own DC addresses answers instead of the one tgnet hardcodes.
     *
     * Not a proxy: the traffic still goes straight to Telegram, only the address is chosen better,
     * and nothing in between can read it. Off by default because it works only where some DC
     * address is reachable at all - on a network that drops every one of them it changes nothing.
     */
    public static boolean isDcRemapEnabled() {
        if (dcRemapLoaded) {
            return dcRemapEnabled;
        }
        SharedPreferences p = prefs;
        if (p == null) {
            return false;
        }
        boolean val = p.getBoolean(KEY_DC_REMAP, false);
        dcRemapEnabled = val;
        dcRemapLoaded = true;
        return val;
    }

    public static void setDcRemapEnabled(boolean enabled) {
        if (prefs != null) {
            dcRemapEnabled = enabled;
            dcRemapLoaded = true;
            prefs.edit().putBoolean(KEY_DC_REMAP, enabled).apply();
        }
    }

    /**
     * Optional user-supplied HTTPS forwarder (a Cloudflare Worker URL). It carries the Bot API,
     * the mail providers and the proxy-list fetches on networks where those IPs are dropped and
     * no public SOCKS relay answers. Empty means "no relay configured".
     */
    public static String getRelayUrl() {
        return relayUrl;
    }

    public static void setRelayUrl(String url) {
        if (prefs != null) {
            String clean = url == null ? "" : url.trim();
            relayUrl = clean;
            prefs.edit().putString(KEY_RELAY_URL, clean).apply();
        }
    }

    /**
     * The address that last answered for a hostname. Persisted because a cold start should not
     * have to re-probe the whole address pool before it can reach the Bot API.
     */
    public static String getPinnedEndpoint(String host) {
        SharedPreferences p = prefs;
        if (p == null || host == null) return "";
        String cached = pinnedEndpointsCache.get(host);
        if (cached != null) return cached;
        String val = p.getString("pin_" + host, "");
        pinnedEndpointsCache.put(host, val);
        return val;
    }

    public static void setPinnedEndpoint(String host, String address) {
        if (prefs != null && host != null && address != null && !address.isEmpty()) {
            pinnedEndpointsCache.put(host, address);
            prefs.edit().putString("pin_" + host, address).apply();
        }
    }

    /** On by default: it only ever silences strangers who score as advertising. */
    public static boolean isSpamGuardEnabled() {
        return spamGuardEnabled;
    }

    public static void setSpamGuardEnabled(boolean enabled) {
        if (prefs != null) {
            spamGuardEnabled = enabled;
            prefs.edit().putBoolean(KEY_SPAM_GUARD, enabled).apply();
        }
    }

    public static boolean isNotificationAdFilterEnabled() {
        return notificationAdFilterEnabled;
    }

    public static void setNotificationAdFilterEnabled(boolean enabled) {
        if (prefs != null) {
            notificationAdFilterEnabled = enabled;
            prefs.edit().putBoolean(KEY_COLEMAILER_NOTIF_FILTER, enabled).apply();
        }
    }

    /** Strictly opt-in (OFF by default): never forward messages from other chats into Saved Messages unless enabled. */
    public static boolean isSaveLeadsEnabled() {
        return saveLeadsEnabled;
    }

    public static void setSaveLeadsEnabled(boolean enabled) {
        if (prefs != null) {
            saveLeadsEnabled = enabled;
            prefs.edit().putBoolean(KEY_COLEMAILER_SAVE_LEADS, enabled).apply();
        }
    }

    public static boolean isBroadcasterEnabled() {
        return broadcasterEnabled;
    }

    public static void setBroadcasterEnabled(boolean enabled) {
        if (prefs != null) {
            broadcasterEnabled = enabled;
            prefs.edit().putBoolean(KEY_COLEMAILER_BROADCASTER, enabled).apply();
        }
    }

    public static boolean isAutoSubEnabled() {
        return autoSubEnabled;
    }

    public static void setAutoSubEnabled(boolean enabled) {
        if (prefs != null) {
            autoSubEnabled = enabled;
            prefs.edit().putBoolean(KEY_COLEMAILER_AUTOSUB, enabled).apply();
        }
    }

    public static String getDefaultMuteMode() {
        return defaultMuteMode;
    }

    public static void setDefaultMuteMode(String mode) {
        if (prefs == null) return;
        String clean = mode == null ? "silent" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!clean.equals("silent") && !clean.equals("delete") && !clean.equals("warn") && !clean.equals("ban")) {
            clean = "silent";
        }
        defaultMuteMode = clean;
        prefs.edit().putString(KEY_COLEMAILER_MUTE_MODE, clean).apply();
    }

    public static String getHelperBotToken() {
        return helperBotToken;
    }

    public static void setHelperBotToken(String token) {
        if (prefs != null) {
            String clean = token == null ? "" : token.trim();
            helperBotToken = clean;
            prefs.edit().putString(KEY_COLEMAILER_BOT_TOKEN, clean).apply();
        }
    }

    public static String getColemailerState() {
        return colemailerState;
    }

    public static void setColemailerState(String serialized) {
        if (prefs != null) {
            String clean = serialized == null ? "" : serialized;
            colemailerState = clean;
            prefs.edit().putString(KEY_COLEMAILER_STATE, clean).apply();
        }
    }

    /**
     * "Unlimited memory" means the media cache is never auto-evicted: Telegram's own
     * AutoDeleteMediaTask reads the cache_limit preference and defaults it to 300 MB, which is
     * what makes old videos and photos silently disappear. With this on the limit is raised to
     * the integer maximum, so everything already viewed stays viewable, and it lives in the app's
     * own storage instead of the gallery, which is the "not cluttering the phone" half.
     */
    public static boolean isUnlimitedMemoryConfigured() {
        return prefs != null && unlimitedMemoryConfigured;
    }

    public static boolean isUnlimitedMemoryEnabled() {
        return unlimitedMemoryEnabled;
    }

    public static void setUnlimitedMemoryEnabled(boolean enabled) {
        if (prefs != null) {
            unlimitedMemoryConfigured = true;
            unlimitedMemoryEnabled = enabled;
            prefs.edit().putBoolean(KEY_UNLIMITED_MEMORY, enabled).apply();
        }
    }

    public static boolean isAutoCompressStorageEnabled() {
        return autoCompressStorageEnabled;
    }

    public static void setAutoCompressStorageEnabled(boolean enabled) {
        if (prefs != null) {
            autoCompressStorageEnabled = enabled;
            prefs.edit().putBoolean("auto_compress_storage_enabled", enabled).apply();
        }
    }

    /** Per-chat (dialogId != 0) or global (dialogId == 0) local Video Wallpaper path (.mp4/.webm/.gif). */
    public static String getChatVideoWallpaper(long dialogId) {
        if (prefs == null) return "";
        if (dialogId != 0L) {
            String specific = getExactChatVideoWallpaper(dialogId);
            if (!specific.isEmpty()) return specific;
            if (!getExactChatImageWallpaper(dialogId).isEmpty()) return "";
        }
        return getExactChatVideoWallpaper(0L);
    }

    public static String getExactChatVideoWallpaper(long dialogId) {
        SharedPreferences p = prefs;
        if (p == null) return "";
        String cached = chatVideoWallpapersCache.get(dialogId);
        if (cached != null) return cached;
        String val = p.getString("colgram_video_wp_" + dialogId, "");
        chatVideoWallpapersCache.put(dialogId, val);
        return val;
    }

    public static void setChatVideoWallpaper(long dialogId, String path) {
        if (prefs != null) {
            if (path == null || path.trim().isEmpty()) {
                chatVideoWallpapersCache.put(dialogId, "");
                prefs.edit().remove("colgram_video_wp_" + dialogId).apply();
            } else {
                String clean = path.trim();
                chatVideoWallpapersCache.put(dialogId, clean);
                prefs.edit().putString("colgram_video_wp_" + dialogId, clean).apply();
            }
        }
    }

    /** Per-chat or global (dialogId == 0) local gallery image wallpaper path (persists across restarts on all accounts). */
    public static String getChatImageWallpaper(long dialogId) {
        if (prefs == null) return "";
        if (dialogId != 0L) {
            String exact = getExactChatImageWallpaper(dialogId);
            if (!exact.isEmpty()) return exact;
            if (!getExactChatVideoWallpaper(dialogId).isEmpty()) return "";
        }
        return getExactChatImageWallpaper(0L);
    }

    public static String getExactChatImageWallpaper(long dialogId) {
        SharedPreferences p = prefs;
        if (p == null) return "";
        String cached = chatImageWallpapersCache.get(dialogId);
        if (cached != null) return cached;
        String val = p.getString("colgram_img_wp_" + dialogId, "");
        chatImageWallpapersCache.put(dialogId, val);
        return val;
    }

    public static void setChatImageWallpaper(long dialogId, String path) {
        if (prefs != null) {
            if (path == null || path.trim().isEmpty()) {
                chatImageWallpapersCache.put(dialogId, "");
                prefs.edit().remove("colgram_img_wp_" + dialogId).apply();
            } else {
                String clean = path.trim();
                chatImageWallpapersCache.put(dialogId, clean);
                prefs.edit().putString("colgram_img_wp_" + dialogId, clean).apply();
            }
        }
    }

    // --- Always-On Background & OTA Throttle ---
    private static final String KEY_ALWAYS_BACKGROUND = "always_background_enabled";
    private static final String KEY_LAST_UPDATE_CHECK_MS = "last_auto_update_check_ms";
    private static final String KEY_SHOW_MESSAGE_SECONDS = "show_message_seconds";
    private static final String KEY_SHOW_MESSAGE_ID = "show_message_id_menu";

    public static boolean isAlwaysBackgroundEnabled() {
        return alwaysBackgroundEnabled;
    }

    public static void setAlwaysBackgroundEnabled(boolean enabled) {
        if (prefs != null) {
            alwaysBackgroundEnabled = enabled;
            prefs.edit().putBoolean(KEY_ALWAYS_BACKGROUND, enabled).apply();
        }
    }

    public static long getLastAutoUpdateCheckMs() {
        return lastAutoUpdateCheckMs;
    }

    public static void setLastAutoUpdateCheckMs(long tsMs) {
        if (prefs != null) {
            lastAutoUpdateCheckMs = tsMs;
            prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_MS, tsMs).apply();
        }
    }

    public static boolean isShowMessageSecondsEnabled() {
        return showMessageSecondsEnabled;
    }

    public static void setShowMessageSecondsEnabled(boolean enabled) {
        if (prefs != null) {
            showMessageSecondsEnabled = enabled;
            prefs.edit().putBoolean(KEY_SHOW_MESSAGE_SECONDS, enabled).apply();
        }
    }

    public static boolean isShowMessageIdEnabled() {
        return showMessageIdEnabled;
    }

    public static void setShowMessageIdEnabled(boolean enabled) {
        if (prefs != null) {
            showMessageIdEnabled = enabled;
            prefs.edit().putBoolean(KEY_SHOW_MESSAGE_ID, enabled).apply();
        }
    }

    // --- Built-in Defender Bot ("Бот-защитник Colgram") & Update Helpers ---
    private static final String KEY_DEFENDER_ENABLED = "defender_enabled";
    private static final String KEY_DEFENDER_AUTO_CLOSE = "defender_auto_close";
    private static final String KEY_DEFENDER_BLOCK_VOIP_P2P = "defender_block_voip_p2p";
    private static final String KEY_DEFENDER_BLOCK_BOT_LOCATION = "defender_block_bot_location";
    private static final String KEY_DEFENDER_STRIP_TRACKERS = "defender_strip_trackers";
    private static final String KEY_DEFENDER_WEBVIEW_HARDENING = "defender_webview_hardening";
    private static final String KEY_DEFENDER_LAST_SCAN_MS = "defender_last_scan_ms";
    private static final String KEY_DEFENDER_FIXED_TOTAL = "defender_fixed_total";
    private static final String KEY_DEFENDER_TRACKERS_STRIPPED = "defender_trackers_stripped";
    private static final String KEY_DEFENDER_P2P_BLOCKED = "defender_p2p_blocked";

    public static boolean isDefenderEnabled() {
        return defenderEnabled;
    }

    public static void setDefenderEnabled(boolean enabled) {
        if (prefs != null) {
            defenderEnabled = enabled;
            prefs.edit().putBoolean(KEY_DEFENDER_ENABLED, enabled).apply();
        }
    }

    public static boolean isDefenderAutoCloseEnabled() {
        return defenderAutoCloseEnabled;
    }

    public static void setDefenderAutoCloseEnabled(boolean enabled) {
        if (prefs != null) {
            defenderAutoCloseEnabled = enabled;
            prefs.edit().putBoolean(KEY_DEFENDER_AUTO_CLOSE, enabled).apply();
        }
    }

    public static boolean isDefenderBlockVoipP2pEnabled() {
        return defenderBlockVoipP2pEnabled;
    }

    public static void setDefenderBlockVoipP2pEnabled(boolean enabled) {
        if (prefs != null) {
            defenderBlockVoipP2pEnabled = enabled;
            prefs.edit().putBoolean(KEY_DEFENDER_BLOCK_VOIP_P2P, enabled).apply();
        }
    }

    public static boolean isDefenderBlockBotLocationEnabled() {
        return defenderBlockBotLocationEnabled;
    }

    public static void setDefenderBlockBotLocationEnabled(boolean enabled) {
        if (prefs != null) {
            defenderBlockBotLocationEnabled = enabled;
            prefs.edit().putBoolean(KEY_DEFENDER_BLOCK_BOT_LOCATION, enabled).apply();
        }
    }

    public static boolean isDefenderStripTrackersEnabled() {
        return defenderStripTrackersEnabled;
    }

    public static void setDefenderStripTrackersEnabled(boolean enabled) {
        if (prefs != null) {
            defenderStripTrackersEnabled = enabled;
            prefs.edit().putBoolean(KEY_DEFENDER_STRIP_TRACKERS, enabled).apply();
        }
    }

    public static boolean isDefenderWebViewHardeningEnabled() {
        return defenderWebViewHardeningEnabled;
    }

    public static void setDefenderWebViewHardeningEnabled(boolean enabled) {
        if (prefs != null) {
            defenderWebViewHardeningEnabled = enabled;
            prefs.edit().putBoolean(KEY_DEFENDER_WEBVIEW_HARDENING, enabled).apply();
        }
    }

    public static long getDefenderLastScanMs() {
        return defenderLastScanMs;
    }

    public static void setDefenderLastScanMs(long tsMs) {
        if (prefs != null) {
            defenderLastScanMs = tsMs;
            prefs.edit().putLong(KEY_DEFENDER_LAST_SCAN_MS, tsMs).apply();
        }
    }

    public static int getDefenderFixedTotal() {
        return defenderFixedTotal;
    }

    public static void addDefenderFixedCount(int delta) {
        if (prefs != null && delta > 0) {
            synchronized (DEFENDER_COUNTER_LOCK) {
                int next = defenderFixedTotal + delta;
                defenderFixedTotal = next;
                prefs.edit().putInt(KEY_DEFENDER_FIXED_TOTAL, next).apply();
            }
        }
    }

    public static int getDefenderTrackersStripped() {
        return defenderTrackersStripped;
    }

    public static void addDefenderTrackersStripped(int delta) {
        if (prefs != null && delta > 0) {
            synchronized (DEFENDER_COUNTER_LOCK) {
                int next = defenderTrackersStripped + delta;
                defenderTrackersStripped = next;
                prefs.edit().putInt(KEY_DEFENDER_TRACKERS_STRIPPED, next).apply();
            }
        }
    }

    public static int getDefenderP2pBlocked() {
        return defenderP2pBlocked;
    }

    public static void incrementDefenderP2pBlocked() {
        if (prefs != null) {
            synchronized (DEFENDER_COUNTER_LOCK) {
                int next = defenderP2pBlocked + 1;
                defenderP2pBlocked = next;
                prefs.edit().putInt(KEY_DEFENDER_P2P_BLOCKED, next).apply();
            }
        }
    }

    // --- Quick Access Panel & 1-Tap Ghost Mode ---
    private static final String KEY_QUICK_BAR_ENABLED = "quick_bar_enabled";
    private static final String KEY_QUICK_BAR_ITEMS = "quick_bar_items";

    public static boolean isQuickBarEnabled() {
        return quickBarEnabled;
    }

    public static void setQuickBarEnabled(boolean enabled) {
        if (prefs != null) {
            quickBarEnabled = enabled;
            prefs.edit().putBoolean(KEY_QUICK_BAR_ENABLED, enabled).apply();
        }
    }

    private static java.util.List<String> parseQuickBarItems(String raw) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String part : raw.split(",")) {
                String clean = part.trim();
                if (!clean.isEmpty() && !list.contains(clean)) {
                    list.add(clean);
                }
            }
        }
        return java.util.Collections.unmodifiableList(list);
    }

    public static java.util.List<String> getQuickBarItems() {
        return new java.util.ArrayList<>(quickBarItems);
    }

    public static void setQuickBarItems(java.util.List<String> items) {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        if (items != null) {
            for (String item : items) {
                if (item == null || item.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(item.trim());
            }
        }
        String raw = sb.toString();
        quickBarItems = parseQuickBarItems(raw);
        prefs.edit().putString(KEY_QUICK_BAR_ITEMS, raw).apply();
    }

    public static boolean isQuickGhostActive() {
        return isGhostOnlineEnabled() && isGhostReadEnabled();
    }

    public static void setQuickGhostMode(boolean enabled) {
        if (prefs == null) return;
        ghostReadEnabled = enabled;
        ghostOnlineEnabled = enabled;
        ghostTypingEnabled = enabled;
        stealthStoriesEnabled = enabled;
        prefs.edit()
                .putBoolean(KEY_GHOST_READ, enabled)
                .putBoolean(KEY_GHOST_ONLINE, enabled)
                .putBoolean(KEY_GHOST_TYPING, enabled)
                .putBoolean(KEY_STEALTH_STORIES, enabled)
                .apply();
    }
}
