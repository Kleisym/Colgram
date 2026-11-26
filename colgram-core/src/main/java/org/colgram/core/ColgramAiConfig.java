package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent preferences for the Colgram on-device & hybrid AI assistant.
 */
public final class ColgramAiConfig {

    private static final String PREFS = "colgram_ai_prefs";
    private static final String KEY_ORB_ENABLED = "orb_enabled";
    private static final String KEY_ORB_OFFSET_Y_DP = "orb_offset_y_dp";
    private static final String KEY_MODEL_ID = "active_model_id";
    private static final String KEY_CUSTOM_MODEL_PATH = "custom_model_path";
    private static final String KEY_CUSTOM_MMPROJ_PATH = "custom_mmproj_path";
    private static final String KEY_WEB_SEARCH = "web_search_enabled";
    private static final String KEY_CROSS_CHAT_RAG = "cross_chat_rag_enabled";
    private static final String KEY_ENABLE_THINKING = "enable_thinking";
    private static final String KEY_KV_QUANT_BITS = "kv_quant_bits";
    private static final String KEY_CTX_OVERRIDE = "ctx_override";
    private static final String KEY_THREADS_OVERRIDE = "threads_override";
    private static final String KEY_FLASH_ATTN = "flash_attn";
    private static final String KEY_IDLE_UNLOAD_SEC = "idle_unload_sec";
    private static final String KEY_IDLE_UNLOAD_MIGRATED_V2 = "idle_unload_migrated_v2";
    private static final String KEY_IDLE_UNLOAD_MIGRATED_V3 = "idle_unload_migrated_v3";
    private static final String KEY_IDLE_UNLOAD_MIGRATED_V4 = "idle_unload_migrated_v4";
    private static final String KEY_TEMPERATURE_PCT = "temperature_pct";
    private static final String KEY_KV_MIGRATED_Q8 = "kv_migrated_q8_v1";
    private static final String KEY_VOICE_OVER_ENABLED = "voice_over_enabled";
    private static final String KEY_VOICE_CLONE_ENDPOINT = "voice_clone_endpoint";
    private static final String KEY_VOICE_CLONE_API_KEY = "voice_clone_api_key";
    private static final String KEY_VOICE_CLONE_VOICE_ID = "voice_clone_voice_id";
    private static final String KEY_VOICE_REFERENCE_PATH = "voice_reference_path";
    private static final String KEY_VOICE_SPEECH_RATE = "voice_speech_rate";
    private static final String KEY_VOICE_PITCH = "voice_pitch";
    private static final String KEY_EMBEDDED_CORE_REV = "embedded_core_rev";
    private static final String DEFAULT_MODEL_ID = "koliti-core-ultra";

    private ColgramAiConfig() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isOrbEnabled(Context ctx) {
        return ctx == null || prefs(ctx).getBoolean(KEY_ORB_ENABLED, true);
    }

    public static void setOrbEnabled(Context ctx, boolean enabled) {
        if (ctx != null) prefs(ctx).edit().putBoolean(KEY_ORB_ENABLED, enabled).apply();
    }

    public static float getOrbExtraOffsetDp(Context ctx) {
        return ctx == null ? 0f : prefs(ctx).getFloat(KEY_ORB_OFFSET_Y_DP, 0f);
    }

    public static void setOrbExtraOffsetDp(Context ctx, float dpOffset) {
        if (ctx != null) prefs(ctx).edit().putFloat(KEY_ORB_OFFSET_Y_DP, dpOffset).apply();
    }

    public static String getActiveModelId(Context ctx) {
        if (ctx == null) return DEFAULT_MODEL_ID;
        String saved = prefs(ctx).getString(KEY_MODEL_ID, DEFAULT_MODEL_ID);
        if (!ColgramBuildFlags.KOLITI_ENABLED) {
            return saved != null && !saved.isEmpty() ? saved : DEFAULT_MODEL_ID;
        }
        try {
            Object spec = Class.forName("org.colgram.core.ColgramAiModels")
                    .getMethod("findById", String.class)
                    .invoke(null, saved);
            if (spec != null) {
                Object id = spec.getClass().getField("id").get(spec);
                if (id instanceof String && !((String) id).isEmpty()) {
                    return (String) id;
                }
            }
        } catch (Throwable ignored) {}
        return saved != null && !saved.isEmpty() ? saved : DEFAULT_MODEL_ID;
    }

    public static void setActiveModelId(Context ctx, String modelId) {
        if (ctx != null && modelId != null) {
            prefs(ctx).edit().putString(KEY_MODEL_ID, modelId).apply();
        }
    }

    public static String getCustomModelPath(Context ctx) {
        return ctx == null ? "" : prefs(ctx).getString(KEY_CUSTOM_MODEL_PATH, "");
    }

