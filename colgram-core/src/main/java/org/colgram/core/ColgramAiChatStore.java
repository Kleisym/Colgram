package org.colgram.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent multi-session chat history store for Koliti AI.
 * Supports creating new chats, switching between saved conversations,
 * auto-titling from the first user prompt, renaming, and deleting chats.
 */
public final class ColgramAiChatStore {

    public static final class ChatMessage {
        public final String role; // "user" or "assistant"
        public final String text;
        public final String footer;
        public final long timestampMs;
        public final List<String> mediaPaths;

        public ChatMessage(String role, String text, String footer, long timestampMs) {
            this(role, text, footer, timestampMs, null);
        }

        public ChatMessage(String role, String text, String footer, long timestampMs, List<String> mediaPaths) {
            this.role = role == null ? "assistant" : role;
            this.text = text == null ? "" : text;
            this.footer = footer == null ? "" : footer;
            this.timestampMs = timestampMs;
            if (mediaPaths != null && !mediaPaths.isEmpty()) {
                List<String> clean = new ArrayList<>();
                for (String p : mediaPaths) {
                    if (p != null && !p.trim().isEmpty()) {
                        clean.add(p.trim());
                    }
                }
                this.mediaPaths = Collections.unmodifiableList(clean);
            } else {
                this.mediaPaths = Collections.emptyList();
            }
        }
    }

    public static final class ChatSession {
        public final String id;
        public String title;
        public long boundDialogId; // 0L = independent Koliti chat; != 0L = linked to a specific Telegram chat/channel
        public String boundChatTitle;
        public long createdAtMs;
        public long updatedAtMs;
        public final List<ChatMessage> messages = new ArrayList<>();

        public ChatSession(String id, String title, long createdAtMs, long updatedAtMs) {
            this(id, title, 0L, "", createdAtMs, updatedAtMs);
        }

        public ChatSession(String id, String title, long boundDialogId, String boundChatTitle, long createdAtMs, long updatedAtMs) {
            this.id = id;
            this.title = title == null || title.trim().isEmpty() ? "Новый чат" : title.trim();
            this.boundDialogId = boundDialogId;
            this.boundChatTitle = boundChatTitle == null ? "" : boundChatTitle.trim();
            this.createdAtMs = createdAtMs;
            this.updatedAtMs = updatedAtMs;
        }
    }

