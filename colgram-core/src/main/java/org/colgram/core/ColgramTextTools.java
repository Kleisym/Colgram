package org.colgram.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java extended text editing utilities for Colgram composer/editor quick actions:
 * regex find/replace, smart case cycling, En<->Ru keyboard layout fixer, and invisible ghost stripping.
 */
public final class ColgramTextTools {

    public static final class RegexResult {
        public final String text;
        public final int matches;

        public RegexResult(String text, int matches) {
            this.text = text;
            this.matches = matches;
        }
    }

    private static final String EN_KEYS =
            "qwertyuiop[]asdfghjkl;'zxcvbnm,.QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>`~";
    private static final String RU_KEYS =
            "йцукенгшщзхъфывапролджэячсмитьбюЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮёЁ";

    private static final Map<Character, Character> EN_TO_RU = new HashMap<>();
    private static final Map<Character, Character> RU_TO_EN = new HashMap<>();

    static {
        int n = Math.min(EN_KEYS.length(), RU_KEYS.length());
        for (int i = 0; i < n; i++) {
            char e = EN_KEYS.charAt(i);
            char r = RU_KEYS.charAt(i);
            EN_TO_RU.put(e, r);
            RU_TO_EN.put(r, e);
        }
    }

    private static final Pattern INVISIBLE_CHARS_PATTERN = Pattern.compile(
            "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF\\u034F\\u00AD\\u061C\\u180E\\u2800]"
    );

    private ColgramTextTools() {}

    /**
     * Runs regex replacement on input with optional case-insensitive flag and returns transformed text + match count.
     * Supports standard Java Matcher group references ($1, $2, etc.) and handles literal fallback cleanly.
     */
    public static RegexResult applyRegexReplace(String input, String patternStr,
                                                String replacement, boolean caseInsensitive) {
        if (input == null) input = "";
        if (patternStr == null || patternStr.isEmpty()) {
            return new RegexResult(input, 0);
        }
        String repl = replacement != null ? replacement : "";
        int flags = caseInsensitive ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0;
        Pattern pattern = Pattern.compile(patternStr, flags);
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        int count = 0;
        while (m.find()) {
            count++;
            try {
                m.appendReplacement(sb, repl);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            }
        }
        m.appendTail(sb);
        return new RegexResult(sb.toString(), count);
    }

    /**
     * Smart case cycler:
     * all-lower -> ALL-UPPER -> Title Case -> all-lower (and mixed -> UPPERCASE or lowercase cleanly).
     */
    public static String cycleCase(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        Locale locale = Locale.getDefault();
        boolean hasLower = false;
        boolean hasUpper = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetter(c)) {
                if (Character.isLowerCase(c)) hasLower = true;
                else if (Character.isUpperCase(c)) hasUpper = true;
            }
        }
        if (!hasLower && !hasUpper) {
            return input;
        }
        if (hasLower && !hasUpper) {
            return input.toUpperCase(locale);
        }
        if (hasUpper && !hasLower) {
            return toTitleCase(input, locale);
        }
        String asTitle = toTitleCase(input, locale);
        if (input.equals(asTitle)) {
            return input.toLowerCase(locale);
        }
        return input.toLowerCase(locale);
    }

    public static String toTitleCase(String input, Locale locale) {
        if (input == null || input.isEmpty()) return "";
        String lower = input.toLowerCase(locale != null ? locale : Locale.getDefault());
        StringBuilder sb = new StringBuilder(lower.length());
        boolean nextUpper = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (nextUpper && Character.isLetter(c)) {
                    sb.append(Character.toUpperCase(c));
                } else {
                    sb.append(c);
                }
                nextUpper = false;
            } else {
                sb.append(c);
                nextUpper = true;
            }
        }
        return sb.toString();
    }

    /**
     * Auto-detects whether text contains more Latin or Cyrillic characters and flips physical QWERTY <-> ЙЦУКЕН layout.
     */
    public static String swapKeyboardLayout(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        int cyrCount = 0;
        int latCount = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (RU_TO_EN.containsKey(c) && ((c >= '\u0400' && c <= '\u04FF'))) {
                cyrCount++;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latCount++;
            }
        }
        boolean ruToEn = cyrCount > latCount;
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            Character mapped = ruToEn ? RU_TO_EN.get(c) : EN_TO_RU.get(c);
            sb.append(mapped != null ? mapped : c);
        }
        return sb.toString();
    }

    /**
     * Strips zero-width/bidi/ghost invisible formatting characters and collapses non-newline multi-spaces.
     */
    public static String stripInvisibleChars(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        String noGhosts = INVISIBLE_CHARS_PATTERN.matcher(input).replaceAll("");
        String[] lines = noGhosts.split("\n", -1);
        StringBuilder sb = new StringBuilder(noGhosts.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i].replace('\u00A0', ' ').replaceAll("[ \\t]{2,}", " ").trim();
            sb.append(line);
        }
        return sb.toString();
    }
}
