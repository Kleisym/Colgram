package org.colgram.core;

import android.util.Base64;
import android.util.Log;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the subscription formats a VPN bot actually hands out.
 *
 * The user buys a subscription in a bot and pastes the link here, so the parser has to accept what
 * those bots emit rather than one tidy variant. Three shapes are in the wild and all three are
 * real: base64 of a newline-separated list, the list already decoded one URI per line, and the
 * `proxies:` block of a Clash YAML.
 *
 * Everything reduces to a {@link Node}: protocol, address, port, credential, name. Nothing is
 * dialled here, so a format this parser has never seen costs a log line rather than a crash.
 * Deciding which nodes actually work is the tunnel's job and has to be: a node that parses
 * perfectly can still speak a protocol the device cannot use.
 */
public final class ColgramSubscription {

    private static final String TAG = "ColgramSub";

    public static final String VLESS = "vless";
    public static final String VMESS = "vmess";
    public static final String TROJAN = "trojan";
    public static final String SHADOWSOCKS = "ss";
    public static final String HYSTERIA = "hysteria";
    public static final String HYSTERIA2 = "hysteria2";
    public static final String SOCKS = "socks";
    public static final String HTTP = "http";
    public static final String WIREGUARD = "wireguard";

    private ColgramSubscription() {}

    /** One parsed server. Fields a given scheme does not carry stay null or zero. */
    public static final class Node {
        public final String protocol;
        public final String address;
        public final int port;
        public String uuid;
        public String password;
        public String method;
        public int network;
        public String host;
        public String path;
        public String sni;
        public String security;
        public String publicKey;
        public String shortId;
        public String flow;
        public String name;
        /** True when the node carries enough to be dialled. */
        public final boolean usable;

        Node(String protocol, String address, int port, boolean usable) {
            this.protocol = protocol;
            this.address = address;
            this.port = port;
            this.usable = usable;
        }

        @Override
        public String toString() {
            return protocol + "://" + address + ":" + port + (name == null ? "" : " (" + name + ")");
        }
    }

    /**
     * Parse whatever the bot returned. Never throws: an unreadable paste yields an empty list and
     * a log line, so the caller can tell the user instead of the app dying on their clipboard.
     */
    public static List<Node> parse(String body) {
        List<Node> found = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return found;
        String text = body.trim();

        if (text.startsWith("proxies:") || text.contains("\nproxies:")) {
            found.addAll(parseClash(text));
            if (!found.isEmpty()) return found;
        }

        String decoded = tryBase64(text);
        String source = decoded != null ? decoded : text;
        for (String line : source.split("[\\r\\n]+")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
            Node node = parseUri(trimmed);
            if (node != null) found.add(node);
        }
        Log.i(TAG, found.isEmpty()
                ? "nothing parsed from " + source.length() + " chars"
                : "parsed " + found.size() + " nodes");
        return found;
    }

