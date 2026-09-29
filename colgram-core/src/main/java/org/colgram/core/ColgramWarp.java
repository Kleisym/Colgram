package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * ColgramWarp — Cloudflare WARP identity, provisioned in-app.
 *
 * WARP is WireGuard over UDP to Cloudflare's edge. The official client is blocked by TSPU
 * (it pins engage.cloudflareclient.com:2408, which the DPI drops), but the registration API
 * is plain HTTPS on Cloudflare's own anycast and answers fine from inside Russia — measured
 * 2026-09-26: POST /reg returns a structured 400 in 0.8s from this network. So Colgram
 * registers its own device, keeps the keys, and hands the profile to the embedded
 * WireGuard userspace backend (com.wireguard.android:tunnel), with endpoint rotation across
 * the alternate UDP ports Cloudflare's edge answers on — the same trick the Oblivion /
 * warp-plus tools use. This class is the identity half; the transport half is
 * {@link ColgramWarpTunnel}.
 *
 * Why wire the peer over HTTPS here at all: without a registration there is no WARP session,
 * and every third-party "free config" generator is a third party that sees the identity.
 * One HTTPS POST to Cloudflare and the keys never leave the device.
 */
public final class ColgramWarp {

    private static final String TAG = "ColgramWarp";
    private static final String PREFS = "colgram_warp";
    private static final String KEY_PRIVATE = "private_key_b64";
    private static final String KEY_REGISTRATION = "registration_json";
    private static final String KEY_ENDPOINT_INDEX = "endpoint_index";
    /** A relay that carries WireGuard for us, for networks that drop Cloudflare's UDP. */
    private static final String KEY_RELAY_ADDRESS = "relay_address";
    private static final String KEY_RELAY_PORT = "relay_port";
    /** The relay's own WireGuard public key, which is NOT Cloudflare's. */
    private static final String KEY_RELAY_PUBLIC_KEY = "relay_public_key";
    private static final String KEY_RELAY_PRESHARED_KEY = "relay_preshared_key";

    private static final String REG_URL = "https://api.cloudflareclient.com/v0a2158/reg";

    /** Alternate UDP ports, newest first. Fallback when the registration carries none:
     *  the API itself lists these in config.peers[0].endpoint.ports. */
    private static final int[] ALTERNATE_PORTS = {2408, 500, 1701, 4500};

    private static volatile int[] portCache = null;

    /** Ports the edge answers WireGuard on, taken from the registration when present. */
    private static int[] endpointPorts() {
        if (portCache != null) return portCache;
        String reg = getRegistration();
        int[] fallback = ALTERNATE_PORTS;
        if (reg != null) {
            try {
                org.json.JSONArray ports = new org.json.JSONObject(reg)
                        .getJSONObject("config").getJSONArray("peers").getJSONObject(0)
                        .getJSONObject("endpoint").optJSONArray("ports");
                if (ports != null && ports.length() > 0) {
                    java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < ports.length(); i++) {
                        int port = ports.optInt(i, 2408);
                        if (port > 0 && port <= 65535) unique.add(port);
                    }
                    if (!unique.isEmpty()) {
                        int[] out = new int[unique.size()];
                        int i = 0;
                        for (Integer port : unique) out[i++] = port;
                        portCache = out;
                        return out;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return fallback;
    }

    /** Number of distinct endpoint attempts advertised by Cloudflare for this registration. */
    public static int endpointPortCount() {
        return Math.max(1, endpointPorts().length);
    }

    /** Cloudflare's WireGuard peer key for every WARP registration. */
    public static final String WARP_PEER_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";

    private static volatile String cachedRegistration = null;
    private static volatile String cachedPrivateKey = null;

    private ColgramWarp() {}

    public static boolean isRegistered() {
        String reg = getRegistration();
        return reg != null && !reg.isEmpty();
    }

    public static String getRegistration() {
        if (cachedRegistration != null) return cachedRegistration;
        Context ctx = ColgramPythonEngine.appContext();
        if (ctx == null) return null;
        cachedRegistration = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REGISTRATION, null);
        return cachedRegistration;
    }

    public static String getPrivateKey() {
        if (cachedPrivateKey != null) return cachedPrivateKey;
        Context ctx = ColgramPythonEngine.appContext();
        if (ctx == null) return null;
        cachedPrivateKey = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PRIVATE, null);
        return cachedPrivateKey;
    }

    public static void deleteRegistration(Context ctx) {
        cachedRegistration = null;
        cachedPrivateKey = null;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /**
     * Register this device with WARP and persist the result.
     *
     * Network call — run off the main thread. Everything goes through {@link ColgramHttp},
     * so the registration survives the same filtered network the rest of the app does.
     *
     * @throws Exception with a human-readable message when Cloudflare refuses or is unreachable
     */
    public static synchronized void register(Context ctx) throws Exception {
        if (ctx == null) throw new Exception("нет контекста приложения");
        // One identity per device: re-registering would orphan the previous account.
        if (isRegistered()) return;

        byte[] privateKey = new byte[32];
        new SecureRandom().nextBytes(privateKey);
        byte[] publicKey = X25519.publicKey(privateKey);
        String pubB64 = android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP);
        String privB64 = android.util.Base64.encodeToString(privateKey, android.util.Base64.NO_WRAP);

        JSONObject body = new JSONObject();
        body.put("fcm_token", "");
        body.put("install_id", "");
        body.put("tos", "2024-06-01T00:00:00.000Z");
        body.put("model", "Android");
        body.put("serial_number", UUID.randomUUID().toString());
        body.put("language", "en_US");
        body.put("locale", "en_US");
        body.put("region", "US");
        body.put("warp_enabled", true);
        body.put("enabled", true);
        body.put("device_type", "Android");
        body.put("key", pubB64);

        ColgramHttp.Response r = ColgramHttp.post(REG_URL, body.toString(), null);
        if (r.code == 409) throw new Exception("устройство уже зарегистрировано");
        if (r.code == 429) throw new Exception("Cloudflare ограничил запросы, попробуйте позже");
        if (r.code < 200 || r.code >= 300) {
            throw new Exception("Cloudflare ответил HTTP " + r.code);
        }
        JSONObject resp = new JSONObject(r.body);
        if (resp.optJSONObject("config") == null
                || resp.optJSONObject("config").optJSONArray("peers") == null) {
            throw new Exception("ответ без конфигурации туннеля");
        }
        if (reservedBytes(resp) == null) {
            throw new Exception("Cloudflare не вернул трёхбайтовый client_id для WARP");
        }

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PRIVATE, privB64)
                .putString(KEY_REGISTRATION, resp.toString())
                .apply();
        cachedPrivateKey = privB64;
        cachedRegistration = resp.toString();
        Log.i(TAG, "WARP registration stored (account " + resp.optString("id") + ")");
    }

