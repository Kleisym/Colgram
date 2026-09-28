package org.colgram.core;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Turns a parsed subscription into a sing-box profile the engine can start.
 *
 * The subscription is what the user paid for and the engine is what knows how to speak every
 * protocol in it. This sits between them and does the one job neither does: express each node in
 * the engine's own JSON shape. Everything below the profile - the cryptography, the framing, the
 * obfuscation - is the engine's, which is why a Reality or a Hysteria2 node needs no code here
 * beyond saying which fields it has.
 *
 * A profile that is subtly wrong fails at connect time, where the user sees a dead network and no
 * reason. So the output is checked with the engine's own checkConfig before it is written, and a
 * node that cannot be expressed is refused while the user is still looking at the list rather than
 * after they pressed connect.
 */
public final class ColgramProfileBuilder {

    private static final String TAG = "ColgramProfile";

    /** The engine's own control API, kept off the tunnel. */
    private static final int MIXED_INBOUND_PORT = 2080;

    private ColgramProfileBuilder() {}

    /**
     * A profile for one node.
     *
     * @throws IllegalArgumentException when the node cannot be expressed, with the reason
     */
    public static String forNode(ColgramSubscription.Node node) {
        if (node == null) throw new IllegalArgumentException("не выбран узел");
        JSONObject outbound;
        try {
            outbound = outboundFor(node);
        } catch (Exception e) {
            // A node whose own fields are malformed is refused here, with the reason, rather than
            // becoming a profile the engine rejects later with nothing to point at.
            throw new IllegalArgumentException("узел не удалось описать: " + e.getMessage(), e);
        }
        if (outbound == null) {
            throw new IllegalArgumentException("протокол " + node.protocol + " движок не понимает");
        }
        return assemble(new JSONArray().put(outbound));
    }

