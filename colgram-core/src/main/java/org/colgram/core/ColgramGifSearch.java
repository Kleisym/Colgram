package org.colgram.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyless live GIF search via Tenor public search pages (zero API keys, zero payment).
 * Used automatically whenever Telegram's @gif inline bot is unavailable (e.g., bot accounts
 * returning BOT_METHOD_INVALID) or returns empty results.
 */
public final class ColgramGifSearch {

    public static final class GifItem {
        public final String id;
        public final String gifUrl;
        public final String previewUrl;
        public final int width;
        public final int height;
        public final String title;

        public GifItem(String id, String gifUrl, String previewUrl, int width, int height, String title) {
            this.id = id;
            this.gifUrl = gifUrl;
            this.previewUrl = previewUrl;
            this.width = width;
            this.height = height;
            this.title = title;
        }
    }

    private static final int MAX_CACHE = 48;
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;

    private static final class CacheEntry {
        final List<GifItem> items;
        final long ts;

        CacheEntry(List<GifItem> items, long ts) {
            this.items = items;
            this.ts = ts;
        }
    }

    private static final LinkedHashMap<String, CacheEntry> CACHE = new LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private static final Pattern MEDIA_TENOR_PATTERN = Pattern.compile(
            "https://media\\.tenor\\.com/([A-Za-z0-9_\\-]+)/([A-Za-z0-9_\\-\\.]+\\.(?:gif|webp|mp4))");
    private static final Pattern MEDIA_GIPHY_PATTERN = Pattern.compile(
            "https://media[0-9]*\\.giphy\\.com/media/(?:[^\"'\\\\\\s<>]+/)?([A-Za-z0-9]+)/((?:200w|200|giphy|giphy_s)\\.(?:gif|webp))");

