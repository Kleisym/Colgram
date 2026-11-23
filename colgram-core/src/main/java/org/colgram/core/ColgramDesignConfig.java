package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * ColgramDesignConfig — Full UI & Layout Designer persistence store.
 * Controls global accent color, surface palette, chat card geometry, neon intensity,
 * header/quick-bar/navigation layout placement, Koliti orb size/opacity/dock/custom avatar & name,
 * and supports full JSON export/import so users can share or back up their custom Colgram builds.
 */
public final class ColgramDesignConfig {

    private static final String PREFS_NAME = "colgram_design_studio";

    public static final int SURFACE_AUTO = 0;
    public static final int SURFACE_OLED_BLACK = 1;
    public static final int SURFACE_GRAPHITE = 2;
    public static final int SURFACE_MIDNIGHT = 3;
    public static final int SURFACE_CRIMSON_NOIR = 4;
    public static final int SURFACE_PORCELAIN = 5;
    public static final int SURFACE_CUSTOM_HEX = 6;

    public static final int CARD_STYLE_CYBER_GLASS = 0;
    public static final int CARD_STYLE_NEON_BORDER = 1;
    public static final int CARD_STYLE_CLASSIC_FLAT = 2;
    public static final int CARD_STYLE_SOFT_PILL = 3;

    public static final int QUICK_BAR_UNDER_SEARCH = 0;
    public static final int QUICK_BAR_ACTION_BUTTON = 1;
    public static final int QUICK_BAR_HIDDEN = 2;

    public static final int DOCK_LEFT = 0;
    public static final int DOCK_RIGHT = 1;
    public static final int DOCK_REMEMBER = 2;

    public static final int DENSITY_COMPACT = 0;
    public static final int DENSITY_NORMAL = 1;
    public static final int DENSITY_COMFORT = 2;

    public static final int MIN_CORNER_DP = 4;
    public static final int MAX_CORNER_DP = 24;
    public static final int DEFAULT_CORNER_DP = 14;

    public static final int MIN_ORB_SIZE_DP = 38;
    public static final int MAX_ORB_SIZE_DP = 76;
    public static final int DEFAULT_ORB_SIZE_DP = 56;

    public static final int MIN_ORB_ALPHA_PCT = 45;
    public static final int MAX_ORB_ALPHA_PCT = 100;

    private static final String KEY_ACCENT_COLOR = "accent_color";
    private static final String KEY_SURFACE_PRESET = "surface_preset";
    private static final String KEY_CUSTOM_SURFACE_COLOR = "custom_surface_color";
    private static final String KEY_CARD_STYLE = "card_style";
    private static final String KEY_CORNER_RADIUS_DP = "corner_radius_dp";
    private static final String KEY_NEON_INTENSITY = "neon_intensity";
    private static final String KEY_CHAT_DENSITY = "chat_density";
    private static final String KEY_QUICK_BAR_PLACEMENT = "quick_bar_placement";
    private static final String KEY_SHOW_HEADER_SHIELD = "show_header_shield";
    private static final String KEY_CUSTOM_HEADER_TITLE = "custom_header_title";
    private static final String KEY_KOLITI_ORB_SIZE_DP = "koliti_orb_size_dp";
    private static final String KEY_KOLITI_ORB_ALPHA_PCT = "koliti_orb_alpha_pct";
    private static final String KEY_KOLITI_ORB_DOCK = "koliti_orb_dock";
    private static final String KEY_KOLITI_AVATAR_PATH = "koliti_avatar_path";
    private static final String KEY_KOLITI_CUSTOM_NAME = "koliti_custom_name";
    private static final String KEY_HIDE_DIVIDERS = "hide_dividers";

    private static volatile SharedPreferences prefs;

    // In-memory mirror (also allows fast zero-I/O reads during 60fps draw and JVM unit tests)
    private static volatile int accentColor = 0;
    private static volatile int surfacePreset = SURFACE_AUTO;
    private static volatile int customSurfaceColor = 0xFF0E111A;
    private static volatile int cardStyle = CARD_STYLE_CYBER_GLASS;
    private static volatile int cornerRadiusDp = DEFAULT_CORNER_DP;
    private static volatile int neonIntensity = 2; // 0=Off, 1=Soft, 2=Standard, 3=Max
    private static volatile int chatDensity = DENSITY_NORMAL;
    private static volatile int quickBarPlacement = QUICK_BAR_UNDER_SEARCH;
    private static volatile boolean showHeaderShield = true;
    private static volatile String customHeaderTitle = "";
    private static volatile int kolitiOrbSizeDp = DEFAULT_ORB_SIZE_DP;
    private static volatile int kolitiOrbAlphaPct = 100;
    private static volatile int kolitiOrbDock = DOCK_LEFT;
    private static volatile String kolitiAvatarPath = "";
    private static volatile String kolitiCustomName = "";
    private static volatile boolean hideDividers = false;
    private static volatile long revision = 1L;

