package org.colgram.core;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Actual pager progress and bitmap evidence for all custom welcome scenes. */
@RunWith(AndroidJUnit4.class)
public final class ColgramIntroSlidesDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test
    public void customScenesSwipeInBothOrientationsAndKeepExactEndpointArt() throws Throwable {
        Activity activity = launch();
        int oldOrientation = activity.getRequestedOrientation();
        try {
            for (int orientation : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                AtomicReference<Object> screen = new AtomicReference<>();
                onMain(() -> {
                    Object intro = load("org.telegram.ui.IntroActivity").getConstructor().newInstance();
                    activity.getClass().getMethod("presentFragment", load("org.telegram.ui.ActionBar.BaseFragment"), boolean.class, boolean.class)
                            .invoke(activity, intro, true, true);
                    screen.set(intro);
                    activity.setRequestedOrientation(orientation);
                });
                Thread.sleep(1200);
                AtomicReference<View> artRef = new AtomicReference<>();
                AtomicReference<Object> pagerRef = new AtomicReference<>();
                onMain(() -> {
                    View root = (View) screen.get().getClass().getMethod("getFragmentView").invoke(screen.get());
                    assertNotNull(root);
                    View art = root.findViewWithTag("colgram_intro_art");
                    assertNotNull("Custom art did not attach", art);
                    assertEquals("org.telegram.ui.ColgramIntroArtView", art.getClass().getName());
                    assertTrue(art.isShown());
                    assertTrue(art.getWidth() > 0 && art.getHeight() > 0);
                    assertFalse("An unused EGL/TextureView renderer remains attached", hasTextureView(root));
                    assertNotNull("Shield disappeared", root.findViewWithTag("colgram_network_menu"));
                    art.getClass().getMethod("setMotionEnabled", boolean.class).invoke(art, false);
                    artRef.set(art);
                    Field field = screen.get().getClass().getDeclaredField("viewPager");
                    field.setAccessible(true);
                    pagerRef.set(field.get(screen.get()));
                });
                View art = artRef.get();
                Object pager = pagerRef.get();
                Method select = pager.getClass().getMethod("setCurrentItem", int.class, boolean.class);
                Set<Long> sceneHashes = new HashSet<>();
                for (int page = 0; page < 6; page++) {
                    final int index = page;
                    onMain(() -> select.invoke(pager, index, false));
                    Thread.sleep(80);
                    AtomicReference<Bitmap> bitmapRef = new AtomicReference<>();
                    onMain(() -> {
                        assertEquals("The pager did not report its selected page", index, progress(art), .001f);
                        Bitmap actual = render(art);
                        if (index == 0 || index == 5) {
                            Bitmap expected = endpoint(art, index == 0);
                            try {
                                assertTrue("Endpoint artwork changed on page " + index, actual.sameAs(expected));
                            } finally {
                                expected.recycle();
                            }
                        }
                        bitmapRef.set(actual);
                    });
                    Bitmap bitmap = bitmapRef.get();
                    long hash = hash(bitmap);
                    assertTrue("Two welcome pages render the same artwork", sceneHashes.add(hash));
                    save(bitmap, "IntroArt-" + orientation + "-page" + page);
                    Log.i("ColgramIntroSlides", "PAGE orientation=" + orientation + " page=" + page + " hash=" + hash);
                    bitmap.recycle();
                }
                for (int page = 0; page < 5; page++) {
                    final int from = page;
                    onMain(() -> {
                        select.invoke(pager, from, false);
                        assertTrue((Boolean) pager.getClass().getMethod("beginFakeDrag").invoke(pager));
                        int width = ((View) pager).getWidth();
                        pager.getClass().getMethod("fakeDragBy", float.class).invoke(pager, -width * .48f);
                        float progress = progress(art);
                        assertTrue("Swipe did not reach an in-between custom scene: " + progress,
                                progress > from + .1f && progress < from + .9f);
                    });
                    Thread.sleep(50);
                    AtomicReference<Bitmap> transition = new AtomicReference<>();
                    onMain(() -> transition.set(render(art)));
                    save(transition.get(), "IntroSwipe-" + orientation + "-from" + page);
                    transition.get().recycle();
                    onMain(() -> pager.getClass().getMethod("endFakeDrag").invoke(pager));
                    Thread.sleep(700);
                    Log.i("ColgramIntroSlides", "SWIPE orientation=" + orientation + " from=" + page + " progress_verified=true");
                }
                onMain(() -> {
                    select.invoke(pager, 2, false);
                    art.getClass().getMethod("setMotionEnabled", boolean.class).invoke(art, true);
                });
                Thread.sleep(1200);
                onMain(() -> {
                    Field animation = art.getClass().getDeclaredField("entryAnimator");
                    animation.setAccessible(true);
                    ValueAnimator animator = (ValueAnimator) animation.get(art);
                    assertTrue("Entry animation keeps running indefinitely", animator == null || !animator.isRunning());
                    art.getClass().getMethod("setMotionEnabled", boolean.class).invoke(art, false);
                    assertNull("Pausing the intro leaves an animator attached", animation.get(art));
                });
            }
        } finally {
            onMain(() -> activity.setRequestedOrientation(oldOrientation));
        }
    }

    private Activity launch() throws Throwable {
        Class<?> launch = load("org.telegram.ui.LaunchActivity");
        onMain(() -> context.startActivity(new Intent(context, launch)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        AtomicReference<Activity> activity = new AtomicReference<>();
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (activity.get() == null && SystemClock.elapsedRealtime() < deadline) {
            onMain(() -> activity.set((Activity) launch.getField("instance").get(null)));
            Thread.sleep(100);
        }
        assertNotNull("LaunchActivity did not appear", activity.get());
        Thread.sleep(700);
        return activity.get();
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, true, context.getClassLoader());
    }

    private static boolean hasTextureView(View view) {
        if (view instanceof TextureView) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasTextureView(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static float progress(View art) throws Exception {
        Field field = art.getClass().getDeclaredField("pageProgress");
        field.setAccessible(true);
        return field.getFloat(art);
    }

    private static Bitmap render(View art) {
        Bitmap bitmap = Bitmap.createBitmap(art.getWidth(), art.getHeight(), Bitmap.Config.ARGB_8888);
        art.draw(new Canvas(bitmap));
        return bitmap;
    }

    private Bitmap endpoint(View art, boolean welcome) {
        Bitmap expected = Bitmap.createBitmap(art.getWidth(), art.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(expected);
        float scale = Math.min(art.getWidth() / 240f, art.getHeight() / 180f);
        canvas.translate((art.getWidth() - 240 * scale) / 2f, (art.getHeight() - 180 * scale) / 2f);
        canvas.scale(scale, scale);
        int resource = context.getResources().getIdentifier(welcome ? "colgram_app_avatar" : "colgram_intro_cloud", "drawable", context.getPackageName());
        assertTrue("Original endpoint asset disappeared", resource != 0);
        Drawable drawable = context.getResources().getDrawable(resource, context.getTheme()).mutate();
        if (welcome) {
            Path clip = new Path();
            clip.addRoundRect(new RectF(56, 26, 184, 154), 28, 28, Path.Direction.CW);
            canvas.clipPath(clip);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            canvas.drawRect(56, 26, 184, 154, paint);
            drawable.setBounds(56, 26, 184, 154);
        } else {
            drawable.setBounds(30, 27, 210, 153);
        }
        drawable.draw(canvas);
        return expected;
    }

    private static long hash(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        long hash = 0xcbf29ce484222325L;
        for (int pixel : pixels) hash = (hash ^ pixel) * 0x100000001b3L;
        return hash;
    }

    private void save(Bitmap bitmap, String label) throws Exception {
        File file = new File(context.getExternalFilesDir(null), "smoke-" + label + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        Log.i("ColgramIntroSlides", "SCREENSHOT " + file.getAbsolutePath());
    }

    private void onMain(Action action) throws Throwable {
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() != null) throw error.get();
    }

    private interface Action { void run() throws Throwable; }
}
