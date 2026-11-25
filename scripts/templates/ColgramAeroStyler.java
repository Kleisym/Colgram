package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import org.colgram.core.ColgramConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Flagship Obsidian / Porcelain / Cyber Visual Design System for Colgram:
 * - Unified Light / Dark / Cyber surface, border, and WCAG-AA semantic tokens
 * - Zero-allocation Matrix-animated hairline strips & badge shaders
 * - Unified double-border-free BottomSheet chrome with edge-to-edge nav tint
 * - RTL-safe, Cyber-aware DialogCell row decorations
 */
public final class ColgramAeroStyler {

    public static final class Palette {
        public final boolean dark;
        public final boolean cyber;
        public final int sheetBgTop;
        public final int sheetBgBottom;
        public final int cardSurface;
        public final int cardElevated;
        public final int inputSurface;
        public final int chipSurface;
        public final int borderSubtle;
        public final int borderAccent;
        public final int textPrimary;
        public final int textSecondary;
        public final int accentPrimary;
        public final int accentSecondary;
        public final int accentSoft;
        public final int semanticEmerald;
        public final int semanticAmber;
        public final int semanticCyan;
        public final int semanticCrimson;

        private Palette(
                boolean dark, boolean cyber,
                int sheetBgTop, int sheetBgBottom, int cardSurface, int cardElevated,
                int inputSurface, int chipSurface, int borderSubtle, int borderAccent,
                int textPrimary, int textSecondary, int accentPrimary, int accentSecondary, int accentSoft,
                int semanticEmerald, int semanticAmber, int semanticCyan, int semanticCrimson
        ) {
            this.dark = dark;
            this.cyber = cyber;
            this.sheetBgTop = sheetBgTop;
            this.sheetBgBottom = sheetBgBottom;
            this.cardSurface = cardSurface;
            this.cardElevated = cardElevated;
            this.inputSurface = inputSurface;
            this.chipSurface = chipSurface;
            this.borderSubtle = borderSubtle;
            this.borderAccent = borderAccent;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.accentPrimary = accentPrimary;
            this.accentSecondary = accentSecondary;
            this.accentSoft = accentSoft;
            this.semanticEmerald = semanticEmerald;
            this.semanticAmber = semanticAmber;
            this.semanticCyan = semanticCyan;
            this.semanticCrimson = semanticCrimson;
        }

