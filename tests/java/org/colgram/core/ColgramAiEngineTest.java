package org.colgram.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Isolated JVM unit tests for Colgram AI model catalog, BM25 big-context RAG compression,
 * anti-hallucination prompt/thinking stripping, keyless web search parsers, and safe JNI bridge guard.
 */
public final class ColgramAiEngineTest {

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void testCatalogAndBudget() {
        List<ColgramAiModels.ModelSpec> specs = ColgramAiModels.catalog();
        check(specs.size() == 3, "Expected 3 optimized Koliti v3 catalog tiers, got: " + specs.size());

        ColgramAiModels.ModelSpec koliti = ColgramAiModels.primary();
        check(ColgramAiModels.KOLITI_MODEL_ID.equals(koliti.id), "Expected koliti-core-ultra id");
        check("Колити Ядро v3 (Оптимизированная)".equals(koliti.title), "Expected branded Koliti Core v3 title: " + koliti.title);
        check(koliti.hasBundledAssets(), "Expected bundled offline GGUF assets");
        check("koliti_core.gguf".equals(koliti.filename), "Expected koliti_core.gguf filename");
        check("koliti_vision.gguf".equals(koliti.mmprojFilename), "Expected koliti_vision.gguf filename");
        check(koliti.supportsVision(), "Expected vision projector support");
        check("https://github.com/Kleisym/Colgram/releases/download/koliti-models-v3/koliti-core-v3.gguf".equals(koliti.modelDownloadUrl()),
                "Unexpected core download URL: " + koliti.modelDownloadUrl());
        check("https://github.com/Kleisym/Colgram/releases/download/koliti-models-v3/koliti-vision-v3.gguf".equals(koliti.mmprojDownloadUrl()),
                "Unexpected core vision URL: " + koliti.mmprojDownloadUrl());

        // Verify ALL catalog models use Kleisym/Colgram koliti-models-v3 GitHub Release (ZERO huggingface.co / < 2 GiB / clean UI branding)
        final long GH_ASSET_LIMIT = 2_147_483_648L;
        for (ColgramAiModels.ModelSpec spec : specs) {
            String mUrl = spec.modelDownloadUrl();
            String vUrl = spec.mmprojDownloadUrl();
            check(mUrl.startsWith("https://github.com/Kleisym/Colgram/releases/download/koliti-models-v3/") && mUrl.endsWith(".gguf"),
                    "Model URL must point to Kleisym/Colgram koliti-models-v3: " + mUrl);
            check(!mUrl.contains("huggingface.co") && !vUrl.contains("huggingface.co"),
                    "Dead HuggingFace URL forbidden in catalog: " + mUrl + " / " + vUrl);
            check(spec.approxModelBytes > 0L && spec.approxModelBytes < GH_ASSET_LIMIT,
                    "Model size must be under GitHub 2 GiB asset limit: " + spec.id);
            if (spec.supportsVision()) {
                check(vUrl.startsWith("https://github.com/Kleisym/Colgram/releases/download/koliti-models-v3/") && vUrl.endsWith(".gguf"),
                        "Vision URL must point to Kleisym/Colgram koliti-models-v3: " + vUrl);
                check(spec.approxMmprojBytes > 0L && spec.approxMmprojBytes < GH_ASSET_LIMIT,
                        "Vision size must be under GitHub 2 GiB asset limit: " + spec.id);
            }
            String uiText = (spec.title + " " + spec.subtitle).toLowerCase(java.util.Locale.ROOT);
            check(!uiText.contains("qwen") && !uiText.contains("unsloth") && !uiText.contains("huggingface") && !uiText.contains("kleidiai"),
                    "Raw third-party model/vendor name leaked into UI label: " + uiText);
        }

        // Verify Turbo & Pro tiers resolve cleanly while legacy IDs still migrate to Koliti Core v3.
        check(ColgramAiModels.findById(ColgramAiModels.KOLITI_TURBO_MODEL_ID) == ColgramAiModels.KOLITI_TURBO, "Turbo id lookup failed");
        check(ColgramAiModels.findById(ColgramAiModels.KOLITI_PRO_MODEL_ID) == ColgramAiModels.KOLITI_PRO, "Pro id lookup failed");
        check(ColgramAiModels.findById("deepseek-v4-pro-qwen35-4b-iq3m") == koliti, "Legacy id migration failed");
        check(ColgramAiModels.findById("hauhaucs-qwen35-4b-aggressive") == koliti, "Legacy id migration failed");
        check(ColgramAiModels.findById("koliti-core-4b") == koliti, "Legacy koliti-core-4b migration failed");
        check(ColgramAiModels.recommendForDeviceRamMb(4096L) == koliti, "Expected Koliti Core for 4GB+ RAM");
        check(ColgramAiModels.recommendForDeviceRamMb(2048L) == ColgramAiModels.KOLITI_TURBO, "Expected Koliti Turbo for 2GB RAM");

        // Verify cross-host GitHub Release 302 redirect resolution (github.com -> release-assets.githubusercontent.com)
        try {
            String crossHost = ColgramAiModelManager.resolveRedirectTarget(
                    koliti.modelDownloadUrl(),
                    "https://release-assets.githubusercontent.com/github-production-release-asset/1234/koliti-core-v3.gguf?sig=abc");
            check(crossHost.startsWith("https://release-assets.githubusercontent.com/"), "Cross-host redirect failed: " + crossHost);
            String rootRel = ColgramAiModelManager.resolveRedirectTarget(
                    koliti.modelDownloadUrl(),
                    "/Kleisym/Colgram/releases/download/koliti-models-v3/koliti-core-v3.gguf");
            check("https://github.com/Kleisym/Colgram/releases/download/koliti-models-v3/koliti-core-v3.gguf".equals(rootRel),
                    "Root-relative redirect failed: " + rootRel);
            String protoRel = ColgramAiModelManager.resolveRedirectTarget(
                    koliti.modelDownloadUrl(),
                    "//objects.githubusercontent.com/assets/koliti-core-v3.gguf");
            check("https://objects.githubusercontent.com/assets/koliti-core-v3.gguf".equals(protoRel),
                    "Protocol-relative redirect failed: " + protoRel);
            boolean rejectedFile = false;
            try {
                ColgramAiModelManager.resolveRedirectTarget(koliti.modelDownloadUrl(), "file:///etc/passwd");
            } catch (java.io.IOException expected) {
                rejectedFile = true;
            }
            check(rejectedFile, "Non-HTTP(S) redirect target must be rejected");
        } catch (java.io.IOException e) {
            throw new AssertionError("Redirect target resolution failed: " + e.getMessage(), e);
        }

        check(ColgramAiModels.recommendContextForTotalRamMb(8192L) == 2048, "Default fast text context must be 2048");
        check(ColgramAiModels.recommendContextForVision(8192L) == 3072, "Vision context must expand cleanly to 3072");
        check(ColgramAiModels.recommendContextForFreeRamMb(1100L) == 3072, "Free RAM <4GB -> 3072 ctx");
        check(ColgramAiModels.recommendContextForFreeRamMb(4200L) == 6144, "Free RAM ~4.2GB -> 6144 ctx");
        check(ColgramAiModels.recommendContextForFreeRamMb(6200L) == 8192, "Free RAM ~6.2GB -> 8192 ctx");
        check(ColgramAiModels.recommendContextForFreeRamMb(8500L) == 16384, "Free RAM >=8GB -> 16384 ctx");
        check(ColgramAiModels.recommendThreadsForCores(4) == 3, "4 cores -> 3 threads thermal low-load cap");

        // RU -> EN GIF/Sticker query expansion
        check("cat".equals(ColgramGifSearch.expandSearchQuery("кот")), "RU->EN gif expansion failed");
        check("fire".equals(ColgramGifSearch.expandSearchQuery("огонь")), "RU->EN fire expansion failed");
        check("funny meme".equals(ColgramGifSearch.expandSearchQuery("прикол")), "RU->EN прикол expansion failed");

        // Channel question must stay strictly scoped to active channel (zero cross-chat contamination!)
        ColgramAiContextEngine.IntentPlan pChan = ColgramAiContextEngine.planAutonomousIntent(
                "колити расскажи о чем этот тг канал", ColgramAiContextEngine.AssistantMode.AUTO, false, true, true);
        check(!pChan.shouldSearchWeb && !pChan.shouldIncludeCrossChat && pChan.shouldUseActiveChatContext,
                "Channel question must use active chat context without web or cross-chat contamination");

        // Casual remark must NOT drag in unrelated Telegram chat history
        ColgramAiContextEngine.IntentPlan pCasual = ColgramAiContextEngine.planAutonomousIntent(
                "окей расскажи анекдот", ColgramAiContextEngine.AssistantMode.AUTO, false, true, true);
        check(!pCasual.shouldUseActiveChatContext && !pCasual.shouldIncludeCrossChat,
                "Casual question must stay clean without dumping open Telegram chat into prompt");

        // Plugin creation intent + code extraction
        ColgramAiContextEngine.IntentPlan pPlug = ColgramAiContextEngine.planAutonomousIntent(
                "создай плагин с командой .dice для броска кубика", ColgramAiContextEngine.AssistantMode.AUTO, false, true, true);
        check(pPlug.resolvedMode == ColgramAiContextEngine.AssistantMode.PLUGIN, "Plugin creation must route to PLUGIN mode");
        String samplePluginReply = "Вот плагин:\n```python\n__name__ = \"Dice Roller\"\n__command__ = \"dice\"\ndef on_command(cmd, args):\n    return \"🎲 6\"\n```\nГотово!";
        ColgramAiAgentTools.InstalledPluginResult pr = ColgramAiAgentTools.extractAndInstallPlugin(samplePluginReply, false);
        check(pr.installed && "koliti_dice.py".equals(pr.fileName) && "dice".equals(pr.command),
                "Plugin extractor failed: " + pr.fileName);

        // Multi-chat history persistence, per-dialog context isolation & session switching
        try {
            java.io.File tmpDir = java.nio.file.Files.createTempDirectory("koliti_chat_test").toFile();
            ColgramAiChatStore.resetForTest();
            ColgramAiChatStore.init(tmpDir);
            ColgramAiChatStore.ChatSession sChanA = ColgramAiChatStore.getOrCreateSessionForDialog(-100111L, "Канал А");
            ColgramAiChatStore.appendMessage(sChanA.id, "user", "Привет из Канала А", "");
            ColgramAiChatStore.appendMessage(sChanA.id, "assistant", "Ответ по Каналу А", "Koliti");
            ColgramAiChatStore.ChatSession sChanB = ColgramAiChatStore.getOrCreateSessionForDialog(-100222L, "Канал Б");
            ColgramAiChatStore.appendMessage(sChanB.id, "user", "Вопрос по Каналу Б", "");
            check(!sChanA.id.equals(sChanB.id) && sChanB.messages.size() == 1,
                    "Dialog sessions must be strictly isolated");
            ColgramAiChatStore.ChatSession sFree = ColgramAiChatStore.createNewSession("Новый чат", 0L, "");
            check(sFree.boundDialogId == 0L && sFree.messages.isEmpty(), "New free session must have empty isolated history");
            check(ColgramAiChatStore.listSessions().size() == 3, "Expected 3 saved Koliti sessions");
            ColgramAiChatStore.resetForTest();
            ColgramAiChatStore.init(tmpDir);
            check(ColgramAiChatStore.listSessions().size() == 3, "Koliti sessions did not persist to disk");
            ColgramAiChatStore.ChatSession reloadedA = ColgramAiChatStore.getOrCreateSessionForDialog(-100111L, "Канал А");
            check(reloadedA.messages.size() == 2 && reloadedA.boundDialogId == -100111L, "Bound dialog session did not reload properly");
            ColgramAiChatStore.resetForTest();
        } catch (Exception e) {
            throw new AssertionError("ColgramAiChatStore test failed: " + e.getMessage(), e);
        }

        // Casual greetings must NOT waste time hitting noisy web search
        ColgramAiContextEngine.IntentPlan pGreet = ColgramAiContextEngine.planAutonomousIntent(
                "привет как дела", ColgramAiContextEngine.AssistantMode.AUTO, false, false, true);
        check(!pGreet.shouldSearchWeb, "Greeting must stay purely local without noisy web search");

        // Offline RU+EN emoji dictionary & keyless Tenor GIF parser
        List<ColgramEmojiDictionary.Entry> ruCats = ColgramEmojiDictionary.search("кот", 10);
        check(!ruCats.isEmpty(), "RU emoji search for кот returned empty");
        List<ColgramEmojiDictionary.Entry> enFire = ColgramEmojiDictionary.search("fire", 10);
        check(!enFire.isEmpty() && "🔥".equals(enFire.get(0).emoji), "EN emoji search for fire failed");
        String fakeTenor = "<img src=\"https://media.tenor.com/abcdef12345AAAAM/funny-cat.gif\">"
                + "<img src=\"https://media.tenor.com/zyxwvu98765AAAAC/cool-dog.gif\">";
        List<ColgramGifSearch.GifItem> gifs = ColgramGifSearch.parseTenorHtml(fakeTenor, "cat", 10);
        check(gifs.size() == 2 && gifs.get(0).id.startsWith("colgram_gif_"), "Tenor HTML GIF parser failed");
        String fakeGiphy = "\"https://media4.giphy.com/media/v1.abc/774uK11mxE3W0cjE8L/giphy.gif\" "
                + "\"https://media4.giphy.com/media/v1.abc/774uK11mxE3W0cjE8L/giphy.webp\" "
                + "\"https://media.giphy.com/media/XJtM2nNFCzT3etvzOB/200.gif\"";
        List<ColgramGifSearch.GifItem> gpGifs = ColgramGifSearch.parseGiphyHtml(fakeGiphy, "cat", 10);
        check(gpGifs.size() == 2 && gpGifs.get(0).id.startsWith("colgram_gif_gp_"), "Giphy HTML GIF parser failed");
    }

