package org.colgram.core;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ColgramAccountManager — Multi-Session & Unlimited Profile Manager.
 * 
 * Allows saving active sessions to `/Documents/Colgram/Sessions/`
 * and instantly swapping accounts without being restricted by device limits.
 */
public class ColgramAccountManager {

    private static File sessionsDir = null;

    public static void init(Context context) {
        try {
            File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            sessionsDir = new File(docs, "Colgram/Sessions");
            if (!sessionsDir.exists()) {
                sessionsDir.mkdirs();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Saves the current account session files to the Sessions directory.
     */
    public static boolean exportSession(Context context, int accountIndex, String sessionName) {
        if (sessionsDir == null) return false;
        try {
            File targetDir = new File(sessionsDir, sessionName.replaceAll("[^a-zA-Z0-9_-]", "_"));
            if (!targetDir.exists()) targetDir.mkdirs();

            // Telegram user config and database files
            String configName = accountIndex == 0 ? "userconfing" : "userconfig" + accountIndex;
            File configFile = context.getDatabasePath(configName + ".xml");
            if (configFile.exists()) {
                copyFile(configFile, new File(targetDir, "userconfig.xml"));
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Lists all saved sessions.
     */
    public static List<String> listSavedSessions() {
        List<String> list = new ArrayList<>();
        if (sessionsDir != null && sessionsDir.exists()) {
            File[] files = sessionsDir.listFiles(File::isDirectory);
            if (files != null) {
                for (File f : files) list.add(f.getName());
            }
        }
        return list;
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}
