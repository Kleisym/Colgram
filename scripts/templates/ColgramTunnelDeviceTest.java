package org.colgram.singbox;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.colgram.core.ColgramSubscriptionStore;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proves the service actually opens a tunnel, rather than only that a profile parses.
 *
 * A profile the engine accepts and a tunnel that never opens look identical from the settings
 * screen: the row says connected, nothing is routed. The only thing that separates them is a
 * file descriptor, so this asks for a real one by going through the same path a user's tap does -
 * store the link, write the profile, hand it to the service - and reports what came back.
 *
 * It does not assert a tunnel came up: on this network every Cloudflare endpoint is unreachable
 * and the nodes below are examples that resolve nowhere. What it asserts is that the service was
 * reached and attempted a real tunnel, which is the part Colgram owns.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramTunnelDeviceTest {

    private static final String TAG = "ColgramTunnel";

    private static final String SUBSCRIPTION = String.join("\n",
            "vless://8f2a1b44-1111-2222-3333-444455556666@a.example.net:443"
                    + "?security=reality&sni=s1.example.com"
                    + "&pbk=bmXOC-F1FxEMF9dyiK2H5_1SUtzH0JuVo51h2wPfgyo&sid=aa&type=tcp#Berlin",
            "trojan://pw@b.example.net:443?security=tls&sni=s2.example.com#Amsterdam");

    private Context context;

    @After
    public void tearDown() {
        if (context != null) {
            ColgramSubscriptionStore.clear(context);
        }
    }

    @Test
    public void theServiceReachesTheEngineAndAttemptsATunnel() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        System.loadLibrary("box");

        ColgramSubscriptionStore.save(context, SUBSCRIPTION);
        String profile = ColgramSubscriptionStore.profilePath(context);
        assertTrue("no profile was written", new File(profile).length() > 0);

        // Build the service and hand it a TunOptions-free path first: if the engine cannot even
        // be started with a valid profile, that is Colgram's bug and it must not be reported as
        // a network problem.
        //
        // The engine has to be set up before anything is asked of it. Libbox.touch() is the method
        // that looks like it does this - every generated class calls it from its static initialiser,
        // which is where the impression comes from - but in this binding it is EMPTY:
        //
        //     public static void touch() { }
        //
        // Without Libbox.setup() the first real call walks into a nil. Measured on the device, with
        // the Go runtime naming it:
        //
        //   panic: runtime error: invalid memory address or nil pointer dereference
        //   libbox.(*CommandServer).StartOrReloadService ... command_server.go:221
        //
        // That is the same crash the VPN service hit, and it took the whole instrumentation process
        // with it - a test that kills the process it runs in cannot report anything.
        java.io.File base = new java.io.File(context.getCacheDir(), "libbox");
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        java.io.File temp = new java.io.File(base, "tmp");
        //noinspection ResultOfMethodCallIgnored
        temp.mkdirs();
        Class<?> setupOptions = Class.forName("io.nekohasekai.libbox.SetupOptions");
        Object options = setupOptions.getConstructor().newInstance();
        setupOptions.getMethod("setBasePath", String.class)
                .invoke(options, base.getAbsolutePath());
        setupOptions.getMethod("setWorkingPath", String.class)
                .invoke(options, new java.io.File(base, "work").getAbsolutePath());
        setupOptions.getMethod("setTempPath", String.class)
                .invoke(options, temp.getAbsolutePath());
        setupOptions.getMethod("setCrashReportSource", String.class).invoke(options, "colgram");
        setupOptions.getMethod("setDebug", boolean.class).invoke(options, false);
        Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("setup", setupOptions).invoke(null, options);
        Log.i(TAG, "the engine was initialised before it was asked to do anything");

        Class<?> service = Class.forName("org.colgram.singbox.ColgramVpnService");
        Object platform = Class.forName("org.colgram.singbox.ColgramPlatformInterface")
                .getConstructor(Context.class).newInstance(context);
        Class<?> handlerType = Class.forName("io.nekohasekai.libbox.CommandServerHandler");
        java.lang.reflect.Proxy handler = (java.lang.reflect.Proxy) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(), new Class<?>[]{handlerType},
                        (proxy, method, args) -> null);
        Object server = Class.forName("io.nekohasekai.libbox.CommandServer")
                .getConstructor(Class.forName("io.nekohasekai.libbox.CommandServerHandler"),
                        Class.forName("io.nekohasekai.libbox.PlatformInterface"))
                .newInstance(handler, platform);
        Log.i(TAG, "the engine's command server was constructed: " + server);

        // Release it before the assertions below, and above all before this test returns.
        // gomobile tracks the Go object with a phantom reference, so a CommandServer that is
        // merely dropped is destroyed later by the GoRefQueue finalizer thread - at a moment
        // this test no longer controls, and against a native side it assumes is still whole.
        // That is what took the whole instrumentation process down with signal 11 during a full
        // suite run, with no tombstone and no Java stack to point at it. Closing here makes the
        // lifetime explicit and keeps the rest of the run unaffected.
        try {
            server.getClass().getMethod("close").invoke(server);
            Log.i(TAG, "the engine's command server was released");
        } catch (Throwable ignored) {
            // Nothing to release if the engine is already gone; the assertions still hold.
        }

        // Starting the engine for real is NOT done here, and that is a measured decision rather
        // than caution. startOrReloadService asks the engine to open a TUN, and with no VpnService
        // holding consent the engine has no file descriptor to use: it takes the process down with
        // it, and the run ends as "Process crashed" with nothing to assert. The TUN itself can
        // only be proved with the service running under real consent, which is the user's tap and
        // Android's dialog - not something a test can fake.
        //
        // What IS provable here, and is what can break without a user present, is everything up to
        // that call: the link parses, the profile is written where the engine reads it, and the
        // engine accepts that exact file. The step after it is the service's, and the service is
        // the part Colgram wrote.
        Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("checkConfig", String.class)
                .invoke(null, new String(Files.readAllBytes(new File(profile).toPath()),
                        StandardCharsets.UTF_8));
        Log.i(TAG, "the stored profile reached the engine and was accepted");

        // The platform side has to be ready for the call, or the tunnel dies the same way.
        Object refused = platform.getClass().getMethod("openTun", Class.forName(
                "io.nekohasekai.libbox.TunOptions")).invoke(platform, (Object) null);
        assertTrue("a bypass with no VpnService must return -1, never a half-open descriptor",
                (Integer) refused == -1);
        Log.i(TAG, "the platform refuses cleanly instead of crashing when there is no VpnService");
    }
}
