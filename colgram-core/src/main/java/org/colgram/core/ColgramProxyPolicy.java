package org.colgram.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Android-independent, deterministic rules shared by feed ingestion and proxy selection. */
public final class ColgramProxyPolicy {
    private ColgramProxyPolicy() {}

    public static final class Endpoint {
        public final String host;
        public final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** Parses one public endpoint. A feed's declared protocol never changes because of its body. */
    public static Endpoint parseEndpoint(String line, String expectedScheme) {
        return parseEndpoint(line, expectedScheme, false);
    }

    /** Country columns are enabled only for a source whose format is explicitly known. */
    public static Endpoint parseEndpoint(String line, String expectedScheme, boolean countryColumn) {
        if (line == null || expectedScheme == null) return null;
        String scheme = expectedScheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("socks5") && !scheme.equals("http")) return null;
        String value = line.trim();
        if (countryColumn) {
            java.util.regex.Matcher metadata = java.util.regex.Pattern.compile(
                    "^([0-9.]+:[0-9]{1,5}):[A-Za-z][A-Za-z .()'-]{1,79}$").matcher(value);
            if (metadata.matches()) value = metadata.group(1);
        }
        if (value.isEmpty() || value.length() > 512 || value.indexOf('\\') >= 0) return null;
        try {
            boolean explicitScheme = value.contains("://");
            URI uri = new URI(explicitScheme ? value : scheme + "://" + value);
            if (!scheme.equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !(explicitScheme && path.equals("/"))) return null;
            String host = normalizeHost(uri.getHost());
            int port = uri.getPort();
            if (host == null || port < 1 || port > 65535 || !isPublicHost(host)) return null;
            return new Endpoint(host, port);
        } catch (Exception malformed) {
            return null;
        }
    }

    /** Validation does not resolve a hostname or perform network I/O. */
    public static String normalizeHost(String host) {
        if (host == null || host.isEmpty() || !host.equals(host.trim())) return null;
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.indexOf(':') >= 0) {
            if (value.indexOf('%') >= 0 || !value.matches("[0-9a-f:.]+")) return null;
            try {
                // A colon makes this a numeric IPv6 literal, so getByName never queries DNS.
                return InetAddress.getByName(value).getHostAddress();
            } catch (Exception malformed) {
                return null;
            }
        }
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.matches("[0-9.]+")) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) return null;
            for (String octet : octets) {
                if (octet.isEmpty() || octet.length() > 3 || (octet.length() > 1 && octet.charAt(0) == '0')) return null;
                try {
                    if (Integer.parseInt(octet) > 255) return null;
                } catch (NumberFormatException malformed) {
                    return null;
                }
            }
            return value;
        }
        try {
            value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (value.isEmpty() || value.length() > 253) return null;
        for (String label : value.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return null;
        }
        return value;
    }

    public static boolean isPublicHost(String host) {
        host = normalizeHost(host);
        if (host == null) return false;
        if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) {
            try {
                return isPublicAddress(InetAddress.getByName(host));
            } catch (Exception malformed) {
                return false;
            }
        }
        return host.contains(".") && !host.equals("localhost") && !host.endsWith(".localhost")
                && !host.endsWith(".local") && !host.endsWith(".internal");
    }

    public static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 0xe0) == 0x20;
        int a = bytes[0] & 255, b = bytes[1] & 255;
        return a != 0 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 198 && (b == 18 || b == 19));
    }

    public static final class State {
        final boolean verified;
        final boolean available;
        final int ping;
        final int tcp;
        final long eligibleAt;
        final String host;
        final int port;
        final int type;

        public State(boolean verified, boolean available, int ping, int tcp, long eligibleAt,
                     String host, int port, int type) {
            this.verified = verified;
            this.available = available;
            this.ping = ping;
            this.tcp = tcp;
            this.eligibleAt = eligibleAt;
            this.host = host;
            this.port = port;
            this.type = type;
        }

        int tier(long now) {
            if (eligibleAt > now) return 4;
            if (verified && available) return 0;
            if (ping == -2 || tcp == -2) return 3;
            if (tcp >= 0) return 1;
            return 2;
        }

        int latency() {
            return ping >= 0 ? ping : (tcp >= 0 ? tcp : Integer.MAX_VALUE);
        }
    }

    public static int compare(State a, State b, long now) {
        int comparison = Integer.compare(a.tier(now), b.tier(now));
        if (comparison == 0) comparison = Integer.compare(a.latency(), b.latency());
        if (comparison == 0) comparison = a.host.compareToIgnoreCase(b.host);
        if (comparison == 0) comparison = Integer.compare(a.port, b.port);
        if (comparison == 0) comparison = Integer.compare(a.type, b.type);
        return comparison;
    }

    /** TCP-open and a previous process's verdict are not current protocol availability. */
    public static boolean isSelectable(State state, long checkedAt, long now, long maximumAge) {
        return state != null && state.verified && state.available && state.ping >= 0
                && state.tcp != -2 && state.eligibleAt <= now && checkedAt > 0
                && checkedAt <= now && maximumAge > 0 && now - checkedAt < maximumAge;
    }

    /** TCP-dead slots must rotate even when they never consumed a native check slot. */
    public static boolean isReplaceable(State state, int protocolFailures, int failureThreshold) {
        return state != null && !state.available
                && (state.tcp == -2 || protocolFailures >= failureThreshold);
    }

    /** A bounded rotating window makes later feeds reachable without fetching every source. */
    public static List<Integer> feedIndices(int count, int budget, int round) {
        List<Integer> indices = new ArrayList<>();
        if (count <= 0 || budget <= 0) return indices;
        int take = Math.min(count, budget);
        int start = (int) Math.floorMod((long) round * take, (long) count);
        for (int i = 0; i < take; i++) indices.add((start + i) % count);
        return indices;
    }

    public static long startupDelay(long startedAt, long now) {
        return Math.max(0L, 15_000L - Math.max(0L, now - startedAt));
    }

    /** Caps bytes before decoding, including a hostile response with no newline. */
    public static String readBounded(InputStream input, int maximumBytes) throws IOException {
        return readBounded(input, maximumBytes, System.nanoTime() + 10_000_000_000L);
    }

    static String readBounded(InputStream input, int maximumBytes, long deadlineNanos) throws IOException {
        if (input == null) return "";
        if (maximumBytes < 0) throw new IllegalArgumentException("negative response budget");
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (;;) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos >= 0)
                    throw new IOException("feed read deadline exceeded");
                int count = stream.read(buffer);
                if (System.nanoTime() - deadlineNanos >= 0) throw new IOException("feed read deadline exceeded");
                if (count == -1) break;
                if (count == 0) throw new IOException("feed stream made no progress");
                if (count > maximumBytes - output.size()) throw new IOException("feed exceeds " + maximumBytes + " bytes");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