    private static void testBm25BigContextRetrieval() {
        List<ColgramAiContextEngine.ChatSnippet> pool = new ArrayList<>();
        for (int i = 0; i < 180; i++) {
            String text = (i == 22)
                    ? "Важно: адрес тестового стенда colgram-node-77, порт 8443, токен обновлен"
                    : "Обычное сообщение номер " + i + " про погоду и задачи дня";
            pool.add(new ColgramAiContextEngine.ChatSnippet(1001L, "Dev Team", "Алекс", 1700000000 + i * 60, text, i % 3 == 0, ""));
        }

        List<ColgramAiContextEngine.ChatSnippet> selected = ColgramAiContextEngine.selectRelevantContext(
                pool, "какой порт у стенда colgram-node-77", 18, 3200);
        boolean foundNeedle = false;
        boolean foundLatest = false;
        for (ColgramAiContextEngine.ChatSnippet s : selected) {
            if (s.text.contains("colgram-node-77") && s.text.contains("8443")) foundNeedle = true;
            if (s.text.contains("номер 179")) foundLatest = true;
        }
        check(foundNeedle, "BM25 failed to retrieve distant needle message #22");
        check(foundLatest, "Recency tail failed to retain latest message #179");
        check(selected.size() <= 18, "Context message limit exceeded");
    }

