package org.colgram.core;

import java.lang.reflect.Method;

/** Reflective access to the statistics API exposed by supported WireGuard tunnel artifacts. */
public final class ColgramWarpStatistics {
    private ColgramWarpStatistics() {}

    /** Resolves getStatistics(Tunnel) without linking this core module to the optional AAR. */
    public static Object statistics(Object backend, Object tunnel) {
        if (backend == null || tunnel == null) return null;
        for (Method method : backend.getClass().getMethods()) {
            if (!"getStatistics".equals(method.getName()) || method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isInstance(tunnel)) {
                continue;
            }
            try {
                return method.invoke(backend, tunnel);
            } catch (Throwable ignored) {
                return null;
            }
        }
        // Keep compatibility with small wrapper backends that expose aggregate counters only.
        try {
            return backend.getClass().getMethod("getStatistics").invoke(backend);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static long totalRx(Object statistics) {
        return readCounter(statistics, "totalRx", "getTotalRx");
    }

    public static long totalTx(Object statistics) {
        return readCounter(statistics, "totalTx", "getTotalTx");
    }

    private static long readCounter(Object statistics, String currentName, String legacyName) {
        if (statistics == null) return 0L;
        for (String name : new String[]{currentName, legacyName}) {
            try {
                Method method = statistics.getClass().getMethod(name);
                Object value = method.invoke(statistics);
                if (value instanceof Number) return ((Number) value).longValue();
            } catch (Throwable ignored) {
                // Try the compatibility name before giving up on a library-version difference.
            }
        }
        return 0L;
    }
}
