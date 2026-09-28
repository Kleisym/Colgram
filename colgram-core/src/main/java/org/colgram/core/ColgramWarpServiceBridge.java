package org.colgram.core;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hands a WARP profile to the sing-box service, which is the only engine in the process.
 *
 * This exists because of a measured crash. WARP used to be driven by the embedded WireGuard
 * Android backend, whose libwg-go.so is a complete cgo Go runtime of its own, while the
 * subscription already ran on sing-box's libbox.so - another one. Two Go runtimes in a single
 * Android process do not coexist: on the device, loading the WireGuard backend and then calling
 * into libbox killed the process with signal 11 inside about a second, with no Java exception, no
 * tombstone and no stack to point at it. Either runtime alone was fine, which is exactly why it
 * only ever appeared in the full device suite - the single run that loads both.
 *
 * sing-box speaks WireGuard itself, so WARP is now just another profile for the engine that is
 * already running, and the second runtime is gone rather than merely unloaded.
 */
public final class ColgramWarpServiceBridge {

    private static final String TAG = "ColgramWarpBridge";
    /** Private to the app, like every other profile, so a subscription never lands in shared storage. */
    private static final String PROFILE_NAME = "colgram-warp.json";

    private ColgramWarpServiceBridge() {}

    /** Where the WARP profile is written, so the service reads the same file. */
    public static String profilePath(Context context) {
        return new File(context.getFilesDir(), PROFILE_NAME).getAbsolutePath();
    }

    /**
     * Write the profile and ask the service to start it.
     *
     * The service performs the Android VPN consent, so this is reached only after the user has
     * already granted it system-wide. A refused consent surfaces as the service's own error
     * rather than as a profile that silently routes nothing.
     */
    public static void start(Context context, String profile) throws Exception {
        write(context, profile);
        // Started reflectively, like the rest of Colgram's use of the module: colgram-core
        // compiles before TMessagesProj against a bare android.jar and has never been allowed a
        // compile-time dependency on the sing-box module. Reflection also means a missing module
        // degrades into a logged reason instead of a broken build.
        startService(context, profilePath(context));
        Log.i(TAG, "WARP profile handed to the sing-box service");
    }

    /**
     * Replace the running profile, for endpoint rotation.
     *
     * The port travels inside the profile now, so rotation is a restart on a rebuilt profile
     * rather than a mutation of a live tunnel.
     */
    public static void restart(Context context, String profile) {
        try {
            stop(context);
            start(context, profile);
        } catch (Throwable t) {
            Log.w(TAG, "cannot restart on the next endpoint: " + t.getMessage());
        }
    }

    private static void write(Context context, String profile) throws Exception {
        File file = new File(context.getFilesDir(), PROFILE_NAME);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(profile.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            try {
                out.close();
            } catch (Exception ignored) {
                // Nothing useful to do about a close failure here.
            }
        }
    }

    /** Stop the tunnel the service is running. Safe to call when nothing is up. */
    public static void stop(Context context) {
        try {
            Intent intent = new Intent(context,
                    Class.forName("org.colgram.singbox.ColgramVpnService"));
            intent.setAction("org.colgram.singbox.STOP");
            context.startService(intent);
        } catch (Throwable t) {
            Log.w(TAG, "cannot stop the tunnel: " + t.getMessage());
        }
    }

    private static void startService(Context context, String profilePath) throws Exception {
        Class<?> service = Class.forName("org.colgram.singbox.ColgramVpnService");
        service.getMethod("start", Context.class, String.class)
                .invoke(null, context, profilePath);
    }
}