    public static final int MAX_SESSIONS = 500;
    public static final int MAX_MESSAGES_PER_SESSION = 2000;
    private static final Object LOCK = new Object();
    private static final java.util.concurrent.ExecutorService SAVE_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "koliti-chat-store-io");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private static File storeFile;
    private static File legacyStoreFile;
    private static final List<ChatSession> sessions = new ArrayList<>();
    private static String activeSessionId = "";
    private static boolean loaded = false;

    private ColgramAiChatStore() {}

    private static void flushPendingSaves() {
        try {
            SAVE_EXEC.submit(() -> {}).get(3000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}
    }

    public static void init(File baseFilesDir) {
        if (baseFilesDir == null) return;
        synchronized (LOCK) {
            File gzFile = new File(baseFilesDir, "koliti_chat_history_v2.json.gz");
            File oldJson = new File(baseFilesDir, "koliti_chat_history_v1.json");
            if (storeFile != null && storeFile.equals(gzFile) && loaded) {
                return;
            }
            storeFile = gzFile;
            legacyStoreFile = oldJson;
            loadLocked();
        }
    }

    public static ChatSession getOrCreateActiveSession() {
        synchronized (LOCK) {
            for (ChatSession s : sessions) {
                if (s.id.equals(activeSessionId)) {
                    return s;
                }
            }
            if (!sessions.isEmpty()) {
                activeSessionId = sessions.get(0).id;
                return sessions.get(0);
            }
            return createNewSessionLocked("Новый чат");
        }
    }

    public static ChatSession getOrCreateSessionForDialog(long dialogId, String chatTitle) {
        synchronized (LOCK) {
            if (dialogId != 0L) {
                for (ChatSession s : sessions) {
                    if (s.boundDialogId == dialogId) {
                        activeSessionId = s.id;
                        return s;
                    }
                }
                String title = (chatTitle != null && !chatTitle.trim().isEmpty()) ? chatTitle.trim() : "Чат";
                ChatSession created = createNewSessionLocked(title, dialogId, title);
                saveLocked();
                return created;
            }
            for (ChatSession s : sessions) {
                if (s.id.equals(activeSessionId) && s.boundDialogId == 0L) {
                    return s;
                }
            }
            for (ChatSession s : sessions) {
                if (s.boundDialogId == 0L) {
                    activeSessionId = s.id;
                    return s;
                }
            }
            ChatSession created = createNewSessionLocked("Новый чат", 0L, "");
            saveLocked();
            return created;
        }
    }

    public static ChatSession createNewSession(String title) {
        return createNewSession(title, 0L, "");
    }

    public static ChatSession createNewSession(String title, long boundDialogId, String boundChatTitle) {
        synchronized (LOCK) {
            ChatSession s = createNewSessionLocked(title, boundDialogId, boundChatTitle);
            saveLocked();
            return s;
        }
    }

    private static ChatSession createNewSessionLocked(String title) {
        return createNewSessionLocked(title, 0L, "");
    }

    private static ChatSession createNewSessionLocked(String title, long boundDialogId, String boundChatTitle) {
        long now = System.currentTimeMillis();
        String id = "chat_" + now + "_" + UUID.randomUUID().toString().substring(0, 6);
        ChatSession s = new ChatSession(id, title, boundDialogId, boundChatTitle, now, now);
        sessions.add(0, s);
        activeSessionId = s.id;
        while (sessions.size() > MAX_SESSIONS) {
            sessions.remove(sessions.size() - 1);
        }
        return s;
    }

    public static ChatSession switchSession(String sessionId) {
        synchronized (LOCK) {
            for (ChatSession s : sessions) {
                if (s.id.equals(sessionId)) {
                    activeSessionId = s.id;
                    saveLocked();
                    return s;
                }
            }
            return getOrCreateActiveSession();
        }
    }

    public static void appendMessage(String sessionId, String role, String text, String footer) {
        appendMessage(sessionId, role, text, footer, null);
    }

    public static void appendMessage(String sessionId, String role, String text, String footer, List<String> mediaPaths) {
        boolean hasText = text != null && !text.trim().isEmpty();
        boolean hasMedia = mediaPaths != null && !mediaPaths.isEmpty();
        if (!hasText && !hasMedia) return;
        String cleanText = hasText ? text.trim() : "";
        synchronized (LOCK) {
            ChatSession target = null;
            for (ChatSession s : sessions) {
                if (s.id.equals(sessionId)) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                target = getOrCreateActiveSession();
            }
            long now = System.currentTimeMillis();
            if ("user".equals(role) && (target.messages.isEmpty() || "Новый чат".equals(target.title))) {
                target.title = makeAutoTitle(hasText ? cleanText : "Изображение");
            }
            target.messages.add(new ChatMessage(role, cleanText, footer == null ? "" : footer, now, mediaPaths));
            while (target.messages.size() > MAX_MESSAGES_PER_SESSION) {
                target.messages.remove(0);
            }
            target.updatedAtMs = now;
            sortSessionsLocked();
            saveLocked();
        }
    }

    public static void deleteSession(String sessionId) {
        synchronized (LOCK) {
            for (int i = 0; i < sessions.size(); i++) {
                if (sessions.get(i).id.equals(sessionId)) {
                    sessions.remove(i);
                    break;
                }
            }
            if (activeSessionId.equals(sessionId)) {
                activeSessionId = sessions.isEmpty() ? "" : sessions.get(0).id;
            }
            saveLocked();
        }
    }

    public static void clearActiveSessionMessages() {
        synchronized (LOCK) {
            ChatSession cur = getOrCreateActiveSession();
            cur.messages.clear();
            cur.title = "Новый чат";
            cur.updatedAtMs = System.currentTimeMillis();
            saveLocked();
        }
    }

    public static List<ChatSession> listSessions() {
        synchronized (LOCK) {
            sortSessionsLocked();
            return new ArrayList<>(sessions);
        }
    }

    public static List<ColgramAiContextEngine.ConversationTurn> toConversationTurns(ChatSession session, int maxTurns) {
        List<ColgramAiContextEngine.ConversationTurn> turns = new ArrayList<>();
        if (session == null) return turns;
        synchronized (LOCK) {
            String pendingUser = null;
            for (ChatMessage m : session.messages) {
                if ("user".equals(m.role)) {
                    pendingUser = m.text;
                } else if ("assistant".equals(m.role) && pendingUser != null) {
                    turns.add(new ColgramAiContextEngine.ConversationTurn(pendingUser, m.text));
                    pendingUser = null;
                }
            }
        }
        if (turns.size() > maxTurns) {
            return new ArrayList<>(turns.subList(turns.size() - maxTurns, turns.size()));
        }
        return turns;
    }

    public static String makeAutoTitle(String firstPrompt) {
        if (firstPrompt == null) return "Новый чат";
        String clean = firstPrompt.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return "Новый чат";
        return clean.length() <= 38 ? clean : clean.substring(0, 37).trim() + "…";
    }

    private static void sortSessionsLocked() {
        Collections.sort(sessions, new Comparator<ChatSession>() {
            @Override
            public int compare(ChatSession a, ChatSession b) {
                return Long.compare(b.updatedAtMs, a.updatedAtMs);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void loadLocked() {
        flushPendingSaves();
        sessions.clear();
        activeSessionId = "";
        loaded = true;
        File source = (storeFile != null && storeFile.isFile())
                ? storeFile
                : ((legacyStoreFile != null && legacyStoreFile.isFile()) ? legacyStoreFile : null);
        if (source == null) return;
        boolean migratedFromLegacy = (source == legacyStoreFile);
        try {
            byte[] rawBytes = ColgramStorageOptimizer.readGzipOrPlainBytes(source);
            if (rawBytes == null || rawBytes.length == 0) return;
            String jsonStr = new String(rawBytes, StandardCharsets.UTF_8);
            Object parsed = new MiniJsonParser(jsonStr).parseValue();
            if (!(parsed instanceof Map)) return;
            Map<String, Object> root = (Map<String, Object>) parsed;
            activeSessionId = asString(root.get("activeId"), "");
            Object rawSessions = root.get("sessions");
            if (rawSessions instanceof List) {
                for (Object item : (List<Object>) rawSessions) {
                    if (!(item instanceof Map)) continue;
                    Map<String, Object> o = (Map<String, Object>) item;
                    String id = asString(o.get("id"), "");
                    if (id.isEmpty()) continue;
                    ChatSession s = new ChatSession(
                            id,
                            asString(o.get("title"), "Чат"),
                            asLong(o.get("boundDialogId"), 0L),
                            asString(o.get("boundChatTitle"), ""),
                            asLong(o.get("createdAt"), 0L),
                            asLong(o.get("updatedAt"), 0L));
                    Object rawMsgs = o.get("messages");
                    if (rawMsgs instanceof List) {
                        for (Object mi : (List<Object>) rawMsgs) {
                            if (!(mi instanceof Map)) continue;
                            Map<String, Object> mo = (Map<String, Object>) mi;
                            List<String> loadedMedia = null;
                            Object rawMedia = mo.get("media");
                            if (rawMedia instanceof List) {
                                loadedMedia = new ArrayList<>();
                                for (Object mp : (List<Object>) rawMedia) {
                                    if (mp instanceof String && !((String) mp).trim().isEmpty()) {
                                        loadedMedia.add(((String) mp).trim());
                                    }
                                }
                            }
                            s.messages.add(new ChatMessage(
                                    asString(mo.get("role"), "assistant"),
                                    asString(mo.get("text"), ""),
                                    asString(mo.get("footer"), ""),
                                    asLong(mo.get("ts"), 0L),
                                    loadedMedia
                            ));
                        }
                    }
                    sessions.add(s);
                }
            }
            sortSessionsLocked();
            if (migratedFromLegacy) {
                saveLocked(false);
            }
        } catch (Throwable ignored) {}
    }

    private static void saveLocked() {
        saveLocked(true);
    }

    private static void saveLocked(boolean async) {
        if (storeFile == null) return;
        try {
            File parent = storeFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            StringBuilder sb = new StringBuilder(1024);
            sb.append("{\"activeId\":");
            appendJsonString(sb, activeSessionId);
            sb.append(",\"sessions\":[");
            for (int i = 0; i < sessions.size(); i++) {
                if (i > 0) sb.append(',');
                ChatSession s = sessions.get(i);
                sb.append("{\"id\":");
                appendJsonString(sb, s.id);
                sb.append(",\"title\":");
                appendJsonString(sb, s.title);
                sb.append(",\"boundDialogId\":").append(s.boundDialogId);
                sb.append(",\"boundChatTitle\":");
                appendJsonString(sb, s.boundChatTitle);
                sb.append(",\"createdAt\":").append(s.createdAtMs);
                sb.append(",\"updatedAt\":").append(s.updatedAtMs);
                sb.append(",\"messages\":[");
                for (int j = 0; j < s.messages.size(); j++) {
                    if (j > 0) sb.append(',');
                    ChatMessage m = s.messages.get(j);
                    sb.append("{\"role\":");
                    appendJsonString(sb, m.role);
                    sb.append(",\"text\":");
                    appendJsonString(sb, m.text);
                    sb.append(",\"footer\":");
                    appendJsonString(sb, m.footer);
                    sb.append(",\"ts\":").append(m.timestampMs);
                    if (m.mediaPaths != null && !m.mediaPaths.isEmpty()) {
                        sb.append(",\"media\":[");
                        for (int k = 0; k < m.mediaPaths.size(); k++) {
                            if (k > 0) sb.append(',');
                            appendJsonString(sb, m.mediaPaths.get(k));
                        }
                        sb.append(']');
                    }
                    sb.append('}');
                }
                sb.append("]}");
            }
            sb.append("]}");
            final byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            final File targetStore = storeFile;
            final File targetLegacy = legacyStoreFile;
            Runnable writeTask = () -> {
                try {
                    if (ColgramStorageOptimizer.writeGzipBytesAtomic(targetStore, bytes)) {
                        if (targetLegacy != null && targetLegacy.isFile()) {
                            //noinspection ResultOfMethodCallIgnored
                            targetLegacy.delete();
                        }
                    }
                } catch (Throwable ignored) {}
            };
            if (async) {
                SAVE_EXEC.execute(writeTask);
            } else {
                flushPendingSaves();
                writeTask.run();
            }
        } catch (Throwable ignored) {}
    }

    private static String asString(Object o, String fallback) {
        return o instanceof String ? (String) o : fallback;
    }

    private static long asLong(Object o, long fallback) {
        if (o instanceof Number) return ((Number) o).longValue();
        return fallback;
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
        }
        sb.append('"');
    }

    private static final class MiniJsonParser {
        private final String s;
        private int pos;

        MiniJsonParser(String s) {
            this.s = s == null ? "" : s;
            this.pos = 0;
        }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) return null;
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' && s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (c == 'f' && s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (c == 'n' && s.startsWith("null", pos)) { pos += 4; return null; }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return map; }
            while (pos < s.length()) {
                skipWs();
                String key = parseString();
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ':') pos++;
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
                if (pos < s.length() && s.charAt(pos) == '}') { pos++; break; }
                break;
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (pos < s.length()) {
                list.add(parseValue());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
                if (pos < s.length() && s.charAt(pos) == ']') { pos++; break; }
                break;
            }
            return list;
        }

        private String parseString() {
            StringBuilder sb = new StringBuilder();
            if (pos < s.length() && s.charAt(pos) == '"') pos++;
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < s.length()) {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                pos += 4;
                            }
                            break;
                        default: sb.append(e); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }
    }

    /** For unit tests only. */
    public static void resetForTest() {
        flushPendingSaves();
        synchronized (LOCK) {
            sessions.clear();
            activeSessionId = "";
            storeFile = null;
            legacyStoreFile = null;
            loaded = false;
        }
    }
}
