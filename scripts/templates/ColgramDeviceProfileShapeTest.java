package org.colgram.core;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Which shape of WireGuard profile this engine actually decodes.
 *
 * <p>The profile in use declares a wireguard block under `endpoints`, with `peers` on it, and a route
 * rule that sends everything to that block's tag. It is accepted with no error, the tunnel comes up,
 * the engine is handed a working tun, traffic reaches the engine - and not one datagram is ever sent
 * to the peer.
 *
 * <p>The rule is the suspicious part, and not on network grounds. In sing-box an endpoint is a
 * transport, not something traffic is routed to: traffic is routed to an outbound, by that outbound's
 * tag. A rule naming an endpoint's tag therefore points at something the router does not carry, which
 * is a configuration that parses and routes nowhere.
 *
 * <p>Whether the engine agrees is answerable, because an unknown field is refused with its own path:
 * `outbounds[0].peers: json: unknown field "peers"` is what settled the peer shape earlier. The same
 * oracle is used here over several candidate shapes, and every answer is kept - an acceptance is as
 * informative as a rejection and more dangerous, because it is silent.
 *
 * <p>Nothing here starts a tunnel. It only asks the decoder what it accepts.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceProfileShapeTest {

    private static final String TAG = "ColgramShape";
    private static final String HOST = "10.0.2.2";
    private static final int PORT = 51823;
    private static final String PEER = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PRIVATE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    public void theEngineNamesTheShapeItDecodes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        setupEngine(context);

        judge("endpoint, rule names its tag", endpointWithPeersOnIt(), ruleTo("warp"), null);
        judge("endpoint, rule names direct", endpointWithPeersOnIt(), ruleTo("direct"), null);
        judge("wireguard outbound, server shape", outboundWireGuard(), ruleTo("warp"), null);
        judge("endpoint and outbound together", endpointWithPeersOnIt(), ruleTo("warp"),
                outboundWireGuard());
    }

    /** Ask the decoder, and keep its answer whatever it is. */
    private static void judge(String name, JSONObject block, JSONArray rules, JSONObject extra)
            throws Exception {
        boolean isEndpoint = block.has("peers");

        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("level", "debug").put("timestamp", true));
        root.put("inbounds", new JSONArray().put(new JSONObject()
                .put("type", "mixed")
                .put("tag", "local")
                .put("listen", "127.0.0.1")
                .put("listen_port", 2080)));

        JSONArray outbounds = new JSONArray();
        if (isEndpoint) {
            root.put("endpoints", new JSONArray().put(block));
        } else {
            outbounds.put(block);
        }
        if (extra != null) {
            outbounds.put(extra);
        }
        outbounds.put(new JSONObject().put("type", "direct").put("tag", "direct"));
        root.put("outbounds", outbounds);
        if (rules != null) {
            root.put("route", new JSONObject().put("rules", rules));
        }

        String profile = root.toString();
        try {
            Class.forName("io.nekohasekai.libbox.Libbox")
                    .getMethod("checkConfig", String.class).invoke(null, profile);
            Log.i(TAG, name + " -> ACCEPTED");
        } catch (Exception rejected) {
            Throwable cause = rejected.getCause() == null ? rejected : rejected.getCause();
            Log.i(TAG, name + " -> " + cause);
        }
    }

    private static JSONObject endpointWithPeersOnIt() throws Exception {
        return new JSONObject()
                .put("type", "wireguard")
                .put("tag", "warp")
                .put("address", new JSONArray().put("172.16.0.2/32").put("2606:4700:110::1/128"))
                .put("private_key", PRIVATE)
                .put("peers", new JSONArray().put(new JSONObject()
                        .put("address", HOST)
                        .put("port", PORT)
                        .put("public_key", PEER)
                        .put("allowed_ips", new JSONArray().put("0.0.0.0/0").put("::/0"))
                        .put("reserved", new JSONArray().put(1).put(2).put(3))))
                .put("mtu", 1280);
    }

    /** The pre-endpoint spelling: an outbound named by server, server_port and peer_public_key. */
    private static JSONObject outboundWireGuard() throws Exception {
        return new JSONObject()
                .put("type", "wireguard")
                .put("tag", "warp")
                .put("server", HOST)
                .put("server_port", PORT)
                .put("local_address", new JSONArray().put("172.16.0.2/32"))
                .put("private_key", PRIVATE)
                .put("peer_public_key", PEER)
                .put("reserved", new JSONArray().put(1).put(2).put(3))
                .put("mtu", 1280);
    }

    private static JSONArray ruleTo(String outbound) throws Exception {
        return new JSONArray().put(new JSONObject()
                .put("ip_cidr", new JSONArray().put("0.0.0.0/0").put("::/0"))
                .put("action", "route")
                .put("outbound", outbound));
    }

    private static void setupEngine(Context context) throws Exception {
        java.io.File base = new java.io.File(context.getCacheDir(), "libbox-shape");
        base.mkdirs();
        java.io.File temp = new java.io.File(base, "tmp");
        temp.mkdirs();
        Class<?> options = Class.forName("io.nekohasekai.libbox.SetupOptions");
        Object setup = options.getConstructor().newInstance();
        options.getMethod("setBasePath", String.class).invoke(setup, base.getAbsolutePath());
        options.getMethod("setWorkingPath", String.class)
                .invoke(setup, new java.io.File(base, "work").getAbsolutePath());
        options.getMethod("setTempPath", String.class).invoke(setup, temp.getAbsolutePath());
        options.getMethod("setCrashReportSource", String.class).invoke(setup, "colgram");
        options.getMethod("setDebug", boolean.class).invoke(setup, true);
        Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("setup", options).invoke(null, setup);
    }
}