    private static void testAntiHallucinationPromptAndThinkStrip() {
        String sys = ColgramAiContextEngine.buildSystemPrompt(ColgramAiContextEngine.AssistantMode.DRAFT);
        check(sys.contains("ТОЛЬКО") && sys.contains("готовый текст"), "Draft mode instruction missing");
        String chatSys = ColgramAiContextEngine.buildSystemPrompt(ColgramAiContextEngine.AssistantMode.CHAT);
        check(!chatSys.contains("Фактов о чате") && !chatSys.contains("Если данных о чате"),
                "System prompt must not contain robotic refusal trigger");
        // KV cache prefix reuse invariant: system prompt MUST stay byte-identical across CHAT/SEARCH/DRAFT/VISION
        check(chatSys.equals(ColgramAiContextEngine.buildSystemPrompt(ColgramAiContextEngine.AssistantMode.SEARCH, false, true)),
                "System prompt must stay static across CHAT and SEARCH for KV cache reuse");
        check(chatSys.equals(ColgramAiContextEngine.buildSystemPrompt(ColgramAiContextEngine.AssistantMode.DRAFT, true, false)),
                "System prompt must stay static across CHAT and DRAFT for KV cache reuse");
        check(chatSys.equals(ColgramAiContextEngine.buildSystemPrompt(ColgramAiContextEngine.AssistantMode.VISION, true, false)),
                "System prompt must stay static across CHAT and VISION for KV cache reuse");

        String raw = "<think>размышляю шаги 1 2 3</think>\nПривет, завтра созвон в 15:00 подойдет!";
        String cleaned = ColgramAiContextEngine.stripThinkingTags(raw, false);
        check("Привет, завтра созвон в 15:00 подойдет!".equals(cleaned), "Failed to strip <think>: " + cleaned);

        String kept = ColgramAiContextEngine.stripThinkingTags(raw, true);
        check(kept.contains("Мысли:") && kept.contains("15:00"), "Failed to format visible thoughts");

        // Regression: Qwen3.5 Jinja template opens `<think>\n` inside prompt, so generated stream lacks `<think>`
        String implicitStreaming = "Thinking Process:\n1. Solve x^2 - 5x + 6 = 0";
        check("".equals(ColgramAiContextEngine.stripThinkingTags(implicitStreaming, false, true)),
                "Implicit prompt-opened thinking leaked while streaming!");
        String implicitCompleted = "Thinking Process:\n1. D = 25 - 24 = 1\n</think>\n\nКорни: x = 2 и x = 3.";
        check("Корни: x = 2 и x = 3.".equals(ColgramAiContextEngine.stripThinkingTags(implicitCompleted, false, true)),
                "Implicit prompt-opened thinking failed to extract final answer");
        check("Корни: x = 2 и x = 3.".equals(ColgramAiContextEngine.stripThinkingTags(implicitCompleted, false)),
                "2-arg stripThinkingTags must also handle orphan </think>");
        String implicitKept = ColgramAiContextEngine.stripThinkingTags(implicitCompleted, true, true);
        check(implicitKept.startsWith("Мысли:\n") && implicitKept.contains("---") && implicitKept.endsWith("Корни: x = 2 и x = 3."),
                "Implicit thinking keepThinking format failed: " + implicitKept);
        String leakedStop = ColgramAiContextEngine.stripThinkingTags("Привет!<|im_end|><|endoftext|>", false, false);
        check("Привет!".equals(leakedStop), "Special stop tokens must be stripped cleanly: " + leakedStop);
        check(chatSys.length() <= 350, "Base system prompt must stay ultra-compact for fast mobile prefill: " + chatSys.length());
    }

    private static void testWebSearchParsers() {
        String cleaned = ColgramAiWebSearch.cleanSearchQuery("найди в интернете курс доллара сегодня");
        check("курс доллара сегодня".equals(cleaned), "Query cleaner failed: " + cleaned);

        String unwrapped = ColgramAiWebSearch.unwrapDuckDuckGoHref("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fnews&rut=123");
        check("https://example.org/news".equals(unwrapped), "DDG redirect unwrap failed: " + unwrapped);

        String ddgHtml = "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fcolgram.dev%2Fdocs\">Colgram Docs</a>"
                + "<a class=\"result__snippet\">Fast AI messenger architecture &amp; proxy.</a>";
        List<ColgramAiWebSearch.SearchHit> hits = ColgramAiWebSearch.parseDuckDuckGoHtml(ddgHtml, 5);
        check(hits.size() == 1, "Expected 1 parsed DDG hit");
        check("https://colgram.dev/docs".equals(hits.get(0).url), "DDG URL mismatch: " + hits.get(0).url);
        check(hits.get(0).snippet.contains("& proxy"), "DDG snippet decode failed");

        String wikiJson = "{\"query\":{\"search\":[{\"title\":\"Qwen\",\"snippet\":\"Family of <span class=\\\"searchmatch\\\">multimodal</span> models\"}]}}";
        List<ColgramAiWebSearch.SearchHit> wHits = ColgramAiWebSearch.parseWikipediaJson(wikiJson, "en.wikipedia.org", 3);
        check(wHits.size() == 1 && wHits.get(0).url.contains("en.wikipedia.org/wiki/Qwen"), "Wiki parser failed");

        String bingRss = "<rss><channel><item><title>Qwen3.5 Release</title><link>https://qwen.ai/news</link><description>Hybrid DeltaNet architecture</description></item></channel></rss>";
        List<ColgramAiWebSearch.SearchHit> bHits = ColgramAiWebSearch.parseBingRssXml(bingRss, 3);
        check(bHits.size() == 1 && "https://qwen.ai/news".equals(bHits.get(0).url), "Bing RSS parser failed");

        String wikiGen = "{\"query\":{\"pages\":{\"1\":{\"title\":\"Нейросеть\",\"extract\":\"Краткое описание модели.\",\"fullurl\":\"https://ru.wikipedia.org/wiki/Нейросеть\"}}}}";
        List<ColgramAiWebSearch.SearchHit> wgHits = ColgramAiWebSearch.parseWikipediaGeneratorJson(wikiGen, "ru.wikipedia.org", 3);
        check(wgHits.size() == 1 && wgHits.get(0).pageExcerpt.contains("Краткое описание"), "Wiki generator parser failed");

        String page = "<html><head><script>alert(1)</script></head><body><nav>Home</nav>"
                + "<p>Qwen3.5 uses a hybrid Gated DeltaNet attention architecture designed for fast long-context efficiency.</p>"
                + "</body></html>";
        String excerpt = ColgramAiWebSearch.extractCleanPageText(page, 500);
        check(excerpt.contains("Gated DeltaNet") && !excerpt.contains("alert(1)"), "HTML clean extractor failed: " + excerpt);
    }

