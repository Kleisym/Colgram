package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.colgram.core.ColgramBuildFlags;
import org.colgram.core.ColgramConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Configuration, catalog, action dispatch, and editor bottom sheet for customizable
 * extra buttons in the main bottom floating navigation bar (MainTabsActivity).
 */
public final class ColgramBottomBarConfig {

    private static final String PREFS_NAME = "colgram_prefs";
    private static final String KEY_EXTRA_BUTTONS = "bottom_bar_extra_buttons";

    public static final class ButtonDef {
        public final String id;
        public final String label;
        public final String fullName;
        public final int iconRes;
        public final int accentColor;

        ButtonDef(String id, String label, String fullName, int iconRes, int accentColor) {
            this.id = id;
            this.label = label;
            this.fullName = fullName;
            this.iconRes = iconRes;
            this.accentColor = accentColor;
        }
    }

    public static final ButtonDef[] ALL_BUTTONS = new ButtonDef[]{
            new ButtonDef("koliti", "Колити", "Колити AI ассистент", R.drawable.koliti_avatar, ColgramMenuChrome.COLOR_ROSE),
            new ButtonDef("saved", "Избранное", "Избранное (Saved Messages)", R.drawable.msg_saved, ColgramMenuChrome.COLOR_BLUE),
            new ButtonDef("archive", "Архив", "Архив чатов", R.drawable.msg_archive, ColgramMenuChrome.COLOR_INDIGO),
            new ButtonDef("proxy", "Обход", "Обход РКН и прокси", R.drawable.outline_shield_check, ColgramMenuChrome.COLOR_EMERALD),
            new ButtonDef("colgram", "Colgram", "Настройки Colgram", R.drawable.msg_settings, ColgramMenuChrome.COLOR_SLATE),
            new ButtonDef("design", "Дизайн", "Конструктор дизайна", R.drawable.msg_customize, ColgramMenuChrome.COLOR_CRIMSON),
            new ButtonDef("files", "Файлы", "Файлы и песочница Colgram", R.drawable.msg_folders, ColgramMenuChrome.COLOR_TEAL),
            new ButtonDef("calls", "Звонки", "Журнал звонков", R.drawable.msg_calls, ColgramMenuChrome.COLOR_EMERALD),
            new ButtonDef("tempmail", "Почта", "Временная почта Colgram", R.drawable.msg_msgbubble3, ColgramMenuChrome.COLOR_CYAN),
            new ButtonDef("plugins", "Плагины", "Плагины Python", R.drawable.msg_bot, ColgramMenuChrome.COLOR_VIOLET),
            new ButtonDef("ghost", "Призрак", "Невидимка (оффлайн + скрытое чтение)", R.drawable.msg_secret, ColgramMenuChrome.COLOR_CRIMSON),
            new ButtonDef("theme", "Тема", "Быстрая смена темы (День / Ночь)", R.drawable.msg_theme, ColgramMenuChrome.COLOR_AMBER),
            new ButtonDef("search", "Поиск", "Глобальный поиск Telegram", R.drawable.msg_search, ColgramMenuChrome.COLOR_BLUE)
    };

    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile List<String> cachedEnabledIds;
    private static long lastActionMs;

    private ColgramBottomBarConfig() {}

    public static void addChangeListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeChangeListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    private static void notifyListeners() {
        for (Runnable r : LISTENERS) {
            try {
                r.run();
            } catch (Throwable ignored) {}
        }
    }

