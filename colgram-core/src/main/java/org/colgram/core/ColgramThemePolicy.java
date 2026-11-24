package org.colgram.core;

/** Pairs local UI paints with the surface they actually draw on. No Android dependency. */
public final class ColgramThemePolicy {
    private static final int BLACK = 0xff000000;
    private static final int WHITE = 0xffffffff;
    private static final double TEXT = 4.5;
    private static final double CONTROL = 3.0;

    private ColgramThemePolicy() {}

    public static BotPalette forBotSheet(int window, int sheet, int title, int secondary,
                                         int accent, int onAccent, boolean cyber) {
        int windowSurface = opaque(window, WHITE);
        boolean night = luminance(windowSurface) < .2;
        int surface = opaque(sheet, windowSurface);
        if (cyber) {
            surface = 0xff16181e;
        } else if (night && luminance(surface) > .55) {
            surface = 0xff202c39;
        }
        int brand = opaque(accent, surface);
        if (cyber) {
            brand = 0xffff3344;
        } else if (chroma(brand) < 20) {
            // The global readability guard sometimes returned white instead of the button accent.
            brand = night ? 0xff58aee9 : 0xff2884bf;
        }
        return new BotPalette(surface, title, secondary, brand, onAccent, cyber);
    }

    public static final class BotPalette {
        public final int sheet, title, secondary, handle, accent, error;
        public final int field, fieldText, fieldHint, fieldStroke;
        public final int paste, pastePressed, pasteText;
        public final int button, buttonPressed, buttonText, badge, badgeIcon;

        private BotPalette(int sheet, int title, int secondary, int brand, int onAccent, boolean cyber) {
            this.sheet = sheet;
            this.title = ink(title, sheet, sheet, TEXT);
            this.secondary = ink(secondary, sheet, sheet, TEXT);
            handle = mix(sheet, this.title, .28);
            accent = ink(brand, sheet, sheet, TEXT);
            error = ink(contrast(WHITE, sheet) > contrast(BLACK, sheet)
                    ? 0xffff8290 : 0xffb92038, sheet, sheet, TEXT);

            field = mix(sheet, strongestInk(sheet), .065);
            fieldText = ink(title, field, field, TEXT);
            fieldHint = ink(secondary, field, field, TEXT);
            fieldStroke = ink(brand, field, sheet, CONTROL);

            int pasteEnd = opposite(strongestInk(field));
            paste = mix(field, pasteEnd, .15);
            pastePressed = mix(field, pasteEnd, .30);
            pasteText = ink(brand, paste, pastePressed, TEXT);

            int buttonEnd = luminance(brand) >= luminance(sheet) ? WHITE : BLACK;
            if (contrast(buttonEnd, sheet) < CONTROL) {
                buttonEnd = opposite(buttonEnd);
            }
            int fitted = fitFill(brand, buttonEnd, sheet);
            // Paint the active/pressed states toward the same endpoint so their shared
            // label and spinner never need to switch foreground mid-press.
            button = fitted;
            buttonPressed = mix(fitted, buttonEnd, .16);
            buttonText = ink(onAccent, button, buttonPressed, TEXT);
            badge = mix(sheet, brand, cyber ? .17 : .14);
            badgeIcon = ink(brand, badge, badge, CONTROL);
        }
    }

    public static HintPalette forStoryHint(int window, int fill, int link) {
        int surface = opaque(window, WHITE);
        int background = opaque(fill, surface);
        // The camera asset and close icon are white in the stock hint; honor their ink.
        if (contrast(WHITE, background) < TEXT || contrast(0x7dffffff, background) < CONTROL) {
            background = luminance(surface) < .2 ? 0xff36414f : 0xff272f38;
        }
        int linkInk = contrast(link, background) >= TEXT
                ? opaque(link, background) : ink(0xff85caff, background, background, TEXT);
        return new HintPalette(background, linkInk);
    }

    public static final class HintPalette {
        public final int background, link;
        public final int text = WHITE;

        private HintPalette(int background, int link) {
            this.background = background;
            this.link = link;
        }
    }

