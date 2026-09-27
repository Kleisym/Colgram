package org.colgram.core;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

/**
 * Hammers the WARP enable/disable path, which is where the app most often got stuck.
 *
 * The reported symptom was a toggle that would not switch, followed by an app that sometimes
 * died outright. Both are consistent with state being left half-applied: bringUp wrote the
 * enabled flag, the backend refused, and something downstream still assumed a live tunnel. A
 * test that only calls bringUp once cannot see that - it needs the pair driven back and forth,
 * the way a user who keeps tapping the row does.
 *
 * Every call may legitimately fail - this network blocks every Cloudflare UDP endpoint - but it
 * must never leave a live-looking flag with no recorded reason, because that is exactly the
 * state that was reported as a switch that would not turn on.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramWarpChurnDeviceTest {

    private static final int ROUNDS = 5;

    private Context context;
    private Class<?> config;
    private Class<?> tunnel;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        ClassLoader loader = context.getClassLoader();
        config = Class.forName("org.colgram.core.ColgramConfig", true, loader);
        tunnel = Class.forName("org.colgram.core.ColgramWarpTunnel", true, loader);
        invoke(config, "init", new Class<?>[]{Context.class}, context);
    }

    @Test
    public void togglingRepeatedlyLeavesAConsistentState() throws Exception {
        Method isEnabled = config.getMethod("isWarpEnabled");
        Method isUp = tunnel.getMethod("isUp");
        Method lastFailure = tunnel.getMethod("lastFailureReason");

        for (int round = 0; round < ROUNDS; round++) {
            // Deliberately NOT setting the flag first. The UI no longer does either - it writes
            // the flag only after bringUp succeeds - so a test that sets it first is asserting a
            // contract the app deliberately broke. What must hold is that bringing the tunnel
            // up and down repeatedly leaves nothing half-applied either way.
            try {
                invoke(tunnel, "bringUp", new Class<?>[]{Context.class}, context);
            } catch (Throwable refused) {
                // A refusal is a fine outcome. An inconsistent state afterwards is not.
            }
            // The tunnel call itself is the only thing that may turn the flag on.
            if ((Boolean) isUp.invoke(null)) {
                setWarp(true);
            }
            assertConsistent(round, isEnabled, isUp, lastFailure);

            setWarp(false);
            invoke(tunnel, "bringDown", new Class<?>[]{Context.class}, context);
            assertConsistent(round, isEnabled, isUp, lastFailure);
            assertTrue("bringDown must always leave the tunnel reported as down",
                    !((Boolean) isUp.invoke(null)));
        }
    }

    @Test
    public void bringDownIsSafeBeforeAnyBringUp() throws Exception {
        // The settings screen tears the tunnel down on the way out whether or not it ever came
        // up. Doing that from a cold state used to be the cheapest way to reach a null backend.
        setWarp(false);
        invoke(tunnel, "bringDown", new Class<?>[]{Context.class}, context);
        invoke(tunnel, "bringDown", new Class<?>[]{Context.class}, context);
        assertTrue("the tunnel must report itself down after a cold teardown",
                !((Boolean) tunnel.getMethod("isUp").invoke(null)));
    }

    @Test
    public void aFailedBringUpNeverLeavesTheFlagClaimingWarpIsOn() throws Exception {
        // The invariant the reported bug violated: whatever bringUp does, it must not leave
        // ColgramConfig claiming WARP is enabled while nothing is running and no reason exists.
        Method isEnabled = config.getMethod("isWarpEnabled");
        Method isUp = tunnel.getMethod("isUp");
        Method lastFailure = tunnel.getMethod("lastFailureReason");
        setWarp(false);
        try {
            invoke(tunnel, "bringUp", new Class<?>[]{Context.class}, context);
        } catch (Throwable refused) {
            // Expected on a filtered network.
        }
        boolean up = (Boolean) isUp.invoke(null);
        boolean enabled = (Boolean) isEnabled.invoke(null);
        String failure = (String) lastFailure.invoke(null);
        assertTrue("bringUp left WARP flagged on (running=" + up + ", reason=" + failure + ") "
                        + "with nothing behind it",
                !enabled || up || (failure != null && !failure.isEmpty()));
        setWarp(false);
        invoke(tunnel, "bringDown", new Class<?>[]{Context.class}, context);
    }

    private static void assertConsistent(int round, Method isEnabled, Method isUp,
                                         Method lastFailure) throws Exception {
        boolean enabled = (Boolean) isEnabled.invoke(null);
        boolean up = (Boolean) isUp.invoke(null);
        String failure = (String) lastFailure.invoke(null);
        if (up) {
            return; // a genuinely running tunnel needs no excuse
        }
        assertTrue("round " + round + ": the flag says enabled but nothing is running and no "
                        + "reason was recorded - the reported state where the switch looks on "
                        + "and carries nothing",
                !enabled || (failure != null && !failure.isEmpty()));
    }

    private void setWarp(boolean value) throws Exception {
        config.getMethod("setWarpEnabled", boolean.class).invoke(null, value);
    }

    private static Object invoke(Class<?> type, String method, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        try {
            return type.getMethod(method, parameterTypes).invoke(null, arguments);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }
}
