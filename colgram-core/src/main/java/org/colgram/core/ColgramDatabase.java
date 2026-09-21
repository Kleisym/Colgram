package org.colgram.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * ColgramDatabase — Secure local SQLite storage for preserving deleted messages
 * and tracking edit history across all chats.
 */
public class ColgramDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "colgram_vault.db";
    private static final int DATABASE_VERSION = 3;

    // Table: Deleted Messages
    public static final String TABLE_DELETED = "deleted_messages";
    public static final String COL_DEL_DIALOG_ID = "dialog_id";
    public static final String COL_DEL_MSG_ID = "message_id";
    public static final String COL_DEL_TIMESTAMP = "deleted_at";

    // Table: Message Edit History
    public static final String TABLE_EDITS = "message_edits";
    public static final String COL_EDIT_ID = "id";
    public static final String COL_EDIT_DIALOG_ID = "dialog_id";
    public static final String COL_EDIT_MSG_ID = "message_id";
    public static final String COL_EDIT_TIMESTAMP = "edit_timestamp";
    public static final String COL_EDIT_PREV_TEXT = "previous_text";

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

    private static ColgramDatabase instance;

    public static synchronized ColgramDatabase getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new ColgramDatabase(context.getApplicationContext());
        }
        return instance;
    }

    public ColgramDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
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
                + COL_EDIT_PREV_TEXT + " TEXT"
                + ");");

        // Index for fast lookup by dialog and message id
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edits_lookup ON " + TABLE_EDITS
                + " (" + COL_EDIT_DIALOG_ID + ", " + COL_EDIT_MSG_ID + ");");

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
                + " (" + COL_MEDIA_DIALOG_ID + ", " + COL_MEDIA_MSG_ID + ");");
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
                    + " (" + COL_MEDIA_DIALOG_ID + ", " + COL_MEDIA_MSG_ID + ");");
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

    public void unblockNotificationsFrom(long userId) {
        if (userId == 0) return;
        try {
            getWritableDatabase().delete(TABLE_BLOCKED_USERS,
                    COL_BLOCK_USER_ID + "=?", new String[]{String.valueOf(userId)});
        } catch (Exception e) {
            // Silently handle
        }
    }

    public boolean isNotificationsBlockedFrom(long userId) {
        if (userId == 0) return false;
        try {
            Cursor cursor = getReadableDatabase().query(TABLE_BLOCKED_USERS,
                    new String[]{COL_BLOCK_USER_ID},
                    COL_BLOCK_USER_ID + "=?",
                    new String[]{String.valueOf(userId)},
                    null, null, null);
            boolean exists = cursor != null && cursor.getCount() > 0;
            if (cursor != null) cursor.close();
            return exists;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Long> getBlockedNotifyUsers() {
        List<Long> out = new ArrayList<>();
        try {
            Cursor cursor = getReadableDatabase().query(TABLE_BLOCKED_USERS,
                    new String[]{COL_BLOCK_USER_ID},
                    null, null, null, null, COL_BLOCK_ADDED_AT + " ASC");
            if (cursor != null) {
                while (cursor.moveToNext()) out.add(cursor.getLong(0));
                cursor.close();
            }
        } catch (Exception e) {
            // Silently handle
        }
        return out;
    }

    /**
     * Records a message as deleted by the other party.
     */
    public void markMessageDeleted(long dialogId, int messageId) {
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
     * Checks if a message was marked as deleted.
     */
    public boolean isMessageDeleted(long dialogId, int messageId) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_DELETED,
                    new String[]{COL_DEL_MSG_ID},
                    COL_DEL_DIALOG_ID + "=? AND " + COL_DEL_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, null);
            boolean exists = (cursor != null && cursor.getCount() > 0);
            if (cursor != null) cursor.close();
            return exists;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stores a prior version of a message before it was edited.
     * Skips duplicates so repeated update packets do not inflate the history.
     */
    public void saveMessageEdit(long dialogId, int messageId, String previousText, long editTimestamp) {
        if (previousText == null || previousText.trim().isEmpty()) return;

        try {
            SQLiteDatabase db = getWritableDatabase();

            // Deduplicate: skip if this exact revision is already the last one stored.
            Cursor last = db.query(TABLE_EDITS,
                    new String[]{COL_EDIT_PREV_TEXT},
                    COL_EDIT_DIALOG_ID + "=? AND " + COL_EDIT_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, COL_EDIT_TIMESTAMP + " DESC", "1");
            if (last != null) {
                if (last.moveToFirst()) {
                    String existing = last.getString(0);
                    if (previousText.equals(existing)) {
                        last.close();
                        return;
                    }
                }
                last.close();
            }

            ContentValues values = new ContentValues();
            values.put(COL_EDIT_DIALOG_ID, dialogId);
            values.put(COL_EDIT_MSG_ID, messageId);
            values.put(COL_EDIT_PREV_TEXT, previousText);
            values.put(COL_EDIT_TIMESTAMP, editTimestamp > 0 ? editTimestamp : System.currentTimeMillis());
            db.insert(TABLE_EDITS, null, values);
        } catch (Exception e) {
            // Silently handle
        }
    }

    public static class MessageEditEntry {
        public final long timestamp;
        public final String text;

        public MessageEditEntry(long timestamp, String text) {
            this.timestamp = timestamp;
            this.text = text;
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

            // Dedupe against the newest stored revision for this message.
            Cursor last = db.query(TABLE_MEDIA,
                    new String[]{COL_MEDIA_REMOTE_ID, COL_MEDIA_FILE_NAME},
                    COL_MEDIA_DIALOG_ID + "=? AND " + COL_MEDIA_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, COL_MEDIA_TIMESTAMP + " DESC", "1");
            if (last != null) {
                if (last.moveToFirst()) {
                    long existingRemote = last.getLong(0);
                    String existingName = last.isNull(1) ? null : last.getString(1);
                    // Same attachment id AND same name => nothing actually changed.
                    if (remoteId != 0 && existingRemote == remoteId) {
                        last.close();
                        return;
                    }
                    if (remoteId == 0 && existingName != null && existingName.equals(fileName)) {
                        last.close();
                        return;
                    }
                }
                last.close();
            }

            ContentValues values = new ContentValues();
            values.put(COL_MEDIA_DIALOG_ID, dialogId);
            values.put(COL_MEDIA_MSG_ID, messageId);
            values.put(COL_MEDIA_TIMESTAMP, capturedAt > 0 ? capturedAt : System.currentTimeMillis());
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
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_MEDIA,
                    new String[]{COL_MEDIA_TIMESTAMP, COL_MEDIA_TYPE, COL_MEDIA_PATH,
                            COL_MEDIA_FILE_NAME, COL_MEDIA_SIZE, COL_MEDIA_MIME},
                    COL_MEDIA_DIALOG_ID + "=? AND " + COL_MEDIA_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, COL_MEDIA_TIMESTAMP + " ASC");

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    out.add(new MediaRevision(
                            cursor.getLong(0),
                            cursor.getInt(1),
                            cursor.isNull(2) ? null : cursor.getString(2),
                            cursor.isNull(3) ? null : cursor.getString(3),
                            cursor.getLong(4),
                            cursor.isNull(5) ? null : cursor.getString(5)));
                }
                cursor.close();
            }
        } catch (Exception e) {
            // Silently handle
        }
        return out;
    }

    /** True when at least one revision exists — cheap existence probe for menu gating. */
    public boolean hasMediaRevisions(long dialogId, int messageId) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_MEDIA,
                    new String[]{COL_MEDIA_ID},
                    COL_MEDIA_DIALOG_ID + "=? AND " + COL_MEDIA_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, null, "1");
            boolean exists = cursor != null && cursor.getCount() > 0;
            if (cursor != null) cursor.close();
            return exists;
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
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_EDITS,
                    new String[]{COL_EDIT_TIMESTAMP, COL_EDIT_PREV_TEXT},
                    COL_EDIT_DIALOG_ID + "=? AND " + COL_EDIT_MSG_ID + "=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, COL_EDIT_TIMESTAMP + " ASC");

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long ts = cursor.getLong(0);
                    String text = cursor.getString(1);
                    history.add(new MessageEditEntry(ts, text));
                }
                cursor.close();
            }
        } catch (Exception e) {
            // Silently handle
        }
        return history;
    }
}