    public static double contrast(int foreground, int background) {
        int solid = opaque(background, WHITE);
        double a = luminance(opaque(foreground, solid));
        double b = luminance(solid);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    /** Picks crisp white or dark charcoal ink depending on actual fill contrast (never same-on-same). */
    public static int contrastInkOn(int bgArgb) {
        int solid = opaque(bgArgb, 0xff202b36);
        double whiteContrast = contrast(WHITE, solid);
        double darkContrast = contrast(0xff101418, solid);
        return (whiteContrast >= 2.45 || whiteContrast >= darkContrast) ? WHITE : 0xff101418;
    }

    /** Repairs dark foregrounds on dark surfaces without corrupting saturated accent/button/badge fills. */
    public static boolean shouldTreatAsDarkForeground(int lightDefaultColor,
                                                      boolean explicitForegroundKey,
                                                      boolean explicitFillOrAccentKey) {
        if (explicitFillOrAccentKey) {
            return false;
        }
        if (explicitForegroundKey) {
            return true;
        }
        if ((lightDefaultColor >>> 24) < 0x20) {
            return false;
        }
        return perceivedLuma(lightDefaultColor) < 128 && chroma(lightDefaultColor) < 40;
    }

    /**
     * Identifies genuine neutral light surface holes (white/light-gray backgrounds) so dark themes
     * never leave bright white strips behind white text, WITHOUT erasing saturated status dots,
     * green online circles (#4ecc5e), checkmarks, waveform fills, or sender name colors!
     */
    public static boolean shouldTreatAsLightSurface(int lightDefaultColor,
                                                    boolean explicitForegroundKey,
                                                    boolean explicitFillOrAccentKey) {
        if (explicitFillOrAccentKey || explicitForegroundKey) {
            return false;
        }
        if ((lightDefaultColor >>> 24) < 0x20) {
            return false;
        }
        return perceivedLuma(lightDefaultColor) >= 196 && chroma(lightDefaultColor) < 24;
    }

    /** Guards text/icon paints against BOTH black-on-black and white-on-white on any surface. */
    public static int ensureReadableOnSurface(int fgArgb, int bgArgb, int lightInk, int darkInk, double minContrast) {
        int alpha = fgArgb >>> 24;
        if (alpha < 0x20) {
            return fgArgb;
        }
        int solidBg = opaque(bgArgb, WHITE);
        if (contrast(fgArgb, solidBg) >= minContrast) {
            return fgArgb;
        }
        int chosen = contrast(lightInk, solidBg) >= contrast(darkInk, solidBg) ? lightInk : darkInk;
        if (alpha >= 0xff) {
            return chosen | 0xff000000;
        }
        return (chosen & 0x00ffffff) | (alpha << 24);
    }

    /** Guards a foreground that may appear on EITHER of two surfaces (e.g. windowWhite or windowGray). */
    public static int ensureReadableOnSurfaces(int fgArgb, int bgA, int bgB, int lightInk, int darkInk, double minContrast) {
        int alpha = fgArgb >>> 24;
        if (alpha < 0x20) {
            return fgArgb;
        }
        int solidA = opaque(bgA, WHITE);
        int solidB = opaque(bgB, WHITE);
        double curWorst = Math.min(contrast(fgArgb, solidA), contrast(fgArgb, solidB));
        if (curWorst >= minContrast) {
            return fgArgb;
        }
        double lightWorst = Math.min(contrast(lightInk, solidA), contrast(lightInk, solidB));
        double darkWorst = Math.min(contrast(darkInk, solidA), contrast(darkInk, solidB));
        int chosen = lightWorst >= darkWorst ? lightInk : darkInk;
        if (alpha >= 0xff) {
            return chosen | 0xff000000;
        }
        return (chosen & 0x00ffffff) | (alpha << 24);
    }

    /** Relative WCAG luminance [0.0..1.0] of the RGB channels of a color. */
    public static double relativeLuminance(int color) {
        return luminance(color);
    }

    /** Computes the average opaque color across non-zero surface stops (e.g. bubble + gradients). */
    public static int averageSurfaceColor(int base, int g1, int g2, int g3) {
        int r = 0, g = 0, b = 0, count = 0;
        int[] cols = new int[]{base, g1, g2, g3};
        for (int c : cols) {
            if (c == 0 || (c >>> 24) < 0x20) continue;
            r += (c >>> 16) & 255;
            g += (c >>> 8) & 255;
            b += c & 255;
            count++;
        }
        if (count == 0) return base;
        return 0xff000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
    }

    /**
     * Resolves readable secondary text/icon ink for outgoing or incoming chat bubbles.
     * Any bubble with relative luminance < 0.55 receives crisp white/translucent-white ink
     * so saturated purple/blue/emerald bubbles never inherit muddy dark-grey timestamps/checks.
     */
    public static int resolveBubbleInk(int fgArgb, int bubbleBgArgb, int onDarkInk, int onLightInk, double minContrast) {
        int solidBg = opaque(bubbleBgArgb, WHITE);
        if (luminance(solidBg) < 0.55) {
            if ((fgArgb >>> 24) >= 0x20 && contrast(fgArgb, solidBg) >= Math.max(minContrast, 7.0)) {
                return fgArgb;
            }
            return onDarkInk;
        }
        return ensureReadableOnSurface(fgArgb, solidBg, onDarkInk, onLightInk, minContrast);
    }

    /**
     * Ensures muted unread badge fill stands apart from the dialog background AND gives white
     * badge text >= 5.5:1 WCAG contrast on both dark and light themes.
     */
    public static int resolveMutedBadgeBackground(int badgeBgArgb, int windowBgArgb, boolean darkSurface) {
        int solidWin = opaque(windowBgArgb, darkSurface ? 0xff16181e : WHITE);
        if (darkSurface) {
            int solid = (badgeBgArgb != 0 && (badgeBgArgb >>> 24) >= 0xd0) ? opaque(badgeBgArgb, solidWin) : 0xff3e536e;
            if (luminance(solid) >= 0.5 || contrast(WHITE, solid) < 5.5 || contrast(solid, solidWin) < 1.85) {
                return 0xff3e536e;
            }
            return solid;
        }
        int solid = (badgeBgArgb != 0 && (badgeBgArgb >>> 24) >= 0xd0) ? opaque(badgeBgArgb, solidWin) : 0xff52647a;
        if (contrast(WHITE, solid) < 5.5 || contrast(solid, solidWin) < 2.2) {
            return 0xff52647a;
        }
        return solid;
    }

    /**
     * Resolves unread counter text color with >= 5.5:1 contrast against both active and muted
     * badge backgrounds (forcing pure white whenever the muted badge or dark theme requires it).
     */
    public static int resolveUnreadCounterText(int currentFgArgb, int activeBadgeBg, int mutedBadgeBg, boolean darkSurface) {
        int solidMuted = opaque(mutedBadgeBg, 0xff3e536e);
        int solidActive = opaque(activeBadgeBg, 0xffe11d48);
        if (darkSurface || luminance(solidMuted) < 0.5) {
            return WHITE;
        }
        return ensureReadableOnSurfaces(currentFgArgb, solidActive, solidMuted, WHITE, 0xff101418, 5.5);
    }

    public static int perceivedLuma(int color) {
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int fitFill(int fill, int end, int surface) {
        final int onFill = opposite(end);
        for (int step = 0; step <= 32; step++) {
            int candidate = mix(fill, end, step / 32.0);
            if (contrast(candidate, surface) >= CONTROL && contrast(onFill, candidate) >= TEXT) {
                return candidate;
            }
        }
        return end;
    }

    private static int ink(int candidate, int a, int b, double minimum) {
        int solid = opaque(candidate, a);
        if ((candidate >>> 24) != 0 && Math.min(contrast(solid, a), contrast(solid, b)) >= minimum) {
            return solid;
        }
        return Math.min(contrast(WHITE, a), contrast(WHITE, b))
                > Math.min(contrast(BLACK, a), contrast(BLACK, b)) ? WHITE : BLACK;
    }

    private static int strongestInk(int surface) {
        return contrast(WHITE, surface) > contrast(BLACK, surface) ? WHITE : BLACK;
    }

    private static int opposite(int blackOrWhite) {
        return blackOrWhite == BLACK ? WHITE : BLACK;
    }

    public static boolean isDarkColor(int color) {
        return luminance(color) < 0.25;
    }

    private static double luminance(int color) {
        return .2126 * linear((color >>> 16) & 255) + .7152 * linear((color >>> 8) & 255)
                + .0722 * linear(color & 255);
    }

    private static double linear(int channel) {
        double s = channel / 255.0;
        return s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
    }

    private static int chroma(int color) {
        int r = (color >>> 16) & 255, g = (color >>> 8) & 255, b = color & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static int opaque(int fg, int bg) {
        return mix(bg, fg, (fg >>> 24) / 255.0);
    }

    private static int mix(int base, int tint, double amount) {
        int result = 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = (base >>> shift) & 255, b = (tint >>> shift) & 255;
            result |= (int) Math.round(a + (b - a) * amount) << shift;
        }
        return result;
    }
}
