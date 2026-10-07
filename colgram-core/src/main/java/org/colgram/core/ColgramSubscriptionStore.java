package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the subscription the user pasted and decides which profile the tunnel runs.
 *
 * The link a VPN bot gives is the whole input, and it is a secret: the user's paid access to
 * someone else's server. So it is kept in the app's private preferences, never logged, and never
 * written anywhere a backup or another app can read it. The profile the engine runs goes to the
 * app's own files directory, which is equally private and is the only path the engine accepts.
 *
 * Nothing here claims a node works. Parsing says a node is well formed; only the engine, once the
 * tunnel is up, can say whether it carries anything.
 */
public final class ColgramSubscriptionStore {

    private static final String TAG = "ColgramSubStore";
    private static final String PREFS = "colgram_subscription";
    private static final String KEY_LINK = "link";
    private static final String KEY_NODE_INDEX = "node_index";

    private static final String PROFILE_FILE = "profile.json";

    /**
     * The profile directory, derived from the context rather than hard-coded.
     *
     * A literal path works until it does not: a different package, a work profile, a second
     * install all break it, and it fails as a permission error on a directory that was never
     * there. The engine reads whatever path it is given, so the app's own files directory is both
     * the correct answer and the one that cannot be wrong.
     */
    private static File profileDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "singbox");
    }

    private ColgramSubscriptionStore() {}

    /** What the settings screen shows: how many nodes, and which one is in use. */
    public static final class State {
        public final int total;
        public final int selected;
        public final String name;
        public final String protocol;

        State(int total, int selected, String name, String protocol) {
            this.total = total;
            this.selected = selected;
            this.name = name;
            this.protocol = protocol;
        }
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Store a subscription link and work out whether it can be used at all.
     *
     * A link that parses to nothing usable is rejected here, with the reason, rather than producing
     * a tunnel that comes up and routes nowhere.
     */
    public static State save(Context context, String link) {
        if (link == null || link.trim().isEmpty()) {
            throw new IllegalArgumentException("ссылка пустая");
        }
        List<ColgramSubscription.Node> nodes = usable(context, link);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("в подписке нет подходящих узлов");
        }
        prefs(context).edit().putString(KEY_LINK, link.trim()).apply();
        Log.i(TAG, "subscription stored with " + nodes.size() + " usable nodes");
        return stateOf(nodes, 0);
    }

    /** Parse a link without keeping it, so the UI can preview before committing. */
    public static List<ColgramSubscription.Node> usable(Context context, String link) {
        List<ColgramSubscription.Node> all = ColgramSubscription.parse(link);
        List<ColgramSubscription.Node> out = new ArrayList<>();
        for (ColgramSubscription.Node node : all) {
            try {
                // Building it is the filter: a node the builder cannot express is one the engine
                // would reject, and shipping it in a profile only moves the failure later.
                ColgramProfileBuilder.forNode(node);
                out.add(node);
            } catch (Throwable t) {
                Log.i(TAG, "dropping " + node.protocol + " node: " + t.getMessage());
            }
        }
        return out;
    }

    public static boolean hasSubscription(Context context) {
        String link = prefs(context).getString(KEY_LINK, null);
        return link != null && !link.isEmpty();
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_LINK).remove(KEY_NODE_INDEX).apply();
        File profile = new File(profileDir(context), PROFILE_FILE);
        if (profile.exists()) profile.delete();
    }

    /** The current selection, or null when nothing is stored. */
    public static State current(Context context) {
        String link = prefs(context).getString(KEY_LINK, null);
        if (link == null || link.isEmpty()) return null;
        List<ColgramSubscription.Node> nodes;
        try {
            nodes = usable(context, link);
        } catch (Throwable t) {
            return null;
        }
        if (nodes.isEmpty()) return null;
        int index = Math.max(0, Math.min(nodes.size() - 1,
                prefs(context).getInt(KEY_NODE_INDEX, 0)));
        return stateOf(nodes, index);
    }

    /**
     * Point the tunnel at one node.
     *
     * The profile covers every usable node in a failover group, and the index only decides which is
     * tried first. That is deliberate: writing a single node would leave a blocked subscription
     * with no way through, which is the failure this whole exercise exists to remove.
     */
    public static State select(Context context, int index) {
        String link = prefs(context).getString(KEY_LINK, null);
        if (link == null || link.isEmpty()) {
            throw new IllegalStateException("подписка не сохранена");
        }
        List<ColgramSubscription.Node> nodes = usable(context, link);
        if (nodes.isEmpty()) throw new IllegalStateException("в подписке нет узлов");
        if (index < 0 || index >= nodes.size()) throw new IllegalArgumentException("нет такого узла");
        writeProfile(context, nodes);
        prefs(context).edit().putInt(KEY_NODE_INDEX, index).apply();
        return stateOf(nodes, index);
    }

    /** The profile file the engine is pointed at, or null when there is nothing to run. */
    public static String profilePath(Context context) {
        if (!hasSubscription(context)) return null;
        select(context, prefs(context).getInt(KEY_NODE_INDEX, 0));
        File profile = new File(profileDir(context), PROFILE_FILE);
        return profile.exists() ? profile.getAbsolutePath() : null;
    }

    private static void writeProfile(Context context, List<ColgramSubscription.Node> nodes) {
        String json = ColgramProfileBuilder.forNodes(nodes);
        File directory = profileDir(context);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("не удалось создать каталог для профиля");
        }
        try (FileOutputStream out = new FileOutputStream(new File(directory, PROFILE_FILE))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("не удалось записать профиль: " + e.getMessage(), e);
        }
    }

    private static State stateOf(List<ColgramSubscription.Node> nodes, int index) {
        int safe = Math.max(0, Math.min(index, nodes.size() - 1));
        ColgramSubscription.Node node = nodes.get(safe);
        return new State(nodes.size(), safe,
                node.name != null ? node.name : node.protocol, node.protocol);
    }
}
