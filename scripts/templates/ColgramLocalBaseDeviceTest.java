package org.colgram.core;

import static org.junit.Assert.*;
import android.content.Context;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;

@RunWith(AndroidJUnit4.class)
public final class ColgramLocalBaseDeviceTest {
    @Test public void disablingAnOverlayDoesNotDisableTheLocalFoundation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ColgramProxyManager.ensureLocalBaseRoute(context);
            ColgramProxyManager.setDpiBypassEnabled(context, false);
        });
        assertTrue(ColgramConfig.isDpiBypassEnabled());
        assertTrue(ColgramDpiBypass.isBound());
        assertTrue(ColgramDpiBypass.activePort() > 0);
        Log.i("ColgramBaseTest", "LOCAL_BASE=PASS requested_off_ignored=true listener_bound=true protocol_not_claimed=true");
    }

    @Test public void feedCountsAreParsedRecordsNotNewlyInsertedRecords() throws Exception {
        String secret = "0123456789abcdef0123456789abcdef";
        String link = "tg://proxy?server=8.8.8.8&port=443&secret=" + secret;
        assertEquals(1, parse("fetchProxiesLinkList", link));
        assertEquals(1, parse("fetchProxiesLinkList", link));
        assertEquals(2, parse("fetchSocksList", "socks5://8.8.8.8:1080\n1.1.1.1:1081\nnot a proxy"));
        assertEquals(1, parse("fetchProxiesJson", "{\"proxies\":[{\"server\":\"8.8.8.8\",\"port\":443,\"secret\":\""+secret+"\"}]}"));
        assertEquals(0, parse("fetchProxiesJson", "https://example.invalid/not-json"));
        assertEquals(0, parse("fetchSocksList", "<html>ordinary web page</html>"));
        Log.i("ColgramBaseTest", "SOURCE_FORMATS=PASS links_socks_json=true duplicate_counts_stable=true invalid_not_available=true");
    }

    private static int parse(String name, String body) throws Exception {
        Method parser = ColgramProxyManager.class.getDeclaredMethod(name, String.class, String.class, int.class);
        parser.setAccessible(true);
        // Zero insertion budget: fixture addresses are parsed but never installed, dialed or saved.
        return (Integer) parser.invoke(null, "https://fixture.invalid/feed", body, 0);
    }
}
