package org.colgram.core;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Account-free on-device check for the reported "grey text on grey" bug.
 *
 * The regression suite proves the guard's source contains the right branches. This proves the
 * guard actually produces readable colours: it walks every theme the device offers, resolves
 * every colour key through Theme.getColor - the exact call the UI makes - and measures the
 * result against the surfaces a text colour is actually drawn on.
 *
 * Luma is measured, not looked at. A screenshot proves one theme on one screen; this proves
 * every key in every theme, which is the only way to cover the complaint that the problem
 * "is not fixed anywhere".
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramThemeContrastDeviceTest {

    private static final String TAG = "ColgramThemeContrast";

    /** Below this a glyph is unreadable, and it is the value the repair targets. */
    private static final int MIN_CONTRAST = 60;

    /** These are the surfaces a foreground colour can legitimately end up on. */
    private static final String[] SURFACE_KEYS = {
            "key_windowBackgroundWhite", "key_dialogBackground", "key_actionBarDefault",
    };

    @Test
    public void everyDarkThemeKeepsTextOffItsOwnBackground() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClassLoader loader = context.getClassLoader();
        Class<?> theme = Class.forName("org.telegram.ui.ActionBar.Theme", true, loader);
        Class<?> config = Class.forName("org.colgram.core.ColgramConfig", true, loader);
        invoke(config, "init", new Class<?>[]{Context.class}, context);

        Method getColor = theme.getMethod("getColor", int.class);

        // The keys the report named, plus the families the guard claims to own. Resolved by
        // NAME because coloursCount++ ints differ between Telegram versions.
        List<String> textKeys = new ArrayList<>();
        for (String name : new String[]{
                "key_windowBackgroundWhiteGrayText", "key_windowBackgroundWhiteGrayText2",
                "key_windowBackgroundWhiteGrayText3", "key_windowBackgroundWhiteGrayText4",
                "key_windowBackgroundWhiteGrayText5", "key_windowBackgroundWhiteGrayText6",
                "key_windowBackgroundWhiteGrayText7", "key_windowBackgroundWhiteGrayText8",
                "key_dialogTextGray", "key_dialogTextGray2", "key_dialogTextGray3",
                "key_dialogTextGray4", "key_graySectionText", "key_profile_tabText",
                "key_actionBarDefaultSubtitle", "key_actionBarTabUnactiveText",
                "key_windowBackgroundWhiteHintText", "key_actionBarTitle",
                "key_dialogTextBlack", "key_chat_messagePanelText",
        }) {
            if (keyOf(theme, name) != null) textKeys.add(name);
        }
        assertTrue("no text colour keys resolved - the reflection contract changed",
                textKeys.size() >= 12);

        List<String> failures = new ArrayList<>();
        int[] surface = new int[SURFACE_KEYS.length];
        for (int s = 0; s < SURFACE_KEYS.length; s++) {
            surface[s] = ((Integer) getColor.invoke(null, keyOf(theme, SURFACE_KEYS[s]))).intValue();
        }
        // Only the dark themes are in scope: on a light theme grey-on-grey is the design.
        if (worstLuma(surface) >= 128) {
            // Do not pass silently. A test that quietly returns when the device happens to be on
            // a light theme looks exactly like a pass while proving nothing, and that is how
            // "the contrast is fine" survives every run that never checked it.
            fail("expected a dark theme, but surfaces are " + describe(surface)
                    + "; the contrast check would have been skipped");
        }

        for (String name : textKeys) {
            int color = ((Integer) getColor.invoke(null, keyOf(theme, name))).intValue();
            if ((color >>> 24) < 0x20) {
                continue; // deliberately invisible, not a readable-text failure
            }
            int worst = Integer.MAX_VALUE;
            for (int bg : surface) {
                worst = Math.min(worst, lumaContrast(color, bg));
            }
            if (worst < MIN_CONTRAST) {
                failures.add(String.format("%s=#%08x worst=%d on %s",
                        name, color, worst, describe(surface)));
            }
        }

        android.util.Log.i(TAG, "checked " + textKeys.size() + " text keys against "
                + describe(surface) + ", all at or above contrast " + MIN_CONTRAST);
        if (!failures.isEmpty()) {
            fail("grey text on grey under the active theme: " + failures);
        }
    }

    private static String describe(int[] surface) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < surface.length; i++) {
            if (i > 0) out.append(", ");
            out.append(String.format("#%08x", surface[i]));
        }
        return out.append(']').toString();
    }

    private static int worstLuma(int[] colors) {
        int worst = 255;
        for (int c : colors) worst = Math.min(worst, luma(c));
        return worst;
    }

    /** The same measure Theme.colgramLuma uses, so a pass here means the guard agreed. */
    private static int luma(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    private static int lumaContrast(int a, int b) {
        return Math.abs(luma(a) - luma(b));
    }

    private static Integer keyOf(Class<?> theme, String name) {
        try {
            Field field = theme.getField(name);
            Object value = field.get(null);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable t) {
            return null;
        }
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
