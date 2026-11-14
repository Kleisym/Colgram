package org.colgram.core;

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

import java.lang.reflect.Method;

/**
 * Account-free check of the real private-call preference migration and route gate.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramCallProxyDeviceTest {

    private static final String PREFS = "mainconfig";

    private Context context;
    private SharedPreferences prefs;
    private static final String MODE = "colgram_private_call_route";
    private static final String LEGACY = "proxy_enabled_calls";
    private String oldMode;
    private boolean hadLegacy, oldLegacy;
    private Class<?> routes;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        oldMode = prefs.getString(MODE, null);
        hadLegacy = prefs.contains(LEGACY);
        oldLegacy = prefs.getBoolean(LEGACY, true);
        routes = load("org.telegram.ui.ColgramCallRoutes");
    }

    @After
    public void tearDown() {
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (oldMode == null) editor.remove(MODE);
        else editor.putString(MODE, oldMode);
        if (hadLegacy) editor.putBoolean(LEGACY, oldLegacy);
        else editor.remove(LEGACY);
        editor.commit();
    }

    @Test
    public void neverTouchedUsesCurrentRoute() throws Exception {
        prefs.edit().remove(LEGACY).remove(MODE).commit();
        assertTrue("CURRENT".equals(currentMode().toString()));
    }

    @Test
    public void legacyOffMigratesAndExplicitPickerWins() throws Exception {
        prefs.edit().remove(MODE).putBoolean(LEGACY, false).commit();
        assertTrue("DIRECT".equals(currentMode().toString()));
        prefs.edit().putString(MODE, "WARP").commit();
        assertTrue("WARP".equals(currentMode().toString()));
    }

    @Test
    public void aMissingExplicitTunnelDoesNotFallBack() throws Exception {
        prefs.edit().putString(MODE, "SOCKS5").commit();
        Object mode = currentMode();
        Method gate = routes.getMethod("verdict", Context.class, mode.getClass());
        Object verdict = gate.invoke(null, context, mode);
        assertFalse("No active SOCKS5 app tunnel in this isolated test",
                verdict.getClass().getField("allowed").getBoolean(verdict));
    }

    private Object currentMode() throws Exception {
        return routes.getMethod("mode").invoke(null);
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, true, context.getClassLoader());
    }
}
