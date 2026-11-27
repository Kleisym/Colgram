package org.colgram.core;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Autonomous orchestrator for Koliti:
 * - Single flagship custom-tuned model (Koliti Core 4B: Qwen3.5 Hybrid Gated DeltaNet + Vision)
 * - Zero-manual-choice Intent Planner (web synthesis, chat RAG, vision, draft insertion, in-app actions)
 * - Grounded anti-hallucination sampling + web distillation (never dumps raw scraped junk)
 * - Adaptive RAM, quantized KV cache, FlashAttention & thermal thread governor
 */
public final class ColgramAiEngine {

    public static final class ToolResult {
        public final boolean ok;
        public final boolean isQueryTool;
        public final boolean dismissSheetOnFinish;
        public final String toolName;
        public final String resolvedTarget;
        public final String observation;

        public ToolResult(
                boolean ok,
                boolean isQueryTool,
                boolean dismissSheetOnFinish,
                String toolName,
                String resolvedTarget,
                String observation
        ) {
            this.ok = ok;
            this.isQueryTool = isQueryTool;
            this.dismissSheetOnFinish = dismissSheetOnFinish;
            this.toolName = toolName == null ? "" : toolName;
            this.resolvedTarget = resolvedTarget == null ? "" : resolvedTarget;
            this.observation = observation == null ? "" : observation;
        }
    }

    public interface ToolExecutor {
        ToolResult executeTool(String normalizedToolAction);
    }

    public interface ResponseListener {
        default void onPlanResolved(ColgramAiContextEngine.IntentPlan plan) {}
        default void onDirectUiAction(String uiAction) {}
        default void onToolResult(ToolResult result) {}
        default void onPluginInstalled(ColgramAiAgentTools.InstalledPluginResult plugin) {}
        void onStage(String stageLabel);
        void onWebHits(List<ColgramAiWebSearch.SearchHit> hits);
        void onTokenDelta(String accumulatedVisibleText, boolean isThinking);
        void onFinished(String finalText, String footerInfo, boolean usedLocalModel);
        void onError(String message);
    }

    public static final class Request {
        public final String userQuery;
        public final ColgramAiContextEngine.AssistantMode mode;
        public final String activeChatTitle;
        public final String activeChatMeta;
        public final List<ColgramAiContextEngine.ChatSnippet> activeChatMessages;
        public final List<ColgramAiContextEngine.ChatSnippet> crossChatMessages;
        public final List<String> mediaPaths;
        public final boolean forceWebSearch;
        public final boolean hasActiveChat;
        public final boolean forceAttachChatContext;
        public final List<String> fallbackChatMediaPaths;
        public final String dialogDirectory;
        public final ToolExecutor toolExecutor;

        public Request(
                String userQuery,
                ColgramAiContextEngine.AssistantMode mode,
                String activeChatTitle,
                List<ColgramAiContextEngine.ChatSnippet> activeChatMessages,
                List<ColgramAiContextEngine.ChatSnippet> crossChatMessages,
                List<String> mediaPaths,
                boolean forceWebSearch
        ) {
            this(userQuery, mode, activeChatTitle, "", activeChatMessages, crossChatMessages,
                    mediaPaths, forceWebSearch,
                    activeChatMessages != null && !activeChatMessages.isEmpty(), false, null, "", null);
        }

        public Request(
                String userQuery,
                ColgramAiContextEngine.AssistantMode mode,
                String activeChatTitle,
                List<ColgramAiContextEngine.ChatSnippet> activeChatMessages,
                List<ColgramAiContextEngine.ChatSnippet> crossChatMessages,
                List<String> mediaPaths,
                boolean forceWebSearch,
                boolean hasActiveChat,
                List<String> fallbackChatMediaPaths
        ) {
            this(userQuery, mode, activeChatTitle, "", activeChatMessages, crossChatMessages,
                    mediaPaths, forceWebSearch, hasActiveChat, false, fallbackChatMediaPaths, "", null);
        }

        public Request(
                String userQuery,
                ColgramAiContextEngine.AssistantMode mode,
                String activeChatTitle,
                String activeChatMeta,
                List<ColgramAiContextEngine.ChatSnippet> activeChatMessages,
                List<ColgramAiContextEngine.ChatSnippet> crossChatMessages,
                List<String> mediaPaths,
                boolean forceWebSearch,
                boolean hasActiveChat,
                List<String> fallbackChatMediaPaths
        ) {
            this(userQuery, mode, activeChatTitle, activeChatMeta, activeChatMessages, crossChatMessages,
                    mediaPaths, forceWebSearch, hasActiveChat, false, fallbackChatMediaPaths, "", null);
        }

        public Request(
                String userQuery,
                ColgramAiContextEngine.AssistantMode mode,
                String activeChatTitle,
                String activeChatMeta,
                List<ColgramAiContextEngine.ChatSnippet> activeChatMessages,
                List<ColgramAiContextEngine.ChatSnippet> crossChatMessages,
                List<String> mediaPaths,
                boolean forceWebSearch,
                boolean hasActiveChat,
                boolean forceAttachChatContext,
                List<String> fallbackChatMediaPaths
        ) {
            this(userQuery, mode, activeChatTitle, activeChatMeta, activeChatMessages, crossChatMessages,
                    mediaPaths, forceWebSearch, hasActiveChat, forceAttachChatContext, fallbackChatMediaPaths, "", null);
        }

        public Request(
                String userQuery,
                ColgramAiContextEngine.AssistantMode mode,
                String activeChatTitle,
                String activeChatMeta,
                List<ColgramAiContextEngine.ChatSnippet> activeChatMessages,
                List<ColgramAiContextEngine.ChatSnippet> crossChatMessages,
                List<String> mediaPaths,
                boolean forceWebSearch,
                boolean hasActiveChat,
                boolean forceAttachChatContext,
                List<String> fallbackChatMediaPaths,
                String dialogDirectory,
                ToolExecutor toolExecutor
        ) {
            this.userQuery = userQuery == null ? "" : userQuery.trim();
            this.mode = mode == null ? ColgramAiContextEngine.AssistantMode.AUTO : mode;
            this.activeChatTitle = activeChatTitle == null ? "" : activeChatTitle;
            this.activeChatMeta = activeChatMeta == null ? "" : activeChatMeta.trim();
            this.activeChatMessages = activeChatMessages;
            this.crossChatMessages = crossChatMessages;
            this.mediaPaths = mediaPaths;
            this.forceWebSearch = forceWebSearch;
            this.hasActiveChat = hasActiveChat;
            this.forceAttachChatContext = forceAttachChatContext;
            this.fallbackChatMediaPaths = fallbackChatMediaPaths;
            this.dialogDirectory = dialogDirectory == null ? "" : dialogDirectory.trim();
            this.toolExecutor = toolExecutor;
        }
    }

