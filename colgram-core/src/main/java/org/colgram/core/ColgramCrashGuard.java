package org.colgram.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ColgramCrashGuard — makes a crash say what it was.
 *
 * The problem this exists to solve is measured, not hypothetical. On the device, every tombstone
 * Colgram ever produced is zero bytes:
 *
 *     /data/tombstones/app/org.colgram.messenger/10075.log   0 bytes
 *     /data/tombstones/app/org.colgram.messenger/10081.log   0 bytes
 *
 * The process died, the report says nothing, and the only trace in ColgramWarpServiceBridge is the
 * phrase "the crash report is 0 bytes". A native fault in this app - which is a normal outcome when
 * two runtimes or a filter-driver library are in one process - writes its backtrace into that file
 * and loses it.
 *
 * So the report is written here instead, into the app's own storage, where it survives the process
 * that produced it and can be read back over adb without root:
 *
 *     adb shell run-as org.colgram.messenger cat files/colgram_crash.log
 *
 * Only Java crashes are recorded here. A SIGSEGV from a native library kills the process with no
 * Throwable anywhere, and catching that needs a signal handler installed from native code - see
 * NativeCrashSignal for why this app cannot do that today. So this file turns the Java crashes from
 * invisible into readable, and leaves the native ones exactly as invisible as they were.
 *
 * The handler chains to whatever was installed before it. An app that had a handler - a crash
 * reporter, a custom logger - keeps it, because this is here to add a record, not to replace one.
 */
public final class ColgramCrashGuard {

    private static final String TAG = "ColgramCrashGuard";
    private static final String FILE_NAME = "colgram_crash.log";
    /** Enough history to see a pattern, small enough to append to without thinking about it. */
    private static final int MAX_BYTES = 256 * 1024;

    private static volatile boolean installed;
    private static Thread.UncaughtExceptionHandler previousHandler;

    private ColgramCrashGuard() {}

    /** Installs the handlers. Safe to call more than once; only the first call does anything. */
    public static synchronized void install(Context context) {
        if (installed || context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        final File file = new File(app.getFilesDir(), FILE_NAME);

        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(file, header("java") + describe(thread, throwable));
            } catch (Throwable ignored) {
                // A handler that throws while handling a crash replaces the crash with a different
                // one, which is worse than losing the report.
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                Log.e(TAG, "uncaught on " + thread.getName(), throwable);
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });

        try {
            NativeCrashSignal.install(file);
        } catch (Throwable t) {
            // The Java handler is the one that always works; the native one is a bonus.
            Log.w(TAG, "native crash handler unavailable: " + t);
        }

        installed = true;
        Log.i(TAG, "crash reporting installed -> " + file.getAbsolutePath());
    }

    private static String header(String kind) {
        return "\n===== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()) + "  " + kind + " =====\n";
    }

    private static String describe(Thread thread, Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter out = new PrintWriter(writer);
        out.println("thread: " + thread.getName() + "  id=" + thread.getId());
        if (throwable != null) {
            throwable.printStackTrace(out);
        }
        out.flush();
        return writer.toString();
    }

    private static synchronized void write(File file, String body) {
        FileOutputStream out = null;
        try {
            // Truncated first so a full disk cannot turn a crash report into a second crash.
            if (file.length() > MAX_BYTES) {
                File keep = new File(file.getParentFile(), file.getName() + ".prev");
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(keep);
                //noinspection ResultOfMethodCallIgnored
                keep.delete();
            }
            out = new FileOutputStream(file, true);
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Where the report is, so a reader does not have to guess the path. */
    public static String reportPath(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        return new File(app.getFilesDir(), FILE_NAME).getAbsolutePath();
    }

    /** Appends a line by hand, for the failures that are not crashes at all. */
    public static void note(String message) {
        Context ctx = ColgramPythonEngine.appContext();
        if (ctx == null) {
            return;
        }
        write(new File(ctx.getFilesDir(), FILE_NAME),
                header("note") + (message == null ? "" : message) + "\n");
    }
}
