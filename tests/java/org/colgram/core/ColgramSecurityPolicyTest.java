package org.colgram.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-JVM unit tests for ColgramSecurityPolicy URL tracker sanitization,
 * homograph/userinfo inspection, WebView UA cloaking, log redaction, and scoring.
 */
public class ColgramSecurityPolicyTest {

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    private static void checkEq(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    public static void testSanitizeTrackingUrl() {
        ColgramSecurityPolicy.SanitizedUrlResult res = ColgramSecurityPolicy.sanitizeTrackingUrl(
                "https://example.com/watch?v=dQw4w9WgXcQ&utm_source=tg&fbclid=12345&si=abc#t=42"
        );
        checkEq("https://example.com/watch?v=dQw4w9WgXcQ#t=42", res.url, "preserves functional param and fragment");
        checkEq(3, res.strippedCount, "counts all stripped tracking params");

        ColgramSecurityPolicy.SanitizedUrlResult onlyTrackers = ColgramSecurityPolicy.sanitizeTrackingUrl(
                "https://shop.example/item?utm_campaign=fall&gclid=xyz&yclid=99"
        );
        checkEq("https://shop.example/item", onlyTrackers.url, "removes trailing ? when all query params stripped");
        checkEq(3, onlyTrackers.strippedCount, "counts all 3 stripped trackers");

        ColgramSecurityPolicy.SanitizedUrlResult tgDeepLink = ColgramSecurityPolicy.sanitizeTrackingUrl(
                "tg://resolve?domain=colgram&start=utm_source"
        );
        checkEq("tg://resolve?domain=colgram&start=utm_source", tgDeepLink.url, "ignores non-http(s) links");
        checkEq(0, tgDeepLink.strippedCount, "does not modify tg:// deep links");
    }

    public static void testInspectUrlThreats() {
        ColgramSecurityPolicy.UrlThreatReport clean = ColgramSecurityPolicy.inspectUrl(
                "https://core.telegram.org/api?utm_medium=share", true
        );
        check(!clean.isCleartextHttp, "https not flagged as cleartext");
        check(!clean.isPunycodeHomograph, "ascii domain not flagged as punycode");
        check(!clean.hasUserinfoTrick, "normal URL has no userinfo trick");
        check(!clean.hasHighRiskWarning(), "clean URL has no high risk warning");
        checkEq(1, clean.strippedTrackers, "stripped 1 tracker during inspect");
        checkEq("https://core.telegram.org/api", clean.sanitizedUrl, "sanitized URL returned");

        ColgramSecurityPolicy.UrlThreatReport loopbackHttp = ColgramSecurityPolicy.inspectUrl(
                "http://127.0.0.1:9876/status", true
        );
        check(!loopbackHttp.isCleartextHttp, "loopback HTTP exempt from cleartext flag");

        ColgramSecurityPolicy.UrlThreatReport remoteHttp = ColgramSecurityPolicy.inspectUrl(
                "http://example.org/login", true
        );
        check(remoteHttp.isCleartextHttp, "remote HTTP flagged as cleartext");

        ColgramSecurityPolicy.UrlThreatReport phish = ColgramSecurityPolicy.inspectUrl(
                "https://telegram.org@xn--pple-43d.com/auth?si=track", true
        );
        check(phish.isPunycodeHomograph, "detects xn-- punycode domain");
        check(phish.hasUserinfoTrick, "detects userinfo@host spoofing");
        check(phish.hasHighRiskWarning(), "punycode + userinfo produces high risk warning");
        checkEq(1, phish.strippedTrackers, "strips si tracker on suspicious link too");
    }

    public static void testCloakWebViewUserAgent() {
        String rawUa = "Mozilla/5.0 (Linux; Android 15; SM-S928B Build/AP3A.240905.015.A2; wv) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36";

        String cloakedBotUa = ColgramSecurityPolicy.buildCloakedWebViewUserAgent(
                rawUa,
                "Pixel 9 Pro",
                "Android 14 (SDK 34)",
                true,
                true,
                "12.10.6-colgram.20261118-1300",
                "HIGH"
        );
        check(!cloakedBotUa.contains("SM-S928B"), "real Samsung model stripped from UA");
        check(!cloakedBotUa.contains("AP3A"), "real build fingerprint stripped from UA");
        check(!cloakedBotUa.contains("; wv)"), "WebView wv token stripped from UA");
        check(!cloakedBotUa.contains("Version/4.0"), "Version/4.0 stripped from UA");
        check(cloakedBotUa.contains("(Linux; Android 14; K)"), "generic Android 14 reduced token injected");
        check(cloakedBotUa.contains("Telegram-Android/12.10.6 (Pixel 9 Pro; Android 14; SDK 34; HIGH)"),
                "spoofed bot tag uses clean Telegram version without local stamp");

        String cloakedBrowserUa = ColgramSecurityPolicy.buildCloakedWebViewUserAgent(
                rawUa,
                "Pixel 9 Pro",
                "Android 14 (SDK 34)",
                true,
                false,
                "12.10.6",
                "HIGH"
        );
        check(!cloakedBrowserUa.contains("Telegram-Android"), "browser mode does not append Telegram-Android tag");
        check(!cloakedBrowserUa.contains("SM-S928B"), "browser mode cloaks device model");

        String uncloakedUa = ColgramSecurityPolicy.buildCloakedWebViewUserAgent(
                rawUa,
                "Pixel 9 Pro",
                "Android 14 (SDK 34)",
                false,
                false,
                "12.10.6",
                "HIGH"
        );
        check(uncloakedUa.contains("SM-S928B"), "when cloakEnabled=false original device model is preserved");
        check(!uncloakedUa.contains("Pixel 9 Pro"), "when cloakEnabled=false spoof model is not injected");
    }

    public static void testRedactSensitiveLogs() {
        String noisy = "GET https://api.telegram.org/bot123456789:AAH_secretBotTokenValue12345678/getMe "
                + "token=987654321:BBG_standaloneToken1234567890 "
                + "Header Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig phone=+79991234567";
        String redacted = ColgramSecurityPolicy.redactSensitiveLogs(noisy);
        check(!redacted.contains("AAH_secretBotTokenValue12345678"), "bot token secret removed");
        check(redacted.contains("123456789:<REDACTED_TOKEN>"), "bot id kept with redacted marker");
        check(!redacted.contains("BBG_standaloneToken1234567890"), "standalone bot token secret removed");
        check(redacted.contains("987654321:<REDACTED_TOKEN>"), "standalone bot id kept with redacted marker");
        check(!redacted.contains("eyJhbGciOiJIUzI1NiJ9"), "bearer token removed");
        check(redacted.contains("<REDACTED_BEARER>"), "bearer marker added");
        check(!redacted.contains("79991234567"), "phone digits removed");
        check(redacted.contains("+<REDACTED_PHONE>"), "phone marker added");
    }

    public static void testScoreComputation() {
        checkEq(100, ColgramSecurityPolicy.computeScorePercent(Collections.<ColgramSecurityPolicy.AuditFinding>emptyList()),
                "empty findings defaults to 100");
        List<ColgramSecurityPolicy.AuditFinding> mix = Arrays.asList(
                new ColgramSecurityPolicy.AuditFinding("a", "net", "t1", "d1", ColgramSecurityPolicy.STATUS_SEALED),
                new ColgramSecurityPolicy.AuditFinding("b", "net", "t2", "d2", ColgramSecurityPolicy.STATUS_AUTO_FIXED),
                new ColgramSecurityPolicy.AuditFinding("c", "net", "t3", "d3", ColgramSecurityPolicy.STATUS_WARNING),
                new ColgramSecurityPolicy.AuditFinding("d", "net", "t4", "d4", ColgramSecurityPolicy.STATUS_SEALED)
        );
        checkEq(75, ColgramSecurityPolicy.computeScorePercent(mix), "3 closed/fixed out of 4 = 75%");
    }

    public static void main(String[] args) {
        testSanitizeTrackingUrl();
        testInspectUrlThreats();
        testCloakWebViewUserAgent();
        testRedactSensitiveLogs();
        testScoreComputation();
        System.out.println("ColgramSecurityPolicyTest: PASS");
    }
}
