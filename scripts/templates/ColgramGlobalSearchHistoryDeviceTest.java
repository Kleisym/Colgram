package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Account-free on-device check for global search history.
 *
 * The history round-trips through the real global SharedPreferences as a newline-joined
 * string, which is exactly where a regression hides: a query containing a newline silently
 * truncates itself into two history entries, and a stale entry survives a case-insensitive
 * re-search. Neither is visible to a test that only reads the source.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramGlobalSearchHistoryDeviceTest {

    private static final String KEY = "global_search_history";
    private static final int LIMIT = 20;

    private Context context;
    private SharedPreferences settings;
    private Class<?> pager;
    private Object instance;
    private String savedBefore;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClassLoader loader = context.getClassLoader();
        Class<?> controller = Class.forName("org.telegram.messenger.MessagesController", true, loader);
        settings = (SharedPreferences) controller.getMethod("getGlobalMainSettings").invoke(null);
        savedBefore = settings.getString(KEY, null);
        settings.edit().remove(KEY).commit();

        pager = Class.forName("org.telegram.ui.Components.SearchViewPager", true, loader);
        // The only constructor wants a DialogsActivity, which needs a logged-in account. The
        // history methods touch nothing but MessagesController's global prefs, so an instance
        // allocated without running any constructor exercises exactly the same code path.
        instance = allocate(pager);
    }

    @After
    public void tearDown() {
        settings.edit().remove(KEY).commit();
        if (savedBefore != null) {
            settings.edit().putString(KEY, savedBefore).commit();
        }
    }

    @Test
    public void historyRoundTripsAndSurvivesAwkwardQueries() throws Exception {
        assertTrue(getHistory().isEmpty());

        save("cats");
        save("  dogs  ");
        List<String> history = getHistory();
        assertEquals(2, history.size());
        assertEquals("dogs", history.get(0));
        assertEquals("cats", history.get(1));

        // A one-character query is noise, not a search.
        save("a");
        assertEquals(2, getHistory().size());

        // Re-searching moves an existing entry to the front instead of duplicating it, and keeps
        // the spelling it was first saved with - typing CATS must not rewrite "cats".
        save("CATS");
        history = getHistory();
        assertEquals(2, history.size());
        assertEquals("cats", history.get(0));

        // A newline in a query would otherwise split it into two entries on the next read.
        save("two\nlines");
        history = getHistory();
        assertEquals(3, history.size());
        assertFalse("a newline in a query must not become a history entry",
                history.contains("lines"));

        remove("dogs");
        assertFalse(getHistory().contains("dogs"));
    }

    @Test
    public void historyIsCappedAndOldestEntriesFallOff() throws Exception {
        for (int i = 0; i < LIMIT + 5; i++) {
            save("query" + i);
        }
        List<String> history = getHistory();
        assertEquals(LIMIT, history.size());
        assertEquals("query" + (LIMIT + 4), history.get(0));
        assertFalse("the oldest entry must have fallen off", history.contains("query0"));
    }

    @SuppressWarnings("unchecked")
    private List<String> getHistory() throws Exception {
        Method method = pager.getDeclaredMethod("getGlobalSearchHistory");
        method.setAccessible(true);
        return (ArrayList<String>) method.invoke(instance);
    }

    private void save(String text) throws Exception {
        Method method = pager.getDeclaredMethod("saveGlobalSearchHistory", String.class);
        method.setAccessible(true);
        method.invoke(instance, text);
    }

    private void remove(String query) throws Exception {
        Method method = pager.getDeclaredMethod("removeGlobalSearchHistory", String.class);
        method.setAccessible(true);
        method.invoke(instance, query);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }
}
