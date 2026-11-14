package org.colgram.core;

import java.lang.reflect.Method;

/** Fresh protocol-verdict rules, executed without Android or network access. */
public final class ColgramProxySelectableTest {
    private static int checks;
    private static Method selectable;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static boolean allowed(boolean verified, boolean available, int ping, int tcp,
                                   long eligible, long checkedAt, long now) throws Exception {
        ColgramProxyPolicy.State state = new ColgramProxyPolicy.State(
                verified, available, ping, tcp, eligible, "proxy.example", 443, 1);
        return (Boolean) selectable.invoke(null, state, checkedAt, now, 60_000L);
    }
    public static void main(String[] args) throws Exception {
        try {
            selectable = ColgramProxyPolicy.class.getMethod("isSelectable",
                    ColgramProxyPolicy.State.class, long.class, long.class, long.class);
        } catch (NoSuchMethodException missing) {
            check(false, "missing fresh native-protocol selection rule");
        }
        check(!allowed(false, false, -1, -1, 0, 0, 1_000), "unprobed is pending, not working");
        check(!allowed(false, false, -1, 2, 0, 900, 1_000), "TCP-only cannot be selected");
        check(!allowed(true, false, -2, 2, 0, 900, 1_000), "failed native handshake is immediately dead");
        check(!allowed(true, true, 1, -2, 0, 900, 1_000), "new TCP failure invalidates previous success");
        check(allowed(true, true, 0, 2, 0, 900, 1_000), "zero-ms native success is valid");
        check(allowed(true, true, 1, -1, 0, 900, 1_000), "native verdict needs no redundant TCP probe");
        check(!allowed(true, true, 1, 2, 1_001, 900, 1_000), "quarantined entries wait");
        check(!allowed(true, true, 1, 2, 0, 0, 1_000), "unknown verdict time cannot be restored live");
        check(!allowed(true, true, 1, 2, 0, 1_001, 1_000), "future verdict time is invalid");
        check(!allowed(true, true, 1, 2, 0, 1, 60_001), "TTL boundary is expired");
        check(allowed(true, true, 1, 2, 0, 1, 60_000), "TTL just below boundary remains current");
        check(!allowed(true, true, -1, 2, 0, 900, 1_000), "missing protocol measurement is unverified");
        System.out.println("ColgramProxySelectableTest: " + checks + " checks PASS");
    }
}
