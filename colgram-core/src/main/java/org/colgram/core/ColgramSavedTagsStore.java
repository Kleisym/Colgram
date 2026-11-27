package org.colgram.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone SQLite store (colgram_saved_tags.db) for local emoji tags in Saved Messages.
 * Maintains an O(1) per-account in-memory cache so ChatMessageCell rendering never hits disk,
 * and falls back seamlessly to pure-JVM in-memory storage when Context is absent in unit tests.
 */
public final class ColgramSavedTagsStore {

    private static final String DB_NAME = "colgram_saved_tags.db";
    private static final int DB_VERSION = 1;

    public static final class TagPreset {
        public final String emoji;
        public final String label;

        public TagPreset(String emoji, String label) {
            this.emoji = emoji;
            this.label = label;
        }
    }

    public static final class TagEntry {
        public final int msgId;
        public final String emoji;
        public final String label;
        public final long updatedAt;

        public TagEntry(int msgId, String emoji, String label, long updatedAt) {
            this.msgId = msgId;
            this.emoji = emoji;
            this.label = label == null ? "" : label;
            this.updatedAt = updatedAt;
        }
    }

    public static final class TagCount {
        public final String emoji;
        public final String label;
        public final int count;
        public final long latestUpdatedAt;

        public TagCount(String emoji, String label, int count, long latestUpdatedAt) {
            this.emoji = emoji;
            this.label = label == null ? "" : label;
            this.count = count;
            this.latestUpdatedAt = latestUpdatedAt;
        }
    }

    public interface ChangeListener {
        void onTagsChanged(int account);
    }

    public static final List<TagPreset> DEFAULT_PRESETS;
    private static final Map<String, String> DEFAULT_LABELS = new LinkedHashMap<>();

    static {
        List<TagPreset> list = new ArrayList<>();
        list.add(new TagPreset("\u2B50", "Важное"));
        list.add(new TagPreset("\uD83D\uDCBC", "Работа"));
        list.add(new TagPreset("\uD83D\uDCA1", "Идеи"));
        list.add(new TagPreset("\uD83D\uDCCC", "Закреп"));
        list.add(new TagPreset("\uD83D\uDD11", "Пароли"));
        list.add(new TagPreset("\uD83D\uDD17", "Ссылки"));
        list.add(new TagPreset("\uD83D\uDED2", "Покупки"));
        list.add(new TagPreset("\uD83C\uDFAC", "Медиа"));
        list.add(new TagPreset("\uD83D\uDCDA", "Учёба"));
        list.add(new TagPreset("\uD83D\uDCB0", "Финансы"));
        list.add(new TagPreset("\u2764\uFE0F", "Личное"));
        list.add(new TagPreset("\uD83D\uDD25", "Срочно"));
        list.add(new TagPreset("\u2705", "Готово"));
        list.add(new TagPreset("\uD83D\uDCDD", "Заметки"));
        list.add(new TagPreset("\uD83C\uDFB5", "Музыка"));
        list.add(new TagPreset("\uD83C\uDFAE", "Игры"));
        list.add(new TagPreset("\u2708\uFE0F", "Поездки"));
        list.add(new TagPreset("\uD83C\uDFE0", "Дом"));
        list.add(new TagPreset("\uD83D\uDCF7", "Фото"));
        list.add(new TagPreset("\uD83C\uDF81", "Подарки"));
        list.add(new TagPreset("\uD83E\uDDE0", "Промпты"));
        list.add(new TagPreset("\uD83D\uDEE0\uFE0F", "Инструменты"));
        list.add(new TagPreset("\uD83D\uDD12", "Сейф"));
        list.add(new TagPreset("\u26A1", "Быстро"));
        DEFAULT_PRESETS = Collections.unmodifiableList(list);
        for (TagPreset p : list) {
            DEFAULT_LABELS.put(p.emoji, p.label);
        }
    }

