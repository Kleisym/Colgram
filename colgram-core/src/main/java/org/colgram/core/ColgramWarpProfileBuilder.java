package org.colgram.core;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A sing-box profile that routes the whole phone through Cloudflare WARP.
 *
 * This class exists because of a measured crash, not a preference. WARP used to be driven by the
 * embedded WireGuard Android backend (com.wireguard.android.backend.GoBackend plus its bundled
 * libwg-go.so) alongside sing-box's libbox.so. Those are each a complete cgo Go runtime, and two
 * of them in one process do not coexist. Measured on the device: loading the WARP backend and
 * then calling Libbox.checkConfig killed the process with signal 11 inside about a second, with
 * no Java exception, no tombstone and no stack to point at it. The same checkConfig call passes
 * with either runtime alone, which is exactly why it only ever appeared in the full device suite
 * - the one run that loads both.
 *
 * sing-box speaks WireGuard natively, so WARP now runs through the same engine that already
 * carries the subscription. One Go runtime, one TUN, and the two features can no longer collide
 * because there is only one of each.
 */
public final class ColgramWarpProfileBuilder {

    /** WARP refuses inner packets larger than this, and the engine will not negotiate it. */
    private static final int MTU = 1280;

    private ColgramWarpProfileBuilder() {}

    /**
     * Build the profile.
     *
     * @param privateKey this device's WARP private key, base64
     * @param addressV4  the IPv4 address Cloudflare assigned, without a prefix length
     * @param addressV6  the IPv6 address Cloudflare assigned, or null when there is none
     * @param reserved   Cloudflare's 3-byte client id as 6 hex characters, or null
     * @param host       the endpoint host Cloudflare assigned
     * @param port       the UDP port on that host
     * @param peerKey    Cloudflare's peer public key, or null to use the well-known one
     */
    public static String build(String privateKey, String addressV4, String addressV6,
                               String reserved, String host, int port, String peerKey) {
        return build(privateKey, addressV4, addressV6, reserved, host, port, peerKey, null, null);
    }