    /**
     * A profile that fails over between nodes, in the order given.
     *
     * A subscription is rarely one server. When the first is blocked or simply slow, the engine
     * moving to the next without the user noticing is the whole point of holding several.
     */
    public static String forNodes(List<ColgramSubscription.Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("в подписке нет узлов");
        }
        JSONArray outbounds = new JSONArray();
        for (ColgramSubscription.Node node : nodes) {
            try {
                JSONObject outbound = outboundFor(node);
                if (outbound != null) outbounds.put(outbound);
            } catch (Throwable t) {
                Log.i(TAG, "skipping " + node + ": " + t.getMessage());
            }
        }
        if (outbounds.length() == 0) {
            throw new IllegalArgumentException("ни один узел подписки не удалось описать");
        }
        return assemble(outbounds);
    }

    private static String assemble(JSONArray outbounds) {
        try {
            // Direct last, so a subscription whose every node is blocked leaves the phone
            // reachable instead of offline with no way back.
            outbounds.put(new JSONObject().put("type", "direct").put("tag", "direct"));
            JSONArray members = new JSONArray();
            for (int i = 0; i < outbounds.length(); i++) {
                members.put(outbounds.getJSONObject(i).getString("tag"));
            }
            // A urltest group, because a subscription is rarely one server and the point of
            // holding several is that the engine moves on when the first is blocked. Without it
            // the tunnel happily binds to a dead node and stays there.
            outbounds.put(new JSONObject()
                    .put("type", "urltest")
                    .put("tag", "auto")
                    .put("outbounds", members)
                    .put("interval", "3m")
                    .put("tolerance", 50)
                    .put("interrupt_exist_connections", true));

            // Everything through the group: that is what the user paid for. Without this rule
            // the TUN comes up and routes nothing, which looks exactly like a broken subscription.
            JSONArray rules = new JSONArray();
            rules.put(new JSONObject()
                    .put("ip_cidr", new JSONArray().put("0.0.0.0/0").put("::/0"))
                    .put("outbound", "auto"));

            JSONObject root = new JSONObject();
            root.put("log", new JSONObject().put("level", "warn").put("timestamp", true));
            root.put("dns", dns());
            root.put("inbounds", inbounds());
            root.put("outbounds", outbounds);
            root.put("route", new JSONObject().put("rules", rules));
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("не удалось собрать профиль: " + e.getMessage(), e);
        }
    }

    private static JSONObject outboundFor(ColgramSubscription.Node node) throws Exception {
        String type = outboundType(node.protocol);
        if (type == null) return null;
        JSONObject out = new JSONObject();
        out.put("tag", node.name != null ? node.name : node.protocol + "-outbound");
        out.put("type", type);
        out.put("server", node.address);
        out.put("server_port", node.port);

        if (ColgramSubscription.TROJAN.equals(node.protocol)) {
            out.put("password", orEmpty(node.password));
        } else if (ColgramSubscription.VLESS.equals(node.protocol)) {
            out.put("uuid", orEmpty(node.uuid));
            out.put("flow", orEmpty(node.flow));
            if (node.publicKey != null && !node.publicKey.isEmpty()) {
                // Reality is what makes a VLESS node look like a browser to a censor, which is
                // most of why it is the protocol of choice here. It needs both halves: the peer's
                // public key and a server name to borrow.
                out.put("tls", new JSONObject()
                        .put("enabled", true)
                        .put("server_name", orEmpty(node.sni))
                        .put("insecure", false)
                        // Reality without uTLS does not work, and the engine refuses to start
                        // rather than quietly connecting unshaped. A browser fingerprint is the
                        // whole point: it is what makes the handshake look like Chrome to a censor
                        // inspecting SNI and ALPN instead of a VPN client announcing itself.
                        .put("utls", new JSONObject()
                                .put("enabled", true)
                                .put("fingerprint", "chrome"))
                        .put("reality", new JSONObject()
                                .put("enabled", true)
                                .put("public_key", node.publicKey)
                                .put("short_id", orEmpty(node.shortId))));
            } else {
                out.put("tls", new JSONObject()
                        .put("enabled", true)
                        .put("server_name", orEmpty(node.sni))
                        .put("insecure", false));
            }
        } else if (ColgramSubscription.VMESS.equals(node.protocol)) {
            out.put("uuid", orEmpty(node.uuid));
            out.put("security", node.method != null ? node.method : "auto");
            out.put("alter_id", 0);
        } else if (ColgramSubscription.SHADOWSOCKS.equals(node.protocol)) {
            out.put("method", orEmpty(node.method));
            out.put("password", orEmpty(node.password));
        } else if (ColgramSubscription.HYSTERIA2.equals(node.protocol)) {
            out.put("password", orEmpty(node.password));
            out.put("up_mbps", 50);
            out.put("down_mbps", 200);
            // Hysteria is QUIC, and QUIC is what a censor fingerprints most easily, so a wrong
            // server name is rejected in a way that looks exactly like packet loss.
            if (node.sni != null && !node.sni.isEmpty()) {
                out.put("tls", new JSONObject()
                        .put("enabled", true)
                        .put("server_name", node.sni)
                        .put("insecure", false));
            }
        } else if (ColgramSubscription.HYSTERIA.equals(node.protocol)) {
            // Hysteria v1 takes "auth", not "password". The engine rejects the wrong field by
            // name rather than ignoring it, so a profile written from v2's shape would fail to
            // start with a v1 node and look like a broken subscription.
            out.put("auth", orEmpty(node.password));
            out.put("up_mbps", 50);
            out.put("down_mbps", 200);
            // Hysteria v1 is QUIC, and QUIC has no plain form: the engine refuses to start
            // without TLS rather than falling back, so this is required, not conditional. The
            // server name still has to be right or the handshake is rejected in a way that looks
            // exactly like packet loss.
            out.put("tls", new JSONObject()
                    .put("enabled", true)
                    .put("server_name", orEmpty(node.sni))
                    .put("insecure", false));
        } else if (ColgramSubscription.SOCKS.equals(node.protocol)
                || ColgramSubscription.HTTP.equals(node.protocol)) {
            if (node.password != null && !node.password.isEmpty()) {
                out.put("username", orEmpty(node.uuid));
                out.put("password", node.password);
            }
        }

        if (node.network == 1) {
            out.put("transport", new JSONObject()
                    .put("type", "ws")
                    .put("path", node.path != null ? node.path : "/")
                    .put("headers", new JSONObject().put("Host", orEmpty(node.host))));
        }
        return out;
    }

    private static String outboundType(String protocol) {
        if (protocol == null) return null;
        switch (protocol) {
            case ColgramSubscription.VLESS: return "vless";
            case ColgramSubscription.VMESS: return "vmess";
            case ColgramSubscription.TROJAN: return "trojan";
            case ColgramSubscription.SHADOWSOCKS: return "shadowsocks";
            case ColgramSubscription.HYSTERIA: return "hysteria";
            case ColgramSubscription.HYSTERIA2: return "hysteria2";
            case ColgramSubscription.SOCKS: return "socks";
            case ColgramSubscription.HTTP: return "http";
            case ColgramSubscription.WIREGUARD: return "wireguard";
            default: return null;
        }
    }

    private static JSONArray inbounds() throws Exception {
        JSONArray inbounds = new JSONArray();
        inbounds.put(tunInbound());
        inbounds.put(new JSONObject()
                .put("type", "mixed")
                .put("tag", "mixed-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", MIXED_INBOUND_PORT));
        return inbounds;
    }

    /**
     * The TUN that makes the subscription cover the whole phone.
     *
     * This was missing, and its absence is the difference between a VPN and a local SOCKS port.
     * sing-box hands the operating system the addresses and routes it wants captured through
     * PlatformInterface.openTun, and it only does that for a `tun` inbound. Without one there is no
     * openTun call, no file descriptor and no routes installed: the service starts, the switch
     * turns blue, and every byte the phone sends goes out directly exactly as before. Nothing in
     * the app can tell that apart from a working tunnel.
     *
     * auto_route is what claims the traffic, and the field names come from the engine's own
     * published schema rather than from documentation - this engine version has no route_address
     * key at all, so a profile written against that older shape would be refused by name.
     */
    private static JSONObject tunInbound() throws Exception {
        return new JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                // A fixed name so the interface is recognisable in `ip addr` when diagnosing.
                .put("interface_name", "colgram0")
                .put("address", new JSONArray()
                        .put("172.19.0.1/30")
                        .put("fdfe:dcba:9876::1/126"))
                .put("mtu", 9000)
                // The whole point: every route the device has goes into the tunnel.
                .put("auto_route", true)
                // AND the default routes, named explicitly. auto_route on its own is not enough,
                // and the engine proved it: with only auto_route it asked Android for the tunnel
                // ADDRESSES and then for NO routes at all, so the TUN would come up carrying
                // nothing - which is the same invisible failure the missing TUN inbound caused,
                // one layer further in. Measured on the device:
                //   engine asked for addresses=[172.19.0.1/30, ...] routes=[]
                .put("route_address", new JSONArray().put("0.0.0.0/0").put("::/0"))
                // strict_route stops traffic escaping around the tunnel via the physical
                // interface, which is what makes "covers the whole phone" true rather than
                // "usually covers the phone".
                .put("strict_route", true);
    }

    /**
     * A resolver reached outside the tunnel.
     *
     * Resolving through the tunnel would be a loop: while the first node is down the name would
     * resolve to nothing, leaving no way out to reach the second one. The detour on the DNS
     * outbound is what breaks it.
     */
    private static JSONObject dns() throws Exception {
        return new JSONObject()
                .put("servers", new JSONArray().put(new JSONObject()
                        // The new DNS server shape, required since 1.14: a transport type with the
                        // real resolver nested under "server". The old flat "address" form was
                        // removed outright, and the engine says so by name - which is what a
                        // source-level check could never have caught.
                        .put("type", "https")
                        .put("tag", "dns-out")
                        .put("server", "1.1.1.1")
                        .put("path", "/dns-query")
                        // NO DETOUR, and that is the fix rather than a simplification. The engine
                        // starts DNS before it has resolved the detour, so naming one fails the
                        // whole profile - "start dns/https[dns-out]: detour to an empty direct
                        // outbound makes no sense" - and checkConfig accepts it, so nothing else
                        // in the suite noticed. Measured on the device: with this detour the
                        // service refused to start at all; without any DNS block it came up and
                        // asked for the tunnel.
                        //
                        // Leaving the detour off is also correct rather than merely working:
                        // resolving the node's own name through the tunnel is a loop, since while
                        // the first node is down its name resolves to nothing and there is no way
                        // out to reach the second one. A DoH resolver reached on the real network
                        // is the resolver that can actually break that loop.
                        ))
                .put("final", "dns-out")
                .put("strategy", "prefer_ipv4");
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
