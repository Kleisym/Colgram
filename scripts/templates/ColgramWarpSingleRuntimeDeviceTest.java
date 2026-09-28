package org.colgram.singbox;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

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
                "aRelayOwnedPublicKey0000000000000000000=", null);
        try {
            checkConfig.invoke(null, profile);
            Log.i(TAG, "the engine accepted a relayed WARP profile");
        } catch (Throwable rejected) {
            Throwable cause = rejected.getCause() != null ? rejected.getCause() : rejected;
            throw new AssertionError("the engine refused a relayed WARP profile: " + cause
                    + "\nprofile was: " + profile, cause);
        }
    }
}
