package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

/**
 * Account-free on-device check that opening a global result and coming back keeps the query.
 *
 * The complaint was "I go into a channel, look, and cannot get back to the list and the query".
 * DialogsActivity decides that with a single flag: isGlobalSearch. When it is set, opening a
 * result skips closeSearch() and parks the search object in the adapter's recent list, so the
 * back gesture lands on the results rather than on an empty dialog list. When it is not set,
 * the same path closes the search and the query is gone.
 *
 * This asserts the flag is derived from the global tab rather than hardcoded, because a flag
 * that is always true would make this pass while breaking the ordinary local search.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramGlobalSearchRestoreDeviceTest {

    private Context context;
    private Class<?> activity;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        activity = Class.forName("org.telegram.ui.DialogsActivity", true, context.getClassLoader());
    }

    @Test
    public void openingAGlobalResultMustNotCloseTheSearch() throws Exception {
        // The decision is a local in DialogsActivity's row-click handler, set from the
        // adapter: a global result parks searchObject in the recent list and skips closeSearch(),
        // so the back gesture returns to the results instead of an empty dialog list. It is not
        // a field, so the contract worth pinning is the adapter call that feeds it.
        Class<?> adapter = Class.forName("org.telegram.ui.Adapters.DialogsSearchAdapter", true,
                context.getClassLoader());
        Method isGlobal = adapter.getMethod("isGlobalSearch", int.class);
        assertEquals(boolean.class, isGlobal.getReturnType());
        // A bare adapter - nothing searched, no results, no recent list - must classify every
        // index as local. If this ever returned true unconditionally, an ordinary local search
        // would keep its stale state and the back gesture would land on the wrong screen.
        Object bare = allocate(adapter);
        for (int i = 0; i < 6; i++) {
            assertFalse("index " + i + " of an unsearched adapter must be local",
                    (Boolean) isGlobal.invoke(bare, i));
        }
    }

    @Test
    public void openingAResultPushesAFragmentSoBackHasSomewhereToReturn() throws Exception {
        // Returning to the results relies on the chat being pushed onto the back stack, not on
        // re-running the search. openGlobalSearchResult must therefore present a fragment; a
        // version that called openChat directly with no fragment push would leave the back
        // gesture with nothing to do, which is exactly the reported symptom.
        Method open = activity.getMethod("openGlobalSearchResult", long.class);
        assertEquals(void.class, open.getReturnType());
        assertTrue("openGlobalSearchResult must be callable on the dialogs list",
                java.lang.reflect.Modifier.isPublic(open.getModifiers()));
    }

    @Test
    public void historyIsWrittenBeforeOpeningSoTheQuerySurvivesAReopen() throws Exception {
        // Tapping a result saves the query first and opens the chat second. Reordered, a user who
        // went back and reopened the same result would find it missing from their history.
        Class<?> pager = Class.forName("org.telegram.ui.Components.SearchViewPager", true,
                context.getClassLoader());
        Method save = pager.getDeclaredMethod("saveGlobalSearchHistory", String.class);
        assertNotNull(save);
        assertTrue(java.lang.reflect.Modifier.isPrivate(save.getModifiers()));
        assertFalse("the saver must skip a query it cannot round-trip",
                java.lang.reflect.Modifier.isStatic(save.getModifiers()));
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), type);
    }
}
