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

    private static SharedPreferences prefs;

    public static void init(Context context) {
        if (prefs == null && context != null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            // Upgrade defaults once so anti-delete self-preserve & unlimited auto-compress storage are always ON by default.
            if (!prefs.getBoolean("colgram_defaults_v3_migrated", false)) {
                prefs.edit()
                        .putBoolean(KEY_ANTI_DELETE_ENABLED, true)
                        .putBoolean(KEY_ANTI_DELETE_SELF, true)
                        .putBoolean(KEY_ANTI_DELETE_WIPE, false)
                        .putBoolean(KEY_ANTI_DELETE_HIGHLIGHT, true)
                        .putBoolean(KEY_UNLIMITED_MEMORY, true)
                        .putBoolean("auto_compress_storage_enabled", true)
                        .putBoolean("colgram_defaults_v3_migrated", true)
                        .apply();
            }
        }
        // The WARP row's gate asks whether the MASQUE client exists without loading it, and needs a
        // Context to answer from the package. ColgramConfig.init is the one initialiser every entry
        // point already calls, so it is where that Context is available.
        if (context != null) {
            ColgramMasqueNative.setProbeContext(context.getApplicationContext());
            ColgramDesignConfig.init(context.getApplicationContext());
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

    /**
     * Whether a retained (anti-deleted) message is tinted so it reads as "this was deleted
     * by the other side". Purely `contentDescription`-style metadata — it never changes
     * message state. On by default; turn off for a completely invisible interception.
     */
    public static boolean isAntiDeleteHighlightEnabled() {
        return prefs == null || prefs.getBoolean(KEY_ANTI_DELETE_HIGHLIGHT, true);
    }

    public static void setAntiDeleteHighlightEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_ANTI_DELETE_HIGHLIGHT, enabled).apply();
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
        return prefs != null && prefs.getBoolean(KEY_ANTI_DELETE_WIPE, false);
    }

    public static void setAntiDeleteWipeEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_ANTI_DELETE_WIPE, enabled).apply();
    }

    public static boolean isChatWallpaperEnabled() {
        return prefs == null || prefs.getBoolean(KEY_CHAT_WALLPAPER_ENABLED, true);
    }

    public static void setChatWallpaperEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_CHAT_WALLPAPER_ENABLED, enabled).apply();
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
        return prefs == null || prefs.getBoolean(KEY_AUTO_HIDE_PHONE, true);
    }

    public static void setAutoHidePhoneEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTO_HIDE_PHONE, enabled).apply();
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
    /** Mandatory local base; availability is reported separately from Telegram connectivity. */
    private static final String KEY_DPI_BYPASS_ENABLED = "dpi_bypass_enabled";

    public static boolean isDpiBypassEnabled() {
        return true; // The local base is mandatory; old persisted false values do not disable it.
    }

    public static void setDpiBypassEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DPI_BYPASS_ENABLED, true).apply();
    }

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

    public static boolean isProxyBrowserEnabled() {
        return prefs == null || prefs.getBoolean(KEY_PROXY_BROWSER_ENABLED, true);
    }

    public static void setProxyBrowserEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_PROXY_BROWSER_ENABLED, enabled).apply();
    }

    public static String getUpdateRepo() {
        return prefs != null ? prefs.getString(KEY_UPDATE_REPO, DEFAULT_UPDATE_REPO) : DEFAULT_UPDATE_REPO;
    }

    public static void setUpdateRepo(String repo) {
        if (prefs == null) return;
        String clean = repo != null ? repo.trim() : "";
        if (clean.isEmpty()) {
            prefs.edit().remove(KEY_UPDATE_REPO).apply();
        } else {
            prefs.edit().putString(KEY_UPDATE_REPO, clean).apply();
        }
    }

    public static boolean isAutoUpdateEnabled() {
        return prefs == null || prefs.getBoolean(KEY_AUTO_UPDATE_ENABLED, true);
    }

    public static void setAutoUpdateEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply();
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
        return prefs != null && prefs.getBoolean(KEY_CYBER_THEME_ENABLED, false);
    }

    public static void setCyberThemeEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_CYBER_THEME_ENABLED, enabled).apply();
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
        return prefs != null ? prefs.getString(KEY_PENDING_UPSTREAM_TAG, null) : null;
    }

    public static void setPendingUpstreamTag(String tag) {
        if (prefs != null) prefs.edit().putString(KEY_PENDING_UPSTREAM_TAG, tag).apply();
    }

    public static void clearPendingUpstreamTag() {
        if (prefs != null) prefs.edit().remove(KEY_PENDING_UPSTREAM_TAG).apply();
    }

    // --- Mini-app floating windows ---

    /**
     * Whether a mini-app can be pinned into an Android picture-in-picture floating
     * window. Enabled by default; the UI reads this before offering the action.
     */
    public static boolean isMiniAppPipEnabled() {
        return prefs == null || prefs.getBoolean(KEY_MINIAPP_PIP_ENABLED, true);
    }

    public static void setMiniAppPipEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_MINIAPP_PIP_ENABLED, enabled).apply();
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
        return prefs != null ? prefs.getInt(KEY_MINIAPP_PIP_MAX, 0) : 0;
    }

    public static void setMiniAppPipMaxWindows(int max) {
        if (prefs != null) prefs.edit().putInt(KEY_MINIAPP_PIP_MAX, max).apply();
    }

    /**
     * Remembered on-screen position of the nth floating mini-app window, as "x,y".
     * Windows are draggable, so there is no sensible default beyond a cascade.
     */
    public static String getMiniAppPipPosition(int slot) {
        return prefs != null ? prefs.getString(KEY_MINIAPP_PIP_POS_PREFIX + slot, null) : null;
    }

    public static void setMiniAppPipPosition(int slot, int x, int y) {
        if (prefs != null) {
            prefs.edit().putString(KEY_MINIAPP_PIP_POS_PREFIX + slot, x + "," + y).apply();
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
    public static final String DEFAULT_AUTO_REPLY_TEXT =
            "Привет, {first_name}! Сейчас занят, отвечу позже. ({time})";
    public static final String DEFAULT_AUTO_REPLY_AWAY_TEXT =
            "Сейчас не в сети (нерабочее время), {first_name}. Отвечу утром!";

    public static boolean isAutoReplyEnabled() {
        return prefs != null && prefs.getBoolean(KEY_AUTO_REPLY_ENABLED, false);
    }

    public static void setAutoReplyEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTO_REPLY_ENABLED, enabled).apply();
    }

    public static String getAutoReplyText() {
        return prefs != null ? prefs.getString(KEY_AUTO_REPLY_TEXT, DEFAULT_AUTO_REPLY_TEXT)
                : DEFAULT_AUTO_REPLY_TEXT;
    }

    public static void setAutoReplyText(String text) {
        if (prefs != null) prefs.edit().putString(KEY_AUTO_REPLY_TEXT, text).apply();
    }

    /** When off, the reply only answers private chats; groups stay quiet. */
    public static boolean isAutoReplyInGroups() {
        return prefs != null && prefs.getBoolean(KEY_AUTO_REPLY_GROUPS, false);
    }

    public static void setAutoReplyInGroups(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTO_REPLY_GROUPS, enabled).apply();
    }

    /** Minimum seconds between two replies to the same dialog; 0 answers every message. */
    public static int getAutoReplyCooldownSec() {
        return prefs != null ? prefs.getInt(KEY_AUTO_REPLY_COOLDOWN, 300) : 300;
    }

    public static void setAutoReplyCooldownSec(int seconds) {
        if (prefs != null) prefs.edit().putInt(KEY_AUTO_REPLY_COOLDOWN, seconds).apply();
    }

    public static boolean isAutoReplyScheduleEnabled() {
        return prefs != null && prefs.getBoolean(KEY_AUTO_REPLY_SCHEDULE, false);
    }

    public static void setAutoReplyScheduleEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTO_REPLY_SCHEDULE, enabled).apply();
    }

    public static boolean isAutoReplyOnlyWhenAway() {
        return prefs != null && prefs.getBoolean(KEY_AUTO_REPLY_ONLY_AWAY, false);
    }

    public static void setAutoReplyOnlyWhenAway(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTO_REPLY_ONLY_AWAY, enabled).apply();
    }

    public static int getAutoReplyStartMin() {
        return prefs != null ? prefs.getInt(KEY_AUTO_REPLY_START_MIN, 22 * 60) : 22 * 60;
    }

    public static void setAutoReplyStartMin(int minutes) {
        if (prefs != null) prefs.edit().putInt(KEY_AUTO_REPLY_START_MIN, minutes).apply();
    }

    public static int getAutoReplyEndMin() {
        return prefs != null ? prefs.getInt(KEY_AUTO_REPLY_END_MIN, 9 * 60) : 9 * 60;
    }

    public static void setAutoReplyEndMin(int minutes) {
        if (prefs != null) prefs.edit().putInt(KEY_AUTO_REPLY_END_MIN, minutes).apply();
    }

    public static String getAutoReplyAwayText() {
        return prefs != null ? prefs.getString(KEY_AUTO_REPLY_AWAY_TEXT, DEFAULT_AUTO_REPLY_AWAY_TEXT)
                : DEFAULT_AUTO_REPLY_AWAY_TEXT;
    }

    public static void setAutoReplyAwayText(String text) {
        if (prefs != null) prefs.edit().putString(KEY_AUTO_REPLY_AWAY_TEXT, text).apply();
    }

    public static int getAutoReplyAudience() {
        return prefs != null ? prefs.getInt(KEY_AUTO_REPLY_AUDIENCE, ColgramAutoReplyPolicy.AUDIENCE_ALL)
                : ColgramAutoReplyPolicy.AUDIENCE_ALL;
    }

    public static void setAutoReplyAudience(int mode) {
        if (prefs != null) prefs.edit().putInt(KEY_AUTO_REPLY_AUDIENCE, mode).apply();
    }

    public static boolean isAutoReplyToBots() {
        return prefs != null && prefs.getBoolean(KEY_AUTO_REPLY_BOTS, false);
    }

    public static void setAutoReplyToBots(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTO_REPLY_BOTS, enabled).apply();
    }

    public static String getAutoReplyBlacklist() {
        return prefs != null ? prefs.getString(KEY_AUTO_REPLY_BLACKLIST, "") : "";
    }

    public static void setAutoReplyBlacklist(String csv) {
        if (prefs != null) prefs.edit().putString(KEY_AUTO_REPLY_BLACKLIST, csv == null ? "" : csv).apply();
    }

    private static final String KEY_LIVE_WALLPAPER_WAVES_MIGRATED = "live_wallpaper_waves_v2";

    public static int getLiveWallpaperStyle() {
        if (prefs == null) return ColgramLiveWallpaper.STYLE_AURORA;
        if (!prefs.getBoolean(KEY_LIVE_WALLPAPER_WAVES_MIGRATED, false)) {
            int cur = prefs.getInt(KEY_LIVE_WALLPAPER_STYLE, ColgramLiveWallpaper.STYLE_AURORA);
            if (cur == ColgramLiveWallpaper.STYLE_OFF) {
                cur = ColgramLiveWallpaper.STYLE_AURORA;
            }
            prefs.edit()
                    .putInt(KEY_LIVE_WALLPAPER_STYLE, cur)
                    .putBoolean(KEY_LIVE_WALLPAPER_WAVES_MIGRATED, true)
                    .apply();
            return cur;
        }
        return prefs.getInt(KEY_LIVE_WALLPAPER_STYLE, ColgramLiveWallpaper.STYLE_AURORA);
    }

    public static void setLiveWallpaperStyle(int style) {
        if (prefs != null) {
            prefs.edit()
                    .putInt(KEY_LIVE_WALLPAPER_STYLE, Math.max(0, Math.min(4, style)))
                    .putBoolean(KEY_LIVE_WALLPAPER_WAVES_MIGRATED, true)
                    .apply();
        }
    }

    // --- Keyword alerts (built-in, was plugins/keyword_alerts.py) ---

    public static boolean isKeywordAlertsEnabled() {
        return prefs != null && prefs.getBoolean(KEY_KEYWORD_ALERTS, false);
    }

    public static void setKeywordAlertsEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_KEYWORD_ALERTS, enabled).apply();
    }

    /** Comma-separated keyword list; matching is case-insensitive substring. */
    public static String getKeywordAlertsList() {
        return prefs != null ? prefs.getString(KEY_KEYWORD_ALERTS_LIST, "") : "";
    }

    public static void setKeywordAlertsList(String keywords) {
        if (prefs != null) prefs.edit().putString(KEY_KEYWORD_ALERTS_LIST, keywords == null ? "" : keywords).apply();
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
        return prefs != null && prefs.getBoolean(KEY_MESSAGE_LOGGER, false);
    }

    public static void setMessageLoggerEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_MESSAGE_LOGGER, enabled).apply();
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
        return prefs != null && prefs.getBoolean(KEY_WARP_ENABLED, false);
    }

    public static void setWarpEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_WARP_ENABLED, enabled).apply();
    }

    public static String getBotTokenPrefill() {
        return prefs != null ? prefs.getString("bot_token_prefill", DEFAULT_BOT_TOKEN) : DEFAULT_BOT_TOKEN;
    }

    public static void setBotTokenPrefill(String token) {
        if (prefs != null) prefs.edit().putString("bot_token_prefill", token == null ? "" : token).apply();
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
        return prefs != null && prefs.getBoolean(KEY_AUTO_PROXY, false);
    }

    public static void setAutoProxyEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTO_PROXY, enabled).apply();
    }

    /** Enforce DoH-first resolution, remote SOCKS5 DNS, no plaintext HTTP over third-party proxies, and no WebRTC P2P IP leaks. */
    public static boolean isStrictProxySecurityEnabled() {
        return prefs == null || prefs.getBoolean(KEY_STRICT_PROXY_SECURITY, true);
    }

    public static void setStrictProxySecurityEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_STRICT_PROXY_SECURITY, enabled).apply();
    }

    public static String getBrowserRouteMode() {
        return prefs != null ? prefs.getString(KEY_BROWSER_ROUTE, "LOCAL_DPI") : "LOCAL_DPI";
    }

    public static void setBrowserRouteMode(String mode) {
        if (prefs != null) {
            prefs.edit().putString(KEY_BROWSER_ROUTE, mode == null || mode.trim().isEmpty() ? "LOCAL_DPI" : mode.trim()).apply();
        }
    }

    public static String getBrowserRouteSocks() {
        return prefs != null ? prefs.getString(KEY_BROWSER_ROUTE_SOCKS, "") : "";
    }

    public static void setBrowserRouteSocks(String socksIdentity) {
        if (prefs != null) {
            prefs.edit().putString(KEY_BROWSER_ROUTE_SOCKS, socksIdentity == null ? "" : socksIdentity.trim()).apply();
        }
    }

    public static boolean isMaxAnonymityEnabled() {
        return prefs != null && prefs.getBoolean(KEY_MAX_ANONYMITY, false);
    }

    /** Apply or clear the one-tap maximum anonymity preset across ghost mode, stealth, cloaking, and proxy hardening. */
    public static void setMaxAnonymityEnabled(boolean enabled) {
        if (prefs == null) return;
        SharedPreferences.Editor ed = prefs.edit().putBoolean(KEY_MAX_ANONYMITY, enabled);
        if (enabled) {
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
        return prefs == null || prefs.getBoolean(KEY_SAVE_ONETIME_MEDIA, true);
    }

    public static void setSaveOneTimeMediaEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_SAVE_ONETIME_MEDIA, enabled).apply();
    }

    public static boolean isHideScreenshotNotifyEnabled() {
        return prefs == null || prefs.getBoolean(KEY_HIDE_SCREENSHOT_NOTIFY, true);
    }

    public static void setHideScreenshotNotifyEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_HIDE_SCREENSHOT_NOTIFY, enabled).apply();
    }

    public static boolean isStealthStoriesEnabled() {
        return prefs == null || prefs.getBoolean(KEY_STEALTH_STORIES, true);
    }

    public static void setStealthStoriesEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_STEALTH_STORIES, enabled).apply();
    }

    public static boolean isForwardNoQuoteEnabled() {
        return prefs != null && prefs.getBoolean(KEY_FORWARD_NO_QUOTE, false);
    }

    public static void setForwardNoQuoteEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_FORWARD_NO_QUOTE, enabled).apply();
    }

    public static boolean isHidePhoneLocalEnabled() {
        return prefs != null && prefs.getBoolean(KEY_HIDE_PHONE_LOCAL, false);
    }

    public static void setHidePhoneLocalEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_HIDE_PHONE_LOCAL, enabled).apply();
    }

    public static boolean isAntiDeleteSelfEnabled() {
        return prefs == null || prefs.getBoolean(KEY_ANTI_DELETE_SELF, true);
    }

    public static void setAntiDeleteSelfEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_ANTI_DELETE_SELF, enabled).apply();
    }

    public static boolean isConfirmCallsEnabled() {
        return prefs != null && prefs.getBoolean(KEY_CONFIRM_CALLS, false);
    }

    public static void setConfirmCallsEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_CONFIRM_CALLS, enabled).apply();
    }

    public static boolean isConfirmStickersEnabled() {
        return prefs != null && prefs.getBoolean(KEY_CONFIRM_STICKERS, false);
    }

    public static void setConfirmStickersEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_CONFIRM_STICKERS, enabled).apply();
    }

    public static boolean isConfirmLinksEnabled() {
        return prefs != null && prefs.getBoolean(KEY_CONFIRM_LINKS, false);
    }

    public static void setConfirmLinksEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_CONFIRM_LINKS, enabled).apply();
    }

    /**
     * Rewrite FakeTLS ClientHellos into browser fingerprints on the local mimic front.
     * TSPU recognises tgnet's fixed hello byte-for-byte, which is how public MTProto proxies
     * die on Russian networks even with a live TCP route. The rewriting only changes what a
     * DPI sees - the proxy server's own handshake math is preserved.
     */
    public static boolean isTlsMimicEnabled() {
        return prefs == null || prefs.getBoolean(KEY_TLS_MIMIC, true);
    }

    public static void setTlsMimicEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_TLS_MIMIC, enabled).apply();
    }

    /**
     * Route Telegram over IPv6 only. Off by default: on a network whose IPv6 cannot reach
     * Telegram this breaks a connection IPv4 was carrying, so it is a choice, not a guess.
     * Where IPv6 does work it is the only bypass with no proxy and no third party in the path.
     */
    public static boolean isIpv6BypassEnabled() {
        return prefs != null && prefs.getBoolean(KEY_IPV6_BYPASS, false);
    }

    public static void setIpv6BypassEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_IPV6_BYPASS, enabled).apply();
    }

    /**
     * Dial whichever of Telegram's own DC addresses answers instead of the one tgnet hardcodes.
     *
     * Not a proxy: the traffic still goes straight to Telegram, only the address is chosen better,
     * and nothing in between can read it. Off by default because it works only where some DC
     * address is reachable at all - on a network that drops every one of them it changes nothing.
     */
    public static boolean isDcRemapEnabled() {
        return prefs != null && prefs.getBoolean(KEY_DC_REMAP, false);
    }

    public static void setDcRemapEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DC_REMAP, enabled).apply();
    }

    /**
     * Optional user-supplied HTTPS forwarder (a Cloudflare Worker URL). It carries the Bot API,
     * the mail providers and the proxy-list fetches on networks where those IPs are dropped and
     * no public SOCKS relay answers. Empty means "no relay configured".
     */
    public static String getRelayUrl() {
        return prefs != null ? prefs.getString(KEY_RELAY_URL, "") : "";
    }

    public static void setRelayUrl(String url) {
        if (prefs != null) {
            prefs.edit().putString(KEY_RELAY_URL, url == null ? "" : url.trim()).apply();
        }
    }

    /**
     * The address that last answered for a hostname. Persisted because a cold start should not
     * have to re-probe the whole address pool before it can reach the Bot API.
     */
    public static String getPinnedEndpoint(String host) {
        return prefs != null ? prefs.getString("pin_" + host, "") : "";
    }

    public static void setPinnedEndpoint(String host, String address) {
        if (prefs != null && host != null && address != null && !address.isEmpty()) {
            prefs.edit().putString("pin_" + host, address).apply();
        }
    }

    /** On by default: it only ever silences strangers who score as advertising. */
    public static boolean isSpamGuardEnabled() {
        return prefs == null || prefs.getBoolean(KEY_SPAM_GUARD, true);
    }

    public static void setSpamGuardEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_SPAM_GUARD, enabled).apply();
    }

    public static boolean isNotificationAdFilterEnabled() {
        return prefs != null && prefs.getBoolean(KEY_COLEMAILER_NOTIF_FILTER, false);
    }

    public static void setNotificationAdFilterEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_COLEMAILER_NOTIF_FILTER, enabled).apply();
    }

    public static boolean isSaveLeadsEnabled() {
        return prefs == null || prefs.getBoolean(KEY_COLEMAILER_SAVE_LEADS, true);
    }

    public static void setSaveLeadsEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_COLEMAILER_SAVE_LEADS, enabled).apply();
    }

    public static boolean isBroadcasterEnabled() {
        return prefs != null && prefs.getBoolean(KEY_COLEMAILER_BROADCASTER, false);
    }

    public static void setBroadcasterEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_COLEMAILER_BROADCASTER, enabled).apply();
    }

    public static boolean isAutoSubEnabled() {
        return prefs != null && prefs.getBoolean(KEY_COLEMAILER_AUTOSUB, false);
    }

    public static void setAutoSubEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_COLEMAILER_AUTOSUB, enabled).apply();
    }

    public static String getDefaultMuteMode() {
        return prefs != null ? prefs.getString(KEY_COLEMAILER_MUTE_MODE, "silent") : "silent";
    }

    public static void setDefaultMuteMode(String mode) {
        if (prefs == null) return;
        String clean = mode == null ? "silent" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!clean.equals("silent") && !clean.equals("delete") && !clean.equals("warn") && !clean.equals("ban")) {
            clean = "silent";
        }
        prefs.edit().putString(KEY_COLEMAILER_MUTE_MODE, clean).apply();
    }

    public static String getHelperBotToken() {
        return prefs != null ? prefs.getString(KEY_COLEMAILER_BOT_TOKEN, "") : "";
    }

    public static void setHelperBotToken(String token) {
        if (prefs != null) {
            prefs.edit().putString(KEY_COLEMAILER_BOT_TOKEN, token == null ? "" : token.trim()).apply();
        }
    }

    public static String getColemailerState() {
        return prefs != null ? prefs.getString(KEY_COLEMAILER_STATE, "") : "";
    }

    public static void setColemailerState(String serialized) {
        if (prefs != null) {
            prefs.edit().putString(KEY_COLEMAILER_STATE, serialized == null ? "" : serialized).apply();
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
        return prefs != null && prefs.contains(KEY_UNLIMITED_MEMORY);
    }

    public static boolean isUnlimitedMemoryEnabled() {
        return prefs == null || prefs.getBoolean(KEY_UNLIMITED_MEMORY, true);
    }

    public static void setUnlimitedMemoryEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_UNLIMITED_MEMORY, enabled).apply();
    }

    public static boolean isAutoCompressStorageEnabled() {
        return prefs == null || prefs.getBoolean("auto_compress_storage_enabled", true);
    }

    public static void setAutoCompressStorageEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean("auto_compress_storage_enabled", enabled).apply();
    }

    /** Per-chat (dialogId != 0) or global (dialogId == 0) local Video Wallpaper path (.mp4/.webm/.gif). */
    public static String getChatVideoWallpaper(long dialogId) {
        if (prefs == null) return "";
        String specific = dialogId != 0L ? prefs.getString("colgram_video_wp_" + dialogId, "") : "";
        if (specific != null && !specific.isEmpty()) return specific;
        return prefs.getString("colgram_video_wp_0", "");
    }

    public static String getExactChatVideoWallpaper(long dialogId) {
        return prefs != null ? prefs.getString("colgram_video_wp_" + dialogId, "") : "";
    }

    public static void setChatVideoWallpaper(long dialogId, String path) {
        if (prefs != null) {
            if (path == null || path.trim().isEmpty()) {
                prefs.edit().remove("colgram_video_wp_" + dialogId).apply();
            } else {
                prefs.edit().putString("colgram_video_wp_" + dialogId, path.trim()).apply();
            }
        }
    }

    /** Per-chat or global (dialogId == 0) local gallery image wallpaper path (persists across restarts on all accounts). */
    public static String getChatImageWallpaper(long dialogId) {
        if (prefs == null) return "";
        if (dialogId != 0L) {
            String exact = prefs.getString("colgram_img_wp_" + dialogId, "");
            if (exact != null && !exact.isEmpty()) return exact;
        }
        return prefs.getString("colgram_img_wp_0", "");
    }

    public static String getExactChatImageWallpaper(long dialogId) {
        return prefs != null ? prefs.getString("colgram_img_wp_" + dialogId, "") : "";
    }

    public static void setChatImageWallpaper(long dialogId, String path) {
        if (prefs != null) {
            if (path == null || path.trim().isEmpty()) {
                prefs.edit().remove("colgram_img_wp_" + dialogId).apply();
            } else {
                prefs.edit().putString("colgram_img_wp_" + dialogId, path.trim()).apply();
            }
        }
    }

    // --- Always-On Background & OTA Throttle ---
    private static final String KEY_ALWAYS_BACKGROUND = "always_background_enabled";
    private static final String KEY_LAST_UPDATE_CHECK_MS = "last_auto_update_check_ms";
    private static final String KEY_SHOW_MESSAGE_SECONDS = "show_message_seconds";
    private static final String KEY_SHOW_MESSAGE_ID = "show_message_id_menu";

    public static boolean isAlwaysBackgroundEnabled() {
        return prefs == null || prefs.getBoolean(KEY_ALWAYS_BACKGROUND, true);
    }

    public static void setAlwaysBackgroundEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_ALWAYS_BACKGROUND, enabled).apply();
    }

    public static long getLastAutoUpdateCheckMs() {
        return prefs != null ? prefs.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L) : 0L;
    }

    public static void setLastAutoUpdateCheckMs(long tsMs) {
        if (prefs != null) prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_MS, tsMs).apply();
    }

    public static boolean isShowMessageSecondsEnabled() {
        return prefs != null && prefs.getBoolean(KEY_SHOW_MESSAGE_SECONDS, false);
    }

    public static void setShowMessageSecondsEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_SHOW_MESSAGE_SECONDS, enabled).apply();
    }

    public static boolean isShowMessageIdEnabled() {
        return prefs == null || prefs.getBoolean(KEY_SHOW_MESSAGE_ID, true);
    }

    public static void setShowMessageIdEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_SHOW_MESSAGE_ID, enabled).apply();
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
        return prefs == null || prefs.getBoolean(KEY_DEFENDER_ENABLED, true);
    }

    public static void setDefenderEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DEFENDER_ENABLED, enabled).apply();
    }

    public static boolean isDefenderAutoCloseEnabled() {
        return prefs == null || prefs.getBoolean(KEY_DEFENDER_AUTO_CLOSE, true);
    }

    public static void setDefenderAutoCloseEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DEFENDER_AUTO_CLOSE, enabled).apply();
    }

    public static boolean isDefenderBlockVoipP2pEnabled() {
        return prefs == null || prefs.getBoolean(KEY_DEFENDER_BLOCK_VOIP_P2P, true);
    }

    public static void setDefenderBlockVoipP2pEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DEFENDER_BLOCK_VOIP_P2P, enabled).apply();
    }

    public static boolean isDefenderBlockBotLocationEnabled() {
        return prefs == null || prefs.getBoolean(KEY_DEFENDER_BLOCK_BOT_LOCATION, true);
    }

    public static void setDefenderBlockBotLocationEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DEFENDER_BLOCK_BOT_LOCATION, enabled).apply();
    }

    public static boolean isDefenderStripTrackersEnabled() {
        return prefs == null || prefs.getBoolean(KEY_DEFENDER_STRIP_TRACKERS, true);
    }

    public static void setDefenderStripTrackersEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DEFENDER_STRIP_TRACKERS, enabled).apply();
    }

    public static boolean isDefenderWebViewHardeningEnabled() {
        return prefs == null || prefs.getBoolean(KEY_DEFENDER_WEBVIEW_HARDENING, true);
    }

    public static void setDefenderWebViewHardeningEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DEFENDER_WEBVIEW_HARDENING, enabled).apply();
    }

    public static long getDefenderLastScanMs() {
        return prefs != null ? prefs.getLong(KEY_DEFENDER_LAST_SCAN_MS, 0L) : 0L;
    }

    public static void setDefenderLastScanMs(long tsMs) {
        if (prefs != null) prefs.edit().putLong(KEY_DEFENDER_LAST_SCAN_MS, tsMs).apply();
    }

    public static int getDefenderFixedTotal() {
        return prefs != null ? prefs.getInt(KEY_DEFENDER_FIXED_TOTAL, 0) : 0;
    }

    public static void addDefenderFixedCount(int delta) {
        if (prefs != null && delta > 0) {
            prefs.edit().putInt(KEY_DEFENDER_FIXED_TOTAL, getDefenderFixedTotal() + delta).apply();
        }
    }

    public static int getDefenderTrackersStripped() {
        return prefs != null ? prefs.getInt(KEY_DEFENDER_TRACKERS_STRIPPED, 0) : 0;
    }

    public static void addDefenderTrackersStripped(int delta) {
        if (prefs != null && delta > 0) {
            prefs.edit().putInt(KEY_DEFENDER_TRACKERS_STRIPPED, getDefenderTrackersStripped() + delta).apply();
        }
    }

    public static int getDefenderP2pBlocked() {
        return prefs != null ? prefs.getInt(KEY_DEFENDER_P2P_BLOCKED, 0) : 0;
    }

    public static void incrementDefenderP2pBlocked() {
        if (prefs != null) {
            prefs.edit().putInt(KEY_DEFENDER_P2P_BLOCKED, getDefenderP2pBlocked() + 1).apply();
        }
    }

    // --- Quick Access Panel & 1-Tap Ghost Mode ---
    private static final String KEY_QUICK_BAR_ENABLED = "quick_bar_enabled";
    private static final String KEY_QUICK_BAR_ITEMS = "quick_bar_items";
    public static final String DEFAULT_QUICK_BAR_ITEMS = "ghost,proxy,koliti,saved,theme";

    public static boolean isQuickBarEnabled() {
        return prefs == null || prefs.getBoolean(KEY_QUICK_BAR_ENABLED, true);
    }

    public static void setQuickBarEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_QUICK_BAR_ENABLED, enabled).apply();
    }

    public static java.util.List<String> getQuickBarItems() {
        String raw = prefs != null ? prefs.getString(KEY_QUICK_BAR_ITEMS, DEFAULT_QUICK_BAR_ITEMS) : DEFAULT_QUICK_BAR_ITEMS;
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String part : raw.split(",")) {
                String clean = part.trim();
                if (!clean.isEmpty() && !list.contains(clean)) {
                    list.add(clean);
                }
            }
        }
        return list;
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
        prefs.edit().putString(KEY_QUICK_BAR_ITEMS, sb.toString()).apply();
    }

    public static boolean isQuickGhostActive() {
        return isGhostOnlineEnabled() && isGhostReadEnabled();
    }

    public static void setQuickGhostMode(boolean enabled) {
        if (prefs == null) return;
        prefs.edit()
                .putBoolean(KEY_GHOST_READ, enabled)
                .putBoolean(KEY_GHOST_ONLINE, enabled)
                .putBoolean(KEY_GHOST_TYPING, enabled)
                .putBoolean(KEY_STEALTH_STORIES, enabled)
                .apply();
    }
}