    public static void setCustomModelPath(Context ctx, String path) {
        if (ctx != null) {
            prefs(ctx).edit().putString(KEY_CUSTOM_MODEL_PATH, path == null ? "" : path).apply();
        }
    }

    public static String getCustomMmprojPath(Context ctx) {
        return ctx == null ? "" : prefs(ctx).getString(KEY_CUSTOM_MMPROJ_PATH, "");
    }

    public static void setCustomMmprojPath(Context ctx, String path) {
        if (ctx != null) {
            prefs(ctx).edit().putString(KEY_CUSTOM_MMPROJ_PATH, path == null ? "" : path).apply();
        }
    }

    public static boolean isWebSearchEnabled(Context ctx) {
        return ctx == null || prefs(ctx).getBoolean(KEY_WEB_SEARCH, true);
    }

    public static void setWebSearchEnabled(Context ctx, boolean enabled) {
        if (ctx != null) prefs(ctx).edit().putBoolean(KEY_WEB_SEARCH, enabled).apply();
    }

    public static boolean isCrossChatRagEnabled(Context ctx) {
        return ctx == null || prefs(ctx).getBoolean(KEY_CROSS_CHAT_RAG, true);
    }

    public static void setCrossChatRagEnabled(Context ctx, boolean enabled) {
        if (ctx != null) prefs(ctx).edit().putBoolean(KEY_CROSS_CHAT_RAG, enabled).apply();
    }

    public static boolean isThinkingEnabled(Context ctx) {
        return ctx != null && prefs(ctx).getBoolean(KEY_ENABLE_THINKING, false);
    }

    public static void setThinkingEnabled(Context ctx, boolean enabled) {
        if (ctx != null) prefs(ctx).edit().putBoolean(KEY_ENABLE_THINKING, enabled).apply();
    }

