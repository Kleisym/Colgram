package org.telegram.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramAiConfig;
import org.colgram.core.ColgramAiEngine;
import org.colgram.core.ColgramAiModelManager;
import org.colgram.core.ColgramAiModels;
import org.colgram.core.ColgramNativeLlama;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Koliti Studio — control center for Koliti v3 optimized local models:
 * - Manage optimized Koliti v3 weights (Core, Turbo, Pro) + Vision projector directly from Colgram Releases
 * - Live download progress with resume, RAM unload, thermal CPU threads, quantized KV cache & FlashAttention
 */
public class ColgramAiActivity extends BaseFragment {

    public static final int REQ_PICK_GGUF = 6401;
    public static final int REQ_PICK_MMPROJ = 6402;
    public static final int REQ_PICK_MEDIA_FROM_SHEET = 6403;
    public static final int REQ_VOICE_RECOGNIZE = 6404;
    public static final int REQ_PICK_VOICE_REF = 6405;
    public static final int REQ_CAPTURE_CAMERA_FROM_SHEET = 6406;
    public static final int REQ_PICK_FILE_FROM_SHEET = 6407;
    public static final int REQ_PERM_MIC_KOLITI = 6410;
    public static final int REQ_PERM_CAMERA_KOLITI = 6412;
    public static ColgramAiSheet pendingSheetForMedia;
    public static ColgramAiSheet pendingSheetForVoice;
    public static ColgramAiSheet pendingSheetForCameraPerm;
    public static String pendingCameraOutputPath;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private int rowCount;
    private int heroRow;
    private int coreCardRow;
    private int modelPickerRow;
    private int designCustomizeRow;
    private int idleSleepRow;
    private int coreInfoRow;
    private int sectionVoiceRow;
    private int voiceOverToggleRow;
    private int voiceSampleRow;
    private int voiceCloneEngineRow;
    private int voiceTestRow;
    private int sectionRuntimeRow;
    private int orbToggleRow;
    private int webSearchRow;
    private int crossChatRow;
    private int thinkingRow;
    private int ctxSizeRow;
    private int kvQuantRow;
    private int flashAttnRow;
    private int threadsRow;
    private int sectionAdvancedRow;
    private int importGgufRow;
    private int importMmprojRow;
    private int resetCustomRow;
    private int footerShadowRow;

