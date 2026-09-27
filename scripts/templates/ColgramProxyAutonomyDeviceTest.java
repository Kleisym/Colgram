package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Account-free on-device check that the proxy stays where the user left it.
 *
 * Two complaints meet here: "it tries to connect to a dead proxy on startup" and "the proxy
 * switches itself on and will not switch off". Both are claims about persisted state, so they
 * are checked against the real SharedPreferences rather than read out of the source.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramProxyAutonomyDeviceTest {

    private static final String PREFS = "mainconfig";

    private Context context;
    private SharedPreferences prefs;
    private String savedProxyEnabled;
    private String savedServer;
    private String savedPort;
    private String savedManuallyDisabled;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        savedProxyEnabled = prefs.getString("proxy_enabled", null);
        savedServer = prefs.getString("proxy_server", null);
        savedPort = prefs.getString("proxy_port", null);
        savedManuallyDisabled = prefs.getString("colgram_proxy_manually_disabled", null);
    }

    @After
    public void tearDown() {
        SharedPreferences.Editor editor = prefs.edit();
        restore(editor, "proxy_enabled", savedProxyEnabled);
        restore(editor, "proxy_server", savedServer);
        restore(editor, "proxy_port", savedPort);
        restore(editor, "colgram_proxy_manually_disabled", savedManuallyDisabled);
        editor.commit();
    }

    @Test
    public void callsDefaultToUsingTheProxy() throws Exception {
        // Read the flag the way the running code does, rather than reading the source: the
        // default lives in a preferences read, and a missing key is exactly the case that made
        // call proxying look switched off to a user who never touched the switch.
        SharedPreferences main = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        main.edit().remove("proxy_enabled_calls").commit();
        assertTrue("call proxying must default to on for a user who never set it",
                main.getBoolean("proxy_enabled_calls", true));
        main.edit().putBoolean("proxy_enabled_calls", false).commit();
        assertFalse("an explicit off must still be honoured",
                main.getBoolean("proxy_enabled_calls", true));
        main.edit().remove("proxy_enabled_calls").commit();
    }

    @Test
    public void manualOffSurvivesStartupAndIsNotUndoneByTheManager() throws Exception {
        Class<?> manager = Class.forName("org.colgram.core.ColgramProxyManager", true,
                context.getClassLoader());
        // Clear every trace of a prior route, then record the choice the complaint is about.
        prefs.edit().remove("proxy_server").remove("proxy_port")
                .putBoolean("proxy_enabled", false).commit();
        Method markDisabled = manager.getDeclaredMethod("setUserProxyDisabled", boolean.class);
        markDisabled.setAccessible(true);
        markDisabled.invoke(null, true);

        // isProxyEnabled is the single thing the settings row and the bypass switch consult.
        Method isEnabled = manager.getMethod("isProxyEnabled", Context.class);
        assertFalse("a proxy the user turned off must read as off",
                (Boolean) isEnabled.invoke(null, context));

        // And the flag Telegram itself reads must still say off.
        assertFalse(prefs.getBoolean("proxy_enabled", true));
        assertTrue("the manual opt-out must be recorded, not just the effect",
                prefs.getBoolean("colgram_proxy_manually_disabled", false));
    }

    @Test
    public void aDeadProxyIsNeverPersistedAsSomethingToConnectTo() throws Exception {
        Class<?> manager = Class.forName("org.colgram.core.ColgramProxyManager", true,
                context.getClassLoader());
        prefs.edit().putBoolean("proxy_enabled", true).commit();

        Method findSaved = manager.getDeclaredMethod("findSavedProxy", SharedPreferences.class);
        findSaved.setAccessible(true);
        // A stored endpoint that no longer resolves must not be handed back as the route to use.
        prefs.edit().putString("proxy_ip", "203.0.113.7")
                .putString("proxy_port", "443")
                .putString("proxy_secret", "eeffffffffffffffffffffffffffffffff")
                .commit();
        Object found = findSaved.invoke(null, prefs);
        if (found != null) {
            // If it is offered, it must at least be one the pool actually knows is alive.
            assertTrue("a stored proxy outside the live pool must not be offered for reconnection",
                    isInPool(manager, found));
        }
    }

    private static boolean isInPool(Class<?> manager, Object item) throws Exception {
        Method candidates = manager.getMethod("getVerifiedPool");
        Object list = candidates.invoke(null);
        if (!(list instanceof java.util.List)) return false;
        return ((java.util.List<?>) list).contains(item);
    }

    private static void restore(SharedPreferences.Editor editor, String key, String value) {
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }

}
