package org.colgram.singbox;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.colgram.core.ColgramProfileBuilder;
import org.colgram.core.ColgramSubscription;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a generated profile with the engine that has to accept it.
 *
 * A profile that is wrong in one field is the worst kind of failure: the tunnel comes up, the
 * switch turns blue, and every connection dies with nothing to explain why. Only sing-box can say
 * whether a profile is valid, so that is what asks - not a check written here that would only
 * agree with the same assumption that produced the profile.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramProfileDeviceTest {

    private static final String TAG = "ColgramProfile";
    /** The character org.json emits in front of a solidus inside a string. */
    private static final String backslash = "\\";

    @Test
    public void everyProtocolWePromiseProducesAProfileTheEngineAccepts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        System.loadLibrary("box");
        Class<?> libbox = Class.forName("io.nekohasekai.libbox.Libbox");
        Method checkConfig = libbox.getMethod("checkConfig", String.class);

        // The exact strings a VPN bot returns, one per protocol, so this is the same input the
        // parser and the builder will meet in production rather than a hand-written shortcut.
        String[] subscriptions = {
                "vless://8f2a1b44-1111-2222-3333-444455556666@vpn.example.net:443"
                        + "?security=reality&sni=www.microsoft.com&pbk=bmXOC-F1FxEMF9dyiK2H5_1SUtzH0JuVo51h2wPfgyo&sid=abcd"
                        + "&type=tcp&flow=xtls-rprx-vision#Berlin",
                "trojan://p4ssw0rd@trojan.example.com:443?security=tls"
                        + "&sni=front.example.com&type=ws&path=%2Fws#Amsterdam",
                "ss://YWVzLTI1Ni1nY206c2VjcmV0@aes.example.io:8388#Tokyo",
                "hysteria2://pw@a2.example.net:443?sni=cdn.example.net#Hysteria",
                "hysteria://cHcAFA==@a1.example.net:36712?protocol=udp#Hysteria1",
                "socks5://1.2.3.4:1080#Local",
        };

        for (String subscription : subscriptions) {
            List<ColgramSubscription.Node> nodes = ColgramSubscription.parse(subscription);
            assertTrue("nothing parsed from " + subscription, !nodes.isEmpty());
            ColgramSubscription.Node node = nodes.get(0);
            Log.i(TAG, "parsed " + node.protocol + " publicKey=[" + node.publicKey + "]");
            String profile = ColgramProfileBuilder.forNode(node);
            Log.i(TAG, node.protocol + " profile: " + profile);
            try {
                // checkConfig throws when the engine refuses the profile. That is the verdict.
                checkConfig.invoke(null, profile);
                Log.i(TAG, node.protocol + ": engine accepted the profile");
            } catch (Throwable rejected) {
                Throwable cause = rejected.getCause() != null ? rejected.getCause() : rejected;
                throw new AssertionError("the engine refused the " + node.protocol + " profile: "
                        + cause + "\nprofile was: " + profile, cause);
            }
        }
    }

    @Test
    public void aWholeSubscriptionBecomesOneProfileWithFailover() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        System.loadLibrary("box");
        String joined = String.join("\n",
                "vless://8f2a1b44-1111-2222-3333-444455556666@a.example.net:443?security=reality&sni=s1.example.com"
                        + "&pbk=bmXOC-F1FxEMF9dyiK2H5_1SUtzH0JuVo51h2wPfgyo&sid=aa&type=tcp#one",
                "trojan://pw@b.example.net:443?security=tls&sni=s2.example.com#two",
                "ss://YWVzLTI1Ni1nY206c2VjcmV0@c.example.net:8388#three");
        List<ColgramSubscription.Node> nodes = ColgramSubscription.parse(joined);
        assertTrue("expected three nodes, got " + nodes.size(), nodes.size() == 3);
        String profile = ColgramProfileBuilder.forNodes(nodes);
        Log.i(TAG, "combined profile: " + profile);
        Class<?> libbox = Class.forName("io.nekohasekai.libbox.Libbox");
        try {
            libbox.getMethod("checkConfig", String.class).invoke(null, profile);
        } catch (Throwable rejected) {
            Throwable cause = rejected.getCause() != null ? rejected.getCause() : rejected;
            throw new AssertionError("the engine refused a three-node profile: " + cause
                    + "\nprofile was: " + profile, cause);
        }
    }

    @Test
    public void aProfileKeepsPerAppRoutingSoThePhoneIsNotSilentlyHalfRouted() throws Exception {
        // Android's per-app lists are whole-device decisions and they are the difference between a
        // tunnel that protects the phone and one that quietly leaves apps out of it.
        String withExcludes = String.join("\n",
                "vless://8f2a1b44-1111-2222-3333-444455556666@a.example.net:443"
                        + "?security=reality&sni=s1.example.com"
                        + "&pbk=bmXOC-F1FxEMF9dyiK2H5_1SUtzH0JuVo51h2wPfgyo&sid=aa#one",
                "trojan://pw@b.example.net:443?security=tls&sni=s2.example.com#two");
        List<ColgramSubscription.Node> nodes = ColgramSubscription.parse(withExcludes);
        String profile = ColgramProfileBuilder.forNodes(nodes);
        // Everything routes through the tunnel by default; a user who excludes an app gets that
        // app back on the real network, which is the only way to keep a bank app working.
        // org.json escapes the forward slash on the wire, so the text reads 0.0.0.0 backslash /0.
        // Matching the bare form would fail against a correct profile and train the test to be
        // ignored, which is worse than having no assertion at all.
        assertTrue("all traffic must route through the tunnel, but the profile was: " + profile,
                profile.replace(backslash, "").contains("0.0.0.0/0"));
        assertTrue("the tunnel must be reachable by anything that is not excluded",
                profile.contains("urltest"));
        // A direct outbound has to exist for the excluded app to fall back onto, or excluding it
        // would cut it off entirely rather than put it back on the real network.
        assertTrue("an excluded app needs a direct path to fall back onto",
                profile.contains("\"direct\""));
    }
}
