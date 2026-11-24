package org.colgram.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded Kleisym/Colemailer v3.4.19 pure-Java engine:
 *   - Inline button parser ([Text](url) || [Text](cb:data))
 *   - 3-mode broadcaster scheduler (interval / counter / hybrid without catch-up bursts)
 *   - Named per-chat template rotations
 *   - Whitelist, mute scopes/modes (silent/delete/warn/ban), and native PeerNotifySettings quiet snapshots
 *   - Gatekeeper / safe verify-button & link extraction for Auto-Sub
 *   - Recent read/ping RPC diagnostic ring for .пингдиаг
 */
public final class ColgramColemailerEngine {

    public static final int MODE_INTERVAL = 0;
    public static final int MODE_COUNTER = 1;
    public static final int MODE_HYBRID = 2;

    public static final String MUTE_SILENT = "silent";
    public static final String MUTE_DELETE = "delete";
    public static final String MUTE_WARN = "warn";
    public static final String MUTE_BAN = "ban";

    public static final int MAX_BUTTON_TEXT = 64;
    public static final int MAX_BUTTON_URL = 2048;
    public static final int MAX_CALLBACK_BYTES = 64;
    public static final int MAX_BUTTONS_PER_ROW = 8;
    public static final int MAX_BUTTON_ROWS = 100;

    private static final long MIN_INTERVAL_SEC = 10L;
    private static final long HYBRID_STALE_MIN_SEC = 1800L;

    private static final Pattern BUTTON_TOKEN_RE = Pattern.compile(
            "\\[\\s*([^\\[\\]\\n]{1,64}?)\\s*]\\s*(?:\\(\\s*([^\\s()]+)\\s*\\)|(?:->|=>)\\s*([^\\s]+))");
    private static final Pattern BETWEEN_BUTTONS_RE = Pattern.compile("^[\\s|]*$");

    private static final Pattern GATEKEEPER_RE[] = {
            p("чтобы\\s+(?:писать|отправлять\\s+сообщения)\\s+в\\s+(?:чат|группу)"),
            p("необходимо\\s+подписаться"),
            p("нужно\\s+подписаться"),
            p("подпишитесь\\s+на\\s+(?:канал|наш)"),
            p("обязательная\\s+подписка"),
            p("для\\s+доступа\\s+к\\s+чату"),
            p("пройдите\\s+капчу"),
            p("подтвердите,?\\s*что\\s+вы\\s+не\\s+(?:бот|робот)"),
            p("you\\s+must\\s+(?:join|subscribe)")
    };

    private static final Pattern VERIFY_BUTTON_RE = p(
            "(?:подписался|я подписался|проверить|готово|подтвердить|вступил|продолжить|я не бот|\\bcheck\\b|\\bdone\\b|\\bverify\\b|subscribed|not a bot)");
    private static final Pattern SUBSCRIBE_BUTTON_RE = p("(?:подпис(?:аться|ывайся)|вступ(?:ить|ай)|\\bjoin\\b|\\bsubscribe\\b)");
    private static final Pattern UNSAFE_BUTTON_LABEL_RE = p(
            "(?:оплат\\w*|плат[её]ж\\w*|\\bpay(?:ment)?\\b|checkout|invoice|кошел\\w*|wallet|крипт\\w*|crypto|\\bton\\b|\\busdt\\b|\\bbtc\\b|\\beth\\b|stars?\\b|звезд\\w*|донат\\w*|donate|купить|\\bbuy\\b|обмен\\w*|exchange|\\bclaim\\b|airdrop|подар\\w*|\\bgift\\b|казино|casino)");
    private static final Pattern UNSAFE_BUTTON_URL_RE = p(
            "(?:tonkeeper|wallet|crypto|cryptobot|payment|invoice|checkout|fragment|donat|casino|airdrop)|[?&](?:start|startapp|invoice|pay)=");

