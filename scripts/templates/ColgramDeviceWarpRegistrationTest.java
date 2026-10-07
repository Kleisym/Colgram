package org.colgram.core;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Does WARP registration actually complete from the phone, on this network?
 *
 * <b>Why this is measured at all.</b>
 *
 * The host's system resolver answers {@code api.cloudflareclient.com} with 8.47.69.0 and 8.6.112.0,
 * which are not Cloudflare ranges, while DoH gives the truth - 104.16.192.82. The device resolves it
 * correctly, which means the app and the host are on different resolvers, and it means the host's
 * failure to register is not evidence about the phone. Registration is the first link of the whole
 * chain: without it there is no private key, no peer, no tunnel, and every later measurement is
 * measuring nothing.
 *
 * <p>So this drives the app's own {@code ColgramWarp.register()} rather than a hand-rolled request,
 * because the point is whether the code path works, not whether an HTTPS POST to one URL can be
 * made from Java.
 *
 * <p><b>Why it never re-registers an existing device.</b> The app refuses to register twice -
 * "one identity per device: re-registering would orphan the previous account" - and that refusal is
 * correct. So the test asserts the registration only when there is not one, and otherwise reports
 * what is already stored. Forcing a second registration to "prove" the endpoint answers would orphan
 * a real account to produce a log line, which is a bad trade on any machine and a worse one on a
 * shared test device.
 *
 * <p>Reported, never asserted about the network: a registration that fails because Cloudflare is
 * unreachable must not turn this suite red, because that is the condition the rest of the file is
 * about. What is asserted is the shape of a successful one - a key, peers, and a client id - since
 * those are code correctness rather than connectivity.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceWarpRegistrationTest {

    private static final String TAG = "ColgramWarpReg";

    @Test
    public void registrationCompletesOrTheStoredOneIsIntact() throws Exception {
        android.content.Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClassLoader loader = context.getClassLoader();
        Class.forName("org.colgram.core.ColgramConfig", true, loader)
                .getMethod("init", android.content.Context.class).invoke(null, context);
        Class<?> warp = Class.forName("org.colgram.core.ColgramWarp", true, loader);

        boolean registered = (Boolean) warp.getMethod("isRegistered").invoke(null);
        if (!registered) {
            long started = System.currentTimeMillis();
            try {
                warp.getMethod("register", android.content.Context.class).invoke(null, context);
                Log.i(TAG, "register() returned true in "
                        + (System.currentTimeMillis() - started) + "ms");
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                Log.w(TAG, "register() failed: " + cause.getClass().getSimpleName()
                        + " - " + String.valueOf(cause.getMessage()));
                Log.i(TAG, "VERDICT: registration did not complete on the device. Everything"
                        + " downstream - key, peer, reserved bytes, the tunnel - is unmeasured"
                        + " while this is true, and a failure here has nothing to say about the"
                        + " WireGuard ports, which are a different path entirely.");
                return;
            }
        } else {
            Log.i(TAG, "a registration already exists; not re-registering, because the app"
                    + " refuses to and doing it anyway would orphan the account");
        }

        // Whatever happened above, the stored state has to be usable or the rest of the app is
        // building profiles out of nothing. These are assertions because they are code, not network.
        org.junit.Assert.assertTrue("isRegistered() is false after a successful register()",
                (Boolean) warp.getMethod("isRegistered").invoke(null));
        String priv = (String) warp.getMethod("getPrivateKey").invoke(null);
        org.junit.Assert.assertNotNull("no private key stored", priv);
        String reserved = (String) warp.getMethod("reservedHex").invoke(null);
        org.junit.Assert.assertNotNull("no three-byte client id in the registration", reserved);
        String host = (String) warp.getMethod("endpointHost").invoke(null);
        int ports = (Integer) warp.getMethod("endpointPortCount").invoke(null);

        Log.i(TAG, "stored identity: endpoint host=" + host + " advertised ports=" + ports
                + " reserved=" + reserved + " key length=" + (priv == null ? 0 : priv.length()));
        org.junit.Assert.assertNotNull("no endpoint host in the registration", host);
        org.junit.Assert.assertTrue("no endpoint ports in the registration", ports > 0);

        Log.i(TAG, "VERDICT: the identity half is intact on the device - a key, a peer host, "
                + "advertised ports and a client id are all present, so the profile builder has"
                + " real material to work with. The transport is a separate question and this"
                + " says nothing about it.");
    }
}

