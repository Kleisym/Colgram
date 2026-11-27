package org.colgram.core;

import java.util.Map;

/**
 * Isolated JVM tests for Track 1 privacy defaults, Contract A spam/mention clear gate,
 * cloaking suffix stripping, and strict encrypted DNS enforcement.
 */
public final class ColgramPrivacyCloakTest {
    private static int checks;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("ColgramPrivacyCloakTest failed [" + checks + "]: " + msg);
        }
    }

    public static void main(String[] args) {
        // 1. Safe privacy defaults in ColgramConfig static initializers
        check(ColgramConfig.isGhostReadEnabled(), "ghostReadEnabled default must be true");
        check(ColgramConfig.isGhostTypingEnabled(), "ghostTypingEnabled default must be true");
        check(!ColgramConfig.isAutoUpdateEnabled(), "autoUpdateEnabled default must be false");
        check(!ColgramConfig.isUnlimitedMemoryEnabled(), "unlimitedMemoryEnabled default must be false");
        check(ColgramConfig.isStrictProxySecurityEnabled(), "strictProxySecurityEnabled default must be true");

        // 2. Contract A: ColgramGhostMode spam/mention/read-all clear ThreadLocal gate
        check(!ColgramGhostMode.isAllowingSpamMentionClear(),
                "isAllowingSpamMentionClear must default to false");
        check(ColgramGhostMode.shouldPreventReadReceipt(123456L),
                "shouldPreventReadReceipt must honor ghostReadEnabled=true by default");

        ColgramGhostMode.runAllowingSpamMentionClear(() -> {
            check(ColgramGhostMode.isAllowingSpamMentionClear(),
                    "isAllowingSpamMentionClear must be true inside runAllowingSpamMentionClear");
            check(!ColgramGhostMode.shouldPreventReadReceipt(123456L),
                    "shouldPreventReadReceipt must allow read receipts inside runAllowingSpamMentionClear");
            ColgramGhostMode.runAllowingSpamMentionClear(() ->
                    check(ColgramGhostMode.isAllowingSpamMentionClear(),
                            "nested runAllowingSpamMentionClear must preserve depth > 0"));
            check(ColgramGhostMode.isAllowingSpamMentionClear(),
                    "outer runAllowingSpamMentionClear must stay active after inner exits");
        });
        check(!ColgramGhostMode.isAllowingSpamMentionClear(),
                "isAllowingSpamMentionClear must reset to false after outer exits");

        boolean caught = false;
        try {
            ColgramGhostMode.runAllowingSpamMentionClear(() -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            caught = true;
        }
        check(caught && !ColgramGhostMode.isAllowingSpamMentionClear(),
                "runAllowingSpamMentionClear must clean up ThreadLocal on exception");

        // 3. ColgramCloak: strip -colgram suffix cleanly from initConnection app_version
        check("12.10.6".equals(ColgramCloak.stripColgramSuffix("12.10.6-colgram.ci42")),
                "stripColgramSuffix must drop -colgram.* suffix");
        check("12.10.6".equals(ColgramCloak.stripColgramSuffix("12.10.6")),
                "stripColgramSuffix must preserve vanilla version");
        Map<String, String> cloaked = ColgramCloak.getCloakedConnectionParams(
                "RealPixel", "Android 14", "12.10.6-colgram.20261114", "ru");
        check("12.10.6".equals(cloaked.get("app_version")),
                "getCloakedConnectionParams must mask -colgram build tag");

        // 4. ColgramDohResolver: strict encrypted DNS disables plaintext domestic UDP/53 & ranks non-RU first
        byte[] dummyQuery = new byte[16];
        check(ColgramDohResolver.queryFastDomesticUdp(dummyQuery, null) == null,
                "queryFastDomesticUdp must return null when strictProxySecurityEnabled is true");
        ColgramDohResolver.Endpoint[] eps = ColgramDohResolver.configuredEndpoints();
        check(eps != null && eps.length > 0, "configuredEndpoints must be non-empty");
        String firstGroup = eps[0].providerGroup;
        check(!"comss".equals(firstGroup) && !"yandex".equals(firstGroup) && !"alidns".equals(firstGroup),
                "first configured DoH endpoint must prioritize non-RU resolver, got=" + firstGroup);

        System.out.println("ColgramPrivacyCloakTest: " + checks + " checks PASS");
    }
}