    public static int getKvQuantBits(Context ctx) {
        if (ctx == null) return 8;
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean(KEY_KV_MIGRATED_Q8, false)) {
            int cur = p.getInt(KEY_KV_QUANT_BITS, 8);
            if (cur == 4) {
                p.edit().putInt(KEY_KV_QUANT_BITS, 8).putBoolean(KEY_KV_MIGRATED_Q8, true).apply();
                return 8;
            }
            p.edit().putBoolean(KEY_KV_MIGRATED_Q8, true).apply();
        }
        int v = p.getInt(KEY_KV_QUANT_BITS, 8);
        return (v == 4 || v == 8 || v == 16) ? v : 8;
    }

    public static void setKvQuantBits(Context ctx, int bits) {
        if (ctx != null) prefs(ctx).edit().putInt(KEY_KV_QUANT_BITS, bits).putBoolean(KEY_KV_MIGRATED_Q8, true).apply();
    }

    /** 0 = auto scaled to device free RAM (4096..32768). */
    public static int getContextSizeOverride(Context ctx) {
        return ctx == null ? 0 : prefs(ctx).getInt(KEY_CTX_OVERRIDE, 0);
    }

    public static void setContextSizeOverride(Context ctx, int nCtx) {
        if (ctx != null) prefs(ctx).edit().putInt(KEY_CTX_OVERRIDE, Math.max(0, nCtx)).apply();
    }

    /** 0 = auto thermal-safe core allocation. */
    public static int getThreadsOverride(Context ctx) {
        return ctx == null ? 0 : prefs(ctx).getInt(KEY_THREADS_OVERRIDE, 0);
    }

    public static void setThreadsOverride(Context ctx, int nThreads) {
        if (ctx != null) prefs(ctx).edit().putInt(KEY_THREADS_OVERRIDE, Math.max(0, nThreads)).apply();
    }

    public static boolean isFlashAttentionEnabled(Context ctx) {
        return ctx == null || prefs(ctx).getBoolean(KEY_FLASH_ATTN, true);
    }

    public static void setFlashAttentionEnabled(Context ctx, boolean enabled) {
        if (ctx != null) prefs(ctx).edit().putBoolean(KEY_FLASH_ATTN, enabled).apply();
    }

    public static int getIdleUnloadSeconds(Context ctx) {
        if (ctx == null) return 600;
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean(KEY_IDLE_UNLOAD_MIGRATED_V4, false)) {
            int cur = p.getInt(KEY_IDLE_UNLOAD_SEC, 600);
            if (cur == 60 || cur == 900 || cur <= 0) {
                p.edit().putInt(KEY_IDLE_UNLOAD_SEC, 600).putBoolean(KEY_IDLE_UNLOAD_MIGRATED_V4, true).apply();
                return 600;
            }
            p.edit().putBoolean(KEY_IDLE_UNLOAD_MIGRATED_V4, true).apply();
        }
        return p.getInt(KEY_IDLE_UNLOAD_SEC, 600);
    }

    public static void setIdleUnloadSeconds(Context ctx, int seconds) {
        if (ctx != null) prefs(ctx).edit().putInt(KEY_IDLE_UNLOAD_SEC, Math.max(0, seconds))
                .putBoolean(KEY_IDLE_UNLOAD_MIGRATED_V4, true).apply();
    }

    public static float getTemperature(Context ctx) {
        int pct = ctx == null ? 65 : prefs(ctx).getInt(KEY_TEMPERATURE_PCT, 65);
        return Math.max(0.10f, Math.min(0.95f, pct / 100.0f));
    }

    public static void setTemperaturePct(Context ctx, int pct) {
        if (ctx != null) prefs(ctx).edit().putInt(KEY_TEMPERATURE_PCT, Math.max(0, Math.min(100, pct))).apply();
    }

    public static boolean isVoiceOverEnabled(Context ctx) {
        return ctx != null && prefs(ctx).getBoolean(KEY_VOICE_OVER_ENABLED, false);
    }

    public static void setVoiceOverEnabled(Context ctx, boolean enabled) {
        if (ctx != null) prefs(ctx).edit().putBoolean(KEY_VOICE_OVER_ENABLED, enabled).apply();
    }

    public static String getVoiceCloneEndpoint(Context ctx) {
        return ctx == null ? "" : prefs(ctx).getString(KEY_VOICE_CLONE_ENDPOINT, "");
    }

    public static void setVoiceCloneEndpoint(Context ctx, String url) {
        if (ctx != null) prefs(ctx).edit().putString(KEY_VOICE_CLONE_ENDPOINT, url == null ? "" : url.trim()).apply();
    }

    public static String getVoiceCloneApiKey(Context ctx) {
        return ctx == null ? "" : prefs(ctx).getString(KEY_VOICE_CLONE_API_KEY, "");
    }

    public static void setVoiceCloneApiKey(Context ctx, String apiKey) {
        if (ctx != null) prefs(ctx).edit().putString(KEY_VOICE_CLONE_API_KEY, apiKey == null ? "" : apiKey.trim()).apply();
    }

    public static String getVoiceCloneVoiceId(Context ctx) {
        return ctx == null ? "" : prefs(ctx).getString(KEY_VOICE_CLONE_VOICE_ID, "");
    }

    public static void setVoiceCloneVoiceId(Context ctx, String voiceId) {
        if (ctx != null) prefs(ctx).edit().putString(KEY_VOICE_CLONE_VOICE_ID, voiceId == null ? "" : voiceId.trim()).apply();
    }

    public static String getVoiceReferencePath(Context ctx) {
        return ctx == null ? "" : prefs(ctx).getString(KEY_VOICE_REFERENCE_PATH, "");
    }

    public static void setVoiceReferencePath(Context ctx, String path) {
        if (ctx != null) prefs(ctx).edit().putString(KEY_VOICE_REFERENCE_PATH, path == null ? "" : path.trim()).apply();
    }

    public static float getVoiceSpeechRate(Context ctx) {
        float v = ctx == null ? 1.0f : prefs(ctx).getFloat(KEY_VOICE_SPEECH_RATE, 1.0f);
        return Math.max(0.6f, Math.min(1.6f, v));
    }

    public static void setVoiceSpeechRate(Context ctx, float rate) {
        if (ctx != null) prefs(ctx).edit().putFloat(KEY_VOICE_SPEECH_RATE, Math.max(0.6f, Math.min(1.6f, rate))).apply();
    }

    public static float getVoicePitch(Context ctx) {
        float v = ctx == null ? 1.0f : prefs(ctx).getFloat(KEY_VOICE_PITCH, 1.0f);
        return Math.max(0.7f, Math.min(1.4f, v));
    }

    public static void setVoicePitch(Context ctx, float pitch) {
        if (ctx != null) prefs(ctx).edit().putFloat(KEY_VOICE_PITCH, Math.max(0.7f, Math.min(1.4f, pitch))).apply();
    }

    public static boolean hasVoiceCloneServer(Context ctx) {
        if (ctx == null) return false;
        return !getVoiceCloneEndpoint(ctx).isEmpty() || !getVoiceCloneApiKey(ctx).isEmpty();
    }

    public static int getEmbeddedCoreRevision(Context ctx) {
        return ctx == null ? 0 : prefs(ctx).getInt(KEY_EMBEDDED_CORE_REV, 0);
    }

    public static void setEmbeddedCoreRevision(Context ctx, int rev) {
        if (ctx != null) prefs(ctx).edit().putInt(KEY_EMBEDDED_CORE_REV, rev).apply();
    }
}
