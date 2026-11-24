package org.colgram.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Big-Context, Autonomous Intent Planner & Anti-Hallucination Engine:
 * - Zero-manual-choice autonomous intent detection (web research, chat digest, cross-chat RAG,
 *   vision/video grab, direct input-field draft insertion, and in-app Colgram commands)
 * - Hierarchical BM25 + recency retriever across arbitrarily large single-chat or cross-chat logs
 * - Sentence-level extractive synthesizer for clean, high-signal answers even before GGUF weights finish downloading
 */
public final class ColgramAiContextEngine {

    public enum AssistantMode {
        AUTO,
        CHAT,
        DRAFT,
        SEARCH,
        VISION,
        ACTION,
        PLUGIN
    }

    public static final class ConversationTurn {
        public final String userQuery;
        public final String assistantReply;

        public ConversationTurn(String userQuery, String assistantReply) {
            this.userQuery = userQuery == null ? "" : userQuery.trim();
            this.assistantReply = assistantReply == null ? "" : assistantReply.trim();
        }
    }

    public static final class IntentPlan {
        public final AssistantMode resolvedMode;
        public final boolean shouldSearchWeb;
        public final boolean shouldDraftIntoInput;
        public final boolean shouldAutoGrabMedia;
        public final boolean shouldIncludeCrossChat;
        public final boolean shouldUseActiveChatContext;
        public final String inAppAction;
        public final String intentSummary;

        public IntentPlan(
                AssistantMode resolvedMode,
                boolean shouldSearchWeb,
                boolean shouldDraftIntoInput,
                boolean shouldAutoGrabMedia,
                boolean shouldIncludeCrossChat,
                String inAppAction,
                String intentSummary
        ) {
            this(resolvedMode, shouldSearchWeb, shouldDraftIntoInput, shouldAutoGrabMedia,
                    shouldIncludeCrossChat, false, inAppAction, intentSummary);
        }

        public IntentPlan(
                AssistantMode resolvedMode,
                boolean shouldSearchWeb,
                boolean shouldDraftIntoInput,
                boolean shouldAutoGrabMedia,
                boolean shouldIncludeCrossChat,
                boolean shouldUseActiveChatContext,
                String inAppAction,
                String intentSummary
        ) {
            this.resolvedMode = resolvedMode == null ? AssistantMode.CHAT : resolvedMode;
            this.shouldSearchWeb = shouldSearchWeb;
            this.shouldDraftIntoInput = shouldDraftIntoInput;
            this.shouldAutoGrabMedia = shouldAutoGrabMedia;
            this.shouldIncludeCrossChat = shouldIncludeCrossChat;
            this.shouldUseActiveChatContext = shouldUseActiveChatContext;
            this.inAppAction = inAppAction == null ? "" : inAppAction;
            this.intentSummary = intentSummary == null ? "" : intentSummary;
        }
    }

    public static final class ChatSnippet {
        public final long dialogId;
        public final String chatTitle;
        public final String sender;
        public final int dateSec;
        public final String text;
        public final boolean outgoing;
        public final String mediaKind;

