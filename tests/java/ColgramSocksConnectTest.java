package org.colgram.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class ColgramSocksConnectTest {
    private static void check(boolean value) {
        if (!value) throw new AssertionError("SOCKS5 CONNECT preflight assertion failed");
    }

    private static boolean exchange(byte[] reply) throws Exception {
        byte[] target = {1, 2, 3, 4};
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream(reply);
        boolean connected = ColgramSocks5Codec.requestConnect(request, () -> in.read(), target, 443);
        check(Arrays.equals(request.toByteArray(), new byte[]{5, 1, 0, 1, 1, 2, 3, 4, 1, (byte) 187}));
        return connected;
    }

    public static void main(String[] args) throws Exception {
        check(exchange(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}));
        check(exchange(new byte[]{5, 0, 0, 3, 1, 'a', 0, 0}));
        check(!exchange(new byte[]{5, 2, 0, 1, 0, 0, 0, 0, 0, 0}));
        check(!exchange(new byte[]{5, 0, 1, 1, 0, 0, 0, 0, 0, 0}));
        check(!exchange(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0}));
        check(!exchange(new byte[]{5, 0, 0, 3, 0, 0, 0}));
        System.out.println("ColgramSocksConnectTest: 6 accepted/refused/malformed fixtures PASS");
    }
}
