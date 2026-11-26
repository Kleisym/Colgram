package org.telegram.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.colgram.core.ColgramBuildFlags;
import org.colgram.core.ColgramProxyDoctor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;
import java.util.Random;

/**
 * Interactive Easter Eggs ("пасхалки") & Secret Arcade for Colgram:
 * - Interactive 60fps Koliti Coca-Cola Fizz Slide ("Кола-горка Koliti") with squash-and-stretch physics & tap-jumps
 * - Poppable translucent Coca-Cola Fizz Bubble Shower floating across any Colgram screen
 * - 5 taps on "Colgram" title in chat list -> Secret Arcade & Matrix Digital Rain Terminal
 * - 3 taps on Koliti avatar (Studio / Koliti Sheet) or 4 taps on any Hero Banner icon -> Cola-Slide + Fizz Bubbles!
 * - Secret chat commands (.cola, .кола, .горка, .fizz, .пузыри, .koliti, .matrix, /konami)
 */
public final class ColgramEasterEggs {

    private static final String PREFS = "colgram_easter_eggs";
    private static final String KEY_OVERDRIVE = "neon_overdrive_enabled";

    private static final String[] QUOTES = ColgramBuildFlags.KOLITI_ENABLED
            ? new String[]{
                "Кола-волна запущена! Тапай по горке — Koliti прыгает выше",
                "Пузырьки газировки можно лопать прямо пальцем",
                "Пасхалка! 5 тапов по заголовку Colgram откроют секретную аркаду",
                "Введи .cola или .пузыри в чате с Koliti для мгновенного запуска"
            }
            : new String[]{
                "Кола-волна запущена! Тапай по горке для прыжка",
                "Пузырьки газировки можно лопать прямо пальцем",
                "Пасхалка! 5 тапов по заголовку Colgram откроют секретную аркаду",
                "Тапни 3 раза по любому баннеру Colgram для запуска пасхалки"
            };

    private static long lastTitleTapMs = 0L;
    private static int titleTapCount = 0;

    private ColgramEasterEggs() {}