        public static Palette resolve() {
            boolean dark = Theme.isCurrentThemeDark();
            boolean cyber = dark && ColgramConfig.isCyberThemeEnabled();

            int customSurface = org.colgram.core.ColgramDesignConfig.resolveCustomBaseSurfaceColor();
            if (customSurface != 0) {
                dark = org.colgram.core.ColgramThemePolicy.isDarkColor(customSurface);
                if (!dark) cyber = false;
            }

            int sheetBgTop, sheetBgBottom, cardSurface, cardElevated, inputSurface, chipSurface;
            int borderSubtle, borderAccent, textPrimary, textSecondary;
            int accentPrimary, accentSecondary, accentSoft;
            int semanticEmerald, semanticAmber, semanticCyan, semanticCrimson;

            if (cyber) {
                sheetBgTop = 0xFF101116; sheetBgBottom = 0xFF0A0B0F;
                cardSurface = 0xFF161820; cardElevated = 0xFF1C1F2A;
                inputSurface = 0xFF1B1E28; chipSurface = 0xFF212430;
                borderSubtle = 0x26FFFFFF; borderAccent = 0x66FF3344;
                textPrimary = 0xFFF8FAFC; textSecondary = 0xFFA1A8B8;
                accentPrimary = 0xFFFF3344; accentSecondary = 0xFFFF6B7A; accentSoft = 0x2AFF3344;
                semanticEmerald = 0xFF34D399; semanticAmber = 0xFFFBBF24; semanticCyan = 0xFF38BDF8; semanticCrimson = 0xFFFF4D5E;
            } else if (dark) {
                sheetBgTop = 0xFF111520; sheetBgBottom = 0xFF0B0E16;
                cardSurface = 0xFF171C2A; cardElevated = 0xFF1E2436;
                inputSurface = 0xFF1C2233; chipSurface = 0xFF22293C;
                borderSubtle = 0x22FFFFFF; borderAccent = 0x5CE11D48;
                textPrimary = 0xFFF3F6FC; textSecondary = 0xFF9AA5BA;
                accentPrimary = 0xFFE11D48; accentSecondary = 0xFFFB7185; accentSoft = 0x26E11D48;
                semanticEmerald = 0xFF34D399; semanticAmber = 0xFFFBBF24; semanticCyan = 0xFF38BDF8; semanticCrimson = 0xFFFB7185;
            } else {
                sheetBgTop = 0xFFFDFEFF; sheetBgBottom = 0xFFF4F6FA;
                cardSurface = 0xFFFFFFFF; cardElevated = 0xFFF2F5FA;
                inputSurface = 0xFFF1F5F9; chipSurface = 0xFFEAF0F7;
                borderSubtle = 0x1F0F172A; borderAccent = 0x4DE11D48;
                textPrimary = 0xFF0F172A; textSecondary = 0xFF526075;
                accentPrimary = 0xFFD81B45; accentSecondary = 0xFFE11D48; accentSoft = 0x16E11D48;
                semanticEmerald = 0xFF047857; semanticAmber = 0xFFB45309; semanticCyan = 0xFF0369A1; semanticCrimson = 0xFFBE123C;
            }

            if (customSurface != 0) {
                if (dark) {
                    sheetBgBottom = customSurface;
                    sheetBgTop = blendRgb(customSurface, 0xFFFFFFFF, 0.045f);
                    cardSurface = blendRgb(customSurface, 0xFFFFFFFF, 0.07f);
                    cardElevated = blendRgb(customSurface, 0xFFFFFFFF, 0.11f);
                    inputSurface = blendRgb(customSurface, 0xFFFFFFFF, 0.09f);
                    chipSurface = blendRgb(customSurface, 0xFFFFFFFF, 0.13f);
                } else {
                    sheetBgTop = blendRgb(customSurface, 0xFFFFFFFF, 0.45f);
                    sheetBgBottom = customSurface;
                    cardSurface = 0xFFFFFFFF;
                    cardElevated = blendRgb(customSurface, 0xFF0F172A, 0.04f);
                    inputSurface = blendRgb(customSurface, 0xFF0F172A, 0.05f);
                    chipSurface = blendRgb(customSurface, 0xFF0F172A, 0.07f);
                }
            }

            int customAccent = org.colgram.core.ColgramDesignConfig.getAccentColor();
            if (customAccent != 0) {
                accentPrimary = customAccent;
                accentSecondary = dark ? blendRgb(customAccent, 0xFFFFFFFF, 0.28f) : blendRgb(customAccent, 0xFF000000, 0.12f);
                accentSoft = (customAccent & 0x00FFFFFF) | ((dark ? 0x2A : 0x18) << 24);
                borderAccent = (customAccent & 0x00FFFFFF) | ((dark ? 0x68 : 0x52) << 24);
            }

            // Guarantee WCAG AA contrast across custom / mid-grey / Aero surfaces so text & semantic
            // accents can never drop into grey-on-grey on any card, sheet, or input fill.
            textPrimary = org.colgram.core.ColgramThemePolicy.ensureReadableOnSurfaces(
                    textPrimary, cardSurface, sheetBgBottom, 0xFFF8FAFC, 0xFF0F172A, 7.0);
            textSecondary = org.colgram.core.ColgramThemePolicy.ensureReadableOnSurfaces(
                    textSecondary, cardSurface, sheetBgBottom, 0xFFDCE4F0, 0xFF2C3542, 4.5);
            accentPrimary = org.colgram.core.ColgramThemePolicy.ensureAccentReadable(
                    accentPrimary, cardSurface, 0xFF7EC8FF, 0xFF1565C0, 4.5);
            accentSecondary = org.colgram.core.ColgramThemePolicy.ensureAccentReadable(
                    accentSecondary, cardSurface, 0xFF9CD6FF, 0xFF1565C0, 4.5);
            semanticEmerald = org.colgram.core.ColgramThemePolicy.ensureAccentReadable(
                    semanticEmerald, cardSurface, 0xFF5CE0A8, 0xFF047857, 4.5);
            semanticAmber = org.colgram.core.ColgramThemePolicy.ensureAccentReadable(
                    semanticAmber, cardSurface, 0xFFFCD34D, 0xFFB45309, 4.5);
            semanticCyan = org.colgram.core.ColgramThemePolicy.ensureAccentReadable(
                    semanticCyan, cardSurface, 0xFF7DD3FC, 0xFF0369A1, 4.5);
            semanticCrimson = org.colgram.core.ColgramThemePolicy.ensureAccentReadable(
                    semanticCrimson, cardSurface, 0xFFFF8290, 0xFFBE123C, 4.5);

            return new Palette(
                    dark, cyber,
                    sheetBgTop, sheetBgBottom, cardSurface, cardElevated,
                    inputSurface, chipSurface, borderSubtle, borderAccent,
                    textPrimary, textSecondary, accentPrimary, accentSecondary, accentSoft,
                    semanticEmerald, semanticAmber, semanticCyan, semanticCrimson
            );
        }

