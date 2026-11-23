package org.colgram.core;

import android.net.VpnService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** RFC 1928 UDP relay for the authenticated, call-only local listener. */
final class ColgramCallUdpRelay {
    private static final int MAX_PACKET = 65507;
    private static final int MAX_PEERS = 32;
    private static final int IDLE_MS = 45_000;

    private ColgramCallUdpRelay() { }

    static void serve(VpnService vpn, Socket control, InputStream in, OutputStream out)
            throws IOException {
        if (vpn == null) throw new IOException("Call VPN has stopped");
        Map<InetSocketAddress, DatagramSocket> upstreams = new ConcurrentHashMap<>();
        InetAddress loopback = control.getLocalAddress();
        try (DatagramSocket relay = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            // RFC 1928: returned BND.ADDR/BND.PORT is the actual client UDP destination.
            int port = relay.getLocalPort();
            out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, (byte) (port >>> 8), (byte) port});
            out.flush();
            control.setSoTimeout(0);
            relay.setSoTimeout(IDLE_MS);
            Thread guard = new Thread(() -> {
                try {
                    byte[] discard = new byte[128];
                    while (in.read(discard) >= 0) { /* the TCP connection owns the UDP lifetime */ }
                } catch (IOException ignored) {
                } finally {
                    relay.close();
                }
            }, "colgram-call-udp-control");
            guard.setDaemon(true);
            guard.start();

            byte[] bytes = new byte[MAX_PACKET];
            DatagramPacket request = new DatagramPacket(bytes, bytes.length);
            InetSocketAddress client = null;
            while (!relay.isClosed()) {
                request.setLength(bytes.length);
                try {
                    relay.receive(request);
                } catch (SocketTimeoutException idle) {
                    break;
                }
                if (!control.getInetAddress().equals(request.getAddress())) continue;
                InetSocketAddress sender = new InetSocketAddress(request.getAddress(), request.getPort());
                if (client != null && !client.equals(sender)) continue;
                Frame frame = Frame.parse(bytes, request.getLength());
                if (frame == null) continue;
                client = sender;
                try {
                    InetSocketAddress target = frame.destination(vpn);
                    if (target.isUnresolved() || target.getAddress().isAnyLocalAddress()
                            || target.getAddress().isLoopbackAddress() || target.getAddress().isMulticastAddress()) continue;
                    DatagramSocket upstream = upstreams.get(target);
                    if (upstream == null) {
                        if (upstreams.size() >= MAX_PEERS) continue;
                        // Do not send low-TTL UDP decoys from a separate ephemeral socket/port:
                        // stateful TSPU tracks UDP flows by 5-tuple, so a mismatched source port
                        // burns latency/FDs with zero DPI effect, and raw fd extraction from Java
                        // DatagramSocket risks bionic fdsan / Android hidden-API failures.
                        upstream = new DatagramSocket(null);
                        try {
                            if (!vpn.protect(upstream)) throw new IOException("UDP socket protection failed");
                            upstream.connect(target);
                            upstream.setSoTimeout(IDLE_MS);
                        } catch (IOException | RuntimeException failed) {
                            upstream.close();
                            throw failed;
                        }
                        upstreams.put(target, upstream);
                        readReplies(upstream, target, relay, sender, upstreams);
                    }
                    upstream.send(new DatagramPacket(bytes, frame.payloadOffset,
                            request.getLength() - frame.payloadOffset));
                } catch (IOException | RuntimeException unreachable) {
                    // A datagram target failure cannot redirect the association onto another route.
                }
            }
        } finally {
            for (DatagramSocket upstream : upstreams.values()) upstream.close();
        }
    }

    private static void readReplies(DatagramSocket upstream, InetSocketAddress target,
            DatagramSocket relay, InetSocketAddress client,
            Map<InetSocketAddress, DatagramSocket> upstreams) {
        Thread reader = new Thread(() -> {
            byte[] bytes = new byte[MAX_PACKET];
            DatagramPacket reply = new DatagramPacket(bytes, bytes.length);
            byte[] header = responseHeader(target);
            try {
                while (!relay.isClosed()) {
                    reply.setLength(bytes.length);
                    upstream.receive(reply);
                    if (header.length + reply.getLength() > MAX_PACKET) continue;
                    byte[] response = Arrays.copyOf(header, header.length + reply.getLength());
                    System.arraycopy(bytes, reply.getOffset(), response, header.length, reply.getLength());
                    relay.send(new DatagramPacket(response, response.length, client));
                }
            } catch (IOException ended) {
                // Control close or idle timeout.
            } finally {
                upstream.close();
                upstreams.remove(target, upstream);
            }
        }, "colgram-call-udp-upstream");
        reader.setDaemon(true);
        reader.start();
    }

    static byte[] responseHeader(InetSocketAddress source) {
        byte[] ip = source.getAddress().getAddress();
        byte[] header = new byte[6 + ip.length];
        header[3] = (byte) (ip.length == 4 ? 1 : 4);
        System.arraycopy(ip, 0, header, 4, ip.length);
        header[header.length - 2] = (byte) (source.getPort() >>> 8);
        header[header.length - 1] = (byte) source.getPort();
        return header;
    }

    static final class Frame {
        final byte[] address;
        final boolean hostname;
        final int port;
        final int payloadOffset;

        Frame(byte[] address, boolean hostname, int port, int payloadOffset) {
            this.address = address;
            this.hostname = hostname;
            this.port = port;
            this.payloadOffset = payloadOffset;
        }

        InetSocketAddress destination() throws IOException {
            return destination(null);
        }

        InetSocketAddress destination(VpnService vpn) throws IOException {
            if (hostname) {
                String host = new String(address, StandardCharsets.US_ASCII);
                if (ColgramConfig.isDohEnabled() && !ColgramDohResolver.isIpLiteral(host)) {
                    InetAddress doh = vpn != null
                            ? ColgramDohResolver.resolveProtected(host, vpn::protect, vpn::protect)
                            : ColgramDohResolver.resolveOrNull(host);
                    if (doh != null) return new InetSocketAddress(doh, port);
                    if (ColgramConfig.isStrictProxySecurityEnabled()) {
                        return InetSocketAddress.createUnresolved(host, port);
                    }
                }
                return new InetSocketAddress(InetAddress.getByName(host), port);
            }
            return new InetSocketAddress(InetAddress.getByAddress(address), port);
        }

        static Frame parse(byte[] bytes, int length) {
            if (length < 8 || length > bytes.length
                    || bytes[0] != 0 || bytes[1] != 0 || bytes[2] != 0) return null;
            int start = 4;
            int size;
            boolean hostname = false;
            switch (bytes[3] & 0xff) {
                case 1: size = 4; break;
                case 4: size = 16; break;
                case 3:
                    start = 5;
                    size = bytes[4] & 0xff;
                    if (size == 0) return null;
                    hostname = true;
                    break;
                default: return null;
            }
            int end = start + size;
            if (end + 2 > length) return null;
            int port = ((bytes[end] & 0xff) << 8) | (bytes[end + 1] & 0xff);
            return port == 0 ? null : new Frame(Arrays.copyOfRange(bytes, start, end),
                    hostname, port, end + 2);
        }
    }
}
