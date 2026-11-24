import org.colgram.core.ColgramThemePolicy;

public final class ColgramThemePolicyTest {
    private static int checks;

    private static void require(boolean passes, String description) {
        checks++;
        if (!passes) throw new AssertionError(description);
    }

    private static void contrast(int ink, int surface, double minimum, String name) {
        double observed = ColgramThemePolicy.contrast(ink, surface);
        require(observed >= minimum, name + " contrast was " + observed + " (ink="
                + String.format("#%08x", ink) + ", bg=" + String.format("#%08x", surface) + ")");
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

            // Exhaustive 0..255 grey-on-grey sweep across every surface helper!
            int sameGrey = bg;
            int fixedBody = ColgramThemePolicy.ensureReadableOnSurface(sameGrey, bg, 0xfff4f7fb, 0xff14181e, 7.0);
            contrast(fixedBody, bg, 4.5, "primary text on grey " + i);
            int fixedSec = ColgramThemePolicy.ensureReadableOnSurface(sameGrey, bg, 0xffcdd4e0, 0xff3b4350, 4.5);
            contrast(fixedSec, bg, 4.5, "secondary text on grey " + i);
            int fixedReplyPrimary = ColgramThemePolicy.resolveReplyPanelInk(0x64ffffff, bg, true);
            contrast(fixedReplyPrimary, bg, 4.5, "reply panel primary on grey " + i);
            int fixedReplySec = ColgramThemePolicy.resolveReplyPanelInk(0x991b2227, bg, false);
            contrast(fixedReplySec, bg, 4.5, "reply panel glass text on grey " + i);
            int fixedCheck = ColgramThemePolicy.resolveCheckmarkInk(0xff787c80, bg, 0xff7ec8ff, 0xff1565c0);
            contrast(fixedCheck, bg, 4.5, "checkmark ink on grey " + i);
            int fixedOutBubbleInk = ColgramThemePolicy.resolveBubbleInk(0xff7d7f81, bg, 0xffffffff, 0xff14181e, 4.5);
            contrast(fixedOutBubbleInk, bg, 4.5, "out bubble primary/check on grey " + i);
            int fixedOutBubbleSec = ColgramThemePolicy.resolveBubbleInk(0xff7d7f81, bg, 0xe6ffffff, 0xff2b3340, 4.5);
            contrast(fixedOutBubbleSec, bg, 4.5, "out bubble time/reply on grey " + i);
            int fixedAccent = ColgramThemePolicy.ensureAccentReadable(0xff5a6e82, bg, 0xff7ec8ff, 0xff1565c0, 4.5);
            contrast(fixedAccent, bg, 4.5, "accent/reply name on grey " + i);
        }
        ColgramThemePolicy.HintPalette original = ColgramThemePolicy.forStoryHint(
                0xffffffff, 0xff272f38, 0xff85caff);
        require(original.background == 0xff272f38 && original.link == 0xff85caff, "honors usable hint colors");

        // 1. Outgoing saturated purple/blue bubbles (L < 0.55): muddy dark grey-blue timestamps/checks become crisp white
        int purpleBubble = ColgramThemePolicy.averageSurfaceColor(0xff6b52c9, 0xff5a66d8, 0, 0);
        require(ColgramThemePolicy.relativeLuminance(purpleBubble) < 0.55, "purple/blue bubble luminance < 0.55");
        require(ColgramThemePolicy.resolveBubbleInk(0xff354656, purpleBubble, 0xffffffff, 0xff283644, 4.5) == 0xffffffff,
                "outgoing checkmarks forced pure white on saturated bubble");
        require(ColgramThemePolicy.resolveBubbleInk(0xff354656, purpleBubble, 0xe6ffffff, 0xff283644, 4.5) == 0xe6ffffff,
                "outgoing time text forced crisp 0xE6FFFFFF on saturated bubble");

