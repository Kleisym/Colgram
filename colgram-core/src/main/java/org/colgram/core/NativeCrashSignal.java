package org.colgram.core;

import android.util.Log;

import java.io.File;

/**
 * NativeCrashSignal — the Java half of the native crash handler.
 *
 * A SIGSEGV kills the process without throwing anything, so no Java handler ever runs. This is why
 * Colgram's tombstones are zero bytes and why the wireguard-go crash has stayed "no stack to point
 * at it" for the length of this project.
 *
 * The handler itself is native, in TMessagesProj/jni/colgram_crash_signal.cpp - it has to be, since
 * sigaction() is a syscall and calling it through JNI for every signal is not a thing anyone should
 * do. This class only hands it the path to write to.
 *
 * Note what is NOT being used: org.telegram.messenger.Signal, the class an app like this normally
 * reaches for. It is not in this tree, so there was nothing to call, which is why the handler was
 * written instead of wired up.
 */
final class NativeCrashSignal {

    private NativeCrashSignal() {}

    static void install(File reportFile) {
        try {
            System.loadLibrary("colgramcrash");
        } catch (Throwable t) {
            // Reporting is a diagnostic, never a reason to fail the app's start.
            Log.w("ColgramCrashGuard", "native crash handler not loadable: " + t);
            return;
        }
        try {
            nativeInstall(reportFile.getAbsolutePath());
            Log.i("ColgramCrashGuard", "native signal handler installed -> " + reportFile.getName());
        } catch (Throwable t) {
            Log.w("ColgramCrashGuard", "native signal handler unavailable: " + t);
        }
    }

    /**
     * Installs the handlers, in colgram_crash_signal.cpp.
     *
     * Reached by name and not by a bind because this module compiles before TMessagesProj against a
     * bare android.jar, so the symbol cannot be a compile-time dependency.
     */
    private static native void nativeInstall(String path);
}
