package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.colgram.core.ColgramAccountManager;
import org.colgram.core.ColgramAccountVaultPolicy;
import org.colgram.core.ColgramPersistentBackup;
import org.colgram.core.ColgramThemePolicy;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Shared UI helpers for Colgram's unlimited account switcher, encrypted account vault,
 * unlimited account pinning, and Human/Bot pill badges.
 */
public final class ColgramAccountSwitcherHelper {

    private ColgramAccountSwitcherHelper() {}

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }

    /** Builds a styled switcher row with avatar, name, Human/Bot pill badge, and pin toggle. */
    public static LinearLayout buildAccountRow(Context context, int account, boolean selected, Runnable onChanged) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), 0, 0));

        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        final long userId = user != null ? user.id : UserConfig.getInstance(account).getClientUserId();
        final boolean isBot = user != null && user.bot;

        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(account, user);

        final FrameLayout avatarContainer = new FrameLayout(context) {
            private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (selected) {
                    selectedPaint.setStyle(Paint.Style.STROKE);
                    selectedPaint.setStrokeWidth(dp(1.5f));
                    selectedPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                    canvas.drawCircle(getWidth() / 2.0f, getHeight() / 2.0f, dp(16), selectedPaint);
                }
                super.dispatchDraw(canvas);
            }
        };
        row.addView(avatarContainer, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

        final BackupImageView avatarView = new BackupImageView(context);
        if (selected) {
            avatarView.setScaleX(0.833f);
            avatarView.setScaleY(0.833f);
        }
        avatarView.setRoundRadius(dp(16));
        avatarView.getImageReceiver().setCurrentAccount(account);
        avatarView.setForUserOrChat(user, avatarDrawable);
        avatarContainer.addView(avatarView, LayoutHelper.createFrame(32, 32, Gravity.CENTER));

        final int bgSurface = Theme.getColor(Theme.key_dialogBackground);
        final int textColor = ColgramThemePolicy.ensureReadableOnSurface(
                Theme.getColor(Theme.key_dialogTextBlack), bgSurface, 0xffffffff, 0xff16181e, 4.5);

        final TextView nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(textColor);
        String displayName = UserObject.getUserName(user);
        nameView.setText(TextUtils.isEmpty(displayName) ? ("Аккаунт " + (account + 1)) : displayName);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(nameView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 4, 0));

        final TextView badgePill = buildRoleBadge(context, isBot);
        row.addView(badgePill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 20, Gravity.CENTER_VERTICAL, 2, 0, 4, 0));

        final boolean[] isPinned = new boolean[] { ColgramAccountManager.isAccountPinned(context, userId, account) };
        final ImageView pinBtn = new ImageView(context);
        pinBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        pinBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        pinBtn.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), dp(14), dp(14)));
        applyPinIconStyle(pinBtn, isPinned[0], bgSurface);
        pinBtn.setOnClickListener(v -> {
            isPinned[0] = ColgramAccountManager.toggleAccountPin(context, userId, account);
            applyPinIconStyle(pinBtn, isPinned[0], bgSurface);
            pinBtn.animate().scaleX(1.22f).scaleY(1.22f).setDuration(90)
                    .withEndAction(() -> pinBtn.animate().scaleX(1f).scaleY(1f).setDuration(90).start())
                    .start();
            if (onChanged != null) {
                AndroidUtilities.runOnUIThread(onChanged, 185L);
            }
        });
        row.addView(pinBtn, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        return row;
    }

    private static TextView buildRoleBadge(Context context, boolean isBot) {
        TextView badge = new TextView(context);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int fill = isBot ? (accent != 0 ? accent : 0xff1e78d6) : 0xff1e9e6a;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(9));
        badge.setBackground(bg);
        badge.setPadding(dp(6), dp(1), dp(6), dp(1));
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(ColgramThemePolicy.contrastInkOn(fill));
        badge.setText(ColgramAccountVaultPolicy.pillText(isBot));
        return badge;
    }

    private static void applyPinIconStyle(ImageView view, boolean pinned, int surfaceColor) {
        view.setImageResource(pinned ? R.drawable.msg_pin_filled : R.drawable.msg_pin);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int tint = pinned ? (accent != 0 ? accent : 0xffffb733) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon);
        tint = ColgramThemePolicy.ensureReadableOnSurface(tint, surfaceColor, 0xffd0d6e0, 0xff5a6370, 2.2);
        view.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        view.setAlpha(pinned ? 1.0f : 0.72f);
    }

    /** Sorts active slot indices with unlimited pinned accounts first, then login order. */
    public static ArrayList<Integer> sortActiveSlots(Context context, List<Integer> slots) {
        long[] userIds = new long[UserConfig.MAX_ACCOUNT_COUNT];
        long[] loginTimes = new long[UserConfig.MAX_ACCOUNT_COUNT];
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig cfg = UserConfig.getInstance(a);
            TLRPC.User u = cfg.getCurrentUser();
            userIds[a] = u != null ? u.id : cfg.getClientUserId();
            loginTimes[a] = cfg.loginTime;
        }
        return new ArrayList<>(ColgramAccountManager.sortActiveAccounts(context, slots, userIds, loginTimes));
    }

    /** Appends "+ Добавить аккаунт" (auto-vaulting past 5) and "Сейф аккаунтов" onto ItemOptions. */
    public static void addUnlimitedAccountActions(BaseFragment fragment, ItemOptions o, int currentAccount) {
        if (fragment == null || o == null || fragment.getContext() == null) return;
        final Context ctx = fragment.getContext();
        int activeCount = UserConfig.getActivatedAccountsCount();
        int vaultCount = countArchivedOnly(ctx);

        o.add(R.drawable.msg_addbot, LocaleController.getString(R.string.AddAccount), () -> {
            o.dismiss();
            openAddAccount(fragment, currentAccount);
        });

        String vaultTitle = "\uD83D\uDD10 \u0421\u0435\u0439\u0444 \u0430\u043A\u043A\u0430\u0443\u043D\u0442\u043E\u0432 (" + (activeCount + vaultCount) + ")";
        o.add(R.drawable.msg_secret, vaultTitle, () -> {
            o.dismiss();
            showAccountVaultSheet(fragment, currentAccount);
        });
    }

    /** Always opens LoginActivity for a new account, encrypting & archiving LRU slot when 5 slots are full. */
    public static void openAddAccount(BaseFragment fragment, int currentAccount) {
        if (fragment == null || fragment.isFinishing() || fragment.getContext() == null) return;
        BaseFragment navHost = fragment.getParentLayout() != null ? fragment : LaunchActivity.getSafeLastFragment();
        if (navHost == null || navHost.isFinishing() || navHost.getParentLayout() == null) return;
        Context ctx = fragment.getContext().getApplicationContext();

        Integer available = findFreeNativeSlot();
        if (available != null) {
            presentLoginPreservingStack(navHost, available);
            return;
        }

        boolean[] pinned = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
        long[] lastUsed = new long[UserConfig.MAX_ACCOUNT_COUNT];
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig cfg = UserConfig.getInstance(a);
            cfg.loadConfig();
            TLRPC.User u = cfg.getCurrentUser();
            long uid = u != null ? u.id : cfg.getClientUserId();
            pinned[a] = ColgramAccountManager.isAccountPinned(ctx, uid, a);
            lastUsed[a] = cfg.loginTime;
        }
        int activeAccount = UserConfig.isValidAccount(UserConfig.selectedAccount) ? UserConfig.selectedAccount : currentAccount;
        int evictSlot = ColgramAccountVaultPolicy.pickEvictableSlotAllowPinnedFallback(
                pinned, lastUsed, activeAccount);
        if (evictSlot < 0 || evictSlot == activeAccount || evictSlot == UserConfig.selectedAccount) {
            Toast.makeText(ctx, "Нет доступного слота", Toast.LENGTH_SHORT).show();
            return;
        }

        UserConfig evictCfg = UserConfig.getInstance(evictSlot);
        TLRPC.User evictUser = evictCfg.getCurrentUser();
        String evictName = evictUser != null ? UserObject.getUserName(evictUser) : ("Slot " + (evictSlot + 1));

        boolean archived = archiveNativeSlot(ctx, evictSlot);
        if (!archived) {
            Toast.makeText(ctx, "Не удалось зашифровать слот перед освобождением", Toast.LENGTH_SHORT).show();
            return;
        }

        // Local-only cleanup so the archived server auth key stays 100% valid in the vault.
        MessagesController.getInstance(evictSlot).performLogout(0);
        ColgramAccountManager.clearBotTokenForSlot(ctx, evictSlot);

        try {
            BulletinFactory.of(navHost).createSimpleBulletin(
                    R.drawable.msg_secret,
                    "Аккаунт " + evictName + " сохранён в зашифрованный сейф"
            ).show();
        } catch (Throwable ignored) {}

        presentLoginPreservingStack(navHost, evictSlot);
    }

    private static void presentLoginPreservingStack(BaseFragment navHost, int slot) {
        if (navHost == null || navHost.isFinishing() || navHost.getParentLayout() == null) return;
        // Preserve MainTabsActivity on the back stack (removeLast=false) so Back returns without restart.
        if (!navHost.presentFragment(new LoginActivity(slot), false)) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!navHost.isFinishing() && navHost.getParentLayout() != null) {
                    navHost.presentFragment(new LoginActivity(slot), false);
                }
            }, 180L);
        }
    }

    public static boolean archiveNativeSlot(Context context, int slot) {
        if (context == null || slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT) return false;
        UserConfig cfg = UserConfig.getInstance(slot);
        if (!cfg.isClientActivated()) return false;
        cfg.saveConfig(false);
        TLRPC.User u = cfg.getCurrentUser();
        long uid = u != null ? u.id : cfg.getClientUserId();
        String name = u != null ? UserObject.getUserName(u) : "";
        String username = u != null && u.username != null ? u.username : "";
        String phone = u != null && u.phone != null ? u.phone : "";
        boolean isBot = u != null && u.bot;
        return ColgramAccountManager.archiveSlot(context, slot, uid, name, username, phone, isBot);
    }

    /** Interactive bottom sheet listing ALL active and vaulted accounts with unlimited pins and 1-tap swap. */
    public static void showAccountVaultSheet(BaseFragment fragment, int currentAccount) {
        if (fragment == null || fragment.isFinishing()) return;
        final Activity activity = fragment.getParentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final Context ctx = activity.getApplicationContext();

        ArrayList<Integer> activeSlots = new ArrayList<>();
        HashSet<Long> activeUserIds = new HashSet<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig cfg = UserConfig.getInstance(a);
            cfg.loadConfig();
            if (cfg.isClientActivated()) {
                activeSlots.add(a);
                long uid = cfg.getCurrentUser() != null ? cfg.getCurrentUser().id : cfg.getClientUserId();
                if (uid > 0) activeUserIds.add(uid);
            }
        }
        activeSlots = sortActiveSlots(ctx, activeSlots);

        List<ColgramAccountManager.VaultEntry> allVault = ColgramAccountManager.listVaultedAccounts(ctx);
        List<ColgramAccountManager.VaultEntry> archivedOnly = new ArrayList<>();
        for (ColgramAccountManager.VaultEntry e : allVault) {
            if (e.userId <= 0 || !activeUserIds.contains(e.userId)) {
                archivedOnly.add(e);
            }
        }

        final org.telegram.ui.ActionBar.BottomSheet[] holder = new org.telegram.ui.ActionBar.BottomSheet[1];
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(18) + AndroidUtilities.navigationBarHeight);

        View handle = new View(activity);
        handle.setBackground(Theme.createRoundRectDrawable(dp(2.5f), pal.dark ? 0x44FFFFFF : 0x300F172A));
        root.addView(handle, LayoutHelper.createLinear(40, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        TextView sheetTitle = new TextView(activity);
        sheetTitle.setText("Сейф аккаунтов · без лимита");
        sheetTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        sheetTitle.setTypeface(AndroidUtilities.bold());
        sheetTitle.setTextColor(pal.textPrimary);
        sheetTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sheetTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        final int bgSurface = pal.cardSurface;
        final int titleColor = pal.textPrimary;
        final int subColor = pal.textSecondary;

        TextView header = new TextView(activity);
        header.setText("Активные аккаунты (" + activeSlots.size() + "/5)");
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(pal.accentPrimary);
        header.setPadding(dp(6), dp(4), dp(6), dp(6));
        root.addView(header);

        for (final int slot : activeSlots) {
            LinearLayout row = buildAccountRow(activity, slot, slot == currentAccount, () -> {
                if (holder[0] == null || !holder[0].isShowing()
                        || fragment.isFinishing() || fragment.getParentActivity() == null
                        || activity.isFinishing() || activity.isDestroyed()) return;
                holder[0].dismiss();
                showAccountVaultSheet(fragment, currentAccount);
            });
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (slot != currentAccount && LaunchActivity.instance != null) {
                    LaunchActivity.instance.switchToAccount(slot, true);
                }
            });
            root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 0, 0, 4));
        }

        TextView vaultHeader = new TextView(activity);
        vaultHeader.setText("В зашифрованном сейфе AES-256 (" + archivedOnly.size() + ")");
        vaultHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        vaultHeader.setTypeface(AndroidUtilities.bold());
        vaultHeader.setTextColor(pal.accentPrimary);
        vaultHeader.setPadding(dp(6), dp(12), dp(6), dp(6));
        root.addView(vaultHeader);

        if (archivedOnly.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("Когда слотов больше 5 или вы нажмёте «Сохранить в сейф», аккаунты появятся здесь.");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(subColor);
            empty.setPadding(dp(8), dp(4), dp(8), dp(8));
            root.addView(empty);
        } else {
            for (final ColgramAccountManager.VaultEntry entry : archivedOnly) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), dp(8), dp(8)));
                row.setPadding(dp(8), dp(6), dp(4), dp(6));

                TLRPC.TL_user vaultAvatarUser = new TLRPC.TL_user();
                vaultAvatarUser.id = entry.userId;
                vaultAvatarUser.first_name = !TextUtils.isEmpty(entry.name) ? entry.name : ("UID " + entry.userId);
                vaultAvatarUser.username = entry.username;
                vaultAvatarUser.bot = entry.isBot;
                AvatarDrawable vaultAvatarDrawable = new AvatarDrawable();
                vaultAvatarDrawable.setInfo(currentAccount, vaultAvatarUser);
                final BackupImageView vaultAvatarView = new BackupImageView(activity);
                vaultAvatarView.setRoundRadius(dp(16));
                vaultAvatarView.setImageDrawable(vaultAvatarDrawable);
                if (entry.userId > 0) {
                    org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                        java.io.File cachedVaultAvatar = org.colgram.core.ColgramBotSync.getCachedBotAvatarFile(currentAccount, entry.userId);
                        if (cachedVaultAvatar != null && cachedVaultAvatar.exists() && cachedVaultAvatar.length() > 64) {
                            final String path = cachedVaultAvatar.getAbsolutePath();
                            AndroidUtilities.runOnUIThread(() -> {
                                if (vaultAvatarView.isAttachedToWindow()) {
                                    vaultAvatarView.setImage(
                                            org.telegram.messenger.ImageLocation.getForPath(path),
                                            "50_50", vaultAvatarDrawable, vaultAvatarUser);
                                }
                            });
                        }
                    });
                }
                row.addView(vaultAvatarView, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

                LinearLayout infoCol = new LinearLayout(activity);
                infoCol.setOrientation(LinearLayout.VERTICAL);

                TextView nameView = new TextView(activity);
                nameView.setText(TextUtils.isEmpty(entry.name) ? ("UID " + entry.userId) : entry.name);
                nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                nameView.setTypeface(AndroidUtilities.bold());
                nameView.setTextColor(titleColor);
                nameView.setSingleLine(true);
                nameView.setEllipsize(TextUtils.TruncateAt.END);
                infoCol.addView(nameView);

                String meta = !entry.username.isEmpty() ? ("@" + entry.username)
                        : (!entry.phone.isEmpty() ? entry.phone : ("ID " + entry.userId));
                TextView sub = new TextView(activity);
                sub.setText(meta + " · Нажмите для входа");
                sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                sub.setTextColor(subColor);
                sub.setSingleLine(true);
                infoCol.addView(sub);

                row.addView(infoCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                row.addView(buildRoleBadge(activity, entry.isBot),
                        LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 20, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));

                boolean pinned = ColgramAccountManager.isVaultEntryPinned(ctx, entry.vaultKey, entry.userId);
                ImageView pinBtn = new ImageView(activity);
                pinBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                applyPinIconStyle(pinBtn, pinned, bgSurface);
                pinBtn.setOnClickListener(v -> {
                    ColgramAccountManager.toggleVaultEntryPin(ctx, entry.vaultKey, entry.userId);
                    pinBtn.animate().scaleX(1.22f).scaleY(1.22f).setDuration(90)
                            .withEndAction(() -> pinBtn.animate().scaleX(1f).scaleY(1f).setDuration(90)
                                    .withEndAction(() -> {
                                        if (holder[0] == null || !holder[0].isShowing()
                                                || fragment.isFinishing() || activity.isFinishing() || activity.isDestroyed()) return;
                                        holder[0].dismiss();
                                        showAccountVaultSheet(fragment, currentAccount);
                                    }).start())
                            .start();
                });
                row.addView(pinBtn, LayoutHelper.createLinear(28, 28));

                ImageView delBtn = new ImageView(activity);
                delBtn.setImageResource(R.drawable.msg_delete);
                delBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                delBtn.setColorFilter(Theme.getColor(Theme.key_text_RedRegular), PorterDuff.Mode.SRC_IN);
                delBtn.setOnClickListener(v -> {
                    ColgramAccountManager.removeVaultAccount(ctx, entry.vaultKey);
                    if (holder[0] != null) holder[0].dismiss();
                    if (!fragment.isFinishing() && !activity.isFinishing() && !activity.isDestroyed()) {
                        showAccountVaultSheet(fragment, currentAccount);
                    }
                });
                row.addView(delBtn, LayoutHelper.createLinear(28, 28, 2, 0, 0, 0));

                row.setOnClickListener(v -> {
                    if (holder[0] != null) holder[0].dismiss();
                    restoreVaultAccountAndRelaunch(activity, entry);
                });
                root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(14), 0, dp(4));

        TextView saveBtn = new TextView(activity);
        saveBtn.setText("В сейф");
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        saveBtn.setTypeface(AndroidUtilities.bold());
        saveBtn.setTextColor(pal.textPrimary);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setMinHeight(dp(44));
        ColgramMenuChrome.attachTactileFeedback(saveBtn, 12, pal.chipSurface, pal.borderSubtle, pal.accentSoft);
        saveBtn.setOnClickListener(v -> {
            saveBtn.setEnabled(false);
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                int saved = 0;
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (archiveNativeSlot(ctx, a)) saved++;
                }
                final int savedCount = saved;
                AndroidUtilities.runOnUIThread(() -> {
                    Toast.makeText(ctx, "Зашифровано аккаунтов: " + savedCount, Toast.LENGTH_SHORT).show();
                    if (holder[0] != null && holder[0].isShowing()) {
                        holder[0].dismiss();
                        if (!fragment.isFinishing() && !activity.isFinishing() && !activity.isDestroyed()) {
                            showAccountVaultSheet(fragment, currentAccount);
                        }
                    }
                });
            });
        });
        actions.addView(saveBtn, LayoutHelper.createLinear(0, 44, 1f, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        TextView addBtn = new TextView(activity);
        addBtn.setText("+ Добавить аккаунт");
        addBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        addBtn.setTypeface(AndroidUtilities.bold());
        addBtn.setTextColor(0xFFFFFFFF);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setMinHeight(dp(44));
        addBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12), pal.accentPrimary, pal.accentSecondary));
        ColgramMenuChrome.attachSpringPress(addBtn);
        addBtn.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            openAddAccount(fragment, currentAccount);
        });
        actions.addView(addBtn, LayoutHelper.createLinear(0, 44, 1.35f, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
        root.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Full persistent backup / restore row (survives app updates AND full uninstall + reinstall)
        LinearLayout backupRow = new LinearLayout(activity);
        backupRow.setOrientation(LinearLayout.HORIZONTAL);
        backupRow.setGravity(Gravity.CENTER);
        backupRow.setPadding(0, dp(8), 0, dp(2));

        TextView fullBackupBtn = new TextView(activity);
        fullBackupBtn.setText("Полный бэкап (на диск)");
        fullBackupBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        fullBackupBtn.setTypeface(AndroidUtilities.bold());
        fullBackupBtn.setTextColor(pal.textPrimary);
        fullBackupBtn.setGravity(Gravity.CENTER);
        fullBackupBtn.setMinHeight(dp(40));
        ColgramMenuChrome.attachTactileFeedback(fullBackupBtn, 12, pal.chipSurface, pal.borderSubtle, pal.accentSoft);
        fullBackupBtn.setOnClickListener(v -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            ColgramUiKit.showInputSheet(
                    activity,
                    null,
                    R.drawable.msg_archive,
                    "Сохранить в Documents/ColgramBackup",
                    "AES-256 резервная копия всех аккаунтов и настроек",
                    "Мастер-пароль (мин. 4 символа)",
                    null,
                    android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    true,
                    !org.colgram.core.ColgramHookHandler.shouldBypassFlagSecure(),
                    true,
                    "Сохранить",
                    rawPass -> {
                        String pass = rawPass != null ? rawPass : "";
                        if (pass.length() < 4) {
                            ColgramUiKit.showNotice(ctx, R.drawable.msg_info, "Минимум 4 символа", true);
                            return;
                        }
                        final char[] passChars = pass.toCharArray();
                        fullBackupBtn.setEnabled(false);
                        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                            try {
                                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                                    if (UserConfig.getInstance(a).isClientActivated()) {
                                        UserConfig.getInstance(a).saveConfig(true);
                                    }
                                }
                                ColgramPersistentBackup.BackupResult res = ColgramPersistentBackup.backupNow(ctx, passChars);
                                AndroidUtilities.runOnUIThread(() -> {
                                    fullBackupBtn.setEnabled(true);
                                    if (res.success) {
                                        ColgramUiKit.showNotice(ctx, R.drawable.msg_archive,
                                                "Сохранено (" + res.accountCount + " акк.) -> " + res.location, false);
                                    } else {
                                        ColgramUiKit.showNotice(ctx, R.drawable.msg_info,
                                                "Ошибка бэкапа: " + res.error, true);
                                    }
                                });
                            } finally {
                                java.util.Arrays.fill(passChars, '\0');
                            }
                        });
                    },
                    null,
                    null
            );
        });
        backupRow.addView(fullBackupBtn, LayoutHelper.createLinear(0, 40, 1f, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        TextView restoreBtn = new TextView(activity);
        restoreBtn.setText("Восстановить копию");
        restoreBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        restoreBtn.setTypeface(AndroidUtilities.bold());
        restoreBtn.setTextColor(pal.accentPrimary);
        restoreBtn.setGravity(Gravity.CENTER);
        restoreBtn.setMinHeight(dp(40));
        ColgramMenuChrome.attachTactileFeedback(restoreBtn, 12, pal.chipSurface, pal.borderSubtle, pal.accentSoft);
        restoreBtn.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            launchPersistentBackupRestore(activity);
        });
        backupRow.addView(restoreBtn, LayoutHelper.createLinear(0, 40, 1f, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
        root.addView(backupRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final int maxH = Math.round(AndroidUtilities.displaySize.y * 0.72f);
        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int hMode = View.MeasureSpec.getMode(heightMeasureSpec);
                int hSize = View.MeasureSpec.getSize(heightMeasureSpec);
                int capped = (hMode == View.MeasureSpec.UNSPECIFIED)
                        ? View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
                        : View.MeasureSpec.makeMeasureSpec(Math.min(hSize, maxH), hMode);
                super.onMeasure(widthMeasureSpec, capped);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(root);

        org.telegram.ui.ActionBar.BottomSheet.Builder builder =
                new org.telegram.ui.ActionBar.BottomSheet.Builder(activity, false, fragment.getResourceProvider());
        builder.setApplyTopPadding(false);
        builder.setCustomView(scroll);
        org.telegram.ui.ActionBar.BottomSheet sheet = builder.create();
        ColgramAeroStyler.applyFlagshipSheetChrome(sheet, scroll, pal);
        holder[0] = sheet;
        fragment.showDialog(sheet);
    }

    private static void restoreVaultAccountAndRelaunch(Activity activity, ColgramAccountManager.VaultEntry entry) {
        Context ctx = activity.getApplicationContext();
        // If already present in a native slot, switch cleanly without restart.
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig cfg = UserConfig.getInstance(a);
            if (cfg.isClientActivated()) {
                long uid = cfg.getCurrentUser() != null ? cfg.getCurrentUser().id : cfg.getClientUserId();
                if (entry.userId > 0 && uid == entry.userId) {
                    if (LaunchActivity.instance != null) {
                        LaunchActivity.instance.switchToAccount(a, true);
                    }
                    return;
                }
            }
        }

        Integer freeSlot = findFreeNativeSlot();
        int targetSlot;
        if (freeSlot != null) {
            targetSlot = freeSlot;
        } else {
            boolean[] pinned = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
            long[] lastUsed = new long[UserConfig.MAX_ACCOUNT_COUNT];
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig cfg = UserConfig.getInstance(a);
                long uid = cfg.getCurrentUser() != null ? cfg.getCurrentUser().id : cfg.getClientUserId();
                pinned[a] = ColgramAccountManager.isAccountPinned(ctx, uid, a);
                lastUsed[a] = cfg.loginTime;
            }
            targetSlot = ColgramAccountVaultPolicy.pickEvictableSlotAllowPinnedFallback(
                    pinned, lastUsed, UserConfig.selectedAccount);
        }

        ColgramAccountManager.VaultBundle bundle = ColgramAccountManager.readVaultBundle(ctx, entry.vaultKey);
        if (bundle == null) {
            Toast.makeText(ctx, "Ошибка расшифровки аккаунта", Toast.LENGTH_SHORT).show();
            return;
        }

        if (UserConfig.getInstance(targetSlot).isClientActivated()) {
            if (!archiveNativeSlot(ctx, targetSlot)) {
                Toast.makeText(ctx, "Не удалось сохранить текущий слот", Toast.LENGTH_SHORT).show();
                return;
            }
            MessagesController.getInstance(targetSlot).performLogout(0);
            ColgramAccountManager.clearBotTokenForSlot(ctx, targetSlot);
        }

        if (!ColgramAccountManager.writeVaultBundleToSlot(ctx, bundle, targetSlot)) {
            Toast.makeText(ctx, "Ошибка записи сессии в слот", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(ctx, "Переключение на " + entry.name + "...", Toast.LENGTH_SHORT).show();
        Intent relaunch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (relaunch != null) {
            relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(relaunch);
        }
        AndroidUtilities.runOnUIThread(() ->
                android.os.Process.killProcess(android.os.Process.myPid()), 90);
    }

    private static Integer findFreeNativeSlot() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig cfg = UserConfig.getInstance(a);
            cfg.loadConfig();
            if (!cfg.isClientActivated()) {
                return a;
            }
        }
        return null;
    }

    private static volatile char[] pendingExportPassphrase = null;

    /** Opens Android SAF picker pre-navigated to Documents/ColgramBackup to restore a full backup. */
    public static void launchPersistentBackupRestore(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            activity.startActivityForResult(
                    ColgramPersistentBackup.buildOpenBackupIntent(),
                    ColgramPersistentBackup.REQ_RESTORE_PERSISTENT_BACKUP);
        } catch (Throwable t) {
            Toast.makeText(activity.getApplicationContext(),
                    "Не удалось открыть выбор файла: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Opens Android SAF save dialog to export a portable passphrase-encrypted full backup. */
    public static void launchPersistentBackupExport(Activity activity, char[] optionalPassphrase) {
        if (activity == null || activity.isFinishing()) return;
        pendingExportPassphrase = optionalPassphrase != null && optionalPassphrase.length > 0
                ? java.util.Arrays.copyOf(optionalPassphrase, optionalPassphrase.length) : null;
        try {
            activity.startActivityForResult(
                    ColgramPersistentBackup.buildCreateBackupIntent(),
                    ColgramPersistentBackup.REQ_EXPORT_PERSISTENT_BACKUP);
        } catch (Throwable t) {
            pendingExportPassphrase = null;
            Toast.makeText(activity.getApplicationContext(),
                    "Не удалось открыть сохранение файла: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Handles SAF results from LaunchActivity.onActivityResult for full-vault backup & restore. */
    public static boolean onPersistentBackupActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != ColgramPersistentBackup.REQ_RESTORE_PERSISTENT_BACKUP
                && requestCode != ColgramPersistentBackup.REQ_EXPORT_PERSISTENT_BACKUP) {
            return false;
        }
        if (activity == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingExportPassphrase = null;
            return true;
        }
        final android.net.Uri uri = data.getData();
        final Context appCtx = activity.getApplicationContext();

        if (requestCode == ColgramPersistentBackup.REQ_EXPORT_PERSISTENT_BACKUP) {
            final char[] pass = pendingExportPassphrase;
            pendingExportPassphrase = null;
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                try {
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        if (UserConfig.getInstance(a).isClientActivated()) {
                            UserConfig.getInstance(a).saveConfig(true);
                        }
                    }
                    byte[] encrypted = ColgramPersistentBackup.buildEncryptedBackupBytes(appCtx, pass);
                    try (java.io.OutputStream os = appCtx.getContentResolver().openOutputStream(uri, "wt")) {
                        if (os == null) throw new IllegalStateException("Не удалось открыть поток записи");
                        os.write(encrypted);
                        os.flush();
                    }
                    AndroidUtilities.runOnUIThread(() ->
                            Toast.makeText(appCtx, "Полная зашифрованная копия сохранена!", Toast.LENGTH_LONG).show());
                } catch (Throwable t) {
                    AndroidUtilities.runOnUIThread(() ->
                            Toast.makeText(appCtx, "Ошибка экспорта: " + t.getMessage(), Toast.LENGTH_LONG).show());
                } finally {
                    if (pass != null) java.util.Arrays.fill(pass, '\0');
                }
            });
            return true;
        }

        // Restore flow
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                final byte[] blob = ColgramPersistentBackup.readUriBytes(appCtx, uri);
                if (ColgramPersistentBackup.isPassphraseProtected(blob)) {
                    AndroidUtilities.runOnUIThread(() -> promptRestorePassphraseAndApply(activity, blob));
                } else {
                    ColgramPersistentBackup.RestoreResult res = ColgramPersistentBackup.restoreFromBytes(appCtx, blob, null);
                    AndroidUtilities.runOnUIThread(() -> finishPersistentRestoreUi(activity, res));
                }
            } catch (Throwable t) {
                AndroidUtilities.runOnUIThread(() ->
                        Toast.makeText(appCtx, "Ошибка чтения копии: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
        return true;
    }

    private static void promptRestorePassphraseAndApply(Activity activity, byte[] blob) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final Context appCtx = activity.getApplicationContext();
        ColgramUiKit.showInputSheet(
                activity,
                null,
                R.drawable.msg_secret,
                "Расшифровка копии Colgram",
                "Введите мастер-пароль для расшифровки резервной копии",
                "Пароль от резервной копии",
                null,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                true,
                !org.colgram.core.ColgramHookHandler.shouldBypassFlagSecure(),
                true,
                "Восстановить",
                rawPass -> {
                    String passStr = rawPass != null ? rawPass : "";
                    if (passStr.isEmpty()) {
                        ColgramUiKit.showNotice(appCtx, R.drawable.msg_info, "Введите пароль", true);
                        return;
                    }
                    final char[] chars = passStr.toCharArray();
                    org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                        ColgramPersistentBackup.RestoreResult res = ColgramPersistentBackup.restoreFromBytes(appCtx, blob, chars);
                        java.util.Arrays.fill(chars, '\0');
                        AndroidUtilities.runOnUIThread(() -> finishPersistentRestoreUi(activity, res));
                    });
                },
                null,
                null
        );
    }

    private static void finishPersistentRestoreUi(Activity activity, ColgramPersistentBackup.RestoreResult res) {
        Context appCtx = activity.getApplicationContext();
        if (!res.success) {
            Toast.makeText(appCtx, res.error.isEmpty() ? "Не удалось восстановить копию" : res.error, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(appCtx,
                "Восстановлено данных: " + res.restoredFiles + " (+сейф: " + res.restoredVaultAccounts + "). Перезапуск…",
                Toast.LENGTH_LONG).show();
        Intent relaunch = appCtx.getPackageManager().getLaunchIntentForPackage(appCtx.getPackageName());
        if (relaunch != null) {
            relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(relaunch);
        }
        AndroidUtilities.runOnUIThread(() ->
                android.os.Process.killProcess(android.os.Process.myPid()), 180L);
    }

    private static int countArchivedOnly(Context ctx) {
        HashSet<Long> active = new HashSet<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig cfg = UserConfig.getInstance(a);
            cfg.loadConfig();
            if (cfg.isClientActivated()) {
                long uid = cfg.getCurrentUser() != null ? cfg.getCurrentUser().id : cfg.getClientUserId();
                if (uid > 0) active.add(uid);
            }
        }
        int c = 0;
        for (ColgramAccountManager.VaultEntry e : ColgramAccountManager.listVaultedAccounts(ctx)) {
            if (e.userId <= 0 || !active.contains(e.userId)) c++;
        }
        return c;
    }
}
