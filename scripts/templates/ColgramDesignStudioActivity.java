package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramBuildFlags;
import org.colgram.core.ColgramDesignConfig;
import org.colgram.core.ColgramThemePolicy;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Full Interactive UI & Layout Constructor ("Конструктор дизайна"):
 * - Live real-time preview of header, chat cards, accent colors, surfaces, and Koliti floating orb
 * - Accent color & surface swatches + custom HEX input
 * - Chat card style, corner radius slider (4–24dp), neon glow intensity, divider toggle
 * - Header & Quick Bar layout constructor (Quick Bar placement, Shield visibility, custom title)
 * - Koliti Orb size slider (38–76dp), opacity slider (45–100%), dock side, custom avatar photo & name
 * - Full JSON export / import & one-tap reset
 */
public class ColgramDesignStudioActivity extends BaseFragment {

    private static final int REQ_PICK_KOLITI_AVATAR = 6511;

    private static final int[] ACCENT_SWATCHES = {
            0,          // Auto / Theme Default
            0xFFE11D48, // Crimson
            0xFFFF3344, // Cyber Red
            0xFF06B6D4, // Neon Cyan
            0xFF10B981, // Emerald
            0xFF8B5CF6, // Violet
            0xFF3B82F6, // Electric Blue
            0xFFF59E0B, // Amber Gold
            0xFFEC4899  // Sakura Pink
    };

    private static final String[] ACCENT_NAMES = {
            "Авто", "Кримсон", "Кибер", "Циан", "Изумруд", "Фиолет", "Синий", "Янтарь", "Розовый"
    };

    private static final int[] SURFACE_PRESET_IDS = {
            ColgramDesignConfig.SURFACE_AUTO,
            ColgramDesignConfig.SURFACE_OLED_BLACK,
            ColgramDesignConfig.SURFACE_GRAPHITE,
            ColgramDesignConfig.SURFACE_MIDNIGHT,
            ColgramDesignConfig.SURFACE_CRIMSON_NOIR,
            ColgramDesignConfig.SURFACE_PORCELAIN
    };

    private static final int[] SURFACE_SWATCH_COLORS = {
            0xFF1A202C,
            0xFF04060A,
            0xFF10131C,
            0xFF0B1022,
            0xFF16090D,
            0xFFF8FAFC
    };

    private static final String[] SURFACE_NAMES = {
            "Авто", "OLED", "Графит", "Индиго", "Нуар", "Светлый"
    };

    private RecyclerListView listView;
    private ListAdapter adapter;
    private long lastDialogTapMs;
    private boolean pendingThemeSync;
    private final Runnable deferredThemeSyncRunnable = () -> {
        pendingThemeSync = false;
        if (!isFinishing() && fragmentView != null) {
            applyLiveDesignEverywhere(true);
        }
    };

