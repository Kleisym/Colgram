package org.colgram.core;

public final class ColgramPrivacyBackupTest {

    public static void main(String[] args) {
        testEpochNormalization();
        testLocalPhoneMasking();
        testBackupSchemaVersion();
        testPrivacyDefaultsAndConstants();
        System.out.println("ColgramPrivacyBackupTest: PASS");
    }

    private static void testEpochNormalization() {
        long sec = 1700000000L;
        assertEquals(sec * 1000L, ColgramHookHandler.normalizeEpochMillis(sec),
                "10-digit seconds should normalize to milliseconds");

        long ms = 1700000000123L;
        assertEquals(ms, ColgramHookHandler.normalizeEpochMillis(ms),
                "13-digit milliseconds must remain unchanged");

        long nowBefore = System.currentTimeMillis();
        long zeroNormalized = ColgramHookHandler.normalizeEpochMillis(0L);
        long nowAfter = System.currentTimeMillis();
        assertTrue(zeroNormalized >= nowBefore && zeroNormalized <= nowAfter + 1000L,
                "zero timestamp must fall back to current epoch millis");

        long negNormalized = ColgramHookHandler.normalizeEpochMillis(-42L);
        assertTrue(negNormalized >= nowBefore && negNormalized <= nowAfter + 1000L,
                "negative timestamp must fall back to current epoch millis");
    }

    private static void testLocalPhoneMasking() {
        String masked = ColgramHookHandler.maskLocalPhone("+79991234567");
        // Without Context prefs initialized, local phone hiding is off so the input stays intact.
        assertEquals("+79991234567", masked, "unmasked phone should match input when setting is off");
        assertEquals("", ColgramHookHandler.maskLocalPhone(""), "empty phone stays empty");
        assertEquals(null, ColgramHookHandler.maskLocalPhone(null), "null phone stays null");
    }

    private static void testBackupSchemaVersion() {
        assertTrue(ColgramSettingsBackup.SCHEMA_VERSION == 1, "schema version should be 1");
    }

    private static void testPrivacyDefaultsAndConstants() {
        // Without Context prefs initialized, defaults must match safe expected behavior.
        assertTrue(ColgramHookHandler.shouldUnlockOneTimeMedia(), "unlock one-time media defaults enabled");
        assertTrue(ColgramHookHandler.shouldUseStealthStories(), "stealth stories defaults enabled");
        assertTrue(ColgramHookHandler.shouldBypassFlagSecure(), "FLAG_SECURE bypass defaults true");
        assertTrue(ColgramHookHandler.shouldHideScreenshotNotify(), "hide screenshot notify defaults true");
        assertTrue(!ColgramHookHandler.shouldForwardNoQuote(), "NoQuote forward defaults false until opted in");
        assertTrue(!ColgramHookHandler.shouldHidePhoneLocally(), "local phone hide defaults false until opted in");
        assertTrue(!ColgramHookHandler.shouldConfirmCalls(), "confirm calls defaults false until opted in");
        assertTrue(!ColgramHookHandler.shouldConfirmStickers(), "confirm stickers defaults false until opted in");
        assertTrue(!ColgramHookHandler.shouldConfirmLinks(), "confirm links defaults false until opted in");
        assertTrue(!ColgramConfig.isMaxAnonymityEnabled(), "max anonymity defaults false");
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
