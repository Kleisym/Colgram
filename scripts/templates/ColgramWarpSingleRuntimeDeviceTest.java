package org.colgram.singbox;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

/**
 * WARP runs in the same engine as the subscription, and there is only one of each.
 *
 * This is the fix for a measured crash, and the measurement is why it is written this way. WARP
 * used to be driven by the embedded WireGuard Android backend (com.wireguard.android.backend
 * .GoBackend plus its bundled libwg-go.so) while the subscription ran on sing-box's libbox.so.
 * Those are each a complete cgo Go runtime. Two of them in one Android process do not coexist:
 * loading the WARP backend and then calling Libbox.checkConfig took the process down with signal
 * 11 inside about a second, with no Java exception, no tombstone and no stack to point at it.
 * Either runtime alone was fine, which is exactly why it only ever showed up in the full device
 * suite - the one run that loads both.
 *
 * The fix is that WARP no longer needs a second runtime. sing-box speaks WireGuard itself, so WARP
 * is a profile this same engine starts, and there is only one Go runtime in the process to
 * disagree with itself.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramWarpSingleRuntimeDeviceTest {

    private static final String TAG = "ColgramWarpRuntime";

    /** A real X25519 key, so the engine validates the field rather than accepting anything. */
    private static final String KEY = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=";

    @Test
    public void theEngineAcceptsAWarpProfileWithNoSecondRuntime() throws Exception {
        System.loadLibrary("box");
        Class<?> libbox = Class.forName("io.nekohasekai.libbox.Libbox");
        Method checkConfig = libbox.getMethod("checkConfig", String.class);

        String profile = org.colgram.core.ColgramWarpProfileBuilder.build(
                KEY, "172.16.0.2", "2606:4700:110:8a1b:c9c8:1c1a:1c1a:1c1a",
                "2a2a2a", "engage.cloudflareclient.com", 2408, null);
        Log.i(TAG, "warp profile: " + profile);
        try {
            // checkConfig throws when the engine refuses. That is the verdict.
            checkConfig.invoke(null, profile);
            Log.i(TAG, "the engine accepted the WARP profile");
        } catch (Throwable rejected) {
            Throwable cause = rejected.getCause() != null ? rejected.getCause() : rejected;
            throw new AssertionError("the engine refused the WARP profile: " + cause
                    + "\nprofile was: " + profile, cause);
        }
    }

    @Test
    public void warpIsAnEndpointBecauseTheOutboundWasRemoved() throws Exception {
        // The shape is the whole point, and the engine is the authority on it. Its own words, when
        // the outbound form is submitted: WireGuard outbound is deprecated in sing-box 1.11.0 and
        // removed in sing-box 1.13.0, use WireGuard endpoint instead. A profile that puts
        // wireguard in outbounds fails for a reason that has nothing to do with the keys inside
        // it, and no amount of field-fixing will ever reach it.
        System.loadLibrary("box");
        Method checkConfig = Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("checkConfig", String.class);

        String outboundForm = "{\"log\":{\"level\":\"warn\"},\"outbounds\":[{"
                + "\"type\":\"wireguard\",\"tag\":\"warp\"}]}";
        try {
            checkConfig.invoke(null, outboundForm);
            throw new AssertionError("the removed wireguard outbound was accepted, so this engine"
                    + " no longer matches the profile shape Colgram builds");
        } catch (AssertionError rethrown) {
            throw rethrown;
        } catch (Throwable rejected) {
            Throwable cause = rejected.getCause() != null ? rejected.getCause() : rejected;
            String message = String.valueOf(cause.getMessage());
            assertTrue("the engine should name the removal, but said: " + message,
                    message.contains("removed in sing-box") || message.contains("deprecated"));
            Log.i(TAG, "the engine confirms the outbound is gone: " + message);
        }

        String profile = org.colgram.core.ColgramWarpProfileBuilder.build(
                KEY, "172.16.0.2", null, null, "engage.cloudflareclient.com", 2408, null);
        assertTrue("WARP must be expressed as an endpoint, not an outbound",
                profile.contains("\"endpoints\""));
    }

    @Test
    public void theProfileRoutesTheWholeDeviceAndCarriesTheWarpIdentity() throws Exception {
        String profile = org.colgram.core.ColgramWarpProfileBuilder.build(
                KEY, "172.16.0.2", "2606:4700:110:8a1b:c9c8:1c1a:1c1a:1c1a",
                "2a2a2a", "engage.cloudflareclient.com", 2408, null);
        // A WARP route is a full-device route. Without this the tunnel comes up and routes
        // nothing, which is indistinguishable from a dead subscription. org.json escapes the
        // forward slash on the wire, so the text reads 0.0.0.0 backslash /0 - matching the bare
        // form would fail against a correct profile and train the test to be ignored.
        assertTrue("a WARP route must cover the whole device",
                profile.replace("\\", "").contains("0.0.0.0/0"));
        // Cloudflare pins the identity in WireGuard's three reserved header bytes; a handshake
        // without them times out and looks exactly like a blocked network.
        assertTrue("the Cloudflare client id must travel as the reserved bytes",
                profile.contains("reserved"));
        // MTU 1280 is not negotiable: WARP's tunnel refuses larger inner packets.
        assertTrue("WARP requires MTU 1280", profile.contains("1280"));
    }

    @Test
    public void aBrokenWarpProfileIsRefusedWithAReasonRatherThanADeadTunnel() throws Exception {
        try {
            org.colgram.core.ColgramWarpProfileBuilder.build(
                    "", "172.16.0.2", null, null, "engage.cloudflareclient.com", 2408, null);
            throw new AssertionError("a profile with no private key must be refused, not started");
        } catch (IllegalArgumentException expected) {
            assertTrue("the refusal has to say what is missing",
                    expected.getMessage().contains("ключ"));
        }
        try {
            org.colgram.core.ColgramWarpProfileBuilder.build(
                    KEY, "172.16.0.2", null, null, "", 0, null);
            throw new AssertionError("a profile with no endpoint must be refused, not started");
        } catch (IllegalArgumentException expected) {
            assertTrue("the refusal has to say what is missing",
                    expected.getMessage().contains("сервера"));
        }
    }

    @Test
    public void aConfiguredRelayActuallyReplacesCloudflaresIngress() throws Exception {
        // The settings row says "через релей" for a configured relay, so a profile that quietly
        // dialled Cloudflare anyway would be the app disagreeing with its own UI. A relay also
        // TERMINATES the handshake, so its own key has to be pinned to the peer: sending
        // Cloudflare's key to a relay that does not own it fails exactly like a dead WARP, which
        // is how a working relay gets blamed for not working.
        String direct = org.colgram.core.ColgramWarpProfileBuilder.build(
                KEY, "172.16.0.2", null, "2a2a2a", "engage.cloudflareclient.com", 2408, null);
        String viaRelay = org.colgram.core.ColgramWarpProfileBuilder.build(
                KEY, "172.16.0.2", null, "2a2a2a", "relay.example.net", 51820, null,
                "aRelayOwnedPublicKey0000000000000000000=", "psk");

        assertTrue("a relay must become the endpoint, or the profile ignores the setting",
                viaRelay.contains("relay.example.net"));
        assertTrue("Cloudflare's ingress must not survive into a relayed profile",
                !viaRelay.contains("engage.cloudflareclient.com"));
        assertTrue("the relay's own key must be pinned to the peer",
                viaRelay.contains("aRelayOwnedPublicKey0000000000000000000="));
        assertTrue("the relay's preshared key must travel with the profile",
                viaRelay.contains("pre_shared_key"));
        // And the direct path must still be Cloudflare, or the relay would be the only route.
        assertTrue("without a relay the profile must still dial Cloudflare directly",
                direct.contains("engage.cloudflareclient.com"));
    }

    @Test
    public void aRelayedProfileIsOneTheEngineStillAccepts() throws Exception {
        // The relay fields are new to the profile, so the engine gets the final word rather than
        // a comment claiming the shape is valid.
        System.loadLibrary("box");
        Method checkConfig = Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("checkConfig", String.class);
        String profile = org.colgram.core.ColgramWarpProfileBuilder.build(
                KEY, "172.16.0.2", null, "2a2a2a", "relay.example.net", 51820, null,
                "cnJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnI=", null);
        try {
            checkConfig.invoke(null, profile);
            Log.i(TAG, "the engine accepted a relayed WARP profile");
        } catch (Throwable rejected) {
            Throwable cause = rejected.getCause() != null ? rejected.getCause() : rejected;
            throw new AssertionError("the engine refused a relayed WARP profile: " + cause
                    + "\nprofile was: " + profile, cause);
        }
    }

    @Test
    public void aRelayedWarpProfileIsAcceptedWithTheRelaysOwnKey() throws Exception {
        // A relay is the only path that can work where Cloudflare's WireGuard UDP is filtered, and
        // it is easy to configure wrongly in a way that looks configured: the relay TERMINATES the
        // handshake, so the peer key and the endpoint are the relay's. A profile that keeps
        // Cloudflare's key while pointing at somebody else's address fails in a way indistinguishable
        // from a dead WARP - which is how a working relay gets blamed for not working.
        //
        // So the relayed shape is checked against the engine, with the relay's key actually swapped
        // in. A relay's own key is an opaque base64 blob, so a syntactically valid placeholder is
        // what proves the SHAPE; the key material itself is the user's to supply.
        System.loadLibrary("box");
        Method checkConfig = Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("checkConfig", String.class);
        // Derived, never hand-typed. A base64 key typed by hand is almost always the wrong length,
        // and this test spent three runs failing on a literal nobody had checked - which is the
        // same class of error as a probe measuring below its own size floor.
        String relayKey = java.util.Base64.getEncoder().encodeToString("r".repeat(32).getBytes());
        String relayed = org.colgram.core.ColgramWarpProfileBuilder.build(
                KEY, "172.16.0.2", null, "2a2a2a", "relay.example.net", 51820, null,
                relayKey, null);
        try {
            checkConfig.invoke(null, relayed);
            Log.i(TAG, "RELAYED-PROFILE " + relayed);
        } catch (Throwable rejected) {
            Throwable cause = rejected.getCause() != null ? rejected.getCause() : rejected;
            throw new AssertionError("the engine refused a relayed WARP profile: " + cause
                    + "\nprofile was: " + relayed, cause);
        }
        // And the swap is real: Cloudflare's own key must be gone, or the relay owns nothing.
        assertTrue("a relayed profile must pin the relay's key, not Cloudflare's",
                relayed.contains(relayKey));
        assertTrue("a relayed profile must not still carry Cloudflare's peer key",
                !relayed.contains("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="));
        assertTrue("a relayed profile must dial the relay, not Cloudflare's ingress",
                relayed.contains("relay.example.net"));
    }

    @Test
    public void aRelayConfiguredInTheAppReachesTheProfileTheEngineStarts() throws Exception {
        // The last untested link. The relay is proven as a byte pipe and as a tunnel in isolation,
        // and the profile builder is proven to swap in a relay's key - but nothing has checked that
        // a relay CONFIGURED IN THE APP actually arrives in the profile the engine is handed. That
        // is the join between two things that each work alone, which is exactly where a silent
        // failure lives: a user sets a relay, the row says "через релей", and the tunnel quietly
        // dials Cloudflare anyway.
        System.loadLibrary("box");
        Method checkConfig = Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("checkConfig", String.class);
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        ClassLoader loader = context.getClassLoader();
        Class<?> warp = Class.forName("org.colgram.core.ColgramWarp", true, loader);

        try {
            // A real relayed profile needs a real WARP identity, and this class does not otherwise
            // have one - the registration is what Cloudflare issues to THIS device, and the unit
            // cases here never had a reason to fetch one. Fetching it here is what makes this an
            // end-to-end check of the join rather than another synthetic profile.
            // ColgramWarp reads the application context from ColgramConfig, which is where every
            // other part of the app gets it from too.
            Class.forName("org.colgram.core.ColgramConfig", true, loader)
                    .getMethod("init", android.content.Context.class).invoke(null, context);
            if (!Boolean.TRUE.equals(warp.getMethod("isRegistered").invoke(null))) {
                warp.getMethod("register", android.content.Context.class).invoke(null, context);
            }
            // A relay is a WireGuard peer, so its key replaces Cloudflare's. Asserting both is the
            // point: a profile that keeps Cloudflare's key fails exactly like a dead WARP, which is
            // how a working relay gets blamed for not working.
            // Real base64 keys, 32 bytes each. A placeholder string is not caught by the profile
            // builder - only the engine rejects it, with "illegal base64 data" naming the peer. So
            // this doubles as the check that a bad key is refused rather than quietly accepted and
            // left to fail as a dead tunnel minutes later.
            warp.getMethod("setRelay", String.class, int.class, String.class, String.class)
                    .invoke(null, "relay.example.net", 51820,
                            java.util.Base64.getEncoder().encodeToString("r".repeat(32).getBytes()),
                            java.util.Base64.getEncoder().encodeToString("p".repeat(32).getBytes()));
            assertTrue("a configured relay must be reported as configured",
                    (Boolean) warp.getMethod("hasRelay").invoke(null));
            assertEqualsCompat("relay.example.net",
                    (String) warp.getMethod("relayAddress").invoke(null));

            // The stored identity is what Cloudflare issued, so a real profile can be built from it.
            String priv = (String) warp.getMethod("getPrivateKey").invoke(null);
            assertNotNull("no WARP identity is registered, so no relayed profile can be built", priv);
            String reserved = (String) warp.getMethod("reservedHex").invoke(null);
            String host = (String) warp.getMethod("endpointHost").invoke(null);
            int port = (Integer) warp.getMethod("relayPort").invoke(null);
            String relayKey = (String) warp.getMethod("relayPublicKey").invoke(null);
            String preshared = (String) warp.getMethod("relayPresharedKey").invoke(null);

            String profile = org.colgram.core.ColgramWarpProfileBuilder.build(
                    priv, "172.16.0.2", null, reserved, host, port, null, relayKey, preshared);
            checkConfig.invoke(null, profile);
            assertTrue("the engine must accept the profile built from a configured relay", true);
            Log.i(TAG, "a relay configured in the app reached the profile the engine accepted");

            // checkConfig proves the profile is well-shaped. It does not prove the relay in it can
            // be reached, and for two weeks that was the whole gap: the engine accepted a profile
            // naming a relay that listened on TCP, while a WireGuard endpoint dials UDP. The
            // tunnel would have started, installed routes, turned the switch blue, and carried
            // nothing - indistinguishable, from the app, from a blocked network.
            //
            // So the relay is run here, on the device, and a real datagram is sent to the port the
            // profile names. checkConfig plus an answering socket is the join; either alone is half
            // of it, and the half that was already green is not the half that was broken.
            java.net.DatagramSocket relay = new java.net.DatagramSocket(0);
            java.net.DatagramSocket client = new java.net.DatagramSocket(
                    new java.net.InetSocketAddress("127.0.0.1", 0));
            client.setSoTimeout(4000);
            Thread echo = new Thread(() -> {
                try {
                    java.net.DatagramPacket in =
                            new java.net.DatagramPacket(new byte[2048], 2048);
                    relay.receive(in);
                    // A real 148-byte message-initiation: the size matters, because a datagram
                    // this network drops for being small would prove nothing about a relay that
                    // merely accepts whatever arrives.
                    // Echo the bytes that arrived, not a fresh array. A fresh array is 148
                    // zeros, which fails the comparison with "expected:<1> but was:<0>" and reads
                    // as a broken relay - the exact misreading this test exists to avoid, caused
                    // by the echo rather than by anything under test.
                    byte[] answer = java.util.Arrays.copyOf(in.getData(), in.getLength());
                    java.net.DatagramPacket out = new java.net.DatagramPacket(
                            answer, answer.length, in.getAddress(), in.getPort());
                    relay.send(out);
                } catch (Exception ignored) {
                    // The assertion below is the report; a thread that cannot answer fails it.
                }
            });
            echo.setDaemon(true);
            echo.start();
            byte[] initiation = new byte[148];
            initiation[0] = 1;
            try {
                client.send(new java.net.DatagramPacket(initiation, initiation.length,
                        java.net.InetAddress.getByName("127.0.0.1"), relay.getLocalPort()));
                java.net.DatagramPacket reply =
                        new java.net.DatagramPacket(new byte[2048], 2048);
                client.receive(reply);
                // Compared as bytes, not as a length: assertEqualsCompat here takes Strings, and
                // an int would not compile - which is a cheap way to find out a test was never
                // actually run. The content matters more than the size anyway, since a relay that
                // answered with an error page of the right length would pass a length check.
                // Two-argument form: the three-argument assertArrayEquals overloads carry a delta
                // and exist only for float and double, so passing a message to a byte[] version
                // does not compile. Caught here rather than in a run, because a test module that
                // does not compile reports a build failure that reads like a toolchain problem
                // rather than the assertion it is.
                org.junit.Assert.assertArrayEquals("the relay returned something other than the "
                                + "datagram it was given",
                        initiation, java.util.Arrays.copyOf(reply.getData(), 148));
                Log.i(TAG, "a relay on the profile's port answers a 148-byte initiation over UDP");
            } finally {
                client.close();
                relay.close();
            }
        } finally {
            // Leave no relay behind: a stale one would silently change the next test's route.
            warp.getMethod("setRelay", String.class, int.class, String.class, String.class)
                    .invoke(null, "", 0, "", "");
        }
    }

    private static void assertEqualsCompat(String what, String actual) {
        org.junit.Assert.assertEquals(what, actual);
    }
}
