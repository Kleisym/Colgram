package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.LinearLayout;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramBotSync;
import org.colgram.core.ColgramCloak;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramDpiBypass;
import org.colgram.core.ColgramOperationGate;
import org.colgram.core.ColgramRouteEpoch;
import org.colgram.core.ColgramProxyDoctor;
import org.colgram.core.ColgramProxyManager;
import org.colgram.core.ColgramSettingsBackup;
import org.colgram.core.ColgramStorageSandbox;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class ColgramSettingsActivity extends BaseFragment {

    private static final String CLOUDFLARE_WARP_PACKAGE = "com.cloudflare.onedotonedotonedotone";
    /** Request code for the system VPN consent dialog started by prepareAndStartWarp(). */
    private static final int REQ_WARP_VPN = 9181;
    /** Android's consent for the subscription tunnel, which routes the whole phone. */
    private static final int REQ_SUBSCRIPTION_VPN = 9183;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int heroBannerRow;
    private int cloakingHeaderRow;
    private int cloakEnabledRow;
    private int cloakModelRow;
    private int cloakingSectionRow;

    private int vaultHeaderRow;
    private int antiDeleteRow;
    private int antiDeleteSelfRow;
    private int antiDeleteHighlightRow;
    private int antiDeleteWipeRow;
    private int preserveMediaRow;
    private int editHistoryRow;
    private int autoHidePhoneRow;
    private int saveOneTimeMediaRow;
    private int hideScreenshotNotifyRow;
    private int forwardNoQuoteRow;
    private int showMessageSecondsRow;
    private int showMessageIdRow;
    private int vaultSectionRow;

    private int ghostHeaderRow;
    private int quickBarRow;
    private int maxAnonymityRow;
    private int ghostReadRow;
    private int ghostTypingRow;
    private int ghostOnlineRow;
    private int stealthStoriesRow;
    private int hidePhoneLocalRow;
    private int bypassFlagSecureRow;
    private int confirmCallsRow;
    private int confirmStickersRow;
    private int confirmLinksRow;
    private int ghostSectionRow;
    private int cloudflareWarpRow;
    private int subscriptionRow;

    private static final ColgramOperationGate warpOperationsGate = org.colgram.core.ColgramRouteControl.WARP_GATE;
    private static final java.util.concurrent.ExecutorService warpOperations =
            org.colgram.core.ColgramRouteControl.OPERATIONS;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final ColgramSubscriptionControls subscription =
            new ColgramSubscriptionControls(this, REQ_SUBSCRIPTION_VPN, this::refreshSettings);
    private long waitingWarpConsent = -1L;
    private long warpRouteEpoch = -1L;
    private boolean screenVisible;
    private long lastNavTapMs;
    private final Runnable proxyCheckRefreshRunnable = () -> {
        if (!isFinishing() && listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    };
    private String lastVisibleNetworkState = "";
    private final Runnable refreshVisibleNetworkState = new Runnable() {
        @Override
        public void run() {
            if (!screenVisible) return;
            String state = warpStatusLine() + "|" + ColgramProxyDoctor.getStatusSummary()
                    + "|" + ColgramConfig.isWarpEnabled() + "|" + dpiStatusLine()
                    + "|" + ColgramProxyManager.hasActiveRouteForUi()
                    + "|" + ColgramProxyManager.getProxySelectionStatus() + "|" + subscriptionStatusLine()
                    + "|" + isAndroidVpnActive();
            if (!state.equals(lastVisibleNetworkState)) {
                lastVisibleNetworkState = state;
                if (listAdapter != null && getParentActivity() != null) {
                    int[] rowsToUpdate = {
                            heroBannerRow, directConnectionRow, publicProxyRow, cloudflareWarpRow,
                            subscriptionRow, subscriptionNodeRow, currentProxyRow,
                            proxyStatusRow, dpiBypassRow, callsRow, browserRow
                    };
                    for (int r : rowsToUpdate) {
                        if (r >= 0 && r < rowCount) {
                            listAdapter.notifyItemChanged(r);
                        }
                    }
                }
            }
            uiHandler.postDelayed(this, 1500L);
        }
    };

    private int networkHeaderRow;
    private int directConnectionRow;
    private int publicProxyRow;
    private int subscriptionNodeRow;
    private int callsRow;
    private int browserRow;
    private int resourceHeaderRow;
    private int dataSettingsRow;
    private int cacheSettingsRow;
    private int resourceSectionRow;
    private int dpiBypassRow;
    private int dohRow;
    private int builtinProxyRow;
    private int proxyBrowserRow;
    private int currentProxyRow;
    private int ownProxyRow;
    private int proxyStatusRow;
    private int relayUrlRow;
    private int autoProxyRow;
    private int ipv6BypassRow;
    private int dcRemapRow;
    private int networkSectionRow;

    private int sandboxHeaderRow;
    private int sandboxStorageRow;
    private int unlimitedMemoryRow;
    private int sandboxFilesRow;
    private int accountVaultRow;
    private int liveWallpaperRow;
    private int easterEggsRow;
    private int alwaysBackgroundRow;
    private int batteryAutostartRow;
    private int settingsBackupRow;
    private int sandboxSectionRow;

    private int botsHeaderRow;
    private int defenderRow;
    private int botRealtimeRow;
    private int botTestRow;
    private String lastBotTestReport;
    private int pluginsRow;
    private int antiSpamRow;
    private int autoReplyRow;
    private int tempMailRow;
    private int versionsRow;
    private int botsSectionRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        ColgramConfig.init(getParentActivity());
        updateRows();
        return true;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_WARP_VPN) {
            if (!warpRequested(waitingWarpConsent)) return;
            if (resultCode == android.app.Activity.RESULT_OK) {
                startWarpTunnel();
            } else {
                Context context = getContext();
                if (context != null) cancelWarp(context);
                refreshSettings();
            }
            return;
        }
        if (subscription.onActivityResult(requestCode, resultCode)) return;
        super.onActivityResultFragment(requestCode, resultCode, data);
    }

    private void updateRows() {
        rowCount = 0;
        heroBannerRow = rowCount++;
        cloakingHeaderRow = rowCount++;
        cloakEnabledRow = rowCount++;
        cloakModelRow = rowCount++;
        cloakingSectionRow = rowCount++;

        vaultHeaderRow = rowCount++;
        antiDeleteRow = rowCount++;
        antiDeleteSelfRow = rowCount++;
        preserveMediaRow = rowCount++;
        editHistoryRow = rowCount++;
        antiDeleteHighlightRow = rowCount++;
        antiDeleteWipeRow = rowCount++;
        autoHidePhoneRow = rowCount++;
        saveOneTimeMediaRow = rowCount++;
        hideScreenshotNotifyRow = rowCount++;
        forwardNoQuoteRow = rowCount++;
        showMessageSecondsRow = rowCount++;
        showMessageIdRow = rowCount++;
        vaultSectionRow = rowCount++;

        ghostHeaderRow = rowCount++;
        quickBarRow = rowCount++;
        maxAnonymityRow = rowCount++;
        ghostReadRow = rowCount++;
        ghostTypingRow = rowCount++;
        ghostOnlineRow = rowCount++;
        stealthStoriesRow = rowCount++;
        hidePhoneLocalRow = rowCount++;
        bypassFlagSecureRow = rowCount++;
        confirmCallsRow = rowCount++;
        confirmStickersRow = rowCount++;
        confirmLinksRow = rowCount++;
        ghostSectionRow = rowCount++;
        networkHeaderRow = rowCount++;
        directConnectionRow = rowCount++;
        publicProxyRow = rowCount++;
        cloudflareWarpRow = rowCount++;
        subscriptionRow = rowCount++;
        subscriptionNodeRow = rowCount++;
        callsRow = rowCount++;
        browserRow = rowCount++;
        dpiBypassRow = rowCount++;
        dohRow = rowCount++;
        builtinProxyRow = rowCount++;
        proxyBrowserRow = rowCount++;
        currentProxyRow = rowCount++;
        ownProxyRow = rowCount++;
        proxyStatusRow = rowCount++;
        relayUrlRow = rowCount++;
        autoProxyRow = rowCount++;
        ipv6BypassRow = rowCount++;
        dcRemapRow = rowCount++;
        networkSectionRow = rowCount++;

        resourceHeaderRow = rowCount++;
        dataSettingsRow = rowCount++;
        cacheSettingsRow = rowCount++;
        resourceSectionRow = rowCount++;

        sandboxHeaderRow = rowCount++;
        sandboxStorageRow = rowCount++;
        unlimitedMemoryRow = rowCount++;
        sandboxFilesRow = rowCount++;
        accountVaultRow = rowCount++;
        liveWallpaperRow = rowCount++;
        easterEggsRow = rowCount++;
        alwaysBackgroundRow = rowCount++;
        batteryAutostartRow = rowCount++;
        settingsBackupRow = rowCount++;
        sandboxSectionRow = rowCount++;

        botsHeaderRow = rowCount++;
        defenderRow = rowCount++;
        botRealtimeRow = rowCount++;
        botTestRow = rowCount++;
        pluginsRow = rowCount++;
        antiSpamRow = rowCount++;
        autoReplyRow = rowCount++;
        tempMailRow = rowCount++;
        versionsRow = rowCount++;
        botsSectionRow = rowCount++;

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Настройки Colgram");
        ColgramMenuChrome.styleActionBar(actionBar);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (isFinishing()) return;
            if (position == defenderRow || position == antiSpamRow || position == autoReplyRow
                    || position == tempMailRow || position == versionsRow
                    || position == dataSettingsRow || position == cacheSettingsRow
                    || position == ownProxyRow || position == sandboxFilesRow
                    || position == pluginsRow || position == accountVaultRow) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastNavTapMs < 360L) return;
                lastNavTapMs = now;
            }
            if (position == defenderRow) {
                presentFragment(new ColgramDefenderActivity());
                return;
            } else if (position == antiSpamRow) {
                presentFragment(new ColgramAntiSpamActivity());
                return;
            } else if (position == autoReplyRow) {
                presentFragment(new ColgramAutoReplyActivity());
                return;
            } else if (position == tempMailRow) {
                presentFragment(new ColgramTempMailActivity());
                return;
            } else if (position == versionsRow) {
                presentFragment(new ColgramVersionsActivity());
                return;
            }
            if (position == ipv6BypassRow || position == dcRemapRow) {
                Context routeContext = getContext();
                if (routeContext != null) { cancelWarp(routeContext); stopSubscriptionTunnel(routeContext); }
                ColgramRouteEpoch.next();
                ColgramProxyManager.cancelProxySelection();
            }
            if (position == cloudflareWarpRow) {
                openCloudflareWarp();
            } else if (position == subscriptionRow) {
                subscription.showMenu();
            } else if (position == subscriptionNodeRow) {
                subscription.showNodes();
            } else if (position == publicProxyRow) {
                if (ColgramProxyManager.isProxySelectionPending()) {
                    ColgramRouteEpoch.next();
                    ColgramProxyManager.cancelProxySelection();
                } else {
                    Context routeContext = getContext();
                    if (routeContext != null) { cancelWarp(routeContext); stopSubscriptionTunnel(routeContext); }
                    ColgramProxyManager.selectPublicProxy(getContext());
                }
                refreshSettings();
            } else if (position == callsRow) {
                ColgramCallRoutes.showPicker(getParentActivity(), this::refreshSettings);
            } else if (position == browserRow) {
                ColgramBrowserRoutes.showPicker(getParentActivity(), this::refreshSettings);
            } else if (position == directConnectionRow) {
                selectDirectConnection();
            } else if (position == dataSettingsRow) {
                presentFragment(new DataSettingsActivity());
            } else if (position == cacheSettingsRow) {
                presentFragment(new CacheControlActivity());
            } else if (position == cloakEnabledRow) {
                boolean val = !ColgramConfig.isCloakEnabled();
                ColgramConfig.setCloakEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
                if (listAdapter != null && cloakModelRow >= 0) {
                    listAdapter.notifyItemChanged(cloakModelRow);
                }
                promptRestartForCloakChange(val
                        ? "Маскировка включена. Перезапустите Colgram, чтобы Telegram-сессия сразу переподключилась с новым отпечатком устройства."
                        : "Маскировка отключена. Перезапустите Colgram для применения изменений к MTProto-сессии.");
            } else if (position == cloakModelRow) {
                showModelSelector();
            } else if (position == antiDeleteRow) {
                boolean val = !ColgramConfig.isAntiDeleteEnabled();
                ColgramConfig.setAntiDeleteEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == preserveMediaRow) {
                boolean val = !ColgramConfig.isPreserveMediaEnabled();
                ColgramConfig.setPreserveMediaEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == editHistoryRow) {
                boolean val = !ColgramConfig.isEditHistoryEnabled();
                ColgramConfig.setEditHistoryEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == antiDeleteHighlightRow) {
                boolean val = !ColgramConfig.isAntiDeleteHighlightEnabled();
                ColgramConfig.setAntiDeleteHighlightEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == antiDeleteWipeRow) {
                boolean val = !ColgramConfig.isAntiDeleteWipeEnabled();
                ColgramConfig.setAntiDeleteWipeEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == antiDeleteSelfRow) {
                boolean val = !ColgramConfig.isAntiDeleteSelfEnabled();
                ColgramConfig.setAntiDeleteSelfEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == autoHidePhoneRow) {
                boolean val = !ColgramConfig.isAutoHidePhoneEnabled();
                ColgramConfig.setAutoHidePhoneEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == saveOneTimeMediaRow) {
                boolean val = !ColgramConfig.isSaveOneTimeMediaEnabled();
                ColgramConfig.setSaveOneTimeMediaEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == hideScreenshotNotifyRow) {
                boolean val = !ColgramConfig.isHideScreenshotNotifyEnabled();
                ColgramConfig.setHideScreenshotNotifyEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == forwardNoQuoteRow) {
                boolean val = !ColgramConfig.isForwardNoQuoteEnabled();
                ColgramConfig.setForwardNoQuoteEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == showMessageSecondsRow) {
                boolean val = !ColgramConfig.isShowMessageSecondsEnabled();
                ColgramConfig.setShowMessageSecondsEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == showMessageIdRow) {
                boolean val = !ColgramConfig.isShowMessageIdEnabled();
                ColgramConfig.setShowMessageIdEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == quickBarRow) {
                boolean val = !ColgramConfig.isQuickBarEnabled();
                ColgramConfig.setQuickBarEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
                Toast.makeText(getParentActivity(),
                        val ? "Панель быстрого доступа включена" : "Панель быстрого доступа скрыта",
                        Toast.LENGTH_SHORT).show();
            } else if (position == maxAnonymityRow) {
                boolean val = !ColgramConfig.isMaxAnonymityEnabled();
                ColgramConfig.setMaxAnonymityEnabled(val);
                if (val) {
                    ColgramQuickBarView.triggerGhostOfflineAllAccounts();
                }
                refreshSettings();
            } else if (position == ghostReadRow) {
                boolean val = !ColgramConfig.isGhostReadEnabled();
                ColgramConfig.setGhostReadEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == ghostTypingRow) {
                boolean val = !ColgramConfig.isGhostTypingEnabled();
                ColgramConfig.setGhostTypingEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == ghostOnlineRow) {
                boolean val = !ColgramConfig.isGhostOnlineEnabled();
                ColgramConfig.setGhostOnlineEnabled(val);
                if (val) {
                    ColgramQuickBarView.triggerGhostOfflineAllAccounts();
                }
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == stealthStoriesRow) {
                boolean val = !ColgramConfig.isStealthStoriesEnabled();
                ColgramConfig.setStealthStoriesEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == hidePhoneLocalRow) {
                boolean val = !ColgramConfig.isHidePhoneLocalEnabled();
                ColgramConfig.setHidePhoneLocalEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == bypassFlagSecureRow) {
                boolean val = !ColgramConfig.isBypassFlagSecureEnabled();
                ColgramConfig.setBypassFlagSecureEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == confirmCallsRow) {
                boolean val = !ColgramConfig.isConfirmCallsEnabled();
                ColgramConfig.setConfirmCallsEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == confirmStickersRow) {
                boolean val = !ColgramConfig.isConfirmStickersEnabled();
                ColgramConfig.setConfirmStickersEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == confirmLinksRow) {
                boolean val = !ColgramConfig.isConfirmLinksEnabled();
                ColgramConfig.setConfirmLinksEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == dohRow) {
                boolean val = !ColgramConfig.isDohEnabled();
                ColgramConfig.setDohEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == builtinProxyRow) {
                boolean val = !ColgramConfig.isBuiltinProxyEnabled();
                ColgramConfig.setBuiltinProxyEnabled(val);
                if (val) ColgramProxyManager.refreshProxySourcesInBackground(getContext(), this::refreshSettings);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == proxyBrowserRow) {
                boolean val = !ColgramConfig.isProxyBrowserEnabled();
                ColgramConfig.setProxyBrowserEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == currentProxyRow) {
                showPublicProxyNodes();
            } else if (position == ownProxyRow) {
                // Telegram's own proxy screen, reached from Colgram. It is where a proxy gets
                // entered properly - by t.me/proxy, t.me/webproxy or t.me/socks link, or by
                // hand - including a WebSocket (wss) proxy, the one kind that gets through an
                // IP-level block with no VPN and no server of your own. Colgram only ever
                // offered its own list here, so that mechanism was unreachable from this app.
                presentFragment(new ProxyListActivity());
            } else if (position == autoProxyRow) {
                boolean val = !ColgramConfig.isAutoProxyEnabled();
                ColgramConfig.setAutoProxyEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == dcRemapRow) {
                boolean remap = !ColgramConfig.isDcRemapEnabled();
                ColgramConfig.setDcRemapEnabled(remap);
                ColgramProxyManager.applyDcRemapFromUser(remap);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(remap);
                }
                Toast.makeText(getParentActivity(), remap
                        ? "Telegram будет звонить на тот свой адрес, который отвечает. Это не прокси: между тобой и Telegram остаётся только Telegram."
                        : "Прямой выбор адреса выключен.",
                        Toast.LENGTH_LONG).show();
            } else if (position == ipv6BypassRow) {
                boolean val = !ColgramConfig.isIpv6BypassEnabled();
                ColgramConfig.setIpv6BypassEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
                // The strategy is read when tgnet re-evaluates the network, so nudge it here -
                // otherwise the switch only takes effect after the next connection change.
                ColgramProxyManager.recheckConnectionNow();
                Toast.makeText(getParentActivity(), val
                        ? "Telegram будет дёргать только IPv6-адреса центров. Нужно, чтобы в сети был рабочий IPv6."
                        : "Вернулись к IPv4.",
                        Toast.LENGTH_LONG).show();
            } else if (position == relayUrlRow) {
                editRelayUrl();
            } else if (position == proxyStatusRow) {
                ColgramProxyManager.checkPoolNow(true);
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity().getApplicationContext(),
                            "Проверяю прокси через нативный чекер Telegram…", Toast.LENGTH_SHORT).show();
                }
                // A sweep is up to 8 handshakes, and an unreachable host only reports after the
                // checker's own timeout, so refreshing at 4 s used to show the old numbers.
                uiHandler.removeCallbacks(proxyCheckRefreshRunnable);
                uiHandler.postDelayed(proxyCheckRefreshRunnable, 15000L);
            } else if (position == sandboxStorageRow) {
                boolean val = !ColgramConfig.isSandboxStorageEnabled();
                ColgramConfig.setSandboxStorageEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == unlimitedMemoryRow) {
                boolean val = !ColgramConfig.isUnlimitedMemoryEnabled();
                ColgramConfig.setUnlimitedMemoryEnabled(val);
                ColgramConfig.setAutoCompressStorageEnabled(val);
                org.colgram.core.ColgramUnlimitedMemory.apply(getParentActivity(), val);
                if (val) {
                    org.colgram.core.ColgramUnlimitedMemory.autoCompressOversizedCacheImagesAsync(getParentActivity());
                }
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
                Toast.makeText(getParentActivity(),
                        val ? "Безлимит хранилища и автосжатие включены"
                                : "Восстановлен стандартный лимит кэша",
                        Toast.LENGTH_SHORT).show();
            } else if (position == sandboxFilesRow) {
                // The folder itself. Attaching a file should not require handing Telegram the
                // whole media library, so this is where files live and where they get picked up.
                presentFragment(new ColgramFilesActivity());
            } else if (position == accountVaultRow) {
                ColgramAccountSwitcherHelper.showAccountVaultSheet(this, currentAccount);
            } else if (position == liveWallpaperRow) {
                showLiveWallpaperPicker();
            } else if (position == easterEggsRow) {
                ColgramEasterEggs.showColaSlideSheet(getParentActivity(), this);
            } else if (position == alwaysBackgroundRow) {
                boolean val = !ColgramConfig.isAlwaysBackgroundEnabled();
                ColgramConfig.setAlwaysBackgroundEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
                if (val && getContext() != null) {
                    org.colgram.core.ColgramForegroundService.start(getContext());
                    org.colgram.core.ColgramBootJobService.schedule(getContext());
                }
            } else if (position == batteryAutostartRow) {
                openBatteryAndAutostartSettings();
            } else if (position == settingsBackupRow) {
                showSettingsBackupDialog();
            } else if (position == pluginsRow) {
                presentFragment(new ColgramPluginsActivity());
            } else if (position == botRealtimeRow) {
                // The cell shows "realtime", the preference stores "passive" — they are opposites.
                boolean passive = !ColgramBotSync.isPassiveBotMode(getContext(), currentAccount);
                ColgramBotSync.setPassiveBotMode(getContext(), currentAccount, passive);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(!passive);
                }
            } else if (position == botTestRow) {
                // "Connection error when I save the bot name" is not diagnosable from a toast that
                // only says that. This runs the same calls the profile screen runs - read the name
                // and description, write them straight back unchanged - and reports each one with
                // the server's own reason and how long it took.
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity().getApplicationContext(), "Проверяю Bot API…", Toast.LENGTH_SHORT).show();
                }
                ColgramBotSync.selfTestBotApi(getContext(), currentAccount, text -> {
                    if (isFinishing()) return;
                    lastBotTestReport = text;
                    if (listAdapter != null && botTestRow >= 0) listAdapter.notifyItemChanged(botTestRow);
                    android.app.Activity act = getParentActivity();
                    if (act == null || act.isFinishing() || act.isDestroyed()) return;
                    showDialog(new AlertDialog.Builder(act, resourceProvider)
                            .setTitle("Bot API")
                            .setMessage(text)
                            .setPositiveButton("Ок", null)
                            .create());
                });
            }
        });

        listView.setOnItemLongClickListener((view, position) -> {
            if (position != cloudflareWarpRow) return false;
            showWarpRelayDialog();
            return true;
        });
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        screenVisible = true;
        uiHandler.removeCallbacks(refreshVisibleNetworkState);
        uiHandler.post(refreshVisibleNetworkState);
    }

    @Override
    public void onPause() {
        screenVisible = false;
        uiHandler.removeCallbacks(refreshVisibleNetworkState);
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        screenVisible = false;
        uiHandler.removeCallbacks(refreshVisibleNetworkState);
        uiHandler.removeCallbacks(proxyCheckRefreshRunnable);
        subscription.abandonConsent();
        if (waitingWarpConsent >= 0 && warpOperationsGate.isCurrent(waitingWarpConsent)
                && warpOperationsGate.isPending()) {
            Context context = getContext();
            if (context != null) cancelWarp(context);
        }
        super.onFragmentDestroy();
    }

    private void refreshSettings() {
        if (listAdapter != null && getParentActivity() != null) listAdapter.notifyDataSetChanged();
    }

    private void showSettingsBackupDialog() {
        android.app.Activity act = getParentActivity();
        if (isFinishing() || act == null || act.isFinishing() || act.isDestroyed() || getVisibleDialog() != null) return;
        final Context appCtx = act.getApplicationContext();
        final java.io.File backupFile = new java.io.File(ColgramStorageSandbox.getRootPath(appCtx), "colgram_settings_backup.json");
        showDialog(new AlertDialog.Builder(act, resourceProvider)
                .setTitle("Бэкап аккаунтов и настроек (AES-256)")
                .setItems(new CharSequence[]{
                        "Сохранить ВСЕ аккаунты и данные в Documents/ColgramBackup",
                        "Восстановить ВСЕ аккаунты и данные из файла (.cgb)",
                        "Перенести на другой телефон (экспорт .cgb с паролем)",
                        "Экспорт настроек в файл: " + backupFile.getName(),
                        "Импорт настроек из файла: " + backupFile.getName(),
                        "Копировать JSON в буфер обмена",
                        "Восстановить JSON из буфера обмена",
                        "Зашифрованный экспорт настроек в буфер (AES-256)",
                        "Импорт зашифрованного бэкапа из буфера"
                }, (dialog, which) -> {
                    try {
                        if (which == 0) {
                            warpOperations.execute(() -> {
                                for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                                    if (org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                                        org.telegram.messenger.UserConfig.getInstance(a).saveConfig(true);
                                    }
                                }
                                org.colgram.core.ColgramPersistentBackup.BackupResult res =
                                        org.colgram.core.ColgramPersistentBackup.backupNow(appCtx, null);
                                uiHandler.post(() -> Toast.makeText(appCtx,
                                        res.success ? ("Сохранено (" + res.accountCount + " акк.) -> " + res.location)
                                                : ("Ошибка: " + res.error),
                                        Toast.LENGTH_LONG).show());
                            });
                        } else if (which == 1) {
                            ColgramAccountSwitcherHelper.launchPersistentBackupRestore(act);
                        } else if (which == 2) {
                            promptPortableVaultPassphrase(act);
                        } else if (which == 3) {
                            warpOperations.execute(() -> {
                                try {
                                    java.io.File written = ColgramSettingsBackup.exportToFile(appCtx, backupFile);
                                    uiHandler.post(() -> Toast.makeText(appCtx, "Сохранено: " + written.getAbsolutePath(), Toast.LENGTH_LONG).show());
                                } catch (Throwable error) {
                                    uiHandler.post(() -> Toast.makeText(appCtx, "Ошибка: " + error.getMessage(), Toast.LENGTH_LONG).show());
                                }
                            });
                        } else if (which == 4) {
                            warpOperations.execute(() -> {
                                try {
                                    if (!backupFile.exists()) {
                                        uiHandler.post(() -> Toast.makeText(appCtx, "Файл не найден: " + backupFile.getAbsolutePath(), Toast.LENGTH_SHORT).show());
                                        return;
                                    }
                                    int restored = ColgramSettingsBackup.importFromFile(appCtx, backupFile);
                                    uiHandler.post(() -> {
                                        if (!isFinishing()) refreshSettings();
                                        Toast.makeText(appCtx, "Восстановлено настроек: " + restored, Toast.LENGTH_SHORT).show();
                                    });
                                } catch (Throwable error) {
                                    uiHandler.post(() -> Toast.makeText(appCtx, "Ошибка: " + error.getMessage(), Toast.LENGTH_LONG).show());
                                }
                            });
                        } else if (which == 5) {
                            String json = ColgramSettingsBackup.exportToJson(appCtx);
                            AndroidUtilities.addToClipboard(json);
                            Toast.makeText(appCtx, "JSON бэкап скопирован", Toast.LENGTH_SHORT).show();
                        } else if (which == 6) {
                            ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
                            CharSequence raw = (clip != null && clip.getItemCount() > 0) ? clip.getItemAt(0).coerceToText(appCtx) : null;
                            if (raw == null || raw.length() == 0) {
                                Toast.makeText(appCtx, "Буфер обмена пуст", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            int restored = ColgramSettingsBackup.importFromJson(appCtx, raw.toString());
                            refreshSettings();
                            Toast.makeText(appCtx, "Импортировано настроек: " + restored, Toast.LENGTH_SHORT).show();
                        } else if (which == 7) {
                            promptBackupPassphrase(act, true, null);
                        } else if (which == 8) {
                            ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
                            CharSequence raw = (clip != null && clip.getItemCount() > 0) ? clip.getItemAt(0).coerceToText(appCtx) : null;
                            if (raw == null || raw.length() == 0) {
                                Toast.makeText(appCtx, "Буфер обмена пуст", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            promptBackupPassphrase(act, false, raw.toString());
                        }
                    } catch (Throwable error) {
                        Toast.makeText(appCtx, "Ошибка: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Закрыть", null)
                .create());
    }

    private void promptPortableVaultPassphrase(android.app.Activity act) {
        if (isFinishing() || act == null || act.isFinishing() || act.isDestroyed()) return;
        final EditTextBoldCursor input = new EditTextBoldCursor(act);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Придумайте пароль (AES-256)");
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider));
        android.widget.FrameLayout container = new android.widget.FrameLayout(act);
        int pad = AndroidUtilities.dp(16);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        showDialog(new AlertDialog.Builder(act, resourceProvider)
                .setTitle("Перенос всех аккаунтов (.cgb)")
                .setView(container)
                .setPositiveButton("Сохранить файл", (d, w) -> {
                    String pass = input.getText() != null ? input.getText().toString() : "";
                    if (pass.length() < 4) {
                        Toast.makeText(act.getApplicationContext(), "Минимум 4 символа", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ColgramAccountSwitcherHelper.launchPersistentBackupExport(act, pass.toCharArray());
                })
                .setNegativeButton("Отмена", null)
                .create());
    }

    private void promptBackupPassphrase(Context ctx, boolean exportMode, String clipboardPayload) {
        android.app.Activity act = getParentActivity();
        if (isFinishing() || act == null || act.isFinishing() || act.isDestroyed()) return;
        final Context appCtx = act.getApplicationContext();
        final EditTextBoldCursor input = new EditTextBoldCursor(act);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Пароль для шифрования AES-256-GCM");
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider));
        android.widget.FrameLayout container = new android.widget.FrameLayout(act);
        int pad = AndroidUtilities.dp(16);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(act, resourceProvider)
                .setTitle(exportMode ? "Зашифровать настройки (AES-256)" : "Расшифровать бэкап (AES-256)")
                .setView(container)
                .setPositiveButton("OK", (d, w) -> {
                    String pass = input.getText().toString();
                    if (pass.isEmpty()) {
                        Toast.makeText(appCtx, "Введите пароль", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final char[] chars = pass.toCharArray();
                    warpOperations.execute(() -> {
                        try {
                            if (exportMode) {
                                String enc = ColgramSettingsBackup.exportEncrypted(appCtx, chars);
                                uiHandler.post(() -> {
                                    AndroidUtilities.addToClipboard(enc);
                                    Toast.makeText(appCtx, "Зашифрованный AES-256 бэкап скопирован", Toast.LENGTH_LONG).show();
                                });
                            } else {
                                int n = ColgramSettingsBackup.importAuto(appCtx, clipboardPayload, chars);
                                uiHandler.post(() -> {
                                    if (!isFinishing()) refreshSettings();
                                    Toast.makeText(appCtx, "Расшифровано и применено: " + n, Toast.LENGTH_LONG).show();
                                });
                            }
                        } catch (Throwable error) {
                            uiHandler.post(() -> Toast.makeText(appCtx, "Ошибка: " + error.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    private void showLiveWallpaperPicker() {
        android.app.Activity act = getParentActivity();
        if (isFinishing() || act == null || act.isFinishing() || act.isDestroyed() || getVisibleDialog() != null) return;
        String[] names = {
                org.colgram.core.ColgramLiveWallpaper.styleLabel(org.colgram.core.ColgramLiveWallpaper.STYLE_OFF),
                org.colgram.core.ColgramLiveWallpaper.styleLabel(org.colgram.core.ColgramLiveWallpaper.STYLE_AURORA),
                org.colgram.core.ColgramLiveWallpaper.styleLabel(org.colgram.core.ColgramLiveWallpaper.STYLE_CYBER_GRID),
                org.colgram.core.ColgramLiveWallpaper.styleLabel(org.colgram.core.ColgramLiveWallpaper.STYLE_CONSTELLATION),
                org.colgram.core.ColgramLiveWallpaper.styleLabel(org.colgram.core.ColgramLiveWallpaper.STYLE_COLA_FIZZ)
        };
        showDialog(new AlertDialog.Builder(act, resourceProvider)
                .setTitle("Живые обои чатов")
                .setItems(names, (d, which) -> {
                    ColgramConfig.setLiveWallpaperStyle(which);
                    refreshSettings();
                    org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.didSetNewWallpapper);
                })
                .create());
    }

    private void openBatteryAndAutostartSettings() {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        org.colgram.core.ColgramForegroundService.requestBatteryExemption(ctx);
        String pkg = ctx.getPackageName();
        android.content.ComponentName[] oemAutostarts = {
                new android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                new android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                new android.content.ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                new android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                new android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                new android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
        };
        for (android.content.ComponentName cn : oemAutostarts) {
            try {
                android.content.Intent i = new android.content.Intent();
                i.setComponent(cn);
                i.putExtra("package_name", pkg);
                if (ctx.getPackageManager().resolveActivity(i, 0) != null) {
                    ctx.startActivity(i);
                    return;
                }
            } catch (Throwable ignored) {}
        }
        try {
            android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + pkg));
            ctx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    private void showPublicProxyNodes() {
        android.app.Activity act = getParentActivity();
        if (isFinishing() || act == null || act.isFinishing() || act.isDestroyed() || getVisibleDialog() != null) return;
        java.util.List<ColgramProxyManager.ProxyItem> nodes = ColgramProxyManager.getSelectableProxies();
        if (nodes.isEmpty()) {
            showDialog(new AlertDialog.Builder(act, resourceProvider).setTitle("Публичные прокси")
                    .setMessage("В каталоге пока нет узлов с подтверждённым протоколом. Адрес сервера не подтверждает страну.")
                    .setPositiveButton("Проверить каталог", (d, w) -> {
                        Context context = getContext();
                        if (context != null) { cancelWarp(context); stopSubscriptionTunnel(context); }
                        ColgramProxyManager.selectPublicProxy(getContext());
                        refreshSettings();
                    }).setNegativeButton("Отмена", null).create());
            return;
        }
        String[] labels = new String[nodes.size()];
        for (int i = 0; i < labels.length; i++) {
            ColgramProxyManager.ProxyItem node = nodes.get(i);
            String protocol = node.type == 0 ? "SOCKS5" : node.type == 1 ? "MTProto" : "WebSocket";
            labels[i] = protocol + " · " + node.address + ":" + node.port + " · " + node.pingMs + " мс";
        }
        showDialog(new AlertDialog.Builder(act, resourceProvider).setTitle("Выбрать публичный узел")
                .setItems(labels, (d, which) -> {
                    Context context = getContext();
                    if (context == null) return;
                    cancelWarp(context);
                    stopSubscriptionTunnel(context);
                    ColgramProxyManager.ProxyItem node = nodes.get(which);
                    ColgramProxyManager.selectUserProxy(context, node.address, node.port, node.secret,
                            node.type, node.user, node.password);
                    refreshSettings();
                }).setNegativeButton("Отмена", null).create());
    }

    private void selectDirectConnection() {
        final Context context = getContext();
        if (context == null) return;
        cancelWarp(context);
        ColgramProxyManager.cancelProxySelection();
        ColgramConfig.setAutoProxyEnabled(false);
        ColgramConfig.setDcRemapEnabled(false);
        ColgramConfig.setIpv6BypassEnabled(false);
        final long token = ColgramRouteEpoch.snapshot();
        stopSubscriptionTunnel(context);
        warpOperations.execute(() -> {
            if (!ColgramRouteEpoch.isCurrent(token)) return;
            ColgramProxyManager.ensureLocalBaseRoute(context);
            ColgramProxyManager.applyDcRemap(false);
            ColgramProxyManager.disableProxy(context);
            ColgramProxyManager.recheckConnectionNow();
            uiHandler.post(this::refreshSettings);
        });
        refreshSettings();
    }

    private void showModelSelector() {
        if (getParentActivity() == null) return;
        final String[] labels = {
            "Google Pixel 8 Pro (Android 14)",
            "Google Pixel 9 Pro (Android 15)",
            "Samsung Galaxy S24 Ultra (Android 14)",
            "Xiaomi 14 Ultra (Android 14)",
            "Nothing Phone (2) (Android 14)",
            "OnePlus 12 (Android 14)",
            "iPhone 15 Pro Max (iOS 17.6.1)",
            "Случайный профиль",
            "Своя модель вручную..."
        };
        final String[] models = {
            "Google Pixel 8 Pro",
            "Google Pixel 9 Pro",
            "Samsung Galaxy S24 Ultra",
            "Xiaomi 14 Ultra",
            "Nothing Phone (2)",
            "OnePlus 12",
            "iPhone 15 Pro Max"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle("Выберите профиль устройства");
        builder.setItems(labels, (dialog, which) -> {
            String selectedModel;
            String selectedSys;
            if (which == 7) {
                ColgramCloak.DeviceProfile rnd = ColgramCloak.getRandomProfile();
                selectedModel = rnd.model;
                selectedSys = rnd.systemVersion;
            } else if (which == 8) {
                showCustomModelInput();
                return;
            } else {
                selectedModel = models[which];
                selectedSys = ColgramCloak.getSystemVersionForModel(selectedModel, null);
            }
            ColgramConfig.setSpoofDeviceModel(selectedModel);
            ColgramConfig.setSpoofSystemVersion(selectedSys);
            if (!ColgramConfig.isCloakEnabled()) {
                ColgramConfig.setCloakEnabled(true);
                if (listAdapter != null && cloakEnabledRow >= 0) {
                    listAdapter.notifyItemChanged(cloakEnabledRow);
                }
            }
            if (listAdapter != null && cloakModelRow >= 0) {
                listAdapter.notifyItemChanged(cloakModelRow);
            }
            promptRestartForCloakChange("Профиль устройства установлен: " + selectedModel + " (" + selectedSys
                    + ").\n\nПерезапустите Colgram сейчас, чтобы Telegram-серверы немедленно обновили модель и ОС сессии.");
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showCustomModelInput() {
        Activity act = getParentActivity();
        if (act == null) return;
        final EditTextBoldCursor input = new EditTextBoldCursor(act);
        input.setHint("Например: Google Pixel 9 Pro");
        input.setText(ColgramCloak.getEffectiveDeviceModel());
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider));
        input.setSingleLine(true);
        input.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8),
                AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        showDialog(new AlertDialog.Builder(act, resourceProvider)
                .setTitle("Своя модель устройства")
                .setView(input)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String model = input.getText() == null ? "" : input.getText().toString().trim();
                    if (model.isEmpty()) return;
                    ColgramConfig.setSpoofDeviceModel(model);
                    if (!ColgramConfig.isCloakEnabled()) {
                        ColgramConfig.setCloakEnabled(true);
                    }
                    if (listAdapter != null) {
                        if (cloakEnabledRow >= 0) listAdapter.notifyItemChanged(cloakEnabledRow);
                        if (cloakModelRow >= 0) listAdapter.notifyItemChanged(cloakModelRow);
                    }
                    promptRestartForCloakChange("Модель «" + model + "» сохранена. Перезапустите Colgram для обновления MTProto-сессии.");
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void promptRestartForCloakChange(String message) {
        Activity act = getParentActivity();
        if (act == null) return;
        try {
            showDialog(new AlertDialog.Builder(act, resourceProvider)
                    .setTitle("Маскировка устройства")
                    .setMessage(message)
                    .setPositiveButton("Перезапустить сейчас", (d, w) -> restartApplicationCleanly(act))
                    .setNegativeButton("Позже", null)
                    .create());
        } catch (Throwable ignored) {
            Toast.makeText(act, message, Toast.LENGTH_LONG).show();
        }
    }

    private static void restartApplicationCleanly(Activity activity) {
        if (activity == null) return;
        try {
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
            if (launch != null) {
                Intent restart = Intent.makeRestartActivityTask(launch.getComponent());
                activity.startActivity(restart);
            }
        } catch (Throwable ignored) {}
        try {
            activity.finishAffinity();
        } catch (Throwable ignored) {}
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /**
     * A self-hosted forwarder for the traffic tgnet cannot carry: the Bot API, the mail
     * providers and the proxy-list fetches. On a network that drops those addresses by IP and
     * where every public SOCKS list is dead, one Worker on a domain the block will not touch is
     * the difference between "сеть недоступна" and a working feature. It is not a Telegram
     * proxy and the UI says so; the worker source ships in the repo under deploy/.
     */
    /** One line for the row: the first step of the last run, or the invitation to run it. */
    private String botTestSummary() {
        if (lastBotTestReport == null || lastBotTestReport.isEmpty()) return "запустить";
        int nl = lastBotTestReport.indexOf('\n');
        return nl < 0 ? lastBotTestReport : lastBotTestReport.substring(0, nl);
    }

    private void editRelayUrl() {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        final EditTextBoldCursor input = new EditTextBoldCursor(ctx);
        input.setHint("https://имя.workers.dev");
        input.setText(ColgramConfig.getRelayUrl());
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider));
        input.setSingleLine(true);
        input.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8),
                AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(ctx, resourceProvider)
                .setTitle("Реле-адрес")
                .setMessage("Через него идут Bot API, временные почты и списки прокси, когда их "
                        + "адреса блокируют по IP. Трафик самого Telegram он не заменяет — "
                        + "для него нужен прокси или обходчик.")
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    ColgramConfig.setRelayUrl(input.getText().toString().trim());
                    if (listAdapter != null && relayUrlRow >= 0) listAdapter.notifyItemChanged(relayUrlRow);
                })
                .setNeutralButton("Сбросить", (dialog, which) -> {
                    ColgramConfig.setRelayUrl("");
                    if (listAdapter != null && relayUrlRow >= 0) listAdapter.notifyItemChanged(relayUrlRow);
                })
                .setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    /**
     * Built-in WARP. The old row just launched the official 1.1.1.1 app — the very app whose
     * pinned :2408 endpoint TSPU drops, which made the row a dead end on the networks this
     * feature exists for. Colgram now registers its own device (one HTTPS POST, keys never
     * leave the phone) and runs the tunnel through the embedded WireGuard backend, rotating
     * the UDP port when the edge stays silent.
     */
    private void openCloudflareWarp() {
        final Context context = getContext();
        if (context == null || getParentActivity() == null) return;
        if (ColgramConfig.isWarpEnabled() || warpOperationsGate.isPending()
                || org.colgram.core.ColgramWarpTunnel.isUp()) {
            cancelWarp(context);
            refreshSettings();
            return;
        }
        if (!org.colgram.core.ColgramWarpTunnel.isBackendAvailable()) {
            Toast.makeText(context, "WARP-бэкенд отсутствует в этой сборке", Toast.LENGTH_LONG).show();
            return;
        }
        final long token = warpOperationsGate.begin();
        warpRouteEpoch = ColgramRouteEpoch.next();
        ColgramProxyManager.cancelProxySelection();
        stopSubscriptionTunnel(context);
        waitingWarpConsent = token;
        // This is the desired route, not proof of a connection. It also suppresses late proxy results.
        ColgramConfig.setWarpEnabled(true);
        org.colgram.core.ColgramWarpTunnel.clearFailure();
        refreshSettings();
        warpOperations.execute(() -> {
            if (!warpRequested(token)) return;
            try {
                if (!org.colgram.core.ColgramMasqueNative.isAvailableForProbe()
                        && !org.colgram.core.ColgramWarp.isRegistered()) {
                    org.colgram.core.ColgramWarp.register(context.getApplicationContext());
                }
                uiHandler.post(() -> {
                    if (warpRequested(token)) prepareAndStartWarp();
                });
            } catch (Throwable failure) {
                completeWarpAttempt(token, context, failure.getMessage());
            }
        });
    }

    private boolean warpRequested(long token) {
        return warpOperationsGate.isCurrent(token) && ColgramRouteEpoch.isCurrent(warpRouteEpoch) && ColgramConfig.isWarpEnabled();
    }

    private void cancelWarp(Context context) {
        ColgramRouteEpoch.next();
        warpOperationsGate.cancel();
        waitingWarpConsent = -1L;
        ColgramConfig.setWarpEnabled(false);
        org.colgram.core.ColgramWarpTunnel.cancelPendingStart();
        final Context application = context.getApplicationContext();
        warpOperations.execute(() -> {
            if (!ColgramConfig.isWarpEnabled()) org.colgram.core.ColgramWarpTunnel.bringDown(application);
            uiHandler.post(this::refreshSettings);
        });
    }

    private void completeWarpAttempt(long token, Context context, String error) {
        uiHandler.post(() -> {
            if (!warpOperationsGate.isCurrent(token) || !ColgramRouteEpoch.isCurrent(warpRouteEpoch)) return;
            warpOperationsGate.finish(token);
            if (error != null || !org.colgram.core.ColgramWarpTunnel.isUp()) {
                ColgramConfig.setWarpEnabled(false);
                if (getParentActivity() != null) {
                    Toast.makeText(context, "WARP: " + (error == null ? "туннель не запущен" : error),
                            Toast.LENGTH_LONG).show();
                }
            }
            refreshSettings();
        });
    }

    private void stopSubscriptionTunnel(Context context) {
        subscription.stopForAlternativeRoute(context);
    }

    /** Android requires the one-time VPN consent dialog before a tunnel can come up. */
    private void prepareAndStartWarp() {
        final long token = waitingWarpConsent;
        if (!warpRequested(token)) return;
        if (getParentActivity() == null) {
            Context context = getContext();
            if (context != null) cancelWarp(context);
            return;
        }
        try {
            Intent prepare = android.net.VpnService.prepare(getParentActivity());
            if (prepare != null) startActivityForResult(prepare, REQ_WARP_VPN);
            else startWarpTunnel();
        } catch (Throwable failure) {
            Context context = getContext();
            if (context != null) completeWarpAttempt(token, context, failure.toString());
        }
    }

    private void startWarpTunnel() {
        final Context context = getContext();
        final long token = waitingWarpConsent;
        final long routeEpoch = warpRouteEpoch;
        waitingWarpConsent = -1L;
        if (context == null || !warpRequested(token)) return;
        warpOperations.execute(() -> {
            if (!warpRequested(token)) return;
            String error = null;
            try {
                ColgramConfig.setAutoProxyEnabled(false);
                ColgramSubscriptionControls.stopStoredProfile(context);
                ColgramConfig.setDcRemapEnabled(false);
                ColgramConfig.setIpv6BypassEnabled(false);
                ColgramProxyManager.ensureLocalBaseRoute(context);
                ColgramProxyManager.applyDcRemap(false);
                ColgramProxyManager.disableProxy(context);
                ColgramProxyManager.notifyProxySettingsChanged();
                if (!warpRequested(token) || !ColgramRouteEpoch.isCurrent(routeEpoch)) return;
                org.colgram.core.ColgramWarpTunnel.bringUp(context.getApplicationContext(), routeEpoch);
            } catch (Throwable failure) {
                error = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            }
            if (!warpRequested(token)) return;
            completeWarpAttempt(token, context, error);
        });
    }

    private String warpStatusLine() {
        if (!org.colgram.core.ColgramWarpTunnel.isBackendAvailable()) return "бэкенд недоступен";
        if (warpOperationsGate.isPending()) {
            String stage = org.colgram.core.ColgramWarpMasqueTunnel.startupStage();
            if (stage == null) stage = waitingWarpConsent >= 0 ? "разрешение VPN" : "запуск";
            return stage + " · нажмите для отмены";
        }
        String failure = org.colgram.core.ColgramWarpTunnel.lastFailureReason();
        if (failure != null && !failure.isEmpty()
                && !org.colgram.core.ColgramWarpTunnel.isUp()) {
            return "не работает: " + failure;
        }
        if (org.colgram.core.ColgramWarpTunnel.isUp()) {
            return (org.colgram.core.ColgramWarpTunnel.isConnected()
                    ? ColgramProxyManager.isTelegramConnected() ? "WARP подключён · Telegram доступен · "
                            : "WARP подключён · ожидаем связь с Telegram · " : "подключается · ")
                    + org.colgram.core.ColgramWarpTunnel.transportDescription();
        }
        if (org.colgram.core.ColgramWarp.isRegistered()) return "выключен · нажмите, чтобы включить";
        return "выключен · нажмите, чтобы подключить";
    }

    private String dpiStatusLine() {
        if (!ColgramDpiBypass.isBound()) return "локальный транспорт ещё не запущен";
        ColgramProxyManager.ProxyItem route = ColgramProxyManager.getCurrentActiveProxy();
        if (route == null || !route.isLocalDpi()) return "поверх локального слоя выбран другой транспорт";
        if (ColgramDpiBypass.bytesFromTarget() > 0 && ColgramProxyManager.isTelegramConnected()) return "подключён к Telegram";
        return ColgramDpiBypass.upstreamStatus();
    }

    private String subscriptionStatusLine() {
        return subscription.statusLine();
    }

    private String subscriptionTunnelStatus(Context context) {
        return context == null ? "stopped" : subscription.rawStatus();
    }

    private boolean isAndroidVpnActive() {
        Context context = getContext();
        if (context == null) return false;
        android.net.ConnectivityManager manager = context.getSystemService(android.net.ConnectivityManager.class);
        android.net.Network active = manager == null ? null : manager.getActiveNetwork();
        android.net.NetworkCapabilities caps = active == null ? null : manager.getNetworkCapabilities(active);
        return caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
    }

    /**
     * Enter or clear the relay that carries WARP where Cloudflare's UDP is dropped.
     *
     * Four fields, because that is what a WireGuard peer actually needs: the relay terminates
     * the handshake, so it has its own public key and it needs the peer's preshared key. The
     * row reports it afterwards, so a relay that is entered but not working is visible as a relay
     * rather than as WARP quietly failing.
     */
    private void showWarpRelayDialog() {
        final Context context = getContext();
        if (context == null) return;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12),
                AndroidUtilities.dp(20), AndroidUtilities.dp(8));

        android.widget.EditText address = relayField(layout, "Адрес релея",
                org.colgram.core.ColgramWarp.relayAddress());
        android.widget.EditText port = relayField(layout, "Порт",
                org.colgram.core.ColgramWarp.relayPort() > 0
                        ? String.valueOf(org.colgram.core.ColgramWarp.relayPort()) : "");
        android.widget.EditText publicKey = relayField(layout, "Публичный ключ релея",
                org.colgram.core.ColgramWarp.relayPublicKey());
        android.widget.EditText preshared = relayField(layout, "Preshared key (если есть)",
                org.colgram.core.ColgramWarp.relayPresharedKey());

        org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle("Релей для WARP");
        builder.setMessage("Нужен, если UDP Cloudflare заблокирован: релея держите сами, "
                + "он принимает WireGuard по UDP и проксирует в Cloudflare. "
                + "Запускайте его с ключом --udp-listen: порт отсюда должен быть UDP, "
                + "иначе туннель не поднимется.");
        builder.setView(ColgramMenuChrome.wrapDialogScroll(context, layout));
        builder.setPositiveButton("Сохранить", (d, w) -> {
            int portNumber = 0;
            try {
                portNumber = Integer.parseInt(port.getText().toString().trim());
            } catch (Exception ignored) {
                // A port that is not a number is the same as no port, and setRelay treats it as
                // "clear" rather than writing something the tunnel cannot use.
            }
            org.colgram.core.ColgramWarp.setRelay(address.getText().toString(), portNumber,
                    publicKey.getText().toString(), preshared.getText().toString());
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
            Toast.makeText(context, org.colgram.core.ColgramWarp.hasRelay()
                    ? "Релей сохранён" : "Релей очищен", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private android.widget.EditText relayField(LinearLayout layout, String hint, String value) {
        EditTextBoldCursor input = new EditTextBoldCursor(getContext());
        input.setHint(hint);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider));
        input.setTextSize(13);
        input.setSingleLine(true);
        if (value != null) input.setText(value);
        layout.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == cloakEnabledRow || position == cloakModelRow ||
                   position == antiDeleteRow || position == antiDeleteSelfRow || position == antiDeleteHighlightRow || position == antiDeleteWipeRow || position == preserveMediaRow || position == editHistoryRow || position == autoHidePhoneRow || position == saveOneTimeMediaRow || position == hideScreenshotNotifyRow || position == forwardNoQuoteRow || position == showMessageSecondsRow || position == showMessageIdRow ||
                   position == quickBarRow || position == maxAnonymityRow || position == ghostReadRow || position == ghostTypingRow || position == ghostOnlineRow || position == stealthStoriesRow || position == hidePhoneLocalRow || position == bypassFlagSecureRow || position == confirmCallsRow || position == confirmStickersRow || position == confirmLinksRow ||
                   position == dohRow || position == builtinProxyRow || position == proxyBrowserRow || position == currentProxyRow || position == ipv6BypassRow || position == dcRemapRow ||
                   position == ownProxyRow || position == proxyStatusRow || position == relayUrlRow || position == autoProxyRow ||
                   position == sandboxStorageRow || position == unlimitedMemoryRow || position == sandboxFilesRow || position == accountVaultRow || position == liveWallpaperRow || position == easterEggsRow || position == alwaysBackgroundRow || position == batteryAutostartRow || position == settingsBackupRow ||
                   position == defenderRow || position == botRealtimeRow || position == botTestRow || position == pluginsRow ||
                   position == antiSpamRow || position == autoReplyRow || position == tempMailRow || position == versionsRow ||
                   position == cloudflareWarpRow || position == subscriptionRow || position == directConnectionRow
                   || position == publicProxyRow || position == subscriptionNodeRow || position == callsRow || position == browserRow
                   || position == dataSettingsRow || position == cacheSettingsRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == heroBannerRow) return 5;
            if (position == dpiBypassRow) return 4; // Non-interactive TextInfoPrivacyCell, never a switch.
            if (position == cloakingHeaderRow || position == vaultHeaderRow || position == ghostHeaderRow ||
                position == networkHeaderRow || position == resourceHeaderRow || position == sandboxHeaderRow ||
                position == botsHeaderRow) {
                return 0; // HeaderCell
            } else if (position == cloakModelRow || position == currentProxyRow
                    || position == ownProxyRow || position == proxyStatusRow || position == relayUrlRow
                    || position == sandboxFilesRow || position == accountVaultRow || position == liveWallpaperRow || position == easterEggsRow || position == batteryAutostartRow || position == settingsBackupRow || position == defenderRow || position == pluginsRow || position == botTestRow
                    || position == antiSpamRow || position == autoReplyRow || position == tempMailRow || position == versionsRow
                    || position == cloudflareWarpRow || position == subscriptionRow || position == directConnectionRow
                    || position == publicProxyRow || position == subscriptionNodeRow || position == callsRow || position == browserRow
                    || position == dataSettingsRow || position == cacheSettingsRow) {
                return 2; // TextSettingsCell
            } else if (position == cloakingSectionRow || position == vaultSectionRow || position == ghostSectionRow ||
                       position == networkSectionRow || position == resourceSectionRow || position == sandboxSectionRow ||
                       position == botsSectionRow) {
                return 3; // ShadowSectionCell
            }
            return 1; // TextCheckCell
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 2:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 3:
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
                case 4:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case 5:
                    view = new ColgramMenuChrome.HeroBannerCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 5: {
                    ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                    org.colgram.core.ColgramSecurityDefender.AuditReport dr =
                            org.colgram.core.ColgramSecurityDefender.getLatestReport();
                    boolean maxAnon = ColgramConfig.isMaxAnonymityEnabled();
                    boolean bypassUp = ColgramDpiBypass.isBound();
                    int accent = maxAnon ? ColgramMenuChrome.COLOR_EMERALD : ColgramMenuChrome.COLOR_CRIMSON;
                    String pill = dr != null ? (dr.scorePercent + "%") : (bypassUp ? "Обход активен" : "");
                    String sub = "Анти-ТСПУ: " + (org.colgram.core.ColgramTelegramWsBridge.hasWorkedOnCurrentNetwork()
                            ? "WSS активен" : (bypassUp ? "локальный слой" : "ожидание"))
                            + " · Сейф: " + (ColgramConfig.isAntiDeleteEnabled() ? "ВКЛ" : "ВЫКЛ");
                    hero.bind(R.drawable.outline_shield_check, accent,
                            "Настройки Colgram", sub, pill,
                            maxAnon ? "Макс. приватность" : "Приватность",
                            ColgramConfig.isDohEnabled() ? "DoH активен" : "Обычный DNS",
                            ColgramConfig.isAlwaysBackgroundEnabled() ? "Фон 24/7" : "Экономный фон");
                    break;
                }
                case 0: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == cloakingHeaderRow) {
                        headerCell.setText("Маскировка устройства (MTProto Cloaking)");
                        ColgramMenuChrome.styleHeader(headerCell, 0xFF6D28D9, 0xFFA78BFA);
                    } else if (position == vaultHeaderRow) {
                        headerCell.setText("Сейф сообщений (Anti-Delete)");
                        ColgramMenuChrome.styleHeader(headerCell, 0xFFB91C3C, 0xFFFF4D6D);
                    } else if (position == ghostHeaderRow) {
                        headerCell.setText("Режим призрака (Ghost Mode)");
                        ColgramMenuChrome.styleHeader(headerCell, 0xFF4338CA, 0xFF818CF8);
                    } else if (position == networkHeaderRow) {
                        headerCell.setText("Сеть и анонимность (Анти-ТСПУ)");
                        ColgramMenuChrome.styleHeader(headerCell, 0xFF047857, 0xFF34D399);
                    } else if (position == resourceHeaderRow) {
                        headerCell.setText("Трафик и память");
                        ColgramMenuChrome.styleHeader(headerCell, 0xFF1D4ED8, 0xFF60A5FA);
                    } else if (position == sandboxHeaderRow) {
                        headerCell.setText("Песочница файлов (Sandbox)");
                        ColgramMenuChrome.styleHeader(headerCell, 0xFFB45309, 0xFFFBBF24);
                    } else if (position == botsHeaderRow) {
                        headerCell.setText("Сервисы, боты и модули Colgram");
                        ColgramMenuChrome.styleHeader(headerCell, 0xFF0F766E, 0xFF2DD4BF);
                    }
                    break;
                }
                case 1: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == cloakEnabledRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_VIOLET, R.drawable.settings_devices);
                        checkCell.setTextAndCheck("Маскировка модели и системы", ColgramConfig.isCloakEnabled(), true);
                    } else if (position == antiDeleteRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_CRIMSON, R.drawable.msg_delete);
                        checkCell.setTextAndCheck("Анти-удаление сообщений", ColgramConfig.isAntiDeleteEnabled(), true);
                    } else if (position == preserveMediaRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_AMBER, R.drawable.msg_media);
                        checkCell.setTextAndCheck("Защита медиафайлов от удаления", ColgramConfig.isPreserveMediaEnabled(), true);
                    } else if (position == editHistoryRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_INDIGO, R.drawable.msg_edit);
                        checkCell.setTextAndCheck("Сохранять историю редакций текста", ColgramConfig.isEditHistoryEnabled(), true);
                    } else if (position == antiDeleteHighlightRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_ROSE, R.drawable.msg_info);
                        checkCell.setTextAndCheck("Показывать метку «Удалено»", ColgramConfig.isAntiDeleteHighlightEnabled(), true);
                    } else if (position == antiDeleteSelfRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_BLUE, R.drawable.msg_archive);
                        checkCell.setTextAndCheck("Сохранять свои удалённые сообщения", ColgramConfig.isAntiDeleteSelfEnabled(), true);
                    } else if (position == antiDeleteWipeRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_SLATE, R.drawable.msg_clear);
                        checkCell.setTextAndCheck("Стирать текст удалённых (иначе — архив)", ColgramConfig.isAntiDeleteWipeEnabled(), true);
                    } else if (position == autoHidePhoneRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_TEAL, R.drawable.settings_privacy);
                        checkCell.setTextAndCheck("Скрывать номер телефона при входе", ColgramConfig.isAutoHidePhoneEnabled(), true);
                    } else if (position == saveOneTimeMediaRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_EMERALD, R.drawable.msg_view_file);
                        checkCell.setTextAndCheck("Сохранять одноразовые (без ограничений)", ColgramConfig.isSaveOneTimeMediaEnabled(), true);
                    } else if (position == hideScreenshotNotifyRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_VIOLET, R.drawable.msg_photos);
                        checkCell.setTextAndCheck("Скрывать уведомление о скриншоте", ColgramConfig.isHideScreenshotNotifyEnabled(), true);
                    } else if (position == forwardNoQuoteRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_CYAN, R.drawable.msg_forward);
                        checkCell.setTextAndCheck("Пересылка без автора (NoQuote)", ColgramConfig.isForwardNoQuoteEnabled(), true);
                    } else if (position == showMessageSecondsRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_BLUE, R.drawable.msg_online);
                        checkCell.setTextAndCheck("Секунды во времени сообщений (HH:mm:ss)", ColgramConfig.isShowMessageSecondsEnabled(), true);
                    } else if (position == showMessageIdRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_INDIGO, R.drawable.msg_copy);
                        checkCell.setTextAndCheck("Кнопка «Копировать ID» в меню сообщений", ColgramConfig.isShowMessageIdEnabled(), false);
                    } else if (position == quickBarRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_ROSE, R.drawable.msg_list);
                        checkCell.setTextAndCheck("Панель быстрого доступа (чаты)", ColgramConfig.isQuickBarEnabled(), true);
                    } else if (position == maxAnonymityRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_CRIMSON, R.drawable.outline_shield_check);
                        checkCell.setTextAndCheck("Максимальная анонимность (включить всё)", ColgramConfig.isMaxAnonymityEnabled(), true);
                    } else if (position == ghostReadRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_VIOLET, R.drawable.msg_secret);
                        checkCell.setTextAndCheck("Не отправлять отчет о прочтении", ColgramConfig.isGhostReadEnabled(), true);
                    } else if (position == ghostTypingRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_INDIGO, R.drawable.msg_edit);
                        checkCell.setTextAndCheck("Скрывать «печатает...» и запись аудио", ColgramConfig.isGhostTypingEnabled(), true);
                    } else if (position == ghostOnlineRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_TEAL, R.drawable.msg_online);
                        checkCell.setTextAndCheck("Скрывать онлайн статус", ColgramConfig.isGhostOnlineEnabled(), true);
                    } else if (position == stealthStoriesRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_AMBER, R.drawable.msg_viewchats);
                        checkCell.setTextAndCheck("Анонимный просмотр историй", ColgramConfig.isStealthStoriesEnabled(), true);
                    } else if (position == hidePhoneLocalRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_CYAN, R.drawable.settings_calls);
                        checkCell.setTextAndCheck("Скрывать свой номер в интерфейсе", ColgramConfig.isHidePhoneLocalEnabled(), true);
                    } else if (position == bypassFlagSecureRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_EMERALD, R.drawable.msg_photos);
                        checkCell.setTextAndCheck("Разрешить скриншоты везде (FLAG_SECURE)", ColgramConfig.isBypassFlagSecureEnabled(), true);
                    } else if (position == confirmCallsRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_BLUE, R.drawable.msg_calls);
                        checkCell.setTextAndCheck("Подтверждение исходящих звонков", ColgramConfig.isConfirmCallsEnabled(), true);
                    } else if (position == confirmStickersRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_AMBER, R.drawable.msg_photo_sticker);
                        checkCell.setTextAndCheck("Подтверждение отправки стикеров", ColgramConfig.isConfirmStickersEnabled(), true);
                    } else if (position == confirmLinksRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_ROSE, R.drawable.msg_link);
                        checkCell.setTextAndCheck("Подтверждение открытия внешних ссылок", ColgramConfig.isConfirmLinksEnabled(), false);
                    } else if (position == autoProxyRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_CYAN, R.drawable.msg_speed);
                        checkCell.setTextAndCheck("Самоподключение прокси, если Telegram не отвечает",
                                ColgramConfig.isAutoProxyEnabled(), true);
                    } else if (position == dohRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_EMERALD, R.drawable.msg_secret);
                        checkCell.setTextAndCheck("DNS-over-HTTPS (шифрованный DNS)", ColgramConfig.isDohEnabled(), true);
                    } else if (position == builtinProxyRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_BLUE, R.drawable.msg_download);
                        checkCell.setTextAndCheck("Обновлять каталог MTProto / SOCKS5", ColgramConfig.isBuiltinProxyEnabled(), true);
                    } else if (position == proxyBrowserRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_TEAL, R.drawable.msg_link2);
                        checkCell.setTextAndCheck("Открывать ссылки в защищенном браузере", ColgramConfig.isProxyBrowserEnabled(), true);
                    } else if (position == sandboxStorageRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_AMBER, R.drawable.msg_folders);
                        checkCell.setTextAndCheck("Изолировать файлы в папке Colgram", ColgramConfig.isSandboxStorageEnabled(), true);
                    } else if (position == unlimitedMemoryRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_VIOLET, R.drawable.msg_media_gallery);
                        checkCell.setTextAndValueAndCheck(
                                "Безлимит хранилища + автосжатие",
                                org.colgram.core.ColgramUnlimitedMemory.buildCompressionStatsLine(mContext),
                                ColgramConfig.isUnlimitedMemoryEnabled(),
                                true,
                                true);
                    } else if (position == alwaysBackgroundRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_EMERALD, R.drawable.settings_power);
                        checkCell.setTextAndCheck("Постоянная работа в фоне и автозапуск", ColgramConfig.isAlwaysBackgroundEnabled(), true);
                    } else if (position == dcRemapRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_INDIGO, R.drawable.msg_speed_fast);
                        checkCell.setTextAndCheck(
                                "Обход без прокси: звонить на доступный адрес Telegram",
                                ColgramConfig.isDcRemapEnabled(), false);
                    } else if (position == ipv6BypassRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_SLATE, R.drawable.msg_link);
                        checkCell.setTextAndCheck("Обход по IPv6 без прокси",
                                ColgramConfig.isIpv6BypassEnabled(), true);
                    } else if (position == botRealtimeRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_CYAN, R.drawable.msg_bots);
                        checkCell.setTextAndCheck("Получать сообщения бота в реальном времени",
                                !ColgramBotSync.isPassiveBotMode(getContext(), currentAccount), true);
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                    if (position == cloakModelRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.settings_devices, ColgramMenuChrome.COLOR_VIOLET);
                        settingsCell.setTextAndValue("Профиль устройства",
                                ColgramCloak.getEffectiveDeviceModel() + " · " + ColgramCloak.getEffectiveSystemVersion(), false);
                    } else if (position == currentProxyRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_speed, ColgramMenuChrome.COLOR_CYAN);
                        ColgramProxyManager.ProxyItem active = ColgramProxyManager.getCurrentActiveProxy();
                        // Automatic failover deliberately keeps Telegram's persisted proxy
                        // preference off. The live route still needs to be shown as active.
                        boolean proxyOn = ColgramProxyManager.isProxyEnabled(getContext())
                                || active != null;
                        String proxyStr = !proxyOn
                                ? "прокси выключен"
                                : (active != null ? active.toString() : "включено, узел не определён");
                        settingsCell.setTextAndValue("Выбрать публичный прокси", proxyStr, true);
                    } else if (position == ownProxyRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.outline_shield_check, ColgramMenuChrome.COLOR_BLUE);
                        settingsCell.setTextAndValue("Свой прокси: MTProto / WebSocket / SOCKS5",
                                (ColgramProxyManager.isProxyEnabled(getContext())
                                        || ColgramProxyManager.getCurrentActiveProxy() != null)
                                        ? "открыть экран Telegram" : "добавить и включить", true);
                    } else if (position == sandboxFilesRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.settings_folders, ColgramMenuChrome.COLOR_AMBER);
                        // Show the path that is actually in effect: scoped storage decides it, so
                        // "Documents/Colgram" is only true when the system let us write there.
                        settingsCell.setTextAndValue("Папка Colgram",
                                ColgramStorageSandbox.getRootPath(getContext()), true);
                    } else if (position == accountVaultRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.settings_account, ColgramMenuChrome.COLOR_INDIGO);
                        settingsCell.setTextAndValue("Сейф аккаунтов (без лимита)",
                                "управление и пины", true);
                    } else if (position == liveWallpaperRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_theme, ColgramMenuChrome.COLOR_ROSE);
                        settingsCell.setTextAndValue("Живые обои и волны чатов",
                                org.colgram.core.ColgramLiveWallpaper.styleLabel(ColgramConfig.getLiveWallpaperStyle()), true);
                    } else if (position == easterEggsRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_reactions, ColgramMenuChrome.COLOR_CRIMSON);
                        settingsCell.setTextAndValue("Кола-горка Koliti и пасхалки",
                                "открыть", true);
                    } else if (position == batteryAutostartRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.settings_power, ColgramMenuChrome.COLOR_EMERALD);
                        boolean exempt = org.colgram.core.ColgramPowerGuard.isBatteryExemptionGranted(getContext());
                        settingsCell.setTextAndValue("Эко-фон 24/7 и автозапуск",
                                exempt ? "без ограничений ✓" : "настроить без ограничений", true);
                    } else if (position == settingsBackupRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_pin_code, ColgramMenuChrome.COLOR_TEAL);
                        settingsCell.setTextAndValue("Бэкап аккаунтов и настроек (AES-256)",
                                "экспорт / импорт", false);
                    } else if (position == defenderRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.outline_shield_check, ColgramMenuChrome.COLOR_EMERALD);
                        org.colgram.core.ColgramSecurityDefender.AuditReport dr =
                                org.colgram.core.ColgramSecurityDefender.getLatestReport();
                        String dVal = dr != null
                                ? dr.scorePercent + "% · закрыто " + dr.closedCount + "/" + dr.findings.size()
                                : (ColgramConfig.isDefenderEnabled() ? "авто-защита активна" : "выключен");
                        settingsCell.setTextAndValue("Бот-защитник Telegram (дыры и утечки)", dVal, true);
                    } else if (position == pluginsRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_customize, ColgramMenuChrome.COLOR_VIOLET);
                        settingsCell.setTextAndValue("Плагины: маркетплейс, exteraGram, Python",
                                "открыть", true);
                    } else if (position == antiSpamRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_block, ColgramMenuChrome.COLOR_CRIMSON);
                        settingsCell.setTextAndValue("Антиспам и Colemailer",
                                "открыть", true);
                    } else if (position == autoReplyRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_reply_small, ColgramMenuChrome.COLOR_BLUE);
                        settingsCell.setTextAndValue("Автоответчик и ключевые слова",
                                "открыть", true);
                    } else if (position == tempMailRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_send, ColgramMenuChrome.COLOR_CYAN);
                        settingsCell.setTextAndValue("Временная почта (Temp Mail)",
                                "открыть", true);
                    } else if (position == versionsRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_download, ColgramMenuChrome.COLOR_EMERALD);
                        settingsCell.setTextAndValue("Версии и обновления Colgram",
                                "открыть", false);
                    } else if (position == relayUrlRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_link, ColgramMenuChrome.COLOR_TEAL);
                        String relay = ColgramConfig.getRelayUrl();
                        settingsCell.setTextAndValue("Реле-адрес для Bot API и почты",
                                relay.isEmpty() ? "не задан" : relay, true);
                    } else if (position == botTestRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_bots, ColgramMenuChrome.COLOR_INDIGO);
                        settingsCell.setTextAndValue("Проверка Bot API (имя и описание)",
                                botTestSummary(), true);
                    } else if (position == proxyStatusRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_info, ColgramMenuChrome.COLOR_AMBER);
                        settingsCell.setTextAndValue("Состояние прокси (нажмите для проверки)",
                                ColgramProxyDoctor.getStatusSummary(), true);
                    } else if (position == directConnectionRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.outline_shield_plain_24, ColgramMenuChrome.COLOR_EMERALD);
                        String subscriptionState = subscriptionTunnelStatus(getContext());
                        boolean vpnActive = isAndroidVpnActive();
                        ColgramProxyManager.ProxyItem route = ColgramProxyManager.getCurrentActiveProxy();
                        boolean direct = !ColgramConfig.isWarpEnabled() && route != null && route.isLocalDpi()
                                && !ColgramProxyManager.isDcRemapActive()
                                && !ColgramConfig.isIpv6BypassEnabled() && !ColgramConfig.isAutoProxyEnabled()
                                && !ColgramProxyManager.isProxySelectionPending() && !vpnActive
                                && !"running".equals(subscriptionState) && !"starting".equals(subscriptionState)
                                && !"stopping".equals(subscriptionState) && !"consent".equals(subscriptionState);
                        settingsCell.setTextAndValue("Обычный интернет · локальный слой",
                                direct ? "выбран · " + dpiStatusLine() : vpnActive ? "активен Android VPN" : "нажмите для переключения", true);
                    } else if (position == publicProxyRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_search, ColgramMenuChrome.COLOR_BLUE);
                        String status = ColgramProxyManager.getProxySelectionStatus();
                        settingsCell.setTextAndValue("Найти рабочий публичный прокси",
                                ColgramProxyManager.isProxySelectionPending() ? "проверяется · нажмите для отмены"
                                        : status == null || status.isEmpty() ? "проверить" : status, true);
                    } else if (position == subscriptionNodeRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_online, ColgramMenuChrome.COLOR_INDIGO);
                        settingsCell.setTextAndValue("Узел / страна из подписки",
                                subscription.nodeLabel(), true);
                    } else if (position == callsRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.settings_calls, ColgramMenuChrome.COLOR_EMERALD);
                        settingsCell.setTextAndValue("Использовать для звонков", ColgramCallRoutes.summary(), true);
                    } else if (position == browserRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_link2, ColgramMenuChrome.COLOR_CYAN);
                        settingsCell.setTextAndValue("Использовать для браузера", ColgramBrowserRoutes.summary(), false);
                    } else if (position == dataSettingsRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.settings_data, ColgramMenuChrome.COLOR_BLUE);
                        settingsCell.setTextAndValue("Экономия трафика и автозагрузка", "настроить", true);
                    } else if (position == cacheSettingsRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_clearcache, ColgramMenuChrome.COLOR_AMBER);
                        settingsCell.setTextAndValue("Кэш: размер и срок хранения",
                                org.colgram.core.ColgramUnlimitedMemory.buildStatusSummary(mContext), false);
                    } else if (position == cloudflareWarpRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_speed, ColgramMenuChrome.COLOR_CRIMSON);
                        settingsCell.setTextAndValue("Cloudflare WARP (встроенный обход)",
                                warpStatusLine(), true);
                    } else if (position == subscriptionRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_download, ColgramMenuChrome.COLOR_VIOLET);
                        // The row says what is actually stored, never what the user hopes for:
                        // a link that parsed to nothing shows as absent, not as a tunnel waiting
                        // to start.
                        settingsCell.setTextAndValue("Подписка (VPN на весь телефон)",
                                subscriptionStatusLine(), true);
                    }
                    break;
                }
                case 4: {
                    TextInfoPrivacyCell statusCell = (TextInfoPrivacyCell) holder.itemView;
                    statusCell.setText("Локальный слой всегда включён\n" + dpiStatusLine());
                    break;
                }
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR,
                new Class[]{HeaderCell.class, TextCheckCell.class, TextSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND,
                null, null, null, null, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND,
                null, null, null, null, Theme.key_actionBarDefault));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR,
                null, null, null, null, Theme.key_actionBarDefaultIcon));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR,
                null, null, null, null, Theme.key_actionBarDefaultTitle));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR,
                null, null, null, null, Theme.key_actionBarDefaultSelector));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR,
                null, null, null, null, Theme.key_listSelector));
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
                new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER,
                new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER,
                new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR,
                new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{ColgramMenuChrome.HeroBannerCell.class}, null, null, () -> {
            if (listView != null) {
                for (int i = 0; i < listView.getChildCount(); i++) {
                    View ch = listView.getChildAt(i);
                    if (ch instanceof ColgramMenuChrome.HeroBannerCell) {
                        ((ColgramMenuChrome.HeroBannerCell) ch).updateTheme();
                    }
                }
            }
        }, Theme.key_windowBackgroundWhite));
        return list;
    }
}