    private static void testNativeBridgeHostSafety() {
        String info = ColgramNativeLlama.getSystemInfoSafe();
        check(info != null && !info.isEmpty(), "Safe system info must never throw or return null on host JVM");
    }

    private static void testAutonomousPlannerAndSynthesizer() {
        ColgramAiContextEngine.IntentPlan pWeb = ColgramAiContextEngine.planAutonomousIntent(
                "какой сегодня курс биткоина?",
                ColgramAiContextEngine.AssistantMode.AUTO, false, false, true);
        check(pWeb.shouldSearchWeb, "Crypto question should auto-enable web search");
        check(!pWeb.shouldDraftIntoInput, "Question should not auto-draft");

        ColgramAiContextEngine.IntentPlan pDraft = ColgramAiContextEngine.planAutonomousIntent(
                "напиши ему вежливый ответ что я согласен",
                ColgramAiContextEngine.AssistantMode.AUTO, false, true, true);
        check(pDraft.shouldDraftIntoInput && pDraft.resolvedMode == ColgramAiContextEngine.AssistantMode.DRAFT,
                "Draft instruction should auto-route to DRAFT");

        ColgramAiContextEngine.IntentPlan pAction = ColgramAiContextEngine.planAutonomousIntent(
                "открой настройки прокси и обхода",
                ColgramAiContextEngine.AssistantMode.AUTO, false, true, true);
        check("OPEN_PROXY".equals(pAction.inAppAction), "Action command not recognized: " + pAction.inAppAction);

        ColgramAiContextEngine.IntentPlan pVision = ColgramAiContextEngine.planAutonomousIntent(
                "что изображено на фото?",
                ColgramAiContextEngine.AssistantMode.AUTO, false, true, true);
        check(pVision.resolvedMode == ColgramAiContextEngine.AssistantMode.VISION && pVision.shouldAutoGrabMedia,
                "Vision prompt in chat should auto-grab chat media");

        check("42".equals(ColgramAiWebSearch.tryEvalMath("посчитай (4 + 3) * 6")), "Math eval failed");

        List<ColgramAiWebSearch.SearchHit> cg = ColgramAiWebSearch.parseCoinGeckoPriceJson(
                "{\"bitcoin\":{\"usd\":92500,\"rub\":8900000}}", "курс btc");
        check(cg.size() == 1 && cg.get(0).pageExcerpt.contains("92500"), "CoinGecko parser failed");

        List<ColgramAiWebSearch.SearchHit> fx = ColgramAiWebSearch.parseExchangeRateJson(
                "{\"time_last_update_utc\":\"Thu, 19 Nov 2026 00:02:31 +0000\",\"rates\":{\"RUB\":82.344611,\"EUR\":0.92}}",
                "эм 4 бакса по курсу в рубли переведи");
        check(fx.size() == 1
                        && fx.get(0).pageExcerpt.contains("329.38 RUB")
                        && fx.get(0).pageExcerpt.contains("82.34 RUB")
                        && fx.get(0).pageExcerpt.contains("19 Nov 2026")
                        && !fx.get(0).pageExcerpt.contains("82.344611"),
                "FX exact conversion + date failed: " + (fx.isEmpty() ? "<empty>" : fx.get(0).pageExcerpt));

        ColgramAiContextEngine.IntentPlan pDraftWeb = ColgramAiContextEngine.planAutonomousIntent(
                "впиши в поле ввода скок будет 4 usdt в рублях",
                ColgramAiContextEngine.AssistantMode.AUTO, false, true, true);
        check(pDraftWeb.shouldDraftIntoInput && pDraftWeb.shouldSearchWeb,
                "Combined draft + FX web intent failed");

        String cleanedDraft = ColgramAiContextEngine.extractCleanDraftText(
                "Колли, вот что нужно написать в чате:\n\n**Привет**\n\nЕсли нужно — могу показать курс валют.");
        check("Привет".equals(cleanedDraft), "Draft extractor failed: " + cleanedDraft);

        ColgramAiAgentTools.DirectCommandResult crossDraft = ColgramAiAgentTools.parseCrossChatDraftCommand(
                "окей в чате избранное напиши 'привет'");
        check(crossDraft.handled && "DRAFT_TO_CHAT:избранное|привет".equalsIgnoreCase(crossDraft.uiAction),
                "Cross-chat draft command failed: " + crossDraft.uiAction);

        // Typo-tolerant cross-chat draft & direct send commands + multi-turn follow-up targeting
        ColgramAiAgentTools.DirectCommandResult typoDraft = ColgramAiAgentTools.parseCrossChatDraftCommand(
                "напиши в избраном \"привет как дела\"");
        check(typoDraft.handled && "DRAFT_TO_CHAT:избраном|привет как дела".equalsIgnoreCase(typoDraft.uiAction),
                "Typo cross-chat draft failed: " + typoDraft.uiAction);

        ColgramAiAgentTools.DirectCommandResult directSend = ColgramAiAgentTools.parseCrossChatDraftCommand(
                "отправь в избранное \"тест\"");
        check(directSend.handled && "SEND_TO_CHAT:избранное|тест".equalsIgnoreCase(directSend.uiAction),
                "Cross-chat send command failed: " + directSend.uiAction);

        ColgramAiAgentTools.DirectCommandResult localInputNotIntercepted = ColgramAiAgentTools.parseCrossChatDraftCommand(
                "впиши в поле ввода скок будет 4 usdt");
        check(!localInputNotIntercepted.handled,
                "Local input-field draft must NOT be hijacked as cross-chat: " + localInputNotIntercepted.uiAction);

        List<ColgramAiContextEngine.ConversationTurn> followHistory = new ArrayList<>();
        followHistory.add(new ColgramAiContextEngine.ConversationTurn("напиши приветствие", "Привет, как дела."));
        ColgramAiAgentTools.DirectCommandResult followTarget = ColgramAiAgentTools.parseFollowUpChatTarget(
                "в чате избранное", followHistory);
        check(followTarget.handled && "DRAFT_TO_CHAT:избранное|Привет, как дела.".equals(followTarget.uiAction),
                "Follow-up chat target failed: " + followTarget.uiAction);

        // Autonomous [[TOOL:...]] extraction & tag stripping
        List<String> tools = ColgramAiAgentTools.extractToolActions("Готово! [[TOOL:OPEN_WALLPAPERS]] [[TOOL:SET_SETTING|ghost=1]]");
        check(tools.size() == 2 && "OPEN_WALLPAPERS".equals(tools.get(0)) && "SET_SETTING:ghost=1".equals(tools.get(1)),
                "Tool tag extractor failed: " + tools);
        String strippedTools = ColgramAiAgentTools.stripToolTags("Готово! [[TOOL:OPEN_WALLPAPERS]]");
        check("Готово!".equals(strippedTools), "Tool tag strip failed: " + strippedTools);

        // Pure-Java LaTeX formula rendering (discriminant, quadratic roots, Greek, powers, fractions, arrows)
        String disc = ColgramAiContextEngine.renderLatexFormula("D = b^2 - 4ac");
        check("D = b² − 4ac".equals(disc), "Discriminant LaTeX render failed: " + disc);
        String quad = ColgramAiContextEngine.renderLatexFormula("x_{1,2} = \\frac{-b \\pm \\sqrt{D}}{2a}");
        check("x₁,₂ = (−b ± √D) / (2a)".equals(quad), "Quadratic formula LaTeX render failed: " + quad);
        String trig = ColgramAiContextEngine.renderLatexFormula("\\sin^2 \\alpha + \\cos^2 \\alpha = 1");
        check("sin² α + cos² α = 1".equals(trig), "Trig LaTeX render failed: " + trig);
        String imp = ColgramAiContextEngine.renderLatexFormula("x^2 - 5x + 6 = 0 \\implies x \\in \\mathbb{R}");
        check("x² − 5x + 6 = 0 ⇒ x ∈ ℝ".equals(imp), "Implies/mathbb LaTeX render failed: " + imp);
        String aligned = ColgramAiContextEngine.renderLatexFormula("\\begin{aligned} x &= 2 \\\\ x &= 3 \\end{aligned}");
        check(!aligned.contains("begin") && !aligned.contains("&") && aligned.contains("x = 2") && aligned.contains("x = 3"),
                "Aligned LaTeX render failed: " + aligned);
        String cases = ColgramAiContextEngine.renderLatexFormula("\\begin{cases} x = 2 \\\\ x = 3 \\end{cases}");
        check(!cases.contains("cases") && cases.contains("x = 2") && cases.contains("x = 3"),
                "Cases LaTeX render failed: " + cases);

        // Agent tools must NOT hijack normal math/creative prompts as fake chat navigation/drafting!
        check(!ColgramAiAgentTools.parseOpenChatCommand("найди корни уравнения x^2 - 5x + 6 = 0").handled,
                "Math query starting with 'найди' was hijacked as OPEN_CHAT!");
        check(ColgramAiAgentTools.parseOpenChatCommand("открой чат Kleisym").handled,
                "Explicit open-chat command should still work!");
        check(!ColgramAiAgentTools.parseCrossChatDraftCommand("напиши в стиле стиха про кота").handled,
                "Creative 'напиши в стиле' was hijacked as cross-chat draft!");

        List<ColgramAiWebSearch.SearchHit> longHits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ColgramAiWebSearch.SearchHit h = new ColgramAiWebSearch.SearchHit("Заголовок " + i, "https://example.com/" + i,
                    "Очень длинный сниппет ".repeat(25), "Bing Web");
            h.pageExcerpt = "Длинный текст страницы ".repeat(40);
            longHits.add(h);
        }
        String promptHits = ColgramAiWebSearch.formatHitsForPrompt(longHits);
        check(promptHits.length() <= 640, "formatHitsForPrompt exceeded compact cap: " + promptHits.length());