    private ColgramDesignConfig() {}

    public static synchronized void init(Context context) {
        if (context == null || prefs != null) return;
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs = p;
        accentColor = p.getInt(KEY_ACCENT_COLOR, 0);
        surfacePreset = clamp(p.getInt(KEY_SURFACE_PRESET, SURFACE_AUTO), SURFACE_AUTO, SURFACE_CUSTOM_HEX);
        customSurfaceColor = p.getInt(KEY_CUSTOM_SURFACE_COLOR, 0xFF0E111A);
        cardStyle = clamp(p.getInt(KEY_CARD_STYLE, CARD_STYLE_CYBER_GLASS), CARD_STYLE_CYBER_GLASS, CARD_STYLE_SOFT_PILL);
        cornerRadiusDp = clamp(p.getInt(KEY_CORNER_RADIUS_DP, DEFAULT_CORNER_DP), MIN_CORNER_DP, MAX_CORNER_DP);
        neonIntensity = clamp(p.getInt(KEY_NEON_INTENSITY, 2), 0, 3);
        chatDensity = clamp(p.getInt(KEY_CHAT_DENSITY, DENSITY_NORMAL), DENSITY_COMPACT, DENSITY_COMFORT);
        quickBarPlacement = clamp(p.getInt(KEY_QUICK_BAR_PLACEMENT, QUICK_BAR_UNDER_SEARCH), QUICK_BAR_UNDER_SEARCH, QUICK_BAR_HIDDEN);
        showHeaderShield = p.getBoolean(KEY_SHOW_HEADER_SHIELD, true);
        customHeaderTitle = safeTrim(p.getString(KEY_CUSTOM_HEADER_TITLE, ""), 28);
        kolitiOrbSizeDp = clamp(p.getInt(KEY_KOLITI_ORB_SIZE_DP, DEFAULT_ORB_SIZE_DP), MIN_ORB_SIZE_DP, MAX_ORB_SIZE_DP);
        kolitiOrbAlphaPct = clamp(p.getInt(KEY_KOLITI_ORB_ALPHA_PCT, 100), MIN_ORB_ALPHA_PCT, MAX_ORB_ALPHA_PCT);
        kolitiOrbDock = clamp(p.getInt(KEY_KOLITI_ORB_DOCK, DOCK_LEFT), DOCK_LEFT, DOCK_REMEMBER);
        kolitiAvatarPath = p.getString(KEY_KOLITI_AVATAR_PATH, "");
        if (kolitiAvatarPath == null) kolitiAvatarPath = "";
        kolitiCustomName = safeTrim(p.getString(KEY_KOLITI_CUSTOM_NAME, ""), 24);
        hideDividers = p.getBoolean(KEY_HIDE_DIVIDERS, false);
        revision++;
    }

    public static long getRevision() {
        return revision;
    }

    // ---- Colors & Surfaces ----

    /** Custom ARGB accent color, or 0 for theme default. */
    public static int getAccentColor() {
        return accentColor;
    }

