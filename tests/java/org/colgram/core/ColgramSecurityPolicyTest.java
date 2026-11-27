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

    public static void testWebViewSpoofJs() {
        checkEq("", ColgramSecurityPolicy.buildWebViewSpoofJs("Google Pixel 9 Pro", "SDK 35 (Android 15)", false),
                "disabled cloak returns empty JS");

        String androidJs = ColgramSecurityPolicy.buildWebViewSpoofJs("Samsung Galaxy S24 Ultra", "SDK 34 (Android 14)", true);
        checkEq(true, androidJs.contains("Samsung Galaxy S24 Ultra"), "Android JS includes spoof model");
        checkEq(true, androidJs.contains("getHighEntropyValues"), "Android JS hooks userAgentData high-entropy values");
        checkEq(true, androidJs.contains("Linux armv8l"), "Android JS spoofs platform");
        checkEq(true, androidJs.contains("WebGLRenderingContext") && androidJs.contains("WebGL2RenderingContext"),
                "Android JS masks WebGL renderer");

        String legacyIosJs = ColgramSecurityPolicy.buildWebViewSpoofJs("iPhone 15 Pro Max", "iOS 17.6.1", true);
        checkEq(true, legacyIosJs.contains("Google Pixel 8 Pro"), "legacy iPhone config normalizes coherently to Android device on Android build");
        checkEq(true, legacyIosJs.contains("Linux armv8l"), "legacy iPhone config keeps coherent Android Linux armv8l platform");
        checkEq(false, legacyIosJs.contains("iPhone"), "JS shim never mixes iPhone tokens on Android build");
        checkEq(true, legacyIosJs.contains("37446") && legacyIosJs.contains("ANGLE (ARM, Immortalis-G715, OpenGL ES 3.2)"),
                "normalized JS masks WebGL unmasked renderer");
        checkEq(true, androidJs.contains("getTimezoneOffset") && androidJs.contains("UTC"),
                "WebView spoof JS normalizes timezone to UTC");
        checkEq(true, androidJs.contains("RTCPeerConnection") && androidJs.contains("webkitRTCPeerConnection"),
                "WebView spoof JS neutralizes WebRTC IP leak");
    }

    public static void testIdempotentUserAgent() {
        String base = "Mozilla/5.0 (Linux; Android 13; RealDevice) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
        String onceBrowser = ColgramSecurityPolicy.buildCloakedWebViewUserAgent(
                base, "Google Pixel 9 Pro", "SDK 35 (Android 15)", true, false, "12.10.6", "HIGH");
        String twiceBrowser = ColgramSecurityPolicy.buildCloakedWebViewUserAgent(
                onceBrowser, "Google Pixel 9 Pro", "SDK 35 (Android 15)", true, false, "12.10.6", "HIGH");
        checkEq(true, !onceBrowser.contains("RealDevice"), "browser cloaked UA strips real model: " + onceBrowser);
        checkEq(onceBrowser, twiceBrowser, "browser buildCloakedWebViewUserAgent must be idempotent");

        String botBase = base + " Telegram-Android/12.10.6 (RealVendor RealDevice; Android 13; SDK 33; AVERAGE)";
        String onceBot = ColgramSecurityPolicy.buildCloakedWebViewUserAgent(
                botBase, "Google Pixel 9 Pro", "SDK 35 (Android 15)", true, true, "12.10.6", "HIGH");
        String twiceBot = ColgramSecurityPolicy.buildCloakedWebViewUserAgent(
                onceBot, "Google Pixel 9 Pro", "SDK 35 (Android 15)", true, true, "12.10.6", "HIGH");
        checkEq(true, onceBot.contains("Google Pixel 9 Pro"), "bot cloaked UA contains spoof model: " + onceBot);
        checkEq(true, !onceBot.contains("RealDevice"), "bot cloaked UA strips real model: " + onceBot);
        checkEq(onceBot, twiceBot, "bot buildCloakedWebViewUserAgent must be idempotent");
    }

    public static void testColgramCloakProfiles() {
        checkEq(true, ColgramCloak.findPresetForModel("iPhone 15 Pro Max") == null,
                "iPhone preset removed for coherent Android APP_ID");
        ColgramCloak.DeviceProfile s25 = ColgramCloak.findPresetForModel("Samsung Galaxy S25 Ultra");
        checkEq(true, s25 != null && "samsung".equals(s25.manufacturer),
                "coherent Android flagship preset Samsung Galaxy S25 Ultra present");
        checkEq(true, ColgramCloak.getSystemVersionForModel("Google Pixel 8 Pro", "iOS 17.6.1").contains("Android"),
                "Android preset ignores stray iOS version");
        String pixelSys = ColgramCloak.getSystemVersionForModel("Google Pixel 9 Pro", "SDK 34 (Android 14)");
        checkEq(true, pixelSys.contains("Android 15"), "Pixel 9 Pro pairs coherent Android 15 version");

        ColgramCloak.DeviceProfile preset = ColgramCloak.findPresetForModel("Google Pixel 9 Pro");
        checkEq(true, preset != null && "Google".equals(preset.manufacturer), "Pixel 9 Pro preset manufacturer");

        java.util.Map<String, String> params = ColgramCloak.getCloakedConnectionParams(
                "RealHardwareModel", "Android 13", "12.10.6", "ru");
        checkEq(true, !"RealHardwareModel".equals(params.get("device_model")),
                "default cloak masks real device model");
        checkEq(true, params.get("manufacturer") != null && !params.get("manufacturer").isEmpty(),
                "cloaked connection params include manufacturer");
        checkEq(true, params.get("system_lang_code") != null && !params.get("system_lang_code").isEmpty(),
                "cloaked connection params include system_lang_code");

        java.util.Map<String, String> strippedParams = ColgramCloak.getCloakedConnectionParams(
                "RealHardwareModel", "Android 13", "12.10.6-colgram.20261114", "ru");
        checkEq("12.10.6", strippedParams.get("app_version"),
                "cloaked connection params strip -colgram suffix from app_version");
    }

    public static void main(String[] args) {
        testSanitizeTrackingUrl();
        testInspectUrlThreats();
        testCloakWebViewUserAgent();
        testWebViewSpoofJs();
        testIdempotentUserAgent();
        testColgramCloakProfiles();
        testRedactSensitiveLogs();
        testScoreComputation();
        System.out.println("ColgramSecurityPolicyTest: PASS");
    }
}