        List<ColgramAiContextEngine.ChatSnippet> chat = new ArrayList<>();
        chat.add(new ColgramAiContextEngine.ChatSnippet(1L, "Алекс", "Алекс", 1700000000, "Привет, успеем созвониться в 18:00?", false, ""));
        String ans = ColgramAiContextEngine.synthesizeSmartAnswer(
                pWeb, "какой сегодня курс биткоина?", "Алекс", chat, null, cg, false);
        check(!ans.contains("Hybrid Assist") && !ans.contains("нажмите") && ans.contains("92500"),
                "Smart synthesizer emitted nag or missed live rate: " + ans);

        // Verify SEO/YouTube junk is stripped and never dumped raw to the user.
        List<ColgramAiWebSearch.SearchHit> noisy = new ArrayList<>();
        noisy.add(new ColgramAiWebSearch.SearchHit("YouTube Track", "https://www.youtube.com/watch?v=123",
                "Provided to YouTube by Music Corp. Auto-generated by YouTube.", "Bing Web"));
        noisy.add(new ColgramAiWebSearch.SearchHit("Портал", "https://news.example.ru/",
                "Новости политики, экономики, общества. Читайте свежую и оперативную информацию на сайте.", "Bing Web"));
        String cleanNoisy = ColgramAiContextEngine.synthesizeSmartAnswer(
                pWeb, "главные новости сегодня", "Алекс", chat, null, noisy, false);
        check(!cleanNoisy.contains("Provided to YouTube") && !cleanNoisy.contains("оперативную информацию"),
                "Raw YouTube/SEO spam leaked to user: " + cleanNoisy);
        check(!cleanNoisy.contains("Чат «Алекс»"),
                "Web query must never fall back to dumping active chat log: " + cleanNoisy);
        List<ColgramAiWebSearch.SearchHit> newsHits = ColgramAiWebSearch.parseNewsRssXml(
                "<rss><channel><item><title>Вышел новый релиз локальных нейросетей Qwen</title><link>https://habr.com/ru/news/1/</link><description>Подробности релиза</description></item></channel></rss>",
                "Habr", 3);
        check(newsHits.size() == 1 && "Live News".equals(newsHits.get(0).source), "Live News RSS parser failed");
        String newsBrief = ColgramAiContextEngine.synthesizeSmartAnswer(
                pWeb, "главные IT новости сегодня", "Алекс", chat, null, newsHits, false);
        check(newsBrief.contains("Главные новости сейчас:") && newsBrief.contains("Вышел новый релиз"),
                "Live News brief failed: " + newsBrief);
        check(ColgramAiContextEngine.buildSystemPrompt(ColgramAiContextEngine.AssistantMode.CHAT).contains("Koliti"),
                "System prompt must identify assistant as Koliti");
        check(!ColgramAiContextEngine.buildSystemPrompt(ColgramAiContextEngine.AssistantMode.CHAT).contains("Колли"),
                "System prompt must not contain pink-elephant 'Колли' trigger");

        // Regression: strict word-boundary finance detection — never hijack normal Russian/English prompts with USD/RUB rates!
        String[] nonFinanceQueries = {
                "ку",
                "че ?",
                "привет как дела",
                "я не в курсе, объясни сцену из фильма",
                "посоветуй курс программирования на python",
                "что такое прямой эфир?",
                "how does this method work together with neural networks?",
                "what is the button skeleton tonight?",
                "реши $x^2 - 5x + 6 = 0$"
        };
        for (String nfq : nonFinanceQueries) {
            check(!ColgramAiWebSearch.looksLikeFinanceQuery(nfq),
                    "False-positive finance trigger on: " + nfq);
            ColgramAiContextEngine.IntentPlan plan = ColgramAiContextEngine.planAutonomousIntent(
                    nfq, ColgramAiContextEngine.AssistantMode.AUTO, false, true, true);
            check(!plan.shouldSearchWeb, "Non-finance query unexpectedly triggered web search: " + nfq);
        }
        // Genuine finance queries MUST still match!
        check(ColgramAiWebSearch.looksLikeFinanceQuery("какой курс доллара сегодня"), "Real USD query missed");
        check(ColgramAiWebSearch.looksLikeFinanceQuery("сколько будет 20 usdt в рублях"), "Real USDT query missed");
        check(ColgramAiWebSearch.looksLikeFinanceQuery("курс btc"), "Real BTC query missed");
        check(ColgramAiWebSearch.looksLikeFinanceQuery("переведи $100 в рубли"), "Real $100 query missed");

