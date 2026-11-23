package org.colgram.core;

import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * ColgramChatExport — built-in chat export (was plugins/chat_exporter.py).
 *
 * Writes the messages the client currently holds for a dialog into a self-contained HTML
 * file inside the Colgram sandbox (files/Colgram/exports/). It reads MessageObject cache,
 * so it exports what the app has loaded - the same content the chat screen shows - and it
 * never touches the network or the shared storage sandbox.
 *
 * Python used to do this by reading the same structures through Chaquopy; the Java version
 * is smaller, starts instantly and removes the plugin round-trip. Triggered by the
 * {@code .export} command in the chat input.
 */
public final class ColgramChatExport {

    private static final String TAG = "ColgramChatExport";

    private ColgramChatExport() {}

    /** @return the exported file, or null when nothing could be exported. */
    public static File exportDialog(long dialogId) {
        try {
            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
            int acct = ucClass.getField("selectedAccount").getInt(null);
            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, acct);
            Object dialogMessage = mcClass.getField("dialogMessage").get(mc);
            Object list = dialogMessage.getClass().getMethod("get", long.class).invoke(dialogMessage, dialogId);
            if (!(list instanceof List)) return null;

            List<Object> messages = new ArrayList<>((List<Object>) list);
            if (messages.isEmpty()) return null;

            StringBuilder html = new StringBuilder(16384);
            html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                    .append("<title>Colgram export ").append(dialogId).append("</title><style>")
                    .append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0e1117;color:#e8eaed;max-width:720px;margin:0 auto;padding:24px}")
                    .append(".m{margin:8px 0;padding:10px 14px;border-radius:12px;background:#1b212c}")
                    .append(".out{background:#2b1d2a;margin-left:15%}")
                    .append(".meta{font-size:12px;color:#8b93a1;margin-bottom:4px}")
                    .append("</style></head><body><h2>Экспорт диалога ").append(dialogId).append("</h2>");

            int exported = 0;
            for (Object mo : messages) {
                try {
                    CharSequence text = (CharSequence) mo.getClass().getField("messageText").get(mo);
                    Object owner = mo.getClass().getField("messageOwner").get(mo);
                    int date = owner.getClass().getField("date").getInt(owner);
                    boolean out = owner.getClass().getField("out").getBoolean(owner);
                    String safe = text == null ? "" : htmlEscape(text.toString());
                    html.append("<div class=\"m").append(out ? " out" : "").append("\">")
                            .append("<div class=\"meta\">")
                            .append(out ? "Вы" : "Собеседник").append(" · ")
                            .append(new java.util.Date(date * 1000L))
                            .append("</div><div>").append(safe).append("</div></div>");
                    exported++;
                } catch (Throwable ignored) {}
            }
            html.append("</body></html>");

            android.content.Context ctx = ColgramPythonEngine.appContext();
            if (ctx == null) return null;
            File baseRoot = ColgramStorageSandbox.getSandboxRootDir(ctx);
            if (baseRoot == null) {
                baseRoot = new File(ctx.getFilesDir(), "Colgram");
            }
            File dir = new File(baseRoot, "exports");
            ColgramStorageSandbox.ensureDirWithNoMedia(dir);
            compressPreviousExports(dir);

            long tsSec = System.currentTimeMillis() / 1000L;
            byte[] utf8 = html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // Write GZIP-compressed archive when export is large (>= 256 KB) or store both compact .html.gz
            if (utf8.length >= 256 * 1024) {
                File gzOut = new File(dir, "chat_" + dialogId + "_" + tsSec + ".html.gz");
                if (ColgramStorageOptimizer.writeGzipBytesAtomic(gzOut, utf8)) {
                    Log.i(TAG, "exported " + exported + " messages (GZIP) of dialog " + dialogId + " to " + gzOut);
                    return gzOut;
                }
            }
            File out = new File(dir, "chat_" + dialogId + "_" + tsSec + ".html");
            try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
                w.write(html.toString());
            }
            Log.i(TAG, "exported " + exported + " messages of dialog " + dialogId + " to " + out);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "export failed: " + t.getMessage());
            return null;
        }
    }

    /** Compresses older uncompressed .html exports in the directory into .html.gz to save 5-10x space. */
    public static int compressPreviousExports(File exportDir) {
        if (exportDir == null || !exportDir.isDirectory()) return 0;
        File[] files = exportDir.listFiles();
        if (files == null) return 0;
        int count = 0;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.endsWith(".html")) continue;
            // Compress any export >= 64 KB or older than 24h
            if (f.length() >= 64 * 1024L || (now - f.lastModified()) >= 24L * 3600_000L) {
                byte[] raw = ColgramStorageOptimizer.readGzipOrPlainBytes(f);
                if (raw != null && raw.length > 0) {
                    File gz = new File(exportDir, name + ".gz");
                    if (ColgramStorageOptimizer.writeGzipBytesAtomic(gz, raw)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
