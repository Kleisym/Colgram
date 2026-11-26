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

    /**
     * Writes a SOCKS5/HTTP CONNECT control payload in small TCP segments (with {@code TCP_NODELAY}
     * enabled on the socket) so carrier TSPU DPI (e.g. MegaFon/Yota LTE) cannot match
     * {@code 05 01 00 01 <149.154.*|91.108.*>} or plaintext hostnames inside a single TCP segment.
     */
    static void writeSegmented(OutputStream output, byte[] data, int headChunkBytes, long gapMs)
            throws IOException {
        if (data == null || data.length == 0) return;
        boolean realStream = !(output instanceof java.io.ByteArrayOutputStream);
        int chunk = Math.max(1, headChunkBytes);
        for (int off = 0; off < data.length; ) {
            int stepSize = (chunk <= 2) ? chunk : (off < 12 ? chunk : Math.max(chunk, 4));
            int step = Math.min(stepSize, data.length - off);
            output.write(data, off, step);
            output.flush();
            off += step;
            // Yield ~2ms only on the leading SOCKS5 header bytes for real sockets so the OS kernel
            // never coalesces 05 01 00 01 <IP> into a single TSPU-matchable TCP segment.
            if (realStream && gapMs > 0L && off < data.length && off <= 4) {
                java.util.concurrent.locks.LockSupport.parkNanos(gapMs * 1_000_000L);
            }
        }
    }

    /**
     * Writes an HTTP/1.1 {@code CONNECT} request segmented right across the target host/IP in both
     * the request line and the {@code Host:} header so TSPU DPI cannot match the target address in
     * one TCP segment.
     */
    static void writeHttpConnectSegmented(OutputStream output, String targetHost, int targetPort,
                                          String username, String password) throws IOException {
        String cleanHost = targetHost.trim();
        String authority = (cleanHost.indexOf(':') >= 0 && !cleanHost.startsWith("["))
                ? "[" + cleanHost + "]:" + targetPort
                : cleanHost + ":" + targetPort;
        StringBuilder req = new StringBuilder(160);
        req.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(authority).append("\r\n");
        req.append("User-Agent: Colgram/1.0\r\n");
        req.append("Proxy-Connection: Keep-Alive\r\n");
        if (username != null && !username.isEmpty()) {
            String creds = username + ":" + (password == null ? "" : password);
            req.append("Proxy-Authorization: Basic ")
                    .append(encodeAsciiBase64(creds.getBytes("UTF-8")))
                    .append("\r\n");
        }
        req.append("\r\n");
        byte[] bytes = req.toString().getBytes("ISO-8859-1");
        int hostMid = Math.max(1, cleanHost.length() / 2);
        String prefix = cleanHost.substring(0, hostMid);
        String full = req.toString();
        int cut1 = full.indexOf(prefix);
        int cut2 = cut1 >= 0 ? full.indexOf(prefix, cut1 + prefix.length()) : -1;
        int split1 = cut1 >= 0 ? cut1 + prefix.length() : Math.min(bytes.length - 1, 10);
        int split2 = cut2 >= 0 ? cut2 + prefix.length() : -1;

        int pos = 0;
        int[] cuts = split2 > split1 ? new int[]{split1, split2, bytes.length}
                : new int[]{split1, bytes.length};
        for (int i = 0; i < cuts.length; i++) {
            int end = Math.min(bytes.length, cuts[i]);
            if (end > pos) {
                output.write(bytes, pos, end - pos);
                output.flush();
                pos = end;
            }
        }
    }

    private static String encodeAsciiBase64(byte[] data) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = (i + 1 < data.length) ? (data[i + 1] & 0xFF) : 0;
            int b2 = (i + 2 < data.length) ? (data[i + 2] & 0xFF) : 0;
            out.append(alphabet[b0 >>> 2]);
            out.append(alphabet[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            out.append(i + 1 < data.length ? alphabet[((b1 & 0x0F) << 2) | (b2 >>> 6)] : '=');
            out.append(i + 2 < data.length ? alphabet[b2 & 0x3F] : '=');
        }
        return out.toString();
    }

    /** SOCKS5 CONNECT only (segmented to evade carrier TSPU IP matching). */
    static boolean requestConnect(OutputStream output, ByteReader input, byte[] target, int port) throws IOException {
        if ((target.length != 4 && target.length != 16) || port < 1 || port > 65535) return false;
        byte[] req = new byte[4 + target.length + 2];
        req[0] = 5;
        req[1] = 1;
        req[2] = 0;
        req[3] = (byte) (target.length == 4 ? 1 : 4);
        System.arraycopy(target, 0, req, 4, target.length);
        req[4 + target.length] = (byte) (port >>> 8);
        req[5 + target.length] = (byte) port;
        writeSegmented(output, req, 2, 2L);
        if (input.next() != 5 || input.next() != 0 || input.next() != 0) return false;
        int type = input.next();
        int addressLength = type == 1 ? 4 : type == 4 ? 16 : type == 3 ? input.next() : -1;
        if (addressLength < 1) return false;
        for (int i = 0; i < addressLength + 2; i++) if (input.next() < 0) return false;
        return true;
    }

    /**
     * Full RFC 1928 SOCKS5 CONNECT handshake (IPv4 ATYP=1, IPv6 ATYP=4, domain ATYP=3) with
     * optional RFC 1929 auth and 2-byte TCP segmentation across the CONNECT target bytes.
     */
    static boolean requestConnectHost(OutputStream output, InputStream input, String host, int port,
                                      String username, String password) throws IOException {
        if (host == null || host.isEmpty() || port < 1 || port > 65535) return false;
        boolean hasUser = username != null && !username.isEmpty();
        if (hasUser) {
            output.write(new byte[]{5, 2, 0, 2});
        } else {
            output.write(new byte[]{5, 1, 0});
        }
        output.flush();
        int ver = readByte(input);
        int method = readByte(input);
        if (ver != 5) return false;
        if (method == 2) {
            if (!hasUser) return false;
            byte[] userBytes = username.getBytes("UTF-8");
            byte[] passBytes = (password == null ? "" : password).getBytes("UTF-8");
            if (userBytes.length > 255 || passBytes.length > 255) return false;
            byte[] auth = new byte[3 + userBytes.length + passBytes.length];
            auth[0] = 1;
            auth[1] = (byte) userBytes.length;
            System.arraycopy(userBytes, 0, auth, 2, userBytes.length);
            auth[2 + userBytes.length] = (byte) passBytes.length;
            System.arraycopy(passBytes, 0, auth, 3 + userBytes.length, passBytes.length);
            output.write(auth);
            output.flush();
            if (readByte(input) != 1 || readByte(input) != 0) return false;
        } else if (method != 0) {
            return false;
        }
        String cleanHost = host.trim();
        if (cleanHost.startsWith("[") && cleanHost.endsWith("]") && cleanHost.length() > 2) {
            cleanHost = cleanHost.substring(1, cleanHost.length() - 1);
        }
        byte[] ipv4 = parseIpv4(cleanHost);
        if (ipv4 != null) {
            return requestConnect(output, input::read, ipv4, port);
        }
        byte[] ipv6 = parseIpv6(cleanHost);
        if (ipv6 != null) {
            return requestConnect(output, input::read, ipv6, port);
        }
        byte[] hostBytes = cleanHost.getBytes("UTF-8");
        if (hostBytes.length == 0 || hostBytes.length > 255) return false;
        byte[] req = new byte[5 + hostBytes.length + 2];
        req[0] = 5;
        req[1] = 1;
        req[2] = 0;
        req[3] = 3;
        req[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
        req[5 + hostBytes.length] = (byte) (port >>> 8);
        req[6 + hostBytes.length] = (byte) port;
        writeSegmented(output, req, 2, 0L);
        if (readByte(input) != 5 || readByte(input) != 0 || readByte(input) != 0) return false;
        int type = readByte(input);
        int addressLength = type == 1 ? 4 : type == 4 ? 16 : type == 3 ? readByte(input) : -1;
        if (addressLength < 1) return false;
        readFully(input, addressLength + 2);
        return true;
    }

    private static byte[] parseIpv6(String host) {
        if (host == null || host.indexOf(':') < 0) return null;
        if (!ColgramDohResolver.isIpLiteral(host)) return null;
        try {
            byte[] raw = java.net.InetAddress.getByName(host).getAddress();
            return (raw != null && raw.length == 16) ? raw : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return null;
            int val = 0;
            for (int c = 0; c < parts[i].length(); c++) {
                char ch = parts[i].charAt(c);
                if (ch < '0' || ch > '9') return null;
                val = val * 10 + (ch - '0');
            }
            if (val > 255) return null;
            bytes[i] = (byte) val;
        }
        return bytes;
    }
}