        // Turbo fast-by-default: normal chat, questions, explanations & code stream immediately WITHOUT hidden <think> burn;
        // deep thinking activates ONLY on explicit deep-reasoning / proof requests!
        check(!ColgramAiContextEngine.shouldUseAdaptiveThinking("ку", null, false), "Greeting should stay instant");
        check(!ColgramAiContextEngine.shouldUseAdaptiveThinking("че ?", null, false), "Short casual check should stay instant");
        check(!ColgramAiContextEngine.shouldUseAdaptiveThinking("объясни простыми словами как работает нейронная сеть", null, false),
                "Normal explanation should stream immediately without hidden <think> stall");
        check(!ColgramAiContextEngine.shouldUseAdaptiveThinking("напиши на python быструю сортировку", null, false),
                "Standard coding prompt should stream immediately without hidden <think> stall");
        check(ColgramAiContextEngine.shouldUseAdaptiveThinking("докажи теорему Пифагора", null, false),
                "Explicit proof request should enable deep thinking");
        check(ColgramAiContextEngine.shouldUseAdaptiveThinking("подумай и пошагово реши уравнение x^2 - 5x + 6 = 0", null, false),
                "Explicit step-by-step deep reasoning request should enable thinking");

        // Poisoned currency history cleanup: repeating "82.08 RUB (на 20.11.2026)." must NEVER bleed into "ку" or normal questions!
        List<ColgramAiContextEngine.ConversationTurn> poisoned = new ArrayList<>();
        poisoned.add(new ColgramAiContextEngine.ConversationTurn("ку какой курс актуальный у usdt в рублях", "82.08 RUB (на 20.11.2026)."));
        poisoned.add(new ColgramAiContextEngine.ConversationTurn("ку", "82.08 RUB (на 20.11.2026)."));
        poisoned.add(new ColgramAiContextEngine.ConversationTurn("че ?", "82.08 RUB (на 20.11.2026)."));
        check(ColgramAiContextEngine.buildCleanHistoryPairs(poisoned, "ку") == null,
                "Greeting after FX turns must wipe unrelated FX history");
        check(ColgramAiContextEngine.buildCleanHistoryPairs(poisoned, "объясни как работает квантовый компьютер") == null,
                "Non-finance question must not inherit pure numeric FX answers");
        check(ColgramAiContextEngine.isParrotRepeatOfHistory("82.08 RUB (на 20.11.2026).", "че ?", poisoned),
                "Parrot detector must catch verbatim repetition of previous turn");

        // Regression: natural Russian compound command "перейти в чат избранное и там написать слово привет"
        // MUST strip meta-noun "слово" and "и там", NEVER paste literal `слово "привет"` or hijack as bare OPEN_SAVED!
        ColgramAiAgentTools.DirectCommandResult compound1 = ColgramAiAgentTools.parseCrossChatDraftCommand(
                "перейди в чат избранное и там напиши слово привет");
        check(compound1.handled && "DRAFT_TO_CHAT:избранное|привет".equalsIgnoreCase(compound1.uiAction),
                "Compound cross-chat command failed: " + compound1.uiAction);

        ColgramAiAgentTools.DirectCommandResult compound2 = ColgramAiAgentTools.parseCrossChatDraftCommand(
                "перейти в чат избранное и там написать слово \"привет\"");
        check(compound2.handled && "DRAFT_TO_CHAT:избранное|привет".equalsIgnoreCase(compound2.uiAction),
                "Quoted compound cross-chat command failed: " + compound2.uiAction);

        ColgramAiAgentTools.DirectCommandResult generative = ColgramAiAgentTools.parseCrossChatDraftCommand(
                "напиши в избранное шутку про программиста");
        check(!generative.handled,
                "Generative cross-chat request must be generated by LLM, not pasted literally: " + generative.uiAction);

        List<ColgramAiContextEngine.ConversationTurn> mathHist = new ArrayList<>();
        mathHist.add(new ColgramAiContextEngine.ConversationTurn("5+5=?", "5+5=10"));
        check(!ColgramAiAgentTools.parseFollowUpChatTarget("напиши в избранное короткую шутку про кота", mathHist).handled,
                "Generative cross-chat prompt must NOT match bare follow-up target and paste stale 5+5=10!");
        String genAct = ColgramAiAgentTools.resolveGenerativeCrossChatAction(
                "напиши в избранное короткую шутку про кота",
                "В избранное:\n\nКот зашёл в Telegram и сразу закрепил миску в Избранном.");
        check("DRAFT_TO_CHAT:избранное|Кот зашёл в Telegram и сразу закрепил миску в Избранном.".equals(genAct),
                "Generative cross-chat pairing failed: " + genAct);

        ColgramAiContextEngine.IntentPlan compoundPlan = ColgramAiContextEngine.planAutonomousIntent(
                "перейди в чат избранное и там напиши слово привет",
                ColgramAiContextEngine.AssistantMode.AUTO, false, true, true);
        check(compoundPlan.inAppAction.isEmpty() && !compoundPlan.shouldDraftIntoInput,
                "Compound cross-chat prompt must not be hijacked as bare OPEN_SAVED or active-chat draft");

        // Normalized LLM [[TOOL:...]] extraction & streaming tail hiding
        List<String> normTools = ColgramAiAgentTools.extractToolActions(
                "Перехожу в Избранное! [[TOOL:DRAFT_TO_CHAT|Избранное|слово \"привет\"]] [[TOOL:OPEN_SCREEN|TEMPMAIL]] [[TOOL:SET_THEME|DARK]]");
        check(normTools.size() == 3
                        && "DRAFT_TO_CHAT:Избранное|привет".equals(normTools.get(0))
                        && "OPEN_TEMPMAIL".equals(normTools.get(1))
                        && "SET_THEME_DARK".equals(normTools.get(2)),
                "Normalized LLM tool tag extraction failed: " + normTools);
        check("Готово".equals(ColgramAiAgentTools.stripStreamingToolTail("Готово [[TOOL:DRAFT")),
                "Streaming partial tool tail leaked");

        // ReAct Agent Architecture: natural person messaging, Cyrillic->Latin translit, pronoun coreference,
        // query tool normalization, anti-offline-refusal detection & directory prompt injection
        String sampleDir = "• Kleisym (@kleisym) [ЛС] — «Привет»\n• Избранное [Заметки]\n• Steal An Egg | Stocks [Канал]";
        ColgramAiAgentTools.NaturalSendIntent ns1 = ColgramAiAgentTools.parseNaturalMessagingIntent(
                "напиши kleisym и спроси как у него дела", "", sampleDir);
        check(ns1.matched && ns1.sendNow && "Kleisym".equals(ns1.target) && !ns1.pronounFollowUp,
                "Natural Kleisym send failed: matched=" + ns1.matched + " target=" + ns1.target);

        ColgramAiAgentTools.NaturalSendIntent nsTranslit = ColgramAiAgentTools.parseNaturalMessagingIntent(
                "напиши клейсиму и спроси как дела", "", sampleDir);
        check(nsTranslit.matched && nsTranslit.sendNow && "Kleisym".equals(nsTranslit.target),
                "Cyrillic->Latin inflected target match failed: " + nsTranslit.target);

        ColgramAiAgentTools.NaturalSendIntent nsAsk = ColgramAiAgentTools.parseNaturalMessagingIntent(
                "спроси у клейсима когда он освободится", "", sampleDir);
        check(nsAsk.matched && nsAsk.sendNow && "Kleisym".equals(nsAsk.target),
                "Ask-person intent failed: " + nsAsk.target);

        ColgramAiAgentTools.NaturalSendIntent nsPronoun = ColgramAiAgentTools.parseNaturalMessagingIntent(
                "напиши ему ещё раз", "Kleisym", sampleDir);
        check(nsPronoun.matched && nsPronoun.sendNow && nsPronoun.pronounFollowUp && "Kleisym".equals(nsPronoun.target),
                "Pronoun coreference follow-up failed: " + nsPronoun.target);

