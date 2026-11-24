package org.colgram.core;

public final class ColgramThemePolicyTest {
    private static void check(boolean c, String m) {
        if (!c) throw new AssertionError(m);
    }

    public static void main(String[] args) {
        int blueFill = 0xff229af0;
        int ink = ColgramThemePolicy.contrastInkOn(blueFill);
        check(ColgramThemePolicy.contrast(ink, blueFill) >= 3.0, "ink must be readable on blue accent fill");
        int white = 0xffffffff;
        int inkOnWhite = ColgramThemePolicy.contrastInkOn(white);
        check(ColgramThemePolicy.contrast(inkOnWhite, white) >= 4.5, "ink must be readable on white");
        int black = 0xff000000;
        int inkOnBlack = ColgramThemePolicy.contrastInkOn(black);
        check(ColgramThemePolicy.contrast(inkOnBlack, black) >= 4.5, "ink must be readable on black");
        int fixed = ColgramThemePolicy.ensureReadableOnSurface(white, white, 0xffffffff, 0xff000000, 4.5);
        check(ColgramThemePolicy.contrast(fixed, white) >= 4.5, "white-on-white must be repaired");
        int fixed2 = ColgramThemePolicy.ensureReadableOnSurface(black, black, 0xffffffff, 0xff000000, 4.5);
        check(ColgramThemePolicy.contrast(fixed2, black) >= 4.5, "black-on-black must be repaired");
        check(!ColgramThemePolicy.shouldTreatAsDarkForeground(blueFill, false, false),
                "saturated blue fill must not be treated as dark foreground ink");
        check(ColgramThemePolicy.shouldTreatAsDarkForeground(0xff181818, true, false),
                "neutral dark ink foreground key must be treated as dark foreground");
        // Green online circle (#4ecc5e, luma=154, chroma=126) MUST NEVER be misclassified as a light background!
        check(!ColgramThemePolicy.shouldTreatAsLightSurface(0xff4ecc5e, false, false),
                "green online circle must never be erased as light surface");
        check(!ColgramThemePolicy.shouldTreatAsLightSurface(0xff46aa36, false, false),
                "green check accent must never be erased as light surface");
        check(ColgramThemePolicy.shouldTreatAsLightSurface(0xffffffff, false, false),
                "pure white surface hole must be recognized as light surface");
        check(ColgramThemePolicy.shouldTreatAsLightSurface(0xfff0f0f0, false, false),
                "neutral light gray surface hole must be recognized as light surface");
        int grayOnGray = ColgramThemePolicy.ensureReadableOnSurfaces(
                0xff6e747c, 0xff2a2e37, 0xff16181e, 0xffd2d7e0, 0xff1e242c, 4.5);
        check(ColgramThemePolicy.contrast(grayOnGray, 0xff2a2e37) >= 4.5,
                "gray-on-gray text must be repaired to >= 4.5 contrast");
        // Low-alpha foregrounds must boost alpha so contrast is actually >= 4.5!
        int lowAlphaRepaired = ColgramThemePolicy.ensureReadableOnSurface(
                0x64ffffff, 0xff1e1e1f, 0xffdce6f4, 0xff222a35, 4.5);
        check(ColgramThemePolicy.contrast(lowAlphaRepaired, 0xff1e1e1f) >= 4.5,
                "low-alpha 0x64ffffff on dark panel must achieve >= 4.5 contrast");
        // Reply panel & read checkmarks on mid-grey surface must hit >= 4.5
        int replyOnMidGrey = ColgramThemePolicy.resolveReplyPanelInk(0x991b2227, 0xff787e88, false);
        check(ColgramThemePolicy.contrast(replyOnMidGrey, 0xff787e88) >= 4.5,
                "reply panel ink on mid-grey must achieve >= 4.5");
        int checkOnMidGrey = ColgramThemePolicy.resolveCheckmarkInk(0xff787e88, 0xff787e88, 0xff7ec8ff, 0xff1565c0);
        check(ColgramThemePolicy.contrast(checkOnMidGrey, 0xff787e88) >= 4.5,
                "checkmark ink on mid-grey must achieve >= 4.5");
        check(ColgramThemePolicy.perceivedLuma(0xffffffff) > 250, "white luma near 255");
        // Purple/blue outgoing bubbles & dark translucent service overlays MUST produce crisp WHITE ink!
        for (int b : new int[]{0xff8251e2, 0xff8a58e8, 0xff9662ee, 0xff2a82da}) {
            check(ColgramThemePolicy.resolveBubbleInk(0xff0a0d12, b, 0xffffffff, 0xff0a0d12, 4.5) == 0xffffffff,
                    "bubble " + String.format("#%08x", b) + " must force WHITE primary/check ink");
            check(ColgramThemePolicy.resolveBubbleInk(0xff0a0d12, b, 0xebffffff, 0xff0a0d12, 4.5) == 0xebffffff,
                    "bubble " + String.format("#%08x", b) + " must force 0xEBFFFFFF time/reply ink");
        }
        check(ColgramThemePolicy.resolveServiceOverlayInk(0x9926323d) == 0xffffffff,
                "dark translucent chat_serviceBackground (#9926323D) must force WHITE");
        System.out.println("ColgramThemePolicyTest: PASS");
    }
}
