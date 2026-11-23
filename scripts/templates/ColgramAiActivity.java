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
import org.telegram.ui.ActionBar.AlertDialog;
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
 * Koliti Studio — single-model control center for Koliti Core 4B:
 * - Manage compact imatrix Qwen3.5-4B Hybrid Gated DeltaNet + Vision (mmproj) weights
 * - Live download progress, RAM unload, thermal CPU threads, quantized KV cache & FlashAttention
 * - Zero multi-model clutter, zero emoji spam
 */
public class ColgramAiActivity extends BaseFragment {

    public static final int REQ_PICK_GGUF = 6401;
    public static final int REQ_PICK_MMPROJ = 6402;
    public static final int REQ_PICK_MEDIA_FROM_SHEET = 6403;
    public static final int REQ_VOICE_RECOGNIZE = 6404;
    public static final int REQ_PICK_VOICE_REF = 6405;
    public static ColgramAiSheet pendingSheetForMedia;
    public static ColgramAiSheet pendingSheetForVoice;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private int rowCount;
    private int heroRow;
    private int coreCardRow;
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

    private String liveDownloadStage = "";
    private int liveDownloadPct = 0;
    private double liveDownloadSpeed = 0.0;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        heroRow = rowCount++;
        coreCardRow = rowCount++;
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
            showDialog(new AlertDialog.Builder(ctx, getResourceProvider())
                    .setTitle("Спящий режим Koliti")
                    .setItems(labels, (d, which) -> {
                        ColgramAiConfig.setIdleUnloadSeconds(ctx, vals[which]);
                        refreshRow(idleSleepRow);
                    }).create());
            return;
        }
        if (position == voiceOverToggleRow) {
            boolean next = !ColgramAiConfig.isVoiceOverEnabled(ctx);
            ColgramAiConfig.setVoiceOverEnabled(ctx, next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            Toast.makeText(ctx, next ? "Режим озвучки ответов Koliti включён" : "Озвучка ответов отключена", Toast.LENGTH_SHORT).show();
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
            final int[] vals = {0, 4096, 8192, 16384, 32768};
            final String[] labels = {
                    "Авто (по свободной RAM)",
                    "4K токенов (минимальная память)",
                    "8K токенов (баланс)",
                    "16K токенов (длинные чаты)",
                    "32K токенов (максимум)"
            };
            showDialog(new AlertDialog.Builder(ctx, getResourceProvider())
                    .setTitle("Размер контекста Koliti")
                    .setItems(labels, (d, which) -> {
                        ColgramAiConfig.setContextSizeOverride(ctx, vals[which]);
                        refreshRow(ctxSizeRow);
                    }).create());
            return;
        }
        if (position == kvQuantRow) {
            final int[] vals = {8, 4, 16};
            final String[] labels = {
                    "Q8_0 (рекомендуется • точный и компактный)",
                    "Q4_0 (ультра-экономия памяти для 32K)",
                    "FP16 (без сжатия)"
            };
            showDialog(new AlertDialog.Builder(ctx, getResourceProvider())
                    .setTitle("Квантование KV-кэша")
                    .setItems(labels, (d, which) -> {
                        ColgramAiConfig.setKvQuantBits(ctx, vals[which]);
                        refreshRow(kvQuantRow);
                    }).create());
            return;
        }
        if (position == threadsRow) {
            final int[] vals = {0, 2, 3, 4, 6};
            final String[] labels = {
                    "Авто (без перегрева)",
                    "2 потока (экономичный)",
                    "3 потока (тихий)",
                    "4 потока (сбалансированный)",
                    "6 потоков (максимальная скорость)"
            };
            showDialog(new AlertDialog.Builder(ctx, getResourceProvider())
                    .setTitle("Потоки CPU для Koliti")
                    .setItems(labels, (d, which) -> {
                        ColgramAiConfig.setThreadsOverride(ctx, vals[which]);
                        refreshRow(threadsRow);
                    }).create());
            return;
        }
        if (position == importGgufRow) {
            launchFilePicker(REQ_PICK_GGUF, "Выберите свой .gguf файл");
            return;
        }
        if (position == importMmprojRow) {
            launchFilePicker(REQ_PICK_MMPROJ, "Выберите Vision mmproj .gguf");
            return;
        }
        if (position == resetCustomRow) {
            ColgramAiConfig.setCustomModelPath(ctx, "");
            ColgramAiConfig.setCustomMmprojPath(ctx, "");
            ColgramAiConfig.setActiveModelId(ctx, ColgramAiModels.KOLITI_MODEL_ID);
            ColgramAiEngine.getInstance().unloadModelAsync();
            Toast.makeText(ctx, "Возвращено встроенное ядро Koliti Core Ultra", Toast.LENGTH_SHORT).show();
            refreshUi();
        }
    }

    private void startOrToggleCoreDownload(Context ctx) {
        if (ColgramAiModelManager.isDownloading()) {
            ColgramAiModelManager.cancelDownload();
            liveDownloadStage = "";
            liveDownloadPct = 0;
            refreshUi();
            return;
        }
        startDownloadForSpec(ctx, ColgramAiModels.primary());
    }

    private void startDownloadForSpec(Context ctx, ColgramAiModels.ModelSpec spec) {
        liveDownloadStage = "Подготовка " + spec.title + "…";
        liveDownloadPct = 0;
        refreshUi();

        ColgramAiModelManager.startDownloadAsync(ctx, spec, true, new ColgramAiModelManager.DownloadListener() {
            @Override
            public void onProgress(String stage, long downloadedBytes, long totalBytes, double speedMbSec) {
                AndroidUtilities.runOnUIThread(() -> {
                    liveDownloadStage = stage;
                    liveDownloadSpeed = speedMbSec;
                    liveDownloadPct = totalBytes > 0 ? (int) Math.min(100, (downloadedBytes * 100L) / totalBytes) : 0;
                    refreshRow(coreCardRow);
                });
            }

            @Override
            public void onComplete(boolean success, String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    liveDownloadStage = "";
                    liveDownloadPct = 0;
                    Toast.makeText(ctx, success ? ("Готово: " + spec.title) : ("Ошибка загрузки: " + message), Toast.LENGTH_LONG).show();
                    refreshUi();
                });
            }
        });
    }

    private void confirmDeleteCore(Context ctx) {
        final ColgramAiModels.ModelSpec spec = ColgramAiModels.findById(ColgramAiConfig.getActiveModelId(ctx));
        long mb = Math.max(1L, (spec.approxModelBytes + spec.approxMmprojBytes) / (1024L * 1024L));
        showDialog(new AlertDialog.Builder(ctx, getResourceProvider())
                .setTitle("Удалить веса " + spec.title + "?")
                .setMessage("Освободится ~" + mb + " МБ памяти. Вы сможете загрузить модель повторно в любой момент или переключиться на встроенную Koliti Core Ultra.")
                .setPositiveButton("Удалить", (d, w) -> {
                    ColgramAiEngine.getInstance().unloadModelAsync();
                    ColgramAiModelManager.deleteModelFiles(ctx, spec);
                    if (!ColgramAiModels.KOLITI_MODEL_ID.equals(spec.id)) {
                        ColgramAiConfig.setActiveModelId(ctx, ColgramAiModels.KOLITI_MODEL_ID);
                    }
                    refreshUi();
                })
                .setNegativeButton("Отмена", null)
                .create());
    }

    private void launchFilePicker(int reqCode, String chooserTitle) {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, chooserTitle), reqCode);
        } catch (Throwable t) {
            Toast.makeText(getParentActivity(), "Не удалось открыть проводник", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Activity act = getParentActivity();
        if (act == null) return;
        Uri uri = data.getData();

        if (requestCode == REQ_PICK_MEDIA_FROM_SHEET && pendingSheetForMedia != null) {
            pendingSheetForMedia.attachExternalMediaUri(act, uri);
            pendingSheetForMedia = null;
            return;
        }
        if (requestCode == REQ_PICK_VOICE_REF) {
            Toast.makeText(act, "Сохраняю образец голоса…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    File dir = new File(act.getFilesDir(), "koliti_voice_ref");
                    if (!dir.exists()) dir.mkdirs();
                    File dst = new File(dir, "user_voice_ref_" + System.currentTimeMillis() + ".ogg");
                    try (InputStream is = act.getContentResolver().openInputStream(uri);
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
            Toast.makeText(act, "Импорт файла в хранилище Koliti…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                File dst = null;
                try {
                    File dir = ColgramAiModelManager.modelsRootDir(act);
                    String name = (requestCode == REQ_PICK_GGUF ? "custom_koliti_" : "custom_mmproj_")
                            + System.currentTimeMillis() + ".gguf";
                    dst = new File(dir, name);
                    try (InputStream is = act.getContentResolver().openInputStream(uri)) {
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
        final String[] options = {
                "Записать свой голос прямо сейчас (ГС)",
                "Выбрать из моих голосовых (ГС) в Telegram",
                "Выбрать аудиофайл (.ogg / .wav / .mp3)",
                "Использовать встроенный голос Koliti"
        };
        showDialog(new AlertDialog.Builder(ctx, getResourceProvider())
                .setTitle("Образец голоса для клонирования")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showRecordVoiceSampleDialog(ctx);
                    } else if (which == 1) {
                        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                            final java.util.List<File> notes = org.colgram.core.ColgramKolitiVoice.findRecentTelegramVoiceNotes(ctx, 12);
                            AndroidUtilities.runOnUIThread(() -> {
                                if (getParentActivity() == null) return;
                                if (notes.isEmpty()) {
                                    Toast.makeText(ctx, "Локальные ГС не найдены — запишите образец или выберите файл", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                String[] names = new String[notes.size()];
                                for (int i = 0; i < notes.size(); i++) {
                                    File f = notes.get(i);
                                    names[i] = f.getName() + " (" + Math.max(1L, f.length() / 1024L) + " КБ)";
                                }
                                showDialog(new AlertDialog.Builder(ctx, getResourceProvider())
                                        .setTitle("Выберите ваше голосовое (ГС)")
                                        .setItems(names, (d2, idx) -> {
                                            File picked = notes.get(idx);
                                            ColgramAiConfig.setVoiceReferencePath(ctx, picked.getAbsolutePath());
                                            Toast.makeText(ctx, "Образец голоса выбран: " + picked.getName(), Toast.LENGTH_SHORT).show();
                                            refreshRow(voiceSampleRow);
                                        }).create());
                            });
                        });
                    } else if (which == 2) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("audio/*");
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            startActivityForResult(Intent.createChooser(intent, "Образец голоса (.ogg / .wav)"), REQ_PICK_VOICE_REF);
                        } catch (Throwable t) {
                            launchFilePicker(REQ_PICK_VOICE_REF, "Образец голоса");
                        }
                    } else {
                        ColgramAiConfig.setVoiceReferencePath(ctx, "");
                        Toast.makeText(ctx, "Используется стандартный образец Koliti", Toast.LENGTH_SHORT).show();
                        refreshRow(voiceSampleRow);
                    }
                }).create());
    }

    private void showRecordVoiceSampleDialog(Context ctx) {
        Activity act = getParentActivity();
        if (act == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 6411);
            Toast.makeText(ctx, "Разрешите доступ к микрофону и нажмите снова", Toast.LENGTH_SHORT).show();
            return;
        }
        File outFile = new File(new File(act.getFilesDir(), "koliti_voice_ref"), "my_voice_" + System.currentTimeMillis() + ".m4a");
        if (!org.colgram.core.ColgramKolitiVoice.startRecording(outFile)) {
            Toast.makeText(ctx, "Не удалось запустить запись с микрофона", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(ctx, getResourceProvider())
                .setTitle("🎙 Запись образца голоса")
                .setMessage("Прочитайте спокойно 10–15 секунд текста своим обычным голосом — так нейросеть точнее передаст тембр и ударения.\n\nНажмите «Сохранить», когда закончите.")
                .setPositiveButton("Сохранить", (d, w) -> {
                    File saved = org.colgram.core.ColgramKolitiVoice.stopRecording();
                    if (saved != null && saved.isFile()) {
                        ColgramAiConfig.setVoiceReferencePath(ctx, saved.getAbsolutePath());
                        Toast.makeText(ctx, "Образец голоса сохранён (" + (saved.length() / 1024L) + " КБ)", Toast.LENGTH_SHORT).show();
                        refreshRow(voiceSampleRow);
                    } else {
                        Toast.makeText(ctx, "Запись слишком короткая", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", (d, w) -> org.colgram.core.ColgramKolitiVoice.stopRecording())
                .create();
        android.app.Dialog shown = showDialog(dlg, d -> {
            if (org.colgram.core.ColgramKolitiVoice.isRecording()) {
                org.colgram.core.ColgramKolitiVoice.stopRecording();
            }
        });
        if (shown == null && org.colgram.core.ColgramKolitiVoice.isRecording()) {
            org.colgram.core.ColgramKolitiVoice.stopRecording();
        }
    }

    private void showVoiceCloneServerDialog(Context ctx) {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(4));

        TextView hint = new TextView(ctx);
        hint.setText("Для точного 1-в-1 клонирования вашего голоса с русскими ударениями укажите адрес вашего XTTS-v2 / F5-TTS / Fish-Speech сервера (например http://192.168.0.10:8020/tts_to_audio/) либо ключ Fish Audio / ElevenLabs.\n\nЕсли оставить пустым — работает встроенная офлайн-озвучка Android Neural TTS.");
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        form.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        android.widget.EditText urlInput = new android.widget.EditText(ctx);
        urlInput.setHint("URL сервера (XTTS / F5-TTS / Fish / ElevenLabs)");
        urlInput.setText(ColgramAiConfig.getVoiceCloneEndpoint(ctx));
        urlInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        urlInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        urlInput.setSingleLine(true);
        form.addView(urlInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        android.widget.EditText keyInput = new android.widget.EditText(ctx);
        keyInput.setHint("API-ключ (опционально для Fish Audio / ElevenLabs)");
        keyInput.setText(ColgramAiConfig.getVoiceCloneApiKey(ctx));
        keyInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        keyInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        keyInput.setSingleLine(true);
        form.addView(keyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        android.widget.EditText vidInput = new android.widget.EditText(ctx);
        vidInput.setHint("Voice ID / Reference ID (опционально)");
        vidInput.setText(ColgramAiConfig.getVoiceCloneVoiceId(ctx));
        vidInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        vidInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        vidInput.setSingleLine(true);
        form.addView(vidInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        showDialog(new AlertDialog.Builder(ctx, getResourceProvider())
                .setTitle("Клонирование голоса Koliti")
                .setView(form)
                .setPositiveButton("Сохранить", (d, w) -> {
                    ColgramAiConfig.setVoiceCloneEndpoint(ctx, urlInput.getText().toString().trim());
                    ColgramAiConfig.setVoiceCloneApiKey(ctx, keyInput.getText().toString().trim());
                    ColgramAiConfig.setVoiceCloneVoiceId(ctx, vidInput.getText().toString().trim());
                    Toast.makeText(ctx, "Настройки озвучки сохранены", Toast.LENGTH_SHORT).show();
                    refreshRow(voiceCloneEngineRow);
                })
                .setNeutralButton("Сбросить", (d, w) -> {
                    ColgramAiConfig.setVoiceCloneEndpoint(ctx, "");
                    ColgramAiConfig.setVoiceCloneApiKey(ctx, "");
                    ColgramAiConfig.setVoiceCloneVoiceId(ctx, "");
                    Toast.makeText(ctx, "Переключено на встроенную офлайн-озвучку", Toast.LENGTH_SHORT).show();
                    refreshRow(voiceCloneEngineRow);
                })
                .setNegativeButton("Отмена", null)
                .create());
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

            ColgramAiModels.ModelSpec spec = ColgramAiModels.primary();
            ColgramAiModelManager.ResolvedModelPaths paths = ColgramAiModelManager.resolveActiveModel(ctx);
            boolean downloading = ColgramAiModelManager.isDownloading();
            boolean hasEmbedded = ColgramAiModelManager.hasEmbeddedAssets(ctx);
            boolean activeFileReady = paths.isReady();

            titleTv.setText(org.colgram.core.ColgramDesignConfig.getKolitiDisplayName() + " Core Ultra");
            descTv.setText(spec.subtitle);

            if (downloading) {
                progressBar.setVisibility(VISIBLE);
                progressBar.setIndeterminate(liveDownloadPct <= 0);
                progressBar.setProgress(liveDownloadPct);
                statusTv.setTextColor(pal.semanticCrimson);
                statusTv.setText(liveDownloadPct > 0
                        ? String.format(Locale.US, "%s — %d%% (%.1f МБ/с)", liveDownloadStage, liveDownloadPct, liveDownloadSpeed)
                        : (liveDownloadStage.isEmpty() ? "Подготовка ядра…" : liveDownloadStage));
                primaryBtn.setText(hasEmbedded ? "Подготовка…" : "Остановить загрузку");
                primaryBtn.setOnClickListener(v -> {
                    if (!hasEmbedded) startOrToggleCoreDownload(ctx);
                });
                secondaryBtn.setVisibility(GONE);
            } else if (activeFileReady) {
                progressBar.setVisibility(GONE);
                statusTv.setTextColor(pal.semanticEmerald);
                boolean inRam = ColgramAiEngine.getInstance().isModelLoaded();
                String loadedNote = inRam ? " • Активно в RAM" : " • Спит (0 МБ в RAM / 0% CPU)";
                statusTv.setText((hasEmbedded ? "Встроено офлайн" : "Установлено локально")
                        + (paths.hasVision() ? " (Текст + Vision)" : " (Текст)") + loadedNote);
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
                secondaryBtn.setVisibility(hasEmbedded ? GONE : VISIBLE);
                secondaryBtn.setText("Удалить");
                secondaryBtn.setOnClickListener(v -> confirmDeleteCore(ctx));
            } else if (hasEmbedded) {
                progressBar.setVisibility(GONE);
                statusTv.setTextColor(pal.semanticCyan);
                statusTv.setText("Встроено в APK (0 МБ интернета) • Готово к мгновенной распаковке");
                primaryBtn.setText("Активировать встроенное ядро");
                primaryBtn.setOnClickListener(v -> startOrToggleCoreDownload(ctx));
                secondaryBtn.setVisibility(GONE);
            } else {
                progressBar.setVisibility(GONE);
                statusTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                statusTv.setText("Веса не загружены • Доступна загрузка с докачкой");
                primaryBtn.setText("Скачать Koliti Core Ultra");
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

            addRow(ctx, "Мышление", "MAX · всегда активно", pal, true);
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
                    h.setText("Ручное подключение весов (GGUF)");
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
                    c.setTextAndValueAndCheck("Показывать ход рассуждений", "Глубокое мышление MAX всегда активно; тумблер раскрывает мысли", ColgramAiConfig.isThinkingEnabled(mContext), true, true);
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_INDIGO, R.drawable.msg_info);
                } else if (position == flashAttnRow) {
                    c.setTextAndValueAndCheck("FlashAttention + Zero-Copy mmap", "Ускоряет большой контекст и снижает нагрев", ColgramAiConfig.isFlashAttentionEnabled(mContext), true, true);
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_AMBER, R.drawable.msg_speed);
                }
            } else if (type == 3) {
                TextDetailSettingsCell d = (TextDetailSettingsCell) holder.itemView;
                if (position == designCustomizeRow) {
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
                    String val = ov == 0 ? "Авто (4K – 32K по свободной RAM)" : (ov / 1024) + "K токенов";
                    ColgramMenuChrome.applyDetailIcon(d, "Окно контекста", val, R.drawable.msg_folders, ColgramMenuChrome.COLOR_BLUE, true);
                } else if (position == kvQuantRow) {
                    int kv = ColgramAiConfig.getKvQuantBits(mContext);
                    ColgramMenuChrome.applyDetailIcon(d, "Квантование KV-кэша", kv == 16 ? "FP16" : ("Q" + kv + "_0 (экономия RAM)"), R.drawable.msg_speed, ColgramMenuChrome.COLOR_EMERALD, true);
                } else if (position == threadsRow) {
                    int th = ColgramAiConfig.getThreadsOverride(mContext);
                    ColgramMenuChrome.applyDetailIcon(d, "Потоки CPU", th == 0 ? "Авто (термо-контроль)" : th + " потока", R.drawable.msg_settings, ColgramMenuChrome.COLOR_AMBER, false);
                } else if (position == importGgufRow) {
                    String custom = ColgramAiConfig.getCustomModelPath(mContext);
                    ColgramMenuChrome.applyDetailIcon(d, "Подключить свой .gguf", TextUtils.isEmpty(custom) ? "По умолчанию используется встроенная Koliti Core Ultra" : new File(custom).getName(), R.drawable.msg_download, ColgramMenuChrome.COLOR_SLATE, true);
                } else if (position == importMmprojRow) {
                    String mm = ColgramAiConfig.getCustomMmprojPath(mContext);
                    ColgramMenuChrome.applyDetailIcon(d, "Подключить Vision mmproj", TextUtils.isEmpty(mm) ? "Встроенный Vision-энкодер для фото и видео" : new File(mm).getName(), R.drawable.msg_media, ColgramMenuChrome.COLOR_SLATE, true);
                } else if (position == resetCustomRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Сбросить к Koliti Core Ultra", "Вернуть встроенное ядро и настройки", R.drawable.msg_clear, ColgramMenuChrome.COLOR_ROSE, false);
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
