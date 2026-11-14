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

/** Menu wiring and permanent-base regressions without a Telegram login or external network. */
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
    public void shieldRoutesAreVisibleDistinctAndSelectableWithoutALogin() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Class<?> screenType = Class.forName("org.telegram.ui.ProxyListActivity", true, context.getClassLoader());
                Object screen = screenType.getDeclaredConstructor().newInstance();
                Method update = screenType.getDeclaredMethod("updateRows", boolean.class);
                update.setAccessible(true);
                update.invoke(screen, false);
                Class<?> adapterType = Class.forName("org.telegram.ui.ProxyListActivity$ListAdapter", true, context.getClassLoader());
                Constructor<?> constructor = adapterType.getDeclaredConstructor(screenType, Context.class);
                constructor.setAccessible(true);
                androidx.recyclerview.widget.RecyclerView.Adapter<?> adapter =
                        (androidx.recyclerview.widget.RecyclerView.Adapter<?>) constructor.newInstance(screen, context);
                Set<Integer> positions = new HashSet<>();
                Set<Long> ids = new HashSet<>();
                int proxyType = adapter.getItemViewType(row(screenType, screen, "useProxyRow"));
                int buttonType = adapter.getItemViewType(row(screenType, screen, "callsRow"));
                for (String name : new String[]{"useProxyRow", "callsRow", "warpRow", "colgramBackgroundProxyRow",
                        "colgramDpiRow", "colgramSubscriptionImportRow", "colgramSubscriptionNodeRow",
                        "colgramSubscriptionToggleRow", "colgramSubscriptionRemoveRow"}) {
                    int position = row(screenType, screen, name);
                    assertTrue(name + " is hidden", position >= 0 && position < adapter.getItemCount());
                    assertTrue(name + " overlaps another row", positions.add(position));
                    assertTrue(name + " reuses an adapter ID", ids.add(adapter.getItemId(position)));
                    if (name.startsWith("colgramSubscription")) assertEquals(buttonType, adapter.getItemViewType(position));
                    if ("colgramDpiRow".equals(name)) assertTrue("The base is still a switch", proxyType != adapter.getItemViewType(position));
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void bothLocalBaseRowsAreRealReadOnlyInformationCells() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                for (String name : new String[]{"ProxyListActivity", "ColgramSettingsActivity"}) {
                    Class<?> screenType = Class.forName("org.telegram.ui." + name, true, context.getClassLoader());
                    Object screen = screenType.getDeclaredConstructor().newInstance();
                    boolean shield = "ProxyListActivity".equals(name);
                    Method update = shield ? screenType.getDeclaredMethod("updateRows", boolean.class)
                            : screenType.getDeclaredMethod("updateRows");
                    update.setAccessible(true);
                    if (shield) update.invoke(screen, false); else update.invoke(screen);
                    Class<?> adapterType = Class.forName("org.telegram.ui." + name + "$ListAdapter", true, context.getClassLoader());
                    Constructor<?> constructor = adapterType.getDeclaredConstructor(screenType, Context.class);
                    constructor.setAccessible(true);
                    androidx.recyclerview.widget.RecyclerView.Adapter adapter =
                            (androidx.recyclerview.widget.RecyclerView.Adapter) constructor.newInstance(screen, context);
                    int position = row(screenType, screen, shield ? "colgramDpiRow" : "dpiBypassRow");
                    androidx.recyclerview.widget.RecyclerView.ViewHolder holder = adapter.createViewHolder(
                            new android.widget.FrameLayout(context), adapter.getItemViewType(position));
                    if (shield) {
                        adapter.bindViewHolder(holder, row(screenType, screen, "colgramBackgroundProxyRow"));
                        assertTrue("The source summary does not own its tap", holder.itemView.isClickable());
                        org.telegram.ui.Cells.TextInfoPrivacyCell summary =
                                (org.telegram.ui.Cells.TextInfoPrivacyCell) holder.itemView;
                        assertTrue("The links movement handler still intercepts source details", summary.getTextView().getMovementMethod() == null);
                        assertFalse("The source child still intercepts its parent", summary.getTextView().isClickable());
                    }
                    // Recycle the actionable source cell into the non-interactive local status.
                    adapter.bindViewHolder(holder, position);
                    assertTrue(name + " still uses a switch instead of read-only status",
                            holder.itemView instanceof org.telegram.ui.Cells.TextInfoPrivacyCell);
                    assertFalse(name + " base status accepts taps", holder.itemView.isClickable());
                    assertFalse(name + " base status accepts long presses", holder.itemView.isLongClickable());
                    assertTrue(name + " has no always-on explanation", containsText(holder.itemView,
                            "Локальный слой всегда включён", "Local base is always on"));
                }
                android.util.Log.i("ColgramBaseTest", "LOCAL_BASE_UI=PASS screens=2 info_cell=true switch=false click=false");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    private static boolean containsText(android.view.View view, String... expected) {
        if (view instanceof android.widget.TextView) {
            String text = ((android.widget.TextView) view).getText().toString();
            for (String value : expected) if (text.contains(value)) return true;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                if (containsText(group.getChildAt(index), expected)) return true;
        }
        return false;
    }

    private static int row(Class<?> type, Object screen, String fieldName) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(screen);
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
