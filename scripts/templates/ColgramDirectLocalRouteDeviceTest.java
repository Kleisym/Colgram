package org.colgram.core;

import static org.junit.Assert.*;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** This is a live acceptance test, not a test which treats a listener as successful bypass. */
@RunWith(AndroidJUnit4.class)
public final class ColgramDirectLocalRouteDeviceTest {
    @Test public void directLocalBaseReceivesGenuineTelegramConfigWithoutVpnOrPublicProxy() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (android.net.Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            assertFalse("This test must not borrow a VPN", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        }
        boolean catalog = ColgramConfig.isBuiltinProxyEnabled();
        ColgramConfig.setBuiltinProxyEnabled(false);
        try {
            Thread.sleep(11_000L);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ColgramProxyManager.ensureLocalBaseRoute(context));
            assertTrue(ColgramDpiBypass.isBound());
            assertTrue(ColgramProxyManager.getCurrentActiveProxy().isLocalDpi());
            ColgramDpiBypass.clearConnectionFailureBackoff();
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> verdict = new AtomicReference<>();
            assertTrue(ColgramGuestProxyProbe.check(context, "127.0.0.1", ColgramDpiBypass.activePort(),
                    "", "", "", false, (time, reason) -> { verdict.set(reason); completed.countDown(); }));
            assertTrue(completed.await(12, TimeUnit.SECONDS));
            Log.i("ColgramBaseTest", "DIRECT_LOCAL_TELEGRAM=" + ("TG_CONFIG_OK".equals(verdict.get()) ? "PASS" : "FAIL")
                    + " verdict=" + verdict.get() + " vpn=false public_proxy=false base_bound=" + ColgramDpiBypass.isBound()
                    + " tx=" + ColgramDpiBypass.bytesToTarget() + " rx=" + ColgramDpiBypass.bytesFromTarget());
            assertEquals("A bound local listener does not prove RKN bypass", "TG_CONFIG_OK", verdict.get());
        } finally {
            ColgramConfig.setBuiltinProxyEnabled(catalog);
            assertTrue(context.getSharedPreferences("colgram_secure_config", Context.MODE_PRIVATE).edit()
                    .putBoolean("builtin_proxy_enabled", catalog).commit());
        }
    }
}
