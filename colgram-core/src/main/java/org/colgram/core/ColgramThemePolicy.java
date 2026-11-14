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