        ColgramAiAgentTools.NaturalSendIntent nsColloquialPronoun = ColgramAiAgentTools.parseNaturalMessagingIntent(
                "ну и пофиг всеровно напиши ему", "Kleisym", sampleDir);
        check(nsColloquialPronoun.matched && nsColloquialPronoun.sendNow && nsColloquialPronoun.pronounFollowUp
                        && "Kleisym".equals(nsColloquialPronoun.target),
                "Colloquial pronoun follow-up failed: " + nsColloquialPronoun.target);

        ColgramAiAgentTools.NaturalSendIntent nsDirect = ColgramAiAgentTools.parseNaturalMessagingIntent(
                "напиши kleisym привет как дела", "", sampleDir);
        check(nsDirect.matched && nsDirect.sendNow && "Kleisym".equals(nsDirect.target) && "привет как дела".equals(nsDirect.literalPayload),
                "Direct unquoted person send failed: target=" + nsDirect.target + " payload=" + nsDirect.literalPayload);

        // Must NOT hijack normal coding/creative/local-draft queries
        check(!ColgramAiAgentTools.parseNaturalMessagingIntent("напиши код на python и проверь его", "", sampleDir).matched,
                "Coding prompt hijacked as person send!");
        check(!ColgramAiAgentTools.parseNaturalMessagingIntent("напиши мне стих про кота", "", sampleDir).matched,
                "Creative prompt hijacked as person send!");
        check(!ColgramAiAgentTools.parseNaturalMessagingIntent("впиши в поле ввода привет", "Kleisym", sampleDir).matched,
                "Local input draft hijacked as remote send!");

        // Query tools normalization + isQueryToolAction
        List<String> reactTools = ColgramAiAgentTools.extractToolActions(
                "Проверю чат [[TOOL:READ_CHAT|Kleisym|10]] [[TOOL:LIST_CHATS]] [[TOOL:WEB_SEARCH|курс ton]] [[TOOL:GET_SETTINGS]]");
        check(reactTools.size() == 4
                        && "READ_CHAT:Kleisym|10".equals(reactTools.get(0))
                        && "LIST_CHATS".equals(reactTools.get(1))
                        && "WEB_SEARCH:курс ton".equals(reactTools.get(2))
                        && "GET_SETTINGS".equals(reactTools.get(3)),
                "ReAct query tool extraction failed: " + reactTools);
        for (String rt : reactTools) {
            check(ColgramAiAgentTools.isQueryToolAction(rt), "Expected query tool: " + rt);
        }
        check(!ColgramAiAgentTools.isQueryToolAction("SEND_TO_CHAT:Kleisym|Привет"),
                "SEND_TO_CHAT must be action tool, not query tool");

        // Anti-offline-refusal & message extraction
        check(ColgramAiAgentTools.looksLikeOfflineOrCannotSendRefusal("К сожалению, Kleisym сейчас не в сети, поэтому я не могу отправить."),
                "Offline refusal detector missed bogus refusal");
        check(!ColgramAiAgentTools.looksLikeOfflineOrCannotSendRefusal("Привет! Как у тебя дела?"),
                "Clean greeting misclassified as refusal");
        check("Привет! Как дела?".equals(ColgramAiAgentTools.extractNaturalMessageFromReply("Я написал Kleisym: «Привет! Как дела?»")),
                "Quoted message extraction failed");

        String dirPrompt = ColgramAiContextEngine.buildUserPrompt(
                "напиши kleisym", "", "", "", "", sampleDir, 0, false, null);
        check(dirPrompt.contains("Доступные чаты Telegram") && dirPrompt.contains("Kleisym"),
                "Dialog directory was not injected into user prompt");

