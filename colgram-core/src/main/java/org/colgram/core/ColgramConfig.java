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
        return prefs == null || prefs.getBoolean(KEY_ANTI_DELETE_WIPE, true);
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
    /**
     * Whether the in-process desync listener should run and carry Telegram's traffic.
     *
     * Defaults to OFF. It used to be effectively always-on, because a fresh install forced
     * proxy_enabled=true and pinned the app to this listener. Measured against the real block
     * that is the wrong default: the drop there is at IP level, so no amount of payload
     * desync makes a dead route answer, and on an unblocked network the loopback hop is pure
     * cost and an extra way to end up stuck on a port that never bound.
     *
     * The setting never existed at all before, so the toggle only started or stopped the
     * listener for the current process and silently reverted on the next launch.
     */
    private static final String KEY_DPI_BYPASS_ENABLED = "dpi_bypass_enabled";

    public static boolean isDpiBypassEnabled() {
        return prefs != null && prefs.getBoolean(KEY_DPI_BYPASS_ENABLED, false);
    }

    public static void setDpiBypassEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_DPI_BYPASS_ENABLED, enabled).apply();
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

    // --- Auto-reply ---
    private static final String KEY_AUTO_REPLY_ENABLED = "auto_reply_enabled";
    private static final String KEY_AUTO_REPLY_TEXT = "auto_reply_text";
    private static final String KEY_AUTO_REPLY_GROUPS = "auto_reply_groups";
    private static final String KEY_AUTO_REPLY_COOLDOWN = "auto_reply_cooldown_sec";
    public static final String DEFAULT_AUTO_REPLY_TEXT =
            "Я сейчас не у экрана, отвечу позже. (автоответ Colgram)";

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

    // --- Unlimited memory ---
    private static final String KEY_UNLIMITED_MEMORY = "unlimited_memory_enabled";

    // --- Built-in spam guard ---
    private static final String KEY_SPAM_GUARD = "spam_guard_enabled";

    // --- Fetch relay ---
    private static final String KEY_RELAY_URL = "relay_url";

    // --- Auto proxy ---
    private static final String KEY_AUTO_PROXY = "auto_proxy_enabled";
    private static final String KEY_IPV6_BYPASS = "ipv6_bypass_enabled";
    private static final String KEY_DC_REMAP = "dc_remap_enabled";

    /**
     * Whether Colgram may switch a proxy on by itself when Telegram is unreachable.
     *
     * Off, and it stays off until he says otherwise. He asked for this behaviour to be removed
     * after a build started dialing a public node the moment the direct route hung: from the phone
     * that is not a bypass, it is the app quietly putting him back on a proxy he turned off. The
     * blocked state is reported instead, as a notification he can act on.
     */
    public static boolean isAutoProxyEnabled() {
        return prefs != null && prefs.getBoolean(KEY_AUTO_PROXY, false);
    }

    public static void setAutoProxyEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTO_PROXY, enabled).apply();
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

    /**
     * "Unlimited memory" means the media cache is never auto-evicted: Telegram's own
     * AutoDeleteMediaTask reads the cache_limit preference and defaults it to 300 MB, which is
     * what makes old videos and photos silently disappear. With this on the limit is raised to
     * the integer maximum, so everything already viewed stays viewable, and it lives in the app's
     * own storage instead of the gallery, which is the "not cluttering the phone" half.
     */
    public static boolean isUnlimitedMemoryEnabled() {
        return prefs == null || prefs.getBoolean(KEY_UNLIMITED_MEMORY, true);
    }

    public static void setUnlimitedMemoryEnabled(boolean enabled) {
        if (prefs != null) prefs.edit().putBoolean(KEY_UNLIMITED_MEMORY, enabled).apply();
    }
}
