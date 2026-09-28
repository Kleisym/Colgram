package org.colgram.singbox;

import android.content.Context;
import android.util.Log;

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
        Object version = platform.getMethod("getGoVersion").invoke(
                platform.getField("INSTANCE").get(null));
        Log.i(TAG, "sing-box go version: " + version);
        Class<?> client = Class.forName("io.nekohasekai.libbox.CommandClient");
        Log.i(TAG, "CommandClient present: " + client.getName());
    }
}
