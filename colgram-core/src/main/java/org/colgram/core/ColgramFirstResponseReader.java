package org.colgram.core;

import java.io.IOException;
import java.net.Socket;

/**
 * Bounds only the first server read in a newly-opened Telegram tunnel.
 * After the first response, an idle MTProto connection remains unbounded as usual.
 */
public final class ColgramFirstResponseReader {

    private ColgramFirstResponseReader() {}

    static int readFirst(Socket socket, byte[] buffer, int timeoutMs) throws IOException {
        int previousTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(timeoutMs);
            return socket.getInputStream().read(buffer);
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
    }
}