    private static final Map<String, String> RU_TO_EN = new LinkedHashMap<>();
    static {
        RU_TO_EN.put("кот", "cat"); RU_TO_EN.put("котик", "cute cat"); RU_TO_EN.put("кошка", "cat"); RU_TO_EN.put("коты", "cats");
        RU_TO_EN.put("собака", "dog"); RU_TO_EN.put("пёс", "dog"); RU_TO_EN.put("пес", "dog"); RU_TO_EN.put("щенок", "puppy");
        RU_TO_EN.put("огонь", "fire"); RU_TO_EN.put("пламя", "fire"); RU_TO_EN.put("мем", "meme"); RU_TO_EN.put("мемы", "meme");
        RU_TO_EN.put("привет", "hello"); RU_TO_EN.put("хай", "hi"); RU_TO_EN.put("пока", "bye"); RU_TO_EN.put("доброе утро", "good morning");
        RU_TO_EN.put("спокойной ночи", "good night"); RU_TO_EN.put("любовь", "love"); RU_TO_EN.put("сердце", "heart");
        RU_TO_EN.put("поцелуй", "kiss"); RU_TO_EN.put("обнимашки", "hug"); RU_TO_EN.put("обнять", "hug");
        RU_TO_EN.put("смех", "laugh"); RU_TO_EN.put("смешно", "funny"); RU_TO_EN.put("ржу", "lol"); RU_TO_EN.put("хаха", "haha");
        RU_TO_EN.put("грусть", "sad"); RU_TO_EN.put("грустно", "sad"); RU_TO_EN.put("плач", "cry"); RU_TO_EN.put("слезы", "crying");
        RU_TO_EN.put("шок", "shocked"); RU_TO_EN.put("вау", "wow"); RU_TO_EN.put("круто", "cool"); RU_TO_EN.put("класс", "thumbs up");
        RU_TO_EN.put("да", "yes"); RU_TO_EN.put("нет", "no"); RU_TO_EN.put("ок", "ok"); RU_TO_EN.put("спасибо", "thank you");
        RU_TO_EN.put("аниме", "anime"); RU_TO_EN.put("танец", "dance"); RU_TO_EN.put("танцы", "dance"); RU_TO_EN.put("музыка", "music");
        RU_TO_EN.put("еда", "food"); RU_TO_EN.put("кофе", "coffee"); RU_TO_EN.put("пицца", "pizza"); RU_TO_EN.put("сон", "sleep");
        RU_TO_EN.put("спать", "sleep"); RU_TO_EN.put("деньги", "money"); RU_TO_EN.put("работа", "work"); RU_TO_EN.put("игры", "gaming");
        RU_TO_EN.put("праздник", "party"); RU_TO_EN.put("др", "happy birthday"); RU_TO_EN.put("подарок", "gift");
        RU_TO_EN.put("злость", "angry"); RU_TO_EN.put("злой", "angry"); RU_TO_EN.put("устал", "tired"); RU_TO_EN.put("страх", "scared");
        RU_TO_EN.put("фейспалм", "facepalm"); RU_TO_EN.put("клоун", "clown"); RU_TO_EN.put("робот", "robot");
        RU_TO_EN.put("хомяк", "hamster"); RU_TO_EN.put("медведь", "bear"); RU_TO_EN.put("лиса", "fox"); RU_TO_EN.put("панда", "panda");
        RU_TO_EN.put("капибара", "capybara"); RU_TO_EN.put("пингвин", "penguin"); RU_TO_EN.put("лягушка", "frog"); RU_TO_EN.put("обезьяна", "monkey");
        RU_TO_EN.put("машина", "car"); RU_TO_EN.put("цветы", "flowers"); RU_TO_EN.put("звезда", "star"); RU_TO_EN.put("молния", "lightning");
        RU_TO_EN.put("череп", "skull"); RU_TO_EN.put("призрак", "ghost"); RU_TO_EN.put("корона", "crown"); RU_TO_EN.put("дракон", "dragon");
        RU_TO_EN.put("прикол", "funny meme"); RU_TO_EN.put("смешной", "funny"); RU_TO_EN.put("котята", "kitten"); RU_TO_EN.put("котенок", "kitten");
        RU_TO_EN.put("лайк", "thumbs up"); RU_TO_EN.put("топ", "cool awesome"); RU_TO_EN.put("кайф", "chill vibe"); RU_TO_EN.put("кринж", "cringe");
        RU_TO_EN.put("ахах", "laughing"); RU_TO_EN.put("ахаха", "laughing"); RU_TO_EN.put("лол", "lol"); RU_TO_EN.put("кек", "funny laugh");
        RU_TO_EN.put("печаль", "sad"); RU_TO_EN.put("скучно", "bored"); RU_TO_EN.put("ура", "celebration yay"); RU_TO_EN.put("погнали", "lets go");
        RU_TO_EN.put("добро", "good"); RU_TO_EN.put("утро", "morning"); RU_TO_EN.put("ночь", "night"); RU_TO_EN.put("приветствие", "hello wave");
        RU_TO_EN.put("бот", "robot"); RU_TO_EN.put("код", "coding programmer"); RU_TO_EN.put("крипта", "crypto bitcoin"); RU_TO_EN.put("биткоин", "bitcoin");
        RU_TO_EN.put("роблокс", "roblox"); RU_TO_EN.put("майнкрафт", "minecraft"); RU_TO_EN.put("игра", "gaming"); RU_TO_EN.put("победа", "victory win");
        RU_TO_EN.put("поражение", "fail lose"); RU_TO_EN.put("гг", "gg gaming"); RU_TO_EN.put("босс", "boss cool"); RU_TO_EN.put("король", "king crown");
        RU_TO_EN.put("девочка", "anime girl"); RU_TO_EN.put("парень", "cool guy"); RU_TO_EN.put("друг", "friends hug"); RU_TO_EN.put("семья", "family");
        RU_TO_EN.put("дождь", "rain"); RU_TO_EN.put("снег", "snow winter"); RU_TO_EN.put("лето", "summer sun"); RU_TO_EN.put("солнце", "sun happy");
        RU_TO_EN.put("луна", "moon night"); RU_TO_EN.put("космос", "space galaxy"); RU_TO_EN.put("волк", "wolf"); RU_TO_EN.put("лев", "lion");
        RU_TO_EN.put("тигр", "tiger"); RU_TO_EN.put("кролик", "bunny rabbit"); RU_TO_EN.put("заяц", "bunny"); RU_TO_EN.put("утка", "duck");
        RU_TO_EN.put("гусь", "goose"); RU_TO_EN.put("акула", "shark"); RU_TO_EN.put("кит", "whale"); RU_TO_EN.put("сова", "owl");
    }

    private ColgramGifSearch() {}

    /** Translates common Russian search keywords into high-recall English GIF/Sticker queries. */
    public static String expandSearchQuery(String rawQuery) {
        if (rawQuery == null) return "";
        String trimmed = rawQuery.trim();
        if (trimmed.isEmpty()) return "";
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (RU_TO_EN.containsKey(lower)) {
            return RU_TO_EN.get(lower);
        }
        String[] parts = lower.split("\\s+");
        StringBuilder mapped = new StringBuilder();
        boolean anyMapped = false;
        for (String p : parts) {
            String rep = RU_TO_EN.get(p);
            if (rep == null && p.length() >= 4) {
                for (Map.Entry<String, String> e : RU_TO_EN.entrySet()) {
                    if (e.getKey().length() >= 4 && p.startsWith(e.getKey())) {
                        rep = e.getValue();
                        break;
                    }
                }
            }
            if (mapped.length() > 0) mapped.append(' ');
            if (rep != null) {
                mapped.append(rep);
                anyMapped = true;
            } else {
                mapped.append(p);
            }
        }
        return anyMapped ? mapped.toString().trim() : trimmed;
    }

