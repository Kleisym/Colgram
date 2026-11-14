package org.colgram.core;

import static org.junit.Assert.*;
import android.content.Context;
import android.net.VpnService;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real overlay compatibility check. This never represents direct, VPN-free connectivity. */
@RunWith(AndroidJUnit4.class)
public final class ColgramWarpBaseTelegramDeviceTest {
    @Test public void mandatoryBaseAndWarpCarryAGenuineTelegramConfig() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Assume.assumeTrue("Grant Android VPN consent on the disposable guest first", VpnService.prepare(context) == null);
        Assume.assumeFalse("Keep another active VPN untouched", ColgramWarpTunnel.isUp());
        Assume.assumeFalse("Keep a running subscription untouched", ColgramSubscriptionStore.hasSubscription(context));
        boolean catalog = ColgramConfig.isBuiltinProxyEnabled();
        ColgramConfig.setBuiltinProxyEnabled(false);
        Thread.sleep(11_000L); // Finish an already running anonymous catalog probe.
        long epoch = ColgramRouteEpoch.next();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ColgramProxyManager.ensureLocalBaseRoute(context));
            assertTrue(ColgramDpiBypass.isBound());
            ColgramConfig.setWarpEnabled(true);
            ColgramWarpTunnel.bringUp(context, epoch);
            assertTrue("WARP must verify its carrier, not merely establish a TUN", ColgramWarpTunnel.isUp());
            assertTrue("Selecting WARP stopped the base", ColgramDpiBypass.isBound());
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> verdict = new AtomicReference<>();
            assertTrue(ColgramGuestProxyProbe.check(context, "127.0.0.1", ColgramDpiBypass.activePort(),
                    "", "", "", false, (time, reason) -> { verdict.set(reason); completed.countDown(); }));
            assertTrue(completed.await(12, TimeUnit.SECONDS));
            assertEquals("Genuine Telegram config through the base + WARP", "TG_CONFIG_OK", verdict.get());
            Log.i("ColgramBaseTest", "WARP_BASE_TELEGRAM=PASS warp_on=true local_base=true help_getConfig=true vpn_free=false");
        } finally {
            ColgramConfig.setWarpEnabled(false);
            ColgramWarpTunnel.bringDown(context);
            ColgramConfig.setBuiltinProxyEnabled(catalog);
            assertTrue(context.getSharedPreferences("colgram_secure_config", Context.MODE_PRIVATE).edit()
                    .putBoolean("builtin_proxy_enabled", catalog).putBoolean("warp_enabled", false).commit());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ColgramProxyManager.ensureLocalBaseRoute(context));
            Log.i("ColgramBaseTest", "WARP_BASE_RESTORE base_bound=" + ColgramDpiBypass.isBound() + " warp_up=" + ColgramWarpTunnel.isUp());
        }
    }
}
