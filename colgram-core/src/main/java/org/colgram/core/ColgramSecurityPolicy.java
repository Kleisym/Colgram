package org.colgram.core;

import java.net.IDN;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java security/privacy audit policy, URL tracker sanitizer, WebView User-Agent cloaker,
 * and log secret redactor for Colgram's built-in Defender Bot ("Бот-защитник Colgram").
 *
 * Kept free of Android framework dependencies so every rule runs under host JVM unit tests
 * as well as on device.
 */
public final class ColgramSecurityPolicy {

    public static final int STATUS_SEALED = 0;
    public static final int STATUS_AUTO_FIXED = 1;
    public static final int STATUS_WARNING = 2;

    private static final Set<String> TRACKING_PARAMS = new HashSet<>(Arrays.asList(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "utm_id", "utm_source_platform", "utm_creative_format", "utm_marketing_tactic",
            "fbclid", "gclid", "dclid", "gbraid", "wbraid", "yclid", "ysclid", "_openstat",
            "mc_cid", "mc_eid", "igshid", "igsh", "si", "ref_src", "ref_url", "spm",
            "wickedid", "twclid", "ttclid", "oly_anon_id", "oly_enc_id", "__s", "vero_id"
    ));

    private static final Pattern BOT_TOKEN_PATTERN = Pattern.compile("(?<![0-9])(\\d{6,14}):([A-Za-z0-9_-]{20,64})(?![A-Za-z0-9_-])");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(Authorization\\s*[:=]\\s*Bearer\\s+)([A-Za-z0-9._~+/-]+=*)");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])(\\+\\d{7,15})(?![A-Za-z0-9_])");

    public static final class SanitizedUrlResult {
        public final String url;
        public final int strippedCount;

        public SanitizedUrlResult(String url, int strippedCount) {
            this.url = url;
            this.strippedCount = strippedCount;
        }
    }

    public static final class UrlThreatReport {
        public final boolean isCleartextHttp;
        public final boolean isPunycodeHomograph;
        public final boolean hasUserinfoTrick;
        public final int strippedTrackers;
        public final String sanitizedUrl;

        public UrlThreatReport(boolean isCleartextHttp, boolean isPunycodeHomograph,
                               boolean hasUserinfoTrick, int strippedTrackers, String sanitizedUrl) {
            this.isCleartextHttp = isCleartextHttp;
            this.isPunycodeHomograph = isPunycodeHomograph;
            this.hasUserinfoTrick = hasUserinfoTrick;
            this.strippedTrackers = strippedTrackers;
            this.sanitizedUrl = sanitizedUrl;
        }

        public boolean hasHighRiskWarning() {
            return isPunycodeHomograph || hasUserinfoTrick;
        }
    }

    public static final class AuditFinding {
        public final String id;
        public final String category;
        public final String title;
        public final String detail;
        public final int status;

        public AuditFinding(String id, String category, String title, String detail, int status) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.detail = detail;
            this.status = status;
        }

        public String statusBadge() {
            if (status == STATUS_SEALED) return "ЗАКРЫТО";
            if (status == STATUS_AUTO_FIXED) return "ИСПРАВЛЕНО АВТО";
            return "ОТКРЫТА ДЫРА";
        }
    }

    private ColgramSecurityPolicy() {}

    /**
     * Strips tracking query parameters (UTM, fbclid, gclid, yclid, si, igshid, etc.)
     * while preserving functional parameters (like `?start=...`, `?v=...`, `?q=...`) and fragment.
     */
    public static SanitizedUrlResult sanitizeTrackingUrl(String rawUrl) {
        if (rawUrl == null) return new SanitizedUrlResult("", 0);
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) return new SanitizedUrlResult(trimmed, 0);

        String lowerScheme = trimmed.toLowerCase(Locale.ROOT);
        if (!lowerScheme.startsWith("http://") && !lowerScheme.startsWith("https://")) {
            return new SanitizedUrlResult(trimmed, 0);
        }

        int hashIdx = trimmed.indexOf('#');
        String fragment = hashIdx >= 0 ? trimmed.substring(hashIdx) : "";
        String beforeHash = hashIdx >= 0 ? trimmed.substring(0, hashIdx) : trimmed;

        int qIdx = beforeHash.indexOf('?');
        if (qIdx < 0 || qIdx == beforeHash.length() - 1) {
            return new SanitizedUrlResult(trimmed, 0);
        }

        String base = beforeHash.substring(0, qIdx);
        String query = beforeHash.substring(qIdx + 1);
        String[] pairs = query.split("&");
        List<String> kept = new ArrayList<>(pairs.length);
        int stripped = 0;

        for (String pair : pairs) {
            if (pair == null || pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String keyLower = rawKey.toLowerCase(Locale.ROOT).trim();
            if (isTrackingParameter(keyLower)) {
                stripped++;
            } else {
                kept.add(pair);
            }
        }

        if (stripped == 0) {
            return new SanitizedUrlResult(trimmed, 0);
        }
        StringBuilder out = new StringBuilder(base);
        if (!kept.isEmpty()) {
            out.append('?');
            for (int i = 0; i < kept.size(); i++) {
                if (i > 0) out.append('&');
                out.append(kept.get(i));
            }
        }
        out.append(fragment);
        return new SanitizedUrlResult(out.toString(), stripped);
    }

    private static boolean isTrackingParameter(String keyLower) {
        if (keyLower.isEmpty()) return false;
        if (TRACKING_PARAMS.contains(keyLower)) return true;
        return keyLower.startsWith("utm_");
    }

    /** Inspects a link for homograph punycode domains, userinfo deception, plaintext HTTP, and trackers. */
    public static UrlThreatReport inspectUrl(String rawUrl, boolean stripTrackers) {
        SanitizedUrlResult res = stripTrackers
                ? sanitizeTrackingUrl(rawUrl)
                : new SanitizedUrlResult(rawUrl == null ? "" : rawUrl.trim(), 0);
        String clean = res.url;
        boolean cleartext = false;
        boolean punycode = false;
        boolean userinfo = false;
        try {
            URI uri = URI.create(clean);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getRawAuthority();
            if ("http".equals(scheme)) {
                String actualHost = uri.getHost();
                if (actualHost != null && !ColgramDohResolver.isLoopbackHost(actualHost)) {
                    cleartext = true;
                }
            }
            if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isEmpty()) {
                userinfo = true;
            }
            if (host != null) {
                int at = host.lastIndexOf('@');
                String domain = at >= 0 ? host.substring(at + 1) : host;
                int colon = domain.lastIndexOf(':');
                if (colon > 0 && !domain.startsWith("[")) {
                    domain = domain.substring(0, colon);
                }
                String lowerDomain = domain.toLowerCase(Locale.ROOT);
                if (lowerDomain.contains("xn--")) {
                    punycode = true;
                } else {
                    String ascii = IDN.toASCII(domain).toLowerCase(Locale.ROOT);
                    if (ascii.contains("xn--")) {
                        punycode = true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new UrlThreatReport(cleartext, punycode, userinfo, res.strippedCount, clean);
    }

    /**
     * Builds a hardware-cloaked WebView User-Agent string so Mini Apps and in-app web pages
     * cannot fingerprint the real Build.MANUFACTURER / Build.MODEL / OS build.
     */
    public static String buildCloakedWebViewUserAgent(
            String originalUa,
            String spoofModel,
            String spoofSystemVersion,
            boolean cloakEnabled,
            boolean appendTelegramBotTag,
            String appVersionName,
            String perfClass) {
        String base = originalUa == null ? "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36" : originalUa;
        // Make idempotent when re-invoked cleanly by multiple WebView setup hooks.
        base = base.replaceAll("\\s+Telegram-(?:Android|iOS)/[^)]*\\)", "");
        base = base.replace("; wv)", ")");
        base = base.replaceAll("Version/[\\d.]+\\s*", "");

        String modelToken = (spoofModel == null || spoofModel.trim().isEmpty()) ? "Google Pixel 8 Pro" : spoofModel.trim();
        // Keep Android WebView UA strictly coherent with Android WebKit/Chromium engine (never mix iPhone OS + Telegram-Android).
        if (modelToken.toLowerCase(Locale.US).contains("iphone")
                || modelToken.toLowerCase(Locale.US).contains("ipad")) {
            modelToken = "Google Pixel 8 Pro";
        }
        String androidVer = extractAndroidRelease(spoofSystemVersion);
        String sdkNum = extractSdkInt(spoofSystemVersion);

        if (cloakEnabled) {
            base = base.replaceAll("\\(Linux;\\s*Android[^)]*\\)", "(Linux; Android " + androidVer + "; K)");
        }

        if (appendTelegramBotTag) {
            String ver = (appVersionName == null || appVersionName.trim().isEmpty()) ? "12.10.6" : appVersionName.trim();
            int colgramIdx = ver.indexOf("-colgram.");
            if (colgramIdx > 0) ver = ver.substring(0, colgramIdx);
            String perf = (perfClass == null || perfClass.trim().isEmpty()) ? "HIGH" : perfClass.trim();
            base = base + " Telegram-Android/" + ver + " (" + modelToken + "; Android " + androidVer + "; SDK " + sdkNum + "; " + perf + ")";
        }
        return base;
    }

    /**
     * Generates a safe JS shim executed at document start to cloak Chromium Client Hints
     * (`navigator.userAgentData.getHighEntropyValues`), `navigator.platform`, CPU/RAM hints, and
     * WebGL GPU renderer strings inside Mini Apps and in-app WebViews.
     */
    public static String buildWebViewSpoofJs(String spoofModel, String spoofSystemVersion, boolean cloakEnabled) {
        if (!cloakEnabled) return "";
        String model = (spoofModel == null || spoofModel.trim().isEmpty()) ? "Google Pixel 8 Pro" : spoofModel.trim();
        if (model.toLowerCase(Locale.US).contains("iphone")
                || model.toLowerCase(Locale.US).contains("ipad")) {
            model = "Google Pixel 8 Pro";
        }
        String escModel = jsEscape(model);
        String osVer = jsEscape(extractAndroidRelease(spoofSystemVersion));
        String platform = "Android";
        String navPlatform = "Linux armv8l";
        return "(function(){try{"
                + "if(window.__colgramCloakApplied)return;window.__colgramCloakApplied=true;"
                + "var m='" + escModel + "',pv='" + osVer + "',pl='" + platform + "';"
                + "try{Object.defineProperty(navigator,'platform',{get:function(){return '" + navPlatform + "';},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'hardwareConcurrency',{get:function(){return 8;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'deviceMemory',{get:function(){return 8;},configurable:true});}catch(e){}"
                + "try{var uad={brands:[{brand:'Chromium',version:'130'},{brand:'Not_A Brand',version:'24'}],mobile:true,platform:pl,"
                + "getHighEntropyValues:function(h){return Promise.resolve({architecture:'arm',bitness:'64',brands:this.brands,mobile:true,model:m,platform:pl,platformVersion:pv,uaFullVersion:'130.0.0.0'});},"
                + "toJSON:function(){return{brands:this.brands,mobile:true,platform:pl};}};"
                + "Object.defineProperty(navigator,'userAgentData',{get:function(){return uad;},configurable:true});}catch(e){}"
                + "var maskGl=function(p){if(!p||!p.prototype||!p.prototype.getParameter)return;"
                + "var orig=p.prototype.getParameter;p.prototype.getParameter=function(k){"
                + "if(k===37445)return 'Google Inc. (ARM)';"
                + "if(k===37446)return 'ANGLE (ARM, Immortalis-G715, OpenGL ES 3.2)';"
                + "return orig.apply(this,arguments);};};"
                + "maskGl(window.WebGLRenderingContext);maskGl(window.WebGL2RenderingContext);"
                + "}catch(e){}})();";
    }

    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "");
    }

    private static String extractIosRelease(String spoofSys) {
        if (spoofSys == null) return "17.6.1";
        Matcher m = Pattern.compile("iOS\\s*(\\d+(?:\\.\\d+)*)", Pattern.CASE_INSENSITIVE).matcher(spoofSys);
        return m.find() ? m.group(1) : "17.6.1";
    }

    private static String extractAndroidRelease(String spoofSys) {
        if (spoofSys == null) return "14";
        Matcher m = Pattern.compile("Android\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE).matcher(spoofSys);
        return m.find() ? m.group(1) : "14";
    }

    private static String extractSdkInt(String spoofSys) {
        if (spoofSys == null) return "34";
        Matcher m = Pattern.compile("SDK\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(spoofSys);
        return m.find() ? m.group(1) : "34";
    }

    /** Redacts Telegram bot tokens, Bearer tokens, and international phone numbers from diagnostic/crash logs. */
    public static String redactSensitiveLogs(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        String out = BOT_TOKEN_PATTERN.matcher(raw).replaceAll("$1:<REDACTED_TOKEN>");
        out = BEARER_PATTERN.matcher(out).replaceAll("$1<REDACTED_BEARER>");
        out = PHONE_PATTERN.matcher(out).replaceAll("+<REDACTED_PHONE>");
        return out;
    }

    /** Computes an integer security posture percentage (0..100) from audit findings. */
    public static int computeScorePercent(List<AuditFinding> findings) {
        if (findings == null || findings.isEmpty()) return 100;
        int closed = 0;
        for (AuditFinding f : findings) {
            if (f != null && f.status != STATUS_WARNING) {
                closed++;
            }
        }
        return Math.round((closed * 100.0f) / findings.size());
    }
}
