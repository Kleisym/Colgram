package org.colgram.core;

import static org.junit.Assert.*;

import android.content.Context;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit positive control; never counted as success from the ordinary physical bridge. */
@RunWith(AndroidJUnit4.class)
public final class ColgramControlledProxyDeviceTest {
    @Test public void controlledProxyReceivesAnActualUnauthenticatedTelegramConfig() throws Exception {
        String fixture = InstrumentationRegistry.getArguments().getString("controlledProxyPort");
        assertNotNull("Supply the deliberately isolated test peer via controlledProxyPort", fixture);
        int port = Integer.parseInt(fixture);
        assertTrue(port > 0 && port <= 65535);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Map<String, ?> before = routePrefs(context);
        boolean savedCatalog = ColgramConfig.isBuiltinProxyEnabled();
        ColgramConfig.setBuiltinProxyEnabled(false);
        try {
            Thread.sleep(11_000L);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> verdict = new AtomicReference<>();
            assertTrue(ColgramGuestProxyProbe.checkThroughBase(context, "127.0.0.1", port, "", "", "", false,
                    (rtt, reason) -> { verdict.set(reason); done.countDown(); }));
            assertTrue(done.await(11, TimeUnit.SECONDS));
            Log.i("ColgramGuestProbe", "CONTROLLED_PROXY_RESULT=" + verdict.get()
                    + "; path=explicit-loopback-fixture-NOT-direct-bridge");
            assertEquals("TG_CONFIG_OK", verdict.get());
            assertEquals(before, routePrefs(context));
        } finally {
            ColgramConfig.setBuiltinProxyEnabled(savedCatalog);
            assertTrue(context.getSharedPreferences("colgram_secure_config", Context.MODE_PRIVATE).edit()
                    .putBoolean("builtin_proxy_enabled", savedCatalog).commit());
        }
    }
    private static Map<String, Object> routePrefs(Context context) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> value : context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getAll().entrySet()) {
            if (value.getKey().startsWith("proxy_")) result.put(value.getKey(), value.getValue());
        }
        return result;
    }
}
