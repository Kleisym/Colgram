package org.colgram.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

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
}
