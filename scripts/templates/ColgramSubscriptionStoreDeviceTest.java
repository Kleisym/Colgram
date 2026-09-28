package org.colgram.singbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.colgram.core.ColgramSubscriptionStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Walks a pasted subscription all the way to a profile the engine accepts.
 *
 * The units are each proven separately, but the chain between them is where a user loses their
 * subscription: a link that parses into a node the builder drops, a profile written somewhere the
 * engine cannot read, a node count that disagrees with what the list shows. This runs the whole
 * path on the device and hands the final file back to sing-box to check.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramSubscriptionStoreDeviceTest {

    private static final String TAG = "ColgramSubStore";

    private static final String SUBSCRIPTION = String.join("\n",
            "vless://8f2a1b44-1111-2222-3333-444455556666@a.example.net:443"
                    + "?security=reality&sni=s1.example.com"
                    + "&pbk=bmXOC-F1FxEMF9dyiK2H5_1SUtzH0JuVo51h2wPfgyo&sid=aa&type=tcp#Berlin",
            "trojan://pw@b.example.net:443?security=tls&sni=s2.example.com#Amsterdam",
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@c.example.net:8388#Tokyo");

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ColgramSubscriptionStore.clear(context);
    }

    @After
    public void tearDown() {
        ColgramSubscriptionStore.clear(context);
    }

    @Test
    public void aPastedLinkBecomesAProfileTheEngineAccepts() throws Exception {
        System.loadLibrary("box");
        Class<?> libbox = Class.forName("io.nekohasekai.libbox.Libbox");

        assertFalse("nothing stored before a link is pasted",
                ColgramSubscriptionStore.hasSubscription(context));

        ColgramSubscriptionStore.State saved = ColgramSubscriptionStore.save(context, SUBSCRIPTION);
        assertEquals("every node in the link should be usable", 3, saved.total);
        assertEquals("the first node is the starting point", 0, saved.selected);
        assertTrue("the link must be stored, or the tunnel has nothing to start from",
                ColgramSubscriptionStore.hasSubscription(context));

        String path = ColgramSubscriptionStore.profilePath(context);
        assertNotNull("a stored subscription must produce a profile path", path);
        File file = new File(path);
        assertTrue("the profile file was not written", file.exists() && file.length() > 0);
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Log.i(TAG, "profile written: " + json);

        // The engine is the judge, and it is the same judge that will run this file.
        libbox.getMethod("checkConfig", String.class).invoke(null, json);
        Log.i(TAG, "the engine accepted the stored profile");
    }

    @Test
    public void theStoredProfileIsPrivateToTheApp() throws Exception {
        ColgramSubscriptionStore.save(context, SUBSCRIPTION);
        String path = ColgramSubscriptionStore.profilePath(context);
        assertNotNull(path);
        // A subscription is paid access to someone else's server. It belongs in the app's own
        // storage and nowhere another app can read it.
        assertTrue("the profile must live in the app's private storage, not " + path,
                path.startsWith(context.getApplicationInfo().dataDir));
    }

    @Test
    public void aBrokenLinkIsRefusedWithAReasonRatherThanAnEmptyTunnel() {
        for (String junk : new String[]{"", "   ", "not a link", "vmess://@@@"}) {
            try {
                ColgramSubscriptionStore.save(context, junk);
                throw new AssertionError("a link with no usable node was accepted: " + junk);
            } catch (IllegalArgumentException expected) {
                assertNotNull("the refusal must say why", expected.getMessage());
            }
        }
        assertFalse("a refused link must not be left stored",
                ColgramSubscriptionStore.hasSubscription(context));
    }

    @Test
    public void choosingANodeKeepsTheOthersAsFallback() throws Exception {
        ColgramSubscriptionStore.save(context, SUBSCRIPTION);
        ColgramSubscriptionStore.State third = ColgramSubscriptionStore.select(context, 2);
        assertEquals("Tokyo", third.name);
        String json = new String(Files.readAllBytes(
                new File(ColgramSubscriptionStore.profilePath(context)).toPath()),
                StandardCharsets.UTF_8);
        // Picking one node must not drop the others: a blocked first server with no fallback is
        // the failure that made a paid subscription look broken.
        for (String tag : new String[]{"Berlin", "Amsterdam", "Tokyo"}) {
            assertTrue("the profile lost " + tag + ": " + json, json.contains(tag));
        }
        assertTrue("the profile must fail over between them", json.contains("urltest"));
    }
}