    /**
     * Build the profile, optionally through a relay.
     *
     * A relay terminates the WireGuard handshake itself, so BOTH the peer key and the endpoint
     * become the relay's. Pointing Cloudflare's key at a relay that does not own it fails in a way
     * that is indistinguishable from a dead WARP, which is how a working relay gets blamed for not
     * working - so the key is swapped together with the address, never on its own.
     *
     * @param relayKey    the relay's own WireGuard public key, or null to go to Cloudflare direct
     * @param presharedKey the relay's preshared key, or null
     */
    public static String build(String privateKey, String addressV4, String addressV6,
                               String reserved, String host, int port, String peerKey,
                               String relayKey, String presharedKey) {
        if (privateKey == null || privateKey.trim().isEmpty()) {
            throw new IllegalArgumentException("нет ключа WARP");
        }
        if (addressV4 == null || addressV4.trim().isEmpty()) {
            throw new IllegalArgumentException("нет адреса WARP");
        }
        if (host == null || host.trim().isEmpty() || port <= 0) {
            throw new IllegalArgumentException("нет адреса сервера WARP");
        }
        String peer = peerKey == null || peerKey.trim().isEmpty()
                ? ColgramWarp.WARP_PEER_PUBLIC_KEY : peerKey.trim();
        String endpoint = host.trim();
        int endpointPort = port;
        if (relayKey != null && !relayKey.trim().isEmpty()) {
            // The relay owns the handshake, so its key is the one the peer must be pinned to.
            peer = relayKey.trim();
        }
        try {
            JSONArray local = new JSONArray().put(addressV4.trim() + "/32");
            if (addressV6 != null && !addressV6.trim().isEmpty()) {
                local.put(addressV6.trim() + "/128");
            }

            // The peer is an object, not a flat server/server_port pair. The engine says so
            // itself, and precisely: `outbounds[0].server: json: unknown field "server"`.
            // Measured on the device against sing-box 1.14.2, where the flat shape was removed.
            JSONObject peerObject = new JSONObject()
                    .put("address", endpoint)
                    .put("port", endpointPort)
                    .put("public_key", peer)
                    // A WARP route is a full-device route. Without this the tunnel comes up and
                    // routes nothing, which is indistinguishable from a dead subscription.
                    .put("allowed_ips", new JSONArray().put("0.0.0.0/0").put("::/0"));

            JSONArray reservedField = bytes(reserved);
            if (reservedField != null) {
                // Cloudflare pins this identity in WireGuard's three reserved header bytes.
                // Sending zeroes where Cloudflare expects the client id produces a handshake
                // that times out, which looks exactly like a blocked network rather than a
                // misconfigured identity.
                peerObject.put("reserved", reservedField);
            }
            if (presharedKey != null && !presharedKey.trim().isEmpty()) {
                peerObject.put("pre_shared_key", presharedKey.trim());
            }

            // A WireGuard ENDPOINT, not a WireGuard outbound. The outbound was deprecated in
            // sing-box 1.11.0 and removed in 1.13.0, and the engine says so itself, by name:
            //   "WireGuard outbound is deprecated in sing-box 1.11.0 and removed in sing-box
            //    1.13.0, use WireGuard endpoint instead"
            // Every field-level rejection before that was this single refusal reported against
            // whichever key happened to come first, which is why address, private_key, peers,
            // mtu, listen_port, udp_timeout and workers all came back "unknown field": the
            // decoder never got as far as reading any of them.
            JSONObject endpointObject = new JSONObject()
                    .put("type", "wireguard")
                    .put("tag", "warp")
                    .put("address", local)
                    .put("private_key", privateKey.trim())
                    .put("peers", new JSONArray().put(peerObject))
                    .put("mtu", MTU);

            // Traffic leaves through an ordinary outbound; the endpoint is what carries it.
            JSONArray outbounds = new JSONArray();
            outbounds.put(new JSONObject()
                    .put("type", "direct")
                    .put("tag", "direct"));

            JSONArray rules = new JSONArray();
            // A WARP route is a full-device route. Without this rule the tunnel comes up and
            // routes nothing, which is indistinguishable from a broken subscription.
            rules.put(new JSONObject()
                    .put("ip_cidr", new JSONArray().put("0.0.0.0/0").put("::/0"))
                    .put("action", "route")
                    .put("outbound", "direct"));

            JSONObject root = new JSONObject();
            root.put("log", new JSONObject().put("level", "warn").put("timestamp", true));
            root.put("inbounds", inbounds());
            root.put("outbounds", outbounds);
            root.put("route", new JSONObject().put("rules", rules));
            // route.endpoints was rejected by name as well - the engine said
            // "route.endpoints: json: unknown field" - so endpoints sit at the top level,
            // next to outbounds.
            root.put("endpoints", new JSONArray().put(endpointObject));
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("не удалось собрать профиль WARP: " + e.getMessage(), e);
        }
    }

    /** The three reserved bytes, or null when Cloudflare assigned none or the text is malformed. */
    private static JSONArray bytes(String reserved) {
        if (reserved == null) return null;
        String hex = reserved.trim().toLowerCase();
        if (hex.length() != 6) return null;
        try {
            return new JSONArray()
                    .put(Integer.parseInt(hex.substring(0, 2), 16))
                    .put(Integer.parseInt(hex.substring(2, 4), 16))
                    .put(Integer.parseInt(hex.substring(4, 6), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The local mixed inbound.
     *
     * The engine's own control port, not the TUN: the service attaches the TUN itself through
     * openTun, and a second inbound on the same profile would only compete for the port.
     */
    private static JSONArray inbounds() throws Exception {
        JSONArray inbounds = new JSONArray();
        inbounds.put(new JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("interface_name", "colgram0")
                .put("address", new JSONArray()
                        .put("172.19.0.1/30")
                        .put("fdfe:dcba:9876::1/126"))
                .put("mtu", 9000)
                // A WARP route is a full-device route, and this is the field that makes the
                // operating system hand the device's traffic to it. Without a tun inbound the
                // endpoint is configured perfectly and the phone still talks to the network
                // directly - a green switch over an inert tunnel.
                .put("auto_route", true)
                // auto_route alone gave the engine the tunnel ADDRESSES and no routes at all,
                // measured on the device, so the TUN would come up carrying nothing. The
                // default routes are named explicitly for the same reason they are in the
                // subscription profile: a tunnel that captures nothing is indistinguishable from
                // a working one from the settings screen.
                .put("route_address", new JSONArray().put("0.0.0.0/0").put("::/0"))
                .put("strict_route", true));
        inbounds.put(new JSONObject()
                .put("type", "mixed")
                .put("tag", "mixed-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", 2080));
        return inbounds;
    }
}
