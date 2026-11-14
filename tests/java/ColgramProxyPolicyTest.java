package org.colgram.core;

import java.util.Arrays;

/** Real JVM tests; no Android classes, mocks, network or third-party test runner. */
public final class ColgramProxyPolicyTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static ColgramProxyPolicy.Endpoint endpoint(String line, String scheme) {
        return ColgramProxyPolicy.parseEndpoint(line, scheme);
    }

    public static void main(String[] args) throws Exception {
        check(endpoint("1.2.3.4:1", "socks5").port == 1, "single-digit port accepted");
        check(endpoint("SOCKS5://Proxy.Example:1080", "socks5").host.equals("proxy.example"), "normalized host");
        check(endpoint("[2606:4700::1111]:65535", "socks5").port == 65535, "bracketed IPv6");
        check(endpoint("socks5://[2606:4700::1111]:1080/", "socks5") != null, "URI IPv6");
        check(!ColgramProxyPolicy.isPublicHost("192.168.0.4"), "public feed links cannot target LAN hosts");
        check(!ColgramProxyPolicy.isPublicAddress(java.net.InetAddress.getByAddress(new byte[]{127, 0, 0, 2})),
                "resolved loopback address is rejected before TCP connect");
        check(!ColgramProxyPolicy.isPublicHost("::ffff:10.0.0.1"), "IPv4-mapped private host is rejected");
        check(endpoint("http://8.8.8.8:80", "http") != null, "HTTP relay supported");
        check(ColgramProxyPolicy.parseEndpoint("8.8.8.8:1080:United States", "socks5", true) != null,
                "explicit country-column feed adapter");
        check(ColgramProxyPolicy.parseEndpoint("8.8.8.8:1080:user:password", "socks5", true) == null,
                "country-column adapter must not consume authentication fields");
        for (String bad : new String[]{"http://1.2.3.4:80", "https://1.2.3.4:443",
                "socks4://1.2.3.4:1080", "ss://abc", "vless://abc", "<html>1.2.3.4:80</html>",
                "1.2.3.999:80", "01.2.3.4:80", "1.2.3.4:0", "1.2.3.4:65536", "1.2.3.4:-1",
                "1.2.3.4:2147483648", "1.2.3.4:1080:username:password", "foo_bar.example:80",
                "-bad.example:80", "localhost:80", "127.0.0.1:80", "10.0.0.1:80", "192.168.1.1:80",
                "172.16.0.1:80", "169.254.1.2:80", "[::1]:80", "[fe80::1%lo]:80", "[fc00::1]:80",
                "8.8.8.8:80?x=1", "socks5://u:p@8.8.8.8:80", "8.8.8.8:80/path", ""}) {
            check(endpoint(bad, "socks5") == null, "reject malformed/non-SOCKS/public endpoint: " + bad);
        }
        ColgramProxyPolicy.State healthy = new ColgramProxyPolicy.State(true, true, 500, -1, 0, "z", 80, 0);
        ColgramProxyPolicy.State tcpOnly = new ColgramProxyPolicy.State(false, false, -1, 1, 0, "a", 80, 0);
        ColgramProxyPolicy.State unknown = new ColgramProxyPolicy.State(false, false, -1, -1, 0, "b", 80, 0);
        ColgramProxyPolicy.State failed = new ColgramProxyPolicy.State(true, false, -2, -2, 0, "c", 80, 0);
        ColgramProxyPolicy.State quarantined = new ColgramProxyPolicy.State(true, true, 1, 1, 1000, "d", 80, 0);
        check(ColgramProxyPolicy.compare(healthy, tcpOnly, 10) < 0, "protocol before TCP");
        check(ColgramProxyPolicy.compare(tcpOnly, unknown, 10) < 0, "TCP before unknown");
        check(ColgramProxyPolicy.compare(unknown, failed, 10) < 0, "unknown before failed");
        check(ColgramProxyPolicy.compare(failed, quarantined, 10) < 0, "quarantine last");
        ColgramProxyPolicy.State sameA = new ColgramProxyPolicy.State(true, true, 0, -1, 0, "a", 80, 0);
        ColgramProxyPolicy.State sameB = new ColgramProxyPolicy.State(true, true, 0, -1, 0, "b", 80, 0);
        check(ColgramProxyPolicy.compare(sameA, sameB, 0) < 0, "deterministic hostname tie break");
        check(ColgramProxyPolicy.compare(sameA, healthy, 0) < 0, "zero milliseconds is valid");
        check(ColgramProxyPolicy.compare(sameA, sameA, 0) == 0, "equal comparator");
        check(ColgramProxyPolicy.isReplaceable(failed, 0, 3), "TCP-dead slots can refresh without a native attempt");
        check(ColgramProxyPolicy.isReplaceable(unknown, 3, 3), "repeated protocol failure can refresh");
        check(!ColgramProxyPolicy.isReplaceable(healthy, 3, 3), "fresh healthy slot is protected");
        check(!ColgramProxyPolicy.isReplaceable(tcpOnly, 0, 3), "TCP-open candidates still deserve a protocol attempt");
        check(ColgramProxyPolicy.feedIndices(12, 6, 0).equals(Arrays.asList(0, 1, 2, 3, 4, 5)), "first source window");
        check(ColgramProxyPolicy.feedIndices(12, 6, 1).equals(Arrays.asList(6, 7, 8, 9, 10, 11)), "rotate source window");
        check(ColgramProxyPolicy.feedIndices(3, 6, Integer.MAX_VALUE).size() == 3, "overflow-safe feed cap");
        check(ColgramProxyPolicy.feedIndices(0, 6, 1).isEmpty(), "empty catalog");
        ColgramOperationGate gate = new ColgramOperationGate();
        long first = gate.begin();
        check(gate.isCurrent(first) && gate.isPending(), "start pending");
        gate.cancel();
        check(!gate.isCurrent(first) && !gate.isPending(), "cancel invalidates result");
        long second = gate.begin();
        check(second != first && !gate.finish(first) && gate.isPending(), "old result cannot finish new start");
        check(gate.finish(second) && !gate.isPending(), "current finish clears pending");
        check(ColgramProxyPolicy.startupDelay(100, 101) == 14999, "startup work deferred");
        check(ColgramProxyPolicy.startupDelay(100, 15100) == 0, "ready work not deferred");
        check(ColgramProxyPolicy.readBounded(new java.io.ByteArrayInputStream(new byte[]{65, 66}), 2).equals("AB"),
                "exact feed budget accepted");
        check(ColgramProxyPolicy.readBounded(null, 2).isEmpty(), "empty feed response");
        check(ColgramProxyPolicy.readBounded(new java.io.ByteArrayInputStream(new byte[0]), 0).isEmpty(),
                "empty zero budget response");
        boolean oversizedRejected = false;
        try {
            ColgramProxyPolicy.readBounded(new java.io.ByteArrayInputStream(new byte[1024 * 1024]), 16);
        } catch (java.io.IOException expected) {
            oversizedRejected = true;
        }
        check(oversizedRejected, "oversized newline-free body rejected before decoding");
        boolean deadlineRejected = false;
        try {
            ColgramProxyPolicy.readBounded(new java.io.ByteArrayInputStream(new byte[]{65}), 1, System.nanoTime() - 1);
        } catch (java.io.IOException expected) {
            deadlineRejected = true;
        }
        check(deadlineRejected, "expired per-feed deadline stops before reading");
        System.out.println("PASS ColgramProxyPolicyTest checks=" + checks);
    }
}
