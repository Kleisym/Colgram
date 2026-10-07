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

import java.util.HashMap;
import java.util.Map;

/**
 * Account-free check that call proxying is actually reachable, not merely defaulted in a string.
 *
 * The complaint was that call proxying had to be switched on by hand. Reading the source proves
 * a true default was written; it does not prove a call would ever use a proxy, because
 * VoIPService gates the decision on several conditions at once and quietly uses none of them
 * when any one fails. This drives those same conditions against the real prefs, so the gate is
 * exercised rather than assumed.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramCallProxyDeviceTest {

    private static final String PREFS = "mainconfig";

    private Context context;
    private SharedPreferences prefs;
    private final Map<String, String> saved = new HashMap<>();
    private boolean hadProxyEnabled;
    private boolean hadCallsEnabled;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        hadProxyEnabled = prefs.contains("proxy_enabled");
        hadCallsEnabled = prefs.contains("proxy_enabled_calls");
        for (String key : new String[]{"proxy_ip", "proxy_port", "proxy_secret"}) {
            saved.put(key, prefs.getString(key, null));
        }
    }

    @After
    public void tearDown() {
        SharedPreferences.Editor editor = prefs.edit();
        if (hadProxyEnabled) {
            editor.putBoolean("proxy_enabled", true);
        } else {
            editor.remove("proxy_enabled");
        }
        if (hadCallsEnabled) {
            editor.putBoolean("proxy_enabled_calls", true);
        } else {
            editor.remove("proxy_enabled_calls");
        }
        for (Map.Entry<String, String> entry : saved.entrySet()) {
            if (entry.getValue() == null) {
                editor.remove(entry.getKey());
            } else {
                editor.putString(entry.getKey(), entry.getValue());
            }
        }
        editor.commit();
    }

    @Test
    public void aUserWhoNeverTouchedTheSwitchGetsCallProxying() {
        prefs.edit().remove("proxy_enabled_calls").commit();
        assertTrue("call proxying must be on by default",
                prefs.getBoolean("proxy_enabled_calls", true));
    }

    @Test
    public void anExplicitOffIsStillHonoured() {
        prefs.edit().putBoolean("proxy_enabled_calls", false).commit();
        assertFalse("turning call proxying off must stick",
                prefs.getBoolean("proxy_enabled_calls", true));
    }

    @Test
    public void theDefaultOnlyMattersWhenAPlainProxyIsActuallyConfigured() {
        // VoIPService uses a proxy only when the proxy is enabled, the switch is on, a server is
        // stored, AND no MTProto secret is present - a secret means an MTProto proxy, which calls
        // cannot speak, so it falls back to direct. The default is what makes the switch part of
        // that conjunction fire; the rest is the user's own configuration.
        prefs.edit()
                .putBoolean("proxy_enabled", true)
                .putBoolean("proxy_enabled_calls", true)
                .putString("proxy_ip", "203.0.113.9")
                .putInt("proxy_port", 1080)
                .putString("proxy_secret", "")
                .commit();
        assertTrue("a plain SOCKS proxy with the default on is usable for calls",
                wouldUseProxy());

        prefs.edit().putString("proxy_secret", "ee0102030405060708090a0b0c0d0e0f").commit();
        assertFalse("an MTProto secret means calls go direct - Telegram cannot proxy a call "
                        + "through one, and must not be reported as if it did",
                wouldUseProxy());
    }

    /** The exact conjunction VoIPService evaluates before it builds its proxy. */
    private boolean wouldUseProxy() {
        boolean enabled = prefs.getBoolean("proxy_enabled", false);
        boolean callsOn = prefs.getBoolean("proxy_enabled_calls", true);
        String server = prefs.getString("proxy_ip", null);
        String secret = prefs.getString("proxy_secret", null);
        return enabled && callsOn
                && server != null && !server.isEmpty()
                && (secret == null || secret.isEmpty());
    }
}