    /** The endpoint host Cloudflare assigned this registration (usually engage.cloudflareclient.com). */
    public static String endpointHost() {
        String reg = getRegistration();
        if (reg == null) return "engage.cloudflareclient.com";
        try {
            JSONObject cfg = new JSONObject(reg).optJSONObject("config");
            if (cfg != null && cfg.optJSONArray("peers") != null
                    && cfg.getJSONArray("peers").length() > 0) {
                JSONObject ep = cfg.getJSONArray("peers").getJSONObject(0).optJSONObject("endpoint");
                if (ep != null) {
                    String host = ep.optString("host", "");
                    if (host.contains(":")) host = host.substring(0, host.indexOf(':'));
                    if (!host.isEmpty()) return host;
                }
            }
        } catch (Throwable ignored) {}
        return "engage.cloudflareclient.com";
    }

    /** The 3 reserved bytes Cloudflare assigns this identity, or null for none. */
    public static String reservedHex() {
        byte[] reserved = reservedBytes();
        if (reserved == null) return null;
        StringBuilder sb = new StringBuilder(6);
        for (byte b : reserved) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /**
     * The interface addresses Cloudflare assigned, as "v4,v6" without prefix lengths.
     *
     * The engine's WireGuard endpoint takes these itself, so the profile builder needs them
     * separately from the wg-quick text: the endpoint adds the /32 and /128 that the quick
     * format would have spelled out, and getting that wrong is a config the engine refuses.
     */
    public static String interfaceAddresses() {
        String reg = getRegistration();
        if (reg == null || reg.isEmpty()) return null;
        try {
            JSONObject addrs = new JSONObject(reg).getJSONObject("config")
                    .getJSONObject("interface").getJSONObject("addresses");
            String v4 = addrs.optString("v4", "").trim();
            String v6 = addrs.optString("v6", "").trim();
            if (v4.isEmpty() && v6.isEmpty()) return null;
            if (v6.isEmpty()) return v4;
            if (v4.isEmpty()) return "" + v6;
            return v4 + "," + v6;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Cloudflare places this 24-bit client identifier in WireGuard's reserved header bytes. */
    public static byte[] reservedBytes() {
        String reg = getRegistration();
        if (reg == null || reg.isEmpty()) return null;
        try {
            return reservedBytes(new JSONObject(reg));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] reservedBytes(JSONObject registration) {
        try {
            JSONObject config = registration.optJSONObject("config");
            String clientId = config == null ? "" : config.optString("client_id", "");
            if (clientId.isEmpty()) clientId = registration.optString("client_id", "");
            if (!clientId.isEmpty()) {
                byte[] bytes = android.util.Base64.decode(clientId, android.util.Base64.NO_WRAP);
                if (bytes.length == 3) return bytes;
            }
            org.json.JSONArray reserved = config == null ? null : config.optJSONArray("reserved");
            if (reserved == null) reserved = registration.optJSONArray("reserved");
            if (reserved == null || reserved.length() != 3) return null;
            byte[] bytes = new byte[3];
            for (int i = 0; i < bytes.length; i++) {
                int value = reserved.optInt(i, -1);
                if (value < 0 || value > 255) return null;
                bytes[i] = (byte) value;
            }
            return bytes;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Next UDP port to try, in rotation. Persisted so a working port survives restarts. */
    public static synchronized int nextEndpointPort() {
        Context ctx = ColgramPythonEngine.appContext();
        SharedPreferences prefs = ctx == null ? null : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int i = prefs != null ? prefs.getInt(KEY_ENDPOINT_INDEX, 0) : 0;
        int[] ports = endpointPorts();
        int port = ports[Math.floorMod(i, ports.length)];
        if (prefs != null) prefs.edit().putInt(KEY_ENDPOINT_INDEX, i + 1).apply();
        return port;
    }

    public static int currentEndpointPort() {
        Context ctx = ColgramPythonEngine.appContext();
        SharedPreferences prefs = ctx == null ? null : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int i = prefs != null ? prefs.getInt(KEY_ENDPOINT_INDEX, 0) : 0;
        int[] ports = endpointPorts();
        return ports[Math.floorMod(i, ports.length)];
    }

    /**
     * Point WARP at a relay instead of Cloudflare's own ingress.
     *
     * On a network that drops Cloudflare's UDP there is no port, no protocol version and no
     * client-side trick that reaches the real endpoint: WireGuard has no TCP transport, and
     * measured on this device every endpoint is silent while UDP itself is alive. What works is
     * a relay the user runs elsewhere, which accepts the WireGuard handshake over TCP and
     * forwards it to Cloudflare. The relay's public key is its own, not WARP's, which is why it
     * is configured here rather than reused.
     *
     * Empty values clear the relay and return to Cloudflare directly.
     */
    public static boolean setRelay(String address, int port, String publicKey,
                                  String presharedKey) {
        Context ctx = ColgramPythonEngine.appContext();
        if (ctx == null) return false;
        SharedPreferences.Editor editor =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (address == null || address.trim().isEmpty() || port <= 0) {
            editor.remove(KEY_RELAY_ADDRESS).remove(KEY_RELAY_PORT)
                    .remove(KEY_RELAY_PUBLIC_KEY).remove(KEY_RELAY_PRESHARED_KEY);
        } else {
            editor.putString(KEY_RELAY_ADDRESS, address.trim()).putInt(KEY_RELAY_PORT, port);
            putOrRemove(editor, KEY_RELAY_PUBLIC_KEY, publicKey);
            putOrRemove(editor, KEY_RELAY_PRESHARED_KEY, presharedKey);
        }
        // commit(), not apply(). apply() returns before the write has landed, and everything this
        // method enables reads the values back immediately: the profile builder calls relayPublicKey()
        // to pin the peer. With apply() the read can beat the write, the profile goes out carrying
        // Cloudflare's peer key while naming the relay's address, and the relay rejects the
        // handshake - which is indistinguishable, from the outside, from a relay that is simply
        // broken. Measured on the device, as a profile that named the relay and then failed the
        // assertion that it carries the relay's key.
        editor.commit();
        // The port list is cached; a relay has to take effect on the next start.
        portCache = null;
        return true;
    }

    private static void putOrRemove(SharedPreferences.Editor editor, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, value.trim());
        }
    }

    public static boolean hasRelay() {
        return relayAddress() != null;
    }

    /**
     * A relay is only usable when its WireGuard key is here too.
     *
     * <p>{@link #hasRelay()} asks about the address alone, which is enough to decide that traffic
     * should go somewhere other than Cloudflare. It is NOT enough to build a profile that connects:
     * the peer has to be pinned to the relay's own public key, and with that key missing the builder
     * falls back to the well-known Cloudflare one, producing a profile that names the relay and can
     * never complete a handshake with it. So anything that decides whether to build a relay profile
     * asks this instead, and a half-configured relay is treated as no relay rather than as a broken
     * one.
     */
    public static boolean hasUsableRelay() {
        String key = relayPublicKey();
        return hasRelay() && key != null && !key.trim().isEmpty();
    }

    public static String relayAddress() {
        Context ctx = ColgramPythonEngine.appContext();
        if (ctx == null) return null;
        String address = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RELAY_ADDRESS, null);
        return address == null || address.trim().isEmpty() ? null : address.trim();
    }

    public static int relayPort() {
        Context ctx = ColgramPythonEngine.appContext();
        return ctx == null ? 0
                : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getInt(KEY_RELAY_PORT, 0);
    }

    public static String relayPublicKey() {
        Context ctx = ColgramPythonEngine.appContext();
        return ctx == null ? null : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RELAY_PUBLIC_KEY, null);
    }

    public static String relayPresharedKey() {
        Context ctx = ColgramPythonEngine.appContext();
        return ctx == null ? null : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RELAY_PRESHARED_KEY, null);
    }

    /** A short description for the settings row, so it never claims a relay it does not have. */
    public static String relaySummary() {
        String address = relayAddress();
        return address == null ? null : address + ":" + relayPort();
    }

    /**
     * X25519 (RFC 7748) on BigInteger.
     *
     * Why not java.security: "X25519" KeyAgreement only exists on API 33+, and Conscrypt's
     * is not guaranteed on OEM builds below that. Key generation happens once per device,
     * so a plain RFC 7748 Montgomery ladder costs nothing in practice and runs everywhere.
     * Only the client-side half lives here — the tunnel's own handshake is done by the
     * WireGuard backend, which has its own implementation.
     */
    public static final class X25519 {
        private static final java.math.BigInteger P =
                java.math.BigInteger.TWO.pow(255).subtract(java.math.BigInteger.valueOf(19));
        private static final java.math.BigInteger A24 =
                java.math.BigInteger.valueOf(121665);

        private X25519() {}

        /** Public key = scalar multiply of the base point (u = 9) by the private scalar. */
        public static byte[] publicKey(byte[] privateKey32) {
            byte[] base = new byte[32];
            base[0] = 9;
            return scalarMult(privateKey32, base);
        }

        public static byte[] scalarMult(byte[] scalar32, byte[] uCoordinate32) {
            java.math.BigInteger k = decodeScalar(scalar32);
            java.math.BigInteger u = decodeUCoordinate(uCoordinate32);
            java.math.BigInteger x1 = u;
            java.math.BigInteger x2 = java.math.BigInteger.ONE;
            java.math.BigInteger z2 = java.math.BigInteger.ZERO;
            java.math.BigInteger x3 = x1;
            java.math.BigInteger z3 = java.math.BigInteger.ONE;
            boolean swap = false;
            for (int t = 254; t >= 0; --t) {
                boolean kt = k.testBit(t);
                if (swap != kt) {
                    java.math.BigInteger tx = x2; x2 = x3; x3 = tx;
                    java.math.BigInteger tz = z2; z2 = z3; z3 = tz;
                }
                swap = kt;
                java.math.BigInteger a = x2.add(z2).mod(P);
                java.math.BigInteger aa = a.multiply(a).mod(P);
                java.math.BigInteger b = x2.subtract(z2).mod(P);
                java.math.BigInteger bb = b.multiply(b).mod(P);
                java.math.BigInteger e = aa.subtract(bb).mod(P);
                java.math.BigInteger c = x3.add(z3).mod(P);
                java.math.BigInteger d = x3.subtract(z3).mod(P);
                java.math.BigInteger da = d.multiply(a).mod(P);
                java.math.BigInteger cb = c.multiply(b).mod(P);
                x3 = da.add(cb).modPow(java.math.BigInteger.ONE, P).pow(2).mod(P);
                z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P);
                x2 = aa.multiply(bb).mod(P);
                z2 = e.multiply(aa.add(A24.multiply(e))).mod(P);
            }
            if (swap) {
                java.math.BigInteger tx = x2; x2 = x3; x3 = tx;
                java.math.BigInteger tz = z2; z2 = z3; z3 = tz;
            }
            java.math.BigInteger res = x2.multiply(z2.modPow(P.subtract(java.math.BigInteger.TWO), P)).mod(P);
            return encodeU(res);
        }

        private static java.math.BigInteger decodeScalar(byte[] s) {
            byte[] k = s.clone();
            k[0] &= 248;
            k[31] &= 127;
            k[31] |= 64;
            return littleEndian(k);
        }

        private static java.math.BigInteger decodeUCoordinate(byte[] u) {
            byte[] copy = u.clone();
            copy[31] &= 127; // mask the high bit; RFC 7748 §5 for 25519
            return littleEndian(copy);
        }

        private static byte[] encodeU(java.math.BigInteger v) {
            byte[] out = new byte[32];
            byte[] le = v.toByteArray();
            // BigInteger.toByteArray is big-endian with a possible sign byte; reverse into LE.
            int j = 0;
            for (int i = le.length - 1; i >= 0 && j < 32; i--) out[j++] = le[i];
            return out;
        }

        private static java.math.BigInteger littleEndian(byte[] in) {
            byte[] be = new byte[in.length];
            for (int i = 0; i < in.length; i++) be[i] = in[in.length - 1 - i];
            return new java.math.BigInteger(1, be);
        }
    }
}
