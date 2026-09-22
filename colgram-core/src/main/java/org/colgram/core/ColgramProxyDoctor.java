package org.colgram.core;

import android.content.Context;
import android.util.Log;

/**
 * ColgramProxyDoctor — Delegates to ColgramProxyManager.
 * 
 * All proxy discovery, verification, safety auditing, and failover logic
 * is now centralized in ColgramProxyManager. This class exists for backward
 * compatibility with any existing references.
 */
public class ColgramProxyDoctor {

    private static final String TAG = "ColgramProxyDoctor";

    /**
     * Initialize the continuous proxy doctor.
     * Delegates entirely to ColgramProxyManager which handles:
     * - Multi-source proxy scraping (GitHub JSON feeds)
     * - Availability verdicts from Telegram's own native proxy checker
     * - Failover through the stock rotator plus Colgram's own selection
     * - The optional in-process desync listener on 127.0.0.1
     * A proxy is applied only when the user asks for one; direct connection is the default.
     */
    public static void init(Context context) {
        if (context == null) return;
        Log.d(TAG, "ProxyDoctor delegating to ColgramProxyManager");
        // ColgramProxyManager.activateBuiltinProxy is already called from ColgramHookHandler
        // No additional setup needed here.
    }

    /**
     * Get a summary of the proxy health status for display.
     *
     * Counts split by verdict because "alive" is now only ever set by Telegram's native protocol
     * checker. It used to include seven hardcoded nodes that were never probed at all, which
     * made the number meaningless.
     */
    public static String getStatusSummary() {
        ColgramProxyManager.ProxyItem current = ColgramProxyManager.getCurrentActiveProxy();
        int alive = ColgramProxyManager.getAliveCount();
        int dead = ColgramProxyManager.getDeadCount();
        int unchecked = ColgramProxyManager.getUncheckedCount();
        int total = ColgramProxyManager.getPoolSize();
        int fakeTls = ColgramProxyManager.getFakeTlsCount();
        int chained = ColgramProxyManager.getChainedCount();
        int relays = ColgramProxyManager.getRelayCount();
        String stats = "в пуле " + total + ": " + alive + " работает, " + dead + " не отвечает, "
                + unchecked + " без проверки; fake-TLS " + fakeTls
                + "; через ретранслятор " + chained + " (ретрансляторов " + relays + ")";
        // Say what the network is actually doing, so "the bypass does nothing" becomes a
        // diagnosis instead of a mystery.
        stats = ColgramProxyManager.describeBlockType() + " | " + stats;

        if (current != null) {
            return "Активный прокси: " + current.address + ":" + current.port
                    + " (" + ColgramProxyManager.describePing(current) + ") | " + stats;
        }
        return "Прокси не применён (прямое соединение) | " + stats;
    }
}
