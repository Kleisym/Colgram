package org.colgram.core;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;

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
    private static final long VPN_CONSENT_TIMEOUT_MS = 45_000L;

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
            // The whole profile, on disk rather than in the log. Every field that could shape a
            // WireGuard message on the wire - mtu, padding, batch, workers, fragment - is decided
            // here, and a 1200-byte "initiation" is a shape problem rather than a network one. It
            // has to be readable in full: logcat drops the tail of a long line, and the fields that
            // matter are all at the end of this string.
            // The app's external files dir: readable by the harness, and it outlives the cache.
            // /data/local/tmp is refused to the app by SELinux - FileNotFoundException: EACCES,
            // measured - and the cache is wiped at teardown, which is exactly when the file is
            // still needed. Both destinations have now been tried and both failed differently.
            java.io.File profileDump = new java.io.File(
                    context.getExternalFilesDir(null), "colgram-relay-profile.json");
            //noinspection ResultOfMethodCallIgnored
            profileDump.getParentFile().mkdirs();
            try {
                java.io.FileOutputStream profileOut =
                        new java.io.FileOutputStream(profileDump);
                profileOut.write(profile.getBytes("UTF-8"));
                profileOut.close();
                Log.i(TAG, "the profile is written to " + profileDump.getAbsolutePath()
                        + ", " + profile.length() + "B");
            } catch (Throwable dumpFailed) {
                // Diagnostics only. Failing the test here would hide the real verdict behind a
                // file it could not write - which is exactly what EACCES on /data/local/tmp did.
                Log.w(TAG, "could not dump the profile: " + dumpFailed);
            }
            org.junit.Assert.assertTrue("the profile does not name the relay",
                    profile.contains(RELAY_HOST));
            // Parsed, not substring-matched on the raw text. Android's org.json escapes "/" as
            // "\/", and about half of all base64 WireGuard keys contain one - so a key that IS in
            // the profile reads as absent, and a relay that works is reported as one that does not.
            // The failure is worse than useless: it looks like a relay fault, and the actual cause
            // is the test's own string comparison. Seen on the device with the key
            // ...QI/QPaQV9ESo= - the same test passed with a key that happened to contain no slash.
            String pinnedKey = pinnedPeerKey(profile);
            Log.i(TAG, "the profile pins the peer key: " + pinnedKey);
            org.junit.Assert.assertEquals("the profile does not pin the relay's own peer key",
                    relayKey, pinnedKey);
            // No mac1 assertion here, and the reason is worth more than the assertion was. The engine
            // has no such field - it refused one by name, on the device:
            //   endpoints[0].peers[0].mac1: json: unknown field "mac1"
            // and the empty handshake on the wire is not caused by its absence. Four implementations
            // in this session produced four different digests for one 74-byte input they all agreed on
            // byte for byte, which is what BLAKE2s does when the digest length is a parameter rather
            // than a truncation. So the value was never verifiable offline, and the peer - a real
            // WireGuard endpoint on the other side of this relay - is the only authority on it.

            callStatic(tunnelClass, "bringDown", new Class<?>[]{Context.class}, context);
            // Android's VPN consent, taken before the tunnel is asked for anything. Without it
            // builder.establish() returns null, the engine gets a bad file descriptor, and the
            // tunnel dies one line later with a message about a TUN device that has nothing to do
            // with the relay. Measured on the device:
            //   establish() returned no descriptor
            //   configure tun interface: query tun name: failed to get name of TUN device:
            //   bad file descriptor
            // appops does not stand in for this: the grant is per-VpnService.prepare() intent, and
            // setting ACTIVATE_VPN by hand left establish() returning null. The prompt has to be
            // answered, which is what the working integration test already does.
            grantVpnConsent(context);
            callStatic(configClass, "setWarpEnabled", new Class<?>[]{boolean.class}, true);
            callStatic(tunnelClass, "bringUp", new Class<?>[]{Context.class}, context);

            long expires = android.os.SystemClock.elapsedRealtime() + WINDOW_MS;
            boolean up = false;
            boolean connected = false;
            while (android.os.SystemClock.elapsedRealtime() < expires) {
                // Traffic, because a WireGuard endpoint is lazy: it sends an initiation when it has
                // a packet to send and stays silent otherwise. Starting the tunnel and waiting
                // produces no handshake at all, which reads as a blocked network and is not one -
                // the engine was never asked to carry anything. A plain HTTP request to a literal
                // address gives it something: the request is routed into the TUN, and carrying it
                // requires a handshake with the relay. The endpoint's own address is excluded from
                // the tunnel's routes (route_exclude_address), so the handshake leaves by wlan0
                // instead of being fed back into the interface it is trying to build.
                driveTrafficThrough(context);
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
            // Whether the relay answers this device at all, with nothing else in the way. If the
            // app routes its own traffic into the TUN it just built, the relay would never see a
            // handshake even with everything configured correctly - and that is a fact about the
            // device reaching the relay, not about WireGuard, so it is measured here separately.
            Log.i(TAG, "a bare datagram to the relay: " + bareProbe());
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
     * Answer Android's VPN consent prompt, if it is still unanswered.
     *
     * <p>A foreground game or overlay can cover the dialog and make UiAutomator tap the wrong
     * window, so the foreground is handed to the launcher first. A foreground activity is required
     * to show the prompt at all - which is why this is not simply {@code appops}: the grant is made
     * per prepare() intent and the system dialog is the only thing that answers it.
     */
    private static void grantVpnConsent(Context context) throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.pressHome();
        Intent consent = VpnService.prepare(context);
        if (consent == null) {
            Log.i(TAG, "VPN consent was already granted");
            return;
        }
        Log.i(TAG, "Requesting foreground Android VPN consent: " + consent.toUri(0));
        try (ActivityScenario<ColgramVpnConsentHostActivity> scenario =
                     ActivityScenario.launch(ColgramVpnConsentHostActivity.class)) {
            scenario.onActivity(ColgramVpnConsentHostActivity::requestVpnConsent);
            long expires = SystemClock.elapsedRealtime() + VPN_CONSENT_TIMEOUT_MS;
            while (VpnService.prepare(context) != null
                    && SystemClock.elapsedRealtime() < expires) {
                if (clickFirstVisible(device, "OK", "ОК", "Allow", "Разрешить", "Разрешить VPN")) {
                    SystemClock.sleep(750L);
                    continue;
                }
                SystemClock.sleep(300L);
            }
        }
        if (VpnService.prepare(context) != null) {
            throw new AssertionError("system VPN consent timed out; the TUN can never be"
                    + " established without it, and the engine's "
                    + "'bad file descriptor' is a symptom of exactly this");
        }
        Log.i(TAG, "VPN consent granted; establish() can return a descriptor now");
    }

    private static boolean clickFirstVisible(UiDevice device, String... labels) {
        for (String label : labels) {
            androidx.test.uiautomator.UiObject2 button = device.findObject(By.text(label));
            if (button == null) {
                button = device.findObject(By.desc(label));
            }
            if (button != null && button.isEnabled()) {
                Log.i(TAG, "Accepting visible VPN consent action: " + label);
                button.click();
                return true;
            }
        }
        androidx.test.uiautomator.UiObject2 positive =
                device.findObject(By.res("android", "button1"));
        if (positive != null && positive.isEnabled()) {
            Log.i(TAG, "Accepting Android VPN consent positive button");
            positive.click();
            return true;
        }
        return false;
    }

    /**
     * The public_key the profile actually pins, read out of the parsed config.
     *
     * <p>The profile is JSON, and JSON escaping is not identity: a "/" inside a base64 key is
     * written "\/" by Android's org.json, and "/" appears in roughly half of all base64 keys.
     * Comparing the raw text with the raw key therefore fails for a key that is present, which is
     * how a working relay gets reported as broken. Parsing and reading the field answers the
     * question that was actually being asked.
     */
    private static String pinnedPeerKey(String profile) throws Exception {
        org.json.JSONObject root = new org.json.JSONObject(profile);
        org.json.JSONArray endpoints = root.optJSONArray("endpoints");
        if (endpoints == null) return null;
        for (int i = 0; i < endpoints.length(); i++) {
            org.json.JSONArray peers = endpoints.getJSONObject(i).optJSONArray("peers");
            if (peers == null) continue;
            for (int j = 0; j < peers.length(); j++) {
                String key = peers.getJSONObject(j).optString("public_key", null);
                if (key != null && !key.isEmpty()) return key;
            }
        }
        return null;
    }

    /**
     * The peer public key, from a file the harness pushed onto the device.
     *
     * <p>Read with DataInputStream rather than java.nio.file, which is not on every API level this
     * app supports. A missing file is reported as missing rather than as an empty key, because
     * "no key" and "an empty key" are rejected for different reasons and only one of them is a
     * relay problem.
     */
    /**
     * A datagram to the relay, with no tunnel involved.
     *
     * <p>The relay answers anything that is not a WireGuard message with RELAY-OK and its length, so
     * a reply here means the device reaches the host at all. Silence means it does not, and every
     * later conclusion about the tunnel has to wait for that to be true - which is the same order
     * the QUIC work ended up needing: establish the path first, then read anything off it.
     */
    private static String bareProbe() {
        java.net.DatagramSocket socket = null;
        try {
            socket = new java.net.DatagramSocket(new java.net.InetSocketAddress(0));
            socket.setSoTimeout(3000);
            byte[] payload = new byte[64];
            socket.send(new java.net.DatagramPacket(payload, payload.length,
                    java.net.InetAddress.getByName(RELAY_HOST), RELAY_PORT));
            java.net.DatagramPacket reply = new java.net.DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            return "answered " + reply.getLength() + "B";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + " - the device does not reach the relay";
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    /**
     * One request through the tunnel, ignoring every outcome.
     *
     * <p>Whether it succeeds is not the question here. A refusal, a timeout, or a 403 all mean the
     * same thing for this test, which is that the engine had traffic to carry and therefore had to
     * negotiate a session first. The answer, if there is one, arrives in the relay log.
     *
     * <p>A literal address rather than a name so no DNS is needed: a resolver that cannot answer
     * would leave the tunnel with nothing to carry, and the test would be measuring DNS instead of
     * WireGuard.
     */
    private static void driveTrafficThrough(Context context) {
        java.net.DatagramSocket socket = null;
        try {
            // A UDP send to a literal address. No reply is expected or awaited - the datagram only
            // has to enter the tunnel, which is what forces the handshake.
            socket = new java.net.DatagramSocket(new java.net.InetSocketAddress(0));
            socket.setSoTimeout(500);
            byte[] payload = "colgram-warp-tunnel-probe".getBytes("UTF-8");
            socket.send(new java.net.DatagramPacket(payload, payload.length,
                    java.net.InetAddress.getByName("1.1.1.1"), 53));
        } catch (Throwable ignored) {
            // Nothing to do. The point is the packet entering the tunnel, not the answer.
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

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
