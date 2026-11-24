package org.colgram.core;

/** Pairs local UI paints with the surface they actually draw on. No Android dependency. */
public final class ColgramThemePolicy {
    private static final int BLACK = 0xff000000;
    private static final int WHITE = 0xffffffff;
    private static final int DEEP_DARK_INK = 0xff0a0d12;
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

    /** Composites a potentially translucent foreground over a solid background. */
    public static int compositeOver(int fgArgb, int bgArgb) {
        int solidBg = solidSurface(bgArgb);
        return opaque(fgArgb, solidBg);
    }

    /**
     * Resolves a surface color to opaque ARGB without bleaching dark translucent surfaces to white.
     * Dark-RGB translucent surfaces composite over charcoal (#16181e); light-RGB surfaces over white.
     */
    public static int solidSurface(int bgArgb) {
        int alpha = bgArgb >>> 24;
        if (alpha >= 0xff) {
            return bgArgb | 0xff000000;
        }
        int fallback = perceivedLuma(bgArgb) < 128 ? 0xff16181e : WHITE;
        return opaque(bgArgb, fallback);
    }

    /** Resolves a translucent surface composited over an explicit underlying backdrop. */
    public static int solidSurfaceOver(int bgArgb, int underlyingBackdropArgb) {
        int solidBackdrop = solidSurface(underlyingBackdropArgb);
        if ((bgArgb >>> 24) >= 0xff) {
            return bgArgb | 0xff000000;
        }
        return opaque(bgArgb, solidBackdrop);
    }

