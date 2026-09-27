package org.colgram.singbox;

import android.content.Context;
import android.util.Log;

import static org.junit.Assert.assertNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Proves the sing-box engine is present and its JNI surface is reachable, before anything
 *  depends on it. A green subscription screen that cannot start a tunnel is worse than none. */
@RunWith(AndroidJUnit4.class)
public final class LibboxPresenceDeviceTest {

    private static final String TAG = "ColgramLibbox";

    @Test
    public void theEngineLoadsAndExposesItsVersion() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        System.loadLibrary("box");
        Log.i(TAG, "libbox loaded");
        Class<?> platform = Class.forName("io.nekohasekai.libbox.AndroidVPNType");
        // AndroidVPNType is a plain wrapper around one Go value, not a singleton, so the call has
        // to go through a real instance. Constructing it is the proof that matters: __New() is the
        // first JNI call, and a binding that did not link would fail here rather than at the first
        // tunnel, which is where a user would find out.
        Object instance = platform.getConstructor().newInstance();
        Object version = platform.getMethod("getGoVersion").invoke(instance);
        Log.i(TAG, "sing-box go version: " + version);
        assertNotNull("the engine must report a Go version once JNI is live", version);
        Class<?> client = Class.forName("io.nekohasekai.libbox.CommandClient");
        Log.i(TAG, "CommandClient present: " + client.getName());
    }

    @Test
    public void ourPlatformInterfaceSatisfiesTheBindingContract() throws Exception {
        // The engine resolves every one of these by name through gomobile. A class that merely
        // compiles is not proof - a missing method is a crash at runtime, in the middle of the
        // first connection, which is exactly where a user would discover it.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        System.loadLibrary("box");
        Class<?> contract = Class.forName("io.nekohasekai.libbox.PlatformInterface");
        Class<?> ours = Class.forName("org.colgram.singbox.ColgramPlatformInterface");

        int required = 0;
        for (java.lang.reflect.Method wanted : contract.getMethods()) {
            required++;
            try {
                ours.getMethod(wanted.getName(), wanted.getParameterTypes());
            } catch (NoSuchMethodException e) {
                throw new AssertionError("PlatformInterface is missing " + wanted.getName()
                        + "; the engine looks it up by name and would crash without it");
            }
        }
        Log.i(TAG, "ColgramPlatformInterface implements all " + required + " binding methods");

        // And it has to be constructible, since that is how the service installs it.
        Object instance = ours.getConstructor(Context.class).newInstance(context);
        assertNotNull(instance);
    }
}
