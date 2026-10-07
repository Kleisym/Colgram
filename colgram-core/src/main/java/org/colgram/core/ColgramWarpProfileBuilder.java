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

    /**
     * Seconds between keepalives the endpoint sends on its own.
     *
     * <p>Measured, not guessed. Against a local peer and a live tunnel, one variable at a time: the
     * profile as shipped sent no initiation at all; adding this timer on its own still sent none;
     * adding it together with a literal peer address sent a real 148-byte initiation, twice, on two
     * runs. So the timer is kept - it is half of the only combination that has ever made this engine
     * put a packet on the wire - and the address shape is the other half.
     *
     * <p>It is also what keeps a session alive through a NAT that would otherwise drop it, and it is
     * what surfaces a dead route: without traffic and without this, a filtered endpoint is
     * indistinguishable from an idle one, which is precisely the state every earlier run reported.
     */
    private static final int KEEPALIVE_SECONDS = 25;

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

            // The keepalive timer, on the peer, in seconds. This is half of the only combination
            // measured to make this engine put a handshake on the wire: a literal peer address plus
            // this timer. Without it the profile is accepted, the tunnel comes up, traffic reaches
            // the engine, and not one datagram is ever sent - the state every run in this project
            // reported before it was measured. With the address still a name, the timer changes
            // nothing; both halves are needed and only the address half is this file's business.
            peerObject.put("persistent_keepalive_interval", KEEPALIVE_SECONDS);

            // No mac1 here, and the engine says so by name when one is offered:
            //   endpoints[0].peers[0].mac1: json: unknown field "mac1"
            // Its own binary lists the peer fields it decodes - address, port, public_key,
            // pre_shared_key, allowed_ips, reserved, persistent_keepalive_interval - and mac1 is not
            // among them, so this engine computes the cookie itself and a profile may not carry one.
            // Adding it was a dead end that cost a build cycle; the empty handshake is a different
            // fault and is still open.

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
            JSONArray rules = new JSONArray();
            // To the ENDPOINT, not to a direct outbound. This is the whole bug, and it is invisible
            // from every surface the app has: the profile declared a wireguard endpoint tagged
            // "warp", routed every packet to an outbound tagged "direct", and nothing anywhere
            // named the endpoint's tag. So the tunnel came up, installed its routes, and had nothing
            // to carry - which means the endpoint was never dialled, which means no handshake was
            // ever attempted, which is why every run reported isConnected=false and the relay saw
            // no packets at all. A full-device tunnel that routes nothing is indistinguishable from
            // a working one, and this is what that looks like.
            rules.put(new JSONObject()
                    .put("ip_cidr", new JSONArray().put("0.0.0.0/0").put("::/0"))
                    .put("action", "route")
                    .put("outbound", "warp"));

            JSONArray outbounds = new JSONArray();
            outbounds.put(new JSONObject()
                    .put("type", "direct")
                    .put("tag", "direct"));

            JSONObject root = new JSONObject();
            // debug, not warn. The engine's own log is the only place its WireGuard decisions appear
            // - which endpoint it dialled, whether the handshake was attempted, what it replied with -
            // and at warn the tunnel starts, sits silent, and carries nothing with nothing said. The
            // empty handshake measured on the device was invisible for exactly this reason: the app
            // saw a running tunnel and a reachable relay, and the engine said nothing at all.
            root.put("log", new JSONObject().put("level", "debug").put("timestamp", true));
            root.put("inbounds", inbounds(onlyIfLiteralIp(endpoint)));
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

    /**
     * The endpoint address, when it is a literal IP rather than a name.
     *
     * <p>A relay is configured by address, so this is what the tunnel has to leave outside itself.
     * Cloudflare's endpoint is a hostname, and a name is not something a route can exclude - the
     * engine resolves it and applies the same rule internally, so nothing is lost by passing null.
     */
    private static String onlyIfLiteralIp(String host) {
        if (host == null) return null;
        String trimmed = host.trim();
        if (trimmed.isEmpty()) return null;
        // Strip a port if one came along, and a [] wrapper around a v6 literal.
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        } else if (trimmed.indexOf(':') >= 0 && trimmed.indexOf(':') == trimmed.lastIndexOf(':')) {
            trimmed = trimmed.substring(0, trimmed.indexOf(':'));
        }
        // A v4 literal is four dot-separated numbers; anything else is a name.
        String[] parts = trimmed.split("\\.");
        if (parts.length == 4) {
            for (String part : parts) {
                if (part.isEmpty()) return null;
                for (int i = 0; i < part.length(); i++) {
                    if (part.charAt(i) < '0' || part.charAt(i) > '9') return null;
                }
            }
            return trimmed;
        }
        return null;
    }

    /** The address as a host prefix: /32 for v4, /128 for v6. */
    private static String hostPrefix(String address) {
        String trimmed = address.trim();
        return trimmed.indexOf(':') >= 0 ? trimmed + "/128" : trimmed + "/32";
    }

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
        return inbounds(null);
    }

    /**
     * The TUN inbound, exempting the endpoint when there is one.
     *
     * <p>{@code route_address: 0.0.0.0/0} plus {@code strict_route} makes the operating system hand
     * the device's traffic to the tunnel - including the tunnel's own WireGuard packets to the
     * endpoint. The engine then sends its handshake into the interface it is trying to build, and
     * the peer never sees an initiation. It looks exactly like a blocked network: the tunnel is up,
     * the routes are installed, and not one byte reaches anyone.
     *
     * <p>Measured on the device against a live relay: with the endpoint captured, the bare datagram
     * probe timed out - {@code SocketTimeoutException: the device does not reach the relay} - while
     * an unproxied probe from the same test reached it in under a second. The relay had been
     * answering all along; the tunnel was eating the reply.
     *
     * <p>The exclusion is read out of the engine rather than assumed. It declares
     * {@code RouteExcludeAddress json:"route_exclude_address,omitempty"} - the ampersand before the
     * tag is how an embedded struct is marked, and a listing that does not allow it reports the field
     * as absent. That is how it read as undeclared here and got removed, and the removal cost a
     * measurable regression: with the exclusion gone the relay became unreachable the moment there
     * was traffic to carry (SocketTimeoutException: the device does not reach the relay), which is
     * the tunnel swallowing its own handshake. With it present the same probe answers in under a
     * second. See ColgramVpnService, where the engine hands the list to the operating system.
     */
    private static JSONArray inbounds(String excludeEndpointIp) throws Exception {
        JSONArray inbounds = new JSONArray();
        JSONObject tun = new JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("interface_name", "colgram0")
                .put("address", new JSONArray()
                        .put("172.19.0.1/30")
                        .put("fdfe:dcba:9876::1/126"))
                // 1280, not 9000. The TUN advertised 9000 and the endpoint is configured for 1280,
                // so the engine padded its WireGuard messages up to the interface MTU instead of
                // sending them at the size the protocol requires. A padded initiation is not an
                // initiation: a real one is exactly 148 bytes, and a peer that only accepts that
                // length sees a 1200-byte foreign datagram and no session. Measured on the device
                // against a live relay: three 1200-byte packets forwarded, handshakes 0.
                .put("mtu", MTU)
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
                .put("strict_route", true);
        if (excludeEndpointIp != null && !excludeEndpointIp.trim().isEmpty()) {
            // A prefix, not a bare address - the engine says so by name and by value:
            //   inbounds[0].route_exclude_address: (json: cannot unmarshal array into Go value of
            //   type **netip.Prefix | netip.ParsePrefix("10.0.2.2"): no '/')
            // A host route is /32, or /128 for v6.
            tun.put("route_exclude_address",
                    new JSONArray().put(hostPrefix(excludeEndpointIp)));
        }
        inbounds.put(tun);
        inbounds.put(new JSONObject()
                .put("type", "mixed")
                .put("tag", "mixed-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", 2080));
        return inbounds;
    }
}