    public static List<GifItem> search(String rawQuery, int limit) {
        int maxResults = Math.max(1, Math.min(limit, 40));
        String q = expandSearchQuery(rawQuery);
        String cacheKey = "gif:" + q.toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            CacheEntry ce = CACHE.get(cacheKey);
            if (ce != null && System.currentTimeMillis() - ce.ts < CACHE_TTL_MS && !ce.items.isEmpty()) {
                return new ArrayList<>(ce.items.subList(0, Math.min(maxResults, ce.items.size())));
            }
        }

        List<GifItem> items = fetchFromTenor(q, maxResults, false);
        if (items.isEmpty()) {
            items = fetchFromGiphy(q, maxResults, false);
        }
        if (items.isEmpty() && !q.isEmpty()) {
            String simplified = q.replaceAll("[^\\p{L}\\p{N}\\s]+", " ").trim();
            if (!simplified.equals(q) && !simplified.isEmpty()) {
                items = fetchFromTenor(simplified, maxResults, false);
                if (items.isEmpty()) {
                    items = fetchFromGiphy(simplified, maxResults, false);
                }
            }
        }
        if (!items.isEmpty()) {
            synchronized (CACHE) {
                CACHE.put(cacheKey, new CacheEntry(new ArrayList<>(items), System.currentTimeMillis()));
            }
        }
        return items;
    }

    /**
     * Keyless live Sticker search via Tenor & Giphy transparent sticker pages.
     * Powers sticker search cleanly even on bot accounts where MTProto searchStickers returns BOT_METHOD_INVALID.
     */
    public static List<GifItem> searchStickers(String rawQuery, int limit) {
        int maxResults = Math.max(1, Math.min(limit, 30));
        String q = expandSearchQuery(rawQuery);
        String cacheKey = "stk:" + q.toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            CacheEntry ce = CACHE.get(cacheKey);
            if (ce != null && System.currentTimeMillis() - ce.ts < CACHE_TTL_MS && !ce.items.isEmpty()) {
                return new ArrayList<>(ce.items.subList(0, Math.min(maxResults, ce.items.size())));
            }
        }
        List<GifItem> items = fetchFromTenor(q, maxResults, true);
        if (items.isEmpty()) {
            items = fetchFromGiphy(q, maxResults, true);
        }
        if (items.isEmpty()) {
            items = search(q, maxResults);
        }
        if (!items.isEmpty()) {
            synchronized (CACHE) {
                CACHE.put(cacheKey, new CacheEntry(new ArrayList<>(items), System.currentTimeMillis()));
            }
        }
        return items;
    }

    private static List<GifItem> fetchFromTenor(String query, int limit, boolean stickers) {
        List<GifItem> out = new ArrayList<>();
        try {
            String suffix = stickers ? "-stickers" : "-gifs";
            String urlStr;
            if (query.isEmpty()) {
                urlStr = "https://tenor.com/search/trending" + suffix;
            } else {
                String slug = URLEncoder.encode(query.replace(' ', '-'), "UTF-8");
                urlStr = "https://tenor.com/search/" + slug + suffix;
            }
            String html = httpGetString(urlStr, 12000);
            if (html == null || html.isEmpty()) {
                return out;
            }
            return parseTenorHtml(html, query, limit);
        } catch (Throwable ignored) {
            return out;
        }
    }

    private static List<GifItem> fetchFromGiphy(String query, int limit, boolean stickers) {
        List<GifItem> out = new ArrayList<>();
        try {
            String urlStr;
            if (query.isEmpty()) {
                urlStr = stickers ? "https://giphy.com/stickers" : "https://giphy.com/trending-gifs";
            } else {
                String slug = URLEncoder.encode(query.replace(' ', '-'), "UTF-8");
                urlStr = stickers ? ("https://giphy.com/search/" + slug + "-stickers") : ("https://giphy.com/search/" + slug);
            }
            String html = httpGetString(urlStr, 12000);
            if (html == null || html.isEmpty()) {
                return out;
            }
            return parseGiphyHtml(html, query, limit);
        } catch (Throwable ignored) {
            return out;
        }
    }

    /**
     * Parses direct media.giphy.com GIF/WebP URLs out of Giphy HTML as a resilient secondary provider.
     */
    public static List<GifItem> parseGiphyHtml(String html, String query, int limit) {
        List<GifItem> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        Map<String, String> gifById = new LinkedHashMap<>();
        Map<String, String> previewById = new LinkedHashMap<>();
        Matcher m = MEDIA_GIPHY_PATTERN.matcher(html);
        while (m.find()) {
            String fullUrl = m.group(0);
            String id = m.group(1);
            String file = m.group(2).toLowerCase(Locale.ROOT);
            if (id.length() < 8) continue;
            if (file.equals("giphy.gif") || file.equals("200.gif") || file.equals("200w.gif")) {
                if (!gifById.containsKey(id) || file.equals("giphy.gif")) {
                    gifById.put(id, fullUrl);
                }
            }
            if (file.equals("200w.webp") || file.equals("200.webp") || file.equals("giphy.webp") || file.equals("200.gif")) {
                if (!previewById.containsKey(id) || file.endsWith(".webp")) {
                    previewById.put(id, fullUrl);
                }
            }
            if (gifById.size() >= limit * 2) break;
        }
        int idx = 0;
        for (Map.Entry<String, String> entry : gifById.entrySet()) {
            if (out.size() >= limit) break;
            String id = entry.getKey();
            String gifUrl = entry.getValue();
            String preview = previewById.getOrDefault(id, gifUrl);
            out.add(new GifItem("colgram_gif_gp_" + id + "_" + (idx++), gifUrl, preview, 320, 240,
                    query == null || query.isEmpty() ? "GIF" : query));
        }
        if (out.isEmpty()) {
            for (Map.Entry<String, String> entry : previewById.entrySet()) {
                if (out.size() >= limit) break;
                String id = entry.getKey();
                String url = entry.getValue();
                out.add(new GifItem("colgram_gif_gp_" + id + "_" + (idx++), url, url, 320, 240,
                        query == null || query.isEmpty() ? "Sticker" : query));
            }
        }
        return out;
    }

    /**
     * Parses direct media.tenor.com GIF/WebP URLs out of Tenor HTML cleanly, deduplicating by asset family.
     * Package-public so JVM unit tests can test parsing deterministically offline.
     */
    public static List<GifItem> parseTenorHtml(String html, String query, int limit) {
        List<GifItem> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;

        // Group variants by Tenor stem (prefix before the 4-char quality code at end of folder id)
        // Tenor asset URLs look like https://media.tenor.com/<base><variant>/<slug>.gif
        Map<String, String> gifByStem = new LinkedHashMap<>();
        Map<String, String> previewByStem = new LinkedHashMap<>();
        Set<String> seenExact = new LinkedHashSet<>();

        Matcher m = MEDIA_TENOR_PATTERN.matcher(html);
        while (m.find()) {
            String fullUrl = m.group(0);
            if (!seenExact.add(fullUrl)) continue;
            String dir = m.group(1);
            String file = m.group(2);
            String lower = file.toLowerCase(Locale.ROOT);
            // Stem = first 11 chars of dir (same GIF in different formats shares the leading hash stem)
            String stem = dir.length() > 11 ? dir.substring(0, 11) : dir;
            if (lower.endsWith(".gif")) {
                // Prefer AAAAM (medium animated gif) or AAAAC (standard) over tiny thumbnails
                if (!gifByStem.containsKey(stem) || dir.endsWith("AAAAC") || dir.endsWith("AAAAM")) {
                    gifByStem.put(stem, fullUrl);
                }
                if (!previewByStem.containsKey(stem)) {
                    previewByStem.put(stem, fullUrl);
                }
            } else if (lower.endsWith(".webp")) {
                if (!previewByStem.containsKey(stem)) {
                    previewByStem.put(stem, fullUrl);
                }
            }
            if (gifByStem.size() >= limit * 2) break;
        }

        int idx = 0;
        for (Map.Entry<String, String> entry : gifByStem.entrySet()) {
            if (out.size() >= limit) break;
            String stem = entry.getKey();
            String gifUrl = entry.getValue();
            String preview = previewByStem.getOrDefault(stem, gifUrl);
            String id = "colgram_gif_" + stem + "_" + (idx++);
            out.add(new GifItem(id, gifUrl, preview, 320, 240, query == null || query.isEmpty() ? "GIF" : query));
        }

        // Fallback if only .webp previews were present
        if (out.isEmpty()) {
            for (Map.Entry<String, String> entry : previewByStem.entrySet()) {
                if (out.size() >= limit) break;
                String stem = entry.getKey();
                String url = entry.getValue();
                out.add(new GifItem("colgram_gif_" + stem + "_" + (idx++), url, url, 320, 240, query == null ? "GIF" : query));
            }
        }
        return out;
    }

    private static final java.util.concurrent.ExecutorService SHARED_DL_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "colgram-gif-dl");
                t.setDaemon(true);
                return t;
            });

    /**
     * Downloads up to maxCount sticker/GIF previews in parallel (6 threads) so search grids populate in <1 second.
     */
    public static List<File> downloadBatchParallel(List<GifItem> items, File cacheDir, int maxCount) {
        List<File> results = new ArrayList<>();
        if (items == null || items.isEmpty() || cacheDir == null || maxCount <= 0) return results;
        int n = Math.min(items.size(), maxCount);
        final File[] slots = new File[n];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final GifItem item = items.get(i);
            SHARED_DL_POOL.execute(() -> {
                try {
                    String dlUrl = (item.previewUrl != null && !item.previewUrl.isEmpty()) ? item.previewUrl : item.gifUrl;
                    File f = downloadToCache(dlUrl, cacheDir);
                    if ((f == null || !f.exists() || f.length() < 128) && item.gifUrl != null && !item.gifUrl.equals(dlUrl)) {
                        f = downloadToCache(item.gifUrl, cacheDir);
                    }
                    if (f != null && f.exists() && f.length() >= 128) {
                        slots[idx] = f;
                    }
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(7000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        for (File f : slots) {
            results.add(f);
        }
        return results;
    }

    /**
     * Downloads a remote GIF URL into cacheDir so it can be sent cleanly into any chat (even on bot accounts).
     */
    public static File downloadToCache(String urlStr, File cacheDir) {
        if (urlStr == null || urlStr.isEmpty() || cacheDir == null) return null;
        try {
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            String cleanPath = urlStr.toLowerCase(Locale.ROOT);
            int qIdx = cleanPath.indexOf('?');
            if (qIdx >= 0) cleanPath = cleanPath.substring(0, qIdx);
            int hIdx = cleanPath.indexOf('#');
            if (hIdx >= 0) cleanPath = cleanPath.substring(0, hIdx);
            String ext = cleanPath.endsWith(".mp4") ? ".mp4"
                    : (cleanPath.endsWith(".webp") ? ".webp" : ".gif");
            File target = new File(cacheDir, "colgram_gif_" + sha1Short(urlStr) + ext);
            if (target.exists() && target.length() > 512) {
                return target;
            }
            HttpURLConnection conn = ColgramHttp.openBinaryStream(urlStr, 4000, 6000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                conn.disconnect();
                return null;
            }
            File tmp = new File(cacheDir, target.getName() + ".tmp");
            try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                    if (total > 12L * 1024L * 1024L) { // 12 MB safety cap
                        break;
                    }
                }
            } finally {
                conn.disconnect();
            }
            if (tmp.length() > 256) {
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) {
                    return tmp;
                }
                return target;
            }
            tmp.delete();
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static HttpURLConnection openSmartConnection(String urlStr, int timeoutMs) throws Exception {
        URL url = new URL(urlStr);
        try {
            int dpiPort = ColgramDpiBypass.isBound() ? ColgramDpiBypass.activePort() : 0;
            if (dpiPort > 0) {
                java.net.Proxy socks = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress("127.0.0.1", dpiPort));
                HttpURLConnection c = (HttpURLConnection) url.openConnection(socks);
                c.setConnectTimeout(Math.min(timeoutMs, 6000));
                c.setReadTimeout(timeoutMs);
                return c;
            }
        } catch (Throwable ignored) {}
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        return conn;
    }

    private static String httpGetString(String urlStr, int timeoutMs) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = (attempt == 0)
                    ? (HttpURLConnection) new URL(urlStr).openConnection()
                    : openSmartConnection(urlStr, timeoutMs);
            conn.setConnectTimeout(attempt == 0 ? Math.min(timeoutMs, 7000) : timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    continue;
                }
                try (InputStream is = conn.getInputStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    int total = 0;
                    while ((n = is.read(buf)) > 0 && total < 768 * 1024) {
                        bos.write(buf, 0, n);
                        total += n;
                    }
                    String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    if (!body.isEmpty()) {
                        return body;
                    }
                }
            } catch (Throwable ignored) {
                if (attempt == 1) {
                    return "";
                }
            } finally {
                conn.disconnect();
            }
        }
        return "";
    }

    private static String sha1Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < d.length; i++) {
                sb.append(String.format(Locale.US, "%02x", d[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
