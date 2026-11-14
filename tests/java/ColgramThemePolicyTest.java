import org.colgram.core.ColgramThemePolicy;

public final class ColgramThemePolicyTest {
    private static int checks;

    private static void require(boolean passes, String description) {
        checks++;
        if (!passes) throw new AssertionError(description);
    }

    private static void contrast(int ink, int surface, double minimum, String name) {
        double observed = ColgramThemePolicy.contrast(ink, surface);
        require(observed >= minimum, name + " contrast was " + observed);
    }

    private static void sheet(ColgramThemePolicy.BotPalette p, String fixture) {
        contrast(p.title, p.sheet, 4.5, fixture + " title");
        contrast(p.secondary, p.sheet, 4.5, fixture + " subtitle and tip");
        contrast(p.fieldText, p.field, 4.5, fixture + " token");
        contrast(p.fieldHint, p.field, 4.5, fixture + " token placeholder");
        contrast(p.fieldStroke, p.field, 3.0, fixture + " token outline");
        contrast(p.fieldStroke, p.sheet, 3.0, fixture + " token outline against sheet");
        contrast(p.pasteText, p.paste, 4.5, fixture + " paste");
        contrast(p.pasteText, p.pastePressed, 4.5, fixture + " paste pressed");
        contrast(p.buttonText, p.button, 4.5, fixture + " submit label/spinner");
        contrast(p.buttonText, p.buttonPressed, 4.5, fixture + " submit label/spinner pressed");
        contrast(p.button, p.sheet, 3.0, fixture + " submit boundary");
        contrast(p.buttonPressed, p.sheet, 3.0, fixture + " submit boundary pressed");
        contrast(p.badgeIcon, p.badge, 3.0, fixture + " icon");
        require(p.field != p.sheet && p.button != p.buttonPressed && p.paste != p.pastePressed,
                fixture + " distinct paint states");
    }

    public static void main(String[] args) {
        require(ColgramThemePolicy.contrast(0xffffffff, 0xff000000) == 21.0, "black/white");
        require(ColgramThemePolicy.contrast(0x00ffffff, 0xff000000) == 1.0, "transparent ink");

        ColgramThemePolicy.BotPalette light = ColgramThemePolicy.forBotSheet(
                0xffffffff, 0xffffffff, 0xff212121, 0xff979797, 0xff2ca5e0, 0xffffffff, false);
        sheet(light, "light");
        require(light.sheet == 0xffffffff && light.title == 0xff212121, "honors readable light theme");

        ColgramThemePolicy.BotPalette dark = ColgramThemePolicy.forBotSheet(
                0xff1c2732, 0xff202c39, 0xfff4f7fb, 0xffb0bac8, 0xffffffff, 0xffffffff, false);
        sheet(dark, "reported dark regression");
        require(dark.sheet == 0xff202c39 && dark.title == 0xfff4f7fb, "honors readable dark theme");
        require(dark.button != 0xffffffff, "whitened accent is not a branded surface");

        ColgramThemePolicy.BotPalette cyber = ColgramThemePolicy.forBotSheet(
                0xff0e0f12, 0xffffffff, 0xffeeeeee, 0xff9aa0a6, 0xffeeeeee, 0xffffffff, true);
        sheet(cyber, "cyber with missing surface");
        require(cyber.sheet == 0xff16181e && cyber.button == 0xffff3344, "cyber keeps red accent and dark sheet");

        sheet(ColgramThemePolicy.forBotSheet(
                0xff131b24, 0x80222f39, 0x80ffffff, 0xff121212, 0xff01050a, 0xff000000, false),
                "translucent/incomplete night palette");

        for (int i = 0; i <= 255; i += 5) {
            int bg = 0xff000000 | (i * 0x010101);
            int opposite = 0xff000000 | ((255 - i) * 0x010101);
            sheet(ColgramThemePolicy.forBotSheet(bg, bg, bg, bg, 0xff762699, bg, false),
                    "custom gray " + i);
            ColgramThemePolicy.HintPalette hint = ColgramThemePolicy.forStoryHint(bg, opposite, opposite);
            contrast(hint.text, hint.background, 4.5, "story copy " + i);
            contrast(hint.link, hint.background, 4.5, "premium story link " + i);
            contrast(0x7dffffff, hint.background, 3.0, "stock close icon " + i);
        }
        ColgramThemePolicy.HintPalette original = ColgramThemePolicy.forStoryHint(
                0xffffffff, 0xff272f38, 0xff85caff);
        require(original.background == 0xff272f38 && original.link == 0xff85caff, "honors usable hint colors");
        System.out.println("ColgramThemePolicyTest: " + checks + " checks PASS");
    }
}
