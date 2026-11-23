package org.colgram.core;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyless, parallel multi-source live internet search, instant live market/math resolver,
 * and readable webpage extractor for grounded RAG.
 */
public final class ColgramAiWebSearch {

    public static final class SearchHit {
        public final String title;
        public final String url;
        public final String snippet;
        public final String source;
        public String pageExcerpt;

        public SearchHit(String title, String url, String snippet, String source) {
            this.title = title == null ? "" : title.trim();
            this.url = url == null ? "" : url.trim();
            this.snippet = snippet == null ? "" : snippet.trim();
            this.source = source == null ? "" : source.trim();
            this.pageExcerpt = "";
        }
    }

    private static final Pattern DDG_RESULT_BLOCK = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>[\\s\\S]*?"
                    + "(?:<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>|<div[^>]*class=\"result__snippet\"[^>]*>(.*?)</div>)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DDG_LITE_LINK = Pattern.compile(
            "<a[^>]*class='result-link'[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_STRIP = Pattern.compile("<[^>]+>");
    private static final Pattern SCRIPT_STYLE_STRIP = Pattern.compile(
            "(?is)<(?:script|style|noscript|svg|header|footer|nav|aside|form)[^>]*>.*?</(?:script|style|noscript|svg|header|footer|nav|aside|form)>");
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile(
            "(?is)<(?:p|li|h1|h2|h3|blockquote|article|td)[^>]*>(.*?)</(?:p|li|h1|h2|h3|blockquote|article|td)>");
    private static final Pattern BING_RSS_ITEM = Pattern.compile("(?is)<item>(.*?)</item>");
    private static final Pattern XML_TITLE = Pattern.compile("(?is)<title>(.*?)</title>");
    private static final Pattern XML_LINK = Pattern.compile("(?is)<link>(.*?)</link>");
    private static final Pattern XML_DESC = Pattern.compile("(?is)<description>(.*?)</description>");
    private static final Pattern WIKI_GEN_BLOCK = Pattern.compile(
            "\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[\\s\\S]*?\"extract\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[\\s\\S]*?\"fullurl\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern HN_ITEM = Pattern.compile(
            "\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[\\s\\S]*?\"url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern WIKI_ITEM_TITLE = Pattern.compile(
            "\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[\\s\\S]*?\"snippet\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_KV_ABSTRACT = Pattern.compile("\"AbstractText\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_KV_ABSTRACT_URL = Pattern.compile("\"AbstractURL\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_KV_HEADING = Pattern.compile("\"Heading\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_KV_RELATED = Pattern.compile("\"FirstURL\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[\\s\\S]*?\"Text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_KV_RELATED_REV = Pattern.compile("\"Text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[\\s\\S]*?\"FirstURL\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MATH_EXTRACT = Pattern.compile("^[\\s()0-9.,+\\-*/^]+$");

    private static final ExecutorService SEARCH_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "colgram-ai-search");
        t.setDaemon(true);
        return t;
    });

    private ColgramAiWebSearch() {}

    public static List<SearchHit> search(String rawQuery, int maxResults, boolean fetchTopPages) {
        String query = cleanSearchQuery(rawQuery);
        if (query.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, Math.min(8, maxResults));
        Map<String, SearchHit> merged = new LinkedHashMap<>();
        boolean cyrillic = containsCyrillic(query);

        // 0. Instant deterministic calculations & live market feeds when query asks math / crypto / FX.
        SearchHit mathHit = tryEvalMathAsHit(rawQuery);
        if (mathHit != null) {
            merged.put(mathHit.url, mathHit);
        }

        // Fast-path: for currency / crypto conversion queries, fetch ONLY live structured market/FX APIs
        // so we never waste 25 seconds scraping unrelated SEO pages or flooding the model with noise.
        if (looksLikeFinanceQuery(query)) {
            List<SearchHit> liveFin = fetchLiveFinanceHits(rawQuery);
            if (!liveFin.isEmpty()) {
                addAllUnique(merged, liveFin);
                List<SearchHit> fastOut = new ArrayList<>(merged.values());
                return fastOut.size() > limit ? new ArrayList<>(fastOut.subList(0, limit)) : fastOut;
            }
        }

        // Launch live web engines in parallel with bounded timeout so offline/blocked providers never stall UX.
        List<Callable<List<SearchHit>>> tasks = new ArrayList<>();
        if (looksLikeNewsQuery(query)) {
            tasks.add(() -> fetchLiveNewsHits(query, cyrillic, limit));
        }
        tasks.add(() -> {
            String bingLocale = cyrillic ? "&setlang=ru&cc=ru" : "&setlang=en&cc=us";
            String rss = fetchText("https://www.bing.com/search?q=" + urlEncode(query) + "&format=rss" + bingLocale);
            return parseBingRssXml(rss, limit);
        });
        tasks.add(() -> {
            String wikiHost = cyrillic ? "ru.wikipedia.org" : "en.wikipedia.org";
            String wikiRichUrl = "https://" + wikiHost + "/w/api.php?action=query&generator=search&gsrsearch="
                    + urlEncode(query) + "&gsrlimit=" + Math.min(4, limit)
                    + "&prop=extracts|info&inprop=url&exintro=1&explaintext=1&exchars=750&format=json&utf8=1";
            List<SearchHit> wiki = parseWikipediaGeneratorJson(fetchText(wikiRichUrl), wikiHost, limit);
            if (wiki.isEmpty()) {
                String wikiUrl = "https://" + wikiHost + "/w/api.php?action=query&list=search&srsearch="
                        + urlEncode(query) + "&utf8=1&format=json&srlimit=" + Math.min(4, limit);
                wiki = parseWikipediaJson(fetchText(wikiUrl), wikiHost, limit);
            }
            if (wiki.isEmpty() && cyrillic) {
                String enRichUrl = "https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch="
                        + urlEncode(query) + "&gsrlimit=2&prop=extracts|info&inprop=url&exintro=1&explaintext=1&exchars=600&format=json&utf8=1";
                wiki = parseWikipediaGeneratorJson(fetchText(enRichUrl), "en.wikipedia.org", 2);
            }
            return wiki;
        });
        tasks.add(() -> {
            String iaUrl = "https://api.duckduckgo.com/?q=" + urlEncode(query)
                    + "&format=json&no_html=1&skip_disambig=1";
            List<SearchHit> ia = parseDuckDuckGoInstantJson(fetchText(iaUrl), limit);
            if (ia.isEmpty()) {
                String ddgHtml = fetchText("https://html.duckduckgo.com/html/?q=" + urlEncode(query));
                ia = parseDuckDuckGoHtml(ddgHtml, limit);
                if (ia.isEmpty()) {
                    ia = parseDuckDuckGoLite(fetchText("https://lite.duckduckgo.com/lite/?q=" + urlEncode(query)), limit);
                }
            }
            return ia;
        });
        if (!cyrillic || query.toLowerCase(Locale.ROOT).matches(".*(ai|llm|python|java|rust|git|api|linux|qwen|deepseek|docker|code).*")) {
            tasks.add(() -> parseHnAlgoliaJson(
                    fetchText("https://hn.algolia.com/api/v1/search?query=" + urlEncode(query) + "&hitsPerPage=3"),
                    limit));
        }

        try {
            List<Future<List<SearchHit>>> futures = SEARCH_POOL.invokeAll(tasks, 4200L, TimeUnit.MILLISECONDS);
            for (Future<List<SearchHit>> f : futures) {
                if (!f.isCancelled() && f.isDone()) {
                    try {
                        List<SearchHit> partial = f.get();
                        if (partial != null) {
                            addAllUnique(merged, partial);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        List<SearchHit> filtered = new ArrayList<>();
        for (SearchHit hit : merged.values()) {
            if (!isLowQualityHit(hit)) {
                filtered.add(hit);
            }
        }
        List<SearchHit> out = filtered;
        if (out.size() > limit) {
            out = new ArrayList<>(out.subList(0, limit));
        }

        // Parallel full-article reading of top external hits (up to 3) to eliminate truncated snippets.
        if (fetchTopPages && !out.isEmpty()) {
            List<Callable<Void>> pageTasks = new ArrayList<>();
            int pagesToRead = Math.min(3, out.size());
            for (int i = 0; i < pagesToRead; i++) {
                final SearchHit hit = out.get(i);
                if (!"Live Market".equals(hit.source)
                        && !"Live FX".equals(hit.source)
                        && !"Live News".equals(hit.source)
                        && !"Instant Math".equals(hit.source)
                        && !"Calculator".equals(hit.source)
                        && hit.url.startsWith("http")
                        && hit.pageExcerpt.length() < 300) {
                    pageTasks.add(() -> {
                        String html = fetchText(hit.url);
                        if (!html.isEmpty()) {
                            String cleaned = extractCleanPageText(html, 2200);
                            if (cleaned.length() > hit.pageExcerpt.length()) {
                                hit.pageExcerpt = cleaned;
                            }
                        }
                        return null;
                    });
                }
            }
            if (!pageTasks.isEmpty()) {
                try {
                    SEARCH_POOL.invokeAll(pageTasks, 3600L, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    public static String cleanSearchQuery(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').replace('\r', ' ').trim();
        s = s.replaceFirst("(?iu)^(?:найди в интернете|найди|поищи|загугли|посмотри в инете|узнай в инете|узнай|что такое|кто такой|кто такая|расскажи про|расскажи о|объясни|какой сегодня|какой|сколько сейчас стоит|сколько стоит|search|find|lookup|what is|who is)\\s+", "");
        s = s.replaceFirst("(?iu)\\s+(?:и напиши ответ.*|и вставь в ввод.*|и ответь в чат.*)$", "");
        if (s.length() > 160) {
            s = s.substring(0, 160).trim();
        }
        return s.trim();
    }

    public static String tryEvalMath(String raw) {
        if (raw == null) return null;
        String norm = raw.trim()
                .replaceAll("(?iu)(?:сколько будет|посчитай|вычисли|реши|calculate|compute|eval)\\s*", "")
                .replace(',', '.')
                .replace('х', '*')
                .replace('×', '*')
                .replace('÷', '/')
                .replace('?', ' ')
                .replace('=', ' ')
                .trim();
        if (norm.length() < 3 || norm.length() > 64 || !MATH_EXTRACT.matcher(norm).matches()) {
            return null;
        }
        boolean hasOp = norm.contains("+") || norm.contains("-") || norm.contains("*") || norm.contains("/") || norm.contains("^");
        if (!hasOp) return null;
        try {
            double val = new ExprParser(norm).parse();
            if (Double.isNaN(val) || Double.isInfinite(val)) return null;
            if (Math.abs(val - Math.rint(val)) < 1e-9 && Math.abs(val) < 1e15) {
                return Long.toString(Math.round(val));
            }
            return String.format(Locale.US, "%.4f", val).replaceAll("0+$", "").replaceAll("\\.$", "");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static SearchHit tryEvalMathAsHit(String raw) {
        String ans = tryEvalMath(raw);
        if (ans == null) return null;
        SearchHit hit = new SearchHit("Точный расчёт", "calc://colgram-math", raw.trim() + " = " + ans, "Calculator");
        hit.pageExcerpt = "Результат вычисления: " + ans;
        return hit;
    }

    private static boolean looksLikeNewsQuery(String q) {
        String l = q.toLowerCase(Locale.ROOT);
        return l.contains("новост") || l.contains("событи") || l.contains("произошло")
                || l.contains("главные темы") || l.contains("что нового в мире")
                || l.contains("it ") || l.contains(" ит ") || l.contains("news") || l.contains("headlines");
    }

    private static List<SearchHit> fetchLiveNewsHits(String query, boolean cyrillic, int limit) {
        List<SearchHit> out = new ArrayList<>();
        String l = query.toLowerCase(Locale.ROOT);
        boolean wantsIt = l.contains("it") || l.contains("ит") || l.contains("технолог") || l.contains("ии") || l.contains("ai");
        if (wantsIt) {
            String habr = fetchText("https://habr.com/ru/rss/news/?fl=ru");
            out.addAll(parseNewsRssXml(habr, "Habr", Math.min(3, limit)));
        }
        String gnewsUrl = cyrillic
                ? "https://news.google.com/rss?hl=ru&gl=RU&ceid=RU:ru"
                : "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en";
        String gnews = fetchText(gnewsUrl);
        out.addAll(parseNewsRssXml(gnews, "Google News", Math.max(2, limit - out.size())));
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public static List<SearchHit> parseNewsRssXml(String xml, String providerLabel, int limit) {
        List<SearchHit> results = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return results;
        Matcher itemM = BING_RSS_ITEM.matcher(xml);
        while (itemM.find() && results.size() < limit) {
            String block = itemM.group(1);
            String title = cleanHtmlText(extractTag(XML_TITLE, block));
            String url = unescapeHtml(extractTag(XML_LINK, block)).trim();
            String desc = cleanHtmlText(extractTag(XML_DESC, block));
            if (title.isEmpty() || title.length() < 14 || isBoilerplateNoise(title)) continue;
            if (desc.equalsIgnoreCase(title) || isBoilerplateNoise(desc)) {
                desc = "";
            }
            SearchHit hit = new SearchHit(title, url, desc, "Live News");
            hit.pageExcerpt = desc.isEmpty() ? title : (title + " — " + desc);
            results.add(hit);
        }
        return results;
    }

    private static final Pattern STRICT_CRYPTO_TOKEN = Pattern.compile(
            "(?iu)(?:^|[^a-zа-яё0-9_])(btc|bitcoin|биткоин\\p{L}*|eth|ethereum|эфириум\\p{L}*|toncoin|usdt|крипт\\p{L}*)(?:$|[^a-zа-яё0-9_])");
    private static final Pattern STRICT_FIAT_TOKEN = Pattern.compile(
            "(?iu)(?:^|[^a-zа-яё0-9_])(usd|eur|rub|доллар\\p{L}*|бакс\\p{L}*|евро|рубл\\p{L}*|руб|валют\\p{L}*)(?:$|[^a-zа-яё0-9_])");
    private static final Pattern STRICT_TON_CRYPTO = Pattern.compile(
            "(?iu)(?:^|[^a-zа-яё0-9_])ton(?:$|[^a-zа-яё0-9_])");
    private static final Pattern STRICT_MONEY_SIGN = Pattern.compile(
            "(?:\\$\\s*[0-9]|[0-9]\\s*\\$|€\\s*[0-9]|[0-9]\\s*€|₽\\s*[0-9]|[0-9]\\s*₽)");
    private static final Pattern STRICT_RATE_WORD = Pattern.compile(
            "(?iu)(?:^|[^a-zа-яё0-9_])(курс[ауеы]?|обменн\\p{L}*|котировк\\p{L}*|цб\\s*рф|сколько\\s+стоит|скок\\s+стоит|переведи\\s+[0-9]|конверт\\p{L}*)(?:$|[^a-zа-яё0-9_])");
    private static final Pattern NON_FINANCE_COURSE_PHRASE = Pattern.compile(
            "(?iu)(?:в\\s+курсе|курс[аеу]?\\s+(?:обучен|програм|лекц|молод|python|java|истор|математ|англ|дизайн|лечен|антибиотик)|(?:перв|втор|трет|четверт|пят)\\p{L}*\\s+курс)");

    public static boolean looksLikeFinanceQuery(String q) {
        if (q == null || q.trim().isEmpty()) return false;
        String s = q.trim();
        if (NON_FINANCE_COURSE_PHRASE.matcher(s).find()
                && !STRICT_CRYPTO_TOKEN.matcher(s).find()
                && !STRICT_FIAT_TOKEN.matcher(s).find()
                && !STRICT_MONEY_SIGN.matcher(s).find()) {
            return false;
        }
        boolean hasCrypto = STRICT_CRYPTO_TOKEN.matcher(s).find();
        boolean hasFiat = STRICT_FIAT_TOKEN.matcher(s).find();
        boolean hasMoneySign = STRICT_MONEY_SIGN.matcher(s).find();
        boolean hasRateWord = STRICT_RATE_WORD.matcher(s).find();
        boolean hasTonWithRate = STRICT_TON_CRYPTO.matcher(s).find()
                && (hasRateWord || hasFiat || hasCrypto || hasMoneySign);
        if (hasTonWithRate) return true;
        if (hasCrypto && (hasRateWord || hasFiat || hasMoneySign || s.length() <= 32)) return true;
        if (hasFiat && (hasRateWord || hasMoneySign || extractQueryAmount(s) > 0)) return true;
        if (hasMoneySign && (hasRateWord || hasFiat || hasCrypto)) return true;
        return false;
    }

    private static List<SearchHit> fetchLiveFinanceHits(String query) {
        List<SearchHit> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        boolean wantsCrypto = STRICT_CRYPTO_TOKEN.matcher(query).find()
                || (STRICT_TON_CRYPTO.matcher(query).find() && looksLikeFinanceQuery(query));
        boolean wantsFiat = STRICT_FIAT_TOKEN.matcher(query).find()
                || STRICT_MONEY_SIGN.matcher(query).find()
                || query.toLowerCase(Locale.ROOT).contains("usdt");
        if (wantsFiat) {
            String fx = fetchText("https://open.er-api.com/v6/latest/USD");
            out.addAll(parseExchangeRateJson(fx, query));
        }
        if (wantsCrypto) {
            String cg = fetchText("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,the-open-network,tether&vs_currencies=usd,rub,eur");
            out.addAll(parseCoinGeckoPriceJson(cg, query));
        }
        return out;
    }

    public static List<SearchHit> parseCoinGeckoPriceJson(String json, String query) {
        List<SearchHit> hits = new ArrayList<>();
        if (json == null || json.isEmpty()) return hits;
        String dateStamp = currentDateRu();
        StringBuilder sb = new StringBuilder();
        double amt = extractQueryAmount(query);
        String lq = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (amt > 0) {
            String coinId = lq.contains("btc") || lq.contains("биткоин") ? "bitcoin"
                    : (lq.contains("eth") || lq.contains("эфир") ? "ethereum"
                    : (lq.contains("ton") ? "the-open-network"
                    : (lq.contains("usdt") ? "tether" : "")));
            if (!coinId.isEmpty()) {
                double rubRate = extractCoinCurrencyRate(json, coinId, "rub");
                double usdRate = extractCoinCurrencyRate(json, coinId, "usd");
                String ticker = "bitcoin".equals(coinId) ? "BTC"
                        : ("ethereum".equals(coinId) ? "ETH"
                        : ("the-open-network".equals(coinId) ? "TON" : "USDT"));
                if (rubRate > 0 && (lq.contains("руб") || lq.contains("rub") || lq.contains("₽"))) {
                    sb.append("Точный расчёт на ").append(dateStamp).append(": ")
                            .append(formatCompactNum(amt)).append(' ').append(ticker)
                            .append(" = ").append(formatMoney2(amt * rubRate)).append(" RUB (1 ")
                            .append(ticker).append(" = ").append(formatMoney2(rubRate)).append(" RUB). ");
                } else if (usdRate > 0) {
                    sb.append("Точный расчёт на ").append(dateStamp).append(": ")
                            .append(formatCompactNum(amt)).append(' ').append(ticker)
                            .append(" = $").append(formatMoney2(amt * usdRate)).append(" USD. ");
                }
            }
        }
        appendCoin(sb, json, "bitcoin", "BTC");
        appendCoin(sb, json, "ethereum", "ETH");
        appendCoin(sb, json, "the-open-network", "TON");
        appendCoin(sb, json, "tether", "USDT");
        if (sb.length() > 0) {
            sb.append("Дата курса: ").append(dateStamp).append('.');
            String txt = sb.toString().trim();
            SearchHit hit = new SearchHit("Актуальные курсы криптовалют (" + dateStamp + ")", "https://www.coingecko.com/", txt, "Live Market");
            hit.pageExcerpt = txt;
            hits.add(hit);
        }
        return hits;
    }

    private static double extractCoinCurrencyRate(String json, String id, String cur) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(id) + "\"\\s*:\\s*\\{([^}]*)\\}").matcher(json);
        if (!m.find()) return -1.0;
        String num = extractJsonNumber(m.group(1), cur);
        if (num.isEmpty()) return -1.0;
        try {
            return Double.parseDouble(num);
        } catch (Throwable ignored) {
            return -1.0;
        }
    }

    private static void appendCoin(StringBuilder sb, String json, String id, String label) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(id) + "\"\\s*:\\s*\\{([^}]*)\\}").matcher(json);
        if (!m.find()) return;
        String body = m.group(1);
        String usd = roundDecimalString(extractJsonNumber(body, "usd"));
        String rub = roundDecimalString(extractJsonNumber(body, "rub"));
        if (usd.isEmpty() && rub.isEmpty()) return;
        sb.append(label).append(": ");
        if (!usd.isEmpty()) sb.append('$').append(usd);
        if (!rub.isEmpty()) sb.append(" / ").append(rub).append(" RUB");
        sb.append(". ");
    }

    public static List<SearchHit> parseExchangeRateJson(String json, String query) {
        List<SearchHit> hits = new ArrayList<>();
        if (json == null || json.isEmpty()) return hits;
        String rubRaw = extractJsonNumber(json, "RUB");
        String eurRaw = extractJsonNumber(json, "EUR");
        String cnyRaw = extractJsonNumber(json, "CNY");
        String kztRaw = extractJsonNumber(json, "KZT");
        if (rubRaw.isEmpty() && eurRaw.isEmpty()) return hits;

        String dateStamp = extractFxDate(json);
        double usdRub = parseDoubleSafe(rubRaw);
        double usdEur = parseDoubleSafe(eurRaw);
        double eurRub = (usdRub > 0 && usdEur > 0) ? (usdRub / usdEur) : -1.0;

        StringBuilder sb = new StringBuilder();
        double amt = extractQueryAmount(query);
        String lq = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (amt > 0 && usdRub > 0) {
            boolean fromEur = lq.contains("евро") || lq.contains("eur") || lq.contains("€");
            boolean fromRub = (lq.contains("руб") || lq.contains("rub") || lq.contains("₽"))
                    && (lq.contains("в доллар") || lq.contains("в бакс") || lq.contains("в usd") || lq.contains("в usdt") || lq.contains("в евро") || lq.contains("в eur"));
            if (fromRub) {
                if (fromEur && eurRub > 0) {
                    sb.append("Точный расчёт на ").append(dateStamp).append(": ")
                            .append(formatCompactNum(amt)).append(" RUB = ").append(formatMoney2(amt / eurRub))
                            .append(" EUR (1 EUR = ").append(formatMoney2(eurRub)).append(" RUB). ");
                } else {
                    sb.append("Точный расчёт на ").append(dateStamp).append(": ")
                            .append(formatCompactNum(amt)).append(" RUB = ").append(formatMoney2(amt / usdRub))
                            .append(" USD (1 USD = ").append(formatMoney2(usdRub)).append(" RUB). ");
                }
            } else if (fromEur && eurRub > 0) {
                sb.append("Точный расчёт на ").append(dateStamp).append(": ")
                        .append(formatCompactNum(amt)).append(" EUR = ").append(formatMoney2(amt * eurRub))
                        .append(" RUB (1 EUR = ").append(formatMoney2(eurRub)).append(" RUB). ");
            } else {
                String unit = lq.contains("usdt") ? "USDT" : "USD";
                sb.append("Точный расчёт на ").append(dateStamp).append(": ")
                        .append(formatCompactNum(amt)).append(' ').append(unit).append(" = ")
                        .append(formatMoney2(amt * usdRub)).append(" RUB (1 ").append(unit)
                        .append(" = ").append(formatMoney2(usdRub)).append(" RUB). ");
            }
        }
        if (usdRub > 0) sb.append("1 USD = ").append(formatMoney2(usdRub)).append(" RUB. ");
        if (eurRub > 0) sb.append("1 EUR = ").append(formatMoney2(eurRub)).append(" RUB. ");
        double usdCny = parseDoubleSafe(cnyRaw);
        if (usdRub > 0 && usdCny > 0) {
            sb.append("1 CNY = ").append(formatMoney2(usdRub / usdCny)).append(" RUB. ");
        }
        double usdKzt = parseDoubleSafe(kztRaw);
        if (usdKzt > 0) sb.append("1 USD = ").append(formatMoney2(usdKzt)).append(" KZT. ");
        sb.append("Дата курса: ").append(dateStamp).append('.');

        String txt = sb.toString().trim();
        if (!txt.isEmpty()) {
            SearchHit hit = new SearchHit("Актуальный курс валют (" + dateStamp + ")", "https://www.exchangerate-api.com/", txt, "Live FX");
            hit.pageExcerpt = txt;
            hits.add(hit);
        }
        return hits;
    }

    public static double extractQueryAmount(String query) {
        if (query == null || query.isEmpty()) return -1.0;
        Matcher m = Pattern.compile("(?iu)(?:^|[^0-9.,])([0-9]+(?:[.,][0-9]+)?)\\s*(?:usdt|usd|eur|rub|btc|eth|ton|бакс\\p{L}*|доллар\\p{L}*|евро|рубл\\p{L}*|руб|\\$|€|₽)").matcher(query);
        if (m.find()) {
            return parseDoubleSafe(m.group(1).replace(',', '.'));
        }
        Matcher rev = Pattern.compile("(?iu)(?:\\$|€|₽)\\s*([0-9]+(?:[.,][0-9]+)?)").matcher(query);
        if (rev.find()) {
            return parseDoubleSafe(rev.group(1).replace(',', '.'));
        }
        return -1.0;
    }

    private static String extractFxDate(String json) {
        Matcher m = Pattern.compile("\"time_last_update_utc\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (m.find()) {
            String raw = m.group(1).trim();
            // e.g. "Thu, 19 Nov 2026 00:02:31 +0000" -> "19 Nov 2026"
            Matcher d = Pattern.compile("(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4})").matcher(raw);
            if (d.find()) {
                return d.group(1) + " (обновлено сегодня, " + currentDateRu() + ")";
            }
        }
        return currentDateRu();
    }

    public static String currentDateRu() {
        return new java.text.SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new java.util.Date());
    }

    private static double parseDoubleSafe(String raw) {
        if (raw == null || raw.isEmpty()) return -1.0;
        try {
            return Double.parseDouble(raw);
        } catch (Throwable ignored) {
            return -1.0;
        }
    }

    private static String roundDecimalString(String raw) {
        double v = parseDoubleSafe(raw);
        return v > 0 ? formatMoney2(v) : raw;
    }

    private static String formatMoney2(double val) {
        return String.format(Locale.US, "%.2f", val);
    }

    private static String formatCompactNum(double val) {
        if (Math.abs(val - Math.rint(val)) < 1e-9) {
            return Long.toString(Math.round(val));
        }
        return String.format(Locale.US, "%.4f", val).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String extractJsonNumber(String block, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(block);
        return m.find() ? m.group(1) : "";
    }

    public static List<SearchHit> parseBingRssXml(String xml, int limit) {
        List<SearchHit> results = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return results;
        Matcher itemM = BING_RSS_ITEM.matcher(xml);
        while (itemM.find() && results.size() < limit) {
            String block = itemM.group(1);
            String title = cleanHtmlText(extractTag(XML_TITLE, block));
            String url = unescapeHtml(extractTag(XML_LINK, block)).trim();
            String desc = cleanHtmlText(extractTag(XML_DESC, block));
            if (title.isEmpty() || url.isEmpty()) continue;
            results.add(new SearchHit(title, url, desc, "Bing Web"));
        }
        return results;
    }

    public static List<SearchHit> parseWikipediaGeneratorJson(String jsonText, String wikiHost, int limit) {
        List<SearchHit> results = new ArrayList<>();
        if (jsonText == null || jsonText.isEmpty()) return results;
        Matcher m = WIKI_GEN_BLOCK.matcher(jsonText);
        while (m.find() && results.size() < limit) {
            String title = unescapeJsonString(m.group(1)).trim();
            String extract = cleanHtmlText(unescapeJsonString(m.group(2)));
            String url = unescapeJsonString(m.group(3)).trim();
            if (title.isEmpty()) continue;
            if (url.isEmpty()) {
                url = "https://" + wikiHost + "/wiki/" + urlEncode(title.replace(' ', '_'));
            }
            SearchHit hit = new SearchHit(title + " — Википедия", url, extract, "Wikipedia");
            hit.pageExcerpt = extract;
            results.add(hit);
        }
        return results;
    }

    public static List<SearchHit> parseHnAlgoliaJson(String jsonText, int limit) {
        List<SearchHit> results = new ArrayList<>();
        if (jsonText == null || jsonText.isEmpty()) return results;
        Matcher m = HN_ITEM.matcher(jsonText);
        while (m.find() && results.size() < limit) {
            String title = unescapeJsonString(m.group(1)).trim();
            String url = unescapeJsonString(m.group(2)).trim();
            if (!title.isEmpty() && url.startsWith("http")) {
                results.add(new SearchHit(title, url, "", "HackerNews"));
            }
        }
        return results;
    }

    private static String extractTag(Pattern p, String block) {
        Matcher m = p.matcher(block);
        if (!m.find()) return "";
        String v = m.group(1);
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) {
            v = v.substring(9, v.length() - 3);
        }
        return v;
    }

    public static List<SearchHit> parseDuckDuckGoHtml(String html, int limit) {
        List<SearchHit> results = new ArrayList<>();
        if (html == null || html.isEmpty()) return results;
        Matcher m = DDG_RESULT_BLOCK.matcher(html);
        while (m.find() && results.size() < limit) {
            String href = unwrapDuckDuckGoHref(unescapeHtml(m.group(1)));
            String title = cleanHtmlText(m.group(2));
            String snip = cleanHtmlText(m.group(3) != null ? m.group(3) : m.group(4));
            if (title.isEmpty() || href.isEmpty() || href.startsWith("/")) continue;
            results.add(new SearchHit(title, href, snip, "DuckDuckGo"));
        }
        return results;
    }

    public static List<SearchHit> parseDuckDuckGoLite(String html, int limit) {
        List<SearchHit> results = new ArrayList<>();
        if (html == null || html.isEmpty()) return results;
        Matcher m = DDG_LITE_LINK.matcher(html);
        while (m.find() && results.size() < limit) {
            String href = unwrapDuckDuckGoHref(unescapeHtml(m.group(1)));
            String title = cleanHtmlText(m.group(2));
            if (title.isEmpty() || href.isEmpty()) continue;
            results.add(new SearchHit(title, href, "", "DuckDuckGo Lite"));
        }
        return results;
    }

    public static List<SearchHit> parseWikipediaJson(String jsonText, String wikiHost, int limit) {
        List<SearchHit> results = new ArrayList<>();
        if (jsonText == null || jsonText.isEmpty()) return results;
        Matcher m = WIKI_ITEM_TITLE.matcher(jsonText);
        while (m.find() && results.size() < limit) {
            String title = unescapeJsonString(m.group(1)).trim();
            String snippet = cleanHtmlText(unescapeJsonString(m.group(2)));
            if (title.isEmpty()) continue;
            String url = "https://" + wikiHost + "/wiki/" + urlEncode(title.replace(' ', '_'));
            results.add(new SearchHit(title + " — Википедия", url, snippet, "Wikipedia"));
        }
        return results;
    }

    public static List<SearchHit> parseDuckDuckGoInstantJson(String jsonText, int limit) {
        List<SearchHit> results = new ArrayList<>();
        if (jsonText == null || jsonText.isEmpty()) return results;
        String absText = matchGroup1(JSON_KV_ABSTRACT, jsonText).trim();
        String absUrl = matchGroup1(JSON_KV_ABSTRACT_URL, jsonText).trim();
        String heading = matchGroup1(JSON_KV_HEADING, jsonText).trim();
        if (!absText.isEmpty()) {
            SearchHit hit = new SearchHit(
                    heading.isEmpty() ? "Краткая справка" : heading,
                    absUrl.isEmpty() ? "https://duckduckgo.com" : absUrl,
                    absText,
                    "DuckDuckGo Instant"
            );
            hit.pageExcerpt = absText;
            results.add(hit);
        }
        Matcher m = JSON_KV_RELATED.matcher(jsonText);
        while (m.find() && results.size() < limit) {
            String firstUrl = unescapeJsonString(m.group(1)).trim();
            String text = cleanHtmlText(unescapeJsonString(m.group(2)));
            if (!text.isEmpty() && !firstUrl.isEmpty()) {
                String title = text.length() > 72 ? text.substring(0, 72) + "…" : text;
                results.add(new SearchHit(title, firstUrl, text, "DuckDuckGo Related"));
            }
        }
        if (results.isEmpty()) {
            Matcher rev = JSON_KV_RELATED_REV.matcher(jsonText);
            while (rev.find() && results.size() < limit) {
                String text = cleanHtmlText(unescapeJsonString(rev.group(1)));
                String firstUrl = unescapeJsonString(rev.group(2)).trim();
                if (!text.isEmpty() && !firstUrl.isEmpty()) {
                    String title = text.length() > 72 ? text.substring(0, 72) + "…" : text;
                    results.add(new SearchHit(title, firstUrl, text, "DuckDuckGo Related"));
                }
            }
        }
        return results;
    }

    private static String matchGroup1(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? unescapeJsonString(m.group(1)) : "";
    }

    private static String unescapeJsonString(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': out.append('"'); i++; break;
                    case '\\': out.append('\\'); i++; break;
                    case '/': out.append('/'); i++; break;
                    case 'n': out.append('\n'); i++; break;
                    case 'r': out.append('\r'); i++; break;
                    case 't': out.append('\t'); i++; break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 5;
                                break;
                            } catch (Throwable ignored) {}
                        }
                        out.append(c);
                        break;
                    default: out.append(next); i++; break;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static String extractCleanPageText(String html, int maxChars) {
        if (html == null || html.isEmpty()) return "";
        String body = SCRIPT_STYLE_STRIP.matcher(html).replaceAll(" ");
        StringBuilder sb = new StringBuilder();
        Matcher pm = PARAGRAPH_SPLIT.matcher(body);
        while (pm.find() && sb.length() < maxChars) {
            String line = cleanHtmlText(pm.group(1));
            if (line.length() < 36 || isBoilerplateNoise(line)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        if (sb.length() == 0) {
            String plain = cleanHtmlText(body);
            if (plain.length() > maxChars) {
                return plain.substring(0, maxChars).trim() + "…";
            }
            return plain;
        }
        String res = sb.toString().trim();
        if (res.length() > maxChars) {
            res = res.substring(0, maxChars).trim() + "…";
        }
        return res;
    }

    public static boolean isLowQualityHit(SearchHit hit) {
        if (hit == null) return true;
        if ("Live Market".equals(hit.source) || "Live FX".equals(hit.source)
                || "Live News".equals(hit.source) || "Calculator".equals(hit.source)
                || "Wikipedia".equals(hit.source)) {
            return false;
        }
        String urlLow = hit.url.toLowerCase(Locale.ROOT);
        String combined = (hit.title + " " + hit.snippet + " " + hit.pageExcerpt).toLowerCase(Locale.ROOT);
        if (urlLow.contains("youtube.com/watch") || urlLow.contains("youtu.be/")
                || urlLow.contains("tiktok.com/") || urlLow.contains("instagram.com/")
                || urlLow.contains("pinterest.") || urlLow.contains("dzen.ru/video")
                || urlLow.contains("://ya.ru") || urlLow.contains("wap.yandex.")
                || urlLow.contains("360.yandex.") || urlLow.contains("yandex.ru/search")
                || urlLow.contains("google.com/search")) {
            return true;
        }
        if (isBoilerplateNoise(combined)) {
            return true;
        }
        return hit.snippet.isEmpty() && hit.pageExcerpt.isEmpty() && hit.title.length() < 12;
    }

    public static boolean isBoilerplateNoise(String line) {
        if (line == null || line.trim().isEmpty()) return true;
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("cookie") || l.contains("куки") || l.contains("политика конфиденциальности")
                || l.contains("privacy policy") || l.contains("terms of service")
                || l.contains("все права защищены") || l.contains("all rights reserved")
                || l.contains("skip to content") || l.contains("enable javascript")
                || l.contains("provided to youtube") || l.contains("auto-generated by youtube")
                || l.contains("released on:") || l.contains("music video by")
                || l.contains("читайте свежую и оперативную информацию")
                || l.contains("новости политики, экономики, общества")
                || l.contains("главные новости россии и мира")
                || l.contains("последние новости дня на сайте")
                || l.contains("подробнее на сайте")
                || l.contains("подписывайтесь на наш канал")
                || l.contains("смотреть онлайн бесплатно")
                || l.contains("слушать онлайн бесплатно")
                || l.contains("403 forbidden") || l.contains("404 not found")
                || l.contains("access denied") || l.contains("just a moment...")
                || l.contains("cloudflare") || l.contains("captcha");
    }

    public static String unwrapDuckDuckGoHref(String href) {
        if (href == null) return "";
        String url = href.trim();
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        int uddg = url.indexOf("uddg=");
        if (uddg >= 0) {
            int start = uddg + 5;
            int end = url.indexOf('&', start);
            String enc = end > start ? url.substring(start, end) : url.substring(start);
            try {
                return URLDecoder.decode(enc, StandardCharsets.UTF_8.name());
            } catch (Throwable ignored) {}
        }
        return url;
    }

    public static String formatHitsForPrompt(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) return "";
        final int maxTotalChars = 610;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size() && sb.length() < maxTotalChars; i++) {
            SearchHit h = hits.get(i);
            if (h == null) continue;
            String body = !h.pageExcerpt.isEmpty() ? h.pageExcerpt : h.snippet;
            if (body.isEmpty()) body = h.title;
            int remaining = maxTotalChars - sb.length();
            if (remaining < 48) break;
            if (body.length() > Math.min(300, remaining - 24)) {
                body = body.substring(0, Math.max(24, Math.min(300, remaining - 27))).trim() + "…";
            }
            sb.append("- ").append(h.title).append(": ").append(body).append('\n');
        }
        return sb.toString().trim();
    }

    private static void addAllUnique(Map<String, SearchHit> map, List<SearchHit> items) {
        for (SearchHit hit : items) {
            String key = !hit.url.isEmpty() ? hit.url : hit.title;
            if (!key.isEmpty() && !map.containsKey(key)) {
                map.put(key, hit);
            }
        }
    }

    private static String fetchText(String url) {
        try {
            ColgramHttp.Response resp = ColgramHttp.get(url);
            if (resp != null && resp.code >= 200 && resp.code < 300 && resp.body != null) {
                return resp.body;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static boolean containsCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '\u0400' && ch <= '\u04FF') return true;
        }
        return false;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Throwable t) {
            return s;
        }
    }

    private static String cleanHtmlText(String s) {
        if (s == null) return "";
        String stripped = TAG_STRIP.matcher(s).replaceAll(" ");
        return unescapeHtml(stripped).replaceAll("\\s+", " ").trim();
    }

    private static String unescapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»");
    }

    /** Lightweight recursive-descent math parser (safe on arbitrary input, zero eval/reflection). */
    private static final class ExprParser {
        private final String s;
        private int pos = -1;
        private int ch;

        ExprParser(String s) {
            this.s = s;
            nextChar();
        }

        private void nextChar() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

        private boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        double parse() {
            double x = parseExpression();
            while (ch == ' ') nextChar();
            if (pos < s.length()) throw new IllegalArgumentException("Unexpected char");
            return x;
        }

        private double parseExpression() {
            double x = parseTerm();
            for (;;) {
                if (eat('+')) x += parseTerm();
                else if (eat('-')) x -= parseTerm();
                else return x;
            }
        }

        private double parseTerm() {
            double x = parseFactor();
            for (;;) {
                if (eat('*')) x *= parseFactor();
                else if (eat('/')) x /= parseFactor();
                else return x;
            }
        }

        private double parseFactor() {
            if (eat('+')) return parseFactor();
            if (eat('-')) return -parseFactor();
            double x;
            int startPos = this.pos;
            if (eat('(')) {
                x = parseExpression();
                if (!eat(')')) throw new IllegalArgumentException("Missing ')'");
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                x = Double.parseDouble(s.substring(startPos, this.pos));
            } else {
                throw new IllegalArgumentException("Invalid token");
            }
            if (eat('^')) x = Math.pow(x, parseFactor());
            return x;
        }
    }
}
