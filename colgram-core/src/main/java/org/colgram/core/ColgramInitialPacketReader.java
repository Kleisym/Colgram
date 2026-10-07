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
        final ByteArrayOutputStream prefix = new ByteArrayOutputStream(Math.min(maxBytes, 64));
        final byte[] chunk = new byte[Math.min(maxBytes, 64)];
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
}
