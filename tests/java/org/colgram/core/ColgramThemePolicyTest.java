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
        int fixed = ColgramThemePolicy.ensureReadableOnSurface(white, white, 0xffffffff, 0xff000000, 3.0);
        check(ColgramThemePolicy.contrast(fixed, white) >= 3.0, "white-on-white must be repaired");
        int fixed2 = ColgramThemePolicy.ensureReadableOnSurface(black, black, 0xffffffff, 0xff000000, 3.0);
        check(ColgramThemePolicy.contrast(fixed2, black) >= 3.0, "black-on-black must be repaired");
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
                0xff6e747c, 0xff2a2e37, 0xff16181e, 0xffd2d7e0, 0xff1e242c, 4.0);
        check(ColgramThemePolicy.contrast(grayOnGray, 0xff2a2e37) >= 4.0,
                "gray-on-gray text must be repaired to readable contrast");
        check(ColgramThemePolicy.perceivedLuma(0xffffffff) > 250, "white luma near 255");
        System.out.println("ColgramThemePolicyTest: PASS");
    }
}