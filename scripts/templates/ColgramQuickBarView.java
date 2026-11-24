package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.colgram.core.ColgramAiConfig;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramKolitiVoice;
import org.colgram.core.ColgramProxyManager;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Customizable horizontal Quick Access Panel mounted in DialogsActivity's top panel.
 * Gives 1-tap access to Ghost Mode (Невидимка), RKN Bypass, Koliti AI, Saved Messages,
 * Theme switch, Voice-over, Anti-Delete, Plugins, TempMail, Anti-Spam, and Colgram Settings,
 * plus a built-in "+" editor to add/remove shortcuts at any time.
 */
public class ColgramQuickBarView extends FrameLayout {

    public static final class ActionDef {
        public final String id;
        public final String label;
        public final String fullName;
        public final int iconRes;
        public final int accentColor;

        ActionDef(String id, String label, String fullName, int iconRes, int accentColor) {
            this.id = id;
            this.label = label;
            this.fullName = fullName;
            this.iconRes = iconRes;
            this.accentColor = accentColor;
        }
    }

    private static final ActionDef[] ALL_ACTIONS = new ActionDef[]{
            new ActionDef("ghost", "Невидимка", "Невидимка (оффлайн + скрытое чтение)", R.drawable.msg_secret, ColgramMenuChrome.COLOR_CRIMSON),
            new ActionDef("proxy", "Обход", "Обход РКН и прокси", R.drawable.outline_shield_check, ColgramMenuChrome.COLOR_EMERALD),
            new ActionDef("koliti", "Koliti", "Koliti AI ассистент", R.drawable.koliti_avatar, ColgramMenuChrome.COLOR_ROSE),
            new ActionDef("saved", "Избранное", "Избранное (Saved Messages)", R.drawable.msg_saved, ColgramMenuChrome.COLOR_BLUE),
            new ActionDef("theme", "Тема", "Быстрая смена темы (День / Ночь)", R.drawable.msg_theme, ColgramMenuChrome.COLOR_AMBER),
            new ActionDef("design", "Дизайн", "Конструктор дизайна и интерфейса", R.drawable.msg_customize, ColgramMenuChrome.COLOR_INDIGO),
            new ActionDef("voice", "Озвучка", "Озвучка ответов Koliti", R.drawable.msg_voice_unmuted, ColgramMenuChrome.COLOR_VIOLET),
            new ActionDef("antidelete", "Анти-удал.", "Сохранение удалённых сообщений", R.drawable.msg_delete, ColgramMenuChrome.COLOR_INDIGO),
            new ActionDef("antispam", "Антиспам", "Фильтр спама Colgram", R.drawable.msg_block, ColgramMenuChrome.COLOR_AMBER),
            new ActionDef("tempmail", "Почта", "Временная почта Colgram", R.drawable.msg_msgbubble3, ColgramMenuChrome.COLOR_CYAN),
            new ActionDef("plugins", "Плагины", "Плагины Colgram", R.drawable.msg_bot, ColgramMenuChrome.COLOR_VIOLET),
            new ActionDef("settings", "Colgram", "Настройки Colgram", R.drawable.msg_settings, ColgramMenuChrome.COLOR_SLATE)
    };

    private final BaseFragment hostFragment;
    private final HorizontalScrollView scrollView;
    private final LinearLayout chipsRow;
    private Runnable onVisibilityChangeRequested;
    private Runnable onCollapseToButtonRequested;
    private long lastNavActionMs;

    public ColgramQuickBarView(Context context, BaseFragment hostFragment) {
        super(context);
        this.hostFragment = hostFragment;
        setClipToPadding(false);
        setPadding(dp(4), dp(4), dp(4), dp(4));

        scrollView = new HorizontalScrollView(context);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setClipToPadding(false);

        chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        scrollView.addView(chipsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scrollView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 34, Gravity.CENTER_VERTICAL));

