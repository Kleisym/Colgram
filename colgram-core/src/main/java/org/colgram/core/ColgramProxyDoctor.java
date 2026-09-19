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
     * - TCP + TLS safety verification
     * - MITM detection
     * - Auto-failover on connection loss
     * - Forced always-on proxy state
     */
    public static void init(Context context) {
        if (context == null) return;
        Log.d(TAG, "ProxyDoctor delegating to ColgramProxyManager");
        // ColgramProxyManager.activateBuiltinProxy is already called from ColgramHookHandler
        // No additional setup needed here.
    }

    /**
     * Get a summary of the proxy health status for display.
     */
    public static String getStatusSummary() {
        ColgramProxyManager.ProxyItem current = ColgramProxyManager.getCurrentActiveProxy();
        int aliveCount = ColgramProxyManager.getAliveCount();

        if (current != null) {
            return "🛡 Active: " + current.address + ":" + current.port +
                   " (" + current.pingMs + "ms) | Pool: " + aliveCount + " alive";
        }
        return "⚠ No active proxy | Pool: " + aliveCount + " available";
    }
}
