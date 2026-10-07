package org.colgram.core;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures, from the phone, whether ANY UDP path to Cloudflare exists.
 *
 * The host and the emulator are on different networks - the host cannot reach 1.1.1.1 over UDP at
 * all, the phone can - so a verdict drawn from the host says nothing about the phone. This is the
 * measurement that decides whether WARP is reachable here, and it sends a real WireGuard
 * initiation rather than random bytes, because only a WireGuard-shaped packet is a fair test of a
 * filter that targets WireGuard.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramWarpUdpDeviceTest {

    private static final String TAG = "ColgramWarpUdp";
    private static final int TIMEOUT_MS = 2500;

    private static final String[] INGRESS = {
            "162.159.192.1", "162.159.193.1", "162.159.195.1",
            "188.114.96.1", "188.114.97.1", "188.114.98.1", "188.114.99.1",
    };
    private static final int[] PORTS = {2408, 500, 854, 859, 934, 4500, 1701, 1640};

    @Test
    public void isAnyCloudflareUdpReachableFromThisDevice() throws Exception {
        InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Control first: if UDP is dead everywhere, every other result means nothing.
        Log.i(TAG, "control udp53(1.1.1.1) = " + dns("1.1.1.1"));
        Log.i(TAG, "control udp53(8.8.8.8) = " + dns("8.8.8.8"));

        final List<String> hits = new ArrayList<>();
        final AtomicInteger done = new AtomicInteger();
        final int total = INGRESS.length * PORTS.length;
        for (String ingress : INGRESS) {
            for (final int port : PORTS) {
                final String host = ingress;
                Thread thread = new Thread(() -> {
                    String result = wireguard(host, port);
                    if (result != null) {
                        synchronized (hits) {
                            hits.add(host + ":" + port + " -> " + result);
                        }
                    }
                    done.incrementAndGet();
                });
                thread.setDaemon(true);
                thread.start();
            }
        }
        long deadline = System.currentTimeMillis() + 30_000L;
        while (done.get() < total && System.currentTimeMillis() < deadline) {
            Thread.sleep(200L);
        }

        Log.i(TAG, "probed " + total + " Cloudflare WireGuard endpoints from the device");
        if (hits.isEmpty()) {
            Log.i(TAG, "RESULT: none of them answered");
        } else {
            for (String hit : hits) Log.i(TAG, "RESULT: " + hit);
        }
    }

    @Test
    public void doWarpEndpointsSpeakTcpAtAll() throws Exception {
        // The measurement that decides whether any client-side work can help. UDP is dropped on
        // every Cloudflare address here, so the only remaining hope would be a WireGuard endpoint
        // reachable over TCP. WireGuard has no TCP transport, so a correct implementation must
        // stay silent - but that is worth measuring on the device rather than assuming, because
        // the host and the phone are on different networks and the host cannot reach 1.1.1.1 over
        // UDP at all while the phone can.
        InstrumentationRegistry.getInstrumentation().getTargetContext();

        String[] hosts = {"162.159.192.1", "188.114.96.1"};
        int[] ports = {2408, 500, 854, 934, 4500, 1640};
        StringBuilder report = new StringBuilder();
        int wireguardReplies = 0;
        for (String host : hosts) {
            for (int port : ports) {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
                try {
                    socket.setSoTimeout(TIMEOUT_MS);
                    socket.getOutputStream().write(initiation(new Random()));
                    socket.getOutputStream().flush();
                    byte[] reply = new byte[256];
                    int read = socket.getInputStream().read(reply);
                    if (read > 0 && read <= 4 && (reply[0] == 2 || reply[0] == 3 || reply[0] == 4)) {
                        wireguardReplies++;
                        report.append("  ").append(host).append(':').append(port)
                                .append(" -> WIREGUARD REPLY type=").append(reply[0]).append('\n');
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // Connected, then silence: the normal answer for a port with no TCP service.
                } catch (Exception ignored) {
                    // Same conclusion, reached differently.
                } finally {
                    socket.close();
                }
            }
        }
        Log.i(TAG, "TCP to WARP endpoints produced " + wireguardReplies + " WireGuard replies");
        Log.i(TAG, wireguardReplies == 0
                ? "VERDICT: no WARP endpoint speaks TCP here, so the tunnel cannot be rescued "
                  + "client-side; UDP is the only transport WARP has and it is filtered"
                : report.toString());
    }

    /** A real handshake initiation; a filter that targets WireGuard keys on exactly this shape. */
    private static byte[] initiation(Random random) {
        byte[] packet = new byte[148];
        packet[0] = 1;
        for (int i = 4; i < 132; i++) {
            packet[i] = (byte) random.nextInt(256);
        }
        return packet;
    }

    private static String wireguard(String host, int port) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            socket.send(new DatagramPacket(initiation(new Random()), 148,
                    InetAddress.getByName(host), port));
            DatagramPacket reply = new DatagramPacket(new byte[2048], 2048);
            socket.receive(reply);
            return "answered " + reply.getLength() + "B";
        } catch (java.net.SocketTimeoutException e) {
            return null;
        } catch (java.net.PortUnreachableException e) {
            return "ICMP unreachable";
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private static String dns(String resolver) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            byte[] query = new byte[36];
            query[0] = 0x12; query[1] = 0x34; query[2] = 0x01; query[5] = 0x01;
            int at = 12;
            String[] labels = {"api", "telegram", "org"};
            for (String label : labels) {
                query[at++] = (byte) label.length();
                for (int i = 0; i < label.length(); i++) query[at++] = (byte) label.charAt(i);
            }
            query[at++] = 0; query[at++] = 0; query[at++] = 1; query[at++] = 0; query[at++] = 1;
            socket.send(new DatagramPacket(query, at, InetAddress.getByName(resolver), 53));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            return "answered " + reply.getLength() + "B";
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        } finally {
            if (socket != null) socket.close();
        }
    }
}
