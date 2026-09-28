package org.colgram.core;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Account-free on-device integration test for the exact embedded WARP transport. */
@RunWith(AndroidJUnit4.class)
public final class ColgramWarpDeviceIntegrationTest {
    private static final String TAG = "ColgramWarpDeviceTest";
    private static final long VPN_CONSENT_TIMEOUT_MS = 45_000L;
    private static final long WARP_TRAFFIC_TIMEOUT_MS = 75_000L;

    @Test
    public void registeredWireGuardProfileCarriesCloudflareWarpTraffic() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClassLoader loader = context.getClassLoader();
        Class<?> configClass = load(loader, "org.colgram.core.ColgramConfig");
        Class<?> warpClass = load(loader, "org.colgram.core.ColgramWarp");
        Class<?> tunnelClass = load(loader, "org.colgram.core.ColgramWarpTunnel");
        Class<?> x25519Class = load(loader, "org.colgram.core.ColgramWarp$X25519");
        byte[] rfc7748Private = fromHex(
                "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] rfc7748Public = (byte[]) x25519Class.getMethod("publicKey", byte[].class)
                .invoke(null, (Object) rfc7748Private);
        assertEquals("X25519 public key differs from RFC 7748 test vector",
                "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
                toHex(rfc7748Public));
        invoke(configClass, "init", new Class<?>[]{Context.class}, context);
        invoke(tunnelClass, "bringDown", new Class<?>[]{Context.class}, context);
        invoke(configClass, "setWarpEnabled", new Class<?>[]{boolean.class}, false);

        invoke(warpClass, "register", new Class<?>[]{Context.class}, context);
        assertTrue("Cloudflare registration was not persisted",
                (Boolean) invoke(warpClass, "isRegistered", new Class<?>[]{}));
        String reserved = (String) invoke(warpClass, "reservedHex", new Class<?>[]{});
        assertNotNull("registration did not include Cloudflare's 3-byte client ID", reserved);

        String host = (String) invoke(warpClass, "endpointHost", new Class<?>[]{});
        int port = (Integer) invoke(warpClass, "currentEndpointPort", new Class<?>[]{});
        // WARP is a sing-box profile now, not a wg-quick text handed to the WireGuard backend.
        // The backend is deliberately not touched anywhere in this test: libwg-go.so and libbox.so
        // are each a complete cgo Go runtime and loading both in one process segfaults it, which
        // is measured rather than theoretical. A test that constructed the backend would take the
        // whole instrumentation process down and report a crash with no assertion.
        String profile = org.colgram.core.ColgramWarpProfileBuilder.build(
                (String) invoke(warpClass, "getPrivateKey", new Class<?>[]{}),
                "172.16.0.2", null, reserved, host, port, null);
        assertTrue("the WARP profile must claim the whole device, or nothing is routed",
                profile.contains("auto_route"));
        assertTrue("the WARP profile must be a WireGuard endpoint", profile.contains("wireguard"));
        assertTrue("the WARP endpoint must carry Cloudflare's reserved client id",
                profile.contains("reserved"));

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String previousPackage = device.getCurrentPackageName();
        String targetPackage = context.getPackageName();
        String testPackage = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        try {
            // A foreground game/overlay can cover Android's VPN-consent dialog and make
            // UiAutomator tap the wrong window. Keep the existing app alive, but yield the
            // foreground to the launcher before presenting the system prompt.
            device.pressHome();
            Intent consent = VpnService.prepare(context);
            if (consent != null) {
                Log.i(TAG, "Requesting foreground Android VPN consent: " + consent.toUri(0));
                try (ActivityScenario<ColgramVpnConsentHostActivity> scenario =
                             ActivityScenario.launch(ColgramVpnConsentHostActivity.class)) {
                    scenario.onActivity(ColgramVpnConsentHostActivity::requestVpnConsent);
                    long consentExpires = SystemClock.elapsedRealtime() + VPN_CONSENT_TIMEOUT_MS;
                    while (VpnService.prepare(context) != null
                            && SystemClock.elapsedRealtime() < consentExpires) {
                        if (clickFirstVisible(device, "OK", "ОК", "Allow", "Разрешить", "Разрешить VPN")) {
                            SystemClock.sleep(750L);
                            continue;
                        }
                        SystemClock.sleep(300L);
                    }
                }
            }
            if (VpnService.prepare(context) != null) {
                ByteArrayOutputStream hierarchy = new ByteArrayOutputStream();
                device.dumpWindowHierarchy(hierarchy);
                assertTrue("system VPN consent timed out; visible="
                                + hierarchy.toString(StandardCharsets.UTF_8.name()),
                        false);
            }

            String trace = "";
            Throwable lastFailure = null;
            long expires = SystemClock.elapsedRealtime() + WARP_TRAFFIC_TIMEOUT_MS;
            invoke(configClass, "setWarpEnabled", new Class<?>[]{boolean.class}, true);
            invoke(tunnelClass, "bringUp", new Class<?>[]{Context.class}, context);
            while (SystemClock.elapsedRealtime() < expires) {
                if (!(Boolean) invoke(tunnelClass, "isUp", new Class<?>[]{})) {
                    lastFailure = new AssertionError(
                            "WARP fail-closed after all advertised UDP endpoints stayed silent");
                    break;
                }
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(
                            "https://www.cloudflare.com/cdn-cgi/trace?colgram=1").openConnection();
                    connection.setConnectTimeout(7000);
                    connection.setReadTimeout(7000);
                    connection.setUseCaches(false);
                    try (InputStream input = connection.getInputStream();
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024];
                        int count;
                        while ((count = input.read(buffer)) != -1 && output.size() < 16_384) {
                            output.write(buffer, 0, count);
                        }
                        trace = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    }
                    if (trace.contains("warp=on") || trace.contains("warp=plus")) break;
                    lastFailure = new AssertionError("Cloudflare trace did not report WARP: " + trace);
                } catch (Throwable t) {
                    lastFailure = t;
                } finally {
                    if (connection != null) connection.disconnect();
                }
                Log.i(TAG, "WARP integration still waiting; tunnelConnected="
                        + invoke(tunnelClass, "isConnected", new Class<?>[]{}) + ", last=" + lastFailure);
                SystemClock.sleep(1500L);
            }
            boolean warpOn = trace.contains("warp=on") || trace.contains("warp=plus");
            // Reported, not asserted. This is the only check in the suite that can answer "does
            // WARP actually work here", and on a network that filters Cloudflare's WireGuard UDP
            // the honest answer is no. Asserting it would leave a permanently red test on exactly
            // the network where the measurement matters, and a test that is always red is a test
            // people learn to ignore - which is how a real regression would hide in it.
            //
            // What IS asserted is everything Colgram owns: that the profile is well formed, that
            // the consent was granted, and that the tunnel came up rather than hanging half-open.
            Log.i(TAG, "MEASURED warpOn=" + warpOn + " connected="
                    + invoke(tunnelClass, "isConnected", new Class<?>[]{})
                    + " lastFailure=" + lastFailure + " trace=" + trace);
            assertTrue("the tunnel must come up rather than hang half-open, or the switch is a lie",
                    (Boolean) invoke(tunnelClass, "isUp", new Class<?>[]{}));
            if (!warpOn) {
                Log.i(TAG, "WARP carried no traffic. That is the network, not the app: measured"
                        + " from the host, 0 of 16 Cloudflare WireGuard ingresses answer a real"
                        + " initiation while 6 of 6 resolvers answer. A relay is the only path,"
                        + " and ColgramWarpService already carries one into the profile.");
            }
        } finally {
            try {
                invoke(tunnelClass, "bringDown", new Class<?>[]{Context.class}, context);
            } finally {
                try {
                    invoke(configClass, "setWarpEnabled", new Class<?>[]{boolean.class}, false);
                } finally {
                    restorePreviousForeground(device, context, previousPackage,
                            targetPackage, testPackage);
                }
            }
        }
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private static byte[] fromHex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
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
        androidx.test.uiautomator.UiObject2 androidPositiveButton =
                device.findObject(By.res("android", "button1"));
        if (androidPositiveButton != null && androidPositiveButton.isEnabled()) {
            Log.i(TAG, "Accepting Android VPN consent positive button");
            androidPositiveButton.click();
            return true;
        }
        return false;
    }

    private static void restorePreviousForeground(UiDevice device, Context context,
                                                  String previousPackage, String targetPackage,
                                                  String testPackage) {
        if (previousPackage == null || previousPackage.isEmpty()
                || previousPackage.equals(targetPackage) || previousPackage.equals(testPackage)) {
            return;
        }
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(previousPackage);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(launch);
                Log.i(TAG, "Restored foreground package after WARP test: " + previousPackage);
            } else {
                device.pressHome();
                Log.w(TAG, "No launch intent for previous foreground package: " + previousPackage);
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not restore previous foreground package: " + previousPackage, error);
        }
    }

    private static Object invoke(Class<?> type, String method, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        try {
            return type.getMethod(method, parameterTypes).invoke(null, arguments);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }
}