    public static double contrast(int foreground, int background) {
        int solid = solidSurface(background);
        double a = luminance(opaque(foreground, solid));
        double b = luminance(solid);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    /** Picks crisp white or dark charcoal ink depending on actual fill contrast (never same-on-same). */
    public static int contrastInkOn(int bgArgb) {
        int solid = solidSurfaceOver(bgArgb, 0xff202b36);
        double whiteContrast = contrast(WHITE, solid);
        double darkContrast = contrast(0xff101418, solid);
        return (whiteContrast >= 3.0 || whiteContrast >= darkContrast) ? WHITE : 0xff101418;
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

    /**
     * Guards text/icon paints against BOTH dark-on-dark, white-on-white, AND grey-on-grey on any surface.
     * Boosts low alpha when the original translucency drops contrast below {@code minContrast}, and
     * escalates to crisp white/black on mid-grey surfaces where muted inks cannot meet WCAG AA.
     */
    public static int ensureReadableOnSurface(int fgArgb, int bgArgb, int lightInk, int darkInk, double minContrast) {
        int alpha = fgArgb >>> 24;
        if (alpha < 0x20) {
            return fgArgb;
        }
        int solidBg = solidSurface(bgArgb);
        if (contrast(fgArgb, solidBg) >= minContrast) {
            return fgArgb;
        }
        int chosen = pickCandidateOnSurfaces(solidBg, solidBg, lightInk, darkInk, minContrast);
        return applySafeAlpha(chosen, alpha, solidBg, solidBg, minContrast);
    }

    /**
     * Guards a foreground that may appear on EITHER of two surfaces (e.g. windowWhite or windowGray,
     * dialogBackground or dialogBackgroundGray, messagePanel or glassTopPanel).
     */
    public static int ensureReadableOnSurfaces(int fgArgb, int bgA, int bgB, int lightInk, int darkInk, double minContrast) {
        int alpha = fgArgb >>> 24;
        if (alpha < 0x20) {
            return fgArgb;
        }
        int solidA = solidSurface(bgA);
        int solidB = solidSurface(bgB);
        double curWorst = Math.min(contrast(fgArgb, solidA), contrast(fgArgb, solidB));
        if (curWorst >= minContrast) {
            return fgArgb;
        }
        int chosen = pickCandidateOnSurfaces(solidA, solidB, lightInk, darkInk, minContrast);
        return applySafeAlpha(chosen, alpha, solidA, solidB, minContrast);
    }

    /**
     * Keeps chromatic accent colors (sender names, reply titles, section headers, links, checkmarks)
     * faithful to their hue while smoothly lightening/darkening toward white/black until WCAG contrast
     * reaches {@code minContrast}. Neutral greys fall back to crisp readable inks.
     */
    public static int ensureAccentReadable(int fgArgb, int bgArgb, int fallbackLight, int fallbackDark, double minContrast) {
        int alpha = fgArgb >>> 24;
        if (alpha < 0x20) {
            return fgArgb;
        }
        int solidBg = solidSurface(bgArgb);
        if (contrast(fgArgb, solidBg) >= minContrast) {
            return fgArgb;
        }
        int baseOpaque = fgArgb | 0xff000000;
        if (chroma(baseOpaque) >= 18) {
            int targetEnd = contrast(WHITE, solidBg) >= contrast(BLACK, solidBg) ? WHITE : BLACK;
            for (int step = 1; step <= 24; step++) {
                int candidate = mix(baseOpaque, targetEnd, step / 24.0);
                if (contrast(candidate, solidBg) >= minContrast) {
                    return candidate;
                }
            }
            return targetEnd;
        }
        return ensureReadableOnSurface(fgArgb, solidBg, fallbackLight, fallbackDark, minContrast);
    }

    /**
     * Resolves readable checkmark / status icon ink (sent, read, clock, mute, pin) on dialog rows
     * or chat surfaces, guaranteeing {@code >= 4.5:1} contrast on dark, light, and mid-grey surfaces.
     */
    public static int resolveCheckmarkInk(int fgArgb, int bgArgb, int lightAccent, int darkAccent) {
        int solidBg = solidSurface(bgArgb);
        if ((fgArgb >>> 24) >= 0x20 && contrast(fgArgb, solidBg) >= TEXT) {
            return fgArgb;
        }
        return ensureAccentReadable(fgArgb, solidBg, lightAccent, darkAccent, TEXT);
    }

    /**
     * Resolves reply panel / glass overlay text and icon ink against the panel background.
     * Guarantees {@code >= 7.0:1} (or max {@code >= 4.58:1} on mid-grey) for primary text and
     * {@code >= 4.5:1} for secondary text / icons even when the panel is translucent or mid-grey.
     */
    public static int resolveReplyPanelInk(int fgArgb, int panelBgArgb, boolean primary) {
        int solidBg = solidSurface(panelBgArgb);
        double minC = primary ? 7.0 : TEXT;
        int lightInk = primary ? 0xfff4f7fb : 0xffdce6f4;
        int darkInk = primary ? 0xff14181e : 0xff222a35;
        return ensureReadableOnSurface(fgArgb, solidBg, lightInk, darkInk, minC);
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
            int solid = solidSurface(c);
            r += (solid >>> 16) & 255;
            g += (solid >>> 8) & 255;
            b += solid & 255;
            count++;
        }
        if (count == 0) return solidSurface(base);
        return 0xff000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
    }

    /**
     * Resolves readable primary or secondary text/icon ink for outgoing or incoming chat bubbles.
     * - Dark or saturated bubbles where {@code onDarkInk} meets {@code minContrast} receive crisp
     *   light/white ink so purple/blue/emerald/dark bubbles never inherit muddy dark-grey checks.
     * - Mid-grey and light bubbles use WCAG contrast selection (never forcing white onto mid-grey!).
     */
    public static int resolveBubbleInk(int fgArgb, int bubbleBgArgb, int onDarkInk, int onLightInk, double minContrast) {
        int solidBg = solidSurface(bubbleBgArgb);
        double bgLum = luminance(solidBg);
        double darkInkContrast = contrast(onDarkInk, solidBg);
        boolean preferWhiteSide = (bgLum < 0.22 || contrast(WHITE, solidBg) >= contrast(BLACK, solidBg))
                && (darkInkContrast >= minContrast || contrast(WHITE, solidBg) >= minContrast);
        if (preferWhiteSide) {
            if ((fgArgb >>> 24) >= 0x20) {
                int effFg = opaque(fgArgb, solidBg);
                if (luminance(effFg) > bgLum && contrast(fgArgb, solidBg) >= Math.max(minContrast, 6.5)) {
                    return fgArgb;
                }
            }
            if (darkInkContrast >= minContrast) {
                return onDarkInk;
            }
            return WHITE;
        }
        return ensureReadableOnSurface(fgArgb, solidBg, WHITE, onLightInk, minContrast);
    }

    /**
     * Ensures muted unread badge fill stands apart from the dialog background AND gives white
     * badge text >= 5.5:1 WCAG contrast on both dark and light themes.
     */
    public static int resolveMutedBadgeBackground(int badgeBgArgb, int windowBgArgb, boolean darkSurface) {
        int solidWin = solidSurfaceOver(windowBgArgb, darkSurface ? 0xff16181e : WHITE);
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
        int solidMuted = solidSurfaceOver(mutedBadgeBg, 0xff3e536e);
        int solidActive = solidSurfaceOver(activeBadgeBg, 0xffe11d48);
        if (darkSurface || luminance(solidMuted) < 0.5) {
            if (contrast(WHITE, solidMuted) >= 5.5 && contrast(WHITE, solidActive) >= 2.5) {
                return WHITE;
            }
        }
        return ensureReadableOnSurfaces(currentFgArgb, solidActive, solidMuted, WHITE, 0xff101418, 5.5);
    }

    public static int perceivedLuma(int color) {
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static double worstContrast(int fg, int solidA, int solidB) {
        double cA = contrast(fg, solidA);
        if (solidA == solidB) {
            return cA;
        }
        return Math.min(cA, contrast(fg, solidB));
    }

    private static int pickCandidateOnSurfaces(int solidA, int solidB, int lightInk, int darkInk, double minContrast) {
        double lightC = worstContrast(lightInk, solidA, solidB);
        double darkC = worstContrast(darkInk, solidA, solidB);
        int best = lightC >= darkC ? lightInk : darkInk;
        double bestC = Math.max(lightC, darkC);
        if (bestC >= minContrast) {
            return best;
        }
        // Try opaque versions of the preferred light/dark inks before escalating to pure white/black.
        int lightOp = lightInk | 0xff000000;
        int darkOp = darkInk | 0xff000000;
        double lightOpC = worstContrast(lightOp, solidA, solidB);
        double darkOpC = worstContrast(darkOp, solidA, solidB);
        if (lightOpC > bestC) {
            best = lightOp;
            bestC = lightOpC;
        }
        if (darkOpC > bestC) {
            best = darkOp;
            bestC = darkOpC;
        }
        if (bestC >= minContrast) {
            return best;
        }
        // Escalate on mid-grey surfaces to crisp white / deep obsidian / pure black.
        double deepC = worstContrast(DEEP_DARK_INK, solidA, solidB);
        if (deepC > bestC) {
            best = DEEP_DARK_INK;
            bestC = deepC;
        }
        if (bestC >= minContrast) {
            return best;
        }
        double whiteC = worstContrast(WHITE, solidA, solidB);
        double blackC = worstContrast(BLACK, solidA, solidB);
        if (whiteC > bestC) {
            best = WHITE;
            bestC = whiteC;
        }
        if (blackC > bestC) {
            best = BLACK;
        }
        return best;
    }

    private static int applySafeAlpha(int chosenInk, int origAlpha, int solidA, int solidB, double minContrast) {
        int inkAlpha = chosenInk >>> 24;
        if (inkAlpha == 0) {
            inkAlpha = 0xff;
            chosenInk |= 0xff000000;
        }
        if (origAlpha < 0xff && origAlpha < inkAlpha) {
            int withOrigAlpha = (chosenInk & 0x00ffffff) | (origAlpha << 24);
            if (worstContrast(withOrigAlpha, solidA, solidB) >= minContrast) {
                return withOrigAlpha;
            }
        }
        if (worstContrast(chosenInk, solidA, solidB) >= minContrast) {
            return chosenInk;
        }
        return chosenInk | 0xff000000;
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
        return luminance(solidSurface(color)) < 0.25;
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
