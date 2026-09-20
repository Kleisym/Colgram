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
    private static final int DATABASE_VERSION = 2;

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