        private static int blendRgb(int base, int target, float amount) {
            int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
            int tr = (target >> 16) & 0xFF, tg = (target >> 8) & 0xFF, tb = target & 0xFF;
            int r = Math.max(0, Math.min(255, Math.round(br + (tr - br) * amount)));
            int g = Math.max(0, Math.min(255, Math.round(bg + (tg - bg) * amount)));
            int b = Math.max(0, Math.min(255, Math.round(bb + (tb - bb) * amount)));
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private static volatile String cachedKolitiAvatarKey = "";
    private static volatile android.graphics.Bitmap cachedKolitiAvatarBitmap;

    /** Loads the user's custom Koliti avatar (if configured) or falls back cleanly to built-in koliti_avatar. */
    public static android.graphics.Bitmap loadKolitiAvatarBitmap(Context ctx) {
        if (ctx == null) return null;
        String customPath = org.colgram.core.ColgramDesignConfig.getKolitiCustomAvatarPath();
        String key = customPath == null ? "" : customPath;
        if (!key.isEmpty()) {
            java.io.File f = new java.io.File(key);
            if (f.isFile() && f.length() > 0) {
                key = f.getAbsolutePath() + "@" + f.lastModified();
                if (key.equals(cachedKolitiAvatarKey) && cachedKolitiAvatarBitmap != null && !cachedKolitiAvatarBitmap.isRecycled()) {
                    return cachedKolitiAvatarBitmap;
                }
                try {
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bmp != null) {
                        cachedKolitiAvatarKey = key;
                        cachedKolitiAvatarBitmap = bmp;
                        return bmp;
                    }
                } catch (Throwable ignored) {}
            }
        }
        String defKey = "res:koliti_avatar";
        if (defKey.equals(cachedKolitiAvatarKey) && cachedKolitiAvatarBitmap != null && !cachedKolitiAvatarBitmap.isRecycled()) {
            return cachedKolitiAvatarBitmap;
        }
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeResource(
                    ctx.getResources(), org.telegram.messenger.R.drawable.koliti_avatar);
            cachedKolitiAvatarKey = defKey;
            cachedKolitiAvatarBitmap = bmp;
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void invalidateKolitiAvatarCache() {
        cachedKolitiAvatarKey = "";
        cachedKolitiAvatarBitmap = null;
    }

    public static void applyKolitiAvatar(android.widget.ImageView iv) {
        if (iv == null) return;
        android.graphics.Bitmap bmp = loadKolitiAvatarBitmap(iv.getContext());
        if (bmp != null) {
            iv.setImageBitmap(bmp);
        } else {
            iv.setImageResource(org.telegram.messenger.R.drawable.koliti_avatar);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0) {
                        outline.setOval(0, 0, w, h);
                    }
                }
            });
            iv.setClipToOutline(true);
        }
    }

    private static final Paint ROW_CARD_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint ROW_STROKE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint ROW_ACCENT_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF ROW_RECT = new RectF();
    private static final RectF ROW_BAR_RECT = new RectF();

    // Cached immutable origin-anchored badge shader + Paint (0 local-matrix mutations & 0 allocations per DialogCell)
    private static LinearGradient cachedBadgeShader;
    private static boolean cachedBadgeCyber;
    private static boolean cachedBadgeDark;
    private static int cachedBadgeAccent;
    private static final Paint AERO_BADGE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static ColorFilter cachedBadgeColorFilter;
    private static int cachedBadgeAlpha = -1;

    static {
        ROW_STROKE_PAINT.setStyle(Paint.Style.STROKE);
        ROW_STROKE_PAINT.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(0.75f)));
    }

    private ColgramAeroStyler() {}

    /**
     * Applies unified flagship BottomSheet chrome: removes Telegram's nested double-rounded
     * shadow frame, applies crisp 24dp top-rounded surface + subtle hairline border, and tints
     * the system navigation bar cleanly in Light, Dark, and Cyber themes.
     */
    public static void applyFlagshipSheetChrome(BottomSheet sheet, View root, Palette p) {
        if (sheet == null || root == null || p == null) return;
        sheet.setApplyTopPadding(false);
        sheet.setApplyBottomPadding(false);
        try {
            sheet.setBackgroundColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {}
        try {
            sheet.fixNavigationBar(p.sheetBgBottom);
        } catch (Throwable ignored) {}
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{p.sheetBgTop, p.sheetBgBottom}
        );
        bg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        bg.setStroke(Math.max(1, dp(0.75f)), p.borderSubtle);
        root.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) {
            root.setElevation(dp(10));
        }
    }

    /**
     * Installs a razor-thin zero-allocation animated accent hairline along the bottom edge of
     * DialogsActivity's ActionBar.
     */
    public static void installDialogsAeroHeader(final DialogsActivity fragment, final ActionBar actionBar) {
        if (fragment == null || actionBar == null) return;
        android.os.Bundle args = fragment.getArguments();
        if (args != null && (args.getBoolean("onlySelect", false) || args.getInt("folderId", 0) != 0)) return;
        if (Boolean.TRUE.equals(actionBar.getTag(0x636f6c11))) return;
        actionBar.setTag(0x636f6c11, Boolean.TRUE);

        Context ctx = actionBar.getContext();
        final AccentHairlineStrip strip = new AccentHairlineStrip(ctx);
        actionBar.addView(strip, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 4f, Gravity.BOTTOM));
    }

    /**
     * Installs a living, zero-allocation ambient wave/aurora backdrop behind the main chat list
     * in DialogsActivity. Auto-pauses completely when hidden or in Battery Saver mode.
     */
    public static void installDialogsAmbientBackdrop(final DialogsActivity fragment, final ViewGroup contentView) {
        if (fragment == null || contentView == null) return;
        android.os.Bundle args = fragment.getArguments();
        if (args != null && (args.getBoolean("onlySelect", false) || args.getInt("folderId", 0) != 0)) return;
        if (Boolean.TRUE.equals(contentView.getTag(0x636f6c14))) return;
        contentView.setTag(0x636f6c14, Boolean.TRUE);

        AmbientMenuBackdropView backdrop = new AmbientMenuBackdropView(contentView.getContext());
        contentView.addView(backdrop, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    /**
     * Safe no-op on AnimatedLinearLayout (MainTabsLayout) so we never inject a permanently-GONE
     * child view that wastes 60fps GPU allocations.
     */
    public static void styleMainTabsDock(final View tabsView) {
        if (tabsView == null || Boolean.TRUE.equals(tabsView.getTag(0x636f6c12))) return;
        tabsView.setTag(0x636f6c12, Boolean.TRUE);
    }

    /** Adds spring tactile press feedback to ProfileActivity quick action tiles. */
    public static void styleProfileActionContainer(View container) {
        if (container == null || Boolean.TRUE.equals(container.getTag(0x636f6c13))) return;
        container.setTag(0x636f6c13, Boolean.TRUE);
        if (container instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) container;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                ColgramMenuChrome.attachSpringPress(child);
            }
        }
    }

    /** Styles sheet container background matching current Light / Dark / Cyber palette. */
    public static GradientDrawable createAeroSheetDrawable(boolean dark) {
        Palette p = Palette.resolve();
        int top = dark ? (p.cyber ? 0xFF101116 : 0xFF121620) : 0xFFFAFBFD;
        int bot = dark ? (p.cyber ? 0xFF0A0B0F : 0xFF0A0C12) : 0xFFF1F5F9;
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bot}
        );
        gd.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        gd.setStroke(Math.max(1, dp(0.75f)), dark ? 0x24FFFFFF : 0x1A0F172A);
        return gd;
    }

    /**
     * Allocation-free, RTL-aware, Cyber-aware card surface & crisp edge indicator for DialogCell rows.
     */
    public static void drawDialogRowDecoration(
            Canvas canvas,
            int width,
            int height,
            boolean dark,
            boolean isPinned,
            boolean isSelected,
            boolean hasUnread
    ) {
        if (width <= 0 || height <= 0) return;
        int cardStyle = org.colgram.core.ColgramDesignConfig.getCardStyle();
        if (cardStyle == org.colgram.core.ColgramDesignConfig.CARD_STYLE_CLASSIC_FLAT && !isSelected) {
            return;
        }
        boolean cyber = dark && ColgramConfig.isCyberThemeEnabled();
        int customAccent = org.colgram.core.ColgramDesignConfig.getAccentColor();
        int accentRgb = (customAccent != 0 ? customAccent : (cyber ? 0xFFFF3344 : 0xFFE11D48)) & 0x00FFFFFF;
        float neonMul = org.colgram.core.ColgramDesignConfig.getNeonMultiplier();
        float radius = dp(cardStyle == org.colgram.core.ColgramDesignConfig.CARD_STYLE_SOFT_PILL
                ? Math.max(18, org.colgram.core.ColgramDesignConfig.getCornerRadiusDp())
                : org.colgram.core.ColgramDesignConfig.getCornerRadiusDp());

        float padX = dp(cardStyle == org.colgram.core.ColgramDesignConfig.CARD_STYLE_SOFT_PILL ? 10 : 8);
        float padY = dp(2.5f);
        ROW_RECT.set(padX, padY, width - padX, height - padY);

        int cardBg;
        int strokeCol;
        if (isSelected) {
            cardBg = ((dark ? 0x24 : 0x16) << 24) | accentRgb;
            strokeCol = (Math.min(255, Math.round((dark ? 0x62 : 0x48) * Math.max(0.6f, neonMul))) << 24) | accentRgb;
        } else if (cardStyle == org.colgram.core.ColgramDesignConfig.CARD_STYLE_NEON_BORDER) {
            cardBg = dark ? 0x0AFFFFFF : 0x050F172A;
            int a = Math.min(255, Math.round((isPinned ? 0x5A : 0x36) * Math.max(0.4f, neonMul)));
            strokeCol = (a << 24) | accentRgb;
        } else if (isPinned) {
            cardBg = (cyber || customAccent != 0)
                    ? (((dark ? 0x11 : 0x09) << 24) | accentRgb)
                    : (dark ? 0x0FFFFFFF : 0x060F172A);
            int a = Math.min(255, Math.round((dark ? 0x28 : 0x1A) * Math.max(0.35f, neonMul)));
            strokeCol = (cyber || customAccent != 0) ? ((a << 24) | accentRgb) : (dark ? 0x18FFFFFF : 0x120F172A);
        } else {
            cardBg = dark ? 0x07FFFFFF : 0x00000000;
            strokeCol = dark ? 0x0EFFFFFF : 0x0A0F172A;
        }

        if (cardBg != 0) {
            ROW_CARD_PAINT.setColor(cardBg);
            canvas.drawRoundRect(ROW_RECT, radius, radius, ROW_CARD_PAINT);
        }
        ROW_STROKE_PAINT.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(
                cardStyle == org.colgram.core.ColgramDesignConfig.CARD_STYLE_NEON_BORDER ? 1.15f : 0.75f)));
        ROW_STROKE_PAINT.setColor(strokeCol);
        canvas.drawRoundRect(ROW_RECT, radius, radius, ROW_STROKE_PAINT);

        if ((isPinned || isSelected || hasUnread) && neonMul > 0f) {
            float barTop = padY + dp(14);
            float barBot = height - padY - dp(14);
            if (barBot > barTop) {
                if (LocaleController.isRTL) {
                    ROW_BAR_RECT.set(width - padX - dp(4f), barTop, width - padX - dp(1.5f), barBot);
                } else {
                    ROW_BAR_RECT.set(padX + dp(1.5f), barTop, padX + dp(4f), barBot);
                }
                int accentColor;
                if (isSelected || hasUnread) accentColor = 0xFF000000 | accentRgb;
                else accentColor = dark ? 0x8894A3B8 : 0x8864748B;
                ROW_ACCENT_PAINT.setColor(accentColor);
                canvas.drawRoundRect(ROW_BAR_RECT, dp(1.5f), dp(1.5f), ROW_ACCENT_PAINT);
            }
        }
    }

    /** Returns the immutable origin-anchored Colgram gradient shader for unread counter badges (0 matrix mutations). */
    public static Shader getAeroBadgeShader(boolean muted) {
        if (muted) return null;
        boolean dark = Theme.isCurrentThemeDark();
        boolean cyber = dark && ColgramConfig.isCyberThemeEnabled();
        int customAccent = org.colgram.core.ColgramDesignConfig.getAccentColor();
        if (cachedBadgeShader == null || cachedBadgeDark != dark || cachedBadgeCyber != cyber || cachedBadgeAccent != customAccent) {
            cachedBadgeDark = dark;
            cachedBadgeCyber = cyber;
            cachedBadgeAccent = customAccent;
            int[] cols;
            if (customAccent != 0) {
                int light = Palette.resolve().accentSecondary;
                cols = new int[]{customAccent, light};
            } else {
                cols = cyber ? new int[]{0xFFFF2438, 0xFFFF6B7A} : new int[]{0xFFE11D48, 0xFFFB7185};
            }
            cachedBadgeShader = new LinearGradient(
                    -dp(18), -dp(10), dp(18), dp(10),
                    cols,
                    null, Shader.TileMode.CLAMP);
            AERO_BADGE_PAINT.setShader(cachedBadgeShader);
        }
        return cachedBadgeShader;
    }

    /** Backwards-compatible overload: ignores per-cell coords so HWUI shader batches without setLocalMatrix. */
    public static Shader getAeroBadgeShader(float cx, float cy, float w, boolean muted) {
        return getAeroBadgeShader(muted);
    }

    /**
     * Dedicated cached badge Paint holding the immutable origin-anchored Aero gradient shader.
     * Draw centered around (0, 0) inside {@code canvas.save(); canvas.translate(cx, cy); ... canvas.restore()}.
     */
    public static Paint getAeroBadgePaint(int alpha, ColorFilter colorFilter) {
        getAeroBadgeShader(false);
        if (cachedBadgeAlpha != alpha) {
            cachedBadgeAlpha = alpha;
            AERO_BADGE_PAINT.setAlpha(alpha);
        }
        if (cachedBadgeColorFilter != colorFilter) {
            cachedBadgeColorFilter = colorFilter;
            AERO_BADGE_PAINT.setColorFilter(colorFilter);
        }
        return AERO_BADGE_PAINT;
    }

    /** Allocation-free flowing dual-sine shimmer wave ribbon for DialogsActivity ActionBar (auto-pauses when hidden). */
    private static final class AccentHairlineStrip extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path wavePath = new android.graphics.Path();
        private final Matrix shaderMatrix = new Matrix();
        private LinearGradient cachedGradient;
        private int cachedWidth;
        private boolean cachedDark;
        private boolean cachedCyber;
        private final float phase = 0.5f;

        AccentHairlineStrip(Context ctx) {
            super(ctx);
            wavePaint.setStyle(Paint.Style.STROKE);
            wavePaint.setStrokeCap(Paint.Cap.ROUND);
            wavePaint.setStrokeWidth(Math.max(1.2f, dp(1.35f)));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            invalidate();
        }

        private int cachedAccent;

        @Override
        protected void onDraw(Canvas canvas) {
            if (org.colgram.core.ColgramDesignConfig.getNeonIntensity() == 0) return;
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            boolean dark = Theme.isCurrentThemeDark();
            boolean cyber = dark && ColgramConfig.isCyberThemeEnabled();
            int customAccent = org.colgram.core.ColgramDesignConfig.getAccentColor();
            if (cachedGradient == null || cachedWidth != w || cachedDark != dark || cachedCyber != cyber || cachedAccent != customAccent) {
                cachedWidth = w;
                cachedDark = dark;
                cachedCyber = cyber;
                cachedAccent = customAccent;
                int[] cols;
                if (customAccent != 0) {
                    int rgb = customAccent & 0x00FFFFFF;
                    int soft = Palette.resolve().accentSecondary & 0x00FFFFFF;
                    cols = new int[]{rgb, 0x88000000 | rgb, 0xEE000000 | soft, 0x88000000 | rgb, rgb};
                } else {
                    cols = cyber
                            ? new int[]{0x00FF3344, 0x88FF3344, 0xEEFFD580, 0x88FB7185, 0x00FF3344}
                            : (dark
                            ? new int[]{0x0038BDF8, 0x7738BDF8, 0xDDE2E8F0, 0x77E11D48, 0x0038BDF8}
                            : new int[]{0x002AABEE, 0x772AABEE, 0xDD38BDF8, 0x66E11D48, 0x002AABEE});
                }
                cachedGradient = new LinearGradient(
                        -w * 0.42f, 0, w * 0.42f, 0,
                        cols,
                        new float[]{0f, 0.28f, 0.52f, 0.76f, 1f},
                        Shader.TileMode.CLAMP
                );
                paint.setShader(cachedGradient);
                wavePaint.setShader(cachedGradient);
            }
            float shift = (phase * 1.85f - 0.42f) * w;
            shaderMatrix.setTranslate(shift, 0f);
            cachedGradient.setLocalMatrix(shaderMatrix);

            // Soft bottom glow bed
            canvas.drawRect(0, h - dp(1.2f), w, h, paint);

            // Flowing liquid sine wave ribbon across the header seam
            float t = phase * (float) (Math.PI * 2.0);
            float midY = h * 0.56f;
            float amp = Math.max(1f, h * 0.34f);
            wavePath.rewind();
            int steps = 20;
            for (int i = 0; i <= steps; i++) {
                float f = (float) i / steps;
                float x = f * w;
                float y = midY + (float) Math.sin(f * Math.PI * 3.0 - t) * amp;
                if (i == 0) wavePath.moveTo(x, y);
                else wavePath.lineTo(x, y);
            }
            canvas.drawPath(wavePath, wavePaint);
        }
    }

    /** Pre-rendered static offscreen bitmap backdrop for main menu (DialogsActivity) & onboarding. */
    public static final class AmbientMenuBackdropView extends View {
        private Bitmap cachedBitmap;
        private Canvas cachedCanvas;
        private int cachedWidth;
        private int cachedHeight;
        private int cachedStyle = -1;
        private int cachedAccent;
        private boolean cachedDark;
        private boolean cachedCyber;
        private float cachedDensity;

        public AmbientMenuBackdropView(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
        }

        private void releaseCachedBitmap() {
            cachedCanvas = null;
            if (cachedBitmap != null) {
                cachedBitmap.recycle();
                cachedBitmap = null;
            }
            cachedWidth = 0;
            cachedHeight = 0;
            cachedStyle = -1;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            releaseCachedBitmap();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            int style = org.colgram.core.ColgramConfig.getLiveWallpaperStyle();
            if (style == org.colgram.core.ColgramLiveWallpaper.STYLE_OFF) {
                releaseCachedBitmap();
                return;
            }
            if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE
                    || org.colgram.core.ColgramPowerGuard.isPowerSaveMode(getContext())) {
                return;
            }
            boolean dark = Theme.isCurrentThemeDark();
            boolean cyber = dark && ColgramConfig.isCyberThemeEnabled();
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            float density = AndroidUtilities.density;
            if (cachedBitmap == null || cachedBitmap.isRecycled()
                    || cachedWidth != w || cachedHeight != h
                    || cachedStyle != style || cachedAccent != accent
                    || cachedDark != dark || cachedCyber != cyber
                    || cachedDensity != density) {
                try {
                    if (cachedBitmap == null || cachedBitmap.isRecycled() || cachedWidth != w || cachedHeight != h) {
                        releaseCachedBitmap();
                        cachedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        cachedCanvas = new Canvas(cachedBitmap);
                        cachedWidth = w;
                        cachedHeight = h;
                    } else {
                        cachedBitmap.eraseColor(Color.TRANSPARENT);
                    }
                    cachedStyle = style;
                    cachedAccent = accent;
                    cachedDark = dark;
                    cachedCyber = cyber;
                    cachedDensity = density;
                    org.colgram.core.ColgramLiveWallpaper.drawMainMenuAmbient(
                            cachedCanvas, w, h, accent, dark, cyber, 0f, density);
                } catch (OutOfMemoryError oom) {
                    releaseCachedBitmap();
                    org.colgram.core.ColgramLiveWallpaper.drawMainMenuAmbient(
                            canvas, w, h, accent, dark, cyber, 0f, density);
                    return;
                }
            }
            canvas.drawBitmap(cachedBitmap, 0f, 0f, null);
        }
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
