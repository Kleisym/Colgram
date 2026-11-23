package org.colgram.core;

import java.util.Arrays;
import java.util.List;

public final class ColgramTempMailDiscoveryTest {

    public static void main(String[] args) {
        testHtmlToPlainText();
        testExtractOtp();
        testExtractCandidateUrls();
        testBuiltInProviders();
        testProviderSerializationRoundTrip();
        System.out.println("ColgramTempMailDiscoveryTest: PASS");
    }

    private static void testHtmlToPlainText() {
        String html = "<html><head><style>body{color:#fff}</style></head>"
                + "<body><p>Hello,&nbsp;world &amp; friends!</p>"
                + "<div>Code&#58; &#x201C;481920&#x201D; &mdash; verify &laquo;now&raquo;.</div>"
                + "<script>alert('xss')</script></body></html>";
        String plain = ColgramTempMailDiscovery.htmlToPlainText(html);
        assertTrue(!plain.contains("color:#fff"), "style tag must be stripped");
        assertTrue(!plain.contains("alert"), "script tag must be stripped");
        assertTrue(plain.contains("Hello, world & friends!"), "HTML entities must decode: " + plain);
        assertTrue(plain.contains("Code: \u201C481920\u201D \u2014 verify \u00ABnow\u00BB."),
                "numeric/named entities must decode cleanly: " + plain);
        assertEquals("", ColgramTempMailDiscovery.htmlToPlainText(null), "null input returns empty");
    }

    private static void testExtractOtp() {
        assertEquals("739204",
                ColgramTempMailDiscovery.extractOtp("Verification", "Your verification code: 739204 expires soon"),
                "context code should match");
        assertEquals("4821",
                ColgramTempMailDiscovery.extractOtp("Alert", "Copyright 2026. PIN: 4821"),
                "context PIN should beat standalone year");
        assertEquals("591028",
                ColgramTempMailDiscovery.extractOtp("Message", "Sent 2026-11-15. Number is 591028"),
                "6-digit code should beat calendar year");
        assertEquals("9182",
                ColgramTempMailDiscovery.extractOtp("Notice", "Date 2026, ref 9182"),
                "non-year 4-digit code should beat standalone calendar year");
        assertEquals("", ColgramTempMailDiscovery.extractOtp("", "No digits here"), "empty when no digits");
    }

    private static void testExtractCandidateUrls() {
        String sample = "See https://github.com/example/repo and https://api.mail.tm/domains, "
                + "https://tempmail.lol/v2/inbox/create and https://raw.githubusercontent.com/a/b/c.json "
                + "or https://docs.google.com/mail and https://dropmail.me/api/graphql.";
        List<String> urls = ColgramTempMailDiscovery.extractCandidateUrls(sample);
        assertTrue(urls.contains("https://api.mail.tm"), "should keep api.mail.tm: " + urls);
        assertTrue(urls.contains("https://tempmail.lol/v2"), "should trim /inbox/create keeping /v2: " + urls);
        assertTrue(urls.contains("https://dropmail.me"), "should keep mail-related host dropmail.me: " + urls);
        assertTrue(!urls.contains("https://github.com"), "non-provider github.com must be excluded");
        assertTrue(!urls.contains("https://raw.githubusercontent.com"), "raw.githubusercontent.com must be excluded");
        assertTrue(!urls.contains("https://docs.google.com"), "docs.google.com must be excluded");
    }

    private static void testBuiltInProviders() {
        List<ColgramTempMailDiscovery.ProviderSpec> list = ColgramTempMailDiscovery.builtInProviders();
        assertTrue(list.size() >= 6, "expected >= 6 built-in providers");
        boolean hasMailTm = false;
        boolean hasGuerrilla = false;
        boolean hasTempMailLol = false;
        boolean hasTempMailIo = false;
        boolean hasMaildrop = false;
        boolean hasInboxes = false;
        for (ColgramTempMailDiscovery.ProviderSpec p : list) {
            if ("mailtm".equals(p.id) && ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(p.protocol)) hasMailTm = true;
            if ("guerrilla".equals(p.id) && ColgramTempMailDiscovery.PROTOCOL_GUERRILLA.equals(p.protocol)) hasGuerrilla = true;
            if ("tempmail_lol".equals(p.id) && ColgramTempMailDiscovery.PROTOCOL_TEMPMAIL_LOL.equals(p.protocol)) hasTempMailLol = true;
            if ("temp_mail_io".equals(p.id) && ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(p.protocol)) hasTempMailIo = true;
            if ("maildrop".equals(p.id) && ColgramTempMailDiscovery.PROTOCOL_MAILDROP.equals(p.protocol)) hasMaildrop = true;
            if ("inboxes".equals(p.id) && ColgramTempMailDiscovery.PROTOCOL_INBOXES.equals(p.protocol)) hasInboxes = true;
        }
        assertTrue(hasInboxes, "inboxes built-in missing");
        assertTrue(hasMailTm, "mailtm built-in missing");
        assertTrue(hasGuerrilla, "guerrilla built-in missing");
        assertTrue(hasTempMailLol, "tempmail_lol built-in missing");
        assertTrue(hasTempMailIo, "temp_mail_io built-in missing");
        assertTrue(hasMaildrop, "maildrop built-in missing");
        assertTrue(ColgramTempMailDiscovery.GUERRILLA_DOMAINS.length >= 6, "GUERRILLA_DOMAINS populated");

        List<String> gmailAliases = ColgramTempMailDiscovery.generateGmailAliases("Colgram.User@gmail.com", "googlemail.com", 5);
        assertEquals(5, gmailAliases.size(), "expected 5 gmail/googlemail aliases");
        assertTrue(gmailAliases.get(0).endsWith("@googlemail.com"), "preferred domain googlemail.com expected: " + gmailAliases);
        boolean sawGmail = false;
        for (String a : gmailAliases) {
            if (a.endsWith("@gmail.com")) sawGmail = true;
        }
        assertTrue(sawGmail, "expected both @googlemail.com and @gmail.com variants: " + gmailAliases);
    }

    private static void testProviderSerializationRoundTrip() {
        List<ColgramTempMailDiscovery.ProviderSpec> input = Arrays.asList(
                new ColgramTempMailDiscovery.ProviderSpec("p1", "Custom\tName", "https://api.example.com/",
                        ColgramTempMailDiscovery.PROTOCOL_HYDRA, 123L, "custom", true),
                new ColgramTempMailDiscovery.ProviderSpec("p2", "Lol API", "https://api.tempmail.lol",
                        ColgramTempMailDiscovery.PROTOCOL_TEMPMAIL_LOL, 45L, "internet", true)
        );
        String encoded = ColgramTempMailDiscovery.serializeProviders(input);
        List<ColgramTempMailDiscovery.ProviderSpec> decoded = ColgramTempMailDiscovery.deserializeProviders(encoded);
        assertEquals(2, decoded.size(), "decoded size mismatch");
        assertEquals("p1", decoded.get(0).id, "id mismatch");
        assertEquals("Custom Name", decoded.get(0).name, "tab sanitization mismatch");
        assertEquals("https://api.example.com", decoded.get(0).apiBase, "trailing slash trim mismatch");
        assertEquals(123L, decoded.get(0).latencyMs, "latency mismatch");
        assertTrue(decoded.get(0).reachable, "reachable flag must restore from non-negative latency");
        assertEquals("https://api.tempmail.lol", decoded.get(1).apiBase, "second provider base mismatch");
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