    private static final Pattern T_ME_LINK_RE = Pattern.compile(
            "(?:https?://)?(?:t\\.me|telegram\\.me)/((?:joinchat/|\\+)?)([a-zA-Z0-9_+\\-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TG_JOIN_RE = Pattern.compile(
            "tg://join\\?invite=([a-zA-Z0-9_+\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MENTION_LINK_RE = Pattern.compile("@([a-zA-Z0-9_]{4,32})");

    public static final class ButtonSpec {
        public final String text;
        public final String type; // "url" or "callback"
        public final String value;

        public ButtonSpec(String text, String type, String value) {
            this.text = text;
            this.type = type;
            this.value = value;
        }
    }

    public static final class ParsedPost {
        public final String body;
        public final List<List<ButtonSpec>> buttons;

        public ParsedPost(String body, List<List<ButtonSpec>> buttons) {
            this.body = body;
            this.buttons = buttons;
        }
    }

    public static final class ScheduleSpec {
        public final int mode;
        public final long intervalSec;
        public final int counterThreshold;

        public ScheduleSpec(int mode, long intervalSec, int counterThreshold) {
            this.mode = mode;
            this.intervalSec = intervalSec;
            this.counterThreshold = counterThreshold;
        }
    }

    public static final class BroadcastCampaign {
        public final String name;
        public String text;
        public int mode;
        public long intervalSec;
        public int counterThreshold;
        public String folderName;
        public final LinkedHashSet<Long> targetChats = new LinkedHashSet<>();
        public final Map<Long, Integer> chatCounters = new HashMap<>();
        public final Map<Long, Long> chatCounterUpdatedAt = new HashMap<>();
        public final Map<Long, Long> chatLastSentAt = new HashMap<>();
        public final List<List<ButtonSpec>> buttons = new ArrayList<>();
        public boolean disablePreview;
        public boolean enabled = true;
        /** Account slot that owns this campaign (-1 = legacy/unassigned, >=0 = pinned to that account). */
        public int ownerAccount = -1;

        public BroadcastCampaign(String name, String text, int mode, long intervalSec,
                                 int counterThreshold, String folderName) {
            this.name = name;
            this.text = text == null ? "" : text;
            this.mode = mode;
            this.intervalSec = intervalSec;
            this.counterThreshold = counterThreshold;
            this.folderName = folderName == null ? "" : folderName.trim();
        }
    }

    public static final class BroadcastDueEvent {
        public final BroadcastCampaign campaign;
        public final long chatId;

        public BroadcastDueEvent(BroadcastCampaign campaign, long chatId) {
            this.campaign = campaign;
            this.chatId = chatId;
        }
    }

    public static final class MuteRule {
        public final long senderId;
        public final long scopeChatId; // 0 = global, negative/nonzero = scoped to chat
        public final boolean wholeChat;
        public final String mode; // silent / delete / warn / ban

        public MuteRule(long senderId, long scopeChatId, boolean wholeChat, String mode) {
            this.senderId = senderId;
            this.scopeChatId = scopeChatId;
            this.wholeChat = wholeChat;
            this.mode = normalizeMuteMode(mode);
        }
    }

    public static final class QuietSnapshot {
        public final long dialogId;
        public final int prevNotifyType;
        public final int prevMuteUntil;
        public final boolean prevSilent;
        public final boolean prevShowPreviews;
        public final String prevSoundTitle;
        public final String prevSoundPath;
        public final long prevSoundDocId;

        public QuietSnapshot(long dialogId, int prevNotifyType, int prevMuteUntil, boolean prevSilent,
                             boolean prevShowPreviews, String prevSoundTitle, String prevSoundPath, long prevSoundDocId) {
            this.dialogId = dialogId;
            this.prevNotifyType = prevNotifyType;
            this.prevMuteUntil = prevMuteUntil;
            this.prevSilent = prevSilent;
            this.prevShowPreviews = prevShowPreviews;
            this.prevSoundTitle = prevSoundTitle == null ? "" : prevSoundTitle;
            this.prevSoundPath = prevSoundPath == null ? "" : prevSoundPath;
            this.prevSoundDocId = prevSoundDocId;
        }
    }

    public static final class RotationRule {
        public final String name;
        public final List<String> campaignNames;
        public final Map<Long, Integer> chatCursor = new HashMap<>();

        public RotationRule(String name, List<String> campaignNames) {
            this.name = name;
            this.campaignNames = new ArrayList<>(campaignNames);
        }
    }

    private static final class PingRpcRecord {
        final long dialogId;
        final String stage;
        final boolean ok;
        final String detail;
        final long timestampSec;

        PingRpcRecord(long dialogId, String stage, boolean ok, String detail, long timestampSec) {
            this.dialogId = dialogId;
            this.stage = stage;
            this.ok = ok;
            this.detail = detail == null ? "" : detail;
            this.timestampSec = timestampSec;
        }
    }

    private static ColgramColemailerEngine instance;

    private final Map<String, BroadcastCampaign> campaigns = new LinkedHashMap<>();
    private final Map<String, RotationRule> rotations = new LinkedHashMap<>();
    private final Set<Long> whitelist = new LinkedHashSet<>();
    private final Map<String, MuteRule> muteRules = new LinkedHashMap<>();
    private final Map<Long, QuietSnapshot> quietSnapshots = new LinkedHashMap<>();
    private final List<PingRpcRecord> rpcHistory = new ArrayList<>();

    private boolean loadedFromConfig = false;

    private ColgramColemailerEngine() {}

    public static synchronized ColgramColemailerEngine getInstance() {
        if (instance == null) {
            instance = new ColgramColemailerEngine();
        }
        if (!instance.loadedFromConfig) {
            String raw = ColgramConfig.getColemailerState();
            instance.loadedFromConfig = true;
            if (raw != null && !raw.isEmpty()) {
                instance.deserializeIntoSelf(raw);
            }
        }
        return instance;
    }

    /** Creates an isolated instance for deterministic JVM unit tests without touching preferences. */
    public static ColgramColemailerEngine newStandaloneForTest() {
        ColgramColemailerEngine eng = new ColgramColemailerEngine();
        eng.loadedFromConfig = true;
        return eng;
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static String normalizeMuteMode(String mode) {
        if (mode == null) return MUTE_SILENT;
        String low = mode.trim().toLowerCase(Locale.ROOT);
        if (low.startsWith("удал") || low.equals("del") || low.equals(MUTE_DELETE)) return MUTE_DELETE;
        if (low.startsWith("варн") || low.equals(MUTE_WARN)) return MUTE_WARN;
        if (low.startsWith("бан") || low.equals(MUTE_BAN)) return MUTE_BAN;
        return MUTE_SILENT;
    }

    // --- Button layout parser ([Text](https://...) || [Text](cb:data)) ---

    public static List<ButtonSpec> parseButtonRow(String line) {
        if (line == null) return null;
        String stripped = line.trim();
        if (!stripped.startsWith("[")) return null;
        Matcher m = BUTTON_TOKEN_RE.matcher(stripped);
        List<ButtonSpec> row = new ArrayList<>();
        int pos = 0;
        while (m.find()) {
            if (!BETWEEN_BUTTONS_RE.matcher(stripped.substring(pos, m.start())).matches()) {
                return null;
            }
            pos = m.end();
            String label = m.group(1) != null ? m.group(1).trim() : "";
            String target = m.group(2) != null ? m.group(2).trim() : (m.group(3) != null ? m.group(3).trim() : "");
            if (label.isEmpty() || label.length() > MAX_BUTTON_TEXT) {
                throw new IllegalArgumentException("Неверная длина подписи кнопки: " + label);
            }
            ButtonSpec spec = classifyButton(label, target);
            row.add(spec);
            if (row.size() > MAX_BUTTONS_PER_ROW) {
                throw new IllegalArgumentException("В ряду не более " + MAX_BUTTONS_PER_ROW + " кнопок");
            }
        }
        if (row.isEmpty() || !BETWEEN_BUTTONS_RE.matcher(stripped.substring(pos)).matches()) {
            return null;
        }
        return row;
    }

    private static ButtonSpec classifyButton(String label, String target) {
        String low = target.toLowerCase(Locale.ROOT);
        if (low.startsWith("cb:") || low.startsWith("callback:") || low.startsWith("callback_data:")) {
            int idx = target.indexOf(':');
            String data = target.substring(idx + 1).trim();
            if (data.isEmpty() || data.getBytes(StandardCharsets.UTF_8).length > MAX_CALLBACK_BYTES) {
                throw new IllegalArgumentException("Неверные callback-данные кнопки");
            }
            return new ButtonSpec(label, "callback", data);
        }
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("tg://") || low.startsWith("mailto:") || low.startsWith("tel:")) {
            if (target.length() > MAX_BUTTON_URL) {
                throw new IllegalArgumentException("Слишком длинная ссылка кнопки");
            }
            return new ButtonSpec(label, "url", target);
        }
        throw new IllegalArgumentException("Неподдерживаемая цель кнопки: " + target);
    }

    public static List<List<ButtonSpec>> parseButtonLayout(String layoutText) {
        if (layoutText == null || layoutText.trim().isEmpty()) return Collections.emptyList();
        List<List<ButtonSpec>> rows = new ArrayList<>();
        for (String line : layoutText.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            List<ButtonSpec> row = parseButtonRow(line);
            if (row == null) {
                throw new IllegalArgumentException("Ожидался формат [Текст](ссылка): " + line);
            }
            rows.add(row);
            if (rows.size() > MAX_BUTTON_ROWS) {
                throw new IllegalArgumentException("Максимум " + MAX_BUTTON_ROWS + " рядов кнопок");
            }
        }
        return rows;
    }

    /** Separates trailing button lines from post text while preserving body formatting. */
    public static ParsedPost extractButtonsFromPost(String payload) {
        if (payload == null || payload.isEmpty()) return new ParsedPost("", Collections.emptyList());
        String[] lines = payload.split("\\r?\\n", -1);
        List<List<ButtonSpec>> trailing = new ArrayList<>();
        int cut = lines.length;
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                if (!trailing.isEmpty()) break;
                cut = i;
                continue;
            }
            try {
                List<ButtonSpec> row = parseButtonRow(trimmed);
                if (row == null) break;
                trailing.add(0, row);
                cut = i;
            } catch (IllegalArgumentException ignored) {
                break;
            }
        }
        if (trailing.isEmpty()) return new ParsedPost(payload, Collections.emptyList());
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < cut; i++) {
            if (i > 0) body.append('\n');
            body.append(lines[i]);
        }
        return new ParsedPost(body.toString().replaceAll("\\s+$", ""), trailing);
    }

    public static String formatButtonsPreview(List<List<ButtonSpec>> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (List<ButtonSpec> row : rows) {
            if (row == null || row.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            for (int i = 0; i < row.size(); i++) {
                ButtonSpec b = row.get(i);
                if (i > 0) sb.append(" || ");
                sb.append('[').append(b.text).append("](");
                if ("callback".equals(b.type)) sb.append("cb:");
                sb.append(b.value).append(')');
            }
        }
        return sb.toString();
    }

    /** Converts button rows into Telegram Bot API InlineKeyboardMarkup JSON without org.json dependency. */
    public static String toBotApiReplyMarkupJson(List<List<ButtonSpec>> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{\"inline_keyboard\":[");
        boolean firstRow = true;
        for (List<ButtonSpec> row : rows) {
            if (row == null || row.isEmpty()) continue;
            if (!firstRow) sb.append(',');
            firstRow = false;
            sb.append('[');
            boolean firstBtn = true;
            for (ButtonSpec b : row) {
                if (!firstBtn) sb.append(',');
                firstBtn = false;
                sb.append("{\"text\":\"").append(jsonEsc(b.text)).append("\",");
                if ("callback".equals(b.type)) {
                    sb.append("\"callback_data\":\"").append(jsonEsc(b.value)).append("\"}");
                } else {
                    sb.append("\"url\":\"").append(jsonEsc(b.value)).append("\"}");
                }
            }
            sb.append(']');
        }
        sb.append("]}");
        return sb.toString();
    }

    // --- Schedule parsing & scheduler ---

    public static ScheduleSpec parseSchedule(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new ScheduleSpec(MODE_INTERVAL, 600L, 0);
        }
        String clean = raw.toLowerCase(Locale.ROOT).trim();
        long intervalSec = 0L;
        int counterThreshold = 0;

        Matcher timeM = Pattern.compile(
                "(\\d+)\\s*(?:сек\\w*|sec\\w*|s\\b|мин\\w*|min\\w*|m\\b|час\\w*|hour\\w*|h\\b|дн\\w*|сут\\w*|day\\w*|d\\b)")
                .matcher(clean);
        while (timeM.find()) {
            long val = Long.parseLong(timeM.group(1));
            String unit = timeM.group(0).toLowerCase(Locale.ROOT);
            if (unit.contains("сек") || unit.contains("sec") || unit.endsWith("s")) intervalSec += val;
            else if (unit.contains("мин") || unit.contains("min") || unit.endsWith("m")) intervalSec += val * 60L;
            else if (unit.contains("час") || unit.contains("hour") || unit.endsWith("h")) intervalSec += val * 3600L;
            else if (unit.contains("дн") || unit.contains("сут") || unit.contains("day") || unit.endsWith("d")) intervalSec += val * 86400L;
        }

        Matcher cntM = Pattern.compile("(\\d+)\\s*(?:сообщ\\w*|смс|пост\\w*|msg\\w*|messages?)").matcher(clean);
        if (cntM.find()) {
            counterThreshold = Math.max(1, Integer.parseInt(cntM.group(1)));
        }

        if (intervalSec > 0 && counterThreshold > 0) {
            return new ScheduleSpec(MODE_HYBRID, Math.max(MIN_INTERVAL_SEC, intervalSec), counterThreshold);
        }
        if (counterThreshold > 0) {
            return new ScheduleSpec(MODE_COUNTER, 0L, counterThreshold);
        }
        if (intervalSec <= 0) {
            Matcher bare = Pattern.compile("^\\d+$").matcher(clean);
            intervalSec = bare.find() ? Long.parseLong(clean) * 60L : 600L;
        }
        return new ScheduleSpec(MODE_INTERVAL, Math.max(MIN_INTERVAL_SEC, intervalSec), 0);
    }

    public synchronized void addOrUpdateCampaign(BroadcastCampaign campaign) {
        if (campaign == null || campaign.name.trim().isEmpty()) return;
        campaigns.put(campaign.name.trim(), campaign);
        persistIfBound();
    }

    public synchronized boolean removeCampaign(String name) {
        if (name == null) return false;
        boolean removed = campaigns.remove(name.trim()) != null;
        if (removed) persistIfBound();
        return removed;
    }

    public synchronized BroadcastCampaign getCampaign(String name) {
        return name == null ? null : campaigns.get(name.trim());
    }

    public synchronized List<BroadcastCampaign> listCampaigns() {
        return new ArrayList<>(campaigns.values());
    }

    public synchronized boolean setCampaignEnabled(String name, boolean enabled) {
        BroadcastCampaign c = getCampaign(name);
        if (c == null) return false;
        c.enabled = enabled;
        persistIfBound();
        return true;
    }

    public synchronized void setCampaignButtons(String name, List<List<ButtonSpec>> rows) {
        BroadcastCampaign c = getCampaign(name);
        if (c == null) return;
        c.buttons.clear();
        if (rows != null) c.buttons.addAll(rows);
        persistIfBound();
    }

    public synchronized void syncFolderTargets(String folderName, Set<Long> liveDialogIds) {
        syncFolderTargets(-1, folderName, liveDialogIds);
    }

    public synchronized void syncFolderTargets(int account, String folderName, Set<Long> liveDialogIds) {
        if (folderName == null || folderName.trim().isEmpty() || liveDialogIds == null) return;
        LinkedHashSet<Long> safeIds = new LinkedHashSet<>();
        for (Long id : liveDialogIds) {
            if (id != null && !ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(id)) {
                safeIds.add(id);
            }
        }
        boolean changed = false;
        for (BroadcastCampaign c : campaigns.values()) {
            if (account >= 0 && c.ownerAccount >= 0 && c.ownerAccount != account) continue;
            if (folderName.equalsIgnoreCase(c.folderName)) {
                if (!c.targetChats.equals(safeIds)) {
                    c.targetChats.clear();
                    c.targetChats.addAll(safeIds);
                    changed = true;
                }
            }
        }
        if (changed) persistIfBound();
    }

    public synchronized void onIncomingChatMessage(long chatId, long nowSec) {
        onIncomingChatMessage(-1, chatId, nowSec);
    }

    public synchronized void onIncomingChatMessage(int account, long chatId, long nowSec) {
        if (ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(chatId)) return;
        boolean changed = false;
        for (BroadcastCampaign c : campaigns.values()) {
            if (!c.enabled || c.mode == MODE_INTERVAL) continue;
            if (account >= 0 && c.ownerAccount >= 0 && c.ownerAccount != account) continue;
            if (!c.targetChats.contains(chatId)) continue;
            if (c.mode == MODE_HYBRID) {
                long staleWindow = Math.max(c.intervalSec * 3L, HYBRID_STALE_MIN_SEC);
                Long updatedAt = c.chatCounterUpdatedAt.get(chatId);
                if (updatedAt != null && (nowSec - updatedAt) > staleWindow) {
                    c.chatCounters.put(chatId, 0);
                }
            }
            int prev = c.chatCounters.getOrDefault(chatId, 0);
            c.chatCounters.put(chatId, prev + 1);
            c.chatCounterUpdatedAt.put(chatId, nowSec);
            changed = true;
        }
        if (changed) persistIfBound();
    }

    public synchronized List<BroadcastDueEvent> collectDueBroadcasts(long nowSec) {
        return collectDueBroadcasts(-1, nowSec);
    }

    public synchronized List<BroadcastDueEvent> collectDueBroadcasts(int account, long nowSec) {
        List<BroadcastDueEvent> due = new ArrayList<>();
        for (BroadcastCampaign c : campaigns.values()) {
            if (!c.enabled || c.text.isEmpty() || c.targetChats.isEmpty()) continue;
            if (account >= 0 && c.ownerAccount >= 0 && c.ownerAccount != account) continue;
            for (Long chatId : c.targetChats) {
                if (chatId == null || ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(chatId)) continue;
                Long lastSentObj = c.chatLastSentAt.get(chatId);
                long lastSent = lastSentObj == null ? 0L : lastSentObj;
                if (lastSent == 0L) {
                    // Arm initial timestamp without dumping catch-up bursts right on startup/creation.
                    c.chatLastSentAt.put(chatId, nowSec);
                    continue;
                }
                boolean ready;
                if (c.mode == MODE_INTERVAL) {
                    ready = (nowSec - lastSent) >= Math.max(MIN_INTERVAL_SEC, c.intervalSec);
                } else if (c.mode == MODE_COUNTER) {
                    int count = c.chatCounters.getOrDefault(chatId, 0);
                    ready = c.counterThreshold > 0 && count >= c.counterThreshold;
                } else {
                    long staleWindow = Math.max(c.intervalSec * 3L, HYBRID_STALE_MIN_SEC);
                    Long cntAt = c.chatCounterUpdatedAt.get(chatId);
                    if (cntAt != null && (nowSec - cntAt) > staleWindow) {
                        c.chatCounters.put(chatId, 0);
                    }
                    int count = c.chatCounters.getOrDefault(chatId, 0);
                    ready = (nowSec - lastSent) >= Math.max(MIN_INTERVAL_SEC, c.intervalSec)
                            && c.counterThreshold > 0 && count >= c.counterThreshold;
                }
                if (ready) {
                    due.add(new BroadcastDueEvent(c, chatId));
                }
            }
        }
        return due;
    }

    public synchronized void markBroadcastSent(BroadcastCampaign c, long chatId, long nowSec) {
        if (c == null) return;
        c.chatLastSentAt.put(chatId, nowSec);
        c.chatCounters.put(chatId, 0);
        c.chatCounterUpdatedAt.put(chatId, nowSec);
        persistIfBound();
    }

    // --- Rotations ---

    public synchronized void setRotation(String name, List<String> campaignNames) {
        if (name == null || name.trim().isEmpty() || campaignNames == null || campaignNames.isEmpty()) return;
        rotations.put(name.trim(), new RotationRule(name.trim(), campaignNames));
        persistIfBound();
    }

    public synchronized boolean removeRotation(String name) {
        if (name == null) return false;
        boolean ok = rotations.remove(name.trim()) != null;
        if (ok) persistIfBound();
        return ok;
    }

    public synchronized List<RotationRule> listRotations() {
        return new ArrayList<>(rotations.values());
    }

    public synchronized BroadcastCampaign nextRotationCampaign(String rotationName, long chatId) {
        RotationRule r = rotations.get(rotationName);
        if (r == null || r.campaignNames.isEmpty()) return null;
        int idx = r.chatCursor.getOrDefault(chatId, 0) % r.campaignNames.size();
        String chosen = r.campaignNames.get(idx);
        r.chatCursor.put(chatId, (idx + 1) % r.campaignNames.size());
        persistIfBound();
        return campaigns.get(chosen);
    }

    // --- Whitelist & Mute rules ---

    public synchronized void addWhitelist(long userId) {
        if (userId == 0) return;
        whitelist.add(userId);
        persistIfBound();
    }

    public synchronized boolean removeWhitelist(long userId) {
        boolean ok = whitelist.remove(userId);
        if (ok) persistIfBound();
        return ok;
    }

    public synchronized boolean isWhitelisted(long userId) {
        return userId != 0 && whitelist.contains(userId);
    }

    public synchronized List<Long> listWhitelist() {
        return new ArrayList<>(whitelist);
    }

    public synchronized void setMuteRule(long senderId, long scopeChatId, boolean wholeChat, String mode) {
        if (senderId == 0 && !wholeChat) return;
        String key = muteKey(senderId, scopeChatId, wholeChat);
        muteRules.put(key, new MuteRule(senderId, scopeChatId, wholeChat, mode));
        persistIfBound();
    }

    public synchronized boolean removeMuteRule(long senderId, long scopeChatId, boolean wholeChat) {
        String key = muteKey(senderId, scopeChatId, wholeChat);
        boolean removed = muteRules.remove(key) != null;
        if (removed) persistIfBound();
        return removed;
    }

    public synchronized String getMuteActionFor(long senderId, long chatId) {
        if (senderId != 0 && isWhitelisted(senderId)) return null;
        MuteRule scoped = muteRules.get(muteKey(senderId, chatId, false));
        if (scoped != null) return scoped.mode;
        MuteRule global = muteRules.get(muteKey(senderId, 0L, false));
        if (global != null) return global.mode;
        MuteRule chatRule = muteRules.get(muteKey(0L, chatId, true));
        return chatRule != null ? chatRule.mode : null;
    }

    public synchronized List<MuteRule> listMuteRules() {
        return new ArrayList<>(muteRules.values());
    }

    private static String muteKey(long senderId, long scopeChatId, boolean wholeChat) {
        return wholeChat ? ("chat:" + scopeChatId)
                : (scopeChatId != 0 ? ("cu:" + scopeChatId + ":" + senderId) : ("u:" + senderId));
    }

    // --- Native Telegram quiet snapshots (.тишина / .тишина выкл) ---

    public synchronized void saveQuietSnapshot(QuietSnapshot snap) {
        if (snap == null || snap.dialogId == 0) return;
        quietSnapshots.put(snap.dialogId, snap);
        persistIfBound();
    }

    public synchronized QuietSnapshot getQuietSnapshot(long dialogId) {
        return quietSnapshots.get(dialogId);
    }

    public synchronized QuietSnapshot removeQuietSnapshot(long dialogId) {
        QuietSnapshot removed = quietSnapshots.remove(dialogId);
        if (removed != null) persistIfBound();
        return removed;
    }

    public synchronized List<QuietSnapshot> listQuietSnapshots() {
        return new ArrayList<>(quietSnapshots.values());
    }

    // --- Gatekeeper & Auto-Sub safety ---

    public static boolean isGatekeeperText(String text) {
        if (text == null || text.isEmpty()) return false;
        String low = text.toLowerCase(Locale.ROOT);
        for (Pattern pat : GATEKEEPER_RE) {
            if (pat.matcher(low).find()) return true;
        }
        return false;
    }

    public static boolean isVerifyButton(String label) {
        return label != null && VERIFY_BUTTON_RE.matcher(label.trim().toLowerCase(Locale.ROOT)).find();
    }

    public static boolean isSubscribeButton(String label) {
        return label != null && SUBSCRIBE_BUTTON_RE.matcher(label.trim().toLowerCase(Locale.ROOT)).find();
    }

    public static boolean isUnsafeButton(String label, String url, String callbackData) {
        if (label != null && UNSAFE_BUTTON_LABEL_RE.matcher(label).find()) return true;
        if (url != null && UNSAFE_BUTTON_URL_RE.matcher(url).find()) return true;
        return callbackData != null && UNSAFE_BUTTON_LABEL_RE.matcher(callbackData).find();
    }

    public static List<String> extractChannelLinks(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        Set<String> targets = new LinkedHashSet<>();
        Matcher tm = T_ME_LINK_RE.matcher(text);
        while (tm.find()) {
            String target = (tm.group(1) + tm.group(2)).replaceAll("[/.,;:!?)\\]}>]+$", "");
            if (!target.isEmpty()) targets.add(target);
        }
        Matcher jg = TG_JOIN_RE.matcher(text);
        while (jg.find()) {
            String invite = jg.group(1).replaceAll("[/.,;:!?)\\]}>]+$", "");
            if (!invite.isEmpty()) targets.add("+" + invite);
        }
        Matcher mm = MENTION_LINK_RE.matcher(text);
        while (mm.find()) {
            targets.add(mm.group(1));
        }
        return new ArrayList<>(targets);
    }

    // --- Recent RPC diagnostic ring ---

    public synchronized void recordPingRpc(long dialogId, String stage, boolean ok, String detail) {
        rpcHistory.add(new PingRpcRecord(dialogId, stage, ok, detail, System.currentTimeMillis() / 1000L));
        while (rpcHistory.size() > 64) {
            rpcHistory.remove(0);
        }
    }

    public synchronized String formatRecentRpcForDialog(long dialogId) {
        long now = System.currentTimeMillis() / 1000L;
        StringBuilder sb = new StringBuilder();
        for (int i = rpcHistory.size() - 1; i >= 0; i--) {
            PingRpcRecord r = rpcHistory.get(i);
            if (r.dialogId == dialogId) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(r.stage).append(": ").append(r.ok ? "ok" : r.detail)
                        .append(" (").append(Math.max(0L, now - r.timestampSec)).append("с назад)");
            }
        }
        return sb.length() == 0 ? "нет запросов в этой сессии" : sb.toString();
    }

    // --- Pure-Java lossless serialization (no org.json needed for host JVM tests) ---

    public synchronized String serializeState() {
        StringBuilder sb = new StringBuilder();
        for (Long wl : whitelist) {
            sb.append("WL\t").append(wl).append('\n');
        }
        for (MuteRule mr : muteRules.values()) {
            sb.append("MUTE\t").append(mr.senderId).append('\t').append(mr.scopeChatId)
                    .append('\t').append(mr.wholeChat ? '1' : '0')
                    .append('\t').append(esc(mr.mode)).append('\n');
        }
        for (QuietSnapshot qs : quietSnapshots.values()) {
            sb.append("QUIET\t").append(qs.dialogId).append('\t').append(qs.prevNotifyType)
                    .append('\t').append(qs.prevMuteUntil).append('\t').append(qs.prevSilent ? '1' : '0')
                    .append('\t').append(qs.prevShowPreviews ? '1' : '0')
                    .append('\t').append(esc(qs.prevSoundTitle))
                    .append('\t').append(esc(qs.prevSoundPath))
                    .append('\t').append(qs.prevSoundDocId).append('\n');
        }
        for (BroadcastCampaign c : campaigns.values()) {
            sb.append("CAMP\t").append(esc(c.name)).append('\t').append(esc(c.text))
                    .append('\t').append(c.mode).append('\t').append(c.intervalSec)
                    .append('\t').append(c.counterThreshold).append('\t').append(esc(c.folderName))
                    .append('\t').append(c.disablePreview ? '1' : '0')
                    .append('\t').append(c.enabled ? '1' : '0')
                    .append('\t').append(joinLongs(c.targetChats))
                    .append('\t').append(esc(formatButtonsPreview(c.buttons)))
                    .append('\t').append(c.ownerAccount).append('\n');
        }
        for (RotationRule rot : rotations.values()) {
            sb.append("ROT\t").append(esc(rot.name)).append('\t')
                    .append(esc(String.join(",", rot.campaignNames))).append('\n');
        }
        return sb.toString();
    }

    public synchronized void loadFromSerializedState(String raw) {
        campaigns.clear();
        rotations.clear();
        whitelist.clear();
        muteRules.clear();
        quietSnapshots.clear();
        loadedFromConfig = true;
        deserializeIntoSelf(raw);
    }

    private void deserializeIntoSelf(String raw) {
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            try {
                if ("WL".equals(p[0]) && p.length >= 2) {
                    whitelist.add(Long.parseLong(p[1]));
                } else if ("MUTE".equals(p[0]) && p.length >= 5) {
                    long sid = Long.parseLong(p[1]);
                    long cid = Long.parseLong(p[2]);
                    boolean wc = "1".equals(p[3]);
                    String mode = unesc(p[4]);
                    muteRules.put(muteKey(sid, cid, wc), new MuteRule(sid, cid, wc, mode));
                } else if ("QUIET".equals(p[0]) && p.length >= 9) {
                    long did = Long.parseLong(p[1]);
                    quietSnapshots.put(did, new QuietSnapshot(
                            did, Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                            "1".equals(p[4]), "1".equals(p[5]),
                            unesc(p[6]), unesc(p[7]), Long.parseLong(p[8])));
                } else if ("CAMP".equals(p[0]) && p.length >= 11) {
                    BroadcastCampaign c = new BroadcastCampaign(
                            unesc(p[1]), unesc(p[2]), Integer.parseInt(p[3]),
                            Long.parseLong(p[4]), Integer.parseInt(p[5]), unesc(p[6]));
                    c.disablePreview = "1".equals(p[7]);
                    c.enabled = "1".equals(p[8]);
                    if (!p[9].isEmpty()) {
                        for (String cidStr : p[9].split(",")) {
                            if (!cidStr.trim().isEmpty()) c.targetChats.add(Long.parseLong(cidStr.trim()));
                        }
                    }
                    String btnLayout = unesc(p[10]);
                    if (!btnLayout.isEmpty()) {
                        c.buttons.addAll(parseButtonLayout(btnLayout));
                    }
                    if (p.length >= 12 && !p[11].trim().isEmpty()) {
                        try {
                            c.ownerAccount = Integer.parseInt(p[11].trim());
                        } catch (NumberFormatException ignored) {
                            c.ownerAccount = -1;
                        }
                    }
                    campaigns.put(c.name, c);
                } else if ("ROT".equals(p[0]) && p.length >= 3) {
                    String name = unesc(p[1]);
                    String joined = unesc(p[2]);
                    List<String> names = new ArrayList<>();
                    for (String item : joined.split(",")) {
                        if (!item.trim().isEmpty()) names.add(item.trim());
                    }
                    if (!names.isEmpty()) rotations.put(name, new RotationRule(name, names));
                }
            } catch (Throwable ignored) {}
        }
    }

    private void persistIfBound() {
        ColgramConfig.setColemailerState(serializeState());
    }

    private static String joinLongs(Set<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    private static String esc(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String unesc(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char nxt = raw.charAt(++i);
                if (nxt == 't') sb.append('\t');
                else if (nxt == 'n') sb.append('\n');
                else if (nxt == 'r') sb.append('\r');
                else sb.append(nxt);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