        public ChatSnippet(
                long dialogId,
                String chatTitle,
                String sender,
                int dateSec,
                String text,
                boolean outgoing,
                String mediaKind
        ) {
            this.dialogId = dialogId;
            this.chatTitle = chatTitle == null ? "" : chatTitle.trim();
            this.sender = sender == null ? "" : sender.trim();
            this.dateSec = dateSec;
            this.text = text == null ? "" : text.trim();
            this.outgoing = outgoing;
            this.mediaKind = mediaKind == null ? "" : mediaKind.trim();
        }
    }

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\p{L}\\p{Nd}_]+");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-ZА-ЯЁ0-9«\"])|\\n+");
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern THINK_UNCLOSED = Pattern.compile("(?is)<think>.*$");

    private ColgramAiContextEngine() {}

    /**
     * Autonomously decides what tools and output behavior the assistant needs without requiring
     * the user to manually toggle modes or chips.
     */
    public static IntentPlan planAutonomousIntent(
            String rawQuery,
            AssistantMode requestedMode,
            boolean hasAttachedMedia,
            boolean hasActiveChat,
            boolean allowWebByDefault
    ) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        String l = q.toLowerCase(Locale.ROOT);

        // 1. Plugin authoring request ("создай плагин...", "напиши плагин...") -> dedicated Plugin Architect mode!
        if (requestedMode == AssistantMode.PLUGIN || ColgramAiAgentTools.looksLikePluginCreationRequest(l)) {
            return new IntentPlan(
                    AssistantMode.PLUGIN,
                    false,
                    false,
                    false,
                    false,
                    "",
                    "Создание плагина Colgram"
            );
        }

        // 2. Explicit UI navigation / Colgram action commands (e.g. "открой настройки прокси", "синхронизируй бота")
        String action = detectInAppAction(l);
        if (!action.isEmpty() && requestedMode != AssistantMode.DRAFT && requestedMode != AssistantMode.SEARCH) {
            return new IntentPlan(
                    AssistantMode.ACTION,
                    false,
                    false,
                    false,
                    false,
                    action,
                    describeActionLabel(action)
            );
        }

        // 3. Vision / Video media detection (attached explicitly OR mentioned implicitly in active chat)
        boolean mentionsMedia = containsAny(l,
                "на фото", "на картин", "изображен", "скриншот", "скрине", "видео", "кружок",
                "кружоч", "опиши картин", "опиши фото", "распознай", "ocr", "describe image", "video");
        boolean vision = hasAttachedMedia || requestedMode == AssistantMode.VISION || (hasActiveChat && mentionsMedia);

        // 4. Draft reply / write directly into ChatActivity input field
        boolean draft = requestedMode == AssistantMode.DRAFT || (hasActiveChat && looksLikeDraftCommand(l));

        // 5. Chat/Channel/Bot-only vs Web research routing
        boolean strictlyAboutThisChat = containsAny(l,
                "этот чат", "этом чате", "этот канал", "этом канале", "этот тг канал", "тг канал",
                "этот бот", "этом боте", "про этого бота", "что за бот", "что делает бот", "о чем бот", "о чём бот",
                "о чем канал", "о чём канал", "о чем этот", "о чём этот", "про этот канал", "про этот чат",
                "переписк", "собеседник", "сводк", "перескаж", "резюме чата", "истори",
                "о чем здесь", "о чём здесь", "что он написал", "что она написала", "что пишут здесь",
                "summarize chat", "what did they say", "about this channel", "about this bot") && !containsAny(l, "найди в инет", "в интернет", "загугли", "курс");

        boolean explicitlyAsksWeb = requestedMode == AssistantMode.SEARCH || (!strictlyAboutThisChat && (containsAny(l,
                "найди в инет", "найди в сети", "поищи", "загугли", "в интернете", "в инете", "в сети",
                "погода", "новости", "дата курса", "на какую дату",
                "search ", "google ")
                || ColgramAiWebSearch.looksLikeFinanceQuery(q)));

        boolean hasMath = ColgramAiWebSearch.tryEvalMath(q) != null;
        boolean web = explicitlyAsksWeb || (!vision && !strictlyAboutThisChat && !hasMath && allowWebByDefault && looksLikeOpenKnowledgeQuestion(l, hasActiveChat));

        // 6. Cross-chat scan ONLY when explicitly requested (never contaminate normal Koliti chats!)
        boolean explicitCrossChat = containsAny(l,
                "всех чат", "все чаты", "других чат", "непрочитан", "кто писал", "кто мне",
                "везде", "во всех переписк", "общее по чатам", "что нового в чатах", "across chats");
        boolean crossChat = explicitCrossChat;

        // 7. Only inject active Telegram chat/channel messages when the user actually asks about it, drafts a reply, or uses chat media.
        //    Normal questions ("привет", "напиши код", "объясни...") stay 100% pure, fast, and unpolluted!
        boolean useActiveChat = hasActiveChat && (strictlyAboutThisChat || draft || vision);

        AssistantMode resolved;
        if (vision) resolved = AssistantMode.VISION;
        else if (draft) resolved = AssistantMode.DRAFT;
        else if (web) resolved = AssistantMode.SEARCH;
        else resolved = AssistantMode.CHAT;

        List<String> steps = new ArrayList<>();
        if (vision) steps.add("Vision");
        if (hasMath) steps.add("Расчёт");
        else if (web) steps.add("Поиск в сети");
        if (crossChat) steps.add("Все чаты");
        else if (useActiveChat) steps.add("Контекст чата");
        if (draft) steps.add("В поле ввода");
        String summary = steps.isEmpty() ? "Koliti" : joinBullet(steps);

        return new IntentPlan(resolved, web, draft, !hasAttachedMedia && vision, crossChat, useActiveChat, "", summary);
    }

    private static String detectInAppAction(String l) {
        if (l.isEmpty()) return "";
        // Never collapse compound write/send/message prompts ("перейди в чат избранное и там напиши привет") into bare navigation!
        if (containsAny(l, "напиши", "написать", "отправ", "впиши", "вставь", "скинь", "скажи", "передай", "напечатай", "придумай", "сочини")) {
            return "";
        }
        // Only intercept explicit imperative navigation commands at the start of the message.
        boolean startsImperative = l.startsWith("открой ") || l.startsWith("перейди ") || l.startsWith("запусти ")
                || l.startsWith("покажи настройки") || l.startsWith("синхрониз") || l.startsWith("open ");
        if (!startsImperative) return "";
        if (containsAny(l, "модел", "gguf", "studio", "koliti studio")) return "OPEN_STUDIO";
        if (containsAny(l, "прокси", "обход", "warp", "dpi", "vless", "proxy")) return "OPEN_PROXY";
        if (containsAny(l, "почт", "temp mail", "tempmail", "временную")) return "OPEN_TEMPMAIL";
        if (containsAny(l, "защитник", "утечк")) return "OPEN_DEFENDER";
        if (containsAny(l, "антиспам", "colemailer")) return "OPEN_ANTISPAM";
        if (containsAny(l, "верси", "обновлен")) return "OPEN_VERSIONS";
        if (containsAny(l, "плагин", "маркетплейс")) return "OPEN_PLUGINS";
        if (containsAny(l, "синхрониз", "чаты бота", "обнови чаты")) return "SYNC_BOT";
        if (containsAny(l, "избран", "чат со мной")) return "OPEN_SAVED";
        if (containsAny(l, "пасхал", "matrix", "матриц")) return "EASTER_EGG";
        if (containsAny(l, "настройк")) return "OPEN_SETTINGS";
        return "";
    }

    private static String describeActionLabel(String action) {
        switch (action) {
            case "OPEN_STUDIO": return "Открываю Koliti Studio";
            case "OPEN_PROXY": return "Настройки обхода и прокси";
            case "OPEN_TEMPMAIL": return "Временная почта";
            case "OPEN_DEFENDER": return "Бот-защитник Telegram";
            case "OPEN_ANTISPAM": return "Антиспам и фильтры";
            case "OPEN_VERSIONS": return "Версии Telegram";
            case "OPEN_PLUGINS": return "Плагины Colgram";
            case "SYNC_BOT": return "Синхронизация чатов бота";
            case "OPEN_SAVED": return "Открываю Избранное";
            case "TOGGLE_THEME": return "Переключаю тему";
            case "EASTER_EGG": return "Секретный режим";
            case "OPEN_SETTINGS": return "Настройки Colgram";
            default: return "Команда Colgram";
        }
    }

    private static boolean looksLikeDraftCommand(String l) {
        if (ColgramAiAgentTools.looksLikePluginCreationRequest(l)) return false;
        // Cross-chat navigation/drafting ("перейди в чат избранное и напиши...") is handled by [[TOOL:DRAFT_TO_CHAT|...]],
        // NOT local active-chat auto-insertion!
        if (containsAny(l, "перейди в", "перейти в", "открой чат", "открыть чат",
                "в избранн", "в избра", "в чате ", "в чат ", "в диалог", "в лс ")) {
            return false;
        }
        return containsAny(l,
                "напиши ему", "напиши ей", "напиши ответ", "ответь", "составь ответ",
                "составь сообщение", "сформулируй", "придумай ответ", "предложи ответ",
                "подготовь сообщение", "подготовь текст", "перепиши для отправки", "вставь",
                "впиши", "в поле ввода", "введи в чат",
                "скажи ему", "скажи ей", "отпишись", "сделай ответ", "вежливый отказ",
                "напиши что", "напиши им", "draft", "reply to", "insert into");
    }

    private static final Pattern CASUAL_GREETING_PATTERN = Pattern.compile(
            "(?iu)^(?:ку+|прив(?:ет|етик|етствую)?|хай|здаров(?:а)?|йо+|здравствуй(?:те)?|добр(?:ое|ый)\\s+(?:утро|день|вечер)|hello|hi+|hey|как\\s+дела|что\\s+делаешь|ты\\s+тут|жив|ало+)[\\s!?.,]*$");
    private static final Pattern SHORT_REACTION_PATTERN = Pattern.compile(
            "(?iu)^(?:че+|чё+|чо+|што|что|ага|угу|ок|окей|ладно|пон|понял|ясно|спс|спасибо)[\\s!?.,]*$");
    private static final Pattern PURE_NUMERIC_FX_REPLY = Pattern.compile(
            "(?iu)^[0-9.,\\s=]+(?:usd|usdt|rub|eur|btc|eth|ton|₽|\\$|€)[^\\n]{0,40}$");

    public static boolean isCasualGreetingOrTopicReset(String query) {
        if (query == null) return false;
        String q = query.trim();
        return !q.isEmpty() && q.length() <= 36 && CASUAL_GREETING_PATTERN.matcher(q).matches();
    }

    public static boolean isPureNumericFinanceReply(String reply) {
        if (reply == null) return false;
        String r = reply.trim();
        return !r.isEmpty() && r.length() <= 80 && !r.contains("\n") && PURE_NUMERIC_FX_REPLY.matcher(r).matches();
    }

    /**
     * Filters conversation history before sending to the neural model:
     * - Drops consecutive duplicate/parrot assistant replies so poisoned sessions heal immediately
     * - Resets history on fresh greetings ("ку", "привет") and drops unrelated pure numeric FX replies
     */
    public static String[] buildCleanHistoryPairs(List<ConversationTurn> history, String currentQuery) {
        if (history == null || history.isEmpty()) return null;
        boolean curIsFinance = ColgramAiWebSearch.looksLikeFinanceQuery(currentQuery);
        boolean curIsGreeting = isCasualGreetingOrTopicReset(currentQuery);

        List<ConversationTurn> clean = new ArrayList<>();
        String prevNormReply = "";
        for (int i = 0; i < history.size(); i++) {
            ConversationTurn t = history.get(i);
            if (t == null || t.userQuery.isEmpty() || t.assistantReply.isEmpty()) continue;
            String normReply = t.assistantReply.trim().toLowerCase(Locale.ROOT);
            if (normReply.equals(prevNormReply)) {
                continue;
            }
            prevNormReply = normReply;
            if (!curIsFinance && isPureNumericFinanceReply(t.assistantReply)) {
                continue;
            }
            clean.add(t);
        }
        if (clean.isEmpty() || curIsGreeting) return null;
        int from = Math.max(0, clean.size() - 4);
        int count = clean.size() - from;
        String[] pairs = new String[count * 2];
        for (int i = 0; i < count; i++) {
            ConversationTurn t = clean.get(from + i);
            pairs[i * 2] = t.userQuery;
            String a = t.assistantReply;
            pairs[i * 2 + 1] = a.length() > 320 ? a.substring(0, 317) + "…" : a;
        }
        return pairs;
    }

    public static boolean isParrotRepeatOfHistory(
            String candidateReply,
            String currentQuery,
            List<ConversationTurn> history
    ) {
        if (candidateReply == null || candidateReply.trim().isEmpty() || history == null || history.isEmpty()) {
            return false;
        }
        String normCand = candidateReply.trim().toLowerCase(Locale.ROOT);
        String normQ = currentQuery == null ? "" : currentQuery.trim().toLowerCase(Locale.ROOT);
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - 3); i--) {
            ConversationTurn t = history.get(i);
            if (t == null) continue;
            String prevAns = t.assistantReply.trim().toLowerCase(Locale.ROOT);
            String prevQ = t.userQuery.trim().toLowerCase(Locale.ROOT);
            if (!prevAns.isEmpty() && prevAns.equals(normCand) && !prevQ.equals(normQ)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decides when Qwen3.5 should engage explicit chain-of-thought reasoning (<think>...</think>).
     * Normal chat, everyday questions, coding, summaries, drafts, and greetings stay in instant
     * Turbo mode (prefilled empty think block -> visible Russian tokens stream immediately!).
     * Deep thinking activates ONLY when the user explicitly requests deep reasoning or formal proofs
     * (or toggles Deep Thinking ON in Koliti Studio).
     */
    public static boolean shouldUseAdaptiveThinking(String query, IntentPlan plan, boolean hasMedia) {
        if (query == null || query.trim().isEmpty()) return false;
        if (hasMedia) return false;
        if (plan != null && (plan.resolvedMode == AssistantMode.DRAFT || plan.resolvedMode == AssistantMode.ACTION)) {
            return false;
        }
        String trimmed = query.trim();
        if (isCasualGreetingOrTopicReset(trimmed) || SHORT_REACTION_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        String l = trimmed.toLowerCase(Locale.ROOT);
        if (containsAny(l, "анекдот", "шутк", "спой", "песн", "песенк")) {
            return false;
        }
        return containsAny(l,
                "подумай", "глубоко проанализ", "глубокий анализ", "глубоко разбер",
                "докажи", "доказательств", "пошагово реши", "распиши по шагам",
                "step by step", "think deeply", "deep reasoning", "chain of thought", "prove that");
    }

    private static boolean looksLikeOpenKnowledgeQuestion(String l, boolean hasActiveChat) {
        if (l == null || l.isEmpty()) return false;
        if (isCasualGreetingOrTopicReset(l)) {
            return false;
        }
        // Casual chat, coding, creative writing & pure math go directly to local Koliti Core Ultra!
        if (containsAny(l,
                "привет", "здравствуй", "хай", "здаров", "доброе утро", "добрый день", "добрый вечер",
                "hello", "hi ", "hey", "как дела", "кто ты", "ты кто", "что ты умеешь", "что умеешь",
                "спасибо", "благодарю", "пока", "ок", "ладно", "круто", "молодец", "спой", "песн",
                "напиши код", "код на ", "python", "java", "c++", "javascript", "реши ", "придумай",
                "анекдот", "шутк", "стих", "сказк", "перепиши", "улучши текст", "исправь ошиб")) {
            return false;
        }
        if (containsAny(l, "чаты", "диалог", "сообщени", "кто писал", "сводка")) {
            return false;
        }
        if (ColgramAiWebSearch.looksLikeFinanceQuery(l)) {
            return true;
        }
        // Only trigger automatic live web search for genuine live/current-event/price/release queries
        // (general encyclopedic questions go straight to clean Qwen3.5-2B UD-Q4_K_XL with zero network latency!)
        return containsAny(l,
                "погода", "новости", "что нового в мире", "главные события", "актуальн",
                "в 2026", "в 2025", "сегодня в мире", "кто сейчас президент", "когда выйдет", "когда вышел",
                "сколько стоит", "дата выхода", "википед", "ссылк");
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || needles == null) return false;
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    /**
     * Selects the most informative messages from a large pool using BM25 keyword overlap +
     * recency anchoring, preserving chronological flow within maxChars.
     */
    public static List<ChatSnippet> selectRelevantContext(
            List<ChatSnippet> allSnippets,
            String query,
            int maxMessages,
            int maxChars
    ) {
        if (allSnippets == null || allSnippets.isEmpty()) {
            return Collections.emptyList();
        }
        int limitMsgs = Math.max(4, maxMessages);
        int limitChars = Math.max(800, maxChars);

        List<String> queryTerms = tokenize(query);
        int n = allSnippets.size();

        Map<String, Integer> df = new HashMap<>();
        double avgLen = 0.0;
        List<List<String>> docTokens = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<String> toks = tokenize(allSnippets.get(i).text + " " + allSnippets.get(i).sender + " " + allSnippets.get(i).chatTitle);
            docTokens.add(toks);
            avgLen += toks.size();
            if (!queryTerms.isEmpty()) {
                Set<String> seen = new LinkedHashSet<>(toks);
                for (String qt : queryTerms) {
                    if (seen.contains(qt)) {
                        df.put(qt, df.getOrDefault(qt, 0) + 1);
                    }
                }
            }
        }
        avgLen = Math.max(1.0, avgLen / Math.max(1, n));

        final double k1 = 1.5;
        final double b = 0.75;
        final double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            List<String> toks = docTokens.get(i);
            double score = 0.0;
            if (!queryTerms.isEmpty() && !toks.isEmpty()) {
                Map<String, Integer> tf = new HashMap<>();
                for (String t : toks) {
                    tf.put(t, tf.getOrDefault(t, 0) + 1);
                }
                for (String qt : queryTerms) {
                    int f = tf.getOrDefault(qt, 0);
                    if (f == 0) continue;
                    int docFreq = df.getOrDefault(qt, 0);
                    double idf = Math.log(1.0 + (n - docFreq + 0.5) / (docFreq + 0.5));
                    double denom = f + k1 * (1.0 - b + b * (toks.size() / avgLen));
                    score += idf * ((f * (k1 + 1.0)) / denom);
                }
            }
            double recency = ((double) (i + 1)) / n;
            scores[i] = score + recency * 0.85;
        }

        int tailAnchor = Math.min(n, Math.max(6, limitMsgs / 3));
        Set<Integer> chosenIdx = new LinkedHashSet<>();
        for (int i = Math.max(0, n - tailAnchor); i < n; i++) {
            chosenIdx.add(i);
        }

        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) order.add(i);
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer bIdx) {
                return Double.compare(scores[bIdx], scores[a]);
            }
        });
        for (int idx : order) {
            if (chosenIdx.size() >= limitMsgs) break;
            chosenIdx.add(idx);
        }

        List<Integer> sortedChrono = new ArrayList<>(chosenIdx);
        Collections.sort(sortedChrono);

        List<ChatSnippet> out = new ArrayList<>();
        int usedChars = 0;
        for (int idx : sortedChrono) {
            ChatSnippet s = allSnippets.get(idx);
            String body = s.text;
            if (body.length() > 240) {
                body = body.substring(0, 237) + "…";
            }
            int cost = body.length() + s.sender.length() + s.chatTitle.length() + 18;
            if (usedChars + cost > limitChars && !out.isEmpty()) {
                continue;
            }
            usedChars += cost;
            out.add(new ChatSnippet(s.dialogId, s.chatTitle, s.sender, s.dateSec, body, s.outgoing, s.mediaKind));
        }
        return out;
    }

    public static String formatChatContext(List<ChatSnippet> snippets, boolean multiChat) {
        if (snippets == null || snippets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ChatSnippet s : snippets) {
            if (multiChat && !s.chatTitle.isEmpty()) {
                sb.append('[').append(s.chatTitle).append("] ");
            }
            String who = s.outgoing ? "Вы" : (s.sender.isEmpty() ? "Собеседник" : s.sender);
            sb.append(who).append(": ");
            if (!s.mediaKind.isEmpty()) {
                sb.append('(').append(s.mediaKind).append(") ");
            }
            sb.append(s.text).append('\n');
        }
        return sb.toString().trim();
    }

    public static String buildSystemPrompt(AssistantMode mode) {
        return buildSystemPrompt(mode, false, false);
    }

    public static String buildSystemPrompt(AssistantMode mode, boolean hasChatContext, boolean hasWebContext) {
        return buildSystemPrompt(mode, hasChatContext, hasWebContext, false);
    }

    public static String buildSystemPrompt(AssistantMode mode, boolean hasChatContext, boolean hasWebContext, boolean includeToolGuide) {
        if (mode == AssistantMode.PLUGIN) {
            return "Ты — Koliti, опытный Python-архитектор плагинов Colgram.\n" + ColgramAiAgentTools.pluginAuthoringGuide();
        }
        // Crisp, ultra-compact persona (~85 tokens, static across modes for instant KV+SSM prefix reuse):
        String base = "Ты — Koliti, умный и живой ИИ-ассистент в Colgram. "
                + "Отвечай грамотно, кратко и по сути на языке пользователя, без воды. "
                + "В коде и расчётах будь точен; не выдумывай неизвестные факты. "
                + "Формулы пиши в LaTeX ($...$). "
                + "Для черновика сообщения верни ТОЛЬКО готовый текст.";
        if (!includeToolGuide) {
            return base;
        }
        return base + "\nДля действий в Colgram добавь в конец тег: "
                + "[[TOOL:SEND_TO_CHAT|имя|текст]], [[TOOL:DRAFT_TO_CHAT|имя|текст]], [[TOOL:READ_CHAT|имя|12]], "
                + "[[TOOL:LIST_CHATS]], [[TOOL:OPEN_CHAT|имя]], [[TOOL:WEB_SEARCH|запрос]], [[TOOL:GET_SETTINGS]], "
                + "[[TOOL:OPEN_SCREEN|экран]], [[TOOL:SET_THEME|DARK/LIGHT/CYBER_ON/CYBER_OFF]], [[TOOL:SET_SETTING|ключ=1/0]], [[TOOL:SING_SONG]].";
    }

    /**
     * Classifies prompt depth so casual chat replies in ~4 seconds (saving battery/CPU),
     * normal questions & quick math answer fast with focused CoT, and complex math/code/logic
     * get deep MAX reasoning:
     * 0 = fast casual turn, 1 = focused reasoning (general & quick math), 2 = deep MAX reasoning (code/proofs/complex).
     */
    public static int classifyReasoningTier(String query) {
        if (query == null) return 0;
        String q = query.trim();
        if (q.isEmpty()) return 0;
        String low = q.toLowerCase(Locale.ROOT);
        // Tier 2 deep MAX reasoning: long prompts, coding, proofs, equations, architecture/debugging/analysis
        if (q.length() >= 80
                || low.contains("```") || low.contains("def ") || low.contains("class ") || low.contains("function ")
                || Pattern.compile("(?iu)\\b(докажи|сравни|алгоритм|код|напиши|програм|ошибк|баг|формул|уравнен|задач|логик|анализ|разбери|реши|объясни подробно|prove|code|algorithm|debug|compare|analyze|equation|solve)\\b").matcher(low).find()) {
            return 2;
        }
        // Tier 1 focused reasoning: quick arithmetic/digits, "why/how/explain", medium questions
        if (low.contains("=") || low.contains("+") || low.contains("*") || low.contains("/")
                || Pattern.compile("\\d").matcher(q).find()
                || Pattern.compile("(?iu)\\b(почему|зачем|объясни|посчитай|вычисли|сколько|как работает|how|why|explain|calculate|cost|total|more than)\\b").matcher(low).find()) {
            return 1;
        }
        String[] words = low.replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim().split("\\s+");
        if (words.length <= 4 && q.length() <= 34) {
            return 0;
        }
        return 1;
    }

    /**
     * Converts a LaTeX math expression (without outer $/$$ delimiters) into crisp, readable
     * mathematical Unicode typography (superscripts, subscripts, fractions, roots, Greek, operators).
     */
    public static String renderLatexFormula(String tex) {
        if (tex == null || tex.isEmpty()) return "";
        String s = tex.trim();
        // Preserve literal escaped braces \{ \}
        s = s.replace("\\{", "\uE001").replace("\\}", "\uE002");
        // Clean LaTeX block environments (\begin{aligned}...\end{aligned}, \begin{cases}...\end{cases}, etc.)
        s = s.replaceAll("\\\\begin\\{cases\\}", "{ ").replaceAll("\\\\end\\{cases\\}", " }");
        s = s.replaceAll("\\\\(?:begin|end)\\{[a-zA-Z*]+\\}", " ");
        s = s.replace("\\\\", " ; ").replace("&=", "=").replace("&", " ");
        // Strip formatting wrappers \left, \right, \text{...}, \mathrm{...}, \mathbf{...}, \operatorname{...}
        s = s.replace("\\left", "").replace("\\right", "");
        s = s.replace("\\mathbb{R}", "ℝ").replace("\\mathbb{Z}", "ℤ")
                .replace("\\mathbb{N}", "ℕ").replace("\\mathbb{Q}", "ℚ").replace("\\mathbb{C}", "ℂ");
        s = s.replaceAll("\\\\(?:text|textbf|textit|mathrm|mathbf|mathit|mathbb|operatorname|boldsymbol|overline|bar|hat|vec)\\{([^{}]*)\\}", "$1");
        // Strip leading backslash on standard math functions (\sin, \cos, \log, \ln, \lim, etc.)
        s = s.replaceAll("\\\\(sin|cos|tan|cot|sec|csc|arcsin|arccos|arctan|sinh|cosh|tanh|log|ln|lg|exp|lim|sup|inf|max|min|det|mod|gcd|lcm)\\b", "$1");
        s = s.replace("\\,", " ").replace("\\;", " ").replace("\\:", " ").replace("\\!", "").replace("\\quad", "  ").replace("\\qquad", "   ");

        // Interleave \sqrt and \frac expansion up to 4 passes so both \frac{-b \pm \sqrt{D}}{2a}
        // and \sqrt{\frac{a}{b}} expand cleanly from innermost to outermost!
        Pattern fracPat = Pattern.compile("\\\\d?frac\\{([^{}]*)\\}\\{([^{}]*)\\}");
        Pattern sqrtBracePat = Pattern.compile("\\\\sqrt\\{([^{}]*)\\}");
        Pattern sqrtSinglePat = Pattern.compile("\\\\sqrt\\s+([A-Za-z0-9])");
        for (int pass = 0; pass < 4; pass++) {
            String before = s;
            s = s.replaceAll("\\\\sqrt\\[3\\]\\{([^{}]*)\\}", "∛($1)");
            s = s.replaceAll("\\\\sqrt\\[4\\]\\{([^{}]*)\\}", "∜($1)");

            Matcher sqM = sqrtBracePat.matcher(s);
            if (sqM.find()) {
                StringBuffer sqSb = new StringBuffer();
                sqM.reset();
                while (sqM.find()) {
                    String inner = sqM.group(1).trim();
                    String rep = (inner.length() <= 2 && !inner.contains(" ") && !inner.contains("/"))
                            ? ("√" + inner) : ("√(" + inner + ")");
                    sqM.appendReplacement(sqSb, Matcher.quoteReplacement(rep));
                }
                sqM.appendTail(sqSb);
                s = sqSb.toString();
            }
            s = sqrtSinglePat.matcher(s).replaceAll("√$1");

            Matcher fm = fracPat.matcher(s);
            if (fm.find()) {
                StringBuffer sb = new StringBuffer();
                fm.reset();
                while (fm.find()) {
                    String num = fm.group(1).trim();
                    String den = fm.group(2).trim();
                    String nWrap = needsParens(num, false) ? "(" + num + ")" : num;
                    String dWrap = needsParens(den, true) ? "(" + den + ")" : den;
                    fm.appendReplacement(sb, Matcher.quoteReplacement(nWrap + " / " + dWrap));
                }
                fm.appendTail(sb);
                s = sb.toString();
            }
            if (s.equals(before)) break;
        }

        // Standard LaTeX operators & Greek symbols
        String[][] symbols = {
                {"\\pm", "±"}, {"\\mp", "∓"}, {"\\times", "×"}, {"\\cdot", "·"}, {"\\div", "÷"},
                {"\\neq", "≠"}, {"\\ne", "≠"}, {"\\leq", "≤"}, {"\\le", "≤"}, {"\\geq", "≥"}, {"\\ge", "≥"},
                {"\\approx", "≈"}, {"\\equiv", "≡"}, {"\\sim", "∼"}, {"\\infty", "∞"}, {"\\propto", "∝"},
                {"\\rightarrow", "→"}, {"\\to", "→"}, {"\\leftarrow", "←"}, {"\\leftrightarrow", "↔"},
                {"\\implies", "⇒"}, {"\\impliedby", "⇐"}, {"\\Rightarrow", "⇒"}, {"\\Leftarrow", "⇐"},
                {"\\Leftrightarrow", "⇔"}, {"\\iff", "⇔"}, {"\\therefore", "∴"}, {"\\because", "∵"},
                {"\\langle", "⟨"}, {"\\rangle", "⟩"},
                {"\\sum", "∑"}, {"\\prod", "∏"}, {"\\int", "∫"}, {"\\oint", "∮"}, {"\\partial", "∂"}, {"\\nabla", "∇"},
                {"\\forall", "∀"}, {"\\exists", "∃"}, {"\\notin", "∉"}, {"\\in", "∈"}, {"\\subset", "⊂"},
                {"\\subseteq", "⊆"}, {"\\cup", "∪"}, {"\\cap", "∩"}, {"\\emptyset", "∅"}, {"\\degree", "°"}, {"\\circ", "°"},
                {"\\alpha", "α"}, {"\\beta", "β"}, {"\\gamma", "γ"}, {"\\delta", "δ"}, {"\\Delta", "Δ"},
                {"\\epsilon", "ε"}, {"\\varepsilon", "ε"}, {"\\zeta", "ζ"}, {"\\eta", "η"},
                {"\\theta", "θ"}, {"\\Theta", "Θ"}, {"\\lambda", "λ"}, {"\\Lambda", "Λ"},
                {"\\mu", "μ"}, {"\\nu", "ν"}, {"\\xi", "ξ"}, {"\\pi", "π"}, {"\\Pi", "Π"},
                {"\\rho", "ρ"}, {"\\sigma", "σ"}, {"\\Sigma", "Σ"}, {"\\tau", "τ"},
                {"\\phi", "φ"}, {"\\varphi", "φ"}, {"\\Phi", "Φ"}, {"\\chi", "χ"}, {"\\psi", "ψ"}, {"\\Psi", "Ψ"},
                {"\\omega", "ω"}, {"\\Omega", "Ω"},
                {"\\ldots", "…"}, {"\\cdots", "⋯"}, {"\\dots", "…"}
        };
        for (String[] pair : symbols) {
            s = s.replace(pair[0], pair[1]);
        }

        // Convert ^{...} and ^x to Unicode superscripts
        s = replaceScript(s, '^', true);
        // Convert _{...} and _x to Unicode subscripts
        s = replaceScript(s, '_', false);

        // Clean leftover grouping braces, restore literal braces, and apply typographic minus
        s = s.replace("{", "").replace("}", "");
        s = s.replace("\uE001", "{").replace("\uE002", "}");
        s = s.replace(" - ", " − ").replace("(-", "(−").replace("= -", "= −");
        s = s.replaceAll("\\s{2,}", " ").trim();
        return s;
    }

    private static boolean needsParens(String expr, boolean isDenominator) {
        if (expr == null || expr.length() <= 1) return false;
        if (expr.startsWith("(") && expr.endsWith(")")) return false;
        if (expr.contains(" ") || expr.contains("+") || expr.contains("-") || expr.contains("±")
                || expr.contains("−") || expr.contains("/")) {
            return true;
        }
        // In denominators like 2a, 2x, ab -> wrap as (2a) for unambiguous reading; keep pure numbers like 10 unwrapped
        return isDenominator && !expr.matches("[0-9]+") && !expr.matches("[A-Za-zΑ-Ωα-ω]");
    }

    private static String replaceScript(String input, char marker, boolean isSuper) {
        StringBuilder out = new StringBuilder(input.length());
        int n = input.length();
        for (int i = 0; i < n; i++) {
            char c = input.charAt(i);
            if (c == marker && i + 1 < n) {
                if (input.charAt(i + 1) == '{') {
                    int end = input.indexOf('}', i + 2);
                    if (end > i + 2) {
                        String inner = input.substring(i + 2, end);
                        String mapped = mapScriptChars(inner, isSuper);
                        out.append(mapped != null ? mapped : (marker + "(" + inner + ")"));
                        i = end;
                        continue;
                    }
                } else {
                    String single = String.valueOf(input.charAt(i + 1));
                    String mapped = mapScriptChars(single, isSuper);
                    if (mapped != null) {
                        out.append(mapped);
                        i++;
                        continue;
                    }
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String mapScriptChars(String src, boolean isSuper) {
        StringBuilder sb = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == ' ' || ch == ',') {
                sb.append(ch);
                continue;
            }
            char mapped = isSuper ? toSuperscriptChar(ch) : toSubscriptChar(ch);
            if (mapped == 0) return null;
            sb.append(mapped);
        }
        return sb.toString();
    }

    private static char toSuperscriptChar(char ch) {
        switch (ch) {
            case '0': return '⁰'; case '1': return '¹'; case '2': return '²'; case '3': return '³';
            case '4': return '⁴'; case '5': return '⁵'; case '6': return '⁶'; case '7': return '⁷';
            case '8': return '⁸'; case '9': return '⁹'; case '+': return '⁺'; case '-': return '⁻';
            case '−': return '⁻'; case '=': return '⁼'; case '(': return '⁽'; case ')': return '⁾';
            case 'n': return 'ⁿ'; case 'i': return 'ⁱ'; case 'x': return 'ˣ'; case 'y': return 'ʸ';
            case 'a': return 'ᵃ'; case 'b': return 'ᵇ'; case 'c': return 'ᶜ'; case 'k': return 'ᵏ';
            case 'm': return 'ᵐ'; case 't': return 'ᵗ';
            default: return 0;
        }
    }

    private static char toSubscriptChar(char ch) {
        switch (ch) {
            case '0': return '₀'; case '1': return '₁'; case '2': return '₂'; case '3': return '₃';
            case '4': return '₄'; case '5': return '₅'; case '6': return '₆'; case '7': return '₇';
            case '8': return '₈'; case '9': return '₉'; case '+': return '₊'; case '-': return '₋';
            case '−': return '₋'; case '=': return '₌'; case '(': return '₍'; case ')': return '₎';
            case 'a': return 'ₐ'; case 'e': return 'ₑ'; case 'h': return 'ₕ'; case 'i': return 'ᵢ';
            case 'j': return 'ⱼ'; case 'k': return 'ₖ'; case 'l': return 'ₗ'; case 'm': return 'ₘ';
            case 'n': return 'ₙ'; case 'o': return 'ₒ'; case 'p': return 'ₚ'; case 'r': return 'ᵣ';
            case 's': return 'ₛ'; case 't': return 'ₜ'; case 'u': return 'ᵤ'; case 'v': return 'ᵥ';
            case 'x': return 'ₓ';
            default: return 0;
        }
    }

    /**
     * Strips conversational preambles/postscripts and markdown from a model draft reply
     * so only the clean message text is placed into the Telegram chat input field.
     */
    public static String extractCleanDraftText(String rawReply) {
        if (rawReply == null) return "";
        String s = rawReply.replace("**", "").replace("__", "").trim();
        // Strip leading conversational preamble line like "Вот что нужно написать:" or "В избранное:"
        s = s.replaceFirst("(?iu)^(?:колли[,\\s]+)?(?:вот|конечно[,\\s]+вот|готово[,:\\s]*|держи[,:\\s]*|можно\\s+написать|в\\s+избранное|для\\s+чата)[^\\n]{0,70}:\\s*\\n+", "");
        // Strip trailing helper filler line like "Если нужно — могу показать..."
        s = s.replaceAll("(?iu)\\n+\\s*(?:если\\s+нужно|если\\s+хочешь|могу\\s+также|подскажи,?\\s+если)[\\s\\S]*$", "");
        s = s.trim();
        if ((s.startsWith("«") && s.endsWith("»"))
                || (s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'"))) {
            if (s.length() > 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    public static String buildUserPrompt(
            String userQuery,
            String activeChatTitle,
            String activeChatContext,
            String crossChatContext,
            String webContext,
            int attachedFramesCount,
            boolean videoGridMode
    ) {
        return buildUserPrompt(userQuery, activeChatTitle, activeChatContext, crossChatContext,
                webContext, attachedFramesCount, videoGridMode, null);
    }

    public static String buildUserPrompt(
            String userQuery,
            String activeChatTitle,
            String activeChatContext,
            String crossChatContext,
            String webContext,
            int attachedFramesCount,
            boolean videoGridMode,
            List<ConversationTurn> history
    ) {
        return buildUserPrompt(userQuery, activeChatTitle, activeChatContext, crossChatContext,
                webContext, "", attachedFramesCount, videoGridMode, history);
    }

    public static String buildUserPrompt(
            String userQuery,
            String activeChatTitle,
            String activeChatContext,
            String crossChatContext,
            String webContext,
            String dialogDirectory,
            int attachedFramesCount,
            boolean videoGridMode,
            List<ConversationTurn> history
    ) {
        String cleanQuery = userQuery == null ? "" : userQuery.trim();
        String qLow = cleanQuery.toLowerCase(Locale.ROOT);
        boolean asksCurrentDate = containsAny(qLow,
                "какая сегодня дата", "какое сегодня число", "какой сегодня день", "какой сейчас год",
                "what date is", "what day is today", "today's date");
        String effectiveWeb = webContext == null ? "" : webContext;
        if (asksCurrentDate && !effectiveWeb.contains(ColgramAiWebSearch.currentDateRu())) {
            effectiveWeb = ("Текущая дата: " + ColgramAiWebSearch.currentDateRu() + "\n" + effectiveWeb).trim();
        }
        String dirBlock = dialogDirectory == null ? "" : dialogDirectory.trim();
        boolean hasExtra = (activeChatContext != null && !activeChatContext.isEmpty())
                || (crossChatContext != null && !crossChatContext.isEmpty())
                || !effectiveWeb.isEmpty()
                || !dirBlock.isEmpty()
                || attachedFramesCount > 0
                || (history != null && !history.isEmpty());
        // For clean conversational turns (with native multi-turn ChatML in C++), pass the raw user message directly!
        if (!hasExtra) {
            return cleanQuery;
        }
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            sb.append("### Предыдущие реплики:\n");
            int from = Math.max(0, history.size() - 4);
            for (int i = from; i < history.size(); i++) {
                ConversationTurn t = history.get(i);
                sb.append("Пользователь: ").append(t.userQuery).append('\n');
                if (!t.assistantReply.isEmpty()) {
                    String shortReply = t.assistantReply.length() > 360
                            ? t.assistantReply.substring(0, 357) + "…"
                            : t.assistantReply;
                    sb.append("Ассистент: ").append(shortReply).append('\n');
                }
            }
            sb.append('\n');
        }
        if (!dirBlock.isEmpty()) {
            sb.append("### Доступные чаты Telegram:\n").append(dirBlock).append("\n\n");
        }
        if (activeChatContext != null && !activeChatContext.isEmpty()) {
            if (activeChatTitle != null && !activeChatTitle.isEmpty()) {
                sb.append("### Чат/канал «").append(activeChatTitle).append("»:\n");
            } else {
                sb.append("### Контекст чата:\n");
            }
            sb.append(activeChatContext).append("\n\n");
        }
        if (crossChatContext != null && !crossChatContext.isEmpty()) {
            sb.append("### Другие чаты:\n").append(crossChatContext).append("\n\n");
        }
        if (!effectiveWeb.isEmpty()) {
            sb.append("### Данные из сети:\n").append(effectiveWeb).append("\n\n");
        }
        if (attachedFramesCount > 0) {
            sb.append("### Вложение: ");
            if (videoGridMode) {
                sb.append("раскадровка видео (").append(attachedFramesCount).append(")\n\n");
            } else {
                sb.append("изображение (").append(attachedFramesCount).append(")\n\n");
            }
        }
        sb.append(cleanQuery);
        return sb.toString().trim();
    }

    public static String stripThinkingTags(String raw, boolean keepThinking) {
        return stripThinkingTags(raw, keepThinking, false);
    }

    /**
     * Strips or formats internal chain-of-thought blocks, including Qwen3.5's implicit prompt-opened
     * `<think>` mode where the generated token stream begins directly inside thinking and only emits `</think>`.
     */
    public static String stripThinkingTags(String raw, boolean keepThinking, boolean promptOpenedThink) {
        if (raw == null || raw.isEmpty()) return "";
        String sanitized = raw
                .replace("<|im_end|>", "")
                .replace("<|im_start|>", "")
                .replace("<|endoftext|>", "")
                .replace("<|eot_id|>", "");
        String lower = sanitized.toLowerCase(Locale.ROOT);
        int openIdx = lower.indexOf("<think>");
        int lastCloseIdx = lower.lastIndexOf("</think>");
        boolean implicitThink = promptOpenedThink || (lastCloseIdx >= 0 && (openIdx < 0 || lastCloseIdx < openIdx));

        if (implicitThink) {
            if (lastCloseIdx < 0) {
                if (!keepThinking) {
                    return "";
                }
                String body = sanitized.replaceFirst("(?is)^\\s*<think>\\s*", "").trim();
                return body.isEmpty() ? "" : ("Мысли:\n" + body);
            }
            String thinkPart = sanitized.substring(0, lastCloseIdx)
                    .replaceFirst("(?is)^\\s*<think>\\s*", "")
                    .replaceAll("(?is)</?think>", "")
                    .trim();
            String answerPart = sanitized.substring(lastCloseIdx + "</think>".length());
            answerPart = THINK_BLOCK.matcher(answerPart).replaceAll("");
            answerPart = THINK_UNCLOSED.matcher(answerPart).replaceAll("")
                    .replaceAll("(?is)</?think>", "").trim();
            if (!keepThinking) {
                return answerPart;
            }
            if (thinkPart.isEmpty()) return answerPart;
            if (answerPart.isEmpty()) return "Мысли:\n" + thinkPart;
            return "Мысли:\n" + thinkPart + "\n\n---\n" + answerPart;
        }

        if (keepThinking) {
            return sanitized.replace("<think>", "Мысли:\n").replace("</think>", "\n\n---\n").trim();
        }
        String cleaned = THINK_BLOCK.matcher(sanitized).replaceAll("");
        cleaned = THINK_UNCLOSED.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("(?is)</?think>", "");
        return cleaned.trim();
    }

    /**
     * Clean, grounded distiller used when local Koliti Core GGUF weights are not yet installed.
     * Never dumps raw scraped SEO/YouTube/RSS junk directly to the user.
     */
    public static String synthesizeSmartAnswer(
            IntentPlan plan,
            String query,
            String activeChatTitle,
            List<ChatSnippet> selectedChat,
            List<ChatSnippet> crossChat,
            List<ColgramAiWebSearch.SearchHit> webHits,
            boolean hasMediaAttachment
    ) {
        AssistantMode mode = plan != null ? plan.resolvedMode : AssistantMode.CHAT;
        if (mode == AssistantMode.DRAFT || (plan != null && plan.shouldDraftIntoInput)) {
            return composeSmartDraft(query, selectedChat, webHits);
        }

        String mathAns = ColgramAiWebSearch.tryEvalMath(query);
        if (mathAns != null) {
            return "Результат: " + mathAns;
        }

        StringBuilder out = new StringBuilder();

        // 1. Structured live data (Market / FX / News) formatted into crisp human-readable Russian prose
        if (webHits != null && !webHits.isEmpty()) {
            String structuredLive = formatLiveStructuredHits(webHits);
            if (!structuredLive.isEmpty()) {
                out.append(structuredLive);
            } else {
                List<RankedSentence> topSentences = rankBestSentences(query, webHits, 3);
                if (!topSentences.isEmpty()) {
                    for (int i = 0; i < topSentences.size(); i++) {
                        if (i > 0) out.append("\n\n");
                        out.append(topSentences.get(i).sentence);
                    }
                }
            }
        }

        // 2. Chat / Cross-chat digest (never dump active chat when the user explicitly asked a web/news question)
        if (selectedChat != null && !selectedChat.isEmpty() && (plan == null || !plan.shouldSearchWeb)) {
            if (out.length() > 0) out.append("\n\n");
            out.append(composeChatDigest(activeChatTitle, selectedChat, false));
        }

        if ((plan == null || plan.shouldIncludeCrossChat) && crossChat != null && !crossChat.isEmpty()) {
            if (out.length() > 0) out.append("\n\n");
            out.append(composeChatDigest("", crossChat, true));
        }

        if (hasMediaAttachment) {
            if (out.length() > 0) out.append("\n\n");
            out.append("Медиафайл подготовлен. Для локального разбора фото и видео установите ядро Koliti Core 4B в Koliti Studio.");
        }

        if (out.length() == 0) {
            return "";
        }
        return out.toString().trim();
    }

    private static String formatLiveStructuredHits(List<ColgramAiWebSearch.SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        List<String> newsLines = new ArrayList<>();
        for (ColgramAiWebSearch.SearchHit h : hits) {
            if (h == null) continue;
            if ("Live Market".equals(h.source) || "Live FX".equals(h.source)) {
                String body = !h.pageExcerpt.isEmpty() ? h.pageExcerpt : h.snippet;
                if (body.isEmpty()) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append("Live Market".equals(h.source) ? "Курсы криптовалют сейчас:\n" : "Курсы валют сейчас:\n");
                String[] parts = body.split("\\.\\s+");
                for (int i = 0; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.endsWith(".")) p = p.substring(0, p.length() - 1).trim();
                    if (p.isEmpty()) continue;
                    if (i > 0) sb.append('\n');
                    sb.append("— ").append(p);
                }
            } else if ("Live News".equals(h.source) && !h.title.isEmpty() && newsLines.size() < 5) {
                newsLines.add(trimClean(h.title, 150));
            }
        }
        if (!newsLines.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Главные новости сейчас:");
            for (String line : newsLines) {
                sb.append("\n— ").append(line);
            }
        }
        return sb.toString().trim();
    }

    public static String synthesizeHybridFallback(
            AssistantMode mode,
            String query,
            String activeChatTitle,
            List<ChatSnippet> selectedChat,
            List<ChatSnippet> crossChat,
            List<ColgramAiWebSearch.SearchHit> webHits,
            boolean hasMediaAttachment
    ) {
        IntentPlan synthetic = new IntentPlan(mode, webHits != null && !webHits.isEmpty(),
                mode == AssistantMode.DRAFT, false, crossChat != null && !crossChat.isEmpty(), "", "");
        return synthesizeSmartAnswer(synthetic, query, activeChatTitle, selectedChat, crossChat, webHits, hasMediaAttachment);
    }

    private static String composeSmartDraft(
            String query,
            List<ChatSnippet> selectedChat,
            List<ColgramAiWebSearch.SearchHit> webHits
    ) {
        String qLow = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String lastIncoming = "";
        if (selectedChat != null) {
            for (int i = selectedChat.size() - 1; i >= 0; i--) {
                ChatSnippet s = selectedChat.get(i);
                if (!s.outgoing && !s.text.isEmpty()) {
                    lastIncoming = s.text;
                    break;
                }
            }
        }

        // If the user explicitly asked to pull web facts into the drafted reply, synthesize clean sentence without URL clutter.
        if (webHits != null && !webHits.isEmpty()) {
            List<RankedSentence> best = rankBestSentences(query, webHits, 2);
            if (!best.isEmpty()) {
                StringBuilder reply = new StringBuilder();
                for (int i = 0; i < best.size(); i++) {
                    if (i > 0) reply.append(' ');
                    reply.append(best.get(i).sentence);
                }
                return reply.toString().trim();
            }
        }

        String math = ColgramAiWebSearch.tryEvalMath(query);
        if (math != null) {
            return math;
        }
        return "";
    }

    private static String composeChatDigest(String title, List<ChatSnippet> snippets, boolean multiChat) {
        StringBuilder sb = new StringBuilder();
        if (multiChat) {
            sb.append("Другие чаты:");
        } else if (title != null && !title.isEmpty()) {
            sb.append("Чат «").append(title).append("»:");
        } else {
            sb.append("Переписка:");
        }
        int start = Math.max(0, snippets.size() - (multiChat ? 4 : 5));
        for (int i = start; i < snippets.size(); i++) {
            ChatSnippet s = snippets.get(i);
            String prefix = multiChat && !s.chatTitle.isEmpty() ? "[" + s.chatTitle + "] " : "";
            String who = s.outgoing ? "Вы" : (s.sender.isEmpty() ? "Собеседник" : s.sender);
            sb.append("\n— ").append(prefix).append(who).append(": ").append(trimClean(s.text, 140));
        }
        return sb.toString();
    }

    private static final class RankedSentence {
        final String sentence;
        final int sourceIdx;
        final double score;

        RankedSentence(String sentence, int sourceIdx, double score) {
            this.sentence = sentence;
            this.sourceIdx = sourceIdx;
            this.score = score;
        }
    }

    private static List<RankedSentence> rankBestSentences(String query, List<ColgramAiWebSearch.SearchHit> hits, int maxSentences) {
        String cleanQ = ColgramAiWebSearch.cleanSearchQuery(query);
        boolean queryIsRussian = hasCyrillicChars(cleanQ);
        List<String> qTerms = tokenize(cleanQ);
        List<RankedSentence> all = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();

        for (int idx = 0; idx < hits.size(); idx++) {
            ColgramAiWebSearch.SearchHit h = hits.get(idx);
            if (ColgramAiWebSearch.isLowQualityHit(h)) continue;
            String combined = (!h.pageExcerpt.isEmpty() ? h.pageExcerpt : h.snippet);
            if (combined.isEmpty()) continue;

            String[] rawParts = SENTENCE_SPLIT.split(combined);
            for (int p = 0; p < rawParts.length; p++) {
                String sent = cleanDistilledSentence(rawParts[p]);
                if (sent.length() < 32 || sent.length() > 360) continue;
                if (ColgramAiWebSearch.isBoilerplateNoise(sent)) continue;
                // Do not leak raw unrequested English scrapes into a Russian user query unless it's structured live data
                if (queryIsRussian && !hasCyrillicChars(sent) && !h.source.contains("Live")) continue;
                String normKey = sent.toLowerCase(Locale.ROOT);
                if (!dedup.add(normKey)) continue;

                double score = 0.0;
                List<String> toks = tokenize(sent);
                int matchedTerms = 0;
                for (String qt : qTerms) {
                    for (String t : toks) {
                        if (t.equals(qt) || (qt.length() >= 4 && t.startsWith(qt))) {
                            score += 3.0;
                            matchedTerms++;
                            break;
                        }
                    }
                }
                if (matchedTerms == 0 && !h.source.contains("Wikipedia") && !h.source.contains("Live")) {
                    continue;
                }
                if (h.source.contains("Live") || h.source.contains("Wikipedia")) score += 2.6;
                if (p < 2) score += 1.4;
                if (containsAny(normKey, "это ", "представляет собой", "— ", "курс", "$", "₽", "rub", "usd")) score += 1.0;
                all.add(new RankedSentence(sent, idx + 1, score));
            }
        }

        Collections.sort(all, new Comparator<RankedSentence>() {
            @Override
            public int compare(RankedSentence a, RankedSentence b) {
                return Double.compare(b.score, a.score);
            }
        });
        if (all.size() > maxSentences) {
            return new ArrayList<>(all.subList(0, maxSentences));
        }
        return all;
    }

    private static String cleanDistilledSentence(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("https?://\\S+", "").trim();
        s = s.replaceAll("\\s*\\|\\s*[^|]{2,40}$", "").trim();
        if (s.endsWith("...") || s.endsWith("…")) {
            int lastDot = Math.max(s.lastIndexOf('.'), Math.max(s.lastIndexOf('!'), s.lastIndexOf('?')));
            if (lastDot > 32) {
                s = s.substring(0, lastDot + 1).trim();
            }
        }
        return s;
    }

    private static boolean hasCyrillicChars(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '\u0400' && ch <= '\u04FF') return true;
        }
        return false;
    }

    private static String trimClean(String text, int maxLen) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= maxLen ? t : t.substring(0, maxLen - 1).trim() + "…";
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN_SPLIT.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String tok = m.group();
            if (tok.length() >= 2) {
                out.add(tok);
            }
        }
        return out;
    }

    private static String joinBullet(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" • ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