    private boolean lastObservedDownloading = false;
    private final ColgramAiModelManager.DownloadObserver downloadObserver = state -> AndroidUtilities.runOnUIThread(() -> {
        if (listView == null || adapter == null) return;
        boolean flippedToDone = lastObservedDownloading && !state.active;
        lastObservedDownloading = state.active;
        if (flippedToDone) {
            refreshRow(heroRow);
        }
        refreshRow(coreCardRow);
    });

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows();
        ColgramAiModelManager.addDownloadObserver(downloadObserver);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        ColgramAiModelManager.removeDownloadObserver(downloadObserver);
        super.onFragmentDestroy();
    }

    private void updateRows() {
        rowCount = 0;
        heroRow = rowCount++;
        coreCardRow = rowCount++;
        modelPickerRow = rowCount++;
        designCustomizeRow = rowCount++;
        idleSleepRow = rowCount++;
        coreInfoRow = rowCount++;
        sectionVoiceRow = rowCount++;
        voiceOverToggleRow = rowCount++;
        voiceSampleRow = rowCount++;
        voiceCloneEngineRow = rowCount++;
        voiceTestRow = rowCount++;
        sectionRuntimeRow = rowCount++;
        orbToggleRow = rowCount++;
        webSearchRow = rowCount++;
        crossChatRow = rowCount++;
        thinkingRow = rowCount++;
        ctxSizeRow = rowCount++;
        kvQuantRow = rowCount++;
        flashAttnRow = rowCount++;
        threadsRow = rowCount++;
        sectionAdvancedRow = rowCount++;
        importGgufRow = rowCount++;
        importMmprojRow = rowCount++;
        resetCustomRow = rowCount++;
        footerShadowRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Koliti Studio");
        ColgramMenuChrome.styleActionBar(actionBar);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout root = (FrameLayout) fragmentView;
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this::handleItemClick);

        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUi();
    }

    private void handleItemClick(View view, int position) {
        Context ctx = getParentActivity();
        if (ctx == null) return;

        if (position == modelPickerRow) {
            showModelTierPickerDialog(ctx);
            return;
        }
        if (position == designCustomizeRow) {
            presentFragment(new ColgramDesignStudioActivity());
            return;
        }
        if (position == idleSleepRow) {
            final int[] vals = {30, 60, 180, 0};
            final String[] labels = {
                    "30 секунд (максимальная экономия батареи и RAM)",
                    "60 секунд (рекомендуется • мгновенный сон без нагрузки)",
                    "3 минуты (баланс)",
                    "Не выгружать (держит в RAM пока открыт Colgram)"
            };
            int curIdle = ColgramAiConfig.getIdleUnloadSeconds(ctx);
            int selIdle = -1;
            for (int i = 0; i < vals.length; i++) {
                if (vals[i] == curIdle) { selIdle = i; break; }
            }
            ColgramUiKit.showChoiceSheet(
                    getParentActivity(),
                    getResourceProvider(),
                    R.drawable.msg_speed,
                    "Спящий режим Koliti",
                    null,
                    labels,
                    selIdle,
                    which -> {
                        ColgramAiConfig.setIdleUnloadSeconds(ctx, vals[which]);
                        refreshRow(idleSleepRow);
                    }
            );
            return;
        }
        if (position == voiceOverToggleRow) {
            boolean next = !ColgramAiConfig.isVoiceOverEnabled(ctx);
            ColgramAiConfig.setVoiceOverEnabled(ctx, next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            ColgramUiKit.showNotice(this, R.drawable.input_mic,
                    next ? "Режим озвучки ответов Koliti включён" : "Озвучка ответов отключена", false);
            return;
        }
        if (position == voiceSampleRow) {
            showVoiceSamplePickerDialog(ctx);
            return;
        }
        if (position == voiceCloneEngineRow) {
            showVoiceCloneServerDialog(ctx);
            return;
        }
        if (position == voiceTestRow) {
            Toast.makeText(ctx, "Тестирую озвучку Koliti…", Toast.LENGTH_SHORT).show();
            org.colgram.core.ColgramKolitiVoice.speakReplyAsync(
                    ctx,
                    "Привет! Я Колити — твой друг-помощник в Колграме. Проверка озвучки и ударений прошла успешно!",
                    "ru",
                    R.raw.koliti_user_voice_ref,
                    (usedClone, file, note) -> AndroidUtilities.runOnUIThread(() ->
                            Toast.makeText(ctx, "Режим: " + note, Toast.LENGTH_SHORT).show())
            );
            return;
        }
        if (position == orbToggleRow) {
            boolean next = !ColgramAiConfig.isOrbEnabled(ctx);
            ColgramAiConfig.setOrbEnabled(ctx, next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            if (ColgramGlobalOrbController.getInstance() != null) {
                ColgramGlobalOrbController.getInstance().scheduleRelayout();
            }
            return;
        }
        if (position == webSearchRow) {
            boolean next = !ColgramAiConfig.isWebSearchEnabled(ctx);
            ColgramAiConfig.setWebSearchEnabled(ctx, next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            return;
        }
        if (position == crossChatRow) {
            boolean next = !ColgramAiConfig.isCrossChatRagEnabled(ctx);
            ColgramAiConfig.setCrossChatRagEnabled(ctx, next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            return;
        }
        if (position == thinkingRow) {
            boolean next = !ColgramAiConfig.isThinkingEnabled(ctx);
            ColgramAiConfig.setThinkingEnabled(ctx, next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            return;
        }
        if (position == flashAttnRow) {
            boolean next = !ColgramAiConfig.isFlashAttentionEnabled(ctx);
            ColgramAiConfig.setFlashAttentionEnabled(ctx, next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            return;
        }
        if (position == ctxSizeRow) {
            final int[] vals = {0, 2048, 3072, 4096, 8192, 16384};
            final String[] labels = {
                    "Авто (Турбо: 2K текст / 3K Vision)",
                    "2K токенов (максимальная скорость и минимум RAM)",
                    "3K токенов (для Vision и средних чатов)",
                    "4K токенов (баланс)",
                    "8K токенов (длинные чаты)",
                    "16K токенов (максимум)"
            };
            int curCtx = ColgramAiConfig.getContextSizeOverride(ctx);
            int selCtx = -1;
            for (int i = 0; i < vals.length; i++) {
                if (vals[i] == curCtx) { selCtx = i; break; }
            }
            ColgramUiKit.showChoiceSheet(
                    getParentActivity(),
                    getResourceProvider(),
                    R.drawable.msg_settings,
                    "Размер контекста Koliti",
                    "Сбросит текущую загруженную сессию в RAM",
                    labels,
                    selCtx,
                    which -> {
                        ColgramAiConfig.setContextSizeOverride(ctx, vals[which]);
                        ColgramAiEngine.getInstance().unloadModelAsync();
                        refreshRow(ctxSizeRow);
                    }
            );
            return;
        }
        if (position == kvQuantRow) {
            final int[] vals = {8, 4, 16};
            final String[] labels = {
                    "Q8_0 (рекомендуется • точный и компактный)",
                    "Q4_0 (ультра-экономия памяти для 32K)",
                    "FP16 (без сжатия)"
            };
            int curKv = ColgramAiConfig.getKvQuantBits(ctx);
            int selKv = -1;
            for (int i = 0; i < vals.length; i++) {
                if (vals[i] == curKv) { selKv = i; break; }
            }
            ColgramUiKit.showChoiceSheet(
                    getParentActivity(),
                    getResourceProvider(),
                    R.drawable.msg_speed,
                    "Квантование KV-кэша",
                    null,
                    labels,
                    selKv,
                    which -> {
                        ColgramAiConfig.setKvQuantBits(ctx, vals[which]);
                        refreshRow(kvQuantRow);
                    }
            );
            return;
        }
        if (position == threadsRow) {
            final int[] vals = {0, 2, 3, 4, 6};
            final String[] labels = {
                    "Авто (только быстрые big-ядра CPU)",
                    "2 потока (лучший для 2×A76 big.LITTLE)",
                    "3 потока (тихий)",
                    "4 потока (для 4× big-ядер)",
                    "6 потоков (все ядра)"
            };
            int curTh = ColgramAiConfig.getThreadsOverride(ctx);
            int selTh = -1;
            for (int i = 0; i < vals.length; i++) {
                if (vals[i] == curTh) { selTh = i; break; }
            }
            ColgramUiKit.showChoiceSheet(
                    getParentActivity(),
                    getResourceProvider(),
                    R.drawable.msg_speed_fast,
                    "Потоки CPU для Koliti",
                    null,
                    labels,
                    selTh,
                    which -> {
                        ColgramAiConfig.setThreadsOverride(ctx, vals[which]);
                        refreshRow(threadsRow);
                    }
            );
            return;
        }
        if (position == importGgufRow) {
            launchFilePicker(REQ_PICK_GGUF, "Выберите свой файл модели");
            return;
        }
        if (position == importMmprojRow) {
            launchFilePicker(REQ_PICK_MMPROJ, "Выберите свой файл Vision-модуля");
            return;
        }
        if (position == resetCustomRow) {
            ColgramAiConfig.setCustomModelPath(ctx, "");
            ColgramAiConfig.setCustomMmprojPath(ctx, "");
            ColgramAiConfig.setActiveModelId(ctx, ColgramAiModels.KOLITI_MODEL_ID);
            ColgramAiEngine.getInstance().unloadModelAsync();
            ColgramUiKit.showNotice(this, R.drawable.msg_bots, "Возвращена модель " + ColgramAiModels.primary().title, false);
            refreshUi();
        }
    }

    private void showModelTierPickerDialog(Context ctx) {
        if (ColgramAiModelManager.isDownloading()) {
            ColgramUiKit.showNotice(this, R.drawable.msg_download, "Сначала дождитесь завершения или остановите загрузку", true);
            return;
        }
        final java.util.List<ColgramAiModels.ModelSpec> specs = ColgramAiModels.catalog();
        final String activeId = ColgramAiConfig.getActiveModelId(ctx);
        final boolean hasEmbedded = ColgramAiModelManager.hasEmbeddedAssets(ctx);
        final java.util.List<ColgramUiKit.ChoiceItem> items = new java.util.ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            ColgramAiModels.ModelSpec s = specs.get(i);
            boolean installed = ColgramAiModelManager.isModelDownloaded(ctx, s);
            boolean embedded = s.hasBundledAssets() && hasEmbedded;
            String state = installed ? "установлена" : (embedded ? "встроена в APK" : "скачать с GitHub");
            items.add(new ColgramUiKit.ChoiceItem(
                    s.title,
                    ColgramAiModelManager.formatSizeMb(s.approxModelBytes)
                            + (s.supportsVision() ? " + Vision" : "")
                            + " · " + state,
                    R.drawable.msg_bots,
                    0,
                    s.id.equals(activeId),
                    false,
                    installed ? "Готова" : (embedded ? "В APK" : "GitHub")
            ));
        }
        ColgramUiKit.showChoiceSheet(
                getParentActivity(),
                getResourceProvider(),
                R.drawable.msg_bots,
                "Выбор модели Колити",
                "Локальные нейросети Koliti v3",
                items,
                which -> {
                    ColgramAiModels.ModelSpec chosen = specs.get(which);
                    ColgramAiConfig.setCustomModelPath(ctx, "");
                    ColgramAiConfig.setCustomMmprojPath(ctx, "");
                    ColgramAiConfig.setActiveModelId(ctx, chosen.id);
                    ColgramAiEngine.getInstance().unloadModelAsync();
                    if (ColgramAiModelManager.isModelDownloaded(ctx, chosen)) {
                        ColgramUiKit.showNotice(this, R.drawable.msg_bots, "Активная модель: " + chosen.title, false);
                        refreshUi();
                    } else {
                        startDownloadForSpec(ctx, chosen);
                    }
                }
        );
    }

    private void startOrToggleCoreDownload(Context ctx) {
        if (ColgramAiModelManager.isDownloading()) {
            ColgramAiModelManager.cancelDownload();
            refreshRow(coreCardRow);
            return;
        }
        ColgramAiModels.ModelSpec activeSpec = ColgramAiModels.findById(ColgramAiConfig.getActiveModelId(ctx));
        startDownloadForSpec(ctx, activeSpec);
    }

    private void startDownloadForSpec(Context ctx, ColgramAiModels.ModelSpec spec) {
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        ColgramAiModelManager.startDownloadAsync(appCtx, spec, true, new ColgramAiModelManager.DownloadListener() {
            @Override
            public void onProgress(String stage, long downloadedBytes, long totalBytes, double speedMbSec) {
                // Live UI state is pushed via global downloadObserver so exiting/reopening preserves progress
            }

            @Override
            public void onComplete(boolean success, String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    Activity act = getParentActivity();
                    if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                        Toast.makeText(act, success ? ("Готово: " + spec.title) : message, Toast.LENGTH_LONG).show();
                        refreshUi();
                    }
                });
            }
        });
    }

    private void confirmDeleteCore(Context ctx) {
        Activity act = getParentActivity();
        if (act == null) return;
        final ColgramAiModels.ModelSpec spec = ColgramAiModels.findById(ColgramAiConfig.getActiveModelId(ctx));
        long mb = Math.max(1L, (spec.approxModelBytes + spec.approxMmprojBytes) / (1024L * 1024L));
        ColgramUiKit.showConfirmSheet(
                act,
                getResourceProvider(),
                R.drawable.msg_delete,
                "Удалить веса " + spec.title + "?",
                "Освободится ~" + mb + " МБ памяти. Вы сможете загрузить модель повторно в любой момент или переключиться на " + ColgramAiModels.primary().title + ".",
                "Удалить",
                true,
                () -> {
                    ColgramAiEngine.getInstance().unloadModelAsync();
                    ColgramAiModelManager.deleteModelFiles(ctx, spec);
                    if (!ColgramAiModels.KOLITI_MODEL_ID.equals(spec.id)) {
                        ColgramAiConfig.setActiveModelId(ctx, ColgramAiModels.KOLITI_MODEL_ID);
                    }
                    refreshUi();
                },
                "Отмена",
                null
        );
    }

    private void launchFilePicker(int reqCode, String chooserTitle) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(intent, chooserTitle), reqCode);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("*/*");
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(Intent.createChooser(fallback, chooserTitle), reqCode);
            } catch (Throwable t2) {
                Toast.makeText(getParentActivity(), "Не удалось открыть проводник", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResultFragment(requestCode, permissions, grantResults);
        Activity act = getParentActivity();
        if (act != null && ColgramAiSheet.handlePermissionResult(act, requestCode, permissions, grantResults)) {
            return;
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        Activity act = getParentActivity();
        if (act != null && !act.isFinishing() && !act.isDestroyed()
                && ColgramAiSheet.handleActivityResult(act, requestCode, resultCode, data)) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) return;
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == REQ_PICK_VOICE_REF) {
            final android.content.res.AssetFileDescriptor stagedAfd;
            try {
                stagedAfd = act.getContentResolver().openAssetFileDescriptor(uri, "r");
            } catch (Throwable err) {
                Toast.makeText(act, "Не удалось прочитать аудиофайл", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(act, "Сохраняю образец голоса…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try (android.content.res.AssetFileDescriptor afd = stagedAfd) {
                    File dir = new File(act.getFilesDir(), "koliti_voice_ref");
                    if (!dir.exists()) dir.mkdirs();
                    File dst = new File(dir, "user_voice_ref_" + System.currentTimeMillis() + ".ogg");
                    try (InputStream is = afd != null ? afd.createInputStream() : act.getContentResolver().openInputStream(uri);
                         FileOutputStream fos = new FileOutputStream(dst)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while (is != null && (n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                    if (dst.length() > 256) {
                        ColgramAiConfig.setVoiceReferencePath(act, dst.getAbsolutePath());
                        AndroidUtilities.runOnUIThread(() -> {
                            Toast.makeText(act, "Образец голоса подключён: " + dst.getName(), Toast.LENGTH_SHORT).show();
                            refreshRow(voiceSampleRow);
                        });
                    }
                } catch (Throwable err) {
                    AndroidUtilities.runOnUIThread(() ->
                            Toast.makeText(act, "Ошибка импорта аудио: " + err.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "colgram-voice-ref-import").start();
            return;
        }
        if (requestCode == REQ_PICK_GGUF || requestCode == REQ_PICK_MMPROJ) {
            final android.content.res.AssetFileDescriptor stagedModelAfd;
            try {
                stagedModelAfd = act.getContentResolver().openAssetFileDescriptor(uri, "r");
            } catch (Throwable err) {
                Toast.makeText(act, "Не удалось прочитать файл модели", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(act, "Импорт файла в хранилище Koliti…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                File dst = null;
                try (android.content.res.AssetFileDescriptor afd = stagedModelAfd) {
                    File dir = ColgramAiModelManager.modelsRootDir(act);
                    String name = (requestCode == REQ_PICK_GGUF ? "custom_koliti_" : "custom_mmproj_")
                            + System.currentTimeMillis() + ".gguf";
                    dst = new File(dir, name);
                    try (InputStream is = afd != null ? afd.createInputStream() : act.getContentResolver().openInputStream(uri)) {
                        if (is == null) {
                            throw new java.io.IOException("Не удалось открыть поток файла");
                        }
                        try (FileOutputStream fos = new FileOutputStream(dst)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                fos.write(buf, 0, n);
                            }
                        }
                    }
                    if (dst.length() <= 1024) {
                        throw new java.io.IOException("Файл пуст или повреждён");
                    }
                    final File savedDst = dst;
                    if (requestCode == REQ_PICK_GGUF) {
                        ColgramAiConfig.setCustomModelPath(act, savedDst.getAbsolutePath());
                    } else {
                        ColgramAiConfig.setCustomMmprojPath(act, savedDst.getAbsolutePath());
                    }
                    ColgramAiEngine.getInstance().unloadModelAsync();
                    AndroidUtilities.runOnUIThread(() -> {
                        Toast.makeText(act, "Файл подключён: " + savedDst.getName(), Toast.LENGTH_SHORT).show();
                        refreshUi();
                    });
                } catch (Throwable err) {
                    if (dst != null) {
                        try { dst.delete(); } catch (Throwable ignored) {}
                    }
                    AndroidUtilities.runOnUIThread(() ->
                            Toast.makeText(act, "Ошибка импорта: " + err.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "colgram-gguf-import").start();
        }
    }

    private void showVoiceSamplePickerDialog(Context ctx) {
        Activity act = getParentActivity();
        if (act == null) return;
        java.util.List<ColgramUiKit.ChoiceItem> items = new java.util.ArrayList<>(4);
        items.add(ColgramUiKit.ChoiceItem.of("Записать свой голос прямо сейчас (ГС)", "10–15 секунд через микрофон", R.drawable.input_mic));
        items.add(ColgramUiKit.ChoiceItem.of("Выбрать из моих голосовых (ГС) в Telegram", "Локальный кэш голосовых", R.drawable.msg_folders));
        items.add(ColgramUiKit.ChoiceItem.of("Выбрать аудиофайл (.ogg / .wav / .mp3)", "Открыть системный проводник", R.drawable.msg_view_file));
        items.add(ColgramUiKit.ChoiceItem.of("Использовать встроенный голос Koliti", "Сбросить образец клон-озвучки", R.drawable.msg_retry));
        ColgramUiKit.showChoiceSheet(
                act,
                getResourceProvider(),
                R.drawable.input_mic,
                "Образец голоса для клонирования",
                "Выберите референс для персональной озвучки",
                items,
                which -> {
                    if (which == 0) {
                        showRecordVoiceSampleDialog(ctx);
                    } else if (which == 1) {
                        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                            final java.util.List<File> notes = org.colgram.core.ColgramKolitiVoice.findRecentTelegramVoiceNotes(ctx, 12);
                            AndroidUtilities.runOnUIThread(() -> {
                                Activity curAct = getParentActivity();
                                if (curAct == null) return;
                                if (notes.isEmpty()) {
                                    ColgramUiKit.showNotice(this, R.drawable.msg_info,
                                            "Локальные ГС не найдены — запишите образец или выберите файл", true);
                                    return;
                                }
                                String[] names = new String[notes.size()];
                                for (int i = 0; i < notes.size(); i++) {
                                    File f = notes.get(i);
                                    names[i] = f.getName() + " (" + Math.max(1L, f.length() / 1024L) + " КБ)";
                                }
                                ColgramUiKit.showChoiceSheet(
                                        curAct,
                                        getResourceProvider(),
                                        R.drawable.input_mic,
                                        "Выберите ваше голосовое (ГС)",
                                        null,
                                        names,
                                        -1,
                                        idx -> {
                                            File picked = notes.get(idx);
                                            ColgramAiConfig.setVoiceReferencePath(ctx, picked.getAbsolutePath());
                                            ColgramUiKit.showNotice(this, R.drawable.input_mic,
                                                    "Образец голоса выбран: " + picked.getName(), false);
                                            refreshRow(voiceSampleRow);
                                        }
                                );
                            });
                        });
                    } else if (which == 2) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("audio/*");
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivityForResult(Intent.createChooser(intent, "Образец голоса (.ogg / .wav)"), REQ_PICK_VOICE_REF);
                        } catch (Throwable t) {
                            launchFilePicker(REQ_PICK_VOICE_REF, "Образец голоса");
                        }
                    } else {
                        ColgramAiConfig.setVoiceReferencePath(ctx, "");
                        ColgramUiKit.showNotice(this, R.drawable.msg_retry, "Используется стандартный образец Koliti", false);
                        refreshRow(voiceSampleRow);
                    }
                }
        );
    }

    private void showRecordVoiceSampleDialog(Context ctx) {
        Activity act = getParentActivity();
        if (act == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 6411);
            ColgramUiKit.showNotice(this, R.drawable.input_mic, "Разрешите доступ к микрофону и нажмите снова", true);
            return;
        }
        File outFile = new File(new File(act.getFilesDir(), "koliti_voice_ref"), "my_voice_" + System.currentTimeMillis() + ".m4a");
        if (!org.colgram.core.ColgramKolitiVoice.startRecording(outFile)) {
            ColgramUiKit.showNotice(this, R.drawable.msg_info, "Не удалось запустить запись с микрофона", true);
            return;
        }
        org.telegram.ui.ActionBar.BottomSheet sheet = ColgramUiKit.showConfirmSheet(
                act,
                getResourceProvider(),
                R.drawable.input_mic,
                "Запись образца голоса",
                "Прочитайте спокойно 10–15 секунд текста своим обычным голосом — так нейросеть точнее передаст тембр и ударения.\n\nНажмите «Сохранить», когда закончите.",
                "Сохранить",
                false,
                () -> {
                    File saved = org.colgram.core.ColgramKolitiVoice.stopRecording();
                    if (saved != null && saved.isFile()) {
                        ColgramAiConfig.setVoiceReferencePath(ctx, saved.getAbsolutePath());
                        ColgramUiKit.showNotice(this, R.drawable.input_mic,
                                "Образец голоса сохранён (" + (saved.length() / 1024L) + " КБ)", false);
                        refreshRow(voiceSampleRow);
                    } else {
                        ColgramUiKit.showNotice(this, R.drawable.msg_info, "Запись слишком короткая", true);
                    }
                },
                "Отмена",
                () -> org.colgram.core.ColgramKolitiVoice.stopRecording()
        );
        if (sheet != null) {
            sheet.setOnDismissListener(d -> {
                if (org.colgram.core.ColgramKolitiVoice.isRecording()) {
                    org.colgram.core.ColgramKolitiVoice.stopRecording();
                }
            });
        } else if (org.colgram.core.ColgramKolitiVoice.isRecording()) {
            org.colgram.core.ColgramKolitiVoice.stopRecording();
        }
    }

    private void showVoiceCloneServerDialog(Context ctx) {
        Activity act = getParentActivity();
        if (act == null) return;
        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        LinearLayout form = new LinearLayout(act);
        form.setOrientation(LinearLayout.VERTICAL);

        org.telegram.ui.Components.EditTextBoldCursor urlInput = ColgramUiKit.makeStyledEditText(
                act, getResourceProvider(), pal,
                "URL сервера (XTTS / F5-TTS / Fish / ElevenLabs)",
                ColgramAiConfig.getVoiceCloneEndpoint(ctx),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI,
                true);
        form.addView(ColgramUiKit.makeInputContainer(act, pal, urlInput, false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        org.telegram.ui.Components.EditTextBoldCursor keyInput = ColgramUiKit.makeStyledEditText(
                act, getResourceProvider(), pal,
                "API-ключ (опционально для Fish Audio / ElevenLabs)",
                ColgramAiConfig.getVoiceCloneApiKey(ctx),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                true);
        form.addView(ColgramUiKit.makeInputContainer(act, pal, keyInput, true),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        org.telegram.ui.Components.EditTextBoldCursor vidInput = ColgramUiKit.makeStyledEditText(
                act, getResourceProvider(), pal,
                "Voice ID / Reference ID (опционально)",
                ColgramAiConfig.getVoiceCloneVoiceId(ctx),
                android.text.InputType.TYPE_CLASS_TEXT,
                true);
        form.addView(ColgramUiKit.makeInputContainer(act, pal, vidInput, false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ColgramUiKit.showCustomViewSheet(
                act,
                getResourceProvider(),
                R.drawable.input_mic,
                "Клонирование голоса Koliti",
                "Для точного 1-в-1 клонирования вашего голоса с русскими ударениями укажите адрес вашего XTTS-v2 / F5-TTS / Fish-Speech сервера (например http://192.168.0.10:8020/tts_to_audio/) либо ключ Fish Audio / ElevenLabs. Если оставить пустым — работает встроенная офлайн-озвучка Android Neural TTS.",
                form,
                "Сохранить",
                s -> {
                    ColgramAiConfig.setVoiceCloneEndpoint(ctx, urlInput.getText().toString().trim());
                    ColgramAiConfig.setVoiceCloneApiKey(ctx, keyInput.getText().toString().trim());
                    ColgramAiConfig.setVoiceCloneVoiceId(ctx, vidInput.getText().toString().trim());
                    ColgramUiKit.showNotice(this, R.drawable.input_mic, "Настройки озвучки сохранены", false);
                    refreshRow(voiceCloneEngineRow);
                    return true;
                },
                "Сбросить",
                s -> {
                    ColgramAiConfig.setVoiceCloneEndpoint(ctx, "");
                    ColgramAiConfig.setVoiceCloneApiKey(ctx, "");
                    ColgramAiConfig.setVoiceCloneVoiceId(ctx, "");
                    ColgramUiKit.showNotice(this, R.drawable.msg_retry, "Переключено на встроенную офлайн-озвучку", false);
                    refreshRow(voiceCloneEngineRow);
                    return true;
                },
                "Отмена",
                true,
                false
        );
    }

    private void refreshUi() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void refreshRow(int row) {
        if (adapter != null && row >= 0 && row < rowCount) {
            adapter.notifyItemChanged(row);
        }
    }

    private long getDeviceTotalRamMb(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return mi.totalMem / (1024L * 1024L);
            }
        } catch (Throwable ignored) {}
        return 4096L;
    }

    private final class KolitiCoreCardView extends FrameLayout {
        private final LinearLayout card;
        private final TextView titleTv;
        private final TextView descTv;
        private final TextView statusTv;
        private final ProgressBar progressBar;
        private final TextView primaryBtn;
        private final TextView secondaryBtn;

        KolitiCoreCardView(Context context) {
            super(context);
            setPadding(dp(14), dp(4), dp(14), dp(6));

            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(15), dp(16), dp(15));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            titleTv = new TextView(context);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            titleTv.setTypeface(AndroidUtilities.bold());
            card.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            descTv = new TextView(context);
            descTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            descTv.setLineSpacing(dp(2f), 1f);
            card.addView(descTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 10));

            statusTv = new TextView(context);
            statusTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
            statusTv.setTypeface(AndroidUtilities.bold());
            card.addView(statusTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

            progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.getProgressDrawable().setColorFilter(ColgramMenuChrome.COLOR_CRIMSON, PorterDuff.Mode.SRC_IN);
            card.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 5, 0, 0, 0, 12));

            LinearLayout btnRow = new LinearLayout(context);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            primaryBtn = makeButton(context, true);
            btnRow.addView(primaryBtn, LayoutHelper.createLinear(0, 42, 1.4f, 0, 0, 8, 0));

            secondaryBtn = makeButton(context, false);
            btnRow.addView(secondaryBtn, LayoutHelper.createLinear(0, 42, 1f));
        }

        private TextView makeButton(Context ctx, boolean primary) {
            TextView tv = new TextView(ctx);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            tv.setTypeface(AndroidUtilities.bold());
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(dp(12), 0, dp(12), 0);
            tv.setTextColor(primary ? Color.WHITE : 0xFFE2E8F0);
            if (primary) {
                ColgramMenuChrome.attachTactileFeedback(tv, 12, ColgramMenuChrome.COLOR_CRIMSON, 0, 0x33FFFFFF);
            } else {
                ColgramMenuChrome.attachTactileFeedback(tv, 12, 0x1CE11D48, 0x44E11D48, 0x26E11D48);
            }
            return tv;
        }

        void bind(Context ctx) {
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            boolean dark = pal.dark;
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            bg.setCornerRadius(dp(16));
            bg.setStroke(dp(1), pal.cyber ? pal.borderAccent : pal.borderSubtle);
            card.setBackground(bg);

            titleTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            descTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));

            ColgramMenuChrome.attachTactileFeedback(primaryBtn, 12, pal.accentPrimary, 0, 0x33FFFFFF);
            ColgramMenuChrome.attachTactileFeedback(secondaryBtn, 12,
                    dark ? 0x26F43F5E : 0x14E11D48,
                    dark ? 0x55FB7185 : 0x44E11D48,
                    0x28E11D48);
            secondaryBtn.setTextColor(dark ? 0xFFFDA4AF : pal.semanticCrimson);

            ColgramAiModels.ModelSpec spec = ColgramAiModels.findById(ColgramAiConfig.getActiveModelId(ctx));
            ColgramAiModelManager.ResolvedModelPaths paths = ColgramAiModelManager.resolveActiveModel(ctx);
            ColgramAiModelManager.DownloadState st = ColgramAiModelManager.getDownloadState();
            boolean downloading = ColgramAiModelManager.isDownloading() || st.active;
            boolean hasEmbedded = ColgramAiModelManager.hasEmbeddedAssets(ctx);
            boolean specEmbedded = spec.hasBundledAssets() && hasEmbedded;
            boolean hasCustom = !TextUtils.isEmpty(ColgramAiConfig.getCustomModelPath(ctx)) && paths.isReady();
            boolean activeFileReady = hasCustom || ColgramAiModelManager.isModelDownloaded(ctx, spec);
            boolean activeVisionReady = hasCustom ? paths.hasVision() : ColgramAiModelManager.isVisionDownloaded(ctx, spec);
            long partialBytes = ColgramAiModelManager.getPartialBytes(ctx, spec);

            titleTv.setText(hasCustom ? paths.displayName : spec.title);
            descTv.setText(spec.subtitle);

            if (downloading) {
                int pct = st.percent();
                progressBar.setVisibility(VISIBLE);
                progressBar.setIndeterminate(pct < 0);
                if (pct >= 0) {
                    progressBar.setProgress(pct);
                }
                statusTv.setTextColor(pal.semanticCrimson);
                String stage = st.phase.isEmpty() ? "Подготовка ядра…" : st.phase;
                if (pct >= 0 && st.totalBytes > 0) {
                    String sizePart = ColgramAiModelManager.formatSizeMb(st.downloadedBytes)
                            + " / " + ColgramAiModelManager.formatSizeMb(st.totalBytes);
                    if (st.speedMbPerSec > 0.05) {
                        statusTv.setText(String.format(Locale.US, "%s — %d%% (%s · %.1f МБ/с)",
                                stage, pct, sizePart, st.speedMbPerSec));
                    } else {
                        statusTv.setText(String.format(Locale.US, "%s — %d%% (%s)", stage, pct, sizePart));
                    }
                } else {
                    statusTv.setText(stage);
                }
                boolean extracting = st.extracting || ColgramAiModelManager.isExtractingEmbedded();
                primaryBtn.setText(extracting ? "Подготовка…" : "Пауза");
                primaryBtn.setOnClickListener(v -> {
                    if (!extracting) startOrToggleCoreDownload(ctx);
                });
                secondaryBtn.setVisibility(GONE);
            } else if (activeFileReady) {
                progressBar.setVisibility(GONE);
                boolean visionMissing = !activeVisionReady && (hasCustom || spec.supportsVision());
                statusTv.setTextColor(visionMissing ? pal.semanticCrimson : pal.semanticEmerald);
                boolean inRam = ColgramAiEngine.getInstance().isModelLoaded();
                String loadedNote = inRam ? " • Активно в RAM" : " • Спит (0 МБ в RAM / 0% CPU)";
                String visNote = activeVisionReady ? " (Текст + Vision)" : (visionMissing ? " (Только текст · Vision не установлен)" : " (Текст)");
                statusTv.setText((specEmbedded ? "Встроено офлайн" : "Установлено локально")
                        + visNote + loadedNote);
                primaryBtn.setText(inRam ? "Усыпить сейчас (0 МБ)" : "Открыть чат с " + org.colgram.core.ColgramDesignConfig.getKolitiDisplayName());
                primaryBtn.setOnClickListener(v -> {
                    if (ColgramAiEngine.getInstance().isModelLoaded()) {
                        ColgramAiEngine.getInstance().unloadModelAsync();
                        Toast.makeText(ctx, "Koliti переведена в сон — память освобождена", Toast.LENGTH_SHORT).show();
                        refreshRow(coreCardRow);
                        AndroidUtilities.runOnUIThread(() -> refreshRow(coreCardRow), 180L);
                        AndroidUtilities.runOnUIThread(() -> refreshRow(coreCardRow), 450L);
                    } else {
                        ColgramAiSheet.show(ColgramAiActivity.this, null, null);
                    }
                });
                if (specEmbedded && visionMissing && ColgramAiModelManager.hasEmbeddedVisionAsset(ctx)) {
                    secondaryBtn.setVisibility(VISIBLE);
                    secondaryBtn.setText("Распаковать Vision");
                    secondaryBtn.setOnClickListener(v -> {
                        ColgramAiModelManager.ensureEmbeddedReadyAsync(ctx, new ColgramAiModelManager.DownloadListener() {
                            @Override
                            public void onProgress(String phase, long downloadedBytes, long totalBytes, double speedMbPerSec) {}

                            @Override
                            public void onComplete(boolean success, String message) {
                                AndroidUtilities.runOnUIThread(() -> {
                                    Activity cur = getParentActivity();
                                    if (cur != null && !cur.isFinishing() && !cur.isDestroyed()) {
                                        Toast.makeText(cur, message, Toast.LENGTH_SHORT).show();
                                        refreshUi();
                                    }
                                });
                            }
                        });
                    });
                } else if (!specEmbedded && visionMissing && !hasCustom && spec.supportsVision()) {
                    statusTv.setText((specEmbedded ? "Встроено офлайн" : "Установлено локально")
                            + " (Vision не скачан — нажмите для загрузки)" + loadedNote);
                    statusTv.setOnClickListener(v -> startDownloadForSpec(ctx, spec));
                    secondaryBtn.setVisibility(VISIBLE);
                    secondaryBtn.setText("Удалить");
                    secondaryBtn.setOnClickListener(v -> confirmDeleteCore(ctx));
                } else {
                    statusTv.setOnClickListener(null);
                    secondaryBtn.setVisibility(specEmbedded ? GONE : VISIBLE);
                    secondaryBtn.setText("Удалить");
                    secondaryBtn.setOnClickListener(v -> confirmDeleteCore(ctx));
                }
            } else if (specEmbedded) {
                progressBar.setVisibility(GONE);
                statusTv.setTextColor(pal.semanticCyan);
                statusTv.setText("Встроено в APK (0 МБ интернета) • Готово к мгновенной распаковке");
                primaryBtn.setText("Активировать встроенное ядро");
                primaryBtn.setOnClickListener(v -> startOrToggleCoreDownload(ctx));
                secondaryBtn.setVisibility(GONE);
            } else if (partialBytes > 0L) {
                long total = spec.approxModelBytes > 0L ? spec.approxModelBytes : partialBytes;
                int pct = (int) Math.min(99L, (partialBytes * 100L) / Math.max(1L, total));
                progressBar.setVisibility(VISIBLE);
                progressBar.setIndeterminate(false);
                progressBar.setProgress(pct);
                boolean hasErr = !TextUtils.isEmpty(st.lastError) && !st.pausedByUser
                        && (st.modelId.isEmpty() || st.modelId.equals(spec.id));
                statusTv.setTextColor(hasErr ? pal.semanticCrimson : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                statusTv.setText((hasErr ? ("Пауза (" + st.lastError + ") • ") : "Приостановлено • ")
                        + ColgramAiModelManager.formatSizeMb(partialBytes) + " из " + ColgramAiModelManager.formatSizeMb(total)
                        + " (" + pct + "%)");
                primaryBtn.setText("Продолжить загрузку");
                primaryBtn.setOnClickListener(v -> startOrToggleCoreDownload(ctx));
                secondaryBtn.setVisibility(VISIBLE);
                secondaryBtn.setText("Сбросить");
                secondaryBtn.setOnClickListener(v -> {
                    ColgramAiModelManager.clearPartialDownload(ctx, spec);
                    refreshUi();
                });
            } else {
                progressBar.setVisibility(GONE);
                boolean hasErr = !TextUtils.isEmpty(st.lastError) && !st.pausedByUser
                        && (st.modelId.isEmpty() || st.modelId.equals(spec.id));
                statusTv.setTextColor(hasErr ? pal.semanticCrimson : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                statusTv.setText(hasErr ? ("Ошибка: " + st.lastError) : "Веса не загружены • Загрузка напрямую с GitHub (с докачкой)");
                primaryBtn.setText(hasErr ? "Повторить загрузку" : ("Скачать " + spec.title));
                primaryBtn.setOnClickListener(v -> startOrToggleCoreDownload(ctx));
                secondaryBtn.setVisibility(GONE);
            }
        }
    }

    private final class KolitiSystemInfoCardView extends FrameLayout {
        private final LinearLayout card;
        private final TextView titleTv;
        private final LinearLayout rowsBox;

        KolitiSystemInfoCardView(Context context) {
            super(context);
            setPadding(dp(14), dp(4), dp(14), dp(10));

            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(12));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            titleTv = new TextView(context);
            titleTv.setText("Системная информация и телеметрия");
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            titleTv.setTypeface(AndroidUtilities.bold());
            card.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

            rowsBox = new LinearLayout(context);
            rowsBox.setOrientation(LinearLayout.VERTICAL);
            card.addView(rowsBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(Context ctx) {
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    pal.dark ? new int[]{0xFF151926, 0xFF10131F} : new int[]{0xFFFFFFFF, 0xFFF8FAFC}
            );
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(1), pal.cyber ? pal.borderAccent : pal.borderSubtle);
            card.setBackground(bg);

            titleTv.setTextColor(pal.dark ? 0xFFFDA4AF : ColgramMenuChrome.COLOR_CRIMSON);
            rowsBox.removeAllViews();

            String nativeTxt = ColgramNativeLlama.getSystemInfoSafe();
            if (nativeTxt.length() > 54) nativeTxt = nativeTxt.substring(0, 54) + "…";
            long ramGb = Math.max(1L, getDeviceTotalRamMb(ctx) / 1024L);

            addRow(ctx, "Режим ответов",
                    ColgramAiConfig.isThinkingEnabled(ctx) ? "Глубокое мышление (CoT)" : "Турбо (мгновенный ответ)", pal, true);
            addRow(ctx, "Голосовой движок", org.colgram.core.ColgramKolitiVoice.getVoiceEngineSummary(ctx), pal, true);
            addRow(ctx, "Память устройства", ramGb + " ГБ RAM · автосон " + ColgramAiConfig.getIdleUnloadSeconds(ctx) + " сек", pal, true);
            addRow(ctx, "CPU-движок", nativeTxt, pal, true);
            addRow(ctx, "Пасхалки", "5× заголовок Colgram или .koliti", pal, false);
        }

        private void addRow(Context ctx, String label, String val, ColgramAeroStyler.Palette pal, boolean divider) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(6), 0, dp(6));

            TextView k = new TextView(ctx);
            k.setText(label);
            k.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            k.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            r.addView(k, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));

            TextView v = new TextView(ctx);
            v.setText(val);
            v.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            v.setTypeface(AndroidUtilities.bold());
            v.setGravity(Gravity.END);
            v.setMaxLines(1);
            v.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(v, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            rowsBox.addView(r, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            if (divider) {
                View d = new View(ctx);
                d.setBackgroundColor(pal.borderSubtle);
                rowsBox.addView(d, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
            }
        }
    }

    private final class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            this.mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            return pos != heroRow && pos != coreCardRow && pos != coreInfoRow
                    && pos != sectionVoiceRow && pos != sectionRuntimeRow && pos != sectionAdvancedRow && pos != footerShadowRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == heroRow) return 0;
            if (position == coreCardRow) return 6;
            if (position == sectionVoiceRow || position == sectionRuntimeRow || position == sectionAdvancedRow) return 1;
            if (position == voiceOverToggleRow || position == orbToggleRow || position == webSearchRow || position == crossChatRow
                    || position == thinkingRow || position == flashAttnRow) return 2;
            if (position == coreInfoRow) return 4;
            if (position == footerShadowRow) return 5;
            return 3;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new ColgramMenuChrome.HeroBannerCell(mContext);
                    break;
                case 1:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 2:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 3:
                    view = new TextDetailSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 4:
                    view = new KolitiSystemInfoCardView(mContext);
                    break;
                case 6:
                    view = new KolitiCoreCardView(mContext);
                    break;
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == 0) {
                ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                ColgramAiModelManager.ResolvedModelPaths active = ColgramAiModelManager.resolveActiveModel(mContext);
                boolean hasEmbedded = ColgramAiModelManager.hasEmbeddedAssets(mContext);
                long ramGb = Math.max(1L, getDeviceTotalRamMb(mContext) / 1024L);
                String pill = (active.isReady() || hasEmbedded) ? "ОФЛАЙН" : "ОНЛАЙН";
                hero.bind(
                        R.drawable.koliti_avatar,
                        ColgramMenuChrome.COLOR_CRIMSON,
                        "Koliti Core",
                        "Локальная модель для текста, фото и чатов",
                        pill,
                        "RAM: " + ramGb + " ГБ",
                        "Контекст до 32K",
                        ColgramNativeLlama.isLoaded() ? "Нативное ядро" : "CPU"
                );
            } else if (type == 6) {
                ((KolitiCoreCardView) holder.itemView).bind(mContext);
            } else if (type == 1) {
                HeaderCell h = (HeaderCell) holder.itemView;
                if (position == sectionVoiceRow) {
                    h.setText("Голос и озвучка Koliti");
                    ColgramMenuChrome.styleHeader(h, ColgramMenuChrome.COLOR_CRIMSON, 0xFFFB7185);
                } else if (position == sectionRuntimeRow) {
                    h.setText("Поведение и производительность");
                    ColgramMenuChrome.styleHeader(h, ColgramMenuChrome.COLOR_CRIMSON, 0xFFFB7185);
                } else {
                    h.setText("Ручное подключение локальной модели");
                    ColgramMenuChrome.styleHeader(h, ColgramMenuChrome.COLOR_SLATE, 0xFF94A3B8);
                }
            } else if (type == 2) {
                TextCheckCell c = (TextCheckCell) holder.itemView;
                if (position == voiceOverToggleRow) {
                    c.setTextAndValueAndCheck("Режим озвучки ответов", "Автоматически озвучивать ответы Koliti в чате", ColgramAiConfig.isVoiceOverEnabled(mContext), true, true);
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_CRIMSON, R.drawable.msg_voice_speaker);
                } else if (position == orbToggleRow) {
                    c.setTextAndValueAndCheck("Плавающий кружок Koliti", "Свободно перемещается по экранам и чатам", ColgramAiConfig.isOrbEnabled(mContext), true, true);
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_CRIMSON, R.drawable.msg_bot);
                } else if (position == webSearchRow) {
                    c.setTextAndValueAndCheck("Веб-синтез через Koliti", "Данные из сети очищаются и передаются в ИИ", ColgramAiConfig.isWebSearchEnabled(mContext), true, true);
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_BLUE, R.drawable.msg_search);
                } else if (position == crossChatRow) {
                    c.setTextAndValueAndCheck("Контекст всех чатов (BM25)", "Поиск важных фактов по всей истории переписок", ColgramAiConfig.isCrossChatRagEnabled(mContext), true, true);
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_TEAL, R.drawable.msg_folders);
                } else if (position == thinkingRow) {
                    c.setTextAndValueAndCheck("Глубокое мышление (CoT)", "Выкл = Турбо-режим (мгновенный ответ); вкл = пошаговые рассуждения", ColgramAiConfig.isThinkingEnabled(mContext), true, true);
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_INDIGO, R.drawable.msg_info);
                } else if (position == flashAttnRow) {
                    c.setTextAndValueAndCheck("FlashAttention + Zero-Copy mmap", "Ускоряет большой контекст и снижает нагрев", ColgramAiConfig.isFlashAttentionEnabled(mContext), true, true);
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_AMBER, R.drawable.msg_speed);
                }
            } else if (type == 3) {
                TextDetailSettingsCell d = (TextDetailSettingsCell) holder.itemView;
                if (position == modelPickerRow) {
                    ColgramAiModels.ModelSpec activeSpec = ColgramAiModels.findById(ColgramAiConfig.getActiveModelId(mContext));
                    String val = activeSpec.title + " • " + ColgramAiModels.catalog().size() + " оптимизированных версии";
                    ColgramMenuChrome.applyDetailIcon(d, "Модель Колити", val, R.drawable.msg_bot, ColgramMenuChrome.COLOR_CRIMSON, true);
                } else if (position == designCustomizeRow) {
                    int sz = org.colgram.core.ColgramDesignConfig.getKolitiOrbSizeDp();
                    boolean hasCustomAva = !TextUtils.isEmpty(org.colgram.core.ColgramDesignConfig.getKolitiCustomAvatarPath());
                    String val = "Размер кнопки: " + sz + "dp • " + (hasCustomAva ? "Свой аватар" : "Стандартный аватар") + " • Конструктор дизайна";
                    ColgramMenuChrome.applyDetailIcon(d, "Аватар, размер кнопки и дизайн", val, R.drawable.msg_theme, ColgramMenuChrome.COLOR_INDIGO, true);
                } else if (position == idleSleepRow) {
                    int sec = ColgramAiConfig.getIdleUnloadSeconds(mContext);
                    String val = sec <= 0 ? "Отключён (держит веса в RAM)" : (sec < 120 ? (sec + " сек (0% нагрузки в простое)") : ((sec / 60) + " мин (экономия батареи)"));
                    ColgramMenuChrome.applyDetailIcon(d, "Спящий режим (автовыгрузка)", val, R.drawable.msg_speed, ColgramMenuChrome.COLOR_EMERALD, true);
                } else if (position == voiceSampleRow) {
                    String refPath = ColgramAiConfig.getVoiceReferencePath(mContext);
                    String val = TextUtils.isEmpty(refPath) ? "Встроенный голос Koliti (можно записать или выбрать своё ГС)" : new File(refPath).getName();
                    ColgramMenuChrome.applyDetailIcon(d, "Образец моего голоса (ГС)", val, R.drawable.input_mic, ColgramMenuChrome.COLOR_ROSE, true);
                } else if (position == voiceCloneEngineRow) {
                    String ep = ColgramAiConfig.getVoiceCloneEndpoint(mContext);
                    String key = ColgramAiConfig.getVoiceCloneApiKey(mContext);
                    String val = !TextUtils.isEmpty(ep) ? ("Клонирование: " + ep)
                            : (!TextUtils.isEmpty(key) ? "Облачное клонирование голоса (активно)"
                                    : org.colgram.core.ColgramKolitiVoice.getVoiceEngineSummary(mContext));
                    ColgramMenuChrome.applyDetailIcon(d, "Движок клонирования голоса", val, R.drawable.msg_voicechat, ColgramMenuChrome.COLOR_INDIGO, true);
                } else if (position == voiceTestRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Проверить озвучку", "Произнести тестовую фразу с проверкой ударений", R.drawable.msg_voice_speaker, ColgramMenuChrome.COLOR_EMERALD, false);
                } else if (position == ctxSizeRow) {
                    int ov = ColgramAiConfig.getContextSizeOverride(mContext);
                    String val = ov == 0 ? "Авто (Турбо: 2K текст / 3K Vision)" : (ov / 1024) + "K токенов";
                    ColgramMenuChrome.applyDetailIcon(d, "Окно контекста", val, R.drawable.msg_folders, ColgramMenuChrome.COLOR_BLUE, true);
                } else if (position == kvQuantRow) {
                    int kv = ColgramAiConfig.getKvQuantBits(mContext);
                    ColgramMenuChrome.applyDetailIcon(d, "Квантование KV-кэша", kv == 16 ? "FP16" : ("Q" + kv + "_0 (экономия RAM)"), R.drawable.msg_speed, ColgramMenuChrome.COLOR_EMERALD, true);
                } else if (position == threadsRow) {
                    int th = ColgramAiConfig.getThreadsOverride(mContext);
                    ColgramMenuChrome.applyDetailIcon(d, "Потоки CPU", th == 0 ? "Авто (быстрые big-ядра)" : th + " потока", R.drawable.msg_settings, ColgramMenuChrome.COLOR_AMBER, false);
                } else if (position == importGgufRow) {
                    String custom = ColgramAiConfig.getCustomModelPath(mContext);
                    ColgramMenuChrome.applyDetailIcon(d, "Подключить свой файл модели", TextUtils.isEmpty(custom) ? ("По умолчанию: " + ColgramAiModels.primary().title) : new File(custom).getName(), R.drawable.msg_download, ColgramMenuChrome.COLOR_SLATE, true);
                } else if (position == importMmprojRow) {
                    String mm = ColgramAiConfig.getCustomMmprojPath(mContext);
                    ColgramMenuChrome.applyDetailIcon(d, "Подключить Vision mmproj", TextUtils.isEmpty(mm) ? "Встроенный Vision-энкодер для фото и видео" : new File(mm).getName(), R.drawable.msg_media, ColgramMenuChrome.COLOR_SLATE, true);
                } else if (position == resetCustomRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Сбросить к " + ColgramAiModels.primary().title, "Вернуть оптимизированное ядро по умолчанию", R.drawable.msg_clear, ColgramMenuChrome.COLOR_ROSE, false);
                }
            } else if (type == 4) {
                ((KolitiSystemInfoCardView) holder.itemView).bind(mContext);
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, TextCheckCell.class, TextDetailSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextDetailSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextDetailSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class, ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ColgramMenuChrome.HeroBannerCell.class, KolitiCoreCardView.class, KolitiSystemInfoCardView.class}, null, null, () -> {
            if (listView != null) {
                for (int i = 0; i < listView.getChildCount(); i++) {
                    View ch = listView.getChildAt(i);
                    if (ch instanceof ColgramMenuChrome.HeroBannerCell) {
                        ((ColgramMenuChrome.HeroBannerCell) ch).updateTheme();
                    } else if (ch instanceof KolitiCoreCardView && getParentActivity() != null) {
                        ((KolitiCoreCardView) ch).bind(getParentActivity());
                    } else if (ch instanceof KolitiSystemInfoCardView && getParentActivity() != null) {
                        ((KolitiSystemInfoCardView) ch).bind(getParentActivity());
                    }
                }
            }
        }, Theme.key_windowBackgroundWhite));
        return themeDescriptions;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