    private int rowCount;
    private int previewRow;
    private int sectionPaletteRow;
    private int accentSwatchesRow;
    private int surfaceSwatchesRow;
    private int cardStyleRow;
    private int cornerRadiusRow;
    private int neonIntensityRow;
    private int hideDividersRow;
    private int sectionLayoutRow;
    private int quickBarPlacementRow;
    private int bottomBarButtonsRow;
    private int showHeaderShieldRow;
    private int customHeaderTitleRow;
    private int sectionKolitiRow;
    private int kolitiOrbSizeRow;
    private int kolitiOrbAlphaRow;
    private int kolitiOrbDockRow;
    private int kolitiAvatarPickRow;
    private int kolitiAvatarResetRow;
    private int kolitiNameRow;
    private int sectionPresetRow;
    private int exportJsonRow;
    private int importJsonRow;
    private int resetAllRow;
    private int infoRow;
    private int footerShadowRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        Context ctx = getContext();
        if (ctx != null) {
            ColgramDesignConfig.init(ctx);
        }
        updateRows();
        return true;
    }

    @Override
    public void clearViews() {
        pendingThemeSync = false;
        AndroidUtilities.cancelRunOnUIThread(deferredThemeSyncRunnable);
        super.clearViews();
        listView = null;
        adapter = null;
    }

    @Override
    public void onFragmentDestroy() {
        pendingThemeSync = false;
        AndroidUtilities.cancelRunOnUIThread(deferredThemeSyncRunnable);
        super.onFragmentDestroy();
    }

    private void updateRows() {
        rowCount = 0;
        previewRow = rowCount++;
        sectionPaletteRow = rowCount++;
        accentSwatchesRow = rowCount++;
        surfaceSwatchesRow = rowCount++;
        cardStyleRow = rowCount++;
        cornerRadiusRow = rowCount++;
        neonIntensityRow = rowCount++;
        hideDividersRow = rowCount++;
        sectionLayoutRow = rowCount++;
        quickBarPlacementRow = rowCount++;
        bottomBarButtonsRow = rowCount++;
        showHeaderShieldRow = rowCount++;
        customHeaderTitleRow = rowCount++;
        if (ColgramBuildFlags.KOLITI_ENABLED) {
            sectionKolitiRow = rowCount++;
            kolitiOrbSizeRow = rowCount++;
            kolitiOrbAlphaRow = rowCount++;
            kolitiOrbDockRow = rowCount++;
            kolitiAvatarPickRow = rowCount++;
            kolitiAvatarResetRow = TextUtils.isEmpty(ColgramDesignConfig.getKolitiCustomAvatarPath()) ? -1 : rowCount++;
            kolitiNameRow = rowCount++;
        } else {
            sectionKolitiRow = -1;
            kolitiOrbSizeRow = -1;
            kolitiOrbAlphaRow = -1;
            kolitiOrbDockRow = -1;
            kolitiAvatarPickRow = -1;
            kolitiAvatarResetRow = -1;
            kolitiNameRow = -1;
        }
        sectionPresetRow = rowCount++;
        exportJsonRow = rowCount++;
        importJsonRow = rowCount++;
        resetAllRow = rowCount++;
        infoRow = rowCount++;
        footerShadowRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        ColgramDesignConfig.init(context);
        updateRows();

        if (actionBar != null) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle("Конструктор дизайна");
            ColgramMenuChrome.styleActionBar(actionBar);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) finishFragment();
                }
            });
        }

        fragmentView = new FrameLayout(context);
        FrameLayout root = (FrameLayout) fragmentView;
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this::handleClick);

        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void applyLiveDesignEverywhere(boolean refreshTheme) {
        if (isFinishing()) return;
        if (ColgramBuildFlags.KOLITI_ENABLED && ColgramGlobalOrbController.getInstance() != null) {
            ColgramGlobalOrbController.getInstance().applyDesignConfig();
        }
        if (refreshTheme) {
            INavigationLayout pl = getParentLayout();
            if (pl != null && (pl.isTransitionAnimationInProgress() || pl.isSwipeInProgress())) {
                if (!pendingThemeSync) {
                    pendingThemeSync = true;
                    AndroidUtilities.cancelRunOnUIThread(deferredThemeSyncRunnable);
                    AndroidUtilities.runOnUIThread(deferredThemeSyncRunnable, 260L);
                }
                updateRows();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                return;
            }
            try {
                boolean night = Theme.isCurrentThemeNight();
                Theme.ThemeInfo active = Theme.getActiveTheme();
                if (LaunchActivity.instance != null && active != null) {
                    LaunchActivity.colgramSyncAllPanesAfterTheme(active, night);
                } else {
                    NotificationCenter.getGlobalInstance().postNotificationName(
                            NotificationCenter.didSetNewTheme, night, true, false);
                }
            } catch (Throwable ignored) {}
            if (fragmentView != null) {
                fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
            }
            if (actionBar != null) {
                ColgramMenuChrome.styleActionBar(actionBar);
            }
        }
        updateRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void refreshPreviewOnly() {
        if (isFinishing()) return;
        if (ColgramBuildFlags.KOLITI_ENABLED && ColgramGlobalOrbController.getInstance() != null) {
            ColgramGlobalOrbController.getInstance().applyDesignConfig();
        }
        if (listView != null && previewRow >= 0) {
            RecyclerView.ViewHolder h = listView.findViewHolderForAdapterPosition(previewRow);
            if (h != null && h.itemView instanceof LivePreviewCell) {
                ((LivePreviewCell) h.itemView).bind();
            }
        }
    }

    private void handleClick(View view, int position) {
        if (position < 0 || adapter == null || position >= adapter.getItemCount()) return;
        Activity act = getParentActivity();
        if (isFinishing() || getParentLayout() == null || act == null || act.isFinishing() || act.isDestroyed()) return;
        final Context ctx = act;
        final Context appCtx = act.getApplicationContext();

        if (position != hideDividersRow && position != showHeaderShieldRow && position != exportJsonRow && (kolitiAvatarResetRow < 0 || position != kolitiAvatarResetRow)) {
            if (getVisibleDialog() != null) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastDialogTapMs < 320L) return;
            lastDialogTapMs = now;
        }

        if (position == cardStyleRow) {
            final String[] items = {
                    "Стеклянные карточки (Cyber Glass)",
                    "Неоновая акцентная обводка",
                    "Классический плоский список",
                    "Мягкие скруглённые капсулы"
            };
            ColgramUiKit.showChoiceSheet(
                    getParentActivity(), getResourceProvider(), R.drawable.msg_customize,
                    "Стиль карточек чатов", null, items, ColgramDesignConfig.getCardStyle(),
                    which -> {
                        if (isFinishing() || which < 0) return;
                        ColgramDesignConfig.setCardStyle(which);
                        applyLiveDesignEverywhere(false);
                    }
            );
        } else if (position == neonIntensityRow) {
            final String[] items = {
                    "Выключено (строгий минимализм)",
                    "Нежное свечение (55%)",
                    "Стандарт (100%)",
                    "Максимум (140%)"
            };
            ColgramUiKit.showChoiceSheet(
                    getParentActivity(), getResourceProvider(), R.drawable.msg_theme,
                    "Интенсивность неоновых акцентов", null, items, ColgramDesignConfig.getNeonIntensity(),
                    which -> {
                        if (isFinishing() || which < 0) return;
                        ColgramDesignConfig.setNeonIntensity(which);
                        applyLiveDesignEverywhere(false);
                    }
            );
        } else if (position == hideDividersRow) {
            boolean next = !ColgramDesignConfig.isHideDividers();
            ColgramDesignConfig.setHideDividers(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            applyLiveDesignEverywhere(true);
        } else if (position == quickBarPlacementRow) {
            final String[] items = {
                    "Полоса под строкой поиска (сворачивается при скролле)",
                    "Всегда свёрнута в кнопку ⚡ в шапке",
                    "Полностью скрыть панель"
            };
            ColgramUiKit.showChoiceSheet(
                    getParentActivity(), getResourceProvider(), R.drawable.msg_customize,
                    "Расположение панели быстрого доступа", null, items, ColgramDesignConfig.getQuickBarPlacement(),
                    which -> {
                        if (isFinishing() || which < 0) return;
                        ColgramDesignConfig.setQuickBarPlacement(which);
                        applyLiveDesignEverywhere(false);
                    }
            );
        } else if (position == bottomBarButtonsRow) {
            ColgramBottomBarConfig.showEditorSheet(this, () -> applyLiveDesignEverywhere(false));
        } else if (position == showHeaderShieldRow) {
            boolean next = !ColgramDesignConfig.isShowHeaderShield();
            ColgramDesignConfig.setShowHeaderShield(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            applyLiveDesignEverywhere(false);
        } else if (position == customHeaderTitleRow) {
            showTextInputDialog(ctx, "Заголовок в шапке чатов",
                    "Оставьте пустым для «Colgram»",
                    ColgramDesignConfig.getCustomHeaderTitle(),
                    val -> {
                        ColgramDesignConfig.setCustomHeaderTitle(val);
                        applyLiveDesignEverywhere(false);
                    });
        } else if (ColgramBuildFlags.KOLITI_ENABLED && position == kolitiOrbDockRow) {
            final String[] items = {
                    "Слева (не мешает кнопке «Новое сообщение»)",
                    "Справа",
                    "Помнить последнее перетаскивание"
            };
            ColgramUiKit.showChoiceSheet(
                    getParentActivity(), getResourceProvider(), R.drawable.msg_bots,
                    "Позиция кружка Koliti", null, items, ColgramDesignConfig.getKolitiOrbDock(),
                    which -> {
                        if (isFinishing() || which < 0) return;
                        ColgramDesignConfig.setKolitiOrbDock(which);
                        applyLiveDesignEverywhere(false);
                    }
            );
        } else if (ColgramBuildFlags.KOLITI_ENABLED && position == kolitiAvatarPickRow) {
            try {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(intent, "Выберите аватар для Koliti"), REQ_PICK_KOLITI_AVATAR);
            } catch (Throwable t) {
                ColgramUiKit.showNotice(appCtx, R.drawable.msg_info, "Не удалось открыть галерею", true);
            }
        } else if (ColgramBuildFlags.KOLITI_ENABLED && kolitiAvatarResetRow >= 0 && position == kolitiAvatarResetRow) {
            final String old = ColgramDesignConfig.getKolitiCustomAvatarPath();
            if (!TextUtils.isEmpty(old)) {
                org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                    try { new File(old).delete(); } catch (Throwable ignored) {}
                });
            }
            ColgramDesignConfig.setKolitiCustomAvatarPath("");
            ColgramAeroStyler.invalidateKolitiAvatarCache();
            applyLiveDesignEverywhere(false);
            ColgramUiKit.showNotice(appCtx, R.drawable.msg_theme, "Возвращён стандартный аватар Koliti", false);
        } else if (ColgramBuildFlags.KOLITI_ENABLED && position == kolitiNameRow) {
            showTextInputDialog(ctx, "Имя ассистента",
                    "Например: Koliti, Jarvis, Nova",
                    ColgramDesignConfig.getKolitiCustomName(),
                    val -> {
                        ColgramDesignConfig.setKolitiCustomName(val);
                        applyLiveDesignEverywhere(false);
                    });
        } else if (position == exportJsonRow) {
            String json = ColgramDesignConfig.exportJson();
            try {
                ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Colgram Design", json));
                    ColgramUiKit.showNotice(appCtx, R.drawable.msg_copy, "Конфиг дизайна (JSON) скопирован в буфер", false);
                }
            } catch (Throwable t) {
                ColgramUiKit.showNotice(appCtx, R.drawable.msg_info, "Не удалось скопировать JSON", true);
            }
        } else if (position == importJsonRow) {
            showTextInputDialog(ctx, "Импорт дизайна (JSON)",
                    "Вставьте JSON-конфиг из буфера",
                    "",
                    val -> {
                        if (isFinishing() || getParentLayout() == null) return;
                        if (ColgramDesignConfig.importJson(val)) {
                            applyLiveDesignEverywhere(true);
                            ColgramUiKit.showNotice(appCtx, R.drawable.msg_check_s, "Дизайн успешно применён!", false);
                        } else {
                            ColgramUiKit.showNotice(appCtx, R.drawable.msg_info, "Некорректный JSON-конфиг", true);
                        }
                    });
        } else if (position == resetAllRow) {
            ColgramUiKit.showConfirmSheet(
                    getParentActivity(),
                    getResourceProvider(),
                    R.drawable.msg_delete,
                    "Сбросить дизайн?",
                    ColgramBuildFlags.KOLITI_ENABLED
                            ? "Все цвета, геометрия карточек, раскладка шапки, размер и аватар Koliti вернутся к заводским."
                            : "Все цвета, геометрия карточек и раскладка шапки вернутся к заводским.",
                    "Сбросить",
                    true,
                    () -> {
                        if (isFinishing() || getParentLayout() == null) return;
                        if (ColgramBuildFlags.KOLITI_ENABLED) {
                            final String old = ColgramDesignConfig.getKolitiCustomAvatarPath();
                            if (!TextUtils.isEmpty(old)) {
                                org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                                    try { new File(old).delete(); } catch (Throwable ignored) {}
                                });
                            }
                        }
                        ColgramDesignConfig.resetAll();
                        ColgramAeroStyler.invalidateKolitiAvatarCache();
                        applyLiveDesignEverywhere(true);
                        ColgramUiKit.showNotice(this, R.drawable.msg_theme, "Дизайн сброшен к стандарту Colgram", false);
                    },
                    "Отмена",
                    null
            );
        }
    }

    private void showHexColorDialog(Context ctx, String title, int currentColor, IntConsumer onPick) {
        String init = currentColor == 0 ? "#E11D48" : ColgramDesignConfig.formatHexColor(currentColor);
        showTextInputDialog(ctx, title, "Формат #RRGGBB, например #00E5FF", init, val -> {
            if (isFinishing() || onPick == null) return;
            int parsed = ColgramDesignConfig.parseHexColor(val);
            if (parsed == 0) {
                ColgramUiKit.showNotice(this, R.drawable.msg_info, "Введите цвет в формате #RRGGBB", true);
            } else {
                onPick.accept(parsed);
            }
        });
    }

    private interface IntConsumer { void accept(int value); }
    private interface StrConsumer { void accept(String value); }

    private void showTextInputDialog(Context ctx, String title, String hint, String initial, StrConsumer onOk) {
        Activity act = (ctx instanceof Activity) ? (Activity) ctx : getParentActivity();
        if (isFinishing() || getParentLayout() == null || act == null || act.isFinishing() || act.isDestroyed()) return;
        ColgramUiKit.showInputSheet(
                act,
                getResourceProvider(),
                R.drawable.msg_edit,
                title,
                null,
                hint,
                initial,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                false,
                false,
                false,
                "Применить",
                val -> {
                    if (isFinishing() || onOk == null) return;
                    onOk.accept(val == null ? "" : val);
                },
                null,
                null
        );
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (ColgramBuildFlags.KOLITI_ENABLED
                && requestCode == REQ_PICK_KOLITI_AVATAR && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Activity act = getParentActivity();
            if (act == null || act.isFinishing() || act.isDestroyed()) return;
            final Context appCtx = act.getApplicationContext();
            if (appCtx == null) return;
            final Uri uri = data.getData();
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    try (InputStream boundsIn = appCtx.getContentResolver().openInputStream(uri)) {
                        if (boundsIn != null) {
                            BitmapFactory.decodeStream(boundsIn, null, bounds);
                        }
                    }
                    int sample = 1;
                    while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) {
                        sample *= 2;
                    }
                    BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                    decodeOpts.inSampleSize = Math.max(1, sample);
                    Bitmap decoded;
                    try (InputStream is = appCtx.getContentResolver().openInputStream(uri)) {
                        decoded = is != null ? BitmapFactory.decodeStream(is, null, decodeOpts) : null;
                    }
                    if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                        AndroidUtilities.runOnUIThread(() ->
                                ColgramUiKit.showNotice(appCtx, R.drawable.msg_info, "Не удалось прочитать изображение", true));
                        return;
                    }
                    int rotationDeg = 0;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        try (InputStream exifIn = appCtx.getContentResolver().openInputStream(uri)) {
                            if (exifIn != null) {
                                android.media.ExifInterface exif = new android.media.ExifInterface(exifIn);
                                int orient = exif.getAttributeInt(
                                        android.media.ExifInterface.TAG_ORIENTATION,
                                        android.media.ExifInterface.ORIENTATION_NORMAL);
                                if (orient == android.media.ExifInterface.ORIENTATION_ROTATE_90) rotationDeg = 90;
                                else if (orient == android.media.ExifInterface.ORIENTATION_ROTATE_180) rotationDeg = 180;
                                else if (orient == android.media.ExifInterface.ORIENTATION_ROTATE_270) rotationDeg = 270;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (rotationDeg != 0) {
                        android.graphics.Matrix m = new android.graphics.Matrix();
                        m.postRotate(rotationDeg);
                        Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
                        if (rotated != null && rotated != decoded) {
                            decoded.recycle();
                            decoded = rotated;
                        }
                    }
                    // Center-crop square and scale cleanly to 256x256 PNG so the orb & UI stay crisp and lightweight
                    int side = Math.min(decoded.getWidth(), decoded.getHeight());
                    int x = (decoded.getWidth() - side) / 2;
                    int y = (decoded.getHeight() - side) / 2;
                    Bitmap cropped = Bitmap.createBitmap(decoded, x, y, side, side);
                    Bitmap scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true);
                    if (cropped != decoded && cropped != scaled) cropped.recycle();
                    if (decoded != scaled) decoded.recycle();

                    final File out = new File(appCtx.getFilesDir(), "koliti_custom_avatar.png");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        scaled.compress(Bitmap.CompressFormat.PNG, 95, fos);
                    }
                    scaled.recycle();

                    AndroidUtilities.runOnUIThread(() -> {
                        ColgramDesignConfig.init(appCtx);
                        ColgramDesignConfig.setKolitiCustomAvatarPath(out.getAbsolutePath());
                        ColgramAeroStyler.invalidateKolitiAvatarCache();
                        if (!isFinishing() && fragmentView != null) {
                            applyLiveDesignEverywhere(false);
                        }
                        ColgramUiKit.showNotice(appCtx, R.drawable.msg_check_s, "Новый аватар Koliti установлен везде!", false);
                    });
                } catch (Throwable t) {
                    AndroidUtilities.runOnUIThread(() ->
                            ColgramUiKit.showNotice(appCtx, R.drawable.msg_info, "Ошибка сохранения аватара: " + t.getMessage(), true));
                }
            });
        }
    }

    // ---- Custom Interactive Cells ----

    private final class LivePreviewCell extends FrameLayout {
        private final LinearLayout phoneFrame;
        private final LinearLayout headerRow;
        private final TextView titleTv;
        private final ImageView shieldIv;
        private final TextView quickBadgeTv;
        private final View hairlineView;
        private final LinearLayout chatCard;
        private final TextView unreadBadge;
        private final TextView bubbleOut;
        private final ImageView orbPreview;
        private final FrameLayout orbHost;
        private final TextView orbMetaTv;

        LivePreviewCell(Context ctx) {
            super(ctx);
            setPadding(dp(14), dp(12), dp(14), dp(10));

            phoneFrame = new LinearLayout(ctx);
            phoneFrame.setOrientation(LinearLayout.VERTICAL);
            phoneFrame.setPadding(dp(12), dp(10), dp(12), dp(12));
            addView(phoneFrame, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            headerRow = new LinearLayout(ctx);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            phoneFrame.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 34));

            titleTv = new TextView(ctx);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
            titleTv.setTypeface(AndroidUtilities.bold());
            titleTv.setMaxLines(1);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            headerRow.addView(titleTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            quickBadgeTv = new TextView(ctx);
            quickBadgeTv.setText("⚡");
            quickBadgeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
            quickBadgeTv.setPadding(dp(6), dp(2), dp(6), dp(2));
            headerRow.addView(quickBadgeTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));

            shieldIv = new ImageView(ctx);
            shieldIv.setImageResource(R.drawable.outline_shield_check);
            headerRow.addView(shieldIv, LayoutHelper.createLinear(18, 18));

            hairlineView = new View(ctx);
            phoneFrame.addView(hairlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 2, 0, 4, 0, 8));

            chatCard = new LinearLayout(ctx);
            chatCard.setOrientation(LinearLayout.HORIZONTAL);
            chatCard.setGravity(Gravity.CENTER_VERTICAL);
            chatCard.setPadding(dp(10), dp(9), dp(10), dp(9));
            phoneFrame.addView(chatCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

            LinearLayout txtCol = new LinearLayout(ctx);
            txtCol.setOrientation(LinearLayout.VERTICAL);
            chatCard.addView(txtCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView chatName = new TextView(ctx);
            chatName.setText("Живое превью Colgram");
            chatName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
            chatName.setTypeface(AndroidUtilities.bold());
            chatName.setTag("preview_primary");
            txtCol.addView(chatName);

            TextView chatSub = new TextView(ctx);
            chatSub.setText("Изменения сразу видны здесь и по всему приложению");
            chatSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            chatSub.setMaxLines(1);
            chatSub.setEllipsize(TextUtils.TruncateAt.END);
            chatSub.setTag("preview_secondary");
            txtCol.addView(chatSub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            unreadBadge = new TextView(ctx);
            unreadBadge.setText("7");
            unreadBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
            unreadBadge.setTypeface(AndroidUtilities.bold());
            unreadBadge.setGravity(Gravity.CENTER);
            unreadBadge.setPadding(dp(7), dp(2), dp(7), dp(2));
            chatCard.addView(unreadBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));

            LinearLayout bottomRow = new LinearLayout(ctx);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            phoneFrame.addView(bottomRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            orbHost = new FrameLayout(ctx);
            bottomRow.addView(orbHost, LayoutHelper.createLinear(84, 84, Gravity.CENTER_VERTICAL));

            orbPreview = new ImageView(ctx);
            orbPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            orbPreview.setClipToOutline(true);
            orbHost.addView(orbPreview, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

            LinearLayout rightCol = new LinearLayout(ctx);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            bottomRow.addView(rightCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 8, 0, 0, 0));

            bubbleOut = new TextView(ctx);
            bubbleOut.setText("Привет! Это стиль твоего Colgram ✨");
            bubbleOut.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            bubbleOut.setPadding(dp(11), dp(6), dp(11), dp(6));
            rightCol.addView(bubbleOut, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));

            orbMetaTv = new TextView(ctx);
            orbMetaTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
            orbMetaTv.setGravity(Gravity.RIGHT);
            rightCol.addView(orbMetaTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 0));
        }

        void bind() {
            if (phoneFrame == null || orbPreview == null || titleTv == null || chatCard == null) return;
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            if (pal == null) return;
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));

            int rad = ColgramDesignConfig.getCornerRadiusDp();
            GradientDrawable frameBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{pal.sheetBgTop, pal.sheetBgBottom});
            frameBg.setCornerRadius(dp(Math.max(14, rad)));
            frameBg.setStroke(dp(1), pal.borderAccent);
            phoneFrame.setBackground(frameBg);

            titleTv.setText(ColgramDesignConfig.getEffectiveHeaderTitle());
            titleTv.setTextColor(pal.accentPrimary);

            if (shieldIv != null) {
                shieldIv.setVisibility(ColgramDesignConfig.isShowHeaderShield() ? VISIBLE : GONE);
                shieldIv.setColorFilter(pal.textPrimary, PorterDuff.Mode.SRC_IN);
            }

            int qb = ColgramDesignConfig.getQuickBarPlacement();
            if (quickBadgeTv != null) {
                quickBadgeTv.setVisibility(qb == ColgramDesignConfig.QUICK_BAR_HIDDEN ? GONE : VISIBLE);
                quickBadgeTv.setTextColor(pal.accentPrimary);
            }

            if (hairlineView != null) {
                hairlineView.setVisibility(ColgramDesignConfig.getNeonIntensity() == 0 ? INVISIBLE : VISIBLE);
                hairlineView.setBackgroundColor(pal.accentPrimary);
            }

            GradientDrawable cardBg = new GradientDrawable();
            int cardStyle = ColgramDesignConfig.getCardStyle();
            cardBg.setCornerRadius(dp(cardStyle == ColgramDesignConfig.CARD_STYLE_SOFT_PILL ? Math.max(18, rad) : rad));
            cardBg.setColor(cardStyle == ColgramDesignConfig.CARD_STYLE_CLASSIC_FLAT ? Color.TRANSPARENT : pal.cardSurface);
            if (cardStyle == ColgramDesignConfig.CARD_STYLE_NEON_BORDER) {
                cardBg.setStroke(dp(1.2f), pal.accentPrimary);
            } else if (cardStyle != ColgramDesignConfig.CARD_STYLE_CLASSIC_FLAT) {
                cardBg.setStroke(dp(0.8f), pal.borderSubtle);
            }
            chatCard.setBackground(cardBg);

            View p1 = chatCard.findViewWithTag("preview_primary");
            if (p1 instanceof TextView) ((TextView) p1).setTextColor(pal.textPrimary);
            View p2 = chatCard.findViewWithTag("preview_secondary");
            if (p2 instanceof TextView) ((TextView) p2).setTextColor(pal.textSecondary);

            if (unreadBadge != null) {
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setCornerRadius(dp(10));
                badgeBg.setColor(pal.accentPrimary);
                unreadBadge.setBackground(badgeBg);
                unreadBadge.setTextColor(ColgramThemePolicy.contrastInkOn(pal.accentPrimary));
            }

            if (bubbleOut != null) {
                GradientDrawable bubbleBg = new GradientDrawable();
                bubbleBg.setCornerRadius(dp(rad));
                bubbleBg.setColor(pal.accentSoft);
                bubbleBg.setStroke(dp(0.8f), pal.borderAccent);
                bubbleOut.setBackground(bubbleBg);
                bubbleOut.setTextColor(pal.textPrimary);
            }

            if (ColgramBuildFlags.KOLITI_ENABLED) {
                orbHost.setVisibility(VISIBLE);
                int orbDp = ColgramDesignConfig.getKolitiOrbSizeDp();
                int visualDp = Math.max(32, Math.min(64, Math.round(orbDp * 0.78f)));
                ViewGroup.LayoutParams rawOlp = orbPreview.getLayoutParams();
                if (rawOlp instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams olp = (FrameLayout.LayoutParams) rawOlp;
                    olp.width = dp(visualDp);
                    olp.height = dp(visualDp);
                    orbPreview.setLayoutParams(olp);
                }
                orbPreview.setAlpha(ColgramDesignConfig.getKolitiOrbAlpha());
                GradientDrawable orbOutline = new GradientDrawable();
                orbOutline.setShape(GradientDrawable.OVAL);
                orbOutline.setStroke(dp(2), pal.accentPrimary);
                orbPreview.setBackground(orbOutline);
                orbPreview.setPadding(dp(2), dp(2), dp(2), dp(2));
                ColgramAeroStyler.applyKolitiAvatar(orbPreview);

                if (orbMetaTv != null) {
                    orbMetaTv.setVisibility(VISIBLE);
                    int dockMode = ColgramDesignConfig.getKolitiOrbDock();
                    String dockTxt = dockMode == ColgramDesignConfig.DOCK_RIGHT ? "справа"
                            : (dockMode == ColgramDesignConfig.DOCK_REMEMBER ? "свободно" : "слева");
                    orbMetaTv.setText(ColgramDesignConfig.getKolitiDisplayName() + " • " + orbDp + "dp • "
                            + ColgramDesignConfig.getKolitiOrbAlphaPct() + "% • " + dockTxt);
                    orbMetaTv.setTextColor(pal.textSecondary);
                }
            } else {
                orbHost.setVisibility(GONE);
                if (orbMetaTv != null) {
                    orbMetaTv.setVisibility(GONE);
                }
            }
        }
    }

    private final class SwatchRowCell extends LinearLayout {
        private final TextView labelTv;
        private final HorizontalScrollView scrollView;
        private final LinearLayout strip;
        private int boundKind = -1;
        private final ArrayList<GradientDrawable> chipDotBgs = new ArrayList<>();
        private final ArrayList<TextView> chipLabels = new ArrayList<>();
        private final ArrayList<View> chipContainers = new ArrayList<>();

        SwatchRowCell(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(10), dp(16), dp(12));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            labelTv = new TextView(ctx);
            labelTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            labelTv.setTypeface(AndroidUtilities.bold());
            addView(labelTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

            scrollView = new HorizontalScrollView(ctx);
            scrollView.setHorizontalScrollBarEnabled(false);
            strip = new LinearLayout(ctx);
            strip.setOrientation(HORIZONTAL);
            scrollView.addView(strip);
            addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        private void ensureChips(Context ctx, int kind, String[] names) {
            if (boundKind == kind && chipContainers.size() == names.length + 1) {
                return;
            }
            boundKind = kind;
            strip.removeAllViews();
            chipDotBgs.clear();
            chipLabels.clear();
            chipContainers.clear();
            for (int i = 0; i <= names.length; i++) {
                String label = i < names.length ? names[i] : "+ HEX";
                strip.addView(createChipSlot(ctx, label));
            }
        }

        private View createChipSlot(Context ctx, String name) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(dp(4), dp(2), dp(8), dp(2));

            View dot = new View(ctx);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            dot.setBackground(gd);
            col.addView(dot, LayoutHelper.createLinear(34, 34));

            TextView tv = new TextView(ctx);
            tv.setText(name);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
            col.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            ColgramMenuChrome.attachSpringPress(col);
            chipDotBgs.add(gd);
            chipLabels.add(tv);
            chipContainers.add(col);
            return col;
        }

        private void styleChipSlot(int index, int fillCol, boolean selected, Runnable onClick) {
            if (index < 0 || index >= chipDotBgs.size() || index >= chipLabels.size() || index >= chipContainers.size()) {
                return;
            }
            GradientDrawable gd = chipDotBgs.get(index);
            if (gd != null) {
                gd.setColor(fillCol);
                gd.setStroke(dp(selected ? 2.6f : 1f), selected
                        ? Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider())
                        : 0x4494A3B8);
            }
            TextView tv = chipLabels.get(index);
            if (tv != null) {
                tv.setTypeface(selected ? AndroidUtilities.bold() : null);
                tv.setTextColor(selected
                        ? Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider())
                        : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
            }
            View container = chipContainers.get(index);
            if (container != null) {
                container.setOnClickListener(v -> {
                    if (onClick != null) onClick.run();
                });
            }
        }

        void bindAccent(Context ctx) {
            if (ctx == null || labelTv == null || strip == null) return;
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
            int cur = ColgramDesignConfig.getAccentColor();
            labelTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            labelTv.setText("Акцентный цвет" + (cur == 0 ? " (Авто)" : " (" + ColgramDesignConfig.formatHexColor(cur) + ")"));
            ensureChips(ctx, 0, ACCENT_NAMES);
            boolean matchesPreset = false;
            int count = Math.min(ACCENT_SWATCHES.length, ACCENT_NAMES.length);
            for (int i = 0; i < count; i++) {
                final int col = ACCENT_SWATCHES[i];
                boolean sel = cur == col;
                if (sel) matchesPreset = true;
                styleChipSlot(i, col == 0 ? 0xFFE11D48 : col, sel, () -> {
                    if (isFinishing() || getParentLayout() == null) return;
                    ColgramDesignConfig.setAccentColor(col);
                    applyLiveDesignEverywhere(true);
                });
            }
            styleChipSlot(count, cur == 0 ? 0xFF334155 : cur, cur != 0 && !matchesPreset, () -> {
                Activity act = getParentActivity();
                if (isFinishing() || getParentLayout() == null || act == null || act.isFinishing() || act.isDestroyed() || getVisibleDialog() != null) {
                    return;
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastDialogTapMs < 320L) return;
                lastDialogTapMs = now;
                showHexColorDialog(act, "Свой акцентный цвет (HEX)", cur, picked -> {
                    if (isFinishing() || getParentLayout() == null) return;
                    ColgramDesignConfig.setAccentColor(picked);
                    applyLiveDesignEverywhere(true);
                });
            });
        }

        void bindSurface(Context ctx) {
            if (ctx == null || labelTv == null || strip == null) return;
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
            int preset = ColgramDesignConfig.getSurfacePreset();
            labelTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            labelTv.setText("Фон и поверхность интерфейса");
            ensureChips(ctx, 1, SURFACE_NAMES);
            int count = Math.min(SURFACE_PRESET_IDS.length, Math.min(SURFACE_SWATCH_COLORS.length, SURFACE_NAMES.length));
            for (int i = 0; i < count; i++) {
                final int pid = SURFACE_PRESET_IDS[i];
                boolean sel = preset == pid;
                styleChipSlot(i, SURFACE_SWATCH_COLORS[i], sel, () -> {
                    if (isFinishing() || getParentLayout() == null) return;
                    ColgramDesignConfig.setSurfacePreset(pid);
                    applyLiveDesignEverywhere(true);
                });
            }
            styleChipSlot(count, ColgramDesignConfig.getCustomSurfaceColor(),
                    preset == ColgramDesignConfig.SURFACE_CUSTOM_HEX, () -> {
                        Activity act = getParentActivity();
                        if (isFinishing() || getParentLayout() == null || act == null || act.isFinishing() || act.isDestroyed() || getVisibleDialog() != null) {
                            return;
                        }
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastDialogTapMs < 320L) return;
                        lastDialogTapMs = now;
                        showHexColorDialog(act, "Свой цвет фона (HEX)", ColgramDesignConfig.getCustomSurfaceColor(), picked -> {
                            if (isFinishing() || getParentLayout() == null) return;
                            ColgramDesignConfig.setCustomSurfaceColor(picked);
                            applyLiveDesignEverywhere(true);
                        });
                    });
        }
    }

    private final class SliderRowCell extends LinearLayout {
        private final TextView titleTv;
        private final TextView valueTv;
        private final SeekBar seekBar;

        SliderRowCell(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(10), dp(16), dp(10));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));

            LinearLayout top = new LinearLayout(ctx);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            titleTv = new TextView(ctx);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
            top.addView(titleTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            valueTv = new TextView(ctx);
            valueTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            valueTv.setTypeface(AndroidUtilities.bold());
            top.addView(valueTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            seekBar = new SeekBar(ctx);
            addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }

        void bind(String title, int min, int max, int current, String suffix, IntConsumer onChanged) {
            if (titleTv == null || valueTv == null || seekBar == null) return;
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
            int range = Math.max(1, max - min);
            int clamped = Math.max(min, Math.min(min + range, current));
            String safeSuffix = suffix == null ? "" : suffix;
            titleTv.setText(title == null ? "" : title);
            titleTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            valueTv.setTextColor(pal != null ? pal.accentPrimary : Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, getResourceProvider()));
            valueTv.setText(clamped + safeSuffix);
            seekBar.setOnSeekBarChangeListener(null);
            seekBar.setMax(range);
            seekBar.setProgress(Math.max(0, clamped - min));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser || isFinishing() || onChanged == null) return;
                    int val = min + Math.max(0, Math.min(range, progress));
                    valueTv.setText(val + safeSuffix);
                    onChanged.accept(val);
                    refreshPreviewOnly();
                }

                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
    }

    // ---- Adapter ----

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
            int t = holder.getItemViewType();
            return t == 2 || t == 3;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == previewRow) return 0;
            if (position == sectionPaletteRow || position == sectionLayoutRow
                    || position == sectionKolitiRow || position == sectionPresetRow) return 1;
            if (position == hideDividersRow || position == showHeaderShieldRow) return 2;
            if (position == accentSwatchesRow || position == surfaceSwatchesRow) return 6;
            if (position == cornerRadiusRow || position == kolitiOrbSizeRow || position == kolitiOrbAlphaRow) return 7;
            if (position == infoRow) return 4;
            if (position == footerShadowRow) return 5;
            return 3;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v;
            switch (viewType) {
                case 0: v = new LivePreviewCell(mContext); break;
                case 1:
                    v = new HeaderCell(mContext);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 2:
                    v = new TextCheckCell(mContext);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 3:
                    v = new TextDetailSettingsCell(mContext);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 4: v = new TextInfoPrivacyCell(mContext); break;
                case 6: v = new SwatchRowCell(mContext); break;
                case 7: v = new SliderRowCell(mContext); break;
                default: v = new ShadowSectionCell(mContext); break;
            }
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= rowCount || holder == null || holder.itemView == null) return;
            int type = holder.getItemViewType();
            if (type == 0) {
                ((LivePreviewCell) holder.itemView).bind();
            } else if (type == 1) {
                HeaderCell h = (HeaderCell) holder.itemView;
                if (position == sectionPaletteRow) {
                    h.setText("Цвета, поверхности и геометрия");
                    ColgramMenuChrome.styleHeader(h, ColgramMenuChrome.COLOR_CRIMSON, 0xFFFB7185);
                } else if (position == sectionLayoutRow) {
                    h.setText("Расположение элементов интерфейса");
                    ColgramMenuChrome.styleHeader(h, ColgramMenuChrome.COLOR_BLUE, 0xFF60A5FA);
                } else if (position == sectionKolitiRow) {
                    h.setText("Внешний вид и кнопка Koliti");
                    ColgramMenuChrome.styleHeader(h, ColgramMenuChrome.COLOR_INDIGO, 0xFFA78BFA);
                } else {
                    h.setText("Пресет дизайна (JSON)");
                    ColgramMenuChrome.styleHeader(h, ColgramMenuChrome.COLOR_SLATE, 0xFF94A3B8);
                }
            } else if (type == 6) {
                SwatchRowCell sc = (SwatchRowCell) holder.itemView;
                if (position == accentSwatchesRow) sc.bindAccent(mContext);
                else sc.bindSurface(mContext);
            } else if (type == 7) {
                SliderRowCell sl = (SliderRowCell) holder.itemView;
                if (position == cornerRadiusRow) {
                    sl.bind("Скругление карточек и углов",
                            ColgramDesignConfig.MIN_CORNER_DP, ColgramDesignConfig.MAX_CORNER_DP,
                            ColgramDesignConfig.getCornerRadiusDp(), " dp",
                            ColgramDesignConfig::setCornerRadiusDp);
                } else if (position == kolitiOrbSizeRow) {
                    sl.bind("Размер плавающей кнопки Koliti",
                            ColgramDesignConfig.MIN_ORB_SIZE_DP, ColgramDesignConfig.MAX_ORB_SIZE_DP,
                            ColgramDesignConfig.getKolitiOrbSizeDp(), " dp",
                            ColgramDesignConfig::setKolitiOrbSizeDp);
                } else {
                    sl.bind("Прозрачность кнопки Koliti",
                            ColgramDesignConfig.MIN_ORB_ALPHA_PCT, ColgramDesignConfig.MAX_ORB_ALPHA_PCT,
                            ColgramDesignConfig.getKolitiOrbAlphaPct(), "%",
                            ColgramDesignConfig::setKolitiOrbAlphaPct);
                }
            } else if (type == 2) {
                TextCheckCell c = (TextCheckCell) holder.itemView;
                if (position == hideDividersRow) {
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_SLATE, R.drawable.msg_customize);
                    c.setTextAndValueAndCheck("Скрыть тонкие разделители", "Более чистый безрамочный список",
                            ColgramDesignConfig.isHideDividers(), true, false);
                } else if (position == showHeaderShieldRow) {
                    ColgramMenuChrome.applyCheckIcon(c, ColgramMenuChrome.COLOR_EMERALD, R.drawable.outline_shield_check);
                    c.setTextAndValueAndCheck("Иконка Щита (обход РКН) в шапке", "Показывать быстрый вход в прокси рядом с заголовком",
                            ColgramDesignConfig.isShowHeaderShield(), true, true);
                }
            } else if (type == 3) {
                TextDetailSettingsCell d = (TextDetailSettingsCell) holder.itemView;
                if (position == cardStyleRow) {
                    String[] st = {"Стеклянные карточки (Cyber Glass)", "Неоновая обводка", "Классический плоский", "Мягкие капсулы"};
                    ColgramMenuChrome.applyDetailIcon(d, "Стиль карточек чатов", st[clampIndex(ColgramDesignConfig.getCardStyle(), st.length)], R.drawable.msg_theme, ColgramMenuChrome.COLOR_CRIMSON, true);
                } else if (position == neonIntensityRow) {
                    String[] ni = {"Выключено", "Нежный (55%)", "Стандарт (100%)", "Максимум (140%)"};
                    ColgramMenuChrome.applyDetailIcon(d, "Неоновые акценты и свечение", ni[clampIndex(ColgramDesignConfig.getNeonIntensity(), ni.length)], R.drawable.msg_speed, ColgramMenuChrome.COLOR_AMBER, true);
                } else if (position == quickBarPlacementRow) {
                    String[] qb = {"Под поиском (сворачивается при скролле)", "Свёрнута в кнопку ⚡ в шапке", "Скрыта"};
                    ColgramMenuChrome.applyDetailIcon(d, "Панель быстрого доступа", qb[clampIndex(ColgramDesignConfig.getQuickBarPlacement(), qb.length)], R.drawable.msg_folders, ColgramMenuChrome.COLOR_BLUE, true);
                } else if (position == bottomBarButtonsRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Кнопки нижней панели",
                            ColgramBottomBarConfig.getSummaryText() + " · доп. страницы при свайпе",
                            R.drawable.msg_list, ColgramMenuChrome.COLOR_INDIGO, true);
                } else if (position == customHeaderTitleRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Заголовок в шапке чатов", ColgramDesignConfig.getEffectiveHeaderTitle() + " (нажмите, чтобы переименовать)", R.drawable.msg_edit, ColgramMenuChrome.COLOR_TEAL, false);
                } else if (position == kolitiOrbDockRow) {
                    String[] dk = {"Слева", "Справа", "Помнить последнее место"};
                    ColgramMenuChrome.applyDetailIcon(d, "Сторона закрепления кнопки Koliti", dk[clampIndex(ColgramDesignConfig.getKolitiOrbDock(), dk.length)], R.drawable.msg_bot, ColgramMenuChrome.COLOR_INDIGO, true);
                } else if (position == kolitiAvatarPickRow) {
                    boolean hasCustom = !TextUtils.isEmpty(ColgramDesignConfig.getKolitiCustomAvatarPath());
                    ColgramMenuChrome.applyDetailIcon(d, "Своя аватарка для Koliti",
                            hasCustom ? "Установлено своё фото • нажмите, чтобы заменить" : "Выбрать любое фото из галереи (для кружка и чата)",
                            R.drawable.msg_media, ColgramMenuChrome.COLOR_ROSE, true);
                } else if (kolitiAvatarResetRow >= 0 && position == kolitiAvatarResetRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Сбросить аватар Koliti", "Вернуть фирменный аватар Koliti", R.drawable.msg_clear, ColgramMenuChrome.COLOR_SLATE, true);
                } else if (position == kolitiNameRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Имя ассистента", ColgramDesignConfig.getKolitiDisplayName() + " • отображается в чате и панели", R.drawable.msg_edit, ColgramMenuChrome.COLOR_INDIGO, false);
                } else if (position == exportJsonRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Экспортировать дизайн (JSON)", "Скопировать все настройки дизайна в буфер обмена", R.drawable.msg_share, ColgramMenuChrome.COLOR_EMERALD, true);
                } else if (position == importJsonRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Импортировать дизайн (JSON)", "Загрузить сохранённый конфиг из текста", R.drawable.msg_download, ColgramMenuChrome.COLOR_BLUE, true);
                } else if (resetAllRow >= 0 && position == resetAllRow) {
                    ColgramMenuChrome.applyDetailIcon(d, "Сбросить всё к стандарту",
                            ColgramBuildFlags.KOLITI_ENABLED
                                    ? "Вернуть заводскую тему, раскладку и аватар"
                                    : "Вернуть заводскую тему и раскладку",
                            R.drawable.msg_clear, ColgramMenuChrome.COLOR_CRIMSON, false);
                }
            } else if (type == 4) {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                        "Все настройки конструктора применяются мгновенно и сохраняются локально. "
                                + "Авто-контраст Colgram автоматически защищает читаемость текста и кнопок на любых ваших цветах.");
            }
        }
    }

    private static int clampIndex(int idx, int length) {
        if (length <= 0) return 0;
        return Math.max(0, Math.min(length - 1, idx));
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR,
                new Class[]{HeaderCell.class, TextCheckCell.class, TextDetailSettingsCell.class, SwatchRowCell.class, SliderRowCell.class},
                null, null, null, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextDetailSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextDetailSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER,
                new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER,
                new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR,
                new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{LivePreviewCell.class, SwatchRowCell.class, SliderRowCell.class}, null, null, () -> {
            if (listView != null && adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }, Theme.key_windowBackgroundWhite));
        return list;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
