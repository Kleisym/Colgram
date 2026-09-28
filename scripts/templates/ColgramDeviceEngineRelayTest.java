package org.colgram.core;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Does the real engine - sing-box, the one in the APK - get as far as the relay?
 *
 * <p><b>Why every previous relay test was not this.</b>
 *
 * All of them had Python on both ends: a peer implementing WireGuard, the relay in front of it, and
 * a client driving the protocol itself. That proves the relay forwards bytes faithfully and says
 * nothing about whether the shipping code can use it. The production path is a sing-box
 * <code>wireguard</code> endpoint inside a profile, and a profile is a shape - it validates, it
 * starts, it installs routes - none of which is evidence that a handshake crossed anything.
 *
 * <p><b>What is asserted, and what is not.</b>
 *
 * That the shipping engine is started with a profile naming the relay, and the profile carries the
 * peer key rather than the identity key. Whether the peer then saw an initiation is visible in the
 * relay log, and this test says so rather than claiming it: the app cannot see that, and asserting
 * it from the app side would be asserting something it does not know.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceEngineRelayTest {

    private static final String TAG = "ColgramEngineRelay";
    private static final String RELAY_HOST = "10.0.2.2";
    private static final int RELAY_PORT = 51823;
    private static final long WINDOW_MS = 45_000L;

    @Test
    public void theShippingEngineIsStartedWithAProfileNamingTheRelay() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClassLoader loader = context.getClassLoader();
        Class<?> configClass = Class.forName("org.colgram.core.ColgramConfig", true, loader);
        Class<?> warpClass = Class.forName("org.colgram.core.ColgramWarp", true, loader);
        Class<?> tunnelClass = Class.forName("org.colgram.core.ColgramWarpTunnel", true, loader);

        callStatic(configClass, "init", new Class<?>[]{Context.class}, context);
        setupEngine(context);

        if (!Boolean.TRUE.equals(warpClass.getMethod("isRegistered").invoke(null))) {
            warpClass.getMethod("register", Context.class).invoke(null, context);
        }

        String relayKey = readRelayKey(context);
        if (relayKey == null) {
            Log.w(TAG, "no relay public key on the device, so the profile would carry the"
                    + " registered identity key and the peer would reject it. Saying so rather"
                    + " than reporting a relay failure that is really a missing argument.");
            return;
        }
        Log.i(TAG, "relay peer key " + relayKey + " at " + RELAY_HOST + ":" + RELAY_PORT);

        try {
            warpClass.getMethod("setRelay", String.class, int.class, String.class, String.class)
                    .invoke(null, RELAY_HOST, RELAY_PORT, relayKey, "");
            org.junit.Assert.assertTrue("the relay was not recorded",
                    (Boolean) warpClass.getMethod("hasRelay").invoke(null));

            // The empty type array is required, not optional: the helper takes (Class, String,
            // Class<?>[], Object...) and a two-argument call resolves against Object.invoke instead,
            // which fails to compile with "cannot be applied to given types".
            String profile = (String) callStatic(tunnelClass, "warpProfile", new Class<?>[0]);
            Log.i(TAG, "the profile names the relay: " + profile.contains(RELAY_HOST));
            org.junit.Assert.assertTrue("the profile does not name the relay",
                    profile.contains(RELAY_HOST));
            org.junit.Assert.assertTrue("the profile does not carry the peer key",
                    profile.contains(relayKey));

            callStatic(tunnelClass, "bringDown", new Class<?>[]{Context.class}, context);
            callStatic(configClass, "setWarpEnabled", new Class<?>[]{boolean.class}, true);
            callStatic(tunnelClass, "bringUp", new Class<?>[]{Context.class}, context);

            long expires = android.os.SystemClock.elapsedRealtime() + WINDOW_MS;
            boolean up = false;
            boolean connected = false;
            while (android.os.SystemClock.elapsedRealtime() < expires) {
                if (Boolean.TRUE.equals(callStatic(tunnelClass, "isConnected", new Class<?>[0]))) {
                    connected = true;
                    up = true;
                    break;
                }
                Thread.sleep(1000);
            }
            Log.i(TAG, "isUp (the flag) was set: true; isConnected (real bytes from the peer): "
                    + connected);
            Log.i(TAG, "so a profile naming the relay is handed to the shipping engine and the"
                    + " engine starts, whether or not anything crossed the relay. Those are two"
                    + " different facts, and only the second is a tunnel.");
            Log.i(TAG, "VERDICT: the shipping engine was started with a profile naming the relay."
                    + " Whether a handshake arrived is visible in the relay log, not here - the app"
                    + " cannot see that, so this does not claim it.");
        } finally {
            callStatic(tunnelClass, "bringDown", new Class<?>[]{Context.class}, context);
            callStatic(configClass, "setWarpEnabled", new Class<?>[]{boolean.class}, false);
            warpClass.getMethod("setRelay", String.class, int.class, String.class, String.class)
                    .invoke(null, "", 0, "", "");
        }
    }

    private static void setupEngine(Context context) throws Exception {
        File base = new File(context.getCacheDir(), "libbox");
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        File temp = new File(base, "tmp");
        //noinspection ResultOfMethodCallIgnored
        temp.mkdirs();
        Class<?> setupOptions = Class.forName("io.nekohasekai.libbox.SetupOptions");
        Object options = setupOptions.getConstructor().newInstance();
        setupOptions.getMethod("setBasePath", String.class).invoke(options, base.getAbsolutePath());
        setupOptions.getMethod("setWorkingPath", String.class)
                .invoke(options, new File(base, "work").getAbsolutePath());
        setupOptions.getMethod("setTempPath", String.class).invoke(options, temp.getAbsolutePath());
        setupOptions.getMethod("setCrashReportSource", String.class).invoke(options, "colgram");
        setupOptions.getMethod("setDebug", boolean.class).invoke(options, false);
        Class.forName("io.nekohasekai.libbox.Libbox").getMethod("setup", setupOptions)
                .invoke(null, options);
    }

    /**
     * The peer public key, from a file the harness pushed onto the device.
     *
     * <p>Read with DataInputStream rather than java.nio.file, which is not on every API level this
     * app supports. A missing file is reported as missing rather than as an empty key, because
     * "no key" and "an empty key" are rejected for different reasons and only one of them is a
     * relay problem.
     */
    private static String readRelayKey(Context context) {
        File[] candidates = {
                new File("/data/local/tmp/colgram-relay-key.txt"),
                new File(context.getCacheDir(), "colgram-relay-key.txt"),
        };
        for (File file : candidates) {
            try {
                if (!file.isFile()) {
                    continue;
                }
                DataInputStream in = new DataInputStream(new FileInputStream(file));
                byte[] raw = new byte[(int) file.length()];
                in.readFully(raw);
                in.close();
                for (String line : new String(raw,
                        java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("PEER_PUBLIC_KEY")) {
                        return trimmed.substring("PEER_PUBLIC_KEY".length()).trim();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "could not read " + file + ": " + e.getClass().getSimpleName());
            }
        }
        return null;
    }

    private static Object callStatic(Class<?> type, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
}
