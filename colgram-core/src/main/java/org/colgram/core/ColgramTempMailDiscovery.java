package org.colgram.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-provider TempMail registry, HTML/OTP decoder, and live internet provider auto-discovery.
 *
 * Keeps URL extraction, HTML entity decoding, OTP matching, and provider serialization pure-Java so
 * host JVM tests can verify them without Android stubs.
 */
public final class ColgramTempMailDiscovery {

    public static final String PROTOCOL_EMAILNATOR = "emailnator";
    public static final String PROTOCOL_HYDRA = "hydra";
    public static final String PROTOCOL_GUERRILLA = "guerrilla";
    public static final String PROTOCOL_TEMPMAIL_LOL = "tempmail_lol";
    public static final String PROTOCOL_MAIL_IO = "temp_mail_io";
    public static final String PROTOCOL_INBOXES = "inboxes";
    public static final String PROTOCOL_1SECMAIL = "1secmail";
    public static final String PROTOCOL_MAILDROP = "maildrop";

    public static final String[] GUERRILLA_DOMAINS = {
            "guerrillamailblock.com",
            "guerrillamail.com",
            "guerrillamail.net",
            "guerrillamail.org",
            "sharklasers.com",
            "grr.la",
            "pokemail.net",
            "spam4.me"
    };

    private static final String[] DISCOVERY_FEED_URLS = {
            "https://api.github.com/search/repositories?q=temp+mail+api+free+no-key&sort=stars&per_page=10",
            "https://api.github.com/search/repositories?q=mail.tm+OR+disposable+email+api&sort=updated&per_page=10",
            "https://raw.githubusercontent.com/marcelscruz/public-apis/main/db/resources.json"
    };

    private static final Pattern URL_PATTERN =
            Pattern.compile("https://[A-Za-z0-9.-]+(?:\\.[A-Za-z]{2,})(?::\\d+)?(?:/[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]*)?");
    private static final Pattern NUMERIC_ENTITY_PATTERN =
            Pattern.compile("&#(x?[0-9a-fA-F]{1,6});");
    private static final Pattern OTP_CONTEXT_PATTERN =
            Pattern.compile("(?i)(?:code|код|otp|pin|verify|verification|confirm|confirmation|подтверж|пароль|password|token)[^0-9]{0,24}(\\d{4,8})\\b");
    private static final Pattern OTP_STANDALONE_PATTERN =
            Pattern.compile("\\b(\\d{4,8})\\b");
    private static final Pattern MAIL_KEYWORD_PATTERN =
            Pattern.compile("(?i)(mail|inbox|guerrilla|disposable|trash|drop|temp|burner|secmail|pokemail|sharklasers|inbucket)");

    private static final Set<String> NON_PROVIDER_HOSTS = Set.of(
            "github.com", "raw.githubusercontent.com", "api.github.com", "gist.github.com",
            "img.shields.io", "shields.io", "w3.org", "www.w3.org", "schema.org",
            "google.com", "www.google.com", "docs.google.com", "play.google.com",
            "cloudflare.com", "www.cloudflare.com", "twitter.com", "x.com",
            "t.me", "telegram.org", "core.telegram.org", "youtube.com", "youtu.be",
            "npmjs.com", "www.npmjs.com", "pypi.org", "opensource.org", "mit-license.org",
            "shields.io.", "avatars.githubusercontent.com", "user-images.githubusercontent.com"
    );

    public static final class ProviderSpec {
        public final String id;
        public final String name;
        public final String apiBase;
        public final String protocol;
        public final long latencyMs;
        public final String source;
        public final boolean reachable;

