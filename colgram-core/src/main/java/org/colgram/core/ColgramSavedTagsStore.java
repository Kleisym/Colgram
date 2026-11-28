package org.colgram.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone SQLite store (colgram_saved_tags.db) for local Saved Messages tags.
 * Supports both Unicode emojis and custom emojis ("doc:" + documentId), topic_id,
 * and explicit user-defined tag labels without auto-seeding default labels.
 */
public final class ColgramSavedTagsStore {

    private static final String DB_NAME = "colgram_saved_tags.db";
    private static final int DB_VERSION = 2;
    private static final int MAX_UNICODE_KEY_LEN = 64;
    private static final int MAX_DOC_KEY_LEN = 40;

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
        public final long topicId;
        public final String emoji;
        public final String label;
        public final long updatedAt;

        public TagEntry(int msgId, String emoji, String label, long updatedAt) {
            this(msgId, 0L, emoji, label, updatedAt);
        }

        public TagEntry(int msgId, long topicId, String emoji, String label, long updatedAt) {
            this.msgId = msgId;
            this.topicId = topicId;
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
                    + "topic_id INTEGER NOT NULL DEFAULT 0,"
                    + "emoji TEXT NOT NULL,"
                    + "label TEXT NOT NULL DEFAULT '',"
                    + "updated_at INTEGER NOT NULL,"
                    + "PRIMARY KEY(account, msg_id, emoji)"
                    + ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_saved_tags_acc_emoji "
                    + "ON saved_tags(account, emoji, msg_id DESC)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_saved_tags_acc_topic_emoji "
                    + "ON saved_tags(account, topic_id, emoji, msg_id DESC)");
            db.execSQL("CREATE TABLE IF NOT EXISTS saved_tag_labels ("
                    + "account INTEGER NOT NULL,"
                    + "emoji_key TEXT NOT NULL,"
                    + "label TEXT NOT NULL,"
                    + "PRIMARY KEY(account, emoji_key)"
                    + ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE saved_tags ADD COLUMN topic_id INTEGER NOT NULL DEFAULT 0");
                } catch (Throwable ignored) {}
            }
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
        Cursor labelsCursor = null;
        Cursor tagsCursor = null;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            Set<Integer> accounts = ConcurrentHashMap.newKeySet();
            labelsCursor = db.rawQuery("SELECT account, emoji_key, label FROM saved_tag_labels", null);
            while (labelsCursor != null && labelsCursor.moveToNext()) {
                int acc = labelsCursor.getInt(0);
                String key = normalizeEmojiKey(labelsCursor.getString(1));
                String lbl = labelsCursor.getString(2);
                String trimmed = lbl == null ? "" : lbl.trim();
                if (!key.isEmpty() && !trimmed.isEmpty()) {
                    CUSTOM_LABELS.computeIfAbsent(acc, k -> new ConcurrentHashMap<>()).putIfAbsent(key, trimmed);
                    accounts.add(acc);
                }
            }
            tagsCursor = db.rawQuery(
                    "SELECT account, msg_id, topic_id, emoji, label, updated_at FROM saved_tags ORDER BY updated_at ASC",
                    null);
            while (tagsCursor != null && tagsCursor.moveToNext()) {
                int acc = tagsCursor.getInt(0);
                int msgId = tagsCursor.getInt(1);
                long topicId = tagsCursor.getLong(2);
                String emoji = normalizeEmojiKey(tagsCursor.getString(3));
                long updatedAt = tagsCursor.getLong(5);
                if (msgId > 0 && !emoji.isEmpty()) {
                    // Preserve only explicit user-renamed labels stored in saved_tag_labels; never resurrect v1 preset labels.
                    putInCacheIfAbsent(acc, msgId, topicId, emoji, getTagLabel(acc, emoji), updatedAt);
                    accounts.add(acc);
                }
            }
            for (int acc : accounts) {
                notifyListeners(acc);
            }
        } catch (Throwable ignored) {
        } finally {
            if (labelsCursor != null) {
                try { labelsCursor.close(); } catch (Throwable ignored) {}
            }
            if (tagsCursor != null) {
                try { tagsCursor.close(); } catch (Throwable ignored) {}
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

    public static String normalizeEmojiKey(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";
        if (t.startsWith("doc:")) {
            String rest = t.substring(4);
            if (rest.isEmpty() || rest.length() > MAX_DOC_KEY_LEN - 4) {
                return "";
            }
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (!(i == 0 && c == '-') && (c < '0' || c > '9')) {
                    return "";
                }
            }
            return t;
        }
        if (t.length() > MAX_UNICODE_KEY_LEN) {
            int cut = MAX_UNICODE_KEY_LEN;
            if (Character.isHighSurrogate(t.charAt(cut - 1))) {
                cut--;
            }
            t = t.substring(0, cut);
        }
        return t;
    }

    private static String normalizeEmoji(String raw) {
        return normalizeEmojiKey(raw);
    }

    private static String resolveLabel(int account, String emoji, String explicitLabel) {
        if (explicitLabel != null && !explicitLabel.trim().isEmpty()) {
            return explicitLabel.trim();
        }
        ConcurrentHashMap<String, String> custom = CUSTOM_LABELS.get(account);
        if (custom != null) {
            String prev = custom.get(emoji);
            if (prev != null && !prev.isEmpty()) {
                return prev;
            }
        }
        return "";
    }

    private static void putInCacheIfAbsent(int account, int msgId, long topicId, String emoji, String label, long updatedAt) {
        ConcurrentHashMap<Integer, List<TagEntry>> map = accountMap(account);
        synchronized (map) {
            List<TagEntry> current = map.get(msgId);
            if (current != null) {
                for (TagEntry e : current) {
                    if (e.emoji.equals(emoji)) {
                        return;
                    }
                }
            }
            List<TagEntry> next = new ArrayList<>(current == null ? 2 : current.size() + 1);
            if (current != null) {
                next.addAll(current);
            }
            String resolvedLabel = resolveLabel(account, emoji, label);
            next.add(new TagEntry(msgId, topicId, emoji, resolvedLabel, updatedAt));
            map.put(msgId, Collections.unmodifiableList(next));
        }
    }

    private static void putInCache(int account, int msgId, long topicId, String emoji, String label, long updatedAt) {
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
            if (explicitNotEmpty(label)) {
                CUSTOM_LABELS.computeIfAbsent(account, k -> new ConcurrentHashMap<>()).put(emoji, resolvedLabel);
            }
            next.add(new TagEntry(msgId, topicId, emoji, resolvedLabel, updatedAt));
            map.put(msgId, Collections.unmodifiableList(next));
        }
    }

    private static boolean explicitNotEmpty(String s) {
        return s != null && !s.trim().isEmpty();
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

    public static List<TagEntry> getEntries(int account, int msgId) {
        if (msgId <= 0) return Collections.emptyList();
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map == null) return Collections.emptyList();
        List<TagEntry> entries = map.get(msgId);
        return entries == null ? Collections.emptyList() : entries;
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

    public static void setMessageTags(Context ctx, int account, int msgId, long topicId, List<String> keys) {
        if (msgId <= 0) return;
        ensureDbSync(ctx);
        Set<String> dedup = new LinkedHashSet<>();
        if (keys != null) {
            for (String raw : keys) {
                String k = normalizeEmojiKey(raw);
                if (!k.isEmpty()) {
                    dedup.add(k);
                }
            }
        }
        long now = System.currentTimeMillis();
        ConcurrentHashMap<Integer, List<TagEntry>> map = accountMap(account);
        final List<TagEntry> newEntries = new ArrayList<>(dedup.size());
        synchronized (map) {
            List<TagEntry> existing = map.get(msgId);
            for (String key : dedup) {
                long ts = now;
                if (existing != null) {
                    for (TagEntry prev : existing) {
                        if (prev.emoji.equals(key)) {
                            ts = prev.updatedAt;
                            break;
                        }
                    }
                }
                newEntries.add(new TagEntry(msgId, topicId, key, getTagLabel(account, key), ts));
            }
            if (newEntries.isEmpty()) {
                map.remove(msgId);
            } else {
                map.put(msgId, Collections.unmodifiableList(newEntries));
            }
        }
        notifyListeners(account);
        final DbHelper helper = dbHelper;
        if (helper != null) {
            final List<TagEntry> snapshot = new ArrayList<>(newEntries);
            DB_EXEC.execute(() -> {
                SQLiteDatabase db = null;
                try {
                    db = helper.getWritableDatabase();
                    db.beginTransaction();
                    db.delete("saved_tags", "account=? AND msg_id=?",
                            new String[]{String.valueOf(account), String.valueOf(msgId)});
                    for (TagEntry e : snapshot) {
                        ContentValues cv = new ContentValues();
                        cv.put("account", account);
                        cv.put("msg_id", msgId);
                        cv.put("topic_id", e.topicId);
                        cv.put("emoji", e.emoji);
                        cv.put("label", e.label);
                        cv.put("updated_at", e.updatedAt);
                        db.insertWithOnConflict("saved_tags", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    db.setTransactionSuccessful();
                } catch (Throwable ignored) {
                } finally {
                    if (db != null) {
                        try {
                            db.endTransaction();
                        } catch (Throwable ignored) {}
                    }
                }
            });
        }
    }

    public static void setTagLabel(Context ctx, int account, String key, String label) {
        String norm = normalizeEmojiKey(key);
        if (norm.isEmpty()) return;
        ensureDbSync(ctx);
        String trimmed = label == null ? "" : label.trim();
        ConcurrentHashMap<String, String> labelsMap =
                CUSTOM_LABELS.computeIfAbsent(account, k -> new ConcurrentHashMap<>());
        if (trimmed.isEmpty()) {
            labelsMap.remove(norm);
        } else {
            labelsMap.put(norm, trimmed);
        }
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map != null) {
            synchronized (map) {
                for (Map.Entry<Integer, List<TagEntry>> entry : map.entrySet()) {
                    List<TagEntry> list = entry.getValue();
                    if (list == null || list.isEmpty()) continue;
                    boolean changed = false;
                    List<TagEntry> updated = new ArrayList<>(list.size());
                    for (TagEntry t : list) {
                        if (t.emoji.equals(norm) && !trimmed.equals(t.label)) {
                            updated.add(new TagEntry(t.msgId, t.topicId, t.emoji, trimmed, t.updatedAt));
                            changed = true;
                        } else {
                            updated.add(t);
                        }
                    }
                    if (changed) {
                        entry.setValue(Collections.unmodifiableList(updated));
                    }
                }
            }
        }
        notifyListeners(account);
        final DbHelper helper = dbHelper;
        if (helper != null) {
            DB_EXEC.execute(() -> {
                try {
                    SQLiteDatabase db = helper.getWritableDatabase();
                    if (trimmed.isEmpty()) {
                        db.delete("saved_tag_labels", "account=? AND emoji_key=?",
                                new String[]{String.valueOf(account), norm});
                    } else {
                        ContentValues lv = new ContentValues();
                        lv.put("account", account);
                        lv.put("emoji_key", norm);
                        lv.put("label", trimmed);
                        db.insertWithOnConflict("saved_tag_labels", null, lv, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    ContentValues tv = new ContentValues();
                    tv.put("label", trimmed);
                    db.update("saved_tags", tv, "account=? AND emoji=?",
                            new String[]{String.valueOf(account), norm});
                } catch (Throwable ignored) {}
            });
        }
    }

    public static String getTagLabel(int account, String key) {
        String norm = normalizeEmojiKey(key);
        if (norm.isEmpty()) return "";
        ConcurrentHashMap<String, String> custom = CUSTOM_LABELS.get(account);
        if (custom == null) return "";
        String lbl = custom.get(norm);
        return lbl == null ? "" : lbl;
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
        addTag(ctx, account, msgId, 0L, emoji, label);
    }

    public static void addTag(Context ctx, int account, int msgId, long topicId, String emoji, String label) {
        String norm = normalizeEmoji(emoji);
        if (msgId <= 0 || norm.isEmpty()) return;
        ensureDbSync(ctx);
        long now = System.currentTimeMillis();
        String resolved = resolveLabel(account, norm, label);
        putInCache(account, msgId, topicId, norm, resolved, now);
        notifyListeners(account);
        final DbHelper helper = dbHelper;
        if (helper != null) {
            final boolean persistLabel = explicitNotEmpty(label);
            DB_EXEC.execute(() -> {
                try {
                    SQLiteDatabase db = helper.getWritableDatabase();
                    ContentValues cv = new ContentValues();
                    cv.put("account", account);
                    cv.put("msg_id", msgId);
                    cv.put("topic_id", topicId);
                    cv.put("emoji", norm);
                    cv.put("label", resolved);
                    cv.put("updated_at", now);
                    db.insertWithOnConflict("saved_tags", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    if (persistLabel) {
                        ContentValues lv = new ContentValues();
                        lv.put("account", account);
                        lv.put("emoji_key", norm);
                        lv.put("label", resolved);
                        db.insertWithOnConflict("saved_tag_labels", null, lv, SQLiteDatabase.CONFLICT_REPLACE);
                    }
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
        pruneDeletedMessages(ctx, account, Collections.singletonList(msgId));
    }

    public static void pruneDeletedMessages(Context ctx, int account, List<Integer> msgIds) {
        if (msgIds == null || msgIds.isEmpty()) return;
        ensureDbSync(ctx);
        List<Integer> validIds = new ArrayList<>(msgIds.size());
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        boolean changed = false;
        for (Integer id : msgIds) {
            if (id != null && id > 0) {
                validIds.add(id);
                if (map != null && map.remove(id) != null) {
                    changed = true;
                }
            }
        }
        if (validIds.isEmpty()) return;
        if (changed) {
            notifyListeners(account);
        }
        final DbHelper helper = dbHelper;
        if (helper != null) {
            DB_EXEC.execute(() -> {
                SQLiteDatabase db = null;
                try {
                    db = helper.getWritableDatabase();
                    db.beginTransaction();
                    String accStr = String.valueOf(account);
                    for (int id : validIds) {
                        db.delete("saved_tags", "account=? AND msg_id=?",
                                new String[]{accStr, String.valueOf(id)});
                    }
                    db.setTransactionSuccessful();
                } catch (Throwable ignored) {
                } finally {
                    if (db != null) {
                        try {
                            db.endTransaction();
                        } catch (Throwable ignored) {}
                    }
                }
            });
        }
    }

    public static int getTotalTaggedMessagesCount(int account) {
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        return map == null ? 0 : map.size();
    }

    public static String getLabelForEmoji(int account, String emoji) {
        return getTagLabel(account, emoji);
    }

    public static List<TagCount> getTagCounts(int account) {
        return getTagCounts(account, 0L);
    }

    public static List<TagCount> getTagCounts(int account, long topicId) {
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map == null || map.isEmpty()) return Collections.emptyList();
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Long> latest = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        synchronized (map) {
            for (List<TagEntry> list : map.values()) {
                for (TagEntry e : list) {
                    if (topicId != 0L && e.topicId != topicId) {
                        continue;
                    }
                    int[] c = counts.get(e.emoji);
                    if (c == null) {
                        counts.put(e.emoji, new int[]{1});
                        latest.put(e.emoji, e.updatedAt);
                        String customLbl = getTagLabel(account, e.emoji);
                        labels.put(e.emoji, !customLbl.isEmpty() ? customLbl : e.label);
                    } else {
                        c[0]++;
                        Long prevTs = latest.get(e.emoji);
                        if (prevTs == null || e.updatedAt > prevTs) {
                            latest.put(e.emoji, e.updatedAt);
                        }
                        String customLbl = getTagLabel(account, e.emoji);
                        if (!customLbl.isEmpty()) {
                            labels.put(e.emoji, customLbl);
                        } else if (!e.label.isEmpty() && labels.get(e.emoji).isEmpty()) {
                            labels.put(e.emoji, e.label);
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
        return getTaggedMessageIds(account, 0L, emoji);
    }

    public static List<Integer> getTaggedMessageIds(int account, long topicId, String emoji) {
        String norm = normalizeEmoji(emoji);
        ConcurrentHashMap<Integer, List<TagEntry>> map = CACHE.get(account);
        if (map == null || map.isEmpty()) return Collections.emptyList();
        List<Integer> ids = new ArrayList<>();
        synchronized (map) {
            for (Map.Entry<Integer, List<TagEntry>> entry : map.entrySet()) {
                List<TagEntry> list = entry.getValue();
                if (list == null || list.isEmpty()) continue;
                for (TagEntry t : list) {
                    if (topicId != 0L && t.topicId != topicId) {
                        continue;
                    }
                    if (norm.isEmpty() || t.emoji.equals(norm)) {
                        ids.add(entry.getKey());
                        break;
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
                        String customLbl = getTagLabel(account, e.emoji);
                        String outLabel = !customLbl.isEmpty() ? customLbl : e.label;
                        sb.append("{\"msgId\":").append(e.msgId)
                                .append(",\"topicId\":").append(e.topicId)
                                .append(",\"emoji\":\"").append(escapeJson(e.emoji))
                                .append("\",\"label\":\"").append(escapeJson(outLabel))
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
            long topicId = parseJsonLong(obj, "topicId", 0L);
            String emoji = normalizeEmoji(parseJsonString(obj, "emoji"));
            String label = parseJsonString(obj, "label");
            if (msgId > 0 && !emoji.isEmpty()) {
                addTag(ctx, account, msgId, topicId, emoji, label);
                imported++;
            } else if (!emoji.isEmpty() && explicitNotEmpty(label)) {
                setTagLabel(ctx, account, emoji, label);
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
        long val = parseJsonLong(obj, key, defVal);
        if (val < Integer.MIN_VALUE || val > Integer.MAX_VALUE) {
            return defVal;
        }
        return (int) val;
    }

    private static long parseJsonLong(String obj, String key, long defVal) {
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
        if (i == start || (i == start + 1 && obj.charAt(start) == '-')) return defVal;
        try {
            return Long.parseLong(obj.substring(start, i));
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
