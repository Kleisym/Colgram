package org.colgram.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * ColgramCloak — Hardware and OS identity spoofing layer.
 * Intercepts Telegram's MTProto initConnection call and injects
 * synthetic device parameters, preventing hardware fingerprinting.
 */
public class ColgramCloak {

    public static class DeviceProfile {
        public final String manufacturer;
        public final String model;
        public final String systemVersion;
        public final String appVersion;
        public final String langCode;

        public DeviceProfile(String model, String systemVersion, String appVersion, String langCode) {
            this(inferManufacturer(model), model, systemVersion, appVersion, langCode);
        }

        public DeviceProfile(String manufacturer, String model, String systemVersion, String appVersion, String langCode) {
            this.manufacturer = manufacturer;
            this.model = model;
            this.systemVersion = systemVersion;
            this.appVersion = appVersion;
            this.langCode = langCode;
        }
    }

    // Curated list of common realistic profiles for randomized and selector cloaking
    private static final DeviceProfile[] PRESET_PROFILES = new DeviceProfile[] {
        new DeviceProfile("Google", "Google Pixel 8 Pro", "SDK 34 (Android 14)", "12.10.6", "en"),
        new DeviceProfile("Google", "Google Pixel 9 Pro", "SDK 35 (Android 15)", "12.10.6", "en"),
        new DeviceProfile("samsung", "Samsung Galaxy S24 Ultra", "SDK 34 (Android 14)", "12.10.6", "en"),
        new DeviceProfile("Xiaomi", "Xiaomi 14 Ultra", "SDK 34 (Android 14)", "12.10.6", "en"),
        new DeviceProfile("Nothing", "Nothing Phone (2)", "SDK 34 (Android 14)", "12.10.6", "en"),
        new DeviceProfile("OnePlus", "OnePlus 12", "SDK 34 (Android 14)", "12.10.6", "en"),
        new DeviceProfile("Google", "Google Pixel 7a", "SDK 33 (Android 13)", "12.10.6", "en"),
        new DeviceProfile("Sony", "Sony Xperia 1 V", "SDK 34 (Android 14)", "12.10.6", "en"),
        new DeviceProfile("Apple", "iPhone 15 Pro Max", "iOS 17.6.1", "12.10.6", "en")
    };

    private static String inferManufacturer(String model) {
        if (model == null) return "Google";
        String lower = model.toLowerCase(java.util.Locale.US);
        if (lower.contains("samsung") || lower.startsWith("sm-")) return "samsung";
        if (lower.contains("iphone") || lower.contains("ipad")) return "Apple";
        if (lower.contains("xiaomi") || lower.contains("redmi") || lower.contains("poco")) return "Xiaomi";
        if (lower.contains("nothing")) return "Nothing";
        if (lower.contains("oneplus")) return "OnePlus";
        if (lower.contains("sony") || lower.contains("xperia")) return "Sony";
        if (lower.contains("motorola") || lower.contains("moto")) return "motorola";
        return "Google";
    }

    public static DeviceProfile findPresetForModel(String model) {
        if (model == null) return null;
        String needle = model.trim();
        for (DeviceProfile p : PRESET_PROFILES) {
            if (p.model.equalsIgnoreCase(needle)) {
                return p;
            }
        }
        return null;
    }

    public static String getSystemVersionForModel(String model, String configuredSysVersion) {
        DeviceProfile preset = findPresetForModel(model);
        if (preset != null) {
            boolean modelIsIos = preset.model.toLowerCase(java.util.Locale.US).contains("iphone");
            if (configuredSysVersion == null || configuredSysVersion.trim().isEmpty()) {
                return preset.systemVersion;
            }
            String sysLower = configuredSysVersion.toLowerCase(java.util.Locale.US);
            if (modelIsIos && !sysLower.contains("ios")) {
                return preset.systemVersion;
            }
            if (!modelIsIos && sysLower.contains("ios")) {
                return preset.systemVersion;
            }
            if (preset.model.equalsIgnoreCase("Google Pixel 9 Pro") && sysLower.contains("android 14")) {
                return preset.systemVersion;
            }
            return configuredSysVersion;
        }
        if (configuredSysVersion == null || configuredSysVersion.trim().isEmpty()) {
            return ColgramConfig.DEFAULT_SYSTEM_VERSION;
        }
        return configuredSysVersion;
    }

    public static String getEffectiveDeviceModel() {
        String m = ColgramConfig.getSpoofDeviceModel();
        return (m == null || m.trim().isEmpty()) ? ColgramConfig.DEFAULT_MODEL : m.trim();
    }

    public static String getEffectiveSystemVersion() {
        return getSystemVersionForModel(getEffectiveDeviceModel(), ColgramConfig.getSpoofSystemVersion());
    }

    public static String getEffectiveManufacturer() {
        String model = getEffectiveDeviceModel();
        DeviceProfile preset = findPresetForModel(model);
        return preset != null ? preset.manufacturer : inferManufacturer(model);
    }

    /**
     * Intercepts and transforms connection parameters before MTProto initialization.
     *
     * @param originalModel Telegram's detected Build.MODEL
     * @param originalSysVersion Telegram's detected Build.VERSION.RELEASE
     * @param originalAppVersion Current Telegram app version
     * @param originalLang System locale
     * @return Map containing spoofed parameters
     */
    public static Map<String, String> getCloakedConnectionParams(
            String originalModel,
            String originalSysVersion,
            String originalAppVersion,
            String originalLang) {

        Map<String, String> params = new HashMap<>();

        if (!ColgramConfig.isCloakEnabled()) {
            params.put("device_model", originalModel);
            params.put("system_version", originalSysVersion);
            params.put("app_version", originalAppVersion);
            params.put("lang_code", originalLang);
            params.put("system_lang_code", originalLang);
            return params;
        }

        // Apply coherent configured spoof profile
        String model = getEffectiveDeviceModel();
        String sysVersion = getEffectiveSystemVersion();
        String lang = ColgramConfig.getSpoofLang();
        if (lang == null || lang.trim().isEmpty()) {
            lang = ColgramConfig.DEFAULT_LANG;
        }

        params.put("device_model", model);
        params.put("system_version", sysVersion);
        params.put("manufacturer", getEffectiveManufacturer());
        params.put("app_version", originalAppVersion);
        params.put("lang_code", lang);
        params.put("system_lang_code", lang);

        return params;
    }

    /**
     * Generates a random realistic fingerprint profile.
     */
    public static DeviceProfile getRandomProfile() {
        int index = new Random().nextInt(PRESET_PROFILES.length);
        return PRESET_PROFILES[index];
    }
}