        public ProviderSpec(String id, String name, String apiBase, String protocol,
                            long latencyMs, String source, boolean reachable) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.apiBase = trimTrailingSlash(apiBase == null ? "" : apiBase);
            this.protocol = protocol == null ? PROTOCOL_HYDRA : protocol;
            this.latencyMs = latencyMs;
            this.source = source == null ? "builtin" : source;
            this.reachable = reachable;
        }
    }

    public static final class DiscoveryResult {
        public final List<ProviderSpec> workingProviders;
        public final int probedCandidates;
        public final String reportSummary;

        public DiscoveryResult(List<ProviderSpec> workingProviders, int probedCandidates, String reportSummary) {
            this.workingProviders = workingProviders;
            this.probedCandidates = probedCandidates;
            this.reportSummary = reportSummary;
        }
    }

    private ColgramTempMailDiscovery() {}

    public static List<ProviderSpec> builtInProviders() {
        List<ProviderSpec> list = new ArrayList<>();
        // Ordered fastest/highest-availability free keyless providers first, with native @gmail.com / @googlemail.com support.
        list.add(new ProviderSpec("emailnator", "Gmail / GoogleMail", "https://www.emailnator.com", PROTOCOL_EMAILNATOR, -1, "builtin", false));
        list.add(new ProviderSpec("inboxes", "Inboxes.com", "https://inboxes.com/api/v2", PROTOCOL_INBOXES, -1, "builtin", false));
        list.add(new ProviderSpec("temp_mail_io", "Temp-Mail.io", "https://api.internal.temp-mail.io/api/v3", PROTOCOL_MAIL_IO, -1, "builtin", false));
        list.add(new ProviderSpec("guerrilla", "GuerrillaMail", "https://api.guerrillamail.com/ajax.php", PROTOCOL_GUERRILLA, -1, "builtin", false));
        list.add(new ProviderSpec("maildrop", "Maildrop.cc", "https://api.maildrop.cc/graphql", PROTOCOL_MAILDROP, -1, "builtin", false));
        list.add(new ProviderSpec("mailtm", "Mail.tm", "https://api.mail.tm", PROTOCOL_HYDRA, -1, "builtin", false));
        list.add(new ProviderSpec("mailgw", "Mail.gw", "https://api.mail.gw", PROTOCOL_HYDRA, -1, "builtin", false));
        list.add(new ProviderSpec("tempmail_lol", "TempMail.lol v2", "https://api.tempmail.lol", PROTOCOL_TEMPMAIL_LOL, -1, "builtin", false));
        return list;
    }

    /**
     * Generates valid unique Gmail / GoogleMail dot-trick & plus-tag aliases that route cleanly
     * to a user's base Gmail mailbox without requiring any API key.
     */
    public static List<String> generateGmailAliases(String rawBaseUsername, String preferredDomain, int count) {
        List<String> out = new ArrayList<>();
        if (rawBaseUsername == null) return out;
        String base = rawBaseUsername.trim().toLowerCase(Locale.ROOT);
        int at = base.indexOf('@');
        if (at > 0) base = base.substring(0, at);
        int plus = base.indexOf('+');
        if (plus > 0) base = base.substring(0, plus);
        base = base.replaceAll("[^a-z0-9]", "");
        if (base.length() < 3) return out;

        String dom = ("googlemail.com".equalsIgnoreCase(preferredDomain)) ? "googlemail.com" : "gmail.com";
        String altDom = "gmail.com".equals(dom) ? "googlemail.com" : "gmail.com";
        int target = Math.max(1, Math.min(count, 10));
        long seed = System.currentTimeMillis();
        LinkedHashSet<String> uniq = new LinkedHashSet<>();

        for (int i = 0; i < target * 2 && uniq.size() < target; i++) {
            StringBuilder dotted = new StringBuilder();
            int mask = (int) (((seed >> (i % 8)) ^ (i * 2654435761L)) & 0xFFFF);
            for (int c = 0; c < base.length(); c++) {
                dotted.append(base.charAt(c));
                if (c < base.length() - 1 && ((mask >> (c % 12)) & 1) == 1 && dotted.charAt(dotted.length() - 1) != '.') {
                    dotted.append('.');
                }
            }
            int tagNum = (int) (Math.abs((seed + i * 7919L) % 8999L) + 1000L);
            String chosenDom = (i % 3 == 2) ? altDom : dom;
            String addr = (i % 2 == 0)
                    ? dotted + "+cg" + tagNum + "@" + chosenDom
                    : dotted + "@" + chosenDom;
            uniq.add(addr);
        }
        out.addAll(uniq);
        return out;
    }

    /** Strips CSS/script blocks, converts structural tags to newlines, and decodes HTML entities. */
    public static String htmlToPlainText(String rawHtml) {
        if (rawHtml == null || rawHtml.isEmpty()) return "";
        String cleaned = rawHtml
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<head[^>]*>.*?</head>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|tr|h[1-6]|blockquote)>", "\n")
                .replaceAll("(?i)<td[^>]*>", " ")
                .replaceAll("(?is)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "...")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&copy;", "©")
                .replace("&reg;", "®")
                .replace("&trade;", "™");

        Matcher m = NUMERIC_ENTITY_PATTERN.matcher(cleaned);
        StringBuilder out = new StringBuilder(cleaned.length());
        int last = 0;
        while (m.find()) {
            out.append(cleaned, last, m.start());
            String code = m.group(1);
            try {
                int cp = code.startsWith("x") || code.startsWith("X")
                        ? Integer.parseInt(code.substring(1), 16)
                        : Integer.parseInt(code, 10);
                if (Character.isValidCodePoint(cp)) {
                    out.append(Character.toChars(cp));
                }
            } catch (NumberFormatException ignored) {
                out.append(m.group(0));
            }
            last = m.end();
        }
        out.append(cleaned, last, cleaned.length());
        return out.toString()
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** Extracts an OTP code from email subject/body, preferring explicit verification contexts. */
    public static String extractOtp(String subject, String body) {
        String combined = ((subject == null ? "" : subject) + "\n" + (body == null ? "" : body)).trim();
        if (combined.isEmpty()) return "";

        Matcher context = OTP_CONTEXT_PATTERN.matcher(combined);
        if (context.find()) {
            return context.group(1);
        }

        Matcher plain = OTP_STANDALONE_PATTERN.matcher(combined);
        String fallback = "";
        while (plain.find()) {
            String candidate = plain.group(1);
            if (candidate.length() >= 4 && candidate.length() <= 8) {
                boolean likelyYear = candidate.length() == 4
                        && (candidate.startsWith("19") || candidate.startsWith("20"));
                boolean fallbackIsYear = fallback.length() == 4
                        && (fallback.startsWith("19") || fallback.startsWith("20"));
                if (fallback.isEmpty() || (fallbackIsYear && !likelyYear)) {
                    fallback = candidate;
                }
                // Prefer 6-digit codes when no explicit context label matched.
                if (candidate.length() == 6) {
                    return candidate;
                }
            }
        }
        return fallback;
    }

    /**
     * Extracts unique HTTPS temp-mail candidate API origins/paths from web feeds, JSON payloads, or user text.
     */
    public static List<String> extractCandidateUrls(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            while (raw.endsWith(".") || raw.endsWith(",") || raw.endsWith(")") || raw.endsWith("\"") || raw.endsWith("'")) {
                raw = raw.substring(0, raw.length() - 1);
            }
            try {
                URI uri = URI.create(raw);
                String host = uri.getHost();
                if (host == null) continue;
                String lowerHost = host.toLowerCase(Locale.ROOT);
                if (NON_PROVIDER_HOSTS.contains(lowerHost)) continue;
                String lowerUrl = raw.toLowerCase(Locale.ROOT);
                if (!MAIL_KEYWORD_PATTERN.matcher(lowerUrl).find()) continue;

                String origin = "https://" + lowerHost + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
                String path = uri.getPath();
                if (path != null && (path.contains("/api") || path.contains("/v1") || path.contains("/v2") || path.contains("/v3"))) {
                    String trimmedPath = trimApiEndpointSuffix(path);
                    if (!trimmedPath.isEmpty()) {
                        candidates.add(origin + trimmedPath);
                    }
                }
                candidates.add(origin);
                if (lowerHost.startsWith("api.") || lowerHost.startsWith("www.")) {
                    candidates.add(origin);
                } else {
                    candidates.add("https://api." + lowerHost);
                }
            } catch (Throwable ignored) {}
        }
        return new ArrayList<>(candidates);
    }

    private static String trimApiEndpointSuffix(String path) {
        String clean = trimTrailingSlash(path);
        String[] suffixes = {
                "/inbox/create", "/email/new", "/domains", "/messages",
                "/accounts", "/token", "/generate", "/inbox", "/ajax.php"
        };
        for (String suffix : suffixes) {
            if (clean.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                clean = clean.substring(0, clean.length() - suffix.length());
            }
        }
        return clean;
    }

    /**
     * Scans built-in free providers, public internet directories, and optional user seeds,
     * probing each candidate concurrently or sequentially with fast timeouts to find working keyless APIs.
     */
    public static DiscoveryResult discoverProviders(Collection<String> extraSeeds) {
        Map<String, ProviderSpec> seenBases = new LinkedHashMap<>();
        for (ProviderSpec builtin : builtInProviders()) {
            seenBases.put(builtin.apiBase.toLowerCase(Locale.ROOT), builtin);
        }

        if (extraSeeds != null) {
            for (String seed : extraSeeds) {
                for (String url : extractCandidateUrls(seed)) {
                    addCandidate(seenBases, url, "custom");
                }
            }
        }

        // Live auto-discovery from public free-API feeds (bounded timeout so slow/blocked feeds never stall)
        java.util.concurrent.ExecutorService feedPool = java.util.concurrent.Executors.newFixedThreadPool(DISCOVERY_FEED_URLS.length);
        try {
            List<java.util.concurrent.Future<List<String>>> feedFutures = new ArrayList<>();
            for (final String feedUrl : DISCOVERY_FEED_URLS) {
                feedFutures.add(feedPool.submit(() -> {
                    try {
                        ColgramHttp.Response r = ColgramHttp.getFast(feedUrl);
                        if (r != null && r.code >= 200 && r.code < 300 && r.body != null && !r.body.isEmpty()) {
                            return extractCandidateUrls(r.body);
                        }
                    } catch (Throwable ignored) {}
                    return Collections.emptyList();
                }));
            }
            long feedDeadline = System.currentTimeMillis() + 5000L;
            for (java.util.concurrent.Future<List<String>> ff : feedFutures) {
                try {
                    long rem = Math.max(50L, feedDeadline - System.currentTimeMillis());
                    List<String> urls = ff.get(rem, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (urls != null) {
                        for (String u : urls) {
                            addCandidate(seenBases, u, "auto");
                            if (seenBases.size() >= 24) break;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } finally {
            feedPool.shutdownNow();
        }

        List<ProviderSpec> candidates = new ArrayList<>(seenBases.values());
        if (candidates.size() > 24) {
            candidates = candidates.subList(0, 24);
        }
        int probed = candidates.size();
        List<ProviderSpec> working = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(6, Math.max(1, candidates.size())));
        try {
            List<java.util.concurrent.Future<ProviderSpec>> futures = new ArrayList<>(candidates.size());
            for (final ProviderSpec candidate : candidates) {
                futures.add(pool.submit(() -> probeProvider(candidate)));
            }
            long probeDeadline = System.currentTimeMillis() + 12_000L;
            for (int i = 0; i < candidates.size(); i++) {
                ProviderSpec candidate = candidates.get(i);
                ProviderSpec verified = null;
                try {
                    long rem = Math.max(50L, probeDeadline - System.currentTimeMillis());
                    verified = futures.get(i).get(rem, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {}
                if (verified != null && verified.reachable) {
                    working.add(verified);
                    report.append("OK  ").append(verified.name)
                            .append(" · ").append(verified.latencyMs).append(" мс\n");
                } else {
                    report.append("--  ").append(candidate.name).append(" (недоступен)\n");
                }
            }
        } finally {
            pool.shutdownNow();
        }

        working.sort(Comparator.comparingLong(p -> p.latencyMs));
        return new DiscoveryResult(working, probed, report.toString().trim());
    }

    private static void addCandidate(Map<String, ProviderSpec> map, String baseUrl, String source) {
        String clean = trimTrailingSlash(baseUrl);
        if (clean.isEmpty()) return;
        String key = clean.toLowerCase(Locale.ROOT);
        if (map.containsKey(key)) return;
        String host = hostOf(clean);
        String proto = clean.contains("emailnator.com") ? PROTOCOL_EMAILNATOR
                : clean.contains("ajax.php") ? PROTOCOL_GUERRILLA
                : clean.contains("inboxes.com") ? PROTOCOL_INBOXES
                : clean.contains("tempmail.lol") ? PROTOCOL_TEMPMAIL_LOL
                : clean.contains("temp-mail.io") ? PROTOCOL_MAIL_IO
                : clean.contains("maildrop.cc") ? PROTOCOL_MAILDROP
                : clean.contains("1secmail") ? PROTOCOL_1SECMAIL
                : PROTOCOL_HYDRA;
        map.put(key, new ProviderSpec("disc_" + Integer.toHexString(key.hashCode()),
                host, clean, proto, -1, source, false));
    }

    /** Probes a single candidate API base URL and detects which keyless temp-mail protocol it speaks. */
    public static ProviderSpec probeProvider(ProviderSpec candidate) {
        if (candidate == null || candidate.apiBase.isEmpty()) return null;
        String base = trimTrailingSlash(candidate.apiBase);
        long start = System.currentTimeMillis();

        if (PROTOCOL_EMAILNATOR.equals(candidate.protocol) || base.contains("emailnator.com")) {
            try {
                ColgramHttp.Response res = ColgramHttp.post(base + "/api/generate-email", "{\"ids\":[3,2]}", null);
                if (res != null && res.code >= 200 && res.code < 300 && res.body != null
                        && res.body.contains("\"email\"")) {
                    return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_EMAILNATOR,
                            elapsed(start), candidate.source, true);
                }
            } catch (Throwable ignored) {}
            return null;
        }
        if (PROTOCOL_INBOXES.equals(candidate.protocol) || base.contains("inboxes.com")) {
            if (probeJsonContains(base + "/domain", "\"qdn\"")) {
                return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_INBOXES,
                        elapsed(start), candidate.source, true);
            }
            return null;
        }
        if (PROTOCOL_GUERRILLA.equals(candidate.protocol) || base.endsWith("ajax.php")) {
            if (probeJsonContains(base + "?f=get_email_address&lang=en", "email_addr")) {
                return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_GUERRILLA,
                        elapsed(start), candidate.source, true);
            }
            return null;
        }
        if (PROTOCOL_1SECMAIL.equals(candidate.protocol)) {
            if (probeArrayResponse(base + "/?action=getDomainList")) {
                return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_1SECMAIL,
                        elapsed(start), candidate.source, true);
            }
            return null;
        }
        if (PROTOCOL_TEMPMAIL_LOL.equals(candidate.protocol)) {
            String lolRoot = base.endsWith("/v2") ? base.substring(0, base.length() - 3) : base;
            try {
                ColgramHttp.Response res = ColgramHttp.post(lolRoot + "/v2/inbox/create", "{}", null);
                if (res != null && res.code >= 200 && res.code < 300 && res.body != null
                        && res.body.contains("\"address\"")) {
                    return new ProviderSpec(candidate.id, candidate.name, lolRoot, PROTOCOL_TEMPMAIL_LOL,
                            elapsed(start), candidate.source, true);
                }
            } catch (Throwable ignored) {}
            if (probeJsonContains(lolRoot + "/generate", "\"address\"")) {
                return new ProviderSpec(candidate.id, candidate.name, lolRoot, PROTOCOL_TEMPMAIL_LOL,
                        elapsed(start), candidate.source, true);
            }
            return null;
        }
        if (PROTOCOL_MAIL_IO.equals(candidate.protocol)) {
            if (probeJsonContains(base + "/domains", "\"name\"")) {
                return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_MAIL_IO,
                        elapsed(start), candidate.source, true);
            }
            try {
                ColgramHttp.Response res = ColgramHttp.post(base + "/email/new",
                        "{\"min_name_length\":8,\"max_name_length\":12}", null);
                if (res != null && res.code >= 200 && res.code < 300 && res.body != null
                        && res.body.contains("\"email\"")) {
                    return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_MAIL_IO,
                            elapsed(start), candidate.source, true);
                }
            } catch (Throwable ignored) {}
            return null;
        }
        if (PROTOCOL_MAILDROP.equals(candidate.protocol) || base.contains("maildrop.cc")) {
            try {
                ColgramHttp.Response res = ColgramHttp.post(base,
                        "{\"query\":\"query{ping(message:\\\"ok\\\")}\"}", null);
                if (res != null && res.code >= 200 && res.code < 300 && res.body != null
                        && res.body.contains("\"ping\"")) {
                    return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_MAILDROP,
                            elapsed(start), candidate.source, true);
                }
            } catch (Throwable ignored) {}
            return null;
        }

        // Default auto-detect for Hydra (Mail.tm/Mail.gw compatible) + fallback protocol signatures.
        if (probeJsonContains(base + "/domains?page=1", "\"domain\"")) {
            return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_HYDRA,
                    elapsed(start), candidate.source, true);
        }
        if (!"builtin".equals(candidate.source)) {
            if (probeJsonContains(base + "/v2/domains", "\"domain\"")
                    || probeJsonContains(base + "/generate", "\"address\"")) {
                return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_TEMPMAIL_LOL,
                        elapsed(start), candidate.source, true);
            }
            if (probeArrayResponse(base + "/?action=getDomainList")) {
                return new ProviderSpec(candidate.id, candidate.name, base, PROTOCOL_1SECMAIL,
                        elapsed(start), candidate.source, true);
            }
        }
        return null;
    }

    private static boolean probeJsonContains(String url, String requiredToken) {
        try {
            ColgramHttp.Response res = ColgramHttp.getFast(url);
            if (res == null || res.code < 200 || res.code >= 300 || res.body == null) return false;
            String trimmed = res.body.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false;
            return trimmed.contains(requiredToken);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean probeArrayResponse(String url) {
        try {
            ColgramHttp.Response res = ColgramHttp.getFast(url);
            if (res == null || res.code < 200 || res.code >= 300 || res.body == null) return false;
            String trimmed = res.body.trim();
            return trimmed.startsWith("[") && trimmed.length() > 4 && !trimmed.contains("<html");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Serializes discovered providers to a compact newline-delimited string for preference storage. */
    public static String serializeProviders(List<ProviderSpec> specs) {
        if (specs == null || specs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ProviderSpec p : specs) {
            if (p == null || p.apiBase.isEmpty()) continue;
            sb.append(sanitizeField(p.id)).append('\t')
                    .append(sanitizeField(p.name)).append('\t')
                    .append(sanitizeField(p.apiBase)).append('\t')
                    .append(sanitizeField(p.protocol)).append('\t')
                    .append(p.latencyMs).append('\t')
                    .append(sanitizeField(p.source)).append('\n');
        }
        return sb.toString();
    }

    /** Restores providers serialized via {@link #serializeProviders(List)}. */
    public static List<ProviderSpec> deserializeProviders(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        List<ProviderSpec> out = new ArrayList<>();
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 6) continue;
            long ms = -1;
            try {
                ms = Long.parseLong(parts[4]);
            } catch (NumberFormatException ignored) {}
            out.add(new ProviderSpec(parts[0], parts[1], parts[2], parts[3], ms, parts[5], ms >= 0));
        }
        return out;
    }

    private static String sanitizeField(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static long elapsed(long startMs) {
        return Math.max(1L, System.currentTimeMillis() - startMs);
    }

    private static String trimTrailingSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (Throwable ignored) {
            return url;
        }
    }
}
