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
        android.content.ServiceConnection heldBinding = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) { }
            @Override public void onServiceDisconnected(android.content.ComponentName name) { }
        };
        boolean bound = false;
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ColgramProxyManager.ensureLocalBaseRoute(context));
            assertTrue(ColgramDpiBypass.isBound());
            ColgramConfig.setWarpEnabled(true);
            ColgramWarpTunnel.bringUp(context, epoch);
            assertTrue("WARP must verify its carrier, not merely establish a TUN", ColgramWarpTunnel.isUp());
            assertTrue("Selecting WARP stopped the base", ColgramDpiBypass.isBound());
            bound = context.bindService(new android.content.Intent(context, ColgramMasqueVpnService.class)
                    .setAction(android.net.VpnService.SERVICE_INTERFACE), heldBinding, Context.BIND_AUTO_CREATE);
            assertTrue("Fixture must retain a real Android service binding", bound);
            CountDownLatch mainRpc = new CountDownLatch(1);
            AtomicReference<Boolean> mainVerified = new AtomicReference<>(false);
            AtomicReference<Throwable> mainFailure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger mainToken = new java.util.concurrent.atomic.AtomicInteger();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    org.telegram.messenger.ApplicationLoader.postInitApplication();
                    org.telegram.tgnet.ConnectionsManager manager = org.telegram.tgnet.ConnectionsManager.getInstance(0);
                    int flags = org.telegram.tgnet.ConnectionsManager.RequestFlagEnableUnauthorized
                            | org.telegram.tgnet.ConnectionsManager.RequestFlagWithoutLogin;
                    mainToken.set(manager.sendRequest(new org.telegram.tgnet.TLRPC.TL_help_getConfig(), (response, error) -> {
                        mainVerified.set(error == null && response instanceof org.telegram.tgnet.TLRPC.TL_config
                                && !((org.telegram.tgnet.TLRPC.TL_config) response).dc_options.isEmpty());
                        mainRpc.countDown();
                    }, flags));
                } catch (Throwable error) { mainFailure.set(error); mainRpc.countDown(); }
            });
            try {
                assertTrue("Main app Telegram RPC did not complete through WARP", mainRpc.await(20, TimeUnit.SECONDS));
                if (mainFailure.get() != null) throw new AssertionError("Main app RPC failed", mainFailure.get());
                assertTrue("WARP trace worked but the main application did not receive Telegram config", mainVerified.get());
                Log.i("ColgramBaseTest", "WARP_MAIN_TELEGRAM=PASS account=0 help_getConfig=true local_base=true vpn_free=false");
            } finally {
                if (mainToken.get() != 0) org.telegram.tgnet.ConnectionsManager.getInstance(0).cancelRequest(mainToken.get(), false);
            }
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
            try {
                long deadline = android.os.SystemClock.elapsedRealtime() + 4_000L;
                while (android.os.SystemClock.elapsedRealtime() < deadline && activeVpn(context)) Thread.sleep(80L);
                assertFalse("WARP OFF left a kernel VPN active while Android still held the service binding", activeVpn(context));
                Log.i("ColgramBaseTest", "WARP_BOUND_STOP=PASS binding_held=true kernel_vpn=false");
            } finally {
                if (bound) context.unbindService(heldBinding);
            }
            ColgramConfig.setBuiltinProxyEnabled(catalog);
            assertTrue(context.getSharedPreferences("colgram_secure_config", Context.MODE_PRIVATE).edit()
                    .putBoolean("builtin_proxy_enabled", catalog).putBoolean("warp_enabled", false).commit());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ColgramProxyManager.ensureLocalBaseRoute(context));
            Log.i("ColgramBaseTest", "WARP_BASE_RESTORE base_bound=" + ColgramDpiBypass.isBound() + " warp_up=" + ColgramWarpTunnel.isUp());
        }
    }

    private static boolean activeVpn(Context context) {
        android.net.ConnectivityManager manager = context.getSystemService(android.net.ConnectivityManager.class);
        for (android.net.Network network : manager.getAllNetworks()) {
            android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }
}