        // Regression: follow-up reaction "че?" must NOT wipe healthy chat history, and casual turns
        // must NEVER trigger unsolicited [[TOOL:...]] ReAct restarts!
        List<ColgramAiContextEngine.ConversationTurn> normalHist = new ArrayList<>();
        normalHist.add(new ColgramAiContextEngine.ConversationTurn("ку", "Привет! Чем помочь?"));
        String[] followPairs = ColgramAiContextEngine.buildCleanHistoryPairs(normalHist, "че?");
        check(followPairs != null && followPairs.length == 2,
                "Follow-up 'че?' must retain conversation history instead of wiping memory!");
        check(!ColgramAiAgentTools.queryAllowsToolCalls("че?", false, false),
                "Casual 'че?' must NOT allow spurious tool-call restarts");
        check(!ColgramAiAgentTools.queryAllowsToolCalls("ку", false, false),
                "Greeting 'ку' must NOT allow spurious tool-call restarts");
        check(!ColgramAiAgentTools.queryAllowsToolCalls("объясни теорию относительности", false, false),
                "Knowledge question must NOT allow spurious tool-call restarts");
        check(ColgramAiAgentTools.queryAllowsToolCalls("напиши kleisym привет", true, true),
                "Explicit messaging must allow tools");
        check(ColgramAiAgentTools.queryAllowsToolCalls("прочитай последние сообщения в чате Kleisym", false, true),
                "Explicit read-chat request must allow tools");
    }

    private static void testKolitiVoiceLogic() {
        // Song Easter Egg detection (RU + EN) without false positives
        check(ColgramKolitiVoice.isSingSongRequest("колити спой песню"), "RU song trigger failed");
        check(ColgramKolitiVoice.isSingSongRequest("Спой мне песенку пожалуйста!"), "RU polite song trigger failed");
        check(ColgramKolitiVoice.isSingSongRequest("напой что-нибудь"), "RU napoy trigger failed");
        check(ColgramKolitiVoice.isSingSongRequest("sing me a song"), "EN sing trigger failed");
        check(ColgramKolitiVoice.isSingSongRequest("Can you sing a song?"), "EN polite sing trigger failed");
        check(!ColgramKolitiVoice.isSingSongRequest("напиши текст песни про лето"), "Must NOT trigger on lyrics writing request");
        check(!ColgramKolitiVoice.isSingSongRequest("кто спел песню Despacito"), "Must NOT trigger on who-sang trivia");
        check(!ColgramKolitiVoice.isSingSongRequest("write a song about cats"), "Must NOT trigger on EN write song");

        // User language inference from chats + profile + locale
        List<String> ruOwn = new ArrayList<>();
        ruOwn.add("привет как дела что нового");
        List<String> enOther = new ArrayList<>();
        enOther.add("hello");
        check("ru".equals(ColgramKolitiVoice.detectPreferredLanguageCode(ruOwn, enOther, "Alex", "en")),
                "User own RU chat messages must outweigh EN profile/locale");

        List<String> enOwn = new ArrayList<>();
        enOwn.add("hey bro how is it going today");
        check("en".equals(ColgramKolitiVoice.detectPreferredLanguageCode(enOwn, null, "John Doe", "ru")),
                "User own EN chat messages must outweigh RU app locale");

        check("ru".equals(ColgramKolitiVoice.detectPreferredLanguageCode(null, null, "Иван Петров", "en")),
                "Cyrillic profile name should pick RU when chats empty");
        check("en".equals(ColgramKolitiVoice.detectPreferredLanguageCode(null, null, "", "en")),
                "EN locale tiebreaker failed");

        // Russian speech normalization for natural TTS stress & pauses
        String norm = ColgramKolitiVoice.prepareSpeechText("**Привет!** Проверь `т.д.` и гс в тг на 100%", "ru");
        check(norm.contains("так далее") && norm.contains("голосовое сообщение")
                        && norm.contains("Телеграм") && norm.contains("процентов") && !norm.contains("**"),
                "Russian speech normalizer failed: " + norm);
        String mathNorm = ColgramKolitiVoice.prepareSpeechText("x + 1 = $0.05", "ru");
        check(mathNorm.contains("плюс") && mathNorm.contains("равно") && mathNorm.contains("доллара"),
                "Russian math/currency speech normalization failed: " + mathNorm);

        // On-device Voice Timbre DSP (F0 detection, WSOLA pitch+formant morphing, WAV encoding)
        int sr = 22050;
        int n = sr; // 1.0 sec synthetic harmonic voiced signals
        float[] userRef = new float[n];
        float[] ttsRaw = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            userRef[i] = (float) (0.35 * Math.sin(2 * Math.PI * 220.0 * t)
                    + 0.18 * Math.sin(2 * Math.PI * 440.0 * t)
                    + 0.08 * Math.sin(2 * Math.PI * 880.0 * t));
            ttsRaw[i] = (float) (0.35 * Math.sin(2 * Math.PI * 125.0 * t)
                    + 0.16 * Math.sin(2 * Math.PI * 250.0 * t)
                    + 0.07 * Math.sin(2 * Math.PI * 500.0 * t));
        }
        ColgramVoiceTimbreDsp.VoiceProfile refProf = ColgramVoiceTimbreDsp.analyzeProfile(userRef, sr);
        check(refProf.isValid() && refProf.medianF0Hz >= 205f && refProf.medianF0Hz <= 235f,
                "Reference F0 detection out of range: " + refProf.medianF0Hz);
        float[] morphed = ColgramVoiceTimbreDsp.matchTimbreToProfile(ttsRaw, sr, refProf, 0.72f, 0.58f);
        check(morphed != null && morphed.length == ttsRaw.length,
                "WSOLA morph must preserve exact speech duration");
        ColgramVoiceTimbreDsp.VoiceProfile morphedProf = ColgramVoiceTimbreDsp.analyzeProfile(morphed, sr);
        check(morphedProf.isValid() && morphedProf.medianF0Hz > 155f,
                "Morphed F0 must shift upward toward user voice: " + morphedProf.medianF0Hz);
        float maxPeak = 0f;
        for (float v : morphed) {
            check(!Float.isNaN(v) && !Float.isInfinite(v), "DSP output contained NaN/Inf");
            maxPeak = Math.max(maxPeak, Math.abs(v));
        }
        check(maxPeak > 0.05f && maxPeak <= 0.92f, "DSP peak ceiling invalid: " + maxPeak);
        try {
            java.io.File tmpWav = java.io.File.createTempFile("colgram_voice_test_", ".wav");
            tmpWav.deleteOnExit();
            ColgramVoiceTimbreDsp.writeWav16Bit(morphed, sr, tmpWav);
            check(tmpWav.length() == 44L + (long) morphed.length * 2L,
                    "WAV byte size mismatch: " + tmpWav.length());
        } catch (Exception e) {
            throw new AssertionError("WAV write failed: " + e.getMessage());
        }

        // Streaming sentence chunking for low-latency TTS start + 16kHz resampling for offline ASR
        List<String> shortChunks = ColgramKolitiVoice.splitIntoSpeechChunks("Привет, друг! Как у тебя дела?");
        check(shortChunks.size() == 1, "Short TTS reply must stay single seamless chunk: " + shortChunks);
        List<String> chunks = ColgramKolitiVoice.splitIntoSpeechChunks(
                "Привет, друг! Сегодня отличный день для работы над нашим приложением Colgram и проверки всех функций. "
                        + "Я уже ускорил генерацию ответов, включил офлайн распознавание голосовых сообщений и настроил тембр голоса.");
        check(chunks.size() >= 2 && chunks.get(0).contains("Привет"),
                "Streaming TTS sentence splitter failed: " + chunks);
        float[] resampled16k = ColgramVoiceTimbreDsp.resampleToRate(userRef, 22050, 16000);
        check(resampled16k != null && Math.abs(resampled16k.length - 16000) <= 4,
                "16kHz ASR resampling length mismatch: " + (resampled16k == null ? -1 : resampled16k.length));

        // Adaptive-MAX reasoning tiering & compact prefill system prompt
        check(ColgramAiContextEngine.classifyReasoningTier("Привет!") == 0,
                "Casual greeting must use tier 0 fast reasoning");
        check(ColgramAiContextEngine.classifyReasoningTier("ку") == 0,
                "Short greeting must use tier 0 fast reasoning");
        check(ColgramAiContextEngine.classifyReasoningTier("Сколько будет 2 + 2 * 5?") == 1,
                "Quick arithmetic must use tier 1 fast focused reasoning");
        check(ColgramAiContextEngine.classifyReasoningTier("Реши уравнение x^2 - 5x + 6 = 0 и докажи") == 2,
                "Equation + proof query must use tier 2 deep MAX reasoning");
        check(ColgramAiContextEngine.classifyReasoningTier("Напиши код быстрой сортировки на Python") == 2,
                "Coding query must use tier 2 deep MAX reasoning");
        // Autonomous song decision tool tag extraction (Koliti decides whether to sing via [[TOOL:SING_SONG]])
        List<String> songTool = ColgramAiAgentTools.extractToolActions("Ладно, держи мою песню! [[TOOL:SING_SONG]]");
        check(songTool.size() == 1 && "SING_SONG".equals(songTool.get(0)),
                "SING_SONG tool extraction failed: " + songTool);
        List<String> playAlias = ColgramAiAgentTools.extractToolActions("Спою! [[TOOL:PLAY_SONG]]");
        check(playAlias.size() == 1 && "SING_SONG".equals(playAlias.get(0)),
                "PLAY_SONG alias normalization failed: " + playAlias);
        check(ColgramAiAgentTools.queryAllowsToolCalls("спой песню пожалуйста", false, false),
                "Song request must allow autonomous SING_SONG tool");
        check(ColgramAiModels.recommendThreadsForCores(8) == 4,
                "8-core ARM big.LITTLE must allocate 4 big-core threads");

        String fastSys = ColgramAiContextEngine.buildSystemPrompt(ColgramAiContextEngine.AssistantMode.CHAT, false, false, false);
        String toolSys = ColgramAiContextEngine.buildSystemPrompt(ColgramAiContextEngine.AssistantMode.CHAT, false, false, true);
        check(fastSys.length() < toolSys.length() && !fastSys.contains("[[TOOL:") && toolSys.contains("[[TOOL:"),
                "Compact system prompt must omit tool guide when tools not needed");
    }

    public static void main(String[] args) {
        testCatalogAndBudget();
        testBm25BigContextRetrieval();
        testAntiHallucinationPromptAndThinkStrip();
        testWebSearchParsers();
        testNativeBridgeHostSafety();
        testAutonomousPlannerAndSynthesizer();
        testKolitiVoiceLogic();
        System.out.println("ColgramAiEngineTest: PASS");
    }

    private static void testBm25BmSafety() {
        List<ColgramAiContextEngine.ChatSnippet> empty = ColgramAiContextEngine.selectRelevantContext(null, "test", 10, 1000);
        check(empty.isEmpty(), "Empty context should return empty list");
    }
}