        rebuild();
    }

    public void setOnVisibilityChangeRequested(Runnable callback) {
        this.onVisibilityChangeRequested = callback;
    }

    public void setOnCollapseToButtonRequested(Runnable callback) {
        this.onCollapseToButtonRequested = callback;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(42), MeasureSpec.EXACTLY)
        );
    }

    public void rebuild() {
        chipsRow.removeAllViews();
        Context ctx = getContext();
        if (ctx == null) return;
        ColgramConfig.init(ctx);
        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();

        List<String> enabledIds = ColgramConfig.getQuickBarItems();
        for (String id : enabledIds) {
            ActionDef def = findDef(id);
            if (def == null) continue;
            View pill = buildActionPill(ctx, def, pal);
            chipsRow.addView(pill, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        }

        // Always append compact "+" customize pill & collapse-to-button pill
        View addPill = buildCustomizePill(ctx, pal);
        chipsRow.addView(addPill, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, 0, 0, 5, 0));

        if (onCollapseToButtonRequested != null) {
            TextView collapseBtn = new TextView(ctx);
            collapseBtn.setText("\u25B4");
            collapseBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
            collapseBtn.setTypeface(AndroidUtilities.bold());
            collapseBtn.setGravity(Gravity.CENTER);
            collapseBtn.setTextColor(pal.textSecondary);
            collapseBtn.setPadding(dp(10), 0, dp(10), 0);
            GradientDrawable cgd = new GradientDrawable();
            cgd.setCornerRadius(dp(16));
            cgd.setColor(pal.dark ? 0xAA161922 : 0xD8FFFFFF);
            cgd.setStroke(Math.max(1, dp(0.8f)), pal.dark ? 0x26FFFFFF : 0x1E0F172A);
            collapseBtn.setBackground(cgd);
            collapseBtn.setTag("colgram_quick_collapse");
            collapseBtn.setContentDescription("Свернуть панель в кнопку");
            ColgramMenuChrome.attachSpringPress(collapseBtn);
            collapseBtn.setOnClickListener(v -> {
                if (onCollapseToButtonRequested != null) onCollapseToButtonRequested.run();
            });
            chipsRow.addView(collapseBtn, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        }
    }

    /**
     * Opens a sleek anchored Quick Access popup menu when the horizontal strip has collapsed
     * into the top ActionBar button (on scroll or user collapse), matching Search's UX!
     */
    public void showQuickPopup(View anchor, boolean currentlyPinnedCollapsed, Runnable onTogglePinCollapsed) {
        Context ctx = getContext();
        if (ctx == null || hostFragment == null || anchor == null) return;
        org.telegram.ui.Components.ItemOptions io = org.telegram.ui.Components.ItemOptions.makeOptions(hostFragment, anchor);
        List<String> enabledIds = ColgramConfig.getQuickBarItems();
        for (String id : enabledIds) {
            ActionDef def = findDef(id);
            if (def == null) continue;
            boolean active = isActionActive(ctx, def.id);
            String title = buildDisplayLabel(ctx, def, active);
            int iconRes = def.iconRes == R.drawable.koliti_avatar ? R.drawable.msg_bot : def.iconRes;
            io.add(iconRes, title, () -> executeAction(ctx, def.id));
            org.telegram.ui.ActionBar.ActionBarMenuSubItem last = io.getLast();
            if (last != null) {
                last.setIconColor(active ? def.accentColor : (Theme.isCurrentThemeDark() ? 0xFFE2E8F0 : def.accentColor));
            }
        }
        io.addGap();
        io.add(R.drawable.msg_customize, "Настроить кнопки…", () -> showCustomizeDialog(ctx));
        if (onTogglePinCollapsed != null) {
            io.add(R.drawable.msg_list,
                    currentlyPinnedCollapsed ? "Развернуть полосу под поиском" : "Закрепить как кнопку",
                    onTogglePinCollapsed);
        }
        io.show();
    }

    private View buildActionPill(Context ctx, ActionDef def, ColgramAeroStyler.Palette pal) {
        boolean active = isActionActive(ctx, def.id);
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(9), 0, dp(10), 0);

        int bgCol;
        int strokeCol;
        int inkCol;
        int iconTint;
        if (active) {
            bgCol = withAlpha(def.accentColor, pal.dark ? 0x34 : 0x20);
            strokeCol = withAlpha(def.accentColor, pal.dark ? 0xAA : 0x88);
            inkCol = pal.dark ? 0xFFF8FAFC : darken(def.accentColor);
            iconTint = pal.dark ? lighten(def.accentColor) : def.accentColor;
        } else {
            bgCol = pal.dark ? 0xCC161922 : 0xEBFFFFFF;
            strokeCol = pal.dark ? 0x24FFFFFF : 0x1E0F172A;
            inkCol = pal.textPrimary;
            iconTint = pal.dark ? lighten(def.accentColor) : def.accentColor;
        }

        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(16));
        gd.setColor(bgCol);
        gd.setStroke(Math.max(1, dp(0.9f)), strokeCol);
        pill.setBackground(gd);

        ImageView iv = new ImageView(ctx);
        if (def.iconRes == R.drawable.koliti_avatar) {
            ColgramAeroStyler.applyKolitiAvatar(iv);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.clearColorFilter();
        } else {
            iv.setImageResource(def.iconRes);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setColorFilter(iconTint, PorterDuff.Mode.SRC_IN);
        }
        pill.addView(iv, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 5, 0));

        TextView tv = new TextView(ctx);
        tv.setText(buildDisplayLabel(ctx, def, active));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(inkCol);
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        pill.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        pill.setTag("colgram_quick_" + def.id);
        pill.setContentDescription(def.fullName);
        ColgramMenuChrome.attachSpringPress(pill);
        pill.setOnClickListener(v -> executeAction(ctx, def.id));
        pill.setOnLongClickListener(v -> {
            showCustomizeDialog(ctx);
            return true;
        });
        return pill;
    }

    private View buildCustomizePill(Context ctx, ColgramAeroStyler.Palette pal) {
        TextView btn = new TextView(ctx);
        btn.setText("+");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
        btn.setTypeface(AndroidUtilities.bold());
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(pal.textPrimary);
        btn.setPadding(dp(11), 0, dp(11), 0);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(16));
        gd.setColor(pal.dark ? 0xBB161922 : 0xE6FFFFFF);
        gd.setStroke(Math.max(1, dp(0.9f)), pal.dark ? 0x33FFFFFF : 0x240F172A);
        btn.setBackground(gd);
        btn.setTag("colgram_quick_customize");
        btn.setContentDescription("Настроить панель быстрого доступа");
        ColgramMenuChrome.attachSpringPress(btn);
        btn.setOnClickListener(v -> showCustomizeDialog(ctx));
        return btn;
    }

    private String buildDisplayLabel(Context ctx, ActionDef def, boolean active) {
        if ("koliti".equals(def.id)) {
            return org.colgram.core.ColgramDesignConfig.getKolitiDisplayName();
        }
        if ("ghost".equals(def.id)) {
            return active ? "Невидимка ВКЛ" : "Невидимка";
        }
        if ("voice".equals(def.id)) {
            return active ? "Озвучка ВКЛ" : "Озвучка";
        }
        return def.label;
    }

    private boolean isActionActive(Context ctx, String id) {
        switch (id) {
            case "ghost":
                return ColgramConfig.isQuickGhostActive();
            case "proxy":
                return ColgramProxyManager.hasActiveRouteForUi() || ColgramConfig.isDpiBypassEnabled();
            case "voice":
                return ColgramAiConfig.isVoiceOverEnabled(ctx);
            case "antidelete":
                return ColgramConfig.isAntiDeleteEnabled();
            default:
                return false;
        }
    }

    private boolean canLaunchNavAction() {
        if (hostFragment == null || hostFragment.isFinishing() || hostFragment.getParentLayout() == null) {
            return false;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastNavActionMs < 380L) {
            return false;
        }
        lastNavActionMs = now;
        return true;
    }

    private void executeAction(Context ctx, String id) {
        if (hostFragment == null || hostFragment.isFinishing() || ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        int currentAccount = hostFragment.getCurrentAccount();
        switch (id) {
            case "ghost": {
                boolean next = !ColgramConfig.isQuickGhostActive();
                ColgramConfig.setQuickGhostMode(next);
                if (next) {
                    triggerGhostOfflineAllAccounts();
                }
                rebuild();
                Toast.makeText(appCtx,
                        next ? "Невидимка ВКЛ: оффлайн, чтение и набор скрыты"
                                : "Невидимка ВЫКЛ: обычный онлайн статус",
                        Toast.LENGTH_SHORT).show();
                break;
            }
            case "proxy": {
                if (!canLaunchNavAction()) return;
                ColgramProxyManager.populateSharedConfigProxies();
                hostFragment.presentFragment(new ProxyListActivity());
                break;
            }
            case "koliti": {
                if (!canLaunchNavAction()) return;
                ColgramAiSheet.show(hostFragment, null, null);
                break;
            }
            case "saved": {
                if (!canLaunchNavAction()) return;
                long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (selfId != 0) {
                    Bundle args = new Bundle();
                    args.putLong("user_id", selfId);
                    hostFragment.presentFragment(new ChatActivity(args));
                }
                break;
            }
            case "theme": {
                toggleQuickTheme(appCtx);
                rebuild();
                break;
            }
            case "design": {
                if (!canLaunchNavAction()) return;
                hostFragment.presentFragment(new ColgramDesignStudioActivity());
                break;
            }
            case "voice": {
                boolean next = !ColgramAiConfig.isVoiceOverEnabled(appCtx);
                ColgramAiConfig.setVoiceOverEnabled(appCtx, next);
                if (!next) {
                    ColgramKolitiVoice.stopSpeaking();
                }
                rebuild();
                Toast.makeText(appCtx,
                        next ? "Озвучка ответов Koliti включена" : "Озвучка ответов Koliti отключена",
                        Toast.LENGTH_SHORT).show();
                break;
            }
            case "antidelete": {
                boolean next = !ColgramConfig.isAntiDeleteEnabled();
                ColgramConfig.setAntiDeleteEnabled(next);
                rebuild();
                Toast.makeText(appCtx,
                        next ? "Анти-удаление включено" : "Анти-удаление отключено",
                        Toast.LENGTH_SHORT).show();
                break;
            }
            case "antispam": {
                if (!canLaunchNavAction()) return;
                hostFragment.presentFragment(new ColgramAntiSpamActivity());
                break;
            }
            case "tempmail": {
                if (!canLaunchNavAction()) return;
                hostFragment.presentFragment(new ColgramTempMailActivity());
                break;
            }
            case "plugins": {
                if (!canLaunchNavAction()) return;
                hostFragment.presentFragment(new ColgramPluginsActivity());
                break;
            }
            case "settings": {
                if (!canLaunchNavAction()) return;
                hostFragment.presentFragment(new ColgramSettingsActivity());
                break;
            }
            default:
                break;
        }
    }

    public static void triggerGhostOfflineAllAccounts() {
        for (int acc = 0; acc < UserConfig.MAX_ACCOUNT_COUNT; acc++) {
            try {
                if (UserConfig.getInstance(acc).isClientActivated()) {
                    MessagesController.getInstance(acc).scheduleGhostOfflineAfterActivity();
                }
            } catch (Throwable ignored) {}
        }
    }

    private void toggleQuickTheme(Context ctx) {
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
            Toast.makeText(ctx.getApplicationContext(),
                    currentlyDark ? "Светлая тема включена" : "Тёмная тема включена",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    public void showCustomizeDialog(Context ctx) {
        if (ctx == null) return;
        android.app.Activity act = AndroidUtilities.findActivity(ctx);
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        if (hostFragment != null && (hostFragment.isFinishing() || hostFragment.getVisibleDialog() != null)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastNavActionMs < 350L) return;
        lastNavActionMs = now;
        final Context appCtx = act.getApplicationContext();
        List<String> current = ColgramConfig.getQuickBarItems();
        final boolean[] checked = new boolean[ALL_ACTIONS.length];
        for (int i = 0; i < ALL_ACTIONS.length; i++) {
            checked[i] = current.contains(ALL_ACTIONS[i].id);
        }

        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        boolean dark = pal.dark;
        int cTextPrimary = dark ? 0xFFF8FAFC : 0xFF0F172A;
        int cTextSecondary = dark ? 0xFF94A3B8 : 0xFF64748B;
        int cCard = dark ? 0x16FFFFFF : 0x0A0F172A;
        int cBorder = dark ? 0x22FFFFFF : 0x160F172A;

        org.telegram.ui.ActionBar.BottomSheet sheet = new org.telegram.ui.ActionBar.BottomSheet(act, false);
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
        titleTv.setText("Панель быстрого доступа");
        titleTv.setTextColor(cTextPrimary);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f);
        titleTv.setTypeface(AndroidUtilities.bold());
        root.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));

        TextView subTv = new TextView(act);
        subTv.setText("Выберите кнопки для мгновенного доступа над чатами");
        subTv.setTextColor(cTextSecondary);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        root.addView(subTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        android.widget.ScrollView scroll = new android.widget.ScrollView(act) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = Math.round(AndroidUtilities.displaySize.y * 0.56f);
                int hMode = View.MeasureSpec.getMode(heightMeasureSpec);
                int hSize = View.MeasureSpec.getSize(heightMeasureSpec);
                int cappedSpec = (hMode == View.MeasureSpec.UNSPECIFIED)
                        ? View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
                        : View.MeasureSpec.makeMeasureSpec(Math.min(hSize, maxH), hMode);
                super.onMeasure(widthMeasureSpec, cappedSpec);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout listCol = new LinearLayout(act);
        listCol.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        for (int i = 0; i < ALL_ACTIONS.length; i++) {
            final int idx = i;
            final ActionDef def = ALL_ACTIONS[i];
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

            TextView lbl = new TextView(act);
            lbl.setText(def.fullName);
            lbl.setTextColor(cTextPrimary);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            lbl.setTypeface(AndroidUtilities.bold());
            lbl.setMaxLines(2);
            lbl.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(lbl, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

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
            syncRowStyle.run();
            ColgramMenuChrome.attachSpringPress(row);
            row.setOnClickListener(v -> {
                checked[idx] = !checked[idx];
                syncRowStyle.run();
            });
            listCol.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 7));
        }

        LinearLayout footerBtns = new LinearLayout(act);
        footerBtns.setOrientation(LinearLayout.HORIZONTAL);

        TextView hideBtn = new TextView(act);
        hideBtn.setText("Скрыть");
        hideBtn.setGravity(Gravity.CENTER);
        hideBtn.setTextColor(dark ? 0xFFFDA4AF : 0xFFE11D48);
        hideBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        hideBtn.setTypeface(AndroidUtilities.bold());
        GradientDrawable hideBg = new GradientDrawable();
        hideBg.setCornerRadius(dp(14));
        hideBg.setColor(dark ? 0x22E11D48 : 0x14E11D48);
        hideBg.setStroke(dp(1), 0x44E11D48);
        hideBtn.setBackground(hideBg);
        ColgramMenuChrome.attachSpringPress(hideBtn);
        hideBtn.setOnClickListener(v -> {
            ColgramConfig.setQuickBarEnabled(false);
            if (onVisibilityChangeRequested != null) onVisibilityChangeRequested.run();
            sheet.dismiss();
            Toast.makeText(appCtx, "Панель скрыта (вернуть: Настройки Colgram)", Toast.LENGTH_SHORT).show();
        });
        footerBtns.addView(hideBtn, LayoutHelper.createLinear(0, 44, 0.9f, 0, 0, 8, 0));

        TextView saveBtn = new TextView(act);
        saveBtn.setText("Сохранить");
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        saveBtn.setTypeface(AndroidUtilities.bold());
        GradientDrawable saveBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFE11D48, 0xFFF43F5E});
        saveBg.setCornerRadius(dp(14));
        saveBtn.setBackground(saveBg);
        ColgramMenuChrome.attachSpringPress(saveBtn);
        saveBtn.setOnClickListener(v -> {
            ArrayList<String> selected = new ArrayList<>();
            for (int i = 0; i < ALL_ACTIONS.length; i++) {
                if (checked[i]) selected.add(ALL_ACTIONS[i].id);
            }
            ColgramConfig.setQuickBarItems(selected);
            rebuild();
            sheet.dismiss();
            Toast.makeText(appCtx, "Панель обновлена (" + selected.size() + " кнопок)", Toast.LENGTH_SHORT).show();
        });
        footerBtns.addView(saveBtn, LayoutHelper.createLinear(0, 44, 1.5f));
        root.addView(footerBtns, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        sheet.setCustomView(root);
        if (hostFragment != null) {
            hostFragment.showDialog(sheet);
        } else {
            ColgramGlobalOrbController.pushModalSuppression();
            sheet.setOnDismissListener(d -> ColgramGlobalOrbController.popModalSuppression());
            try {
                sheet.show();
            } catch (Throwable ignored) {
                ColgramGlobalOrbController.popModalSuppression();
            }
        }
    }

    private static ActionDef findDef(String id) {
        if (id == null) return null;
        for (ActionDef def : ALL_ACTIONS) {
            if (def.id.equals(id)) return def;
        }
        return null;
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

    private static int darken(int color) {
        return Color.rgb(
                Math.max(0, Math.round(Color.red(color) * 0.78f)),
                Math.max(0, Math.round(Color.green(color) * 0.78f)),
                Math.max(0, Math.round(Color.blue(color) * 0.78f))
        );
    }
}
