package org.colgram.core;

import java.net.*;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.*;

public final class LocalTcpFrontTest {
    public static void main(String[] args) throws Exception {
        byte[] payload = new byte[256 * 1024]; new Random(71).nextBytes(payload);
        payload[0]=22; payload[1]=3; payload[5]=1;
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ColgramLocalTcpFront front = new ColgramLocalTcpFront("127.0.0.1", peer.getLocalPort())) {
            CompletableFuture<byte[]> received = new CompletableFuture<>();
            Thread echo = new Thread(() -> {
                try (Socket socket = peer.accept()) {
                    socket.setSoTimeout(5000);
                    byte[] request = socket.getInputStream().readNBytes(payload.length);
                    received.complete(request);
                    socket.getOutputStream().write(request);
                } catch (Exception e) { received.completeExceptionally(e); }
            }); echo.setDaemon(true); echo.start();
            try (Socket client = new Socket("127.0.0.1", front.port())) {
                client.setSoTimeout(5000);
                client.getOutputStream().write(payload); client.shutdownOutput();
                if (!Arrays.equals(payload, client.getInputStream().readAllBytes())) throw new AssertionError("response changed");
            }
            if (!Arrays.equals(payload, received.get(5, TimeUnit.SECONDS))) throw new AssertionError("request changed");
            int port = front.port(); front.close();
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                throw new AssertionError("closed front accepted");
            } catch (java.io.IOException expected) {}
        }
        System.out.println("LOCAL_TCP_FRONT: PASS; exact request/response bytes, half-close, teardown");
    }
}
