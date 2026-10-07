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
        public final String model;
        public final String systemVersion;
        public final String appVersion;
        public final String langCode;

        public DeviceProfile(String model, String systemVersion, String appVersion, String langCode) {
            this.model = model;
            this.systemVersion = systemVersion;
            this.appVersion = appVersion;
            this.langCode = langCode;
        }
    }

    // Curated list of common realistic profiles for randomized cloaking
    private static final DeviceProfile[] PRESET_PROFILES = new DeviceProfile[] {
        new DeviceProfile("Google Pixel 8 Pro", "SDK 34 (Android 14)", "10.14.5", "en"),
        new DeviceProfile("Samsung Galaxy S24 Ultra", "SDK 34 (Android 14)", "10.14.5", "en"),
        new DeviceProfile("Google Pixel 7a", "SDK 33 (Android 13)", "10.14.5", "en"),
        new DeviceProfile("Motorola Edge 40 Pro", "SDK 33 (Android 13)", "10.14.5", "en"),
        new DeviceProfile("Sony Xperia 1 V", "SDK 34 (Android 14)", "10.14.5", "en")
    };

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

        // Apply configured spoof profile
        String model = ColgramConfig.getSpoofDeviceModel();
        String sysVersion = ColgramConfig.getSpoofSystemVersion();
        String lang = ColgramConfig.getSpoofLang();

        params.put("device_model", model);
        params.put("system_version", sysVersion);
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
