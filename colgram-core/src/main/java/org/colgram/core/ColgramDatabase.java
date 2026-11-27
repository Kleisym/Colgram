package org.colgram.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * ColgramDatabase — Secure local SQLite storage for preserving deleted messages,
 * edit history, collapsed spam tombstones, and Lazy Shadow Vault metadata across all chats.
 */
public class ColgramDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "colgram_vault.db";
    private static final int DATABASE_VERSION = 7;

    public static final int MAX_COLLAPSED_SPAM_ROWS = 2000;
    public static final int MAX_SHADOW_MESSAGES_ROWS = 15000;

    // Table: Deleted Messages
    public static final String TABLE_DELETED = "deleted_messages";
    public static final String COL_DEL_DIALOG_ID = "dialog_id";
    public static final String COL_DEL_MSG_ID = "message_id";
    public static final String COL_DEL_TIMESTAMP = "deleted_at";

    // Table: Deleted / Cleared Dialogs
    public static final String TABLE_DELETED_DIALOGS = "deleted_dialogs";
    public static final String COL_DEL_DLG_ID = "dialog_id";
    public static final String COL_DEL_DLG_TIMESTAMP = "deleted_at";
    public static final String COL_DEL_DLG_TITLE = "title";

    // Table: Message Edit History
    public static final String TABLE_EDITS = "message_edits";
    public static final String COL_EDIT_ID = "id";
    public static final String COL_EDIT_DIALOG_ID = "dialog_id";
    public static final String COL_EDIT_MSG_ID = "message_id";
    public static final String COL_EDIT_TIMESTAMP = "edit_timestamp";
    public static final String COL_EDIT_PREV_TEXT = "previous_text";
    public static final String COL_EDIT_ENTITIES = "entities";

    // Table: Per-user notification blocks
    public static final String TABLE_BLOCKED_USERS = "blocked_notify_users";
    public static final String COL_BLOCK_USER_ID = "user_id";
    public static final String COL_BLOCK_ADDED_AT = "added_at";

    // Table: Media revisions of an edited (or replaced) message.
    //
    // Telegram has no concept of media edit history: TL_updateEditMessage carries only
    // the NEW document/photo, and once the MessageObject is rebuilt the previous
    // file reference is gone. Caption edits are covered by TABLE_EDITS, but photo /
    // video / document *replacement* is not. This table records the previous
    // attachment so the user can open the older revision and re-save it.
    //
    // We deliberately store the LOCAL FILE PATH rather than re-downloading: the old
    // media is normally already on disk in Telegram's own cache, and copying it into
    // the sandbox means the revision survives even after Telegram evicts the cache
    // entry. colgramCopyRevisionFile() does that copy at capture time.
    public static final String TABLE_MEDIA = "media_revisions";
    public static final String COL_MEDIA_ID = "id";
    public static final String COL_MEDIA_DIALOG_ID = "dialog_id";
    public static final String COL_MEDIA_MSG_ID = "message_id";
    public static final String COL_MEDIA_TIMESTAMP = "captured_at";
    public static final String COL_MEDIA_TYPE = "media_type";     // 1=photo 2=video 3=doc
    public static final String COL_MEDIA_PATH = "local_path";     // sandbox copy, may be null
    public static final String COL_MEDIA_FILE_NAME = "file_name"; // display name
    public static final String COL_MEDIA_SIZE = "file_size";
    public static final String COL_MEDIA_MIME = "mime_type";
    public static final String COL_MEDIA_REMOTE_ID = "remote_id";  // document/photo id, for the "original" hint

    // Table: Collapsed Spam Pings (bounded 2,000 rows LRU)
    public static final String TABLE_COLLAPSED_SPAM = "collapsed_spam_messages";
    public static final String COL_SPAM_DIALOG_ID = "dialog_id";
    public static final String COL_SPAM_MSG_ID = "message_id";
    public static final String COL_SPAM_SENDER_ID = "sender_id";
    public static final String COL_SPAM_REASON = "reason";
    public static final String COL_SPAM_AT = "collapsed_at";

    // Table: Traffic-Saving Lazy Shadow Vault (bounded 15,000 rows LRU + zlib metadata)
    public static final String TABLE_SHADOW_MESSAGES = "shadow_messages";
    public static final String COL_SHADOW_DIALOG_ID = "dialog_id";
    public static final String COL_SHADOW_MSG_ID = "message_id";
    public static final String COL_SHADOW_SENDER_ID = "sender_id";
    public static final String COL_SHADOW_DATE_SEC = "date_sec";
    public static final String COL_SHADOW_MEDIA_TYPE = "media_type";
    public static final String COL_SHADOW_MEDIA_SIZE = "media_size";
    public static final String COL_SHADOW_META_ZLIB = "compressed_meta";
    public static final String COL_SHADOW_PREVIEW_PATH = "preview_path";
    public static final String COL_SHADOW_DEHYDRATED = "dehydrated";
    public static final String COL_SHADOW_CAPTURED_AT = "captured_at";

    public static final class ShadowMessageRecord {
        public final long dialogId;
        public final int messageId;
        public final long senderId;
        public final int dateSec;
        public final int mediaType;
        public final long mediaSize;
        public final String metaJson;
        public final String previewPath;
        public final boolean dehydrated;
        public final long capturedAtMs;

        public ShadowMessageRecord(long dialogId, int messageId, long senderId, int dateSec,
                                   int mediaType, long mediaSize, String metaJson,
                                   String previewPath, boolean dehydrated, long capturedAtMs) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.senderId = senderId;
            this.dateSec = dateSec;
            this.mediaType = mediaType;
            this.mediaSize = mediaSize;
            this.metaJson = metaJson == null ? "" : metaJson;
            this.previewPath = previewPath == null ? "" : previewPath;
            this.dehydrated = dehydrated;
            this.capturedAtMs = capturedAtMs;
        }
    }

    private static ColgramDatabase instance;

    private final ConcurrentHashMap<Long, Set<Integer>> deletedMessagesByDialog = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, String>> collapsedSpamByDialog = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> shadowPreviewCache = new ConcurrentHashMap<>();
    private final AtomicInteger collapsedInsertTick = new AtomicInteger(0);
    private final AtomicInteger shadowInsertTick = new AtomicInteger(0);
    private final Set<Long> deletedDialogsCache = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Long> blockedUsersCache = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingPurgesDuringLoad = ConcurrentHashMap.newKeySet();
    private final Object cacheLoadLock = new Object();
    private final Object blockedCacheLock = new Object();
    private volatile boolean deletedCachesLoaded = false;
    private volatile boolean deletedCacheLoadStarted = false;
    private volatile boolean blockedUsersLoaded = false;

    public static synchronized ColgramDatabase getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new ColgramDatabase(context.getApplicationContext());
            instance.startPreloadDeletedCachesAsync();
        }
        return instance;
    }

    public ColgramDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    private static void execPragmaQuiet(SQLiteDatabase db, String sql) {
        if (db == null) return;
        try (Cursor c = db.rawQuery(sql, null)) {
            if (c != null) {
                c.moveToFirst();
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        try {
            db.enableWriteAheadLogging();
        } catch (Throwable ignored) {}
        execPragmaQuiet(db, "PRAGMA synchronous=NORMAL");
        execPragmaQuiet(db, "PRAGMA temp_store=MEMORY");
        execPragmaQuiet(db, "PRAGMA cache_size=-4096");
        execPragmaQuiet(db, "PRAGMA mmap_size=16777216");
        execPragmaQuiet(db, "PRAGMA busy_timeout=5000");
    }

    /**
     * Reclaims free SQLite pages and truncates the WAL journal on Colgram's own colgram_vault.db.
     * Strictly touches only ColgramDatabase, never Telegram databases.
     */
    public void compactOwnDatabase() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            try (Cursor c1 = db.rawQuery("PRAGMA incremental_vacuum(200)", null)) {
                if (c1 != null) c1.moveToFirst();
            }
            try (Cursor c2 = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
                if (c2 != null) c2.moveToFirst();
            }
        } catch (Throwable ignored) {}
    }

    private static String deletedKey(long dialogId, int messageId) {
        return dialogId + ":" + messageId;
    }

    /** Normalizes Unix timestamps in seconds or milliseconds to epoch milliseconds. */
    public static long normalizeEpochMillis(long ts) {
        return ColgramHookHandler.normalizeEpochMillis(ts);
    }

    private static boolean isMainThreadSafe() {
        try {
            Looper main = Looper.getMainLooper();
            return main != null && Looper.myLooper() == main;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void startPreloadDeletedCachesAsync() {
        if ((deletedCachesLoaded && blockedUsersLoaded) || deletedCacheLoadStarted) return;
        synchronized (cacheLoadLock) {
            if ((deletedCachesLoaded && blockedUsersLoaded) || deletedCacheLoadStarted) return;
            deletedCacheLoadStarted = true;
        }
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            ensureBlockedUsersLoadedSync();
            ensureDeletedCachesLoadedSync();
        }, "colgram-vault-preload");
        t.setDaemon(true);
        t.start();
    }

    private void ensureBlockedUsersLoadedSync() {
        if (blockedUsersLoaded) return;
        synchronized (blockedCacheLock) {
            if (blockedUsersLoaded) return;
            try {
                SQLiteDatabase db = getReadableDatabase();
                try (Cursor blockCur = db.query(TABLE_BLOCKED_USERS,
                        new String[]{COL_BLOCK_USER_ID},
                        null, null, null, null, null)) {
                    if (blockCur != null) {
                        while (blockCur.moveToNext()) {
                            blockedUsersCache.add(blockCur.getLong(0));
                        }
                    }
                }
                blockedUsersLoaded = true;
            } catch (Exception ignored) {
            }
        }
    }

    public interface DeletedCacheRefreshListener {
        void onDeletedCacheReady();
    }

    private final java.util.concurrent.CopyOnWriteArrayList<DeletedCacheRefreshListener> deletedCacheListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addDeletedCacheRefreshListener(DeletedCacheRefreshListener listener) {
        if (listener != null) {
            deletedCacheListeners.addIfAbsent(listener);
        }
    }

    public void removeDeletedCacheRefreshListener(DeletedCacheRefreshListener listener) {
        if (listener != null) {
            deletedCacheListeners.remove(listener);
        }
    }

    private void notifyDeletedCacheReady() {
        for (DeletedCacheRefreshListener listener : deletedCacheListeners) {
            try {
                listener.onDeletedCacheReady();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Non-destructively peeks poisoned non-positive message_id tombstones (mid <= 0)
     * so every Telegram account database (accounts 0..4) can scrub its own messages_v2 /
     * messages_topics before purgePoisonedNonPositiveEntries() clears TABLE_DELETED.
     */
    public java.util.List<long[]> peekPoisonedNonPositiveEntries() {
        java.util.List<long[]> poisoned = new java.util.ArrayList<>();
        synchronized (cacheLoadLock) {
            try {
                SQLiteDatabase db = getReadableDatabase();
                try (Cursor c = db.query(TABLE_DELETED,
                        new String[]{COL_DEL_DIALOG_ID, COL_DEL_MSG_ID},
                        COL_DEL_MSG_ID + " <= 0",
                        null, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        poisoned.add(new long[]{c.getLong(0), c.getInt(1)});
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return poisoned;
    }

    /** Deletes poisoned non-positive tombstones (mid <= 0) from TABLE_DELETED once all accounts are cleaned. */
    public void purgePoisonedNonPositiveEntries() {
        synchronized (cacheLoadLock) {
            try {
                SQLiteDatabase db = getWritableDatabase();
                db.delete(TABLE_DELETED, COL_DEL_MSG_ID + " <= 0", null);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Drains and removes poisoned non-positive message_id tombstones (mid <= 0).
     */
    public java.util.List<long[]> drainPoisonedNonPositiveEntries() {
        java.util.List<long[]> poisoned = peekPoisonedNonPositiveEntries();
        if (!poisoned.isEmpty()) {
            purgePoisonedNonPositiveEntries();
        }
        return poisoned;
    }

    private void ensureDeletedCachesLoadedSync() {
        if (deletedCachesLoaded) return;
        synchronized (cacheLoadLock) {
            if (deletedCachesLoaded) return;
            boolean loadedOk = false;
            try {
                SQLiteDatabase db = getReadableDatabase();
                java.util.Map<Long, Set<Integer>> tmpMsgs = new ConcurrentHashMap<>();
                Cursor msgCur = db.query(TABLE_DELETED,
                        new String[]{COL_DEL_DIALOG_ID, COL_DEL_MSG_ID},
                        null, null, null, null, null);
                if (msgCur != null) {
                    try {
                        while (msgCur.moveToNext()) {
                            long did = msgCur.getLong(0);
                            int mid = msgCur.getInt(1);
                            if (mid <= 0) {
                                continue;
                            }
                            if (did == 0L || (!pendingPurgesDuringLoad.isEmpty()
                                    && pendingPurgesDuringLoad.contains(deletedKey(did, mid)))) {
                                continue;
                            }
                            tmpMsgs.computeIfAbsent(did, k -> ConcurrentHashMap.newKeySet()).add(mid);
                        }
                    } finally {
                        msgCur.close();
                    }
                }
                for (java.util.Map.Entry<Long, Set<Integer>> entry : tmpMsgs.entrySet()) {
                    long did = entry.getKey();
                    Set<Integer> liveSet = deletedMessagesByDialog
                            .computeIfAbsent(did, k -> ConcurrentHashMap.newKeySet());
                    for (Integer midVal : entry.getValue()) {
                        if (midVal != null && midVal > 0
                                && (pendingPurgesDuringLoad.isEmpty()
                                        || !pendingPurgesDuringLoad.contains(deletedKey(did, midVal)))) {
                            liveSet.add(midVal);
                        }
                    }
                }

                Set<Long> tmpDialogs = ConcurrentHashMap.newKeySet();
                Cursor dlgCur = db.query(TABLE_DELETED_DIALOGS,
                        new String[]{COL_DEL_DLG_ID},
                        null, null, null, null, null);
                if (dlgCur != null) {
                    try {
                        while (dlgCur.moveToNext()) {
                            long did = dlgCur.getLong(0);
                            if (did != 0L) {
                                tmpDialogs.add(did);
                            }
                        }
                    } finally {
                        dlgCur.close();
                    }
                }
                deletedDialogsCache.addAll(tmpDialogs);

                // Preload collapsed spam pings for O(1) UI bind check
                try (Cursor spamCur = db.query(TABLE_COLLAPSED_SPAM,
                        new String[]{COL_SPAM_DIALOG_ID, COL_SPAM_MSG_ID, COL_SPAM_REASON},
                        null, null, null, null, null)) {
                    while (spamCur != null && spamCur.moveToNext()) {
                        long sd = spamCur.getLong(0);
                        int sm = spamCur.getInt(1);
                        String sr = spamCur.isNull(2) ? "spam" : spamCur.getString(2);
                        if (sd != 0L && sm != 0) {
                            collapsedSpamByDialog
                                    .computeIfAbsent(sd, k -> new ConcurrentHashMap<>())
                                    .put(sm, sr);
                        }
                    }
                } catch (Throwable ignored) {}

                // Preload Shadow Micro-Vault preview paths for O(1) deleted photo display
                java.util.List<long[]> stalePreviews = null;
                try (Cursor shadowCur = db.query(TABLE_SHADOW_MESSAGES,
                        new String[]{COL_SHADOW_DIALOG_ID, COL_SHADOW_MSG_ID, COL_SHADOW_PREVIEW_PATH},
                        COL_SHADOW_PREVIEW_PATH + " IS NOT NULL AND " + COL_SHADOW_PREVIEW_PATH + " != ''",
                        null, null, null, null)) {
                    while (shadowCur != null && shadowCur.moveToNext()) {
                        long xd = shadowCur.getLong(0);
                        int xm = shadowCur.getInt(1);
                        String xp = shadowCur.getString(2);
                        if (xd != 0L && xm != 0 && xp != null && !xp.isEmpty()) {
                            if (new java.io.File(xp).isFile()) {
                                shadowPreviewCache.put(deletedKey(xd, xm), xp);
                            } else {
                                if (stalePreviews == null) stalePreviews = new ArrayList<>();
                                stalePreviews.add(new long[]{xd, xm});
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                if (stalePreviews != null && !stalePreviews.isEmpty()) {
                    try {
                        SQLiteDatabase wdb = getWritableDatabase();
                        for (long[] pair : stalePreviews) {
                            ContentValues cv = new ContentValues();
                            cv.putNull(COL_SHADOW_PREVIEW_PATH);
                            wdb.update(TABLE_SHADOW_MESSAGES, cv,
                                    COL_SHADOW_DIALOG_ID + "=? AND " + COL_SHADOW_MSG_ID + "=?",
                                    new String[]{String.valueOf(pair[0]), String.valueOf(pair[1])});
                        }
                    } catch (Throwable ignored) {}
                }

                pendingPurgesDuringLoad.clear();
                deletedCachesLoaded = true;
                loadedOk = true;
            } catch (Exception e) {
                deletedCacheLoadStarted = false;
                try {
                    android.util.Log.w("ColgramDatabase", "ensureDeletedCachesLoadedSync retryable failure", e);
                } catch (Throwable ignored) {
                }
            }
            if (loadedOk) {
                notifyDeletedCacheReady();
            }
        }
    }

    private void prepareCacheForLookup() {
        if (deletedCachesLoaded) return;
        // Never block the main/UI thread on SQLite during ChatMessageCell/DialogCell bind/draw.
        if (isMainThreadSafe()) {
            startPreloadDeletedCachesAsync();
        } else {
            ensureDeletedCachesLoadedSync();
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL("PRAGMA auto_vacuum = INCREMENTAL;");
        } catch (Throwable ignored) {}
        // Table storing IDs of deleted messages
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DELETED + " ("
                + COL_DEL_DIALOG_ID + " INTEGER, "
                + COL_DEL_MSG_ID + " INTEGER, "
                + COL_DEL_TIMESTAMP + " INTEGER, "
                + "PRIMARY KEY (" + COL_DEL_DIALOG_ID + ", " + COL_DEL_MSG_ID + ")"
                + ");");

        // Table storing prior versions of edited messages
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_EDITS + " ("
                + COL_EDIT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_EDIT_DIALOG_ID + " INTEGER, "
                + COL_EDIT_MSG_ID + " INTEGER, "
                + COL_EDIT_TIMESTAMP + " INTEGER, "
                + COL_EDIT_PREV_TEXT + " TEXT, "
                + COL_EDIT_ENTITIES + " TEXT"
                + ");");

        // Covering index for fast lookup + newest-row ORDER BY id DESC without table scan/sort
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edits_lookup ON " + TABLE_EDITS
                + " (" + COL_EDIT_DIALOG_ID + ", " + COL_EDIT_MSG_ID + ", " + COL_EDIT_ID + ");");

        // Users whose group pings should not reach this device at all.
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BLOCKED_USERS + " ("
                + COL_BLOCK_USER_ID + " INTEGER PRIMARY KEY, "
                + COL_BLOCK_ADDED_AT + " INTEGER"
                + ");");

        // Media revisions of an edited message (photo/video/document replacement).
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MEDIA + " ("
                + COL_MEDIA_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_MEDIA_DIALOG_ID + " INTEGER, "
                + COL_MEDIA_MSG_ID + " INTEGER, "
                + COL_MEDIA_TIMESTAMP + " INTEGER, "
                + COL_MEDIA_TYPE + " INTEGER, "
                + COL_MEDIA_PATH + " TEXT, "
                + COL_MEDIA_FILE_NAME + " TEXT, "
                + COL_MEDIA_SIZE + " INTEGER, "
                + COL_MEDIA_MIME + " TEXT, "
                + COL_MEDIA_REMOTE_ID + " INTEGER"
                + ");");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_lookup ON " + TABLE_MEDIA
                + " (" + COL_MEDIA_DIALOG_ID + ", " + COL_MEDIA_MSG_ID + ", " + COL_MEDIA_ID + ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DELETED_DIALOGS + " ("
                + COL_DEL_DLG_ID + " INTEGER PRIMARY KEY, "
                + COL_DEL_DLG_TIMESTAMP + " INTEGER, "
                + COL_DEL_DLG_TITLE + " TEXT"
                + ");");

        createV7Tables(db);
    }

    private static void createV7Tables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_COLLAPSED_SPAM + " ("
                + COL_SPAM_DIALOG_ID + " INTEGER, "
                + COL_SPAM_MSG_ID + " INTEGER, "
                + COL_SPAM_SENDER_ID + " INTEGER, "
                + COL_SPAM_REASON + " TEXT, "
                + COL_SPAM_AT + " INTEGER, "
                + "PRIMARY KEY (" + COL_SPAM_DIALOG_ID + ", " + COL_SPAM_MSG_ID + ")"
                + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_collapsed_spam_at ON " + TABLE_COLLAPSED_SPAM
                + " (" + COL_SPAM_AT + ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SHADOW_MESSAGES + " ("
                + COL_SHADOW_DIALOG_ID + " INTEGER, "
                + COL_SHADOW_MSG_ID + " INTEGER, "
                + COL_SHADOW_SENDER_ID + " INTEGER, "
                + COL_SHADOW_DATE_SEC + " INTEGER, "
                + COL_SHADOW_MEDIA_TYPE + " INTEGER, "
                + COL_SHADOW_MEDIA_SIZE + " INTEGER, "
                + COL_SHADOW_META_ZLIB + " BLOB, "
                + COL_SHADOW_PREVIEW_PATH + " TEXT, "
                + COL_SHADOW_DEHYDRATED + " INTEGER DEFAULT 0, "
                + COL_SHADOW_CAPTURED_AT + " INTEGER, "
                + "PRIMARY KEY (" + COL_SHADOW_DIALOG_ID + ", " + COL_SHADOW_MSG_ID + ")"
                + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shadow_captured_at ON " + TABLE_SHADOW_MESSAGES
                + " (" + COL_SHADOW_CAPTURED_AT + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 -> v2: per-user notification blocks
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BLOCKED_USERS + " ("
                    + COL_BLOCK_USER_ID + " INTEGER PRIMARY KEY, "
                    + COL_BLOCK_ADDED_AT + " INTEGER"
                    + ");");
        }
        // v2 -> v3: media revision history for edited messages
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MEDIA + " ("
                    + COL_MEDIA_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_MEDIA_DIALOG_ID + " INTEGER, "
                    + COL_MEDIA_MSG_ID + " INTEGER, "
                    + COL_MEDIA_TIMESTAMP + " INTEGER, "
                    + COL_MEDIA_TYPE + " INTEGER, "
                    + COL_MEDIA_PATH + " TEXT, "
                    + COL_MEDIA_FILE_NAME + " TEXT, "
                    + COL_MEDIA_SIZE + " INTEGER, "
                    + COL_MEDIA_MIME + " TEXT, "
                    + COL_MEDIA_REMOTE_ID + " INTEGER"
                    + ");");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_lookup ON " + TABLE_MEDIA
                    + " (" + COL_MEDIA_DIALOG_ID + ", " + COL_MEDIA_MSG_ID + ", " + COL_MEDIA_ID + ");");
        }
        // v3 -> v4: retained deleted/cleared dialogs
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DELETED_DIALOGS + " ("
                    + COL_DEL_DLG_ID + " INTEGER PRIMARY KEY, "
                    + COL_DEL_DLG_TIMESTAMP + " INTEGER, "
                    + COL_DEL_DLG_TITLE + " TEXT"
                    + ");");
        }
        // v4 -> v5: store Telegram entities (custom emoji + formatting) with each text edit revision
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_EDITS + " ADD COLUMN " + COL_EDIT_ENTITIES + " TEXT");
            } catch (Exception ignored) {}
        }
        // v5 -> v6: covering indices (dialog_id, message_id, id) on TABLE_EDITS and TABLE_MEDIA
        if (oldVersion < 6) {
            try {
                db.execSQL("DROP INDEX IF EXISTS idx_edits_lookup;");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_edits_lookup ON " + TABLE_EDITS
                        + " (" + COL_EDIT_DIALOG_ID + ", " + COL_EDIT_MSG_ID + ", " + COL_EDIT_ID + ");");
                db.execSQL("DROP INDEX IF EXISTS idx_media_lookup;");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_lookup ON " + TABLE_MEDIA
                        + " (" + COL_MEDIA_DIALOG_ID + ", " + COL_MEDIA_MSG_ID + ", " + COL_MEDIA_ID + ");");
            } catch (Exception ignored) {}
        }
        // v6 -> v7: collapsed_spam_messages + Lazy Shadow Vault shadow_messages
        if (oldVersion < 7) {
            createV7Tables(db);
        }
    }

    /**
     * Suppress notifications originating from this user id.
     *
     * Applies to pings in groups and channels: the message is still delivered and readable
     * on opening the chat, it is only kept quiet — no notification, no badge, no sound.
     */
    public void blockNotificationsFrom(long userId) {
        if (userId == 0) return;
        blockedUsersCache.add(userId);
        synchronized (blockedCacheLock) {
            blockedUsersCache.add(userId);
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues values = new ContentValues();
                values.put(COL_BLOCK_USER_ID, userId);
                values.put(COL_BLOCK_ADDED_AT, System.currentTimeMillis());
                db.insertWithOnConflict(TABLE_BLOCKED_USERS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception e) {
                // Never let this throw into the message pipeline.
            }
        }
    }

    public void unblockNotificationsFrom(long userId) {
        if (userId == 0) return;
        blockedUsersCache.remove(userId);
        synchronized (blockedCacheLock) {
            blockedUsersCache.remove(userId);
            try {
                getWritableDatabase().delete(TABLE_BLOCKED_USERS,
                        COL_BLOCK_USER_ID + "=?", new String[]{String.valueOf(userId)});
            } catch (Exception e) {
                // Silently handle
            }
        }
    }

    public boolean isNotificationsBlockedFrom(long userId) {
        if (userId == 0) return false;
        if (!blockedUsersLoaded) {
            if (isMainThreadSafe()) {
                startPreloadDeletedCachesAsync();
            } else {
                ensureBlockedUsersLoadedSync();
            }
        }
        return blockedUsersCache.contains(userId);
    }

    public List<Long> getBlockedNotifyUsers() {
        List<Long> out = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE_BLOCKED_USERS,
                new String[]{COL_BLOCK_USER_ID},
                null, null, null, null, COL_BLOCK_ADDED_AT + " ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) out.add(cursor.getLong(0));
            }
        } catch (Exception e) {
            // Silently handle
        }
        return out;
    }

    /**
     * Records a message as deleted by the other party.
     * Requires a concrete non-zero dialogId so tombstones never leak across private chats / Saved Messages.
     */
    public void markMessageDeleted(long dialogId, int messageId) {
        if (dialogId == 0L || messageId <= 0) return;
        deletedMessagesByDialog
                .computeIfAbsent(dialogId, k -> ConcurrentHashMap.newKeySet())
                .add(messageId);
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_DEL_DIALOG_ID, dialogId);
            values.put(COL_DEL_MSG_ID, messageId);
            values.put(COL_DEL_TIMESTAMP, System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_DELETED, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception e) {
            // Silently fail to avoid crashing Telegram message loop
        }
    }

    /**
     * Batched insert for recording bulk deleted messages in a single SQLite transaction
     * with a compiled SQLiteStatement.
     */
    public void markMessagesDeleted(long dialogId, Collection<Integer> messageIds) {
        if (dialogId == 0L || messageIds == null || messageIds.isEmpty()) return;
        Set<Integer> dialogSet = null;
        boolean hasAny = false;
        for (Integer id : messageIds) {
            if (id == null || id <= 0) continue;
            if (dialogSet == null) {
                dialogSet = deletedMessagesByDialog.computeIfAbsent(dialogId, k -> ConcurrentHashMap.newKeySet());
            }
            dialogSet.add(id);
            hasAny = true;
        }
        if (!hasAny) return;
        SQLiteDatabase db = null;
        SQLiteStatement stmt = null;
        boolean inTx = false;
        try {
            db = getWritableDatabase();
            long now = System.currentTimeMillis();
            stmt = db.compileStatement("INSERT OR IGNORE INTO " + TABLE_DELETED + " ("
                    + COL_DEL_DIALOG_ID + ", " + COL_DEL_MSG_ID + ", " + COL_DEL_TIMESTAMP
                    + ") VALUES (?, ?, ?)");
            db.beginTransaction();
            inTx = true;
            for (Integer id : messageIds) {
                if (id == null || id <= 0) continue;
                stmt.clearBindings();
                stmt.bindLong(1, dialogId);
                stmt.bindLong(2, id);
                stmt.bindLong(3, now);
                stmt.executeInsert();
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            // Silently fail to avoid crashing Telegram message loop
        } finally {
            if (stmt != null) {
                try { stmt.close(); } catch (Throwable ignored) {}
            }
            if (db != null && inTx) {
                try { db.endTransaction(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Removes a message from the deleted tombstone cache and DB (e.g. local self-delete or double-delete purge). */
    public void removeMessageDeleted(long dialogId, int messageId) {
        if (dialogId == 0L || messageId == 0) return;
        if (!deletedCachesLoaded) {
            pendingPurgesDuringLoad.add(deletedKey(dialogId, messageId));
        }
        Set<Integer> dialogSet = deletedMessagesByDialog.get(dialogId);
        if (dialogSet != null) {
            dialogSet.remove(messageId);
        }
        try {
            getWritableDatabase().delete(TABLE_DELETED,
                    COL_DEL_DIALOG_ID + "=? AND " + COL_DEL_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)});
        } catch (Exception ignored) {
        }
    }

    /**
     * Permanently removes all local Colgram records (deleted tombstones, edit history, and media
     * revisions) for a batch of messages in one transaction so a user-requested full local delete
     * leaves zero preserved trace in ColgramDatabase.
     */
    public void purgeMessagesCompletely(long dialogId, Collection<Integer> messageIds) {
        purgeMessagesMemoryOnly(dialogId, messageIds);
        purgeMessagesFromDiskOnly(dialogId, messageIds);
    }

    /** Synchronously clears local tombstones from in-memory caches without touching SQLite on caller thread. */
    public void purgeMessagesMemoryOnly(long dialogId, Collection<Integer> messageIds) {
        if (dialogId == 0L || messageIds == null || messageIds.isEmpty()) return;
        Set<Integer> dialogSet = deletedMessagesByDialog != null ? deletedMessagesByDialog.get(dialogId) : null;
        ConcurrentHashMap<Integer, String> spamMap = collapsedSpamByDialog != null ? collapsedSpamByDialog.get(dialogId) : null;
        for (Integer id : messageIds) {
            if (id == null || id == 0) continue;
            if (!deletedCachesLoaded && pendingPurgesDuringLoad != null) {
                pendingPurgesDuringLoad.add(deletedKey(dialogId, id));
            }
            if (dialogSet != null) {
                dialogSet.remove(id);
            }
            if (spamMap != null) {
                spamMap.remove(id);
            }
            if (shadowPreviewCache != null) {
                shadowPreviewCache.remove(deletedKey(dialogId, id));
            }
        }
    }

    /** Executes SQLite DELETE statements and shadow-preview file cleanup for purged messages; safe on background I/O thread. */
    public void purgeMessagesFromDiskOnly(long dialogId, Collection<Integer> messageIds) {
        if (dialogId == 0L || messageIds == null || messageIds.isEmpty()) return;
        List<String> previewPathsToDelete = new ArrayList<>();
        SQLiteDatabase db = null;
        SQLiteStatement delStmt = null;
        SQLiteStatement editStmt = null;
        SQLiteStatement mediaStmt = null;
        SQLiteStatement spamStmt = null;
        SQLiteStatement shadowStmt = null;
        boolean inTx = false;
        try {
            db = getWritableDatabase();
            for (Integer id : messageIds) {
                if (id == null || id == 0) continue;
                try (Cursor c = db.query(TABLE_SHADOW_MESSAGES,
                        new String[]{COL_SHADOW_PREVIEW_PATH},
                        COL_SHADOW_DIALOG_ID + "=? AND " + COL_SHADOW_MSG_ID + "=?",
                        new String[]{String.valueOf(dialogId), String.valueOf(id)},
                        null, null, null, "1")) {
                    if (c != null && c.moveToFirst() && !c.isNull(0)) {
                        String p = c.getString(0);
                        if (p != null && !p.isEmpty()) {
                            previewPathsToDelete.add(p);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            delStmt = db.compileStatement("DELETE FROM " + TABLE_DELETED + " WHERE "
                    + COL_DEL_DIALOG_ID + "=? AND " + COL_DEL_MSG_ID + "=?");
            editStmt = db.compileStatement("DELETE FROM " + TABLE_EDITS + " WHERE "
                    + COL_EDIT_DIALOG_ID + "=? AND " + COL_EDIT_MSG_ID + "=?");
            mediaStmt = db.compileStatement("DELETE FROM " + TABLE_MEDIA + " WHERE "
                    + COL_MEDIA_DIALOG_ID + "=? AND " + COL_MEDIA_MSG_ID + "=?");
            spamStmt = db.compileStatement("DELETE FROM " + TABLE_COLLAPSED_SPAM + " WHERE "
                    + COL_SPAM_DIALOG_ID + "=? AND " + COL_SPAM_MSG_ID + "=?");
            shadowStmt = db.compileStatement("DELETE FROM " + TABLE_SHADOW_MESSAGES + " WHERE "
                    + COL_SHADOW_DIALOG_ID + "=? AND " + COL_SHADOW_MSG_ID + "=?");
            db.beginTransaction();
            inTx = true;
            for (Integer id : messageIds) {
                if (id == null || id == 0) continue;
                delStmt.clearBindings();
                delStmt.bindLong(1, dialogId);
                delStmt.bindLong(2, id);
                delStmt.executeUpdateDelete();

                editStmt.clearBindings();
                editStmt.bindLong(1, dialogId);
                editStmt.bindLong(2, id);
                editStmt.executeUpdateDelete();

                mediaStmt.clearBindings();
                mediaStmt.bindLong(1, dialogId);
                mediaStmt.bindLong(2, id);
                mediaStmt.executeUpdateDelete();

                spamStmt.clearBindings();
                spamStmt.bindLong(1, dialogId);
                spamStmt.bindLong(2, id);
                spamStmt.executeUpdateDelete();

                shadowStmt.clearBindings();
                shadowStmt.bindLong(1, dialogId);
                shadowStmt.bindLong(2, id);
                shadowStmt.executeUpdateDelete();
            }
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
        } finally {
            if (delStmt != null) try { delStmt.close(); } catch (Throwable ignored) {}
            if (editStmt != null) try { editStmt.close(); } catch (Throwable ignored) {}
            if (mediaStmt != null) try { mediaStmt.close(); } catch (Throwable ignored) {}
            if (spamStmt != null) try { spamStmt.close(); } catch (Throwable ignored) {}
            if (shadowStmt != null) try { shadowStmt.close(); } catch (Throwable ignored) {}
            if (db != null && inTx) try { db.endTransaction(); } catch (Throwable ignored) {}
        }
        for (String p : previewPathsToDelete) {
            deleteShadowPreviewFileSafe(p);
        }
    }

    /**
     * Checks if a message was marked as deleted in O(1) memory without blocking the UI thread.
     * Strictly scoped to the exact (dialogId, messageId) pair — never wildcard-matches dialogId=0.
     */
    public boolean isMessageDeleted(long dialogId, int messageId) {
        if (dialogId == 0L || messageId <= 0) return false;
        prepareCacheForLookup();
        Set<Integer> dialogSet = deletedMessagesByDialog.get(dialogId);
        return dialogSet != null && dialogSet.contains(messageId);
    }

    /** Returns all preserved deleted message IDs for a dialog so SQLite sync/reload never wipes them. */
    public java.util.List<Integer> getDeletedMessageIdsForDialog(long dialogId) {
        if (dialogId == 0) return new java.util.ArrayList<>();
        if (deletedCachesLoaded) {
            Set<Integer> cached = deletedMessagesByDialog.get(dialogId);
            return (cached == null || cached.isEmpty())
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(cached);
        }
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        try {
            android.database.Cursor c = getReadableDatabase().query(
                    false,
                    TABLE_DELETED,
                    new String[]{COL_DEL_MSG_ID},
                    COL_DEL_DIALOG_ID + "=?",
                    new String[]{String.valueOf(dialogId)},
                    null, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        int mid = c.getInt(0);
                        if (mid > 0) {
                            ids.add(mid);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {}
        return ids;
    }

    /** Records a dialog whose history was remotely deleted/cleared but preserved locally. */
    public void markDialogDeleted(long dialogId, String title) {
        if (dialogId == 0) return;
        deletedDialogsCache.add(dialogId);
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_DEL_DLG_ID, dialogId);
            values.put(COL_DEL_DLG_TIMESTAMP, System.currentTimeMillis());
            values.put(COL_DEL_DLG_TITLE, title == null ? "" : title);
            db.insertWithOnConflict(TABLE_DELETED_DIALOGS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception ignored) {
        }
    }

    /** Clears the preserved-deleted marker from a dialog when the user dismisses it locally. */
    public void clearDialogDeleted(long dialogId) {
        if (dialogId == 0) return;
        deletedDialogsCache.remove(dialogId);
        try {
            getWritableDatabase().delete(TABLE_DELETED_DIALOGS,
                    COL_DEL_DLG_ID + "=?", new String[]{String.valueOf(dialogId)});
        } catch (Exception ignored) {
        }
    }

    /** Returns true if this dialog is preserved locally after remote deletion in O(1) memory. */
    public boolean isDialogDeleted(long dialogId) {
        if (dialogId == 0) return false;
        prepareCacheForLookup();
        return deletedDialogsCache.contains(dialogId);
    }

    /**
     * Stores a prior version of a message before it was edited.
     * Skips duplicates so repeated update packets do not inflate the history.
     */
    public void saveMessageEdit(long dialogId, int messageId, String previousText, long editTimestamp) {
        saveMessageEdit(dialogId, messageId, previousText, null, editTimestamp);
    }

    public void saveMessageEdit(long dialogId, int messageId, String previousText, String previousEntitiesJson, long editTimestamp) {
        if (dialogId == 0L || messageId == 0 || previousText == null || previousText.trim().isEmpty()) return;

        try {
            SQLiteDatabase db = getWritableDatabase();
            long tsMs = normalizeEpochMillis(editTimestamp);
            String normEntities = previousEntitiesJson == null ? "" : previousEntitiesJson;

            // Deduplicate against the newest inserted row by _id DESC (not timestamp, which can tie within <1s).
            try (Cursor last = db.query(TABLE_EDITS,
                    new String[]{COL_EDIT_PREV_TEXT, COL_EDIT_TIMESTAMP, COL_EDIT_ENTITIES},
                    COL_EDIT_DIALOG_ID + "=? AND " + COL_EDIT_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, COL_EDIT_ID + " DESC", "1")) {
                if (last != null && last.moveToFirst()) {
                    String existing = last.getString(0);
                    long lastTs = normalizeEpochMillis(last.getLong(1));
                    String existingEnt = last.isNull(2) ? "" : last.getString(2);
                    if (previousText.equals(existing) && normEntities.equals(existingEnt)) {
                        return;
                    }
                    if (tsMs <= lastTs) {
                        tsMs = lastTs + 1L;
                    }
                }
            }

            ContentValues values = new ContentValues();
            values.put(COL_EDIT_DIALOG_ID, dialogId);
            values.put(COL_EDIT_MSG_ID, messageId);
            values.put(COL_EDIT_PREV_TEXT, previousText);
            values.put(COL_EDIT_TIMESTAMP, tsMs);
            if (!normEntities.isEmpty()) {
                values.put(COL_EDIT_ENTITIES, normEntities);
            }
            db.insert(TABLE_EDITS, null, values);
        } catch (Exception e) {
            // Silently handle
        }
    }

    public static class MessageEditEntry {
        public final long timestamp;
        public final String text;
        public final String entitiesJson;

        public MessageEditEntry(long timestamp, String text) {
            this(timestamp, text, null);
        }

        public MessageEditEntry(long timestamp, String text, String entitiesJson) {
            this.timestamp = timestamp;
            this.text = text;
            this.entitiesJson = entitiesJson;
        }
    }

    /** A previously-attached photo / video / document that was replaced by an edit. */
    public static class MediaRevision {
        public static final int TYPE_PHOTO = 1;
        public static final int TYPE_VIDEO = 2;
        public static final int TYPE_DOCUMENT = 3;

        public final long timestamp;
        public final int mediaType;
        /** Sandbox copy of the file. May be null if the copy failed at capture time. */
        public final String localPath;
        public final String fileName;
        public final long fileSize;
        public final String mimeType;

        public MediaRevision(long timestamp, int mediaType, String localPath,
                             String fileName, long fileSize, String mimeType) {
            this.timestamp = timestamp;
            this.mediaType = mediaType;
            this.localPath = localPath;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.mimeType = mimeType;
        }

        /** True when we actually hold a usable local copy the user can open or re-save. */
        public boolean isRetrievable() {
            return localPath != null && !localPath.isEmpty() && new java.io.File(localPath).exists();
        }

        public boolean isVideo() { return mediaType == TYPE_VIDEO; }
        public boolean isPhoto() { return mediaType == TYPE_PHOTO; }
    }

    /**
     * Records a media attachment that is being replaced by an edit.
     *
     * Deduplicates on (dialog, message, remoteId) so a repeated update packet does not
     * stack identical revisions — the same reason TABLE_EDITS dedupes on text.
     */
    public void saveMediaRevision(long dialogId, int messageId, int mediaType,
                                  String localPath, String fileName, long fileSize,
                                  String mimeType, long remoteId, long capturedAt) {
        if (messageId == 0 || dialogId == 0) return;

        try {
            SQLiteDatabase db = getWritableDatabase();
            long tsMs = normalizeEpochMillis(capturedAt);

            // Dedupe against the newest inserted revision by _id DESC so rapid <1s media swaps preserve order.
            try (Cursor last = db.query(TABLE_MEDIA,
                    new String[]{COL_MEDIA_REMOTE_ID, COL_MEDIA_FILE_NAME, COL_MEDIA_TIMESTAMP},
                    COL_MEDIA_DIALOG_ID + "=? AND " + COL_MEDIA_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, COL_MEDIA_ID + " DESC", "1")) {
                if (last != null && last.moveToFirst()) {
                    long existingRemote = last.getLong(0);
                    String existingName = last.isNull(1) ? null : last.getString(1);
                    long lastTs = normalizeEpochMillis(last.getLong(2));
                    // Same attachment id AND same name => nothing actually changed.
                    if (remoteId != 0 && existingRemote == remoteId) {
                        return;
                    }
                    if (remoteId == 0 && existingName != null && existingName.equals(fileName)) {
                        return;
                    }
                    if (tsMs <= lastTs) {
                        tsMs = lastTs + 1L;
                    }
                }
            }

            ContentValues values = new ContentValues();
            values.put(COL_MEDIA_DIALOG_ID, dialogId);
            values.put(COL_MEDIA_MSG_ID, messageId);
            values.put(COL_MEDIA_TIMESTAMP, tsMs);
            values.put(COL_MEDIA_TYPE, mediaType);
            values.put(COL_MEDIA_PATH, localPath);
            values.put(COL_MEDIA_FILE_NAME, fileName);
            values.put(COL_MEDIA_SIZE, fileSize);
            values.put(COL_MEDIA_MIME, mimeType);
            values.put(COL_MEDIA_REMOTE_ID, remoteId);
            db.insert(TABLE_MEDIA, null, values);
        } catch (Exception e) {
            // Silently handle — never break Telegram's update loop over history bookkeeping.
        }
    }

    /** All stored media revisions for a message, oldest first. */
    public List<MediaRevision> getMediaRevisions(long dialogId, int messageId) {
        List<MediaRevision> out = new ArrayList<>();
        if (dialogId == 0L || messageId == 0) return out;
        try (Cursor cursor = getReadableDatabase().query(TABLE_MEDIA,
                new String[]{COL_MEDIA_TIMESTAMP, COL_MEDIA_TYPE, COL_MEDIA_PATH,
                        COL_MEDIA_FILE_NAME, COL_MEDIA_SIZE, COL_MEDIA_MIME},
                COL_MEDIA_DIALOG_ID + "=? AND " + COL_MEDIA_MSG_ID + "=?",
                new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                null, null, COL_MEDIA_TIMESTAMP + " ASC, " + COL_MEDIA_ID + " ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    out.add(new MediaRevision(
                            normalizeEpochMillis(cursor.getLong(0)),
                            cursor.getInt(1),
                            cursor.isNull(2) ? null : cursor.getString(2),
                            cursor.isNull(3) ? null : cursor.getString(3),
                            cursor.getLong(4),
                            cursor.isNull(5) ? null : cursor.getString(5)));
                }
            }
        } catch (Exception e) {
            // Silently handle
        }
        return out;
    }

    /** True when at least one revision exists — cheap existence probe for menu gating. */
    public boolean hasMediaRevisions(long dialogId, int messageId) {
        if (dialogId == 0L || messageId == 0) return false;
        try (Cursor cursor = getReadableDatabase().query(TABLE_MEDIA,
                new String[]{COL_MEDIA_ID},
                COL_MEDIA_DIALOG_ID + "=? AND " + COL_MEDIA_MSG_ID + "=?",
                new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                null, null, null, "1")) {
            return cursor != null && cursor.getCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Drops the sandbox copies of a message's media revisions and their rows. */
    public void deleteMediaRevisions(long dialogId, int messageId) {
        try {
            List<MediaRevision> revs = getMediaRevisions(dialogId, messageId);
            for (MediaRevision r : revs) {
                if (r.localPath != null && !r.localPath.isEmpty()) {
                    try { new java.io.File(r.localPath).delete(); } catch (Throwable ignore) {}
                }
            }
            getWritableDatabase().delete(TABLE_MEDIA,
                    COL_MEDIA_DIALOG_ID + "=? AND " + COL_MEDIA_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)});
        } catch (Exception e) {
            // Silently handle
        }
    }

    /**
     * Retrieves all recorded previous revisions of an edited message in chronological order.
     */
    public List<MessageEditEntry> getEditHistory(long dialogId, int messageId) {
        List<MessageEditEntry> history = new ArrayList<>();
        if (dialogId == 0L || messageId == 0) return history;
        try (Cursor cursor = getReadableDatabase().query(TABLE_EDITS,
                new String[]{COL_EDIT_TIMESTAMP, COL_EDIT_PREV_TEXT, COL_EDIT_ENTITIES},
                COL_EDIT_DIALOG_ID + "=? AND " + COL_EDIT_MSG_ID + "=?",
                new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                null, null, COL_EDIT_TIMESTAMP + " ASC, " + COL_EDIT_ID + " ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long ts = normalizeEpochMillis(cursor.getLong(0));
                    String text = cursor.getString(1);
                    String entJson = cursor.isNull(2) ? null : cursor.getString(2);
                    history.add(new MessageEditEntry(ts, text, entJson));
                }
            }
        } catch (Exception e) {
            // Silently handle
        }
        return history;
    }

    public void recordCollapsedSpam(long dialogId, int messageId, long senderId, String reason) {
        if (dialogId == 0L || messageId == 0) return;
        String safeReason = (reason == null || reason.isEmpty()) ? "spam" : reason;
        collapsedSpamByDialog
                .computeIfAbsent(dialogId, k -> new ConcurrentHashMap<>())
                .put(messageId, safeReason);
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put(COL_SPAM_DIALOG_ID, dialogId);
            v.put(COL_SPAM_MSG_ID, messageId);
            v.put(COL_SPAM_SENDER_ID, senderId);
            v.put(COL_SPAM_REASON, safeReason);
            v.put(COL_SPAM_AT, System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_COLLAPSED_SPAM, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            if ((collapsedInsertTick.incrementAndGet() & 63) == 0) {
                pruneCollapsedSpamOverflow(db);
            }
        } catch (Throwable ignored) {}
    }

    private void pruneCollapsedSpamOverflow(SQLiteDatabase db) {
        if (db == null) return;
        List<long[]> evicted = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT " + COL_SPAM_DIALOG_ID + ", " + COL_SPAM_MSG_ID + " FROM " + TABLE_COLLAPSED_SPAM
                        + " ORDER BY " + COL_SPAM_AT + " DESC LIMIT -1 OFFSET " + MAX_COLLAPSED_SPAM_ROWS,
                null)) {
            while (c != null && c.moveToNext()) {
                evicted.add(new long[]{c.getLong(0), c.getInt(1)});
            }
        } catch (Throwable ignored) {}
        if (evicted.isEmpty()) return;
        db.execSQL("DELETE FROM " + TABLE_COLLAPSED_SPAM + " WHERE rowid IN ("
                + "SELECT rowid FROM " + TABLE_COLLAPSED_SPAM
                + " ORDER BY " + COL_SPAM_AT + " DESC LIMIT -1 OFFSET " + MAX_COLLAPSED_SPAM_ROWS + ");");
        for (long[] pair : evicted) {
            ConcurrentHashMap<Integer, String> m = collapsedSpamByDialog.get(pair[0]);
            if (m != null) {
                m.remove((int) pair[1]);
            }
        }
    }

    public boolean isCollapsedSpam(long dialogId, int messageId) {
        if (dialogId == 0L || messageId == 0) return false;
        prepareCacheForLookup();
        ConcurrentHashMap<Integer, String> m = collapsedSpamByDialog.get(dialogId);
        return m != null && m.containsKey(messageId);
    }

    public String getCollapsedSpamReason(long dialogId, int messageId) {
        if (dialogId == 0L || messageId == 0) return null;
        prepareCacheForLookup();
        ConcurrentHashMap<Integer, String> m = collapsedSpamByDialog.get(dialogId);
        return m != null ? m.get(messageId) : null;
    }

    public void removeCollapsedSpam(long dialogId, int messageId) {
        if (dialogId == 0L || messageId == 0) return;
        ConcurrentHashMap<Integer, String> m = collapsedSpamByDialog.get(dialogId);
        if (m != null) m.remove(messageId);
        try {
            getWritableDatabase().delete(TABLE_COLLAPSED_SPAM,
                    COL_SPAM_DIALOG_ID + "=? AND " + COL_SPAM_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)});
        } catch (Throwable ignored) {}
    }

    public void upsertShadowMessage(long dialogId, int messageId, long senderId, int dateSec,
                                    int mediaType, long mediaSize, String metaJson,
                                    String previewPath, boolean dehydrated) {
        if (dialogId == 0L || messageId <= 0) return;
        upsertShadowMessagesBatch(Collections.singletonList(new ShadowMessageRecord(
                dialogId, messageId, senderId, dateSec, mediaType, mediaSize,
                metaJson, previewPath, dehydrated, System.currentTimeMillis())));
    }

    /** Batched API-24-safe shadow vault upsert preserving existing preview_path when new previewPath is empty. */
    public void upsertShadowMessagesBatch(List<ShadowMessageRecord> records) {
        if (records == null || records.isEmpty()) return;
        for (int i = 0; i < records.size(); i++) {
            ShadowMessageRecord r = records.get(i);
            if (r != null && r.dialogId != 0L && r.messageId > 0 && r.previewPath != null && !r.previewPath.isEmpty()) {
                shadowPreviewCache.put(deletedKey(r.dialogId, r.messageId), r.previewPath);
            }
        }
        SQLiteDatabase db = null;
        SQLiteStatement updWithPreview = null;
        SQLiteStatement updKeepPreview = null;
        SQLiteStatement insStmt = null;
        boolean inTx = false;
        int written = 0;
        try {
            db = getWritableDatabase();
            updWithPreview = db.compileStatement("UPDATE " + TABLE_SHADOW_MESSAGES + " SET "
                    + COL_SHADOW_SENDER_ID + "=?, " + COL_SHADOW_DATE_SEC + "=?, "
                    + COL_SHADOW_MEDIA_TYPE + "=?, " + COL_SHADOW_MEDIA_SIZE + "=?, "
                    + COL_SHADOW_META_ZLIB + "=COALESCE(?, " + COL_SHADOW_META_ZLIB + "), "
                    + COL_SHADOW_PREVIEW_PATH + "=?, "
                    + COL_SHADOW_DEHYDRATED + "=?, " + COL_SHADOW_CAPTURED_AT + "=? "
                    + "WHERE " + COL_SHADOW_DIALOG_ID + "=? AND " + COL_SHADOW_MSG_ID + "=?");
            updKeepPreview = db.compileStatement("UPDATE " + TABLE_SHADOW_MESSAGES + " SET "
                    + COL_SHADOW_SENDER_ID + "=?, " + COL_SHADOW_DATE_SEC + "=?, "
                    + COL_SHADOW_MEDIA_TYPE + "=?, " + COL_SHADOW_MEDIA_SIZE + "=?, "
                    + COL_SHADOW_META_ZLIB + "=COALESCE(?, " + COL_SHADOW_META_ZLIB + "), "
                    + COL_SHADOW_DEHYDRATED + "=?, " + COL_SHADOW_CAPTURED_AT + "=? "
                    + "WHERE " + COL_SHADOW_DIALOG_ID + "=? AND " + COL_SHADOW_MSG_ID + "=?");
            insStmt = db.compileStatement("INSERT OR IGNORE INTO " + TABLE_SHADOW_MESSAGES + " ("
                    + COL_SHADOW_DIALOG_ID + ", " + COL_SHADOW_MSG_ID + ", "
                    + COL_SHADOW_SENDER_ID + ", " + COL_SHADOW_DATE_SEC + ", "
                    + COL_SHADOW_MEDIA_TYPE + ", " + COL_SHADOW_MEDIA_SIZE + ", "
                    + COL_SHADOW_META_ZLIB + ", " + COL_SHADOW_PREVIEW_PATH + ", "
                    + COL_SHADOW_DEHYDRATED + ", " + COL_SHADOW_CAPTURED_AT
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            db.beginTransaction();
            inTx = true;
            long defaultNow = System.currentTimeMillis();
            for (int i = 0; i < records.size(); i++) {
                ShadowMessageRecord r = records.get(i);
                if (r == null || r.dialogId == 0L || r.messageId <= 0) continue;
                byte[] metaBytes = (r.metaJson != null && !r.metaJson.isEmpty())
                        ? zlibCompressBytes(r.metaJson.getBytes(StandardCharsets.UTF_8))
                        : null;
                boolean hasPreview = r.previewPath != null && !r.previewPath.isEmpty();
                long capTs = r.capturedAtMs > 0L ? r.capturedAtMs : defaultNow;
                int dehyd = r.dehydrated ? 1 : 0;
                int updatedRows;
                if (hasPreview) {
                    updWithPreview.clearBindings();
                    updWithPreview.bindLong(1, r.senderId);
                    updWithPreview.bindLong(2, r.dateSec);
                    updWithPreview.bindLong(3, r.mediaType);
                    updWithPreview.bindLong(4, r.mediaSize);
                    if (metaBytes != null && metaBytes.length > 0) updWithPreview.bindBlob(5, metaBytes);
                    else updWithPreview.bindNull(5);
                    updWithPreview.bindString(6, r.previewPath);
                    updWithPreview.bindLong(7, dehyd);
                    updWithPreview.bindLong(8, capTs);
                    updWithPreview.bindLong(9, r.dialogId);
                    updWithPreview.bindLong(10, r.messageId);
                    updatedRows = updWithPreview.executeUpdateDelete();
                } else {
                    updKeepPreview.clearBindings();
                    updKeepPreview.bindLong(1, r.senderId);
                    updKeepPreview.bindLong(2, r.dateSec);
                    updKeepPreview.bindLong(3, r.mediaType);
                    updKeepPreview.bindLong(4, r.mediaSize);
                    if (metaBytes != null && metaBytes.length > 0) updKeepPreview.bindBlob(5, metaBytes);
                    else updKeepPreview.bindNull(5);
                    updKeepPreview.bindLong(6, dehyd);
                    updKeepPreview.bindLong(7, capTs);
                    updKeepPreview.bindLong(8, r.dialogId);
                    updKeepPreview.bindLong(9, r.messageId);
                    updatedRows = updKeepPreview.executeUpdateDelete();
                }
                if (updatedRows == 0) {
                    insStmt.clearBindings();
                    insStmt.bindLong(1, r.dialogId);
                    insStmt.bindLong(2, r.messageId);
                    insStmt.bindLong(3, r.senderId);
                    insStmt.bindLong(4, r.dateSec);
                    insStmt.bindLong(5, r.mediaType);
                    insStmt.bindLong(6, r.mediaSize);
                    if (metaBytes != null && metaBytes.length > 0) insStmt.bindBlob(7, metaBytes);
                    else insStmt.bindNull(7);
                    if (hasPreview) insStmt.bindString(8, r.previewPath);
                    else insStmt.bindNull(8);
                    insStmt.bindLong(9, dehyd);
                    insStmt.bindLong(10, capTs);
                    insStmt.executeInsert();
                }
                written++;
            }
            db.setTransactionSuccessful();
        } catch (Throwable ignored) {
        } finally {
            if (updWithPreview != null) try { updWithPreview.close(); } catch (Throwable ignored) {}
            if (updKeepPreview != null) try { updKeepPreview.close(); } catch (Throwable ignored) {}
            if (insStmt != null) try { insStmt.close(); } catch (Throwable ignored) {}
            if (db != null && inTx) try { db.endTransaction(); } catch (Throwable ignored) {}
        }
        if (written > 0 && db != null && (shadowInsertTick.addAndGet(written) & 127) < written) {
            pruneShadowOverflow(db);
        }
    }

    private void pruneShadowOverflow(SQLiteDatabase db) {
        if (db == null) return;
        List<long[]> evictedKeys = new ArrayList<>();
        List<String> evictedPaths = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT " + COL_SHADOW_DIALOG_ID + ", " + COL_SHADOW_MSG_ID + ", " + COL_SHADOW_PREVIEW_PATH
                        + " FROM " + TABLE_SHADOW_MESSAGES
                        + " ORDER BY " + COL_SHADOW_CAPTURED_AT + " DESC LIMIT -1 OFFSET " + MAX_SHADOW_MESSAGES_ROWS,
                null)) {
            while (c != null && c.moveToNext()) {
                evictedKeys.add(new long[]{c.getLong(0), c.getInt(1)});
                String p = c.isNull(2) ? "" : c.getString(2);
                if (p != null && !p.isEmpty()) {
                    evictedPaths.add(p);
                }
            }
        } catch (Throwable ignored) {}
        if (evictedKeys.isEmpty()) return;
        try {
            db.execSQL("DELETE FROM " + TABLE_SHADOW_MESSAGES + " WHERE rowid IN ("
                    + "SELECT rowid FROM " + TABLE_SHADOW_MESSAGES
                    + " ORDER BY " + COL_SHADOW_CAPTURED_AT + " DESC LIMIT -1 OFFSET " + MAX_SHADOW_MESSAGES_ROWS + ");");
        } catch (Throwable ignored) {}
        for (long[] pair : evictedKeys) {
            shadowPreviewCache.remove(deletedKey(pair[0], (int) pair[1]));
        }
        for (String p : evictedPaths) {
            deleteShadowPreviewFileSafe(p);
        }
    }

    private static void deleteShadowPreviewFileSafe(String previewPath) {
        if (previewPath == null || previewPath.isEmpty()) return;
        try {
            if (!previewPath.contains("shadow_previews")) return;
            java.io.File f = new java.io.File(previewPath);
            if (f.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {}
    }

    public void clearShadowPreviewPath(long dialogId, int messageId) {
        if (dialogId == 0L || messageId <= 0) return;
        shadowPreviewCache.remove(deletedKey(dialogId, messageId));
        try {
            ContentValues cv = new ContentValues();
            cv.putNull(COL_SHADOW_PREVIEW_PATH);
            getWritableDatabase().update(TABLE_SHADOW_MESSAGES, cv,
                    COL_SHADOW_DIALOG_ID + "=? AND " + COL_SHADOW_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)});
        } catch (Throwable ignored) {}
    }

    public void markShadowDehydrated(long dialogId, int messageId, boolean dehydrated) {
        if (dialogId == 0L || messageId <= 0) return;
        try {
            ContentValues v = new ContentValues();
            v.put(COL_SHADOW_DEHYDRATED, dehydrated ? 1 : 0);
            getWritableDatabase().update(TABLE_SHADOW_MESSAGES, v,
                    COL_SHADOW_DIALOG_ID + "=? AND " + COL_SHADOW_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)});
        } catch (Throwable ignored) {}
    }

    /** O(1) UI-safe lookup for Lazy Shadow Vault WebP micro-preview path. */
    public String getShadowPreviewPath(long dialogId, int messageId) {
        if (dialogId == 0L || messageId <= 0) return null;
        prepareCacheForLookup();
        return shadowPreviewCache.get(deletedKey(dialogId, messageId));
    }

    public ShadowMessageRecord getShadowMessageRecord(long dialogId, int messageId) {
        if (dialogId == 0L || messageId <= 0) return null;
        try (Cursor c = getReadableDatabase().query(TABLE_SHADOW_MESSAGES,
                new String[]{COL_SHADOW_SENDER_ID, COL_SHADOW_DATE_SEC, COL_SHADOW_MEDIA_TYPE,
                        COL_SHADOW_MEDIA_SIZE, COL_SHADOW_META_ZLIB, COL_SHADOW_PREVIEW_PATH,
                        COL_SHADOW_DEHYDRATED, COL_SHADOW_CAPTURED_AT},
                COL_SHADOW_DIALOG_ID + "=? AND " + COL_SHADOW_MSG_ID + "=?",
                new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                null, null, null, "1")) {
            if (c != null && c.moveToFirst()) {
                byte[] blob = c.isNull(4) ? null : c.getBlob(4);
                String meta = "";
                if (blob != null && blob.length > 0) {
                    byte[] raw = zlibDecompressBytes(blob);
                    if (raw != null) {
                        meta = new String(raw, StandardCharsets.UTF_8);
                    }
                }
                return new ShadowMessageRecord(
                        dialogId, messageId, c.getLong(0), c.getInt(1), c.getInt(2), c.getLong(3),
                        meta, c.isNull(5) ? "" : c.getString(5), c.getInt(6) != 0,
                        normalizeEpochMillis(c.getLong(7)));
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public int getShadowMessageCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE_SHADOW_MESSAGES, null)) {
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (Throwable ignored) {}
        return 0;
    }

    public static byte[] zlibCompressBytes(byte[] raw) {
        if (raw == null || raw.length == 0) return new byte[0];
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 2))) {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buf = new byte[1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable ignored) {
            return raw;
        } finally {
            deflater.end();
        }
    }

    public static byte[] zlibDecompressBytes(byte[] compressed) {
        if (compressed == null || compressed.length == 0) return new byte[0];
        Inflater inflater = new Inflater();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(compressed.length * 2)) {
            inflater.setInput(compressed);
            byte[] buf = new byte[1024];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable ignored) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