    private static SharedPreferences getPrefs() {
        Context app = ApplicationLoader.applicationContext;
        if (app == null) return null;
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static List<String> getEnabledIds() {
        List<String> cached = cachedEnabledIds;
        if (cached != null) {
            return cached;
        }
        SharedPreferences prefs = getPrefs();
        String raw = prefs != null ? prefs.getString(KEY_EXTRA_BUTTONS, "") : "";
        List<String> parsed = parseOrderedValidIds(raw);
        cachedEnabledIds = parsed;
        return parsed;
    }

    public static void setEnabledIds(List<String> ids) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null) {
                    String clean = id.trim();
                    if (findDef(clean) != null) {
                        unique.add(clean);
                    }
                }
            }
        }
        List<String> normalized = Collections.unmodifiableList(new ArrayList<>(unique));
        cachedEnabledIds = normalized;
        SharedPreferences prefs = getPrefs();
        if (prefs != null) {
            prefs.edit().putString(KEY_EXTRA_BUTTONS, TextUtils.join(",", normalized)).apply();
        }
        notifyListeners();
    }

    private static List<String> parseOrderedValidIds(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String[] parts = raw.split(",");
        for (String p : parts) {
            String clean = p.trim();
            if (!clean.isEmpty() && findDef(clean) != null) {
                out.add(clean);
            }
        }
        if (out.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }

    public static int getPageCount() {
        int extra = getEnabledIds().size();
        return extra <= 0 ? 1 : (1 + (extra + 3) / 4);
    }

    public static String getSummaryText() {
        int count = getEnabledIds().size();
        if (count <= 0) {
            return "Стандартно (4)";
        }
        return "+" + count + " доп. · " + getPageCount() + " стр.";
    }

    private static boolean isButtonAvailable(ButtonDef def) {
        return def != null && (ColgramBuildFlags.KOLITI_ENABLED || !"koliti".equals(def.id));
    }

    public static ButtonDef findDef(String id) {
        if (id == null) return null;
        for (ButtonDef def : ALL_BUTTONS) {
            if (def.id.equals(id) && isButtonAvailable(def)) return def;
        }
        return null;
    }

    public static boolean isActionActive(String id) {
        if ("ghost".equals(id)) {
            return ColgramConfig.isQuickGhostActive();
        }
        if ("proxy".equals(id)) {
            return org.colgram.core.ColgramProxyManager.hasActiveRouteForUi() || ColgramConfig.isDpiBypassEnabled();
        }
        if ("theme".equals(id)) {
            return Theme.isCurrentThemeDark();
        }
        return false;
    }

    public static void executeAction(BaseFragment hostFragment, String id, Runnable onUiChanged) {
        if (hostFragment == null || hostFragment.isFinishing()) return;
        Activity act = hostFragment.getParentActivity();
        Context appCtx = act != null ? act.getApplicationContext() : ApplicationLoader.applicationContext;
        if (appCtx == null || id == null) return;
        int currentAccount = hostFragment.getCurrentAccount();

        if ("ghost".equals(id)) {
            boolean next = !ColgramConfig.isQuickGhostActive();
            ColgramConfig.setQuickGhostMode(next);
            if (next) {
                ColgramQuickBarView.triggerGhostOfflineAllAccounts();
            }
            if (onUiChanged != null) onUiChanged.run();
            Toast.makeText(appCtx,
                    next ? "Невидимка ВКЛ: оффлайн и скрытое чтение"
                            : "Невидимка ВЫКЛ: обычный онлайн статус",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if ("theme".equals(id)) {
            try {
                boolean currentlyDark = Theme.isCurrentThemeDark();
                Theme.ThemeInfo target = currentlyDark
                        ? Theme.getTheme("Blue")
                        : Theme.getTheme("Dark Blue");
                if (target == null) {
                    target = currentlyDark ? Theme.getTheme("Day") : Theme.getCurrentNightTheme();
                }
                if (target != null) {
                    Theme.applyTheme(target, !currentlyDark);
                    LaunchActivity.colgramSyncAllPanesAfterTheme(target, !currentlyDark);
                }
                if (onUiChanged != null) onUiChanged.run();
                Toast.makeText(appCtx,
                        currentlyDark ? "Светлая тема включена" : "Тёмная тема включена",
                        Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
            return;
        }

        if (hostFragment.getParentLayout() == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastActionMs < 360L) return;
        lastActionMs = now;

        switch (id) {
            case "koliti":
                if (ColgramBuildFlags.KOLITI_ENABLED) {
                    ColgramAiSheet.show(hostFragment, null, null);
                }
                break;
            case "saved": {
                long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (selfId != 0) {
                    Bundle args = new Bundle();
                    args.putLong("user_id", selfId);
                    hostFragment.presentFragment(new ChatActivity(args));
                }
                break;
            }
            case "archive": {
                Bundle args = new Bundle();
                args.putInt("folderId", 1);
                hostFragment.presentFragment(new DialogsActivity(args));
                break;
            }
            case "proxy":
                org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
                hostFragment.presentFragment(new ProxyListActivity());
                break;
            case "colgram":
                hostFragment.presentFragment(new ColgramSettingsActivity());
                break;
            case "design":
                hostFragment.presentFragment(new ColgramDesignStudioActivity());
                break;
            case "files":
                hostFragment.presentFragment(new ColgramFilesActivity());
                break;
            case "calls": {
                Bundle args = new Bundle();
                args.putBoolean("needFinishFragment", false);
                hostFragment.presentFragment(new CallLogActivity(args));
                break;
            }
            case "tempmail":
                hostFragment.presentFragment(new ColgramTempMailActivity());
                break;
            case "plugins":
                hostFragment.presentFragment(new ColgramPluginsActivity());
                break;
            case "search":
                hostFragment.presentFragment(ColgramGlobalSearchView.createStandaloneFragment());
                break;
            default:
                break;
        }
    }

    public static void showEditorSheet(BaseFragment hostFragment, Runnable onApplied) {
        if (hostFragment == null || hostFragment.isFinishing() || hostFragment.getVisibleDialog() != null) return;
        final Activity act = hostFragment.getParentActivity();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final Context appCtx = act.getApplicationContext();

        final List<String> current = getEnabledIds();
        final boolean[] checked = new boolean[ALL_BUTTONS.length];
        for (int i = 0; i < ALL_BUTTONS.length; i++) {
            checked[i] = isButtonAvailable(ALL_BUTTONS[i]) && current.contains(ALL_BUTTONS[i].id);
        }

        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        final boolean dark = pal.dark;
        final int cTextPrimary = dark ? 0xFFF8FAFC : 0xFF0F172A;
        final int cTextSecondary = dark ? 0xFF94A3B8 : 0xFF64748B;
        final int cCard = dark ? 0x16FFFFFF : 0x0A0F172A;
        final int cBorder = dark ? 0x22FFFFFF : 0x160F172A;

        final BottomSheet sheet = new BottomSheet(act, false, hostFragment.getResourceProvider());
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(16) + AndroidUtilities.navigationBarHeight);
        ColgramAeroStyler.applyFlagshipSheetChrome(sheet, root, pal);

        View handle = new View(act);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(dark ? 0x38FFFFFF : 0x260F172A);
        hBg.setCornerRadius(dp(99));
        handle.setBackground(hBg);
        root.addView(handle, LayoutHelper.createLinear(40, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        TextView titleTv = new TextView(act);
        titleTv.setText("Кнопки нижней панели");
        titleTv.setTextColor(cTextPrimary);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f);
        titleTv.setTypeface(AndroidUtilities.bold());
        root.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 3));

        final TextView livePageInfoTv = new TextView(act);
        livePageInfoTv.setTextColor(cTextSecondary);
        livePageInfoTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        root.addView(livePageInfoTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        final Runnable[] rowSyncs = new Runnable[ALL_BUTTONS.length];
        Runnable updateHeaderMeta = () -> {
            int cnt = 0;
            for (boolean b : checked) if (b) cnt++;
            int pages = cnt <= 0 ? 1 : (1 + (cnt + 3) / 4);
            livePageInfoTv.setText(cnt <= 0
                    ? "Стандартно: 4 вкладки (1 страница)"
                    : "Выбрано +" + cnt + " кнопок · свайп по нижней панели: " + pages + " стр.");
        };
        updateHeaderMeta.run();

        // Quick preset buttons ("Сбросить", "Популярные (+4)")
        LinearLayout presetsRow = new LinearLayout(act);
        presetsRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView resetQuickBtn = makeQuickPresetBtn(act, "Сбросить", dark ? 0x20FFFFFF : 0x100F172A, cTextPrimary);
        TextView popularQuickBtn = makeQuickPresetBtn(act, "Популярные (+4)", withAlpha(pal.accentPrimary, dark ? 0x32 : 0x1C), dark ? lighten(pal.accentPrimary) : pal.accentPrimary);
        presetsRow.addView(resetQuickBtn, LayoutHelper.createLinear(0, 34, 1f, 0, 0, 8, 0));
        presetsRow.addView(popularQuickBtn, LayoutHelper.createLinear(0, 34, 1.3f));
        root.addView(presetsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        final int maxH = Math.round(AndroidUtilities.displaySize.y * 0.52f);
        ScrollView scroll = new ScrollView(act) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int hMode = MeasureSpec.getMode(heightMeasureSpec);
                int hSize = MeasureSpec.getSize(heightMeasureSpec);
                int cappedSpec = (hMode == MeasureSpec.UNSPECIFIED)
                        ? MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                        : MeasureSpec.makeMeasureSpec(Math.min(hSize, maxH), hMode);
                super.onMeasure(widthMeasureSpec, cappedSpec);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout listCol = new LinearLayout(act);
        listCol.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        for (int i = 0; i < ALL_BUTTONS.length; i++) {
            final int idx = i;
            final ButtonDef def = ALL_BUTTONS[i];
            if (!isButtonAvailable(def)) continue;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), dp(9), dp(12), dp(9));

            ImageView ic = new ImageView(act);
            GradientDrawable icBg = new GradientDrawable();
            icBg.setCornerRadius(dp(11));
            icBg.setColor(withAlpha(def.accentColor, dark ? 0x2E : 0x1E));
            ic.setBackground(icBg);
            if (def.iconRes == R.drawable.koliti_avatar) {
                ColgramAeroStyler.applyKolitiAvatar(ic);
                ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ic.setPadding(dp(4), dp(4), dp(4), dp(4));
                ic.clearColorFilter();
            } else {
                ic.setImageResource(def.iconRes);
                ic.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                ic.setPadding(dp(7), dp(7), dp(7), dp(7));
                ic.setColorFilter(dark ? lighten(def.accentColor) : def.accentColor, PorterDuff.Mode.SRC_IN);
            }
            row.addView(ic, LayoutHelper.createLinear(34, 34, 0, 0, 11, 0));

            LinearLayout textCol = new LinearLayout(act);
            textCol.setOrientation(LinearLayout.VERTICAL);
            TextView lbl = new TextView(act);
            lbl.setText(def.label);
            lbl.setTextColor(cTextPrimary);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
            lbl.setTypeface(AndroidUtilities.bold());
            lbl.setMaxLines(1);
            lbl.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(lbl);

            TextView sub = new TextView(act);
            sub.setText(def.fullName);
            sub.setTextColor(cTextSecondary);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(sub);
            row.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

            TextView badge = new TextView(act);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            badge.setTypeface(AndroidUtilities.bold());
            badge.setPadding(dp(10), dp(4), dp(10), dp(4));
            row.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            Runnable syncRowStyle = () -> {
                boolean on = checked[idx];
                GradientDrawable rBg = new GradientDrawable();
                rBg.setCornerRadius(dp(14));
                rBg.setColor(on ? withAlpha(def.accentColor, dark ? 0x20 : 0x12) : cCard);
                rBg.setStroke(dp(1), on ? withAlpha(def.accentColor, dark ? 0x55 : 0x44) : cBorder);
                row.setBackground(rBg);

                badge.setText(on ? "✓ ВКЛ" : "ВЫКЛ");
                badge.setTextColor(on ? (dark ? lighten(def.accentColor) : def.accentColor) : cTextSecondary);
                GradientDrawable bBg = new GradientDrawable();
                bBg.setCornerRadius(dp(999));
                bBg.setColor(on ? withAlpha(def.accentColor, dark ? 0x2C : 0x1A) : (dark ? 0x14FFFFFF : 0x0C0F172A));
                badge.setBackground(bBg);
            };
            rowSyncs[idx] = syncRowStyle;
            syncRowStyle.run();
            ColgramMenuChrome.attachSpringPress(row);
            row.setOnClickListener(v -> {
                checked[idx] = !checked[idx];
                syncRowStyle.run();
                updateHeaderMeta.run();
            });
            listCol.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 7));
        }

        resetQuickBtn.setOnClickListener(v -> {
            Arrays.fill(checked, false);
            for (Runnable r : rowSyncs) if (r != null) r.run();
            updateHeaderMeta.run();
        });

        popularQuickBtn.setOnClickListener(v -> {
            List<String> pop = ColgramBuildFlags.KOLITI_ENABLED
                    ? Arrays.asList("koliti", "saved", "proxy", "ghost")
                    : Arrays.asList("saved", "proxy", "ghost", "colgram");
            for (int i = 0; i < ALL_BUTTONS.length; i++) {
                checked[i] = isButtonAvailable(ALL_BUTTONS[i]) && pop.contains(ALL_BUTTONS[i].id);
                if (rowSyncs[i] != null) rowSyncs[i].run();
            }
            updateHeaderMeta.run();
        });

        TextView saveBtn = new TextView(act);
        saveBtn.setText("Применить");
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        saveBtn.setTypeface(AndroidUtilities.bold());
        GradientDrawable saveBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFE11D48, 0xFFF43F5E});
        saveBg.setCornerRadius(dp(14));
        saveBtn.setBackground(saveBg);
        ColgramMenuChrome.attachSpringPress(saveBtn);
        saveBtn.setOnClickListener(v -> {
            ArrayList<String> selected = new ArrayList<>();
            for (int i = 0; i < ALL_BUTTONS.length; i++) {
                if (checked[i] && isButtonAvailable(ALL_BUTTONS[i])) selected.add(ALL_BUTTONS[i].id);
            }
            setEnabledIds(selected);
            if (onApplied != null) onApplied.run();
            sheet.dismiss();
            Toast.makeText(appCtx,
                    selected.isEmpty()
                            ? "Нижняя панель: стандартные 4 вкладки"
                            : "Нижняя панель: +" + selected.size() + " доп. (" + getPageCount() + " стр.)",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46));

        sheet.setCustomView(root);
        hostFragment.showDialog(sheet);
    }

    private static TextView makeQuickPresetBtn(Context ctx, String text, int bgColor, int fgColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(fgColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        tv.setTypeface(AndroidUtilities.bold());
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(11));
        gd.setColor(bgColor);
        tv.setBackground(gd);
        ColgramMenuChrome.attachSpringPress(tv);
        return tv;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int lighten(int color) {
        int r = Math.min(255, Math.round(Color.red(color) + (255 - Color.red(color)) * 0.28f));
        int g = Math.min(255, Math.round(Color.green(color) + (255 - Color.green(color)) * 0.28f));
        int b = Math.min(255, Math.round(Color.blue(color) + (255 - Color.blue(color)) * 0.28f));
        return Color.rgb(r, g, b);
    }
}
