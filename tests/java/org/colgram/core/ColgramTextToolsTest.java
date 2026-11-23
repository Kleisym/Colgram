package org.colgram.core;

/** Unit tests for ColgramTextTools extended message editing helpers. */
public final class ColgramTextToolsTest {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void testRegexReplace() {
        ColgramTextTools.RegexResult r = ColgramTextTools.applyRegexReplace(
                "Order #123 and Order #456", "Order #(\\d+)", "ID-$1", true);
        check("ID-123 and ID-456".equals(r.text), "regex replacement with group failed: " + r.text);
        check(r.matches == 2, "expected 2 matches, got " + r.matches);
    }

    private static void testCaseCycle() {
        String s1 = ColgramTextTools.cycleCase("hello world");
        check("HELLO WORLD".equals(s1), "lower -> upper failed: " + s1);
        String s2 = ColgramTextTools.cycleCase(s1);
        check("Hello World".equals(s2), "upper -> title failed: " + s2);
        String s3 = ColgramTextTools.cycleCase(s2);
        check("hello world".equals(s3), "title -> lower failed: " + s3);
    }

    private static void testKeyboardLayoutSwap() {
        String ru = ColgramTextTools.swapKeyboardLayout("ghbdtn vbh");
        check("привет мир".equals(ru), "En->Ru failed: " + ru);
        String en = ColgramTextTools.swapKeyboardLayout("руддщ цщкдв");
        check("hello world".equals(en), "Ru->En failed: " + en);
    }

    private static void testStripInvisible() {
        String dirty = "Привет\u200B  мир\uFEFF\nВторая\u202E   строка\u00AD";
        String clean = ColgramTextTools.stripInvisibleChars(dirty);
        check("Привет мир\nВторая строка".equals(clean), "stripInvisible failed: " + clean);
    }

    public static void main(String[] args) {
        testRegexReplace();
        testCaseCycle();
        testKeyboardLayoutSwap();
        testStripInvisible();
        System.out.println("ColgramTextToolsTest: PASS");
    }
}
