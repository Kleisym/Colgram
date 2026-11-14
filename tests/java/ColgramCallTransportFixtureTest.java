package org.colgram.core;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Wire-level loopback fixtures; no live SOCKS, Telegram or Android API is called. */
public final class ColgramCallTransportFixtureTest {
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void probe(boolean authenticated, boolean reject) throws Exception {
        String name = authenticated ? "fixture" : "";
        String password = authenticated ? "local-test-only" : "";
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(2000);
            FutureTask<Void> mock = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    byte method = (byte) (authenticated ? 2 : 0);
                    check(Arrays.equals(new byte[]{5, 1, method},
                            ColgramSocks5Codec.readFully(input, 3)), "offered only expected method");
                    output.write(new byte[]{5, reject && !authenticated ? (byte) 0xff : method});
                    if (authenticated) {
                        check(ColgramSocks5Codec.readByte(input) == 1, "RFC 1929 version");
                        check(name.equals(readField(input)), "SOCKS5 username");
                        check(password.equals(readField(input)), "SOCKS5 password");
                        output.write(new byte[]{1, (byte) (reject ? 1 : 0)});
                    }
                    output.flush();
                }
                return null;
            });
            Thread thread = new Thread(mock, "colgram-fixture-socks");
            thread.setDaemon(true);
            thread.start();
            boolean refused = false;
            try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
                socket.setSoTimeout(2000);
                ColgramCallSocksTunnel.probeAuthentication(socket, name, password);
            } catch (IOException failure) {
                refused = true;
            }
            mock.get(3, TimeUnit.SECONDS);
            check(refused == reject, "failed auth must never be treated as a working proxy");
        }
    }

    private static String readField(InputStream in) throws IOException {
        return new String(ColgramSocks5Codec.readFully(in, ColgramSocks5Codec.readByte(in)),
                StandardCharsets.UTF_8);
    }

    private static void frames() throws Exception {
        InetSocketAddress ipv4 = new InetSocketAddress("203.0.113.7", 3478);
        InetSocketAddress ipv6 = new InetSocketAddress("2001:db8::a", 443);
        for (InetSocketAddress target : new InetSocketAddress[]{ipv4, ipv6}) {
            byte[] header = ColgramCallUdpRelay.responseHeader(target);
            byte[] packet = Arrays.copyOf(header, header.length + 2);
            packet[header.length] = 11;
            packet[header.length + 1] = 23;
            ColgramCallUdpRelay.Frame frame = ColgramCallUdpRelay.Frame.parse(packet, packet.length);
            check(frame != null && frame.destination().equals(target), "IP address/port round-trip");
            check(frame.payloadOffset == header.length, "payload starts after complete header");
            check(packet[frame.payloadOffset] == 11, "first byte preserved");
            for (int i = 0; i < header.length; i++) {
                check(ColgramCallUdpRelay.Frame.parse(packet, i) == null, "truncation rejected");
            }
            packet[2] = 1;
            check(ColgramCallUdpRelay.Frame.parse(packet, packet.length) == null, "fragment rejected");
            packet[2] = 0;
            packet[header.length - 2] = 0;
            packet[header.length - 1] = 0;
            check(ColgramCallUdpRelay.Frame.parse(packet, packet.length) == null, "zero port rejected");
        }
        byte[] host = "example.invalid".getBytes(StandardCharsets.US_ASCII);
        byte[] domain = new byte[host.length + 8];
        domain[3] = 3;
        domain[4] = (byte) host.length;
        System.arraycopy(host, 0, domain, 5, host.length);
        domain[host.length + 6] = 53;
        ColgramCallUdpRelay.Frame frame = ColgramCallUdpRelay.Frame.parse(domain, domain.length);
        check(frame != null && frame.hostname && frame.port == 53, "domain form parsed without DNS");
        check(frame.payloadOffset == host.length + 7 && Arrays.equals(frame.address, host), "domain framing");
        domain[4] = (byte) 0xff;
        check(ColgramCallUdpRelay.Frame.parse(domain, domain.length) == null, "oversized domain rejected");
    }

    public static void main(String[] args) throws Exception {
        for (boolean auth : new boolean[]{false, true}) {
            probe(auth, false);
            probe(auth, true);
        }
        frames();
        System.out.println("ColgramCallTransportFixtureTest: " + checks + " checks PASS");
    }
}