        // 2. Mid-grey & light-grey Aero bubbles must NOT force white ink onto mid-grey/light-grey!
        int[] greyBubbles = {0xff6e747c, 0xff808080, 0xff9a9fa8, 0xffb8b8b8, 0xffe6f2fc, 0xfff0f0f0};
        for (int gb : greyBubbles) {
            int checkInk = ColgramThemePolicy.resolveBubbleInk(0xff8caabd, gb, 0xffffffff, 0xff14181e, 4.5);
            contrast(checkInk, gb, 4.5, "out check on bubble " + String.format("#%08x", gb));
            int timeInk = ColgramThemePolicy.resolveBubbleInk(0xf78caabd, gb, 0xe6ffffff, 0xff2b3340, 4.5);
            contrast(timeInk, gb, 4.5, "out time on bubble " + String.format("#%08x", gb));
            int replyBg = ColgramThemePolicy.compositeOver(0x1f79c4fc, gb);
            int replyMsg = ColgramThemePolicy.resolveBubbleInk(0xff7f8081, replyBg, 0xffeef4fc, 0xff1a2028, 4.5);
            contrast(replyMsg, replyBg, 4.5, "reply quote msg inside bubble " + String.format("#%08x", gb));
            int replyName = ColgramThemePolicy.ensureAccentReadable(0xff79c4fc, replyBg, 0xffffffff, 0xff14181e, 4.5);
            contrast(replyName, replyBg, 4.5, "reply quote name inside bubble " + String.format("#%08x", gb));
        }

        // 3. Real Telegram .attheme grey-on-grey regressions (night, darkblue, day, bluebubbles, arctic, cyber)
        // Low-alpha foregrounds (0x64ffffff, 0x6edcf4ff, 0x991b2227, 0x70ffffff) must boost alpha so contrast >= 4.5!
        int nightPanel = 0xff1e1e1f;
        int darkBluePanel = 0xff212d3b;
        int translucentDarkPanel = 0xc21e1e1f; // BlurredBackgroundColorProviderThemed 76% dark glass!
        int translucentLightPanel = 0xc2f5f6f8;
        int[] panels = {nightPanel, darkBluePanel, translucentDarkPanel, translucentLightPanel, 0xffffffff, 0xff787e88};
        for (int p : panels) {
            int glassReplyText = ColgramThemePolicy.ensureReadableOnSurfaces(0x991b2227, p, p, 0xfff0f4fa, 0xff14181e, 4.5);
            contrast(glassReplyText, p, 4.5, "glass reply text on panel " + String.format("#%08x", p));
            int nightHint = ColgramThemePolicy.resolveReplyPanelInk(0x64ffffff, p, false);
            contrast(nightHint, p, 4.5, "low-alpha 0x64ffffff hint on panel " + String.format("#%08x", p));
            int dbIcon = ColgramThemePolicy.resolveReplyPanelInk(0x6edcf4ff, p, false);
            contrast(dbIcon, p, 4.5, "low-alpha 0x6edcf4ff icon on panel " + String.format("#%08x", p));
            int replyName = ColgramThemePolicy.ensureAccentReadable(0xff62b4f9, p, 0xff7ec8ff, 0xff1565c0, 4.5);
            contrast(replyName, p, 4.5, "reply panel name on panel " + String.format("#%08x", p));
            int replyLine = ColgramThemePolicy.ensureAccentReadable(0x5e171a1d, p, 0xff7ec8ff, 0xff1565c0, 3.2);
            contrast(replyLine, p, 3.2, "darkblue invisible replyPanelLine on panel " + String.format("#%08x", p));
        }

        // 4. Translucent dark background must NEVER be bleached to white!
        int solidDarkGlass = ColgramThemePolicy.solidSurface(0xc21e1e1f);
        require(ColgramThemePolicy.isDarkColor(solidDarkGlass), "translucent dark glass stays dark");
        int onDarkGlass = ColgramThemePolicy.resolveReplyPanelInk(0x64ffffff, 0xc21e1e1f, false);
        require(ColgramThemePolicy.perceivedLuma(onDarkGlass) > 180, "ink on translucent dark glass is light, not dark");
        contrast(onDarkGlass, 0xc21e1e1f, 4.5, "ink contrast on translucent dark glass");

