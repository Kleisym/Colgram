package org.colgram.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Anti-spam userbot: a Telethon-based account guard ported from Kleisym/spamblock-mailing.
 *
 * ARCHITECTURE
 * ------------
 * The userbot runs inside the bundled CPython interpreter (Chaquopy) as a normal Python
 * program. It is NOT a plugin and does not use the plugin command dispatcher — it is a
 * long-lived service with its own login flow, so it gets its own entry points here.
 *
 * Three Python modules are shipped as assets and copied into app-private storage at start:
 *
 *   antispam/spamblock_bot.py     the ported userbot (scoring, DB, broadcaster, handlers)
 *   antispam/spamblock_bridge.py  upstream's Chaquopy lifecycle wrapper
 *   antispam/colgram_antispam.py  Colgram's adapter (see below)
 *
 * WHY THERE IS AN ADAPTER
 * -----------------------
 * Upstream's bridge expects a Java callback object (onStatusChange / requestCode /
 * requestPassword). colgram-core cannot build one: it has no Chaquopy on its classpath, so
 * it cannot produce an object the Python side recognises as that interface, and the code /
 * password requests are blocking calls that would have to wait on a UI dialog from a
 * non-UI thread.
 *
 * Instead the adapter inverts control: it owns a plain-Python callback and publishes a
 * poll-able status record. This class reads that record and pushes credentials in. The
 * Python thread never blocks on the Android side.
 *
 * CREDENTIALS
 * -----------
 * api_id / api_hash / phone / 2FA password are stored in the same preferences file the rest
 * of Colgram uses, under the calling account's name. The Telethon session file lives in
 * app-private storage next to the plugin directory, so it is covered by the app sandbox and
 * not readable by other apps.
 *
 * The user's real Telegram login is NOT used: the userbot needs its OWN api_id/api_hash from
 * my.telegram.org and a phone number. That is worth surfacing in the UI, because it is the
 * single most common source of confusion with this kind of tool.
 */
public final class ColgramAntiSpam {

    private static final String TAG = "ColgramAntiSpam";

    /** Directory name under filesDir/. App-private, so no permission is required. */
    private static final String DIR_NAME = "antispam";

    /** Asset subdirectory holding the three modules. */
    private static final String ASSET_DIR = "antispam";

    private static final String[] MODULES = {
            "spamblock_bot.py",
            "spamblock_bridge.py",
            "colgram_antispam.py",
    };

    private static final String PREFS = "colgram_prefs";
    private static final String KEY_API_ID = "antispam_api_id_";
    private static final String KEY_API_HASH = "antispam_api_hash_";
    private static final String KEY_PHONE = "antispam_phone_";
    private static final String KEY_PASSWORD = "antispam_password_";
    private static final String KEY_ENABLED = "antispam_enabled_";

    private ColgramAntiSpam() {}

    /** App-private directory the userbot runs from. Created on demand. */
    public static File moduleDir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * Copy the three Python modules out of assets into app-private storage.
     *
     * Refreshed on every call, deliberately: these are vendored infrastructure that must
     * track the Colgram build. A user editing them in place would be surprised, so they are
     * not treated as user-editable the way plugins are.
     *
     * @return true when every module is present on disk afterwards
     */
    public static boolean installModules(Context context) {
        if (context == null) return false;
        File dir = moduleDir(context);
        boolean allOk = true;
        for (String name : MODULES) {
            if (!copyAsset(context, ASSET_DIR + "/" + name, new File(dir, name))) {
                allOk = false;
            }
        }
        if (allOk) {
            Log.i(TAG, "anti-spam modules installed in " + dir.getAbsolutePath());
        }
        return allOk;
    }

