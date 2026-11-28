package org.colgram.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;

/**
 * Local client-side translator without Telegram Premium.
 * Routes keyless requests through Colgram's active local proxy/DPI bypass + DoH + polymorphic
 * browser User-Agent, with a bounded LRU memory cache and gzip-compressed disk cache.
 */
public final class ColgramLocalTranslator {

    private static final int MAX_MEM_CACHE = 512;
    private static final int MAX_DISK_CACHE = 2000;
    private static final int MAX_CHUNK_CHARS = 1500;
    private static final String CACHE_FILENAME = "colgram_translate_cache_v1.gz";

    public interface Callback {
        void onResult(boolean ok, String translatedText, boolean rateLimited);
    }

    private static final Object CACHE_LOCK = new Object();
    private static final Object DISK_WRITE_LOCK = new Object();
    private static final LinkedHashMap<String, String> MEM_CACHE =
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_MEM_CACHE;
                }
            };
    private static volatile File diskCacheFile;
    private static volatile boolean diskLoaded = false;

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            r.run();
        }, "colgram-local-translate");
        t.setDaemon(true);
        return t;
    });

    private ColgramLocalTranslator() {}

    public static void translateAsync(Context ctx, String text, String fromLang, String toLang, Callback callback) {
        if (callback == null) return;
        if (text == null || text.trim().isEmpty()) {
            deliverMain(() -> callback.onResult(true, text == null ? "" : text, false));
            return;
        }
        final String targetLang = normalizeLang(toLang);
        final String sourceLang = normalizeSourceLang(fromLang);
        final String cacheKey = targetLang + "\0" + text;
        final Context appCtx = ctx != null && ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;

        if (diskLoaded) {
            synchronized (CACHE_LOCK) {
                String cached = MEM_CACHE.get(cacheKey);
                if (cached != null) {
                    deliverMain(() -> callback.onResult(true, cached, false));
                    return;
                }
            }
        }

        EXEC.execute(() -> {
            ensureDiskLoaded(appCtx);
            synchronized (CACHE_LOCK) {
                String cached = MEM_CACHE.get(cacheKey);
                if (cached != null) {
                    deliverMain(() -> callback.onResult(true, cached, false));
                    return;
                }
            }
            boolean[] rateLimited = new boolean[]{false};
            String translated = translateChunksSync(text, sourceLang, targetLang, rateLimited);
            if (translated != null) {
                putCache(appCtx, cacheKey, translated);
                deliverMain(() -> callback.onResult(true, translated, false));
            } else {
                deliverMain(() -> callback.onResult(false, null, rateLimited[0]));
            }
        });
    }

    private static String translateChunksSync(String text, String fromLang, String toLang, boolean[] rateLimited) {
        List<String> chunks = splitChunks(text, MAX_CHUNK_CHARS);
        StringBuilder out = new StringBuilder(text.length());
        for (String chunk : chunks) {
            String res = translateSingleSync(chunk, fromLang, toLang, rateLimited);
            if (res == null) {
                return null;
            }
            out.append(res);
        }
        return out.toString();
    }

    private static String translateSingleSync(String chunk, String fromLang, String toLang, boolean[] rateLimited) {
        try {
            String q = URLEncoder.encode(chunk, "UTF-8");
            String sl = URLEncoder.encode(fromLang, "UTF-8");
            String tl = URLEncoder.encode(toLang, "UTF-8");
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
                    + sl + "&tl=" + tl + "&dt=t&ie=UTF-8&oe=UTF-8&q=" + q;

            Proxy proxy = null;
            try {
                proxy = ColgramHttp.pickRelayProxy();
            } catch (Throwable ignored) {}

            String raw = fetchViaProxyOrDirect(urlStr, proxy, rateLimited);
            if (raw == null && proxy != null && shouldAllowNoProxyFallback()) {
                raw = fetchViaProxyOrDirect(urlStr, Proxy.NO_PROXY, rateLimited);
            }
            if (raw == null) {
                return null;
            }
            String parsed = parseTranslateGtxResponse(raw);
            if (parsed != null && chunk.startsWith("\n") && !parsed.startsWith("\n")) {
                parsed = "\n" + parsed;
            }
            return parsed;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean shouldAllowNoProxyFallback() {
        return !ColgramConfig.isStrictProxySecurityEnabled()
                && !ColgramConfig.isMaxAnonymityEnabled();
    }

    static boolean shouldFailClosedWhenUnpinned(InetAddress via, Proxy effectiveProxy) {
        boolean socks = effectiveProxy != null && effectiveProxy.type() == Proxy.Type.SOCKS;
        return via == null && !socks && !shouldAllowNoProxyFallback();
    }

    private static String fetchViaProxyOrDirect(String urlStr, Proxy proxy, boolean[] rateLimited) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            Proxy effectiveProxy = proxy != null ? proxy : Proxy.NO_PROXY;
            conn = (HttpURLConnection) url.openConnection(effectiveProxy);
            if (conn instanceof HttpsURLConnection) {
                InetAddress via = null;
                try {
                    via = ColgramDohResolver.resolveOrNull(url.getHost());
                } catch (Throwable ignored) {}
                if (via != null || effectiveProxy.type() == Proxy.Type.SOCKS) {
                    conn = ColgramPinnedConnection.create((HttpsURLConnection) conn, url, via, effectiveProxy);
                } else if (shouldFailClosedWhenUnpinned(via, effectiveProxy)) {
                    conn.disconnect();
                    return null;
                }
            }
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", ColgramPolymorphicStealth.pickBrowserUserAgent());
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code == 429) {
                rateLimited[0] = true;
                return null;
            }
            if (code < 200 || code >= 300) {
                return null;
            }
            try (InputStream is = new BufferedInputStream(conn.getInputStream());
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > 512 * 1024) break;
                    bos.write(buf, 0, n);
                }
                return bos.toString("UTF-8");
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Parses Google Translate `client=gtx` nested JSON array without org.json dependency
     * so pure-JVM tests can verify extraction directly.
     */
    public static String parseTranslateGtxResponse(String json) {
        if (json == null || json.isEmpty()) return null;
        int len = json.length();
        int i = 0;
        while (i < len && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= len || json.charAt(i) != '[') return null;
        i++;
        while (i < len && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= len || json.charAt(i) != '[') return null;
        i++; // inside outer array element 0: array of [translated, original, ...]

        StringBuilder out = new StringBuilder();
        boolean any = false;
        while (i < len) {
            while (i < len && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
            if (i >= len || json.charAt(i) == ']') break;
            if (json.charAt(i) != '[') {
                i = skipJsonValue(json, i);
                continue;
            }
            i++; // inside inner tuple
            while (i < len && Character.isWhitespace(json.charAt(i))) i++;
            if (i < len && json.charAt(i) == '"') {
                StringBuilder seg = new StringBuilder();
                i = readJsonString(json, i, seg);
                String s = seg.toString();
                if (!"null".equals(s)) {
                    out.append(s);
                    any = true;
                }
            }
            // skip rest of inner tuple
            int depth = 1;
            boolean inStr = false;
            boolean esc = false;
            while (i < len && depth > 0) {
                char c = json.charAt(i++);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = !inStr;
                } else if (!inStr) {
                    if (c == '[') depth++;
                    else if (c == ']') depth--;
                }
            }
        }
        return any ? out.toString() : null;
    }

    private static int readJsonString(String s, int quoteIdx, StringBuilder out) {
        int i = quoteIdx + 1;
        boolean esc = false;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (esc) {
                if (c == 'n') out.append('\n');
                else if (c == 'r') out.append('\r');
                else if (c == 't') out.append('\t');
                else if (c == 'u' && i + 4 <= s.length()) {
                    try {
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } catch (Throwable ignored) {}
                } else {
                    out.append(c);
                }
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                return i;
            } else {
                out.append(c);
            }
        }
        return i;
    }

    private static int skipJsonValue(String s, int start) {
        int i = start;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (esc) {
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                inStr = !inStr;
            } else if (!inStr) {
                if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') {
                    if (depth == 0) return i;
                    depth--;
                } else if (c == ',' && depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return i;
    }

    public static List<String> splitChunks(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        int n = text.length();
        int start = 0;
        while (start < n) {
            int end = Math.min(n, start + maxLen);
            if (end < n) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start + maxLen / 3) {
                    end = nl + 1;
                } else {
                    int sp = text.lastIndexOf(' ', end);
                    if (sp > start + maxLen / 2) {
                        end = sp + 1;
                    }
                }
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private static String normalizeLang(String lang) {
        if (lang == null || lang.trim().isEmpty()) {
            String def = Locale.getDefault().getLanguage();
            return def == null || def.isEmpty() ? "ru" : def;
        }
        String base = lang.trim().split("[_-]")[0].toLowerCase(Locale.ROOT);
        if ("nb".equals(base)) return "no";
        if ("iw".equals(base)) return "he";
        return base.isEmpty() ? "ru" : base;
    }

    private static String normalizeSourceLang(String lang) {
        if (lang == null || lang.trim().isEmpty() || "und".equalsIgnoreCase(lang.trim())) {
            return "auto";
        }
        return normalizeLang(lang);
    }

    private static void ensureDiskLoaded(Context ctx) {
        if (diskLoaded || ctx == null) return;
        synchronized (CACHE_LOCK) {
            if (diskLoaded) return;
            diskLoaded = true;
            File filesDir = ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext().getFilesDir() : ctx.getFilesDir();
            if (filesDir == null) return;
            diskCacheFile = new File(filesDir, CACHE_FILENAME);
            if (!diskCacheFile.isFile()) return;
            try (DataInputStream dis = new DataInputStream(new GZIPInputStream(new FileInputStream(diskCacheFile)))) {
                int count = dis.readInt();
                if (count < 0 || count > MAX_DISK_CACHE * 2) return;
                for (int i = 0; i < count; i++) {
                    int kl = dis.readInt();
                    int vl = dis.readInt();
                    if (kl <= 0 || kl > 65536 || vl < 0 || vl > 65536) break;
                    byte[] kb = new byte[kl];
                    byte[] vb = new byte[vl];
                    dis.readFully(kb);
                    dis.readFully(vb);
                    MEM_CACHE.put(new String(kb, StandardCharsets.UTF_8), new String(vb, StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void putCache(Context ctx, String key, String value) {
        ensureDiskLoaded(ctx);
        int sizeSnapshot;
        synchronized (CACHE_LOCK) {
            MEM_CACHE.put(key, value);
            sizeSnapshot = MEM_CACHE.size();
        }
        if (diskCacheFile != null && (sizeSnapshot % 10 == 0 || sizeSnapshot <= 5)) {
            persistDiskCacheAsync();
        }
    }

    private static void persistDiskCacheAsync() {
        final File target = diskCacheFile;
        if (target == null) return;
        final List<Map.Entry<String, String>> snapshot;
        synchronized (CACHE_LOCK) {
            snapshot = new ArrayList<>(MEM_CACHE.entrySet());
        }
        EXEC.execute(() -> {
            synchronized (DISK_WRITE_LOCK) {
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                File tmp = new File(parent, CACHE_FILENAME + ".tmp");
                try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
                    int start = Math.max(0, snapshot.size() - MAX_DISK_CACHE);
                    int count = snapshot.size() - start;
                    dos.writeInt(count);
                    for (int i = start; i < snapshot.size(); i++) {
                        Map.Entry<String, String> e = snapshot.get(i);
                        byte[] kb = e.getKey().getBytes(StandardCharsets.UTF_8);
                        byte[] vb = e.getValue().getBytes(StandardCharsets.UTF_8);
                        dos.writeInt(kb.length);
                        dos.writeInt(vb.length);
                        dos.write(kb);
                        dos.write(vb);
                    }
                    dos.flush();
                } catch (Throwable t) {
                    tmp.delete();
                    return;
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                }
            }
        });
    }

    private static void deliverMain(Runnable r) {
        try {
            Looper main = Looper.getMainLooper();
            if (main == null || Looper.myLooper() == main) {
                r.run();
            } else {
                new Handler(main).post(r);
            }
        } catch (Throwable ignored) {
            r.run();
        }
    }
}
