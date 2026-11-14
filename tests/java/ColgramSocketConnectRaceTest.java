package org.colgram.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public final class ColgramSocketConnectRaceTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
    }

    private static InetSocketAddress endpoint(ServerSocket server) {
        return new InetSocketAddress(LOOPBACK, server.getLocalPort());
    }

    public static void main(String[] args) throws Exception {
        try (ServerSocket preferred = new ServerSocket(0, 2, LOOPBACK);
             ServerSocket alternate = new ServerSocket(0, 2, LOOPBACK);
             Socket connected = ColgramSocketConnectRace.connectPreferred(endpoint(preferred),
                     new InetSocketAddress[]{endpoint(alternate)}, 1100)) {
            check(connected != null && connected.getPort() == preferred.getLocalPort(), "keep the primary port when reachable");
        }
        InetSocketAddress unavailable;
        try (ServerSocket reserved = new ServerSocket(0, 1, LOOPBACK)) { unavailable = endpoint(reserved); }
        try (ServerSocket reachable = new ServerSocket(0, 3, LOOPBACK);
             Socket connected = ColgramSocketConnectRace.connectPreferred(unavailable,
                     new InetSocketAddress[]{unavailable, endpoint(reachable), endpoint(reachable)}, 1100)) {
            check(connected != null && connected.getPort() == reachable.getLocalPort(), "allow a different same-DC address");
        }
        check(ColgramSocketConnectRace.connectPreferred(unavailable, new InetSocketAddress[]{unavailable}, 150) == null,
                "unreachable endpoints do not produce a socket");
        System.out.println("ColgramSocketConnectRaceTest: primary, alternate, unavailable PASS");
    }
}
