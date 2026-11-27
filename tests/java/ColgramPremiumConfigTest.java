import org.colgram.core.ColgramPremiumConfig;

/**
 * Pure-JVM tests for Contract C (ColgramPremiumConfig) defaults, setters,
 * normalization/clamping, and test reset behavior.
 */
public final class ColgramPremiumConfigTest {

    public static void main(String[] args) {
        ColgramPremiumConfig.resetForTests();

        // 1. Default values must match Contract C safely before Android init
        assertTrue(ColgramPremiumConfig.isBlockSponsoredAdsEnabled(), "ads block default true");
        assertTrue(ColgramPremiumConfig.isLocalVoiceTranscribeEnabled(), "local stt default true");
        assertFalse(ColgramPremiumConfig.isLocalVoiceTranscribeLocalFirst(), "stt local-first default false");
        assertTrue(ColgramPremiumConfig.isLocalTranslatorEnabled(), "local translator default true");
        assertTrue(ColgramPremiumConfig.isBypassNoForwardsEnabled(), "bypass noforwards default true");
        assertTrue(ColgramPremiumConfig.isSavedTagsEnabled(), "saved tags default true");
        assertFalse(ColgramPremiumConfig.isHideStoriesEnabled(), "hide stories default false");
        assertFalse(ColgramPremiumConfig.isAutoHideArchiveEnabled(), "auto-hide archive default false");
        assertEquals(0, ColgramPremiumConfig.getDefaultStartFolderId(), "default start folder 0");
        assertEquals("", ColgramPremiumConfig.getLocalDoubleTapReaction(), "double tap default empty");

        // 2. Round-trip updates (without Context -> pure volatile state on host JVM)
        ColgramPremiumConfig.setBlockSponsoredAdsEnabled(false);
        ColgramPremiumConfig.setLocalVoiceTranscribeEnabled(false);
        ColgramPremiumConfig.setLocalVoiceTranscribeLocalFirst(true);
        ColgramPremiumConfig.setLocalTranslatorEnabled(false);
        ColgramPremiumConfig.setBypassNoForwardsEnabled(false);
        ColgramPremiumConfig.setSavedTagsEnabled(false);
        ColgramPremiumConfig.setHideStoriesEnabled(true);
        ColgramPremiumConfig.setAutoHideArchiveEnabled(true);
        ColgramPremiumConfig.setDefaultStartFolderId(42);
        ColgramPremiumConfig.setLocalDoubleTapReaction("  \uD83D\uDD25  ");

        assertFalse(ColgramPremiumConfig.isBlockSponsoredAdsEnabled(), "ads block toggled false");
        assertFalse(ColgramPremiumConfig.isLocalVoiceTranscribeEnabled(), "local stt toggled false");
        assertTrue(ColgramPremiumConfig.isLocalVoiceTranscribeLocalFirst(), "stt local-first toggled true");
        assertFalse(ColgramPremiumConfig.isLocalTranslatorEnabled(), "translator toggled false");
        assertFalse(ColgramPremiumConfig.isBypassNoForwardsEnabled(), "noforwards toggled false");
        assertFalse(ColgramPremiumConfig.isSavedTagsEnabled(), "saved tags toggled false");
        assertTrue(ColgramPremiumConfig.isHideStoriesEnabled(), "hide stories toggled true");
        assertTrue(ColgramPremiumConfig.isAutoHideArchiveEnabled(), "auto-hide archive toggled true");
        assertEquals(42, ColgramPremiumConfig.getDefaultStartFolderId(), "folder id saved");
        assertEquals("\uD83D\uDD25", ColgramPremiumConfig.getLocalDoubleTapReaction(), "trimmed reaction emoji");

        // 3. Bounds & null normalization
        ColgramPremiumConfig.setDefaultStartFolderId(-7);
        assertEquals(0, ColgramPremiumConfig.getDefaultStartFolderId(), "negative folder id clamped to 0");
        ColgramPremiumConfig.setLocalDoubleTapReaction(null);
        assertEquals("", ColgramPremiumConfig.getLocalDoubleTapReaction(), "null reaction normalized empty");
        ColgramPremiumConfig.setLocalDoubleTapReaction("disabled");
        assertEquals("disabled", ColgramPremiumConfig.getLocalDoubleTapReaction(), "disabled preserved");

        // 4. Reset restores clean defaults
        ColgramPremiumConfig.resetForTests();
        assertTrue(ColgramPremiumConfig.isBlockSponsoredAdsEnabled(), "ads block reset");
        assertFalse(ColgramPremiumConfig.isHideStoriesEnabled(), "hide stories reset");
        assertEquals(0, ColgramPremiumConfig.getDefaultStartFolderId(), "folder id reset");
        assertEquals("", ColgramPremiumConfig.getLocalDoubleTapReaction(), "reaction reset");

        System.out.println("ColgramPremiumConfigTest: ALL PASSED");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void assertFalse(boolean cond, String msg) {
        if (cond) throw new AssertionError(msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
    }

    private static void assertEquals(String expected, String actual, String msg) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
        }
    }
}