    public static boolean isOverdriveEnabled(Context ctx) {
        if (ctx == null) return true;
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OVERDRIVE, true);
    }

    public static void setOverdriveEnabled(Context ctx, boolean enabled) {
        if (ctx != null) {
            ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_OVERDRIVE, enabled).apply();
        }
    }

    /** Call when the user taps HeroBannerCell badge icon or Koliti avatar repeatedly. */
    public static void onHeroBadgeTap(View anchor) {
        if (anchor == null || anchor.getContext() == null) return;
        int cur = anchor.getTag() instanceof Integer ? (Integer) anchor.getTag() : 0;
        cur++;
        anchor.setTag(cur);
        anchor.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        anchor.animate().scaleX(1.18f).scaleY(1.18f).rotationBy(22f).setDuration(110)
                .withEndAction(() -> anchor.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(new OvershootInterpolator(1.5f)).setDuration(190).start())
                .start();

        if (cur == 2) {
            View root = anchor.getRootView();
            if (root instanceof ViewGroup) {
                launchColaBubbleShower((ViewGroup) root, 16);
            }
        } else if (cur >= 3) {
            anchor.setTag(0);
            View root = anchor.getRootView();
            if (root instanceof ViewGroup) {
                int[] loc = new int[2];
                anchor.getLocationInWindow(loc);
                ColgramConfettiView.burstOver((ViewGroup) root,
                        loc[0] + anchor.getWidth() * 0.5f,
                        loc[1] + anchor.getHeight() * 0.5f, 65);
            }
            Activity act = AndroidUtilities.findActivity(anchor.getContext());
            if (act != null) {
                showColaSlideSheet(act, LaunchActivity.getSafeLastFragment());
            } else {
                String msg = QUOTES[new Random().nextInt(QUOTES.length)];
                Toast.makeText(anchor.getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Tracks rapid 5-taps on "Colgram" ActionBar title; opens Secret Arcade + Cola-Slide on 5th tap. */
    public static boolean handleDialogsTitleTap(BaseFragment fragment, View fxRoot) {
        if (fragment == null || fragment.getParentActivity() == null) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastTitleTapMs > 1800L) {
            titleTapCount = 0;
        }
        lastTitleTapMs = now;
        titleTapCount++;
        if (titleTapCount >= 5) {
            titleTapCount = 0;
            if (fxRoot instanceof ViewGroup) {
                launchColaBubbleShower((ViewGroup) fxRoot, 26);
                ColgramConfettiView.burstOver((ViewGroup) fxRoot, fxRoot.getWidth() * 0.35f, AndroidUtilities.dp(72), 80);
            }
            showColaSlideSheet(fragment.getParentActivity(), fragment);
            return true;
        }
        return false;
    }

    /** Handles secret phrases entered inside Colgram AI sheet. Returns null if not an Easter egg trigger. */
    public static String matchSecretPrompt(String raw, View confettiAnchor) {
        if (raw == null) return null;
        String q = raw.trim().toLowerCase(Locale.ROOT);
        if (!q.equals(".koliti") && !q.equals(".aero") && !q.equals(".matrix") && !q.equals("idkfa")
                && !q.equals("/konami") && !q.equals("/secret") && !q.equals("easter egg")
                && !q.equals(".cola") && !q.equals(".кола") && !q.equals(".горка")
                && !q.equals(".fizz") && !q.equals(".bubbles") && !q.equals(".пузыри")) {
            return null;
        }
        if (confettiAnchor != null && confettiAnchor.getRootView() instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) confettiAnchor.getRootView();
            if (q.equals(".cola") || q.equals(".кола") || q.equals(".горка")
                    || q.equals(".fizz") || q.equals(".bubbles") || q.equals(".пузыри")) {
                launchColaBubbleShower(root, 34);
            } else {
                ColgramConfettiView.burstOver(root, root.getWidth() * 0.5f, root.getHeight() * 0.45f, 110);
            }
        }
        if (!ColgramBuildFlags.KOLITI_ENABLED) {
            return "Пасхалка Colgram активирована.\n\n"
                    + "• Секретные жесты:\n"
                    + "  — 3 нажатия на иконку любого баннера Colgram — Кола-горка\n"
                    + "  — 5 нажатий на заголовок Colgram в списке чатов — Секретная аркада";
        }
        return "Пасхалка Colgram x Koliti активирована.\n\n"
                + "• Ядро: Koliti Core Ultra (Qwen3.5 Hybrid Gated DeltaNet + FlashAttn)\n"
                + "• C++ движок: " + readNativeSystemInfo() + "\n"
                + "• Секретные команды и жесты:\n"
                + "  — .cola / .кола / .горка — интерактивная Кола-горка Koliti\n"
                + "  — .пузыри / .fizz — душ из лопающихся пузырьков газировки\n"
                + "  — 3 нажатия на иконку любого баннера или аватар Koliti — Кола-горка\n"
                + "  — 5 нажатий на заголовок Colgram в списке чатов — Секретная аркада";
    }

    /**
     * Executes interactive secret commands from Koliti chat (.cola, .кола, .горка, .fizz, .пузыри, .matrix, .koliti).
     * Returns true if handled as an interactive easter egg.
     */
    public static boolean tryHandleSecretCommand(String raw, BaseFragment parentFragment, View anchor) {
        if (raw == null) return false;
        String q = raw.trim().toLowerCase(Locale.ROOT);
        Activity act = parentFragment != null ? parentFragment.getParentActivity()
                : (anchor != null ? AndroidUtilities.findActivity(anchor.getContext()) : LaunchActivity.instance);
        if (q.equals(".cola") || q.equals(".кола") || q.equals(".горка")) {
            if (act != null) {
                showColaSlideSheet(act, parentFragment);
                return true;
            }
        }
        if (q.equals(".fizz") || q.equals(".bubbles") || q.equals(".пузыри") || q.equals(".пузырьки")) {
            View root = anchor != null ? anchor.getRootView()
                    : (act != null && act.getWindow() != null ? act.getWindow().getDecorView() : null);
            if (root instanceof ViewGroup) {
                launchColaBubbleShower((ViewGroup) root, 38);
                Toast.makeText(root.getContext(), "Пузырьки Колы! Лопай их пальцем", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        if (q.equals(".matrix") || q.equals(".koliti") || q.equals(".aero") || q.equals("/konami") || q.equals("idkfa")) {
            if (parentFragment != null) {
                showMatrixOverdriveSheet(parentFragment);
                return true;
            }
        }
        return false;
    }

    /** Opens the interactive 60fps Koliti Coca-Cola Fizz Slide playground BottomSheet! */
    public static void showColaSlideSheet(final Context ctx, final BaseFragment parentFragment) {
        final Activity act = AndroidUtilities.findActivity(ctx);
        if (act == null || act.isFinishing()) return;

        final BottomSheet sheet = new BottomSheet(act, false);
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();

        LinearLayout container = new LinearLayout(act);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(18) + AndroidUtilities.navigationBarHeight);

        GradientDrawable sheetBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A090F, 0xFF0E070A}
        );
        sheetBg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        sheetBg.setStroke(dp(1), 0x55FF3344);
        sheet.setApplyTopPadding(false);
        sheet.setApplyBottomPadding(false);
        try {
            sheet.setBackgroundColor(Color.TRANSPARENT);
            sheet.fixNavigationBar(0xFF0E070A);
        } catch (Throwable ignored) {}
        container.setBackground(sheetBg);

        // Grab handle
        View handle = new View(act);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(0x55FFD580);
        hBg.setCornerRadius(dp(3));
        handle.setBackground(hBg);
        container.addView(handle, LayoutHelper.createLinear(38, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        LinearLayout headerRow = new LinearLayout(act);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        headerRow.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView title = new TextView(act);
        title.setText(ColgramBuildFlags.KOLITI_ENABLED ? "Кола-горка Koliti" : "Кола-горка Colgram");
        title.setTextColor(0xFFFFF4DC);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18.5f);
        title.setTypeface(AndroidUtilities.bold());
        titleCol.addView(title);

        TextView subtitle = new TextView(act);
        subtitle.setText(ColgramBuildFlags.KOLITI_ENABLED
                ? "Нажимай на горку — Koliti прыгает выше и ловит буст"
                : "Нажимай на горку — персонаж прыгает выше и ловит буст");
        subtitle.setTextColor(0xBBFDBA74);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        titleCol.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        // Interactive 60fps Coca-Cola Fizz Slide Stage
        final ColgramColaSlideView slideView = new ColgramColaSlideView(act);
        GradientDrawable frameBg = new GradientDrawable();
        frameBg.setCornerRadius(dp(18));
        frameBg.setStroke(dp(1.2f), 0x66FF4D5E);
        slideView.setBackground(frameBg);
        slideView.setClipToOutline(true);
        container.addView(slideView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 240, 0, 0, 0, 14));

        // Interactive action bar
        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView fizzBtn = makePillButton(act, "Ещё газировки!", 0xFFE11D48);
        ColgramMenuChrome.attachSpringPress(fizzBtn);
        fizzBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            slideView.triggerFizzEruption();
            if (sheet.getContainer() != null) {
                launchColaBubbleShower(sheet.getContainer(), 22);
            }
        });
        btnRow.addView(fizzBtn, LayoutHelper.createLinear(0, 44, 1.25f, 0, 0, 8, 0));

        TextView bubblesBtn = makePillButton(act, "Пузырьки", 0xFF7C2D12);
        ColgramMenuChrome.attachSpringPress(bubblesBtn);
        bubblesBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (act.getWindow() != null && act.getWindow().getDecorView() instanceof ViewGroup) {
                launchColaBubbleShower((ViewGroup) act.getWindow().getDecorView(), 40);
                Toast.makeText(act, "Лопай пузырьки пальцем!", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(bubblesBtn, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 8, 0));

        TextView termBtn = makePillButton(act, "Аркада", 0xFF1F2430);
        ColgramMenuChrome.attachSpringPress(termBtn);
        termBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (parentFragment != null) {
                showMatrixOverdriveSheet(parentFragment);
            }
        });
        btnRow.addView(termBtn, LayoutHelper.createLinear(0, 44, 0.85f, 0, 0, 0, 0));

        sheet.setCustomView(container);
        sheet.show();
    }

    /** Spawns poppable translucent Coca-Cola bubbles floating across any host ViewGroup. */
    public static void launchColaBubbleShower(ViewGroup host, int count) {
        if (host == null || host.getContext() == null) return;
        ColaBubbleShowerView overlay = new ColaBubbleShowerView(host.getContext(), Math.max(10, Math.min(50, count)));
        host.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
    }

    public static void showMatrixOverdriveSheet(final BaseFragment parentFragment) {
        final Activity act = parentFragment != null ? parentFragment.getParentActivity() : null;
        if (act == null) return;

        BottomSheet sheet = new BottomSheet(act, false);
        LinearLayout container = new LinearLayout(act);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(12), dp(16), dp(20));

        GradientDrawable sheetBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0A0F1A, 0xFF0E1726}
        );
        sheetBg.setCornerRadius(dp(18));
        sheetBg.setStroke(dp(1), 0x6610B981);
        container.setBackground(sheetBg);

        MatrixRainView rainView = new MatrixRainView(act);
        GradientDrawable rainFrame = new GradientDrawable();
        rainFrame.setColor(0xFF050A0E);
        rainFrame.setCornerRadius(dp(14));
        rainFrame.setStroke(dp(1), 0x8010B981);
        rainView.setBackground(rainFrame);
        rainView.setClipToOutline(true);
        container.addView(rainView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 130, 0, 0, 0, 14));

        TextView title = new TextView(act);
        title.setText("COLGRAM // SECRET ARCADE");
        title.setTextColor(0xFF34D399);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView telemetry = new TextView(act);
        Runtime rt = Runtime.getRuntime();
        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        if (ColgramBuildFlags.KOLITI_ENABLED) {
            String sysInfo = readNativeSystemInfo();
            if (sysInfo.length() > 96) sysInfo = sysInfo.substring(0, 96) + "…";
            telemetry.setText(
                    "• Движок ИИ: " + readAiEngineSummary(act) + "\n"
                            + "• C++ Core: " + sysInfo + "\n"
                            + "• Сеть: " + ColgramProxyDoctor.getStatusSummary() + "\n"
                            + "• Память JVM: " + heapUsed + " МБ · Моделей: " + readAiCatalogCount()
            );
        } else {
            telemetry.setText(
                    "• Режим: Colgram Privacy Core (без встроенного ИИ)\n"
                            + "• Сеть: " + ColgramProxyDoctor.getStatusSummary() + "\n"
                            + "• Память JVM: " + heapUsed + " МБ"
            );
        }
        telemetry.setTextColor(0xFFD1FAE5);
        telemetry.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        telemetry.setTypeface(Typeface.MONOSPACE);
        container.addView(telemetry, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView colaBtn = makePillButton(act, ColgramBuildFlags.KOLITI_ENABLED ? "Кола-горка Koliti" : "Кола-горка Colgram", 0xFFE11D48);
        ColgramMenuChrome.attachSpringPress(colaBtn);
        colaBtn.setOnClickListener(v -> {
            sheet.dismiss();
            showColaSlideSheet(act, parentFragment);
        });
        btnRow.addView(colaBtn, LayoutHelper.createLinear(0, 44, 1.35f, 0, 0, 8, 0));

        TextView burstBtn = makePillButton(act, "Салют", 0xFF0F766E);
        ColgramMenuChrome.attachSpringPress(burstBtn);
        burstBtn.setOnClickListener(v -> {
            if (sheet.getContainer() != null) {
                ColgramConfettiView.burstOver(sheet.getContainer(),
                        sheet.getContainer().getWidth() * 0.5f,
                        sheet.getContainer().getHeight() * 0.3f, 110);
            }
        });
        btnRow.addView(burstBtn, LayoutHelper.createLinear(0, 44, 0.9f, 0, 0, 0, 0));

        if (ColgramBuildFlags.KOLITI_ENABLED) {
            TextView studioBtn = makePillButton(act, "Открыть Koliti Studio", 0xFF1E293B);
            ColgramMenuChrome.attachSpringPress(studioBtn);
            studioBtn.setOnClickListener(v -> {
                sheet.dismiss();
                if (parentFragment != null) {
                    parentFragment.presentFragment(new ColgramAiActivity());
                }
            });
            container.addView(studioBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42));
        }

        sheet.setCustomView(container);
        sheet.show();
    }

    private static TextView makePillButton(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(12));
        tv.setBackground(gd);
        return tv;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    /** Glossy poppable Coca-Cola carbonation bubble shower overlay that auto-detaches cleanly. */
    private static final class ColaBubbleShowerView extends View {
        private final float[] x;
        private final float[] y;
        private final float[] r;
        private final float[] vy;
        private final float[] phase;
        private final boolean[] popped;
        private final float[] popRing;
        private final int[] colors;
        private final int count;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private ValueAnimator anim;
        private long lastMs;
        private float elapsed = 0f;
        private boolean initialized = false;

        ColaBubbleShowerView(Context ctx, int count) {
            super(ctx);
            this.count = count;
            x = new float[count];
            y = new float[count];
            r = new float[count];
            vy = new float[count];
            phase = new float[count];
            popped = new boolean[count];
            popRing = new float[count];
            colors = new int[count];
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(dp(1.4f));
            hiPaint.setColor(0xDDFFFFFF);
            int[] pal = {0x99E11D48, 0x99F59E0B, 0x88FB7185, 0x99B91C1C, 0x88FDE68A};
            for (int i = 0; i < count; i++) {
                colors[i] = pal[i % pal.length];
            }
        }

        private void initIfNeeded(int w, int h) {
            if (initialized || w <= 0 || h <= 0) return;
            initialized = true;
            for (int i = 0; i < count; i++) {
                x[i] = dp(24) + rnd.nextFloat() * Math.max(dp(40), w - dp(48));
                y[i] = h + rnd.nextFloat() * h * 0.55f;
                r[i] = dp(10 + rnd.nextFloat() * 16);
                vy[i] = dp(115 + rnd.nextFloat() * 145);
                phase[i] = rnd.nextFloat() * 6.28f;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                float tx = event.getX();
                float ty = event.getY();
                for (int i = count - 1; i >= 0; i--) {
                    if (popped[i]) continue;
                    float dx = tx - x[i];
                    float dy = ty - y[i];
                    float hit = r[i] + dp(14);
                    if (dx * dx + dy * dy <= hit * hit) {
                        popped[i] = true;
                        popRing[i] = 1f;
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        invalidate();
                        return true;
                    }
                }
            }
            // Let non-bubble taps pass right through to the app underneath!
            return false;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            lastMs = SystemClock.uptimeMillis();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1000L);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                long now = SystemClock.uptimeMillis();
                float dt = Math.min(0.05f, Math.max(0.008f, (now - lastMs) / 1000f));
                lastMs = now;
                elapsed += dt;
                initIfNeeded(getWidth(), getHeight());
                int alive = 0;
                for (int i = 0; i < count; i++) {
                    if (popped[i]) {
                        if (popRing[i] > 0f) {
                            popRing[i] = Math.max(0f, popRing[i] - dt * 3.8f);
                            alive++;
                        }
                        continue;
                    }
                    y[i] -= vy[i] * dt;
                    x[i] += (float) Math.sin(elapsed * 2.4f + phase[i]) * dp(26) * dt;
                    if (y[i] + r[i] > -dp(12)) {
                        alive++;
                    }
                }
                invalidate();
                if ((alive == 0 && initialized) || elapsed > 8.5f) {
                    if (anim != null) anim.cancel();
                    if (getParent() instanceof ViewGroup) {
                        ((ViewGroup) getParent()).removeView(this);
                    }
                }
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!initialized) return;
            for (int i = 0; i < count; i++) {
                if (popped[i]) {
                    if (popRing[i] > 0f) {
                        float expand = r[i] * (1f + (1f - popRing[i]) * 1.15f);
                        rimPaint.setColor(0xFFFFF4DC);
                        rimPaint.setAlpha(Math.round(popRing[i] * 220f));
                        canvas.drawCircle(x[i], y[i], expand, rimPaint);
                    }
                    continue;
                }
                if (y[i] - r[i] > getHeight() || y[i] + r[i] < 0) continue;
                fillPaint.setColor(colors[i]);
                canvas.drawCircle(x[i], y[i], r[i], fillPaint);
                rimPaint.setColor(0xAAFFF4DC);
                canvas.drawCircle(x[i], y[i], r[i], rimPaint);
                canvas.drawCircle(x[i] - r[i] * 0.32f, y[i] - r[i] * 0.32f, Math.max(dp(2), r[i] * 0.26f), hiPaint);
            }
        }
    }

    private static final class MatrixRainView extends View {
        private static final String GLYPHS = ColgramBuildFlags.KOLITI_ENABLED
                ? "01COLGRAMKOLITIQWEN35ZX#@$%&*"
                : "01COLGRAMCYBERAEROZX#@$%&*";
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private ValueAnimator anim;
        private int[] drops;
        private float scanY = 0f;

        MatrixRainView(Context context) {
            super(context);
            textPaint.setTypeface(Typeface.MONOSPACE);
            textPaint.setTextSize(dp(12));
            headerPaint.setColor(0xFFECFEFF);
            headerPaint.setTextSize(dp(15));
            headerPaint.setTypeface(Typeface.MONOSPACE);
            headerPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1000L);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                if (drops != null) {
                    for (int i = 0; i < drops.length; i++) {
                        if (rnd.nextFloat() < 0.32f) {
                            drops[i] = (drops[i] + 1) % 14;
                        }
                    }
                }
                scanY = (scanY + dp(2.2f)) % Math.max(1f, getHeight());
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            int step = dp(14);
            int cols = Math.max(8, w / step);
            if (drops == null || drops.length != cols) {
                drops = new int[cols];
                for (int i = 0; i < cols; i++) drops[i] = rnd.nextInt(14);
            }
            textPaint.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xFF10B981, 0xFF06B6D4, 0xFF34D399}, null, Shader.TileMode.CLAMP));
            for (int c = 0; c < cols; c++) {
                int headRow = drops[c];
                for (int r = 0; r < 8; r++) {
                    int yRow = (headRow - r + 14) % 14;
                    int alpha = Math.max(25, 240 - r * 32);
                    textPaint.setAlpha(alpha);
                    char ch = GLYPHS.charAt((c * 7 + yRow * 3 + (int) scanY) % GLYPHS.length());
                    canvas.drawText(String.valueOf(ch), c * step + dp(4), yRow * dp(11) + dp(12), textPaint);
                }
            }
            headerPaint.setShadowLayer(dp(6), 0, 0, 0xFF10B981);
            canvas.drawText(ColgramBuildFlags.KOLITI_ENABLED ? "COLGRAM // KOLITI CORE" : "COLGRAM // PRIVACY CORE",
                    w * 0.5f, h * 0.54f, headerPaint);
        }
    }

    private static String readNativeSystemInfo() {
        try {
            Object v = Class.forName("org.colgram.core.ColgramNativeLlama")
                    .getMethod("getSystemInfoSafe")
                    .invoke(null);
            return v != null ? String.valueOf(v) : "native";
        } catch (Throwable ignored) {
            return "native";
        }
    }

    private static String readAiEngineSummary(Activity act) {
        try {
            Class<?> cls = Class.forName("org.colgram.core.ColgramAiEngine");
            Object inst = cls.getMethod("getInstance").invoke(null);
            if (inst == null) return "unavailable";
            Object v = cls.getMethod("buildStatusSummary", Activity.class).invoke(inst, act);
            return v != null ? String.valueOf(v) : "unavailable";
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static int readAiCatalogCount() {
        try {
            Object list = Class.forName("org.colgram.core.ColgramAiModels")
                    .getMethod("catalog")
                    .invoke(null);
            return (list instanceof java.util.List<?>) ? ((java.util.List<?>) list).size() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
