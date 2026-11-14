package org.colgram.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Exact byte reads for SOCKS5 fields carried over a TCP stream. */
final class ColgramSocks5Codec {
    private ColgramSocks5Codec() {
    }

    static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("incomplete SOCKS5 request");
        }
        return value;
    }

    static byte[] readFully(InputStream input, int length) throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) {
                throw new EOFException("incomplete SOCKS5 field");
            }
            if (count == 0) {
                result[offset++] = (byte) readByte(input);
            } else {
                offset += count;
            }
        }
        return result;
    }

    interface ByteReader { int next() throws IOException; }

    /** SOCKS5 CONNECT only: a valid reply does not establish application-protocol health. */
    static boolean requestConnect(OutputStream output, ByteReader input, byte[] target, int port) throws IOException {
        if ((target.length != 4 && target.length != 16) || port < 1 || port > 65535) return false;
        output.write(new byte[]{5, 1, 0, (byte) (target.length == 4 ? 1 : 4)});
        output.write(target);
        output.write(new byte[]{(byte) (port >>> 8), (byte) port});
        output.flush();
        if (input.next() != 5 || input.next() != 0 || input.next() != 0) return false;
        int type = input.next();
        int addressLength = type == 1 ? 4 : type == 4 ? 16 : type == 3 ? input.next() : -1;
        if (addressLength < 1) return false;
        for (int i = 0; i < addressLength + 2; i++) if (input.next() < 0) return false;
        return true;
    }
}