    public static void setAccentColor(int argbOrZero) {
        accentColor = argbOrZero == 0 ? 0 : (0xFF000000 | argbOrZero);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_ACCENT_COLOR, accentColor).apply();
    }

    public static int getSurfacePreset() {
        return surfacePreset;
    }

    public static void setSurfacePreset(int preset) {
        surfacePreset = clamp(preset, SURFACE_AUTO, SURFACE_CUSTOM_HEX);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_SURFACE_PRESET, surfacePreset).apply();
    }

    public static int getCustomSurfaceColor() {
        return customSurfaceColor;
    }

    public static void setCustomSurfaceColor(int argb) {
        customSurfaceColor = 0xFF000000 | argb;
        surfacePreset = SURFACE_CUSTOM_HEX;
        revision++;
        SharedPreferences p = prefs;
        if (p != null) {
            p.edit()
                    .putInt(KEY_CUSTOM_SURFACE_COLOR, customSurfaceColor)
                    .putInt(KEY_SURFACE_PRESET, surfacePreset)
                    .apply();
        }
    }

    /** Resolves surface base background color for the active preset, or 0 for Auto. */
    public static int resolveCustomBaseSurfaceColor() {
        switch (surfacePreset) {
            case SURFACE_OLED_BLACK: return 0xFF04060A;
            case SURFACE_GRAPHITE: return 0xFF10131C;
            case SURFACE_MIDNIGHT: return 0xFF0B1022;
            case SURFACE_CRIMSON_NOIR: return 0xFF16090D;
            case SURFACE_PORCELAIN: return 0xFFF8FAFC;
            case SURFACE_CUSTOM_HEX: return customSurfaceColor;
            case SURFACE_AUTO:
            default:
                return 0;
        }
    }

    // ---- Cards, Geometry & Effects ----

    public static int getCardStyle() {
        return cardStyle;
    }

    public static void setCardStyle(int style) {
        cardStyle = clamp(style, CARD_STYLE_CYBER_GLASS, CARD_STYLE_SOFT_PILL);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_CARD_STYLE, cardStyle).apply();
    }

    public static int getCornerRadiusDp() {
        return cornerRadiusDp;
    }

    public static void setCornerRadiusDp(int dpVal) {
        cornerRadiusDp = clamp(dpVal, MIN_CORNER_DP, MAX_CORNER_DP);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_CORNER_RADIUS_DP, cornerRadiusDp).apply();
    }

    /** 0 = Off, 1 = Soft (50%), 2 = Standard (100%), 3 = Max (140%). */
    public static int getNeonIntensity() {
        return neonIntensity;
    }

    public static float getNeonMultiplier() {
        switch (neonIntensity) {
            case 0: return 0f;
            case 1: return 0.55f;
            case 3: return 1.40f;
            case 2:
            default: return 1.0f;
        }
    }

    public static void setNeonIntensity(int level) {
        neonIntensity = clamp(level, 0, 3);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_NEON_INTENSITY, neonIntensity).apply();
    }

    public static int getChatDensity() {
        return chatDensity;
    }

    public static void setChatDensity(int density) {
        chatDensity = clamp(density, DENSITY_COMPACT, DENSITY_COMFORT);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_CHAT_DENSITY, chatDensity).apply();
    }

    public static boolean isHideDividers() {
        return hideDividers;
    }

    public static void setHideDividers(boolean hide) {
        hideDividers = hide;
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putBoolean(KEY_HIDE_DIVIDERS, hideDividers).apply();
    }

    // ---- Layout & Placement ----

    public static int getQuickBarPlacement() {
        return quickBarPlacement;
    }

    public static void setQuickBarPlacement(int placement) {
        quickBarPlacement = clamp(placement, QUICK_BAR_UNDER_SEARCH, QUICK_BAR_HIDDEN);
        ColgramConfig.setQuickBarEnabled(quickBarPlacement != QUICK_BAR_HIDDEN);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_QUICK_BAR_PLACEMENT, quickBarPlacement).apply();
    }

    public static boolean isShowHeaderShield() {
        return showHeaderShield;
    }

    public static void setShowHeaderShield(boolean show) {
        showHeaderShield = show;
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putBoolean(KEY_SHOW_HEADER_SHIELD, showHeaderShield).apply();
    }

    public static String getCustomHeaderTitle() {
        return customHeaderTitle;
    }

    public static String getEffectiveHeaderTitle() {
        return customHeaderTitle.isEmpty() ? "Colgram" : customHeaderTitle;
    }

    public static void setCustomHeaderTitle(String title) {
        customHeaderTitle = safeTrim(title, 28);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putString(KEY_CUSTOM_HEADER_TITLE, customHeaderTitle).apply();
    }

    // ---- Koliti Orb & Avatar Customization ----

    public static int getKolitiOrbSizeDp() {
        return kolitiOrbSizeDp;
    }

    public static void setKolitiOrbSizeDp(int sizeDp) {
        kolitiOrbSizeDp = clamp(sizeDp, MIN_ORB_SIZE_DP, MAX_ORB_SIZE_DP);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_KOLITI_ORB_SIZE_DP, kolitiOrbSizeDp).apply();
    }

    public static int getKolitiOrbAlphaPct() {
        return kolitiOrbAlphaPct;
    }

    public static float getKolitiOrbAlpha() {
        return kolitiOrbAlphaPct / 100f;
    }

    public static void setKolitiOrbAlphaPct(int pct) {
        kolitiOrbAlphaPct = clamp(pct, MIN_ORB_ALPHA_PCT, MAX_ORB_ALPHA_PCT);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_KOLITI_ORB_ALPHA_PCT, kolitiOrbAlphaPct).apply();
    }

    public static int getKolitiOrbDock() {
        return kolitiOrbDock;
    }

    public static void setKolitiOrbDock(int dock) {
        kolitiOrbDock = clamp(dock, DOCK_LEFT, DOCK_REMEMBER);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(KEY_KOLITI_ORB_DOCK, kolitiOrbDock).apply();
    }

    public static String getKolitiCustomAvatarPath() {
        return kolitiAvatarPath;
    }

    public static void setKolitiCustomAvatarPath(String path) {
        kolitiAvatarPath = path == null ? "" : path.trim();
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putString(KEY_KOLITI_AVATAR_PATH, kolitiAvatarPath).apply();
    }

    public static String getKolitiCustomName() {
        return kolitiCustomName;
    }

    public static String getKolitiDisplayName() {
        return kolitiCustomName.isEmpty() ? "Koliti" : kolitiCustomName;
    }

    public static void setKolitiCustomName(String name) {
        kolitiCustomName = safeTrim(name, 24);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().putString(KEY_KOLITI_CUSTOM_NAME, kolitiCustomName).apply();
    }

    // ---- Helpers: HEX parsing, JSON export/import, Reset ----

    /** Parses "#RRGGBB" or "RRGGBB" into opaque ARGB, or returns 0 on invalid input. */
    public static int parseHexColor(String input) {
        if (input == null) return 0;
        String s = input.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 8) s = s.substring(2);
        if (s.length() != 6) return 0;
        try {
            int rgb = Integer.parseInt(s, 16);
            return 0xFF000000 | (rgb & 0x00FFFFFF);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static String formatHexColor(int argb) {
        return String.format(java.util.Locale.US, "#%06X", argb & 0x00FFFFFF);
    }

    public static String exportJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"v\": 1,\n");
        sb.append("  \"accent\": \"").append(escapeJson(accentColor == 0 ? "" : formatHexColor(accentColor))).append("\",\n");
        sb.append("  \"surfacePreset\": ").append(surfacePreset).append(",\n");
        sb.append("  \"customSurface\": \"").append(escapeJson(formatHexColor(customSurfaceColor))).append("\",\n");
        sb.append("  \"cardStyle\": ").append(cardStyle).append(",\n");
        sb.append("  \"cornerRadiusDp\": ").append(cornerRadiusDp).append(",\n");
        sb.append("  \"neonIntensity\": ").append(neonIntensity).append(",\n");
        sb.append("  \"chatDensity\": ").append(chatDensity).append(",\n");
        sb.append("  \"hideDividers\": ").append(hideDividers).append(",\n");
        sb.append("  \"quickBarPlacement\": ").append(quickBarPlacement).append(",\n");
        sb.append("  \"showHeaderShield\": ").append(showHeaderShield).append(",\n");
        sb.append("  \"headerTitle\": \"").append(escapeJson(customHeaderTitle)).append("\",\n");
        sb.append("  \"orbSizeDp\": ").append(kolitiOrbSizeDp).append(",\n");
        sb.append("  \"orbAlphaPct\": ").append(kolitiOrbAlphaPct).append(",\n");
        sb.append("  \"orbDock\": ").append(kolitiOrbDock).append(",\n");
        sb.append("  \"kolitiName\": \"").append(escapeJson(kolitiCustomName)).append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    public static boolean importJson(String json) {
        if (json == null) return false;
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false;
        Map<String, String> kv = parseFlatJsonObject(trimmed);
        if (kv == null || kv.isEmpty()) return false;
        try {
            if (kv.containsKey("accent")) {
                String a = kv.get("accent");
                setAccentColor(a == null || a.isEmpty() ? 0 : parseHexColor(a));
            }
            if (kv.containsKey("customSurface")) {
                int c = parseHexColor(kv.get("customSurface"));
                if (c != 0) setCustomSurfaceColor(c);
            }
            if (kv.containsKey("surfacePreset")) setSurfacePreset(parseIntSafe(kv.get("surfacePreset"), SURFACE_AUTO));
            if (kv.containsKey("cardStyle")) setCardStyle(parseIntSafe(kv.get("cardStyle"), CARD_STYLE_CYBER_GLASS));
            if (kv.containsKey("cornerRadiusDp")) setCornerRadiusDp(parseIntSafe(kv.get("cornerRadiusDp"), DEFAULT_CORNER_DP));
            if (kv.containsKey("neonIntensity")) setNeonIntensity(parseIntSafe(kv.get("neonIntensity"), 2));
            if (kv.containsKey("chatDensity")) setChatDensity(parseIntSafe(kv.get("chatDensity"), DENSITY_NORMAL));
            if (kv.containsKey("hideDividers")) setHideDividers("true".equalsIgnoreCase(kv.get("hideDividers")));
            if (kv.containsKey("quickBarPlacement")) setQuickBarPlacement(parseIntSafe(kv.get("quickBarPlacement"), QUICK_BAR_UNDER_SEARCH));
            if (kv.containsKey("showHeaderShield")) setShowHeaderShield(!"false".equalsIgnoreCase(kv.get("showHeaderShield")));
            if (kv.containsKey("headerTitle")) setCustomHeaderTitle(kv.get("headerTitle"));
            if (kv.containsKey("orbSizeDp")) setKolitiOrbSizeDp(parseIntSafe(kv.get("orbSizeDp"), DEFAULT_ORB_SIZE_DP));
            if (kv.containsKey("orbAlphaPct")) setKolitiOrbAlphaPct(parseIntSafe(kv.get("orbAlphaPct"), 100));
            if (kv.containsKey("orbDock")) setKolitiOrbDock(parseIntSafe(kv.get("orbDock"), DOCK_LEFT));
            if (kv.containsKey("kolitiName")) setKolitiCustomName(kv.get("kolitiName"));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '"') sb.append("\\\"");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static Map<String, String> parseFlatJsonObject(String s) {
        Map<String, String> out = new HashMap<>();
        int n = s.length();
        int i = 1; // skip '{'
        while (i < n - 1) {
            while (i < n - 1 && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            if (i >= n - 1) break;
            if (s.charAt(i) != '"') return null;
            StringBuilder key = new StringBuilder();
            i++;
            while (i < n - 1 && s.charAt(i) != '"') {
                if (s.charAt(i) == '\\' && i + 1 < n - 1) i++;
                key.append(s.charAt(i++));
            }
            if (i >= n - 1 || s.charAt(i) != '"') return null;
            i++;
            while (i < n - 1 && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n - 1 || s.charAt(i) != ':') return null;
            i++;
            while (i < n - 1 && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n - 1) return null;
            StringBuilder val = new StringBuilder();
            if (s.charAt(i) == '"') {
                i++;
                while (i < n - 1 && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\' && i + 1 < n - 1) {
                        i++;
                        char esc = s.charAt(i);
                        if (esc == 'n') val.append('\n');
                        else if (esc == 'r') val.append('\r');
                        else val.append(esc);
                        i++;
                    } else {
                        val.append(s.charAt(i++));
                    }
                }
                if (i >= n - 1 || s.charAt(i) != '"') return null;
                i++;
            } else {
                while (i < n - 1 && s.charAt(i) != ',' && !Character.isWhitespace(s.charAt(i))) {
                    val.append(s.charAt(i++));
                }
            }
            out.put(key.toString(), val.toString());
        }
        return out;
    }

    public static void resetAll() {
        accentColor = 0;
        surfacePreset = SURFACE_AUTO;
        customSurfaceColor = 0xFF0E111A;
        cardStyle = CARD_STYLE_CYBER_GLASS;
        cornerRadiusDp = DEFAULT_CORNER_DP;
        neonIntensity = 2;
        chatDensity = DENSITY_NORMAL;
        quickBarPlacement = QUICK_BAR_UNDER_SEARCH;
        showHeaderShield = true;
        customHeaderTitle = "";
        kolitiOrbSizeDp = DEFAULT_ORB_SIZE_DP;
        kolitiOrbAlphaPct = 100;
        kolitiOrbDock = DOCK_LEFT;
        kolitiAvatarPath = "";
        kolitiCustomName = "";
        hideDividers = false;
        ColgramConfig.setQuickBarEnabled(true);
        revision++;
        SharedPreferences p = prefs;
        if (p != null) p.edit().clear().apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String safeTrim(String s, int maxLen) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > maxLen ? t.substring(0, maxLen) : t;
    }
}