    private static final ExecutorService DB_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "colgram-saved-tags-db");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, List<TagEntry>>> CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, ConcurrentHashMap<String, String>> CUSTOM_LABELS =
            new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ChangeListener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile DbHelper dbHelper;
    private static volatile boolean loadedFromDb = false;

    private ColgramSavedTagsStore() {}

    private static final class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) {
            super(ctx, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            try {
                db.enableWriteAheadLogging();
            } catch (Throwable ignored) {}
            try (Cursor c = db.rawQuery("PRAGMA synchronous=NORMAL", null)) {
                if (c != null) c.moveToFirst();
            } catch (Throwable ignored) {}
            try (Cursor c = db.rawQuery("PRAGMA busy_timeout=5000", null)) {
                if (c != null) c.moveToFirst();
            } catch (Throwable ignored) {}
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS saved_tags ("
                    + "account INTEGER NOT NULL,"
                    + "msg_id INTEGER NOT NULL,"
                    + "emoji TEXT NOT NULL,"
                    + "label TEXT NOT NULL DEFAULT '',"
                    + "updated_at INTEGER NOT NULL,"
                    + "PRIMARY KEY(account, msg_id, emoji)"
                    + ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_saved_tags_acc_emoji "
                    + "ON saved_tags(account, emoji, msg_id DESC)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            onCreate(db);
        }
    }

    public static void init(Context ctx) {
        if (ctx == null) return;
        synchronized (ColgramSavedTagsStore.class) {
            if (dbHelper == null) {
                Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                dbHelper = new DbHelper(app);
            }
            if (!loadedFromDb) {
                loadedFromDb = true;
                final DbHelper helper = dbHelper;
                DB_EXEC.execute(() -> loadAllFromDb(helper));
            }
        }
    }

    private static void ensureDbSync(Context ctx) {
        if (ctx != null && dbHelper == null) {
            init(ctx);
        }
    }

    private static void loadAllFromDb(DbHelper helper) {
        if (helper == null) return;
        Cursor c = null;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            c = db.rawQuery("SELECT account, msg_id, emoji, label, updated_at FROM saved_tags ORDER BY updated_at ASC", null);
            Set<Integer> accounts = ConcurrentHashMap.newKeySet();
            while (c != null && c.moveToNext()) {
                int acc = c.getInt(0);
                int msgId = c.getInt(1);
                String emoji = c.getString(2);
                String label = c.getString(3);
                long updatedAt = c.getLong(4);
                if (msgId > 0 && emoji != null && !emoji.isEmpty()) {
                    putInCache(acc, msgId, emoji, label, updatedAt);
                    accounts.add(acc);
                }
            }
            for (int acc : accounts) {
                notifyListeners(acc);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void addListener(ChangeListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(ChangeListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    private static void notifyListeners(int account) {
        for (ChangeListener l : LISTENERS) {
            try {
                l.onTagsChanged(account);
            } catch (Throwable ignored) {}
        }
    }

    private static ConcurrentHashMap<Integer, List<TagEntry>> accountMap(int account) {
        return CACHE.computeIfAbsent(account, k -> new ConcurrentHashMap<>());
    }

    private static String normalizeEmoji(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() > 16) {
            t = t.substring(0, 16);
        }
        return t;
    }

    private static String resolveLabel(int account, String emoji, String explicitLabel) {
        if (explicitLabel != null && !explicitLabel.trim().isEmpty()) {
            return explicitLabel.trim();
        }
        ConcurrentHashMap<String, String> custom = CUSTOM_LABELS.get(account);
        if (custom != null) {
            String prev = custom.get(emoji);
            if (prev != null && !prev.isEmpty()) return prev;
        }
        String def = DEFAULT_LABELS.get(emoji);
        return def != null ? def : "";
    }

    private static void putInCache(int account, int msgId, String emoji, String label, long updatedAt) {
        ConcurrentHashMap<Integer, List<TagEntry>> map = accountMap(account);
        synchronized (map) {
            List<TagEntry> current = map.get(msgId);
            List<TagEntry> next = new ArrayList<>(current == null ? 2 : current.size() + 1);
            if (current != null) {
                for (TagEntry e : current) {
                    if (!e.emoji.equals(emoji)) {
                        next.add(e);
                    }
                }
            }
            String resolvedLabel = resolveLabel(account, emoji, label);
            if (!resolvedLabel.isEmpty()) {
                CUSTOM_LABELS.computeIfAbsent(account, k -> new ConcurrentHashMap<>()).put(emoji, resolvedLabel);
            }
            next.add(new TagEntry(msgId, emoji, resolvedLabel, updatedAt));
            map.put(msgId, Collections.unmodifiableList(next));
        }
    }

    private static boolean removeFromCache(int account, int msgId, String emoji) {
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map == null) return false;
        synchronized (map) {
            List<TagEntry> current = map.get(msgId);
            if (current == null || current.isEmpty()) return false;
            List<TagEntry> next = new ArrayList<>(current.size());
            boolean removed = false;
            for (TagEntry e : current) {
                if (e.emoji.equals(emoji)) {
                    removed = true;
                } else {
                    next.add(e);
                }
            }
            if (!removed) return false;
            if (next.isEmpty()) {
                map.remove(msgId);
            } else {
                map.put(msgId, Collections.unmodifiableList(next));
            }
            return true;
        }
    }

    public static List<String> getTags(int account, int msgId) {
        if (msgId <= 0) return Collections.emptyList();
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map == null) return Collections.emptyList();
        List<TagEntry> entries = map.get(msgId);
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(entries.size());
        for (TagEntry e : entries) {
            out.add(e.emoji);
        }
        return out;
    }

    public static boolean hasTag(int account, int msgId, String emoji) {
        String norm = normalizeEmoji(emoji);
        if (msgId <= 0 || norm.isEmpty()) return false;
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map == null) return false;
        List<TagEntry> entries = map.get(msgId);
        if (entries == null) return false;
        for (TagEntry e : entries) {
            if (e.emoji.equals(norm)) return true;
        }
        return false;
    }

    public static boolean toggleTag(Context ctx, int account, int msgId, String emoji, String label) {
        String norm = normalizeEmoji(emoji);
        if (msgId <= 0 || norm.isEmpty()) return false;
        if (hasTag(account, msgId, norm)) {
            removeTag(ctx, account, msgId, norm);
            return false;
        } else {
            addTag(ctx, account, msgId, norm, label);
            return true;
        }
    }

    public static void addTag(Context ctx, int account, int msgId, String emoji, String label) {
        String norm = normalizeEmoji(emoji);
        if (msgId <= 0 || norm.isEmpty()) return;
        ensureDbSync(ctx);
        long now = System.currentTimeMillis();
        String resolved = resolveLabel(account, norm, label);
        putInCache(account, msgId, norm, resolved, now);
        notifyListeners(account);
        final DbHelper helper = dbHelper;
        if (helper != null) {
            DB_EXEC.execute(() -> {
                try {
                    SQLiteDatabase db = helper.getWritableDatabase();
                    ContentValues cv = new ContentValues();
                    cv.put("account", account);
                    cv.put("msg_id", msgId);
                    cv.put("emoji", norm);
                    cv.put("label", resolved);
                    cv.put("updated_at", now);
                    db.insertWithOnConflict("saved_tags", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                } catch (Throwable ignored) {}
            });
        }
    }

    public static void removeTag(Context ctx, int account, int msgId, String emoji) {
        String norm = normalizeEmoji(emoji);
        if (msgId <= 0 || norm.isEmpty()) return;
        ensureDbSync(ctx);
        if (!removeFromCache(account, msgId, norm)) {
            return;
        }
        notifyListeners(account);
        final DbHelper helper = dbHelper;
        if (helper != null) {
            DB_EXEC.execute(() -> {
                try {
                    SQLiteDatabase db = helper.getWritableDatabase();
                    db.delete("saved_tags", "account=? AND msg_id=? AND emoji=?",
                            new String[]{String.valueOf(account), String.valueOf(msgId), norm});
                } catch (Throwable ignored) {}
            });
        }
    }

    public static void clearMessageTags(Context ctx, int account, int msgId) {
        if (msgId <= 0) return;
        ensureDbSync(ctx);
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map != null) {
            synchronized (map) {
                map.remove(msgId);
            }
        }
        notifyListeners(account);
        final DbHelper helper = dbHelper;
        if (helper != null) {
            DB_EXEC.execute(() -> {
                try {
                    SQLiteDatabase db = helper.getWritableDatabase();
                    db.delete("saved_tags", "account=? AND msg_id=?",
                            new String[]{String.valueOf(account), String.valueOf(msgId)});
                } catch (Throwable ignored) {}
            });
        }
    }

    public static int getTotalTaggedMessagesCount(int account) {
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        return map == null ? 0 : map.size();
    }

    public static String getLabelForEmoji(int account, String emoji) {
        String norm = normalizeEmoji(emoji);
        if (norm.isEmpty()) return "";
        return resolveLabel(account, norm, null);
    }

    public static List<TagCount> getTagCounts(int account) {
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map == null || map.isEmpty()) return Collections.emptyList();
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Long> latest = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        synchronized (map) {
            for (List<TagEntry> list : map.values()) {
                for (TagEntry e : list) {
                    int[] c = counts.get(e.emoji);
                    if (c == null) {
                        counts.put(e.emoji, new int[]{1});
                        latest.put(e.emoji, e.updatedAt);
                        labels.put(e.emoji, e.label);
                    } else {
                        c[0]++;
                        Long prevTs = latest.get(e.emoji);
                        if (prevTs == null || e.updatedAt > prevTs) {
                            latest.put(e.emoji, e.updatedAt);
                            if (!e.label.isEmpty()) {
                                labels.put(e.emoji, e.label);
                            }
                        }
                    }
                }
            }
        }
        List<TagCount> out = new ArrayList<>(counts.size());
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            String emoji = entry.getKey();
            Long ts = latest.get(emoji);
            out.add(new TagCount(emoji, labels.get(emoji), entry.getValue()[0], ts != null ? ts : 0L));
        }
        Collections.sort(out, (a, b) -> {
            int cmp = Integer.compare(b.count, a.count);
            return cmp != 0 ? cmp : Long.compare(b.latestUpdatedAt, a.latestUpdatedAt);
        });
        return out;
    }

    public static List<Integer> getTaggedMessageIds(int account, String emoji) {
        String norm = normalizeEmoji(emoji);
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map == null || map.isEmpty()) return Collections.emptyList();
        List<Integer> ids = new ArrayList<>();
        synchronized (map) {
            for (Map.Entry<Integer, List<TagEntry>> entry : map.entrySet()) {
                if (norm.isEmpty()) {
                    if (!entry.getValue().isEmpty()) {
                        ids.add(entry.getKey());
                    }
                } else {
                    for (TagEntry t : entry.getValue()) {
                        if (t.emoji.equals(norm)) {
                            ids.add(entry.getKey());
                            break;
                        }
                    }
                }
            }
        }
        Collections.sort(ids, Collections.reverseOrder());
        return ids;
    }

    public static String exportJson(int account) {
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        if (map != null) {
            synchronized (map) {
                List<Integer> msgIds = new ArrayList<>(map.keySet());
                Collections.sort(msgIds);
                for (int msgId : msgIds) {
                    List<TagEntry> entries = map.get(msgId);
                    if (entries == null) continue;
                    for (TagEntry e : entries) {
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"msgId\":").append(e.msgId)
                                .append(",\"emoji\":\"").append(escapeJson(e.emoji))
                                .append("\",\"label\":\"").append(escapeJson(e.label))
                                .append("\",\"updatedAt\":").append(e.updatedAt).append('}');
                    }
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }

    public static int importJson(Context ctx, int account, String json) {
        if (json == null || json.trim().isEmpty()) return 0;
        ensureDbSync(ctx);
        int imported = 0;
        int pos = 0;
        int len = json.length();
        while (pos < len) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd < 0) break;
            String obj = json.substring(objStart + 1, objEnd);
            int msgId = parseJsonInt(obj, "msgId", 0);
            String emoji = normalizeEmoji(parseJsonString(obj, "emoji"));
            String label = parseJsonString(obj, "label");
            if (msgId > 0 && !emoji.isEmpty()) {
                addTag(ctx, account, msgId, emoji, label);
                imported++;
            }
            pos = objEnd + 1;
        }
        return imported;
    }

    private static int findMatchingBrace(String s, int start) {
        boolean inStr = false;
        boolean esc = false;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                inStr = !inStr;
            } else if (!inStr && c == '}') {
                return i;
            }
        }
        return -1;
    }

    private static String parseJsonString(String obj, String key) {
        String pat = "\"" + key + "\"";
        int idx = obj.indexOf(pat);
        if (idx < 0) return "";
        int colon = obj.indexOf(':', idx + pat.length());
        if (colon < 0) return "";
        int q1 = obj.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int i = q1 + 1; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (esc) {
                if (c == 'n') out.append('\n');
                else if (c == 'r') out.append('\r');
                else if (c == 't') out.append('\t');
                else if (c == 'u' && i + 4 < obj.length()) {
                    try {
                        out.append((char) Integer.parseInt(obj.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (Throwable ignored) {}
                } else {
                    out.append(c);
                }
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return "";
    }

    private static int parseJsonInt(String obj, String key, int defVal) {
        String pat = "\"" + key + "\"";
        int idx = obj.indexOf(pat);
        if (idx < 0) return defVal;
        int colon = obj.indexOf(':', idx + pat.length());
        if (colon < 0) return defVal;
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        int start = i;
        if (i < obj.length() && obj.charAt(i) == '-') i++;
        while (i < obj.length() && Character.isDigit(obj.charAt(i))) i++;
        if (i == start) return defVal;
        try {
            return Integer.parseInt(obj.substring(start, i));
        } catch (Throwable t) {
            return defVal;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') out.append("\\\"");
            else if (c == '\\') out.append("\\\\");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else out.append(c);
        }
        return out.toString();
    }

    public static void resetForTests() {
        CACHE.clear();
        CUSTOM_LABELS.clear();
        LISTENERS.clear();
        dbHelper = null;
        loadedFromDb = false;
    }
}
