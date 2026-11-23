package org.colgram.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Coalesces a bounded prefix of the first outbound packet before applying TCP desync. */
public final class ColgramInitialPacketReader {
    private ColgramInitialPacketReader() {}

    public static byte[] readPrefix(Socket socket, int maxBytes, int timeoutMs) throws IOException {
        if (socket == null || maxBytes <= 0) return new byte[0];
        final int previousTimeout = socket.getSoTimeout();
        final long deadlineNanos = System.nanoTime() + Math.max(1, timeoutMs) * 1_000_000L;
        final InputStream input = socket.getInputStream();
        final ByteArrayOutputStream prefix = new ByteArrayOutputStream(Math.min(maxBytes, 512));
        final byte[] chunk = new byte[Math.min(maxBytes, 2048)];
        try {
            while (prefix.size() < maxBytes) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) break;
                int remainingMs = (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                        (remainingNanos + 999_999L) / 1_000_000L));
                socket.setSoTimeout(remainingMs);
                int count = input.read(chunk, 0, Math.min(chunk.length, maxBytes - prefix.size()));
                if (count < 0) break;
                if (count > 0) prefix.write(chunk, 0, count);
            }
        } catch (SocketTimeoutException idle) {
            // Send the bytes that arrived before the bounded coalescing window elapsed.
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
        return prefix.toByteArray();
    }

    /**
     * Reads the initial client payload cleanly: returns immediately once a complete TLS
     * ClientHello record arrives (even for ~1800-2100B Post-Quantum X25519MLKEM768 hellos),
     * while staying strictly time-bounded for non-TLS or server-speaks-first protocols.
     */
    public static byte[] readInitialPacket(Socket socket, int firstCap, int maxTlsBytes,
                                           int firstTimeoutMs, int tlsRestTimeoutMs) throws IOException {
        if (socket == null || firstCap <= 0) return new byte[0];
        final int previousTimeout = socket.getSoTimeout();
        final InputStream input = socket.getInputStream();
        final int hardMax = Math.max(firstCap, maxTlsBytes);
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(firstCap, 2048));
        final byte[] chunk = new byte[Math.min(hardMax, 2048)];
        int targetLen = firstCap;
        boolean tlsTargetLocked = false;
        long deadlineNanos = System.nanoTime() + Math.max(1, firstTimeoutMs) * 1_000_000L;
        try {
            while (buf.size() < targetLen) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) break;
                int remainingMs = (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                        (remainingNanos + 999_999L) / 1_000_000L));
                socket.setSoTimeout(remainingMs);
                int toRead = Math.min(chunk.length, targetLen - buf.size());
                int count = input.read(chunk, 0, toRead);
                if (count < 0) break;
                if (count > 0) {
                    buf.write(chunk, 0, count);
                    if (!tlsTargetLocked && buf.size() >= 11) {
                        byte[] snap = buf.toByteArray();
                        int expected = ColgramTlsMimic.expectedClientHelloLength(snap, snap.length);
                        if (expected > 0) {
                            tlsTargetLocked = true;
                            targetLen = Math.min(expected, hardMax);
                            if (buf.size() >= targetLen) break;
                            long extended = System.nanoTime() + Math.max(1, tlsRestTimeoutMs) * 1_000_000L;
                            if (extended > deadlineNanos) deadlineNanos = extended;
                        } else if (buf.size() >= 64) {
                            // Non-TLS protocol (e.g. 64B MTProto obfuscated2 init or HTTP GET):
                            // do not stall waiting for extra bytes after the initial write.
                            break;
                        }
                    }
                }
            }
        } catch (SocketTimeoutException idle) {
            // Normal for server-speaks-first protocols or short initial payloads.
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
        return buf.toByteArray();
    }
}
