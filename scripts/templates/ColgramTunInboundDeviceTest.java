package org.colgram.singbox;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

/**
 * The profile has to declare a TUN, or the phone is never routed through it.
 *
 * The subscription profile carried only a local mixed inbound on 127.0.0.1. That is a SOCKS port
 * for something on the device to dial deliberately - it captures nothing by itself. sing-box hands
 * the operating system its addresses and routes through PlatformInterface.openTun, and it only
 * does that for a `tun` inbound: without one there is no openTun call, no descriptor, and no routes
 * installed. The result is the worst kind of failure - the switch says connected, the service is
 * running, and the phone talks to the network directly exactly as before.
 *
 * The engine is the authority on the field names, as it was for the WireGuard endpoint, so this
 * asks it rather than trusting a document that may describe a different version.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramTunInboundDeviceTest {

    private static final String TAG = "ColgramTun";

    @Test
    public void theEngineNamesTheTunInboundFieldsItAccepts() throws Exception {
        System.loadLibrary("box");
        Class<?> libbox = Class.forName("io.nekohasekai.libbox.Libbox");
        Object schema = libbox.getMethod("generateConfigSchema").invoke(null);
        String text = String.valueOf(schema);
        int at = text.indexOf("\"const\": \"tun\"");
        assertTrue("the engine published no schema, or none mentioning a tun inbound", at >= 0);
        StringBuilder keys = new StringBuilder("TUN-INBOUND-KEYS");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([a-z0-9_]+)\":\\s*\\{").matcher(text.substring(at,
                        Math.min(text.length(), at + 6000)));
        while (m.find()) {
            keys.append(' ').append(m.group(1));
        }
        Log.i(TAG, keys.toString());
        // This engine version routes by auto_route rather than an explicit route_address list;
        // the whole point of asking is not to carry a field name over from another version.
        assertTrue("a TUN inbound must be able to claim the device's traffic",
                keys.toString().contains("auto_route"));
    }

    @Test
    public void theEngineAcceptsATunInboundThatCapturesTheWholeDevice() throws Exception {
        System.loadLibrary("box");
        Method checkConfig = Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("checkConfig", String.class);
        String profile = "{\"log\":{\"level\":\"warn\"},"
                + "\"inbounds\":[{\"type\":\"tun\",\"tag\":\"tun-in\","
                + "\"interface_name\":\"colgram0\","
                + "\"address\":[\"172.19.0.1/30\",\"fdfe:dcba:9876::1/126\"],"
                + "\"mtu\":9000,"
                + "\"auto_route\":true,"
                + "\"strict_route\":true}],"
                + "\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]}";
        try {
            checkConfig.invoke(null, profile);
            Log.i(TAG, "the engine accepted a whole-device TUN inbound");
        } catch (Throwable rejected) {
            Throwable cause = rejected.getCause() != null ? rejected.getCause() : rejected;
            throw new AssertionError("the engine refused a whole-device TUN inbound: " + cause
                    + "\nprofile was: " + profile, cause);
        }
    }

    @Test
    public void aProfileWithNoTunIsNotADeviceWideTunnel() throws Exception {
        // The shape the subscription profile had: a local SOCKS port and nothing else. It is a
        // perfectly good profile and a completely inert one - no openTun, no routes, no capture.
        // Asserting it here keeps the two from being confused again, because both validate.
        Method checkConfig = Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("checkConfig", String.class);
        String profile = "{\"log\":{\"level\":\"warn\"},"
                + "\"inbounds\":[{\"type\":\"mixed\",\"tag\":\"mixed-in\","
                + "\"listen\":\"127.0.0.1\",\"listen_port\":2080}],"
                + "\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]}";
        checkConfig.invoke(null, profile);
        assertTrue("a profile with no tun inbound captures nothing, however valid it is",
                !profile.contains("\"tun\""));
        Log.i(TAG, "confirmed: a valid profile can still be a completely inert one");
    }

    @Test
    public void theRealSubscriptionProfileCarriesATunThatClaimsTheDevice() throws Exception {
        // The check that matters: not a hand-written profile, the one the app actually builds
        // from a pasted subscription. It validated perfectly before, with no tun inbound at all,
        // which is exactly why the gap survived every earlier test.
        java.util.List<org.colgram.core.ColgramSubscription.Node> nodes =
                org.colgram.core.ColgramSubscription.parse(String.join("\n",
                        "vless://8f2a1b44-1111-2222-3333-444455556666@a.example.net:443"
                                + "?security=reality&sni=s1.example.com"
                                + "&pbk=bmXOC-F1FxEMF9dyiK2H5_1SUtzH0JuVo51h2wPfgyo&sid=aa#one"));
        String profile = org.colgram.core.ColgramProfileBuilder.forNodes(nodes);
        assertTrue("the built profile must contain a tun inbound, or the phone is not routed",
                profile.contains("\"tun\""));
        assertTrue("the tun must claim the device's routes", profile.contains("auto_route"));
        // And the engine, which is the only thing that can say whether the shape is real.
        System.loadLibrary("box");
        Class.forName("io.nekohasekai.libbox.Libbox").getMethod("checkConfig", String.class)
                .invoke(null, profile);
        Log.i(TAG, "the engine accepted the built profile with its tun inbound");
    }

    @Test
    public void theWarpProfileCarriesATunToo() throws Exception {
        // WARP is a full-device route by definition, so the same gap applies: an endpoint that is
        // configured perfectly and a phone that still talks to the network directly.
        String profile = org.colgram.core.ColgramWarpProfileBuilder.build(
                "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "172.16.0.2", null,
                "2a2a2a", "engage.cloudflareclient.com", 2408, null);
        assertTrue("the WARP profile must contain a tun inbound", profile.contains("\"tun\""));
        assertTrue("the WARP tun must claim the device's routes", profile.contains("auto_route"));
        System.loadLibrary("box");
        Class.forName("io.nekohasekai.libbox.Libbox").getMethod("checkConfig", String.class)
                .invoke(null, profile);
        Log.i(TAG, "the engine accepted the WARP profile with its tun inbound");
    }
}