    /**
     * Does this text even claim to be a VPN subscription?
     *
     * A cheap prefix test, for the place that has to decide whether to treat a blob of text as a
     * subscription at all - a link shared from a chat, where the blob is usually a caption with a
     * link somewhere inside it. parse() is the authority and is what decides; this only avoids
     * handing it an ordinary http link, and it is deliberately the same scheme list parseUri
     * understands so the two cannot drift apart.
     */
    public static boolean looksLikeSubscription(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd <= 0) return false;
        String scheme = trimmed.substring(0, schemeEnd).toLowerCase(java.util.Locale.ROOT);
        return VLESS.equals(scheme) || VMESS.equals(scheme) || TROJAN.equals(scheme)
                || SHADOWSOCKS.equals(scheme) || "shadowsocks".equals(scheme)
                || "ssr".equals(scheme) || HYSTERIA.equals(scheme) || "hysteria2".equals(scheme)
                || "hy2".equals(scheme) || "socks".equals(scheme) || "socks5".equals(scheme);
    }

    /**
     * The first subscription link inside a blob of text, or null when there is none.
     *
     * A bot does not send a bare URI. It sends a message with a caption, sometimes a payment link,
     * an expiry note and the subscription somewhere in the middle - so a link that arrives by being
     * shared has to be found inside that text rather than taken whole. Anything the parser does not
     * recognise is skipped, which is what keeps an ordinary http link in a shared message from
     * being read as a VPN config and replacing a working tunnel with a nonsense one.
     */
    public static String firstLinkIn(String text) {
        if (text == null) return null;
        for (String candidate : text.split("[\\s\\r\\n]+")) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) continue;
            if (!looksLikeSubscription(trimmed)) continue;
            if (!parse(trimmed).isEmpty()) return trimmed;
        }
        // And a base64 subscription list, which is the other shape a bot sends: one long token
        // rather than a vless:// URI.
        String whole = text.trim();
        if (!whole.isEmpty() && whole.indexOf("://") < 0 && !parse(whole).isEmpty()) {
            return whole;
        }
        return null;
    }

    /** One URI, in any of the schemes the wild uses. */
    static Node parseUri(String uri) {
        try {
            int schemeEnd = uri.indexOf("://");
            if (schemeEnd <= 0) return null;
            String scheme = uri.substring(0, schemeEnd).toLowerCase(java.util.Locale.ROOT);
            // Both spellings are in the wild, and the two spellings mean the same protocol. Left
            // unnormalised, a socks5:// node reached the profile builder as an unknown scheme and
            // was dropped - the user would see a subscription that silently lost its entries.
            if ("socks5".equals(scheme)) scheme = SOCKS;
            String rest = uri.substring(schemeEnd + 3);

            // The fragment is after the parameters and is the only human-readable name.
            String name = null;
            int hash = rest.indexOf('#');
            if (hash >= 0) {
                name = urlDecode(rest.substring(hash + 1));
                rest = rest.substring(0, hash);
            }

            String query = "";
            int mark = rest.indexOf('?');
            if (mark >= 0) {
                query = rest.substring(mark + 1);
                rest = rest.substring(0, mark);
            }

            // vmess carries a base64 JSON after the scheme - a different shape entirely, and
            // it has to be handled before the host:port split would take it apart wrongly.
            if (VMESS.equals(scheme)) {
                return parseVmess(rest, name);
            }

            String credentials = "";
            int at = rest.lastIndexOf('@');
            if (at >= 0) {
                credentials = rest.substring(0, at);
                rest = rest.substring(at + 1);
            }

            int slash = rest.indexOf('/');
            if (slash >= 0) rest = rest.substring(0, slash);

            String host;
            int port;
            int colon = rest.lastIndexOf(':');
            if (colon > 0) {
                host = stripBrackets(rest.substring(0, colon));
                port = parseInt(rest.substring(colon + 1));
            } else {
                host = stripBrackets(rest);
                port = 0;
            }
            if (host.isEmpty()) return null;

            Map<String, String> options = parseQuery(query);
            Node node = new Node(scheme, host, port, port > 0);
            node.name = name;
            node.path = options.get("path");
            node.host = options.get("host");
            node.sni = options.get("sni") != null ? options.get("sni") : options.get("peer");
            node.security = options.get("security");
            node.flow = options.get("flow");
            node.publicKey = options.get("pbk");
            node.shortId = options.get("sid");
            node.network = "ws".equals(options.get("type")) ? 1 : 0;
            if (!credentials.isEmpty()) {
                // Shadowsocks base64 wraps "method:password", not a bare password, and getting
                // that wrong hands the tunnel a cipher where the secret belongs.
                String plain = tryBase64(credentials);
                String raw = plain != null ? plain : urlDecode(credentials);
                if (SHADOWSOCKS.equals(scheme) && raw.contains(":")) {
                    int split = raw.indexOf(':');
                    node.method = raw.substring(0, split);
                    node.password = raw.substring(split + 1);
                } else {
                    node.uuid = raw;
                    node.password = raw;
                }
            }
            if (options.containsKey("obfs-password")) node.password = options.get("obfs-password");
            // Only fill the cipher from a parameter when the credentials did not already carry one.
            // Assigning it unconditionally wiped the method shadowsocks had just decoded, so every
            // such node reached the tunnel with a null cipher and could not be used.
            if (node.method == null && options.containsKey("encryption")) {
                node.method = options.get("encryption");
            }
            return node;
        } catch (Throwable t) {
            Log.i(TAG, "unreadable node: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** vmess:// is base64 of a small JSON object, not a URI. */
    private static Node parseVmess(String payload, String fallbackName) {
        String json = tryBase64(payload);
        if (json == null) return null;
        try {
            Map<String, String> map = tinyJson(json);
            String host = map.get("add");
            int port = parseInt(map.get("port"));
            if (host == null || host.isEmpty()) return null;
            Node node = new Node(VMESS, host, port, port > 0);
            node.uuid = map.get("id");
            node.path = map.get("path");
            node.host = map.get("host");
            node.sni = map.get("sni");
            node.method = map.get("scy");
            node.name = map.get("ps") != null ? map.get("ps") : fallbackName;
            node.security = map.get("tls");
            node.network = "ws".equals(map.get("net")) ? 1 : 0;
            return node;
        } catch (Throwable t) {
            Log.i(TAG, "vmess payload unreadable: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The `proxies:` block of a Clash config.
     *
     * Read with a purpose-built line scanner rather than a YAML library: colgram-core compiles
     * against a bare android.jar and has no YAML dependency, and this is a flat list of maps
     * rather than a document that needs a real parser.
     */
    static List<Node> parseClash(String yaml) {
        List<Node> found = new ArrayList<>();
        Map<String, String> current = null;
        for (String raw : yaml.split("[\\r\\n]+")) {
            String line = raw.trim();
            if (line.equals("proxies:")) {
                current = null;
                continue;
            }
            if (line.startsWith("- ")) {
                if (current != null) {
                    Node node = fromClashEntry(current);
                    if (node != null) found.add(node);
                }
                current = new LinkedHashMap<>();
                line = line.substring(2).trim();
            } else if (line.isEmpty() || current == null) {
                continue;
            } else if (!raw.startsWith(" ") && !raw.startsWith("- ")) {
                // A new top-level key ends the list.
                current = null;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = unquote(line.substring(0, colon).trim());
            String value = unquote(line.substring(colon + 1).trim());
            current.put(key, value);
        }
        if (current != null) {
            Node node = fromClashEntry(current);
            if (node != null) found.add(node);
        }
        return found;
    }

    private static Node fromClashEntry(Map<String, String> entry) {
        String type = entry.get("type");
        String server = entry.get("server");
        if (type == null || server == null || server.isEmpty()) return null;
        int port = parseInt(entry.get("port"));
        String protocol = type.toLowerCase(java.util.Locale.ROOT);
        // Clash spells socks5 "socks5"; the tunnel wants a scheme name.
        if ("socks5".equals(protocol)) protocol = SOCKS;
        Node node = new Node(protocol, server, port, port > 0);
        node.name = entry.get("name");
        node.uuid = entry.get("uuid");
        node.password = entry.get("password");
        node.method = entry.get("cipher");
        node.sni = entry.get("sni");
        node.publicKey = entry.get("public-key");
        node.shortId = entry.get("short-id");
        node.flow = entry.get("flow");
        node.network = "ws".equals(entry.get("network")) ? 1 : 0;
        node.path = entry.get("ws-path");
        return node;
    }

    private static String stripBrackets(String value) {
        String out = value.trim();
        if (out.startsWith("[")) out = out.substring(1);
        if (out.endsWith("]")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String unquote(String value) {
        String out = value.trim();
        if (out.length() >= 2 && ((out.startsWith("\"") && out.endsWith("\""))
                || (out.startsWith("'") && out.endsWith("'")))) {
            out = out.substring(1, out.length() - 1);
        }
        return out;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            out.put(urlDecode(pair.substring(0, equals)), urlDecode(pair.substring(equals + 1)));
        }
        return out;
    }

    /** Decode when the text is base64 and the result is actually text. */
    private static String tryBase64(String value) {
        String candidate = value.replace("\n", "").replace("\r", "").trim();
        if (candidate.isEmpty()) return null;
        try {
            byte[] decoded = Base64.decode(candidate, Base64.DEFAULT);
            String text = new String(decoded, "UTF-8");
            // A blob that decodes to nonsense is not a subscription. Treating it as one would
            // turn a pasted certificate into a screen full of empty nodes.
            // A JSON object counts as content too: a vmess payload is base64'd JSON, and
            // requiring a colon or a scheme here silently discarded every vmess node the user
            // had paid for.
            if (!text.contains("://") && !text.contains(":")
                    && !(text.startsWith("{") && text.contains("add"))) {
                return null;
            }
            return text;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Just enough JSON for a flat object of strings, which is all a vmess payload is.
     *
     * A real parser is deliberately avoided: this cannot be thrown off by an escaped quote in a
     * way that a subscription ever does, and it keeps colgram-core dependency-free.
     */
    private static Map<String, String> tinyJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < json.length()) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = json.substring(keyStart + 1, keyEnd);
            int colon = json.indexOf(':', keyEnd);
            if (colon < 0) break;
            int valueStart = colon + 1;
            while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;
            String value;
            int valueEnd;
            if (valueStart < json.length() && json.charAt(valueStart) == '"') {
                valueStart++;
                valueEnd = json.indexOf('"', valueStart);
                if (valueEnd < 0) break;
                value = json.substring(valueStart, valueEnd);
            } else {
                valueEnd = valueStart;
                while (valueEnd < json.length()
                        && ",} \t\r\n".indexOf(json.charAt(valueEnd)) < 0) {
                    valueEnd++;
                }
                value = json.substring(valueStart, valueEnd);
            }
            out.put(key, value);
            // A flat object has one brace, not one per pair. Seeking the next '{' after the
            // first value ended the scan there, so every vmess payload parsed as a single pair
            // and the address came back null - the node was silently dropped.
            i = Math.max(valueEnd, keyEnd) + 1;
        }
        return out;
    }
}
