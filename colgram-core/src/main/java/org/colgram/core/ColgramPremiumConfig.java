package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configuration store for real client-side Telegram Premium+ capabilities (Contract C).
 * Volatile defaults remain safe prior to init() and in pure-JVM unit tests.
 */
public final class ColgramPremiumConfig {

    private static final String PREFS_NAME = "colgram_premium_config";

    private static final String KEY_BLOCK_SPONSORED_ADS = "block_sponsored_ads";
    private static final String KEY_LOCAL_VOICE_TRANSCRIBE = "local_voice_transcribe";
    private static final String KEY_LOCAL_VOICE_TRANSCRIBE_FIRST = "local_voice_transcribe_first";
    private static final String KEY_LOCAL_TRANSLATOR = "local_translator";
    private static final String KEY_BYPASS_NO_FORWARDS = "bypass_noforwards";
    private static final String KEY_SAVED_TAGS = "saved_tags";
    private static final String KEY_HIDE_STORIES = "hide_stories";
    private static final String KEY_AUTO_HIDE_ARCHIVE = "auto_hide_archive";
    private static final String KEY_DEFAULT_START_FOLDER_ID = "default_start_folder_id";
    private static final String KEY_LOCAL_DOUBLE_TAP_REACTION = "local_double_tap_reaction";

    private static volatile SharedPreferences prefs;
    private static volatile Object prefListener;

    private static volatile boolean blockSponsoredAdsEnabled = true;
    private static volatile boolean localVoiceTranscribeEnabled = true;
    private static volatile boolean localVoiceTranscribeLocalFirst = false;
    private static volatile boolean localTranslatorEnabled = true;
    private static volatile boolean bypassNoForwardsEnabled = true;
    private static volatile boolean savedTagsEnabled = true;
    private static volatile boolean hideStoriesEnabled = false;
    private static volatile boolean autoHideArchiveEnabled = false;
    private static volatile int defaultStartFolderId = 0;
    private static volatile String localDoubleTapReaction = "";

    private ColgramPremiumConfig() {}

    public static void init(Context ctx) {
        if (ctx == null) {
            return;
        }
        synchronized (ColgramPremiumConfig.class) {
            if (prefs == null) {
                Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                SharedPreferences sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs = sp;
                loadAll(sp);
                if (prefListener == null) {
                    prefListener = (SharedPreferences.OnSharedPreferenceChangeListener) (sharedPreferences, key) -> {
                        if (sharedPreferences != null) {
                            loadAll(sharedPreferences);
                        }
                    };
                }
                sp.registerOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener) prefListener);
            }
        }
    }

    private static void loadAll(SharedPreferences sp) {
        if (sp == null) return;
        blockSponsoredAdsEnabled = sp.getBoolean(KEY_BLOCK_SPONSORED_ADS, true);
        localVoiceTranscribeEnabled = sp.getBoolean(KEY_LOCAL_VOICE_TRANSCRIBE, true);
        localVoiceTranscribeLocalFirst = sp.getBoolean(KEY_LOCAL_VOICE_TRANSCRIBE_FIRST, false);
        localTranslatorEnabled = sp.getBoolean(KEY_LOCAL_TRANSLATOR, true);
        bypassNoForwardsEnabled = sp.getBoolean(KEY_BYPASS_NO_FORWARDS, true);
        savedTagsEnabled = sp.getBoolean(KEY_SAVED_TAGS, true);
        hideStoriesEnabled = sp.getBoolean(KEY_HIDE_STORIES, false);
        autoHideArchiveEnabled = sp.getBoolean(KEY_AUTO_HIDE_ARCHIVE, false);
        defaultStartFolderId = sp.getInt(KEY_DEFAULT_START_FOLDER_ID, 0);
        String rx = sp.getString(KEY_LOCAL_DOUBLE_TAP_REACTION, "");
        localDoubleTapReaction = rx == null ? "" : rx.trim();
    }

    public static boolean isBlockSponsoredAdsEnabled() {
        return blockSponsoredAdsEnabled;
    }

    public static void setBlockSponsoredAdsEnabled(boolean v) {
        blockSponsoredAdsEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putBoolean(KEY_BLOCK_SPONSORED_ADS, v).apply();
    }

    public static boolean isLocalVoiceTranscribeEnabled() {
        return localVoiceTranscribeEnabled;
    }

    public static void setLocalVoiceTranscribeEnabled(boolean v) {
        localVoiceTranscribeEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putBoolean(KEY_LOCAL_VOICE_TRANSCRIBE, v).apply();
    }

    public static boolean isLocalVoiceTranscribeLocalFirst() {
        return localVoiceTranscribeLocalFirst;
    }

    public static void setLocalVoiceTranscribeLocalFirst(boolean v) {
        localVoiceTranscribeLocalFirst = v;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putBoolean(KEY_LOCAL_VOICE_TRANSCRIBE_FIRST, v).apply();
    }

    public static boolean isLocalTranslatorEnabled() {
        return localTranslatorEnabled;
    }

    public static void setLocalTranslatorEnabled(boolean v) {
        localTranslatorEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putBoolean(KEY_LOCAL_TRANSLATOR, v).apply();
    }

    public static boolean isBypassNoForwardsEnabled() {
        return bypassNoForwardsEnabled;
    }

    public static void setBypassNoForwardsEnabled(boolean v) {
        bypassNoForwardsEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putBoolean(KEY_BYPASS_NO_FORWARDS, v).apply();
    }

    public static boolean isSavedTagsEnabled() {
        return savedTagsEnabled;
    }

    public static void setSavedTagsEnabled(boolean v) {
        savedTagsEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putBoolean(KEY_SAVED_TAGS, v).apply();
    }

    public static boolean isHideStoriesEnabled() {
        return hideStoriesEnabled;
    }

    public static void setHideStoriesEnabled(boolean v) {
        hideStoriesEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putBoolean(KEY_HIDE_STORIES, v).apply();
    }

    public static boolean isAutoHideArchiveEnabled() {
        return autoHideArchiveEnabled;
    }

    public static void setAutoHideArchiveEnabled(boolean v) {
        autoHideArchiveEnabled = v;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putBoolean(KEY_AUTO_HIDE_ARCHIVE, v).apply();
    }

    public static int getDefaultStartFolderId() {
        return defaultStartFolderId;
    }

    public static void setDefaultStartFolderId(int folderId) {
        int safe = Math.max(0, folderId);
        defaultStartFolderId = safe;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putInt(KEY_DEFAULT_START_FOLDER_ID, safe).apply();
    }

    public static String getLocalDoubleTapReaction() {
        return localDoubleTapReaction;
    }

    public static void setLocalDoubleTapReaction(String emoji) {
        String norm = emoji == null ? "" : emoji.trim();
        localDoubleTapReaction = norm;
        SharedPreferences sp = prefs;
        if (sp != null) sp.edit().putString(KEY_LOCAL_DOUBLE_TAP_REACTION, norm).apply();
    }

    public static void resetForTests() {
        prefs = null;
        prefListener = null;
        blockSponsoredAdsEnabled = true;
        localVoiceTranscribeEnabled = true;
        localVoiceTranscribeLocalFirst = false;
        localTranslatorEnabled = true;
        bypassNoForwardsEnabled = true;
        savedTagsEnabled = true;
        hideStoriesEnabled = false;
        autoHideArchiveEnabled = false;
        defaultStartFolderId = 0;
        localDoubleTapReaction = "";
    }
}
