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

        assertTrue("embedded GoBackend/Config classes are missing",
                (Boolean) invoke(tunnelClass, "isBackendAvailable", new Class<?>[]{}));
        invoke(warpClass, "register", new Class<?>[]{Context.class}, context);
        assertTrue("Cloudflare registration was not persisted",
                (Boolean) invoke(warpClass, "isRegistered", new Class<?>[]{}));
        String reserved = (String) invoke(warpClass, "reservedHex", new Class<?>[]{});
        assertNotNull("registration did not include Cloudflare's 3-byte client ID", reserved);

        String host = (String) invoke(warpClass, "endpointHost", new Class<?>[]{});
        int port = (Integer) invoke(warpClass, "currentEndpointPort", new Class<?>[]{});
        String profileText = (String) invoke(warpClass, "buildWgQuickConf",
                new Class<?>[]{String.class, int.class}, host, port);
        assertNotNull("registration did not produce a WireGuard profile", profileText);
        Class<?> profileClass = load(loader, "com.wireguard.config.Config");
        Object profile = profileClass.getMethod("parse", BufferedReader.class)
                .invoke(null, new BufferedReader(new StringReader(profileText)));
        assertTrue("parsed WARP profile has no peers",
                !((java.util.Collection<?>) profileClass.getMethod("getPeers").invoke(profile)).isEmpty());
        Class<?> backendClass = load(loader, "com.wireguard.android.backend.GoBackend");
        Object backend = backendClass.getConstructor(Context.class).newInstance(context);
        backendClass.getMethod("setClientReserved", String.class).invoke(backend, reserved);

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
            assertTrue("no Cloudflare WARP response before timeout; last=" + lastFailure
                            + "; trace=" + trace,
                    trace.contains("warp=on") || trace.contains("warp=plus"));
            assertTrue("WireGuard receive counter never confirmed tunnel traffic",
                    (Boolean) invoke(tunnelClass, "isConnected", new Class<?>[]{}));
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