    private static final class Holder {
        static final ColgramAiEngine INSTANCE = new ColgramAiEngine();
    }
    private static final int MAX_MEMORY_TURNS = 12;
    private static final int MAX_AGENT_STEPS = 5;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "colgram-ai-worker");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong requestSeq = new AtomicLong(0L);
    private final List<ColgramAiContextEngine.ConversationTurn> conversationMemory = new ArrayList<>();
    private volatile String lastReferencedChatTarget = "";

    private volatile String loadedModelKey = "";
    private volatile int loadedCtxBudget = 0;
    private volatile boolean loadedWithVision = false;
    private volatile boolean modelLoaded = false;
    private final AtomicBoolean warmUpQueued = new AtomicBoolean(false);

    private final Runnable idleUnloadTask = this::unloadModelAsync;

    private ColgramAiEngine() {}

    public static ColgramAiEngine getInstance() {
        return Holder.INSTANCE;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public boolean isModelLoaded() {
        return modelLoaded;
    }

    public synchronized void clearHistory() {
        conversationMemory.clear();
        lastReferencedChatTarget = "";
        clearNativeMemoryAsync();
    }

    public synchronized void setHistory(List<ColgramAiContextEngine.ConversationTurn> turns) {
        conversationMemory.clear();
        lastReferencedChatTarget = "";
        if (turns != null) {
            int start = Math.max(0, turns.size() - MAX_MEMORY_TURNS);
            for (int i = start; i < turns.size(); i++) {
                ColgramAiContextEngine.ConversationTurn turn = turns.get(i);
                conversationMemory.add(turn);
                if (turn != null && !turn.userQuery.isEmpty()) {
                    ColgramAiAgentTools.NaturalSendIntent ns =
                            ColgramAiAgentTools.parseNaturalMessagingIntent(turn.userQuery, lastReferencedChatTarget, "");
                    if (ns.matched && !ns.target.isEmpty()) {
                        lastReferencedChatTarget = ns.target;
                    } else {
                        ColgramAiAgentTools.DirectCommandResult dc = ColgramAiAgentTools.parseCrossChatDraftCommand(turn.userQuery);
                        if (dc.handled && dc.uiAction.contains(":") && dc.uiAction.contains("|")) {
                            String t = dc.uiAction.substring(dc.uiAction.indexOf(':') + 1, dc.uiAction.indexOf('|')).trim();
                            if (!t.isEmpty()) lastReferencedChatTarget = t;
                        }
                    }
                }
            }
        }
        clearNativeMemoryAsync();
    }

    private void clearNativeMemoryAsync() {
        worker.execute(() -> {
            if (ColgramNativeLlama.isLoaded() && modelLoaded) {
                try {
                    ColgramNativeLlama.nativeClearMemory();
                } catch (Throwable ignored) {}
            }
        });
    }

    private synchronized List<ColgramAiContextEngine.ConversationTurn> snapshotHistory() {
        return new ArrayList<>(conversationMemory);
    }

    private synchronized void recordTurn(String q, String ans) {
        if (q == null || q.isEmpty() || ans == null || ans.isEmpty()) return;
        conversationMemory.add(new ColgramAiContextEngine.ConversationTurn(q, ans));
        while (conversationMemory.size() > MAX_MEMORY_TURNS) {
            conversationMemory.remove(0);
        }
    }

    public void cancelCurrent() {
        cancelled.set(true);
        if (ColgramNativeLlama.isLoaded()) {
            ColgramNativeLlama.nativeCancel();
        }
    }

    public void unloadModelAsync() {
        worker.execute(() -> {
            if (ColgramNativeLlama.isLoaded() && modelLoaded) {
                ColgramNativeLlama.nativeUnload();
                modelLoaded = false;
                loadedWithVision = false;
                loadedModelKey = "";
                loadedCtxBudget = 0;
            }
        });
    }

    /**
     * Releases idle local GGUF memory when the app goes into deep background or the OS trims memory,
     * keeping background RSS tiny (~80 MB) so OEM killers never terminate Colgram 24/7 sync.
     */
    public void unloadIfIdleInBackground() {
        if (modelLoaded && !busy.get()) {
            unloadModelAsync();
        }
    }

    /**
     * Silently warms the text core AND prefills the static system prompt KV+SSM snapshot in background
     * when the user opens the Koliti sheet so typing time overlaps with initialization and the very
     * first question starts streaming immediately!
     */
    public void warmUpAsync(final Context appCtx) {
        if (appCtx == null || !ColgramNativeLlama.isLoaded() || busy.get()) return;
        if (!warmUpQueued.compareAndSet(false, true)) return;
        final Context ctx = appCtx.getApplicationContext();
        worker.execute(() -> {
            try {
                if (busy.get()) return;
                ColgramAiModelManager.ResolvedModelPaths paths = ColgramAiModelManager.resolveActiveModel(ctx);
                if ((!paths.isReady() || ColgramAiModelManager.needsEmbeddedUpgrade(ctx))
                        && (ColgramAiModelManager.hasEmbeddedAssets(ctx) || ColgramAiModelManager.hasEmbeddedVisionAsset(ctx))) {
                    if (modelLoaded) {
                        ColgramNativeLlama.nativeUnload();
                        modelLoaded = false;
                        loadedWithVision = false;
                        loadedModelKey = "";
                        loadedCtxBudget = 0;
                    }
                    paths = ColgramAiModelManager.ensureEmbeddedReadySync(ctx, null);
                }
                if (!paths.isReady()) return;
                int ctxBudget = resolveEffectiveContext(ctx, queryTotalRamMb(ctx), false);
                int kvBits = ColgramAiConfig.getKvQuantBits(ctx);
                boolean flash = ColgramAiConfig.isFlashAttentionEnabled(ctx);
                int threads = resolveThreads(ctx);
                String baseKey = paths.modelPath + "|" + kvBits + "|" + (flash ? 1 : 0) + "|" + threads;
                if (!modelLoaded || !baseKey.equals(loadedModelKey) || loadedCtxBudget < ctxBudget) {
                    String mmprojArg = paths.hasVision() ? paths.mmprojPath : "";
                    int rc = ColgramNativeLlama.nativeInit(paths.modelPath, mmprojArg, ctxBudget, threads, kvBits, flash);
                    if (rc != 0) return;
                    modelLoaded = true;
                    loadedWithVision = paths.hasVision();
                    loadedModelKey = baseKey;
                    loadedCtxBudget = ctxBudget;
                }
                if (!busy.get() && modelLoaded) {
                    String baseSys = ColgramAiContextEngine.buildSystemPrompt(
                            ColgramAiContextEngine.AssistantMode.CHAT, false, false, false);
                    try {
                        ColgramNativeLlama.nativeWarmUpPrompt(baseSys);
                    } catch (Throwable ignored) {}
                    scheduleIdleUnload(ctx);
                }
            } catch (Throwable ignored) {
            } finally {
                warmUpQueued.set(false);
            }
        });
    }

    public String buildStatusSummary(Context ctx) {
        ColgramAiModelManager.ResolvedModelPaths paths = ColgramAiModelManager.resolveActiveModel(ctx);
        long totalMb = queryTotalRamMb(ctx);
        int ctxBudget = resolveEffectiveContext(ctx, totalMb, false);
        int kv = ColgramAiConfig.getKvQuantBits(ctx);
        if (paths.isReady()) {
            String modeLbl = ColgramAiConfig.isThinkingEnabled(ctx) ? "Мышление" : "Турбо";
            return "Koliti Core · " + modeLbl + " · " + (ctxBudget / 1024) + "K (Q" + kv + ")";
        }
        if (ColgramAiModelManager.hasEmbeddedAssets(ctx)) {
            return "Koliti Core · Локально";
        }
        String nativeState = ColgramNativeLlama.isLoaded() ? "ядро готово" : "CPU";
        return "Koliti · " + nativeState;
    }

    public void runRequest(final Context appCtx, final Request req, final ResponseListener listener) {
        if (appCtx == null || req == null || listener == null) return;
        final long myReqId = requestSeq.incrementAndGet();
        cancelCurrent();
        mainHandler.removeCallbacks(idleUnloadTask);

        worker.execute(() -> {
            if (requestSeq.get() != myReqId) return;
            busy.set(true);
            cancelled.set(false);
            try {
                executeInternal(appCtx.getApplicationContext(), req, listener, myReqId);
            } catch (Throwable t) {
                if (requestSeq.get() == myReqId) {
                    String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    postFor(myReqId, () -> listener.onError("Ошибка AI: " + msg));
                }
            } finally {
                if (requestSeq.get() == myReqId) {
                    busy.set(false);
                    scheduleIdleUnload(appCtx.getApplicationContext());
                }
            }
        });
    }

    private void executeInternal(Context ctx, Request req, ResponseListener listener, final long reqId) {
        // Resolve local Koliti Core Ultra availability FIRST:
        // When the real neural network is available, EVERY message goes to the LLM (ZERO canned replies!).
        // Deterministic command shortcuts only serve as offline fallback when no native model exists.
        ColgramAiModelManager.ResolvedModelPaths paths = ColgramAiModelManager.resolveActiveModel(ctx);
        boolean upgradeEmbedded = ColgramAiModelManager.needsEmbeddedUpgrade(ctx);
        if ((!paths.isReady() || upgradeEmbedded)
                && (ColgramAiModelManager.hasEmbeddedAssets(ctx) || ColgramAiModelManager.hasEmbeddedVisionAsset(ctx))) {
            if (modelLoaded && upgradeEmbedded) {
                ColgramNativeLlama.nativeUnload();
                modelLoaded = false;
                loadedWithVision = false;
                loadedModelKey = "";
                loadedCtxBudget = 0;
            }
            postFor(reqId, () -> listener.onStage(upgradeEmbedded ? "Обновление ядра Koliti…" : "Подготовка ядра Koliti…"));
            paths = ColgramAiModelManager.ensureEmbeddedReadySync(ctx, new ColgramAiModelManager.DownloadListener() {
                @Override
                public void onProgress(String phase, long downloadedBytes, long totalBytes, double speedMbPerSec) {
                    int pct = totalBytes > 0 ? (int) Math.min(100L, (downloadedBytes * 100L) / totalBytes) : 0;
                    postFor(reqId, () -> listener.onStage(phase + " • " + pct + "%"));
                }
                @Override
                public void onComplete(boolean success, String message) {}
            });
        }
        final boolean nativeAvailable = ColgramNativeLlama.isLoaded() && paths.isReady();

        if (!nativeAvailable) {
            ColgramAiAgentTools.DirectCommandResult direct =
                    ColgramAiAgentTools.tryExecuteDirectCommand(ctx, req.userQuery, snapshotHistory());
            if (direct.handled) {
                recordTurn(req.userQuery, direct.replyMessage);
                if (!direct.uiAction.isEmpty()) {
                    postFor(reqId, () -> listener.onDirectUiAction(direct.uiAction));
                }
                postFor(reqId, () -> listener.onFinished(direct.replyMessage, "Koliti • Управление Colgram", false));
                return;
            }
        }

        boolean hasExplicitMedia = req.mediaPaths != null && !req.mediaPaths.isEmpty();
        ColgramAiContextEngine.IntentPlan plan = ColgramAiContextEngine.planAutonomousIntent(
                req.userQuery,
                req.mode,
                hasExplicitMedia,
                req.hasActiveChat,
                ColgramAiConfig.isWebSearchEnabled(ctx) || req.forceWebSearch
        );
        postFor(reqId, () -> listener.onPlanResolved(plan));
        postFor(reqId, () -> listener.onStage(nativeAvailable ? "Koliti отвечает…" : plan.intentSummary));

        // Only short-circuit bare ACTION labels if native neural model is absent.
        if (!nativeAvailable && plan.resolvedMode == ColgramAiContextEngine.AssistantMode.ACTION && !plan.inAppAction.isEmpty()) {
            String msg = plan.intentSummary;
            recordTurn(req.userQuery, msg);
            postFor(reqId, () -> listener.onFinished(msg, "Koliti • Действие", false));
            return;
        }

        // 1. Live Web Search & Readable Excerpt Fetch (filtered & passed straight to Koliti)
        List<ColgramAiWebSearch.SearchHit> webHits = new ArrayList<>();
        if ((plan.shouldSearchWeb || req.forceWebSearch) && !cancelled.get()) {
            postFor(reqId, () -> listener.onStage("Ищу и проверяю данные в сети…"));
            webHits = ColgramAiWebSearch.search(req.userQuery, 4, true);
            final List<ColgramAiWebSearch.SearchHit> hitsFinal = webHits;
            if (!hitsFinal.isEmpty()) {
                postFor(reqId, () -> listener.onWebHits(hitsFinal));
            }
        }
        if (cancelled.get() || requestSeq.get() != reqId) {
            postFor(reqId, () -> listener.onError("Отменено"));
            return;
        }

        // 2. Prepare attached OR auto-grabbed chat media (images, video keyframes, PDF pages, text/code/DOCX)
        List<String> rawMedia = new ArrayList<>();
        if (req.mediaPaths != null) rawMedia.addAll(req.mediaPaths);
        if (rawMedia.isEmpty() && plan.shouldAutoGrabMedia && req.fallbackChatMediaPaths != null) {
            rawMedia.addAll(req.fallbackChatMediaPaths);
        }

        List<String> processedImagePaths = new ArrayList<>();
        StringBuilder docTextSb = new StringBuilder();
        List<String> attachmentErrors = new ArrayList<>();
        boolean hasVideoGrid = false;
        if (!rawMedia.isEmpty()) {
            postFor(reqId, () -> listener.onStage("Подготавливаю вложения…"));
            for (String rawPath : rawMedia) {
                if (cancelled.get() || requestSeq.get() != reqId) return;
                ColgramAiVisionProcessor.PreparedAttachment pa = ColgramAiVisionProcessor.prepareAttachment(ctx, rawPath);
                if (pa == null) continue;
                if (pa.kind == ColgramAiVisionProcessor.AttachmentKind.UNSUPPORTED) {
                    if (!pa.errorMessage.isEmpty()) {
                        attachmentErrors.add(pa.errorMessage);
                    }
                    continue;
                }
                if (pa.hasImages()) {
                    for (java.io.File imgFile : pa.imageFiles) {
                        if (imgFile != null && imgFile.isFile() && processedImagePaths.size() < 6) {
                            processedImagePaths.add(imgFile.getAbsolutePath());
                        }
                    }
                    if (pa.isVideoGrid) hasVideoGrid = true;
                }
                if (pa.hasText() && docTextSb.length() < 6500) {
                    if (docTextSb.length() > 0) docTextSb.append("\n\n");
                    int remain = 6500 - docTextSb.length();
                    String chunk = pa.extractedText.length() > remain
                            ? pa.extractedText.substring(0, remain) + "\n… [обрезано]"
                            : pa.extractedText;
                    docTextSb.append(chunk);
                }
            }
        }
        if (cancelled.get() || requestSeq.get() != reqId) {
            postFor(reqId, () -> listener.onError("Отменено"));
            return;
        }

        if (!rawMedia.isEmpty() && processedImagePaths.isEmpty() && docTextSb.length() == 0 && !attachmentErrors.isEmpty()) {
            final String errText = String.join("\n", attachmentErrors);
            postFor(reqId, () -> listener.onError(errText));
            return;
        }

        if (nativeAvailable && !processedImagePaths.isEmpty() && !paths.hasVision()) {
            if (docTextSb.length() == 0) {
                postFor(reqId, () -> listener.onError(
                        "Модуль Vision для анализа фото, видео и PDF не установлен. "
                                + "Откройте Koliti Studio и загрузите Vision-модуль для активной модели."));
                return;
            }
            processedImagePaths.clear();
            docTextSb.insert(0, "(Примечание: изображения/PDF пропущены, так как Vision-модуль не установлен в Koliti Studio.)\n\n");
        }
        if (!attachmentErrors.isEmpty() && (docTextSb.length() > 0 || !processedImagePaths.isEmpty())) {
            if (docTextSb.length() > 0) docTextSb.append("\n\n");
            docTextSb.append("(Пропущены вложения: ").append(String.join("; ", attachmentErrors)).append(')');
        }
        String docBlock = docTextSb.toString().trim();

        // 3. Focused BM25 + Recency compression — ONLY inject Telegram chat/channel context when asked or explicitly attached!
        long totalRamMb = queryTotalRamMb(ctx);
        boolean needsVision = !processedImagePaths.isEmpty() && paths.hasVision();
        int ctxBudget = resolveEffectiveContext(ctx, totalRamMb, processedImagePaths.size(), docBlock.length());
        int maxChatChars = Math.min(1800, Math.max(900, (ctxBudget * 2) / 3));

        boolean includeActiveChat = (plan.shouldUseActiveChatContext || req.forceAttachChatContext)
                && plan.resolvedMode != ColgramAiContextEngine.AssistantMode.PLUGIN;
        List<ColgramAiContextEngine.ChatSnippet> activePicked = includeActiveChat
                ? ColgramAiContextEngine.selectRelevantContext(req.activeChatMessages, req.userQuery, 18, maxChatChars)
                : new ArrayList<>();
        boolean wantCross = plan.shouldIncludeCrossChat && ColgramAiConfig.isCrossChatRagEnabled(ctx);
        List<ColgramAiContextEngine.ChatSnippet> crossPicked = wantCross
                ? ColgramAiContextEngine.selectRelevantContext(req.crossChatMessages, req.userQuery, 8, 900)
                : new ArrayList<>();

        StringBuilder activeSb = new StringBuilder();
        if (includeActiveChat && !req.activeChatMeta.isEmpty()) {
            activeSb.append(req.activeChatMeta).append('\n');
        }
        String msgsFmt = ColgramAiContextEngine.formatChatContext(activePicked, false);
        if (!msgsFmt.isEmpty()) {
            activeSb.append(msgsFmt);
        }
        String activeBlock = activeSb.toString().trim();
        String crossBlock = ColgramAiContextEngine.formatChatContext(crossPicked, true);

        StringBuilder webSb = new StringBuilder();
        if (ColgramAiAgentTools.looksLikeSettingsStatusQuery(req.userQuery)) {
            webSb.append(ColgramAiAgentTools.buildSettingsStatusReport(ctx)).append('\n');
        }
        String verifiedMath = ColgramAiWebSearch.tryEvalMath(req.userQuery);
        if (verifiedMath != null) {
            webSb.append("Точный математический расчёт: ").append(verifiedMath).append('\n');
        }
        String fetchedWeb = ColgramAiWebSearch.formatHitsForPrompt(webHits);
        if (!fetchedWeb.isEmpty()) {
            webSb.append(fetchedWeb);
        }
        String webBlock = webSb.toString().trim();
        List<ColgramAiContextEngine.ConversationTurn> history = snapshotHistory();

        // 4. Run built-in local Koliti Core Ultra GGUF + Vision generation via libcolgram_llama.so
        if (nativeAvailable) {
            executeNativePipeline(
                    ctx, req, listener, reqId, paths, plan,
                    processedImagePaths, hasVideoGrid, needsVision, ctxBudget,
                    activeBlock, crossBlock, webBlock, docBlock, verifiedMath, history);
            return;
        }

        // Only reached if native llama library or embedded weights are completely absent
        String answer = ColgramAiContextEngine.synthesizeSmartAnswer(
                plan, req.userQuery, req.activeChatTitle, activePicked, crossPicked, webHits, !processedImagePaths.isEmpty());
        if (answer.isEmpty() && !docBlock.isEmpty()) {
            answer = "Текстовое содержимое документа извлечено. Для анализа файла установите локальное ядро Koliti Core в Koliti Studio.";
        }
        if (answer.isEmpty()) {
            String reason = !ColgramNativeLlama.isLoaded()
                    ? (" (" + ColgramNativeLlama.getLoadError() + ")")
                    : (!paths.isReady() ? " (модель не распакована — проверьте память устройства)" : "");
            postFor(reqId, () -> listener.onError("Локальное ядро Koliti не загружено" + reason + "."));
            return;
        }
        recordTurn(req.userQuery, answer);
        final String finalAnswer = answer;
        final String finalFooter = plan.intentSummary + (webHits.isEmpty() ? "" : " • Источников: " + webHits.size());
        postFor(reqId, () -> listener.onFinished(finalAnswer, finalFooter, false));
    }

    private static final class NativeRunState {
        String sysPrompt;
        String currentStepPrompt;
        String[] imgArr;
        int baseMaxTokens;
        float baseTemp;
        boolean strictGrounded;
        boolean baseNativeThinking;
        boolean showThinking;
        boolean allowTools;
        boolean includeDirectory;
        boolean remoteTargetOverride;
        ColgramAiAgentTools.NaturalSendIntent naturalSend;
        final List<String> agentLoopHistory = new ArrayList<>();
        final List<String> verifiedConfirmations = new ArrayList<>();
        String finalText = "";
        boolean anyActionExecuted = false;
        boolean aborted = false;
    }

    private boolean ensureNativeModelInitialized(
            Context ctx,
            ColgramAiModelManager.ResolvedModelPaths paths,
            int ctxBudget,
            boolean needsVision,
            ResponseListener listener,
            final long reqId
    ) {
        int kvBits = ColgramAiConfig.getKvQuantBits(ctx);
        boolean flash = ColgramAiConfig.isFlashAttentionEnabled(ctx);
        int threads = resolveThreads(ctx);
        String targetBaseKey = paths.modelPath + "|" + kvBits + "|" + (flash ? 1 : 0) + "|" + threads;
        boolean alreadySatisfied = modelLoaded
                && targetBaseKey.equals(loadedModelKey)
                && loadedCtxBudget >= ctxBudget
                && (!needsVision || loadedWithVision);
        if (alreadySatisfied) return true;

        postFor(reqId, () -> listener.onStage(needsVision ? "Подключаю модуль Vision…" : "Запуск Koliti Core…"));
        String mmprojArg = paths.hasVision() ? paths.mmprojPath : "";
        int rc = ColgramNativeLlama.nativeInit(paths.modelPath, mmprojArg, ctxBudget, threads, kvBits, flash);
        if (rc != 0) {
            modelLoaded = false;
            loadedWithVision = false;
            loadedModelKey = "";
            loadedCtxBudget = 0;
            String err = ColgramNativeLlama.nativeGetLastError();
            postFor(reqId, () -> listener.onError("Не удалось загрузить Koliti Core: " + err));
            return false;
        }
        modelLoaded = true;
        loadedWithVision = paths.hasVision();
        loadedModelKey = targetBaseKey;
        loadedCtxBudget = ctxBudget;
        return true;
    }

    private NativeRunState buildNativeRunState(
            Context ctx,
            Request req,
            ColgramAiContextEngine.IntentPlan plan,
            List<String> processedImagePaths,
            boolean hasVideoGrid,
            String activeBlock,
            String crossBlock,
            String webBlock,
            String docBlock,
            String verifiedMath,
            List<ColgramAiContextEngine.ConversationTurn> history
    ) {
        NativeRunState st = new NativeRunState();
        boolean hasChatCtx = !activeBlock.isEmpty() || !crossBlock.isEmpty();
        boolean hasWebCtx = !webBlock.isEmpty();
        boolean hasDocCtx = docBlock != null && !docBlock.isEmpty();

        st.naturalSend = ColgramAiAgentTools.parseNaturalMessagingIntent(
                req.userQuery, lastReferencedChatTarget, req.dialogDirectory);
        if (st.naturalSend.matched && !st.naturalSend.target.isEmpty()) {
            lastReferencedChatTarget = st.naturalSend.target;
        } else {
            ColgramAiAgentTools.DirectCommandResult namedCmd = ColgramAiAgentTools.parseCrossChatDraftCommand(req.userQuery);
            if (namedCmd.handled && namedCmd.uiAction.contains(":") && namedCmd.uiAction.contains("|")) {
                String t = namedCmd.uiAction.substring(namedCmd.uiAction.indexOf(':') + 1, namedCmd.uiAction.indexOf('|')).trim();
                if (!t.isEmpty()) {
                    String resolved = ColgramAiAgentTools.resolveBestTargetFromDirectory(t, req.dialogDirectory);
                    lastReferencedChatTarget = resolved.isEmpty() ? t : resolved;
                }
            }
        }

        st.remoteTargetOverride = st.naturalSend.matched && !st.naturalSend.target.isEmpty();
        boolean effectiveLocalDraft = plan.shouldDraftIntoInput && !st.remoteTargetOverride;

        st.includeDirectory = !req.dialogDirectory.isEmpty()
                && (st.remoteTargetOverride
                || ColgramAiAgentTools.queryNeedsDialogDirectory(req.userQuery, lastReferencedChatTarget));
        String dirForPrompt = st.includeDirectory ? req.dialogDirectory : "";

        boolean isSongAsk = !st.remoteTargetOverride && !effectiveLocalDraft
                && ColgramKolitiVoice.isSingSongRequest(req.userQuery);
        st.allowTools = isSongAsk || ColgramAiAgentTools.queryAllowsToolCalls(
                req.userQuery, st.remoteTargetOverride, st.includeDirectory);
        st.sysPrompt = ColgramAiContextEngine.buildSystemPrompt(plan.resolvedMode, hasChatCtx, hasWebCtx, st.allowTools);
        String effectiveQuery = req.userQuery;
        if (st.remoteTargetOverride) {
            String toolVerb = st.naturalSend.sendNow ? "SEND_TO_CHAT" : "DRAFT_TO_CHAT";
            String hint = "\n(Задача: реально " + (st.naturalSend.sendNow ? "отправить сообщение" : "подготовить черновик")
                    + " в чат «" + st.naturalSend.target + "». В Telegram сообщения доставляются даже офлайн — не отказывайся."
                    + " Напиши живой естественный текст для собеседника и ОБЯЗАТЕЛЬНО добавь в конце тег [[TOOL:"
                    + toolVerb + "|" + st.naturalSend.target + "|текст_сообщения]].)";
            effectiveQuery = req.userQuery + hint;
        } else if (effectiveLocalDraft) {
            effectiveQuery = req.userQuery + "\n(Выведи ТОЛЬКО готовый текст для отправки в чат, без пояснений.)";
        } else if (isSongAsk) {
            effectiveQuery = req.userQuery
                    + "\n(Пользователь просит спеть. Реши сам по настроению и контексту: "
                    + "либо спой свою фирменную аудио-песню — напиши короткую живую реплику и добавь в самом конце [[TOOL:SING_SONG]]; "
                    + "либо просто сочини короткий весёлый куплет текстом без тега; "
                    + "либо шутливо откажись / спроси в каком стиле спеть. Без шаблонов!)";
        }

        st.currentStepPrompt = ColgramAiContextEngine.buildUserPrompt(
                effectiveQuery, req.activeChatTitle, activeBlock, crossBlock, webBlock,
                dirForPrompt, docBlock, processedImagePaths.size(), hasVideoGrid, null);

        String[] baseHistoryPairs = plan.resolvedMode == ColgramAiContextEngine.AssistantMode.PLUGIN
                ? null
                : ColgramAiContextEngine.buildCleanHistoryPairs(history, req.userQuery);
        if (baseHistoryPairs == null && !history.isEmpty()) {
            try {
                ColgramNativeLlama.nativeClearMemory();
            } catch (Throwable ignored) {}
        }
        if (baseHistoryPairs != null) {
            for (String hp : baseHistoryPairs) {
                st.agentLoopHistory.add(processedImagePaths.isEmpty()
                        ? hp
                        : ColgramAiContextEngine.sanitizeMediaMarker(hp));
            }
        }

        st.showThinking = ColgramAiConfig.isThinkingEnabled(ctx);
        boolean adaptiveThink = !isSongAsk
                && ColgramAiContextEngine.shouldUseAdaptiveThinking(
                        req.userQuery, plan, !processedImagePaths.isEmpty());
        st.baseNativeThinking = !effectiveLocalDraft
                && !st.remoteTargetOverride
                && processedImagePaths.isEmpty()
                && (st.showThinking || adaptiveThink);
        st.imgArr = processedImagePaths.isEmpty() ? null : processedImagePaths.toArray(new String[0]);

        int reasoningTier = ColgramAiContextEngine.classifyReasoningTier(req.userQuery);
        int thinkingBudget = reasoningTier <= 1 ? 384 : 512;
        int fastBudget = isSongAsk ? 192
                : ((!processedImagePaths.isEmpty() || hasDocCtx) ? 448 : (reasoningTier == 0 ? 192 : 256));
        st.baseMaxTokens = effectiveLocalDraft ? 192
                : (st.remoteTargetOverride ? 256
                : (st.baseNativeThinking ? thinkingBudget
                : (plan.resolvedMode == ColgramAiContextEngine.AssistantMode.PLUGIN ? 768 : fastBudget)));
        st.baseTemp = ColgramAiConfig.getTemperature(ctx);
        st.strictGrounded = hasChatCtx || hasWebCtx || hasDocCtx || st.includeDirectory || st.remoteTargetOverride
                || plan.resolvedMode == ColgramAiContextEngine.AssistantMode.PLUGIN
                || effectiveLocalDraft || verifiedMath != null;
        return st;
    }

    private boolean executeSingleAgentStep(
            Context ctx,
            Request req,
            ResponseListener listener,
            final long reqId,
            ColgramAiContextEngine.IntentPlan plan,
            List<ColgramAiContextEngine.ConversationTurn> history,
            NativeRunState st,
            int step
    ) {
        boolean useThinkingThisStep = (step == 0) && st.baseNativeThinking;
        String[] stepHistory = st.agentLoopHistory.isEmpty() ? null : st.agentLoopHistory.toArray(new String[0]);
        String[] stepImgs = (step == 0) ? st.imgArr : null;
        int stepMaxTokens = (step == 0) ? st.baseMaxTokens : 640;

        float temp;
        float topP;
        if (useThinkingThisStep) {
            temp = st.strictGrounded ? 0.45f : 0.60f;
            topP = 0.95f;
        } else if (st.strictGrounded || step > 0) {
            temp = 0.35f;
            topP = 0.85f;
        } else {
            temp = Math.max(0.55f, Math.min(0.70f, st.baseTemp));
            topP = 0.85f;
        }

        final boolean[] streamedVisibleThisStep = {false};
        String stepRaw = runNativeGenerationStep(
                reqId, st.sysPrompt, stepHistory, st.currentStepPrompt, stepImgs,
                stepMaxTokens, temp, topP, 100, useThinkingThisStep, st.showThinking,
                listener, streamedVisibleThisStep);
        if (cancelled.get() || requestSeq.get() != reqId) {
            if (requestSeq.get() == reqId) {
                String partialClean = stepRaw == null ? ""
                        : ColgramAiAgentTools.stripToolTags(
                                ColgramAiContextEngine.stripThinkingTags(stepRaw, st.showThinking, useThinkingThisStep));
                if (!partialClean.isEmpty()) {
                    recordTurn(req.userQuery, partialClean);
                    postFor(reqId, () -> listener.onFinished(partialClean, "Koliti • Остановлено", true));
                } else {
                    postFor(reqId, () -> listener.onError("Генерация остановлена"));
                }
            }
            st.aborted = true;
            return false;
        }
        if (stepRaw == null) {
            String err = ColgramNativeLlama.nativeGetLastError();
            postFor(reqId, () -> listener.onError("Ошибка генерации: " + err));
            st.aborted = true;
            return false;
        }

        String stepText = ColgramAiContextEngine.stripThinkingTags(stepRaw, st.showThinking, useThinkingThisStep);
        if (step == 0 && stepText.isEmpty() && useThinkingThisStep && !cancelled.get()) {
            String retryRaw = runNativeGenerationStep(
                    reqId, st.sysPrompt, stepHistory, st.currentStepPrompt, stepImgs,
                    480, st.strictGrounded ? 0.35f : 0.62f, 0.85f, 100, false, st.showThinking,
                    listener, streamedVisibleThisStep);
            if (!cancelled.get() && retryRaw != null) {
                stepText = ColgramAiContextEngine.stripThinkingTags(retryRaw, st.showThinking, false);
            }
        }
        if (step == 0 && !cancelled.get() && ColgramAiContextEngine.isParrotRepeatOfHistory(stepText, req.userQuery, history)) {
            try {
                ColgramNativeLlama.nativeClearMemory();
            } catch (Throwable ignored) {}
            String retryRaw = runNativeGenerationStep(
                    reqId, st.sysPrompt, null, st.currentStepPrompt, stepImgs,
                    480, 0.68f, 0.88f, 100, false, st.showThinking,
                    listener, streamedVisibleThisStep);
            if (!cancelled.get() && retryRaw != null) {
                String retried = ColgramAiContextEngine.stripThinkingTags(retryRaw, st.showThinking, false);
                if (!retried.isEmpty()) stepText = retried;
            }
        }

        if (stepText.isEmpty()) {
            if (step == 0) {
                postFor(reqId, () -> listener.onError("Модель вернула пустой ответ — попробуй переформулировать запрос."));
                st.aborted = true;
            }
            return false;
        }

        List<String> toolActions = st.allowTools
                ? ColgramAiAgentTools.extractToolActions(stepText)
                : java.util.Collections.emptyList();
        String visibleProse = ColgramAiAgentTools.stripToolTags(stepText);

        if (toolActions.isEmpty() && st.remoteTargetOverride
                && ColgramAiAgentTools.looksLikeOfflineOrCannotSendRefusal(visibleProse)
                && step < MAX_AGENT_STEPS - 1) {
            postFor(reqId, () -> listener.onStage("Уточняю отправку в «" + st.naturalSend.target + "»…"));
            st.agentLoopHistory.add(st.currentStepPrompt);
            st.agentLoopHistory.add(stepText);
            st.currentStepPrompt = "В Telegram сообщения доставляются собеседнику даже если он не в сети. "
                    + "Не отказывайся и не объясняй статус онлайн. Напиши короткое естественное сообщение для «"
                    + st.naturalSend.target + "» по просьбе пользователя («" + req.userQuery + "») и ОБЯЗАТЕЛЬНО "
                    + "добавь в конце тег [[TOOL:" + (st.naturalSend.sendNow ? "SEND_TO_CHAT" : "DRAFT_TO_CHAT")
                    + "|" + st.naturalSend.target + "|текст]].";
            return true;
        }

        if (!toolActions.isEmpty()) {
            StringBuilder observationsForNextTurn = new StringBuilder();
            boolean needsNextLlmTurn = false;
            for (String ta : toolActions) {
                if (cancelled.get()) break;
                if (ColgramAiAgentTools.isQueryToolAction(ta)) {
                    if (step == 0 && streamedVisibleThisStep[0] && visibleProse.length() >= 48 && !st.includeDirectory && !plan.shouldSearchWeb) {
                        continue;
                    }
                    ToolResult qRes = executeQueryOrActionTool(ctx, req, ta, listener, reqId);
                    if (qRes != null && !qRes.observation.isEmpty()) {
                        if (observationsForNextTurn.length() > 0) observationsForNextTurn.append('\n');
                        observationsForNextTurn.append("[").append(ta).append("]\n").append(qRes.observation);
                        if (!qRes.resolvedTarget.isEmpty()) {
                            lastReferencedChatTarget = qRes.resolvedTarget;
                        }
                        needsNextLlmTurn = true;
                    }
                } else {
                    ToolResult aRes = executeQueryOrActionTool(ctx, req, ta, listener, reqId);
                    if (aRes != null) {
                        if (aRes.ok) {
                            st.anyActionExecuted = true;
                            if (!aRes.resolvedTarget.isEmpty()) {
                                lastReferencedChatTarget = aRes.resolvedTarget;
                            }
                            if (!aRes.observation.isEmpty()) {
                                st.verifiedConfirmations.add(aRes.observation);
                            }
                            final ToolResult delivered = aRes;
                            postFor(reqId, () -> listener.onToolResult(delivered));
                        } else if (step < MAX_AGENT_STEPS - 1 && !aRes.observation.isEmpty()) {
                            if (observationsForNextTurn.length() > 0) observationsForNextTurn.append('\n');
                            observationsForNextTurn.append("[Ошибка ").append(ta).append("]: ").append(aRes.observation);
                            needsNextLlmTurn = true;
                        } else if (!aRes.observation.isEmpty()) {
                            st.verifiedConfirmations.add("⚠ " + aRes.observation);
                        }
                    }
                }
            }

            if (needsNextLlmTurn && step < MAX_AGENT_STEPS - 1) {
                st.agentLoopHistory.add(st.currentStepPrompt);
                st.agentLoopHistory.add(stepText);
                st.currentStepPrompt = "Результат инструментов:\n" + observationsForNextTurn
                        + "\n\nИспользуй эти реальные данные, чтобы дать точный финальный ответ пользователю"
                        + " (или выполни действие через [[TOOL:...]], если задача ещё не завершена).";
                postFor(reqId, () -> listener.onStage("Koliti анализирует данные…"));
                return true;
            }
        }

        st.finalText = visibleProse;
        return false;
    }

    private void executeNativePipeline(
            Context ctx,
            Request req,
            ResponseListener listener,
            final long reqId,
            ColgramAiModelManager.ResolvedModelPaths paths,
            ColgramAiContextEngine.IntentPlan plan,
            List<String> processedImagePaths,
            boolean hasVideoGrid,
            boolean needsVision,
            int ctxBudget,
            String activeBlock,
            String crossBlock,
            String webBlock,
            String docBlock,
            String verifiedMath,
            List<ColgramAiContextEngine.ConversationTurn> history
    ) {
        if (!ensureNativeModelInitialized(ctx, paths, ctxBudget, needsVision, listener, reqId)) {
            return;
        }
        postFor(reqId, () -> listener.onStage("Koliti отвечает…"));
        NativeRunState st = buildNativeRunState(
                ctx, req, plan, processedImagePaths, hasVideoGrid,
                activeBlock, crossBlock, webBlock, docBlock, verifiedMath, history);

        for (int step = 0; step < MAX_AGENT_STEPS && !cancelled.get(); step++) {
            boolean keepGoing = executeSingleAgentStep(ctx, req, listener, reqId, plan, history, st, step);
            if (st.aborted) return;
            if (!keepGoing) break;
        }

        if (!st.anyActionExecuted && !cancelled.get()) {
            st.anyActionExecuted = runFallbackActionSafetyNet(
                    ctx, req, listener, reqId, plan, st.naturalSend, st.remoteTargetOverride,
                    st.finalText, history, st.verifiedConfirmations);
        }

        String finalText = st.finalText;
        if (!st.verifiedConfirmations.isEmpty()) {
            StringBuilder confSb = new StringBuilder();
            for (String c : st.verifiedConfirmations) {
                if (confSb.length() > 0) confSb.append('\n');
                confSb.append(c.startsWith("⚠") ? c : ("✓ " + c));
            }
            String confBlock = confSb.toString();
            if (finalText.isEmpty() || st.remoteTargetOverride) {
                finalText = confBlock;
            } else if (!finalText.contains(st.verifiedConfirmations.get(0))) {
                finalText = finalText + "\n\n" + confBlock;
            }
        } else if (finalText.isEmpty()) {
            finalText = "Готово.";
        }

        if (plan.resolvedMode == ColgramAiContextEngine.AssistantMode.PLUGIN) {
            ColgramAiAgentTools.InstalledPluginResult installed =
                    ColgramAiAgentTools.extractAndInstallPlugin(finalText, true);
            if (installed.installed) {
                postFor(reqId, () -> listener.onPluginInstalled(installed));
            }
        }
        recordTurn(req.userQuery, finalText);
        String timings = ColgramNativeLlama.nativeGetLastTimings();
        String footer = paths.displayName + (timings.isEmpty() ? "" : " • " + timings);
        final String outFinal = finalText;
        postFor(reqId, () -> listener.onFinished(outFinal, footer, true));
    }

    private boolean runFallbackActionSafetyNet(
            Context ctx,
            Request req,
            ResponseListener listener,
            final long reqId,
            ColgramAiContextEngine.IntentPlan plan,
            ColgramAiAgentTools.NaturalSendIntent naturalSend,
            boolean remoteTargetOverride,
            String finalText,
            List<ColgramAiContextEngine.ConversationTurn> history,
            List<String> verifiedConfirmations
    ) {
        String fallbackAction = "";
        if (remoteTargetOverride) {
            String msgBody = !naturalSend.literalPayload.isEmpty()
                    ? naturalSend.literalPayload
                    : ColgramAiAgentTools.extractNaturalMessageFromReply(finalText);
            if (msgBody.isEmpty() || ColgramAiAgentTools.looksLikeOfflineOrCannotSendRefusal(msgBody)) {
                if (naturalSend.pronounFollowUp && !history.isEmpty()) {
                    for (int i = history.size() - 1; i >= 0; i--) {
                        String prev = ColgramAiAgentTools.extractNaturalMessageFromReply(history.get(i).assistantReply);
                        if (!prev.isEmpty() && !ColgramAiAgentTools.looksLikeOfflineOrCannotSendRefusal(prev) && prev.length() <= 300) {
                            msgBody = prev;
                            break;
                        }
                    }
                }
            }
            if (!msgBody.isEmpty() && !ColgramAiAgentTools.looksLikeOfflineOrCannotSendRefusal(msgBody)) {
                fallbackAction = (naturalSend.sendNow ? "SEND_TO_CHAT:" : "DRAFT_TO_CHAT:")
                        + naturalSend.target + "|" + msgBody;
            }
        }
        if (fallbackAction.isEmpty()) {
            fallbackAction = ColgramAiAgentTools.resolveGenerativeCrossChatAction(req.userQuery, finalText);
        }
        if (fallbackAction.isEmpty()) {
            ColgramAiAgentTools.DirectCommandResult implicit =
                    ColgramAiAgentTools.tryExecuteDirectCommand(ctx, req.userQuery, history);
            if (implicit.handled && !implicit.uiAction.isEmpty()) {
                fallbackAction = implicit.uiAction;
            } else if (!plan.inAppAction.isEmpty()) {
                fallbackAction = plan.inAppAction;
            }
        }
        if (!fallbackAction.isEmpty()) {
            ToolResult fRes = executeQueryOrActionTool(ctx, req, fallbackAction, listener, reqId);
            if (fRes != null) {
                if (fRes.ok) {
                    if (!fRes.resolvedTarget.isEmpty()) {
                        lastReferencedChatTarget = fRes.resolvedTarget;
                    }
                    if (!fRes.observation.isEmpty()) {
                        verifiedConfirmations.add(fRes.observation);
                    }
                    final ToolResult delivered = fRes;
                    postFor(reqId, () -> listener.onToolResult(delivered));
                    return true;
                } else if (!fRes.observation.isEmpty()) {
                    verifiedConfirmations.add("⚠ " + fRes.observation);
                }
            }
        }
        return false;
    }

    private static final class StreamDelta {
        final String text;
        final boolean thinking;

        StreamDelta(String text, boolean thinking) {
            this.text = text;
            this.thinking = thinking;
        }
    }

    private String runNativeGenerationStep(
            final long reqId,
            String sysPrompt,
            String[] historyPairs,
            String userPrompt,
            String[] imgArr,
            int maxTokens,
            float temp,
            float topP,
            int repPen100,
            boolean useNativeThinking,
            boolean showThinking,
            ResponseListener listener,
            final boolean[] streamedVisibleFlag
    ) {
        final StringBuilder rawAccum = new StringBuilder();
        final long[] lastEmit = {0L};
        final boolean[] announcedThinking = {false};
        final boolean[] announcedAnswering = {false};
        final AtomicReference<StreamDelta> pendingDelta = new AtomicReference<>();
        final AtomicBoolean drainScheduled = new AtomicBoolean(false);
        final Runnable drainUiTask = () -> {
            drainScheduled.set(false);
            if (cancelled.get() || requestSeq.get() != reqId) return;
            StreamDelta snap = pendingDelta.getAndSet(null);
            if (snap != null) {
                listener.onTokenDelta(snap.text, snap.thinking);
            }
        };

        int genRc = ColgramNativeLlama.nativeGenerateWithHistory(
                sysPrompt,
                historyPairs,
                userPrompt,
                imgArr,
                maxTokens,
                temp,
                topP,
                repPen100,
                useNativeThinking,
                piece -> {
                    if (cancelled.get() || requestSeq.get() != reqId) return false;
                    rawAccum.append(piece);
                    long now = System.currentTimeMillis();
                    // Emit token #1 immediately (0ms wait) and coalesce subsequent UI ticks at ~50ms
                    // so mainHandler never backs up and native decode never stalls!
                    if (lastEmit[0] == 0L || now - lastEmit[0] >= 50L) {
                        lastEmit[0] = now;
                        boolean thinkingNow = (useNativeThinking || rawAccum.indexOf("<think>") >= 0)
                                && rawAccum.indexOf("</think>") < 0;
                        if (thinkingNow && !showThinking) {
                            if (!announcedThinking[0]) {
                                announcedThinking[0] = true;
                                postFor(reqId, () -> listener.onStage("Размышляю…"));
                            }
                            return true;
                        }
                        if (!thinkingNow && announcedThinking[0] && !announcedAnswering[0]) {
                            announcedAnswering[0] = true;
                            postFor(reqId, () -> listener.onStage("Koliti отвечает…"));
                        }
                        String visible = ColgramAiAgentTools.stripStreamingToolTail(
                                ColgramAiContextEngine.stripThinkingTags(
                                        rawAccum.toString(), showThinking, useNativeThinking));
                        if (!visible.isEmpty()) {
                            // Live loop circuit-breaker: if the visible answer repeats the exact same 24+ char phrase 3x, stop cleanly.
                            if (!thinkingNow && hasDegenerateRepetitionLoop(visible)) {
                                return false;
                            }
                            if (streamedVisibleFlag != null && !thinkingNow) {
                                streamedVisibleFlag[0] = true;
                            }
                            pendingDelta.set(new StreamDelta(visible, thinkingNow));
                            if (drainScheduled.compareAndSet(false, true)) {
                                mainHandler.post(drainUiTask);
                            }
                        }
                    }
                    return true;
                }
        );
        if (genRc < 0 && rawAccum.length() == 0) {
            return null;
        }
        return rawAccum.toString();
    }

    private static boolean hasDegenerateRepetitionLoop(String text) {
        if (text == null || text.length() < 96) return false;
        int n = text.length();
        for (int patLen = 18; patLen <= 64; patLen += 6) {
            if (n < patLen * 3) break;
            String tail = text.substring(n - patLen);
            if (tail.trim().length() < 12) continue;
            int idx2 = text.lastIndexOf(tail, n - patLen - 1);
            if (idx2 >= 0 && (n - patLen - idx2) <= patLen + 8) {
                int idx1 = text.lastIndexOf(tail, idx2 - 1);
                if (idx1 >= 0 && (idx2 - idx1) <= patLen + 8) {
                    return true;
                }
            }
        }
        return false;
    }

    private ToolResult executeQueryOrActionTool(Context ctx, Request req, String ta, ResponseListener listener, long reqId) {
        if (ta == null || ta.isEmpty()) return null;
        if (ta.startsWith("WEB_SEARCH:")) {
            String query = ta.substring("WEB_SEARCH:".length()).trim();
            if (query.isEmpty()) return null;
            postFor(reqId, () -> listener.onStage("Ищу в сети: " + query + "…"));
            List<ColgramAiWebSearch.SearchHit> hits = ColgramAiWebSearch.search(query, 4, true);
            if (!hits.isEmpty()) {
                postFor(reqId, () -> listener.onWebHits(hits));
            }
            String formatted = ColgramAiWebSearch.formatHitsForPrompt(hits);
            return new ToolResult(!formatted.isEmpty(), true, false, "WEB_SEARCH", "",
                    formatted.isEmpty() ? "Ничего релевантного в сети не найдено." : formatted);
        }
        if ("GET_SETTINGS".equals(ta)) {
            return new ToolResult(true, true, false, "GET_SETTINGS", "",
                    ColgramAiAgentTools.buildSettingsStatusReport(ctx));
        }
        if ("SING_SONG".equals(ta)) {
            return new ToolResult(true, false, false, "SING_SONG", "", "");
        }
        if (ta.startsWith("SET_SETTING:")) {
            String kv = ta.substring("SET_SETTING:".length());
            boolean applied = ColgramAiAgentTools.applySettingTool(ctx, kv);
            if (applied && kv.toLowerCase(java.util.Locale.ROOT).startsWith("cyber=")) {
                if (req.toolExecutor != null) {
                    req.toolExecutor.executeTool("REFRESH_THEME");
                } else {
                    postFor(reqId, () -> listener.onDirectUiAction("REFRESH_THEME"));
                }
            }
            return new ToolResult(applied, false, false, "SET_SETTING", "",
                    applied ? ("Настройка обновлена (" + kv + ").") : ("Неизвестная настройка: " + kv));
        }
        if (ta.startsWith("READ_CHAT:")) {
            String p = ta.substring("READ_CHAT:".length());
            String tName = p.contains("|") ? p.substring(0, p.indexOf('|')).trim() : p.trim();
            postFor(reqId, () -> listener.onStage("Читаю чат «" + tName + "»…"));
        } else if ("LIST_CHATS".equals(ta)) {
            postFor(reqId, () -> listener.onStage("Проверяю список чатов…"));
        } else if (ta.startsWith("SEND_TO_CHAT:")) {
            String p = ta.substring("SEND_TO_CHAT:".length());
            String tName = p.contains("|") ? p.substring(0, p.indexOf('|')).trim() : p.trim();
            postFor(reqId, () -> listener.onStage("Отправляю сообщение в «" + tName + "»…"));
        } else if (ta.startsWith("DRAFT_TO_CHAT:")) {
            String p = ta.substring("DRAFT_TO_CHAT:".length());
            String tName = p.contains("|") ? p.substring(0, p.indexOf('|')).trim() : p.trim();
            postFor(reqId, () -> listener.onStage("Подготавливаю черновик для «" + tName + "»…"));
        }
        if (req.toolExecutor != null) {
            return req.toolExecutor.executeTool(ta);
        }
        final String actionToRun = ta;
        postFor(reqId, () -> listener.onDirectUiAction(actionToRun));
        return new ToolResult(true, false, false, ta, "", "");
    }

    public static int computeVisionContextBudget(int overrideCtx, int imageCount, int docChars) {
        int dynamicBase = 2048;
        if (imageCount > 0) {
            dynamicBase = imageCount >= 3
                    ? 4096
                    : Math.max(3072, 2048 + imageCount * 448);
        }
        if (docChars > 2000) {
            dynamicBase = Math.max(dynamicBase, 4096);
        }
        if (overrideCtx >= 1024) {
            return Math.max(overrideCtx, dynamicBase);
        }
        return dynamicBase;
    }

    private int resolveEffectiveContext(Context ctx, long totalRamMb, boolean needsVision) {
        return resolveEffectiveContext(ctx, totalRamMb, needsVision ? 1 : 0, 0);
    }

    private int resolveEffectiveContext(Context ctx, long totalRamMb, int imageCount, int docChars) {
        int override = ColgramAiConfig.getContextSizeOverride(ctx);
        if (imageCount > 0 || docChars > 2000) {
            return computeVisionContextBudget(override, imageCount, docChars);
        }
        if (override >= 1024) {
            return override;
        }
        return ColgramAiModels.recommendContextForTotalRamMb(totalRamMb);
    }

    private int resolveThreads(Context ctx) {
        // 0 = auto big.LITTLE performance-core detection & affinity pinning in native C++!
        return Math.max(0, ColgramAiConfig.getThreadsOverride(ctx));
    }

    private long queryTotalRamMb(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                if (mi.totalMem > 0L) {
                    return mi.totalMem / (1024L * 1024L);
                }
            }
        } catch (Throwable ignored) {}
        return 4096L;
    }

    private void scheduleIdleUnload(Context ctx) {
        int sec = ColgramAiConfig.getIdleUnloadSeconds(ctx);
        if (sec > 0 && modelLoaded) {
            mainHandler.removeCallbacks(idleUnloadTask);
            mainHandler.postDelayed(idleUnloadTask, sec * 1000L);
        }
    }

    private void post(Runnable r) {
        mainHandler.post(r);
    }

    private void postFor(final long reqId, final Runnable r) {
        mainHandler.post(() -> {
            if (requestSeq.get() == reqId) {
                r.run();
            }
        });
    }
}
