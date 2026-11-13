package org.colgram.core;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/** Real, account-free welcome/login navigation, not just an inspection of row constants. */
@RunWith(AndroidJUnit4.class)
public final class ColgramOnboardingDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test
    public void networkMenuStaysVisibleAndOpensProxyPanelInBothOrientations() throws Throwable {
        Class<?> launch = Class.forName("org.telegram.ui.LaunchActivity", true, context.getClassLoader());
        Intent intent = new Intent(context, launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // The welcome animation continuously invalidates; it has no globally idle queue.
        onMain(() -> context.startActivity(intent));
        AtomicReference<Activity> launched = new AtomicReference<>();
        long launchDeadline = SystemClock.elapsedRealtime() + 15_000L;
        while (launched.get() == null && SystemClock.elapsedRealtime() < launchDeadline) {
            onMain(() -> launched.set((Activity) launch.getField("instance").get(null)));
            Thread.sleep(100L);
        }
        final Activity activity = launched.get();
        assertNotNull("LaunchActivity did not appear", activity);
        Thread.sleep(800L);
        int originalOrientation = activity.getRequestedOrientation();
        try {
            for (int orientation : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                onMain(() -> activity.setRequestedOrientation(orientation));
                Thread.sleep(1200L);
                for (String name : new String[]{"IntroActivity", "LoginActivity"}) {
                    AtomicReference<Object> fragment = new AtomicReference<>();
                    onMain(() -> {
                        Class<?> base = Class.forName("org.telegram.ui.ActionBar.BaseFragment", true, context.getClassLoader());
                        Object screen = Class.forName("org.telegram.ui." + name, true, context.getClassLoader()).getConstructor().newInstance();
                        launch.getMethod("presentFragment", base, boolean.class, boolean.class).invoke(activity, screen, true, true);
                        fragment.set(screen);
                    });
                    Thread.sleep(120L);
                    // Intro requests portrait in onResume; exercise a real post-resume rotation.
                    onMain(() -> activity.setRequestedOrientation(orientation));
                    Thread.sleep(1200L);
                    AtomicReference<View> menu = new AtomicReference<>();
                    onMain(() -> {
                        View root = (View) fragment.get().getClass().getMethod("getFragmentView").invoke(fragment.get());
                        assertNotNull(name + " did not attach a view", root);
                        View button = root.findViewWithTag("colgram_network_menu");
                        assertNotNull(name + " has no permanent network-menu control", button);
                        Rect bounds = new Rect();
                        assertTrue(name + " network menu is not shown", button.isShown());
                        assertTrue(name + " network menu is transparent", button.getAlpha() >= .99f);
                        assertTrue(name + " network menu has no visible touch rectangle", button.getGlobalVisibleRect(bounds));
                        int expected = orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                ? android.content.res.Configuration.ORIENTATION_PORTRAIT : android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                        assertEquals("The device did not rotate", expected, root.getResources().getConfiguration().orientation);
                        float density = root.getResources().getDisplayMetrics().density;
                        assertTrue(name + " touch target is below 48dp: " + bounds, bounds.width() >= 47 * density && bounds.height() >= 47 * density);
                        assertTrue(name + " network menu has no accessibility label", button.getContentDescription() != null);
                        menu.set(button);
                        Log.i("ColgramOnboardingTest", "MENU_VISIBLE screen=" + name + " orientation=" + orientation + " bounds=" + bounds);
                    });
                    capture(name + "-" + orientation);
                    // Let the old five-second login warning timer expire, then force a normal update.
                    if ("LoginActivity".equals(name)) {
                        Thread.sleep(5500L);
                        onMain(() -> {
                            Method update = fragment.get().getClass().getDeclaredMethod("updateProxyButton", boolean.class, boolean.class);
                            update.setAccessible(true);
                            update.invoke(fragment.get(), false, false);
                            assertTrue("A connection update hid the menu", menu.get().isShown());
                            assertEquals("A connection update faded the menu", 1f, menu.get().getAlpha(), .01f);
                        });
                    }
                    Rect touch = new Rect();
                    onMain(() -> assertTrue(menu.get().getGlobalVisibleRect(touch)));
                    long now = SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, touch.centerX(), touch.centerY(), 0);
                    MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, touch.centerX(), touch.centerY(), 0);
                    try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up); }
                    finally { down.recycle(); up.recycle(); }
                    Thread.sleep(120L);
                    Thread.sleep(650L);
                    onMain(() -> {
                        // Tablet onboarding lives in the layers layout, not LaunchActivity's
                        // main-layout-only static getLastFragment(). Inspect the actual owner.
                        Object layout = fragment.get().getClass().getMethod("getParentLayout").invoke(fragment.get());
                        Class<?> navigation = Class.forName("org.telegram.ui.ActionBar.INavigationLayout", true, context.getClassLoader());
                        Object selected = navigation.getMethod("getLastFragment").invoke(layout);
                        assertNotNull("Menu left its navigation layout empty", selected);
                        assertEquals("Shield must open the proxy/WARP panel, not general settings",
                                "org.telegram.ui.ProxyListActivity", selected.getClass().getName());
                        for (String row : new String[]{"warpRow", "warpDetailRow", "useProxyRow", "proxyAddRow"}) {
                            Field field = selected.getClass().getDeclaredField(row);
                            field.setAccessible(true);
                            assertTrue("Proxy panel lost " + row, field.getInt(selected) >= 0);
                        }
                    });
                    Thread.sleep(400L);
                    assertFalse("Opening the proxy menu must not start WARP", ColgramConfig.isWarpEnabled());
                    capture("ProxyMenu-" + name + "-" + orientation);
                    Log.i("ColgramOnboardingTest", "MENU_CLICK proxy_panel=true warp_rows=true screen=" + name + " orientation=" + orientation);
                }
            }
        } finally {
            onMain(() -> activity.setRequestedOrientation(originalOrientation));
        }
    }

    private void capture(String label) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Screenshot capture failed", bitmap);
        File output = new File(context.getExternalFilesDir(null), "smoke-" + label + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
        Log.i("ColgramOnboardingTest", "SCREENSHOT " + output.getAbsolutePath());
    }

    private void onMain(Action action) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
    }

    private interface Action { void run() throws Throwable; }
}