        // 5. Read checkmarks & dialog status icons on Night, Dark Blue, Day, BlueBubbles, Cyber, Mid-Grey
        int[][] dialogChecks = {
                {0xff555555, 0xff181819}, // night mute icon (was 2.47:1!)
                {0xff646464, 0xff181819}, // night pin icon (was 3.16:1!)
                {0xff4e5f6a, 0xff1d2733}, // darkblue mute icon (was 2.29:1!)
                {0xff4c5f6a, 0xff1d2733}, // darkblue clock (was 2.26:1!)
                {0xffb8bcbf, 0xffffffff}, // bluebubbles clock (was 1.9:1!)
                {0x7b96b4c6, 0xffffffff}, // day clock (was 1.45:1!)
                {0xff46aa36, 0xffffffff}, // default green read check (was 3.05:1!)
                {0xff64b5ef, 0xff1d2733}, // darkblue read check
                {0xff787878, 0xff787878}, // mid-grey check on mid-grey
        };
        for (int[] pair : dialogChecks) {
            int fixed = ColgramThemePolicy.resolveCheckmarkInk(pair[0], pair[1], 0xff7ec8ff, 0xff1565c0);
            contrast(fixed, pair[1], 4.5, "dialog check/status " + String.format("#%08x on #%08x", pair[0], pair[1]));
        }

        // 6. Settings gray sections, bottom sheets & pinned top panel
        int grayFooterLight = ColgramThemePolicy.ensureReadableOnSurfaces(
                0xff82878a, 0xffffffff, 0xfff5f5f5, 0xffcdd4e0, 0xff3b4350, 4.5);
        contrast(grayFooterLight, 0xffffffff, 4.5, "light settings gray text on white");
        contrast(grayFooterLight, 0xfff5f5f5, 4.5, "light settings gray text on gray section");

        int grayFooterDark = ColgramThemePolicy.ensureReadableOnSurfaces(
                0xff6a6a6a, 0xff181819, 0xff000000, 0xffcdd4e0, 0xff3b4350, 4.5);
        contrast(grayFooterDark, 0xff181819, 4.5, "night settings gray text on dark window");
        contrast(grayFooterDark, 0xff000000, 4.5, "night settings gray text on black section");

        int dialogGray = ColgramThemePolicy.ensureReadableOnSurfaces(
                0xff999999, 0xffffffff, 0xfff0f0f0, 0xffcdd4e0, 0xff3b4350, 4.5);
        contrast(dialogGray, 0xffffffff, 4.5, "dialog gray text on white sheet");
        contrast(dialogGray, 0xfff0f0f0, 4.5, "dialog gray text on gray sheet");

        int topPanelMsg = ColgramThemePolicy.ensureReadableOnSurface(
                0x64ffffff, 0xff1a1a1b, 0xffe2e8f2, 0xff222a35, 4.5);
        contrast(topPanelMsg, 0xff1a1a1b, 4.5, "pinned top panel message on dark top bar");

        // 7. Muted unread counter badges on dark and light themes must not render dark-on-dark or grey-on-grey
        int resolvedMutedBg = ColgramThemePolicy.resolveMutedBadgeBackground(0xff1b2430, 0xff16181e, true);
        int resolvedBadgeFg = ColgramThemePolicy.resolveUnreadCounterText(0xff354656, 0xffe11d48, resolvedMutedBg, true);
        contrast(resolvedMutedBg, 0xff16181e, 1.85, "muted unread badge separated from dark window");
        contrast(resolvedBadgeFg, resolvedMutedBg, 5.5, "unread count text crisp against muted badge");
        require(resolvedBadgeFg == 0xffffffff, "dark muted badge forces white counter text");

        int lightMutedBg = ColgramThemePolicy.resolveMutedBadgeBackground(0xffcccccc, 0xffffffff, false);
        int lightBadgeFg = ColgramThemePolicy.resolveUnreadCounterText(0xffaaaaaa, 0xff229af0, lightMutedBg, false);
        contrast(lightMutedBg, 0xffffffff, 2.2, "light muted badge separated from white window");
        contrast(lightBadgeFg, lightMutedBg, 5.5, "light unread count text crisp against muted badge");

        System.out.println("ColgramThemePolicyTest: " + checks + " checks PASS");
    }
}
