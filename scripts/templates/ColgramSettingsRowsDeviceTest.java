package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Menu wiring and explicit-off regressions without a Telegram login or external network. */
@RunWith(AndroidJUnit4.class)
public final class ColgramSettingsRowsDeviceTest {
    @Test
    public void everyDeclaredRowHasAUniquePositionAndSettingsRowsHaveTheRightCell() throws Throwable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Class<?> settings = Class.forName("org.telegram.ui.ColgramSettingsActivity", true, context.getClassLoader());
                Object screen = settings.getDeclaredConstructor().newInstance();
                Method update = settings.getDeclaredMethod("updateRows");
                update.setAccessible(true);
                update.invoke(screen);
                Set<Integer> positions = new HashSet<>();
                for (Field field : settings.getDeclaredFields()) {
                    if (field.getType() != int.class || !field.getName().endsWith("Row")
                            || Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    int position = field.getInt(screen);
                    assertTrue(field.getName() + " is hidden", position >= 0);
                    assertTrue(field.getName() + " overlaps another row", positions.add(position));
                }
                Field count = settings.getDeclaredField("rowCount");
                count.setAccessible(true);
                assertEquals(count.getInt(screen), positions.size());
                Class<?> adapterType = Class.forName("org.telegram.ui.ColgramSettingsActivity$ListAdapter",
                        true, context.getClassLoader());
                Constructor<?> constructor = adapterType.getDeclaredConstructor(settings, Context.class);
                constructor.setAccessible(true);
                Object adapter = constructor.newInstance(screen, context);
                Method viewType = adapterType.getDeclaredMethod("getItemViewType", int.class);
                viewType.setAccessible(true);
                for (String row : new String[]{"subscriptionRow", "directConnectionRow", "cloudflareWarpRow",
                        "dataSettingsRow", "cacheSettingsRow"}) {
                    Field field = settings.getDeclaredField(row);
                    field.setAccessible(true);
                    assertEquals(row, 2, ((Number) viewType.invoke(adapter, field.getInt(screen))).intValue());
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    @Test
    public void initializationPreservesAnExplicitlyDisabledAutoProxy() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences isolated = target.getSharedPreferences("colgram_regression_config", Context.MODE_PRIVATE);
        isolated.edit().clear().putBoolean("auto_proxy_enabled", false).commit();
        Context context = new ContextWrapper(target) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return isolated;
            }
        };
        Class<?> config = Class.forName("org.colgram.core.ColgramConfig", true, target.getClassLoader());
        Field preferences = config.getDeclaredField("prefs");
        preferences.setAccessible(true);
        Object original = preferences.get(null);
        try {
            preferences.set(null, null);
            config.getMethod("init", Context.class).invoke(null, context);
            assertFalse((Boolean) config.getMethod("isAutoProxyEnabled").invoke(null));
        } finally {
            preferences.set(null, original);
            isolated.edit().clear().commit();
        }
    }
}
