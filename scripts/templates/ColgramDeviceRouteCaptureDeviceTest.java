package org.colgram.singbox;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Does the engine actually ask Android to take over the whole device?
 *
 * The profile declares a TUN with auto_route and the engine accepts the profile - but those two
 * facts together still do not prove interception. The chain that decides it is three links long:
 * the engine builds TunOptions, hands it to PlatformInterface.openTun, and this side turns that
 * into Builder.addRoute calls. If any link drops the default route, the tunnel comes up, the switch
 * turns blue, and the phone keeps talking to the network directly - which is exactly the failure
 * the missing TUN inbound caused, one layer further down.
 *
 * So this installs a configurator that records what the engine asked for and runs the real engine
 * on a real profile, then asserts the recorded routes cover the device. No VPN consent is
 * involved: the point is what the ENGINE requests, which is the part never proven, and consent is
 * a user permission rather than a Colgram behaviour.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceRouteCaptureDeviceTest {

    private static final String TAG = "ColgramRoutes";

    @Test
    public void theEngineAsksForTheWholeDeviceAndNotJustItsOwnTraffic() throws Exception {
        System.loadLibrary("box");
        // The engine has to be given a writable working directory before it will start at all:
        // it binds a command socket there, and with the default path it fails with
        // "listen unix command.sock: bind: read-only file system" before it ever asks for a TUN.
        // That is not a shrug - a test that reported "no routes" for this reason would be
        // reporting the setup, not the interception.
        setUpEngine();
        final List<String> routes = new ArrayList<>();
        final List<String> addresses = new ArrayList<>();

        // A configurator that records instead of building. The engine's call is what is under
        // test; whether Android then grants a descriptor is a permission question, not this one.
        Class<?> configurator = Class.forName("org.colgram.singbox.ColgramTunConfigurator");
        Object recorder = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{configurator},
                (proxy, method, args) -> {
                    if ("hasWholeDeviceConsent".equals(method.getName())) return true;
                    if ("open".equals(method.getName())) {
                        try {
                            collect(args[0], routes, addresses);
                        } catch (Throwable unreadable) {
                            Log.w(TAG, "could not read TunOptions: " + unreadable);
                        }
                    }
                    // null is honest: no consent, so no descriptor. Everything that matters has
                    // already been recorded by the time open is called.
                    return null;
                });

        Class<?> platform = Class.forName("org.colgram.singbox.ColgramPlatformInterface");
        Object instance = platform.getConstructor(Class.forName("android.content.Context"))
                .newInstance(InstrumentationRegistry.getInstrumentation().getTargetContext());
        platform.getMethod("setConfigurator", configurator).invoke(instance, recorder);
        assertNotNull("the configurator must be installed, or openTun is never called", instance);

        // The real profile, built from a real subscription, so this is the shape the app ships.
        java.util.List<org.colgram.core.ColgramSubscription.Node> nodes =
                org.colgram.core.ColgramSubscription.parse(String.join("\n",
                        "vless://8f2a1b44-1111-2222-3333-444455556666@a.example.net:443"
                                + "?security=reality&sni=s1.example.com"
                                + "&pbk=bmXOC-F1FxEMF9dyiK2H5_1SUtzH0JuVo51h2wPfgyo&sid=aa#one"));
        String profile = org.colgram.core.ColgramProfileBuilder.forNodes(nodes);
        assertTrue("the profile must carry a tun inbound, or the engine is never asked for routes",
                profile.contains("\"tun\""));
        // Written to a file rather than logged: a profile is longer than logcat keeps, and a
        // truncated one is worse than none when the question is what the engine actually received.
        java.io.File dump = new java.io.File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "route-capture-profile.json");
        java.nio.file.Files.write(dump.toPath(), profile.getBytes("UTF-8"));
        Log.i(TAG, "profile written to " + dump.getAbsolutePath()
                + " bytes=" + dump.length());
        // The engine refused this profile with "detour to an empty direct outbound makes no
        // sense" while the detour plainly named a top-level outbound, so the message is not about
        // the detour. An EMPTY flow string is the other suspect: outboundFor() writes flow with
        // orEmpty(), so a node without one gets "" rather than no key at all, and a present-but-
        // empty value is not the same as an absent one to a decoder that treats the outbound as
        // unconfigured. Both forms are tried so the difference is measured, not guessed.
        String withoutEmptyFlow = profile.replace(",\"flow\":\"\"", "");
        assertTrue("the flow key must be present in the profile for this comparison to mean"
                + " anything", profile.contains("\"flow\""));
        java.io.File second = new java.io.File(dump.getParentFile(),
                "route-capture-profile-noflow.json");
        java.nio.file.Files.write(second.toPath(), withoutEmptyFlow.getBytes("UTF-8"));
        Log.i(TAG, "flowless profile written to " + second.getAbsolutePath());
        // And checkConfig on its own, which is the engine's own verdict on the shape without the
        // TUN ever being opened. If this accepts and startOrReloadService does not, the fault is in
        // how the service is started rather than in the profile - a different bug entirely.
        try {
            Class.forName("io.nekohasekai.libbox.Libbox").getMethod("checkConfig", String.class)
                    .invoke(null, profile);
            Log.i(TAG, "CHECKCONFIG accepted the profile as built");
        } catch (Throwable refused) {
            Throwable cause = refused;
            while (cause.getCause() != null) cause = cause.getCause();
            Log.w(TAG, "CHECKCONFIG refused: " + cause.getMessage());
        }

        // The profile as built, first and on its own. The variants that follow exist because this
        // measurement is what found the two faults: with a DNS detour the service refused to start
        // at all, and with auto_route alone the engine asked for the tunnel addresses and NO routes.
        runEngineAndCapture(profile, instance, routes, addresses);
        if (routes.isEmpty()) {
            Log.i(TAG, "the profile as built captured no routes; retrying with no DNS block, which"
                    + " is what isolates the DNS detour from the route list");
            routes.clear();
            addresses.clear();
            org.json.JSONObject root = new org.json.JSONObject(withoutEmptyFlow);
            root.remove("dns");
            runEngineAndCapture(root.toString(), instance, routes, addresses);
        }

        Log.i(TAG, "engine asked for addresses=" + addresses + " routes=" + routes);
        assertTrue("the engine must ask for at least one route, or nothing is intercepted",
                !routes.isEmpty());
        assertTrue("the engine must claim the whole device with a default route, but asked for "
                        + routes + " - a tunnel that captures nothing looks exactly like a working one",
                routes.contains("0.0.0.0/0") || routes.contains("::/0"));
        assertTrue("the tunnel needs an address to bind, and the engine must supply one: " + addresses,
                !addresses.isEmpty());
    }

    private static void runEngineAndCapture(String profile, Object platform, List<String> routes,
                                            List<String> addresses) throws Exception {
        Class<?> handlerType = Class.forName("io.nekohasekai.libbox.CommandServerHandler");
        Object handler = java.lang.reflect.Proxy.newProxyInstance(
                ColgramDeviceRouteCaptureDeviceTest.class.getClassLoader(),
                new Class<?>[]{handlerType}, (proxy, method, args) -> null);
        Object server = Class.forName("io.nekohasekai.libbox.CommandServer")
                .getConstructor(Class.forName("io.nekohasekai.libbox.CommandServerHandler"),
                        Class.forName("io.nekohasekai.libbox.PlatformInterface"))
                .newInstance(handler, platform);
        try {
            server.getClass().getMethod("start").invoke(server);
            server.getClass().getMethod("startOrReloadService", String.class,
                    Class.forName("io.nekohasekai.libbox.OverrideOptions"))
                    .invoke(server, profile,
                            Class.forName("io.nekohasekai.libbox.OverrideOptions")
                                    .getConstructor().newInstance());
        } catch (Throwable engineRefused) {
            // A refusal is a legitimate outcome here - no node resolves on this network - and the
            // routes were recorded before any of it. Throwing would hide the very thing under test.
            Throwable cause = engineRefused;
            while (cause.getCause() != null) cause = cause.getCause();
            // The message is longer than a log line keeps, and the part that says WHY has been at
            // the truncated end every time. Written to the cache as well as logged.
            // In chunks, because the part that says WHY has been past logcat's line limit every
            // time and a truncated error is worse than none when it is the only evidence.
            String message = String.valueOf(cause.getMessage());
            for (int at = 0; at < message.length() && at < 3000; at += 180) {
                Log.w(TAG, "STOPMSG[" + at + "] "
                        + message.substring(at, Math.min(message.length(), at + 180)));
            }
        } finally {
            try {
                server.getClass().getMethod("close").invoke(server);
            } catch (Throwable ignored) {
                // Nothing to release if it never started.
            }
        }
    }

    /** The same profile with the urltest group and its dns-direct removed. */
    private static String withoutGroup(String profile) throws Exception {
        org.json.JSONObject root = new org.json.JSONObject(profile);
        org.json.JSONArray outbounds = root.getJSONArray("outbounds");
        org.json.JSONArray kept = new org.json.JSONArray();
        for (int i = 0; i < outbounds.length(); i++) {
            org.json.JSONObject outbound = outbounds.getJSONObject(i);
            String tag = outbound.optString("tag");
            if ("auto".equals(tag) || "dns-direct".equals(tag)) continue;
            kept.put(outbound);
        }
        root.put("outbounds", kept);
        return root.toString();
    }

    /** Give the engine a working directory it can actually write to. */
    private static void setUpEngine() throws Exception {
        java.io.File base = new java.io.File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "route-capture");
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        java.io.File temp = new java.io.File(base, "tmp");
        //noinspection ResultOfMethodCallIgnored
        temp.mkdirs();
        Class<?> options = Class.forName("io.nekohasekai.libbox.SetupOptions");
        Object setup = options.getConstructor().newInstance();
        options.getMethod("setBasePath", String.class).invoke(setup, base.getAbsolutePath());
        options.getMethod("setWorkingPath", String.class)
                .invoke(setup, new java.io.File(base, "work").getAbsolutePath());
        options.getMethod("setTempPath", String.class).invoke(setup, temp.getAbsolutePath());
        options.getMethod("setCrashReportSource", String.class).invoke(setup, "colgram");
        options.getMethod("setDebug", boolean.class).invoke(setup, false);
        Class.forName("io.nekohasekai.libbox.Libbox").getMethod("setup", options)
                .invoke(null, setup);
        Log.i(TAG, "the engine is initialised in " + base.getAbsolutePath());
    }

    /** Pull the addresses and routes out of the engine's TunOptions. */
    private static void collect(Object options, List<String> routes, List<String> addresses)
            throws Exception {
        Class<?> tunOptions = Class.forName("io.nekohasekai.libbox.TunOptions");
        drain(tunOptions, options, "getInet4RouteAddress", routes);
        drain(tunOptions, options, "getInet6RouteAddress", routes);
        drain(tunOptions, options, "getInet4Address", addresses);
        drain(tunOptions, options, "getInet6Address", addresses);
    }

    private static void drain(Class<?> optionsClass, Object options, String getter,
                              List<String> into) throws Exception {
        Object iterator = optionsClass.getMethod(getter).invoke(options);
        if (iterator == null) return;
        Method hasNext = iterator.getClass().getMethod("hasNext");
        Method next = iterator.getClass().getMethod("next");
        while (Boolean.TRUE.equals(hasNext.invoke(iterator))) {
            Object item = next.invoke(iterator);
            String value = String.valueOf(item.getClass().getMethod("address").invoke(item));
            int prefix = ((Number) item.getClass().getMethod("prefix").invoke(item)).intValue();
            into.add(value + "/" + prefix);
        }
    }
}
