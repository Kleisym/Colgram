package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
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
    private static final String KEY_NODE_TOTAL = "node_total";
    private static final String KEY_NODE_NAME = "node_name";
    private static final String KEY_NODE_PROTOCOL = "node_protocol";

    private static final String PROFILE_FILE = "profile.json";
    private static final int MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024;

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
        String content = link.trim();
        if (content.startsWith("https://") || content.startsWith("http://")) {
            content = fetchSubscription(content);
        }
        List<ColgramSubscription.Node> nodes = usable(context, content);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("в подписке нет подходящих узлов");
        }
        writeProfile(context, nodes.get(0));
        State state = stateOf(nodes, 0);
        SharedPreferences.Editor ed = prefs(context).edit().putString(KEY_LINK, content);
        if (link.trim().startsWith("https://") || link.trim().startsWith("http://")) {
            ed.putString("source_url", link.trim());
        } else {
            ed.remove("source_url");
        }
        recordState(ed, state).apply();
        Log.i(TAG, "subscription stored with " + nodes.size() + " usable nodes");
        return state;
    }

    /** Parse a link without keeping it, so the UI can preview before committing. */
    public static List<ColgramSubscription.Node> usable(Context context, String link) {
        List<ColgramSubscription.Node> all = ColgramSubscription.parse(link);
        List<ColgramSubscription.Node> out = new ArrayList<>();
        for (ColgramSubscription.Node node : all) {
            try {
                // Building it is the filter: a node the builder cannot express is one the engine
                // would reject, and shipping it in a profile only moves the failure later.
                ColgramProfileBuilder.forSelectedNode(node);
                out.add(node);
            } catch (Throwable t) {
                Log.i(TAG, "dropping " + node.protocol + " node: " + t.getMessage());
            }
        }
        return out;
    }

    /** Provider names are labels, not independently verified countries or health verdicts. */
    public static List<ColgramSubscription.Node> nodes(Context context) {
        String link = prefs(context).getString(KEY_LINK, null);
        return link == null ? new ArrayList<>() : usable(context, link);
    }

    /** Provider fetches are direct, bounded and never log the credential-bearing URL or response. */
    private static String fetchSubscription(String link) {
        try {
            URL current = new URL(link);
            for (int redirects = 0; redirects <= 3; redirects++) {
                String scheme = current.getProtocol();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                    throw new IOException("неподдерживаемая схема подписки");
                HttpURLConnection connection = (HttpURLConnection) current.openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "Colgram/Subscription");
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || redirects == 3) throw new IOException("слишком много перенаправлений");
                        URL next = new URL(current, location);
                        if ("https".equalsIgnoreCase(current.getProtocol()) && !"https".equalsIgnoreCase(next.getProtocol()))
                            throw new IOException("подписка перенаправляет без HTTPS");
                        current = next;
                        continue;
                    }
                    if (status < 200 || status >= 300) throw new IOException("сервер подписки вернул HTTP " + status);
                    if (connection.getContentLengthLong() > MAX_SUBSCRIPTION_BYTES)
                        throw new IOException("подписка превышает 2 МиБ");
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (out.size() + count > MAX_SUBSCRIPTION_BYTES) throw new IOException("подписка превышает 2 МиБ");
                            out.write(buffer, 0, count);
                        }
                        return new String(out.toByteArray(), StandardCharsets.UTF_8);
                    }
                } finally {
                    connection.disconnect();
                }
            }
            throw new IOException("слишком много перенаправлений");
        } catch (IOException failure) {
            try {
                ColgramHttp.Response resp = ColgramHttp.get(link);
                if (resp != null && resp.code >= 200 && resp.code < 300
                        && resp.body != null && !resp.body.trim().isEmpty()) {
                    return resp.body;
                }
            } catch (Throwable ignored) {}
            // Exception messages from URL/TLS stacks can contain the full paid link.
            throw new IllegalArgumentException("сервер подписки недоступен или ответ не подходит");
        }
    }

    public static boolean hasSubscription(Context context) {
        String link = prefs(context).getString(KEY_LINK, null);
        return link != null && !link.isEmpty();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
        new android.util.AtomicFile(new File(profileLocation(context))).delete();
    }

    /** The current selection, or null when nothing is stored. */
    public static State current(Context context) {
        SharedPreferences preferences = prefs(context);
        String link = preferences.getString(KEY_LINK, null);
        if (link == null || link.isEmpty()) return null;
        int total = preferences.getInt(KEY_NODE_TOTAL, 0);
        int index = preferences.getInt(KEY_NODE_INDEX, 0);
        if (total > 0) {
            return new State(total, Math.max(0, Math.min(total - 1, index)),
                    preferences.getString(KEY_NODE_NAME, "Узел"),
                    preferences.getString(KEY_NODE_PROTOCOL, ""));
        }
        // Migrate the old prefs once; polling the UI must not reparse a large subscription.
        List<ColgramSubscription.Node> nodes;
        try {
            nodes = usable(context, link);
        } catch (Throwable t) {
            return null;
        }
        if (nodes.isEmpty()) return null;
        State state = stateOf(nodes, index);
        if (link.equals(preferences.getString(KEY_LINK, null)) && index == preferences.getInt(KEY_NODE_INDEX, 0)) {
            recordState(preferences.edit(), state).apply();
        }
        return state;
    }

    /**
     * Point the tunnel at one node.
     *
     * The profile pins exactly the selected node. Other countries and direct access never compete
     * in a latency group behind an explicit selection.
     */
    public static State select(Context context, int index) {
        String link = prefs(context).getString(KEY_LINK, null);
        if (link == null || link.isEmpty()) {
            throw new IllegalStateException("подписка не сохранена");
        }
        List<ColgramSubscription.Node> nodes = usable(context, link);
        if (nodes.isEmpty()) throw new IllegalStateException("в подписке нет узлов");
        if (index < 0 || index >= nodes.size()) throw new IllegalArgumentException("нет такого узла");
        writeProfile(context, nodes.get(index));
        State state = stateOf(nodes, index);
        recordState(prefs(context).edit(), state).apply();
        return state;
    }

    /** The profile file the engine is pointed at, or null when there is nothing to run. */
    public static String profilePath(Context context) {
        String path = profileLocation(context);
        return hasSubscription(context) && new File(path).isFile() ? path : null;
    }

    public static String profileLocation(Context context) {
        return new File(profileDir(context), PROFILE_FILE).getAbsolutePath();
    }

    private static SharedPreferences.Editor recordState(SharedPreferences.Editor editor, State state) {
        return editor.putInt(KEY_NODE_INDEX, state.selected).putInt(KEY_NODE_TOTAL, state.total)
                .putString(KEY_NODE_NAME, state.name).putString(KEY_NODE_PROTOCOL, state.protocol);
    }

    private static void writeProfile(Context context, ColgramSubscription.Node node) {
        String json = ColgramProfileBuilder.forSelectedNode(node);
        File directory = profileDir(context);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("не удалось создать каталог для профиля");
        }
        android.util.AtomicFile file = new android.util.AtomicFile(new File(directory, PROFILE_FILE));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
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