    private static boolean copyAsset(Context context, String assetPath, File out) {
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = context.getAssets().open(assetPath);
            fos = new FileOutputStream(out, false);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not install " + assetPath + ": " + t.getMessage());
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    // --- credential storage -------------------------------------------------

    public static void saveCredentials(Context context, int account, String apiId, String apiHash,
                                       String phone, String password) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_API_ID + account, apiId == null ? "" : apiId.trim())
                .putString(KEY_API_HASH + account, apiHash == null ? "" : apiHash.trim())
                .putString(KEY_PHONE + account, phone == null ? "" : phone.trim())
                .putString(KEY_PASSWORD + account, password == null ? "" : password)
                .apply();
    }

    public static String getApiId(Context context, int account) {
        return prefs(context).getString(KEY_API_ID + account, "");
    }

    public static String getApiHash(Context context, int account) {
        return prefs(context).getString(KEY_API_HASH + account, "");
    }

    public static String getPhone(Context context, int account) {
        return prefs(context).getString(KEY_PHONE + account, "");
    }

    public static String getPassword(Context context, int account) {
        return prefs(context).getString(KEY_PASSWORD + account, "");
    }

    /** Whether the userbot should be auto-started on next launch. */
    public static boolean isEnabled(Context context, int account) {
        return prefs(context).getBoolean(KEY_ENABLED + account, false);
    }

    public static void setEnabled(Context context, int account, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED + account, enabled).apply();
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // --- lifecycle ----------------------------------------------------------

    /**
     * Start the userbot for an account, using the stored credentials.
     *
     * Returns a human-readable failure string, or null when the run request was accepted.
     * "Accepted" is not "connected": the connect happens on a background thread and its
     * progress is readable through getStatus().
     */
    public static String start(Context context, int account) {
        if (context == null) return "нет контекста";
        String apiId = getApiId(context, account);
        String apiHash = getApiHash(context, account);
        String phone = getPhone(context, account);
        String password = getPassword(context, account);

        if (apiId.isEmpty() || apiHash.isEmpty() || phone.isEmpty()) {
            return "Заполните api_id, api_hash и телефон";
        }
        if (!installModules(context)) {
            return "Не удалось установить модули анти-спама";
        }

        // The userbot imports its siblings by bare module name, so its directory has to be
        // on sys.path before anything is executed.
        String dir = moduleDir(context).getAbsolutePath();
        ColgramPythonEngine.addPythonPath(dir);

        String script =
                "import sys\n" +
                "_d = " + pyStr(dir) + "\n" +
                "if _d not in sys.path:\n" +
                "    sys.path.insert(0, _d)\n" +
                // The Python engine execs into a persistent globals dict, so a plain
                // `import` would hand back a module cached from a previous call. That
                // module may have been imported before installModules() refreshed the
                // .py files on disk, which would silently run the OLD code. Importing
                // fresh only when the module is absent and letting the adapter own its
                // own state avoids that: the adapter is a long-lived singleton by design,
                // and re-importing it mid-session would tear down the running userbot.
                "import colgram_antispam as _a\n" +
                "_a.start(" + pyStr(apiId) + ", " + pyStr(apiHash) + ", " + pyStr(phone) + ", " +
                pyStr(password) + ", " + pyStr(dir) + ")\n" +
                "print(_a.get_status())\n";
        try {
            String out = ColgramPythonEngine.executeCode(script);
            setEnabled(context, account, true);
            Log.i(TAG, "anti-spam start requested: " + out);
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "anti-spam start failed", t);
            return "Ошибка запуска: " + t.getMessage();
        }
    }

    /** Stop the userbot. Returns null on success, or a failure string. */
    public static String stop(Context context, int account) {
        try {
            String out = ColgramPythonEngine.executeCode(
                    "import colgram_antispam as _a\nprint(_a.stop())\n");
            setEnabled(context, account, false);
            Log.i(TAG, "anti-spam stop requested: " + out);
            return null;
        } catch (Throwable t) {
            return "Ошибка остановки: " + t.getMessage();
        }
    }

    /**
     * Current status as a small map-like string, or null when the interpreter is not up.
     *
     * Deliberately returned as raw text rather than a parsed object: the adapter already
     * produces a stable dict, and re-parsing it here would mean maintaining a second
     * definition of the same shape.
     */
    public static String getStatus() {
        try {
            return ColgramPythonEngine.executeCode(
                    "import colgram_antispam as _a\n"
                            + "s = _a.get_status()\n"
                            + "print('%s|%s|%s|%s' % (s.get('phase',''), s.get('running'), "
                            + "s.get('text',''), s.get('username','')))\n");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Push the login code or 2FA password that a parked provider is waiting for. */
    public static boolean submitCredentials(String code, String password) {
        try {
            ColgramPythonEngine.executeCode(
                    "import colgram_antispam as _a\n"
                            + "_a.submit_credentials(code=" + pyJavaString(code) + ", password="
                            + pyJavaString(password) + ")\n");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "submitCredentials failed: " + t.getMessage());
            return false;
        }
    }

    /** Python literal for a plain value. */
    private static String pyStr(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.append('\'').toString();
    }

    /** Python expression for an optional string: None when null or empty. */
    private static String pyJavaString(String s) {
        if (s == null || s.isEmpty()) return "None";
        return pyStr(s);
    }
}
