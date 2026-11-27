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

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramBotSync;
import org.colgram.core.ColgramBuildFlags;
import org.colgram.core.ColgramCloak;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramDpiBypass;
import org.colgram.core.ColgramOperationGate;
import org.colgram.core.ColgramPremiumConfig;
import org.colgram.core.ColgramRouteEpoch;
import org.colgram.core.ColgramProxyDoctor;
import org.colgram.core.ColgramProxyManager;
import org.colgram.core.ColgramSettingsBackup;
import org.colgram.core.ColgramShieldConfig;
import org.colgram.core.ColgramStorageSandbox;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
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
    private int liveTelemetryRow;
    private int premiumRow;
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
                            heroBannerRow, liveTelemetryRow, directConnectionRow, publicProxyRow, cloudflareWarpRow,
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
    private int filesHubRow;
    private int infiniteStorageRow;
    private int storageCapRow;
    private int lazyShadowVaultRow;
    private int malwareShieldRow;
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
    private int antiPingBigGroupsRow;
    private int collapseSpamPingsRow;
    private int autoClearMentionsRow;
    private int autoReplyRow;
    private int tempMailRow;
    private int versionsRow;
    private int botsSectionRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        ColgramConfig.init(getParentActivity());
        ColgramShieldConfig.init(getParentActivity());
        ColgramPremiumConfig.init(getParentActivity());
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
        liveTelemetryRow = rowCount++;
        premiumRow = rowCount++;
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
        filesHubRow = rowCount++;
        infiniteStorageRow = rowCount++;
        storageCapRow = rowCount++;
        lazyShadowVaultRow = rowCount++;
        malwareShieldRow = rowCount++;
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
        antiPingBigGroupsRow = rowCount++;
        collapseSpamPingsRow = rowCount++;
        autoClearMentionsRow = rowCount++;
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
                    || position == pluginsRow || position == accountVaultRow
                    || position == premiumRow || position == filesHubRow) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastNavTapMs < 360L) return;
                lastNavTapMs = now;
            }
            if (position == premiumRow) {
                presentFragment(new ColgramPremiumActivity());
                return;
            } else if (position == filesHubRow) {
                presentFragment(new ColgramFilesActivity());
                return;
            } else if (position == storageCapRow) {
                showStorageCapSelector();
                return;
            } else if (position == infiniteStorageRow) {
                boolean val = !ColgramShieldConfig.isInfiniteVirtualStorageEnabled();
                ColgramShieldConfig.setInfiniteVirtualStorageEnabled(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
                ColgramUiKit.showNotice(this, R.drawable.msg_folders,
                        val ? "Бесконечное хранилище (≤ 1 ГБ на диске) включено"
                                : "Виртуальное хранилище выключено",
                        false);
                return;
            } else if (position == lazyShadowVaultRow) {
                boolean val = !ColgramShieldConfig.isLazyShadowVaultEnabled();
                ColgramShieldConfig.setLazyShadowVaultEnabled(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
                return;
            } else if (position == malwareShieldRow) {
                boolean val = !ColgramShieldConfig.isMalwareDownloadShieldEnabled();
                ColgramShieldConfig.setMalwareDownloadShieldEnabled(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
                return;
            } else if (position == antiPingBigGroupsRow) {
                boolean val = !ColgramShieldConfig.isAntiPingStrangersBigGroupsEnabled();
                ColgramShieldConfig.setAntiPingStrangersBigGroupsEnabled(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
                return;
            } else if (position == collapseSpamPingsRow) {
                boolean val = !ColgramShieldConfig.isCollapseSpamPingsEnabled();
                ColgramShieldConfig.setCollapseSpamPingsEnabled(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
                return;
            } else if (position == autoClearMentionsRow) {
                boolean val = !ColgramShieldConfig.isAntiPingAutoClearServerMentionsEnabled();
                ColgramShieldConfig.setAntiPingAutoClearServerMentionsEnabled(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
                return;
            } else if (position == defenderRow) {
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
                ColgramUiKit.showNotice(this, R.drawable.msg_list,
                        val ? "Панель быстрого доступа включена" : "Панель быстрого доступа скрыта",
                        false);
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
                ColgramUiKit.showNotice(this, R.drawable.msg_speed, remap
                        ? "Telegram будет звонить на тот свой адрес, который отвечает. Это не прокси: между тобой и Telegram остаётся только Telegram."
                        : "Прямой выбор адреса выключен.",
                        false);
            } else if (position == ipv6BypassRow) {
                boolean val = !ColgramConfig.isIpv6BypassEnabled();
                ColgramConfig.setIpv6BypassEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
                // The strategy is read when tgnet re-evaluates the network, so nudge it here -
                // otherwise the switch only takes effect after the next connection change.
                ColgramProxyManager.recheckConnectionNow();
                ColgramUiKit.showNotice(this, R.drawable.msg_online, val
                        ? "Telegram будет дёргать только IPv6-адреса центров. Нужно, чтобы в сети был рабочий IPv6."
                        : "Вернулись к IPv4.",
                        false);
            } else if (position == relayUrlRow) {
                editRelayUrl();
            } else if (position == proxyStatusRow) {
                ColgramProxyManager.checkPoolNow(true);
                if (getParentActivity() != null) {
                    ColgramUiKit.showNotice(this, R.drawable.msg_info,
                            "Проверяю прокси через нативный чекер Telegram…", false);
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
                ColgramUiKit.showNotice(this, R.drawable.msg_clearcache,
                        val ? "Безлимит хранилища и автосжатие включены"
                                : "Восстановлен стандартный лимит кэша",
                        false);
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
                    ColgramUiKit.showNotice(this, R.drawable.msg_bots, "Проверяю Bot API…", false);
                }
                ColgramBotSync.selfTestBotApi(getContext(), currentAccount, text -> {
                    if (isFinishing()) return;
                    lastBotTestReport = text;
                    if (listAdapter != null && botTestRow >= 0) listAdapter.notifyItemChanged(botTestRow);
                    android.app.Activity act = getParentActivity();
                    if (act == null || act.isFinishing() || act.isDestroyed()) return;
                    ColgramUiKit.showInfoSheet(
                            act,
                            resourceProvider,
                            R.drawable.msg_bots,
                            "Bot API",
                            text,
                            "Ок",
                            null,
                            null,
                            null,
                            true
                    );
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
        java.util.List<ColgramUiKit.ChoiceItem> items = new java.util.ArrayList<>(9);
        items.add(ColgramUiKit.ChoiceItem.of("Сохранить ВСЕ аккаунты и данные в Documents/ColgramBackup", "AES-256 · портативный сейф", R.drawable.msg_archive));
        items.add(ColgramUiKit.ChoiceItem.of("Восстановить ВСЕ аккаунты и данные из файла (.cgb)", "Импорт через SAF", R.drawable.msg_download));
        items.add(ColgramUiKit.ChoiceItem.of("Перенести на другой телефон (экспорт .cgb с паролем)", "Зашифрованный перенос", R.drawable.msg_forward));
        items.add(ColgramUiKit.ChoiceItem.of("Экспорт настроек в файл: " + backupFile.getName(), null, R.drawable.msg_view_file));
        items.add(ColgramUiKit.ChoiceItem.of("Импорт настроек из файла: " + backupFile.getName(), null, R.drawable.msg_view_file));
        items.add(ColgramUiKit.ChoiceItem.of("Копировать JSON в буфер обмена", null, R.drawable.msg_copy));
        items.add(ColgramUiKit.ChoiceItem.of("Восстановить JSON из буфера обмена", null, R.drawable.msg_edit));
        items.add(ColgramUiKit.ChoiceItem.of("Зашифрованный экспорт настроек в буфер (AES-256)", null, R.drawable.msg_secret));
        items.add(ColgramUiKit.ChoiceItem.of("Импорт зашифрованного бэкапа из буфера", null, R.drawable.msg_secret));
        ColgramUiKit.showChoiceSheet(
                act,
                resourceProvider,
                R.drawable.msg_archive,
                "Бэкап аккаунтов и настроек (AES-256)",
                "Выберите способ сохранения или восстановления",
                items,
                which -> {
                    try {
                        if (which == 0) {
                            promptDocumentsBackupPassphrase(act, appCtx);
                        } else if (which == 1) {
                            ColgramAccountSwitcherHelper.launchPersistentBackupRestore(act);
                        } else if (which == 2) {
                            promptPortableVaultPassphrase(act);
                        } else if (which == 3) {
                            warpOperations.execute(() -> {
                                try {
                                    java.io.File written = ColgramSettingsBackup.exportToFile(appCtx, backupFile);
                                    uiHandler.post(() -> ColgramUiKit.showNotice(this, R.drawable.msg_archive,
                                            "Сохранено: " + written.getAbsolutePath(), false));
                                } catch (Throwable error) {
                                    uiHandler.post(() -> ColgramUiKit.showNotice(this, R.drawable.msg_info,
                                            "Ошибка: " + error.getMessage(), true));
                                }
                            });
                        } else if (which == 4) {
                            warpOperations.execute(() -> {
                                try {
                                    if (!backupFile.exists()) {
                                        uiHandler.post(() -> ColgramUiKit.showNotice(this, R.drawable.msg_info,
                                                "Файл не найден: " + backupFile.getAbsolutePath(), true));
                                        return;
                                    }
                                    int restored = ColgramSettingsBackup.importFromFile(appCtx, backupFile);
                                    uiHandler.post(() -> {
                                        if (!isFinishing()) refreshSettings();
                                        ColgramUiKit.showNotice(this, R.drawable.msg_archive,
                                                "Восстановлено настроек: " + restored, false);
                                    });
                                } catch (Throwable error) {
                                    uiHandler.post(() -> ColgramUiKit.showNotice(this, R.drawable.msg_info,
                                            "Ошибка: " + error.getMessage(), true));
                                }
                            });
                        } else if (which == 5) {
                            String json = ColgramSettingsBackup.exportToJson(appCtx);
                            AndroidUtilities.addToClipboard(json);
                            ColgramUiKit.showNotice(this, R.drawable.msg_copy, "JSON бэкап скопирован", false);
                        } else if (which == 6) {
                            ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
                            CharSequence raw = (clip != null && clip.getItemCount() > 0) ? clip.getItemAt(0).coerceToText(appCtx) : null;
                            if (raw == null || raw.length() == 0) {
                                ColgramUiKit.showNotice(this, R.drawable.msg_info, "Буфер обмена пуст", true);
                                return;
                            }
                            int restored = ColgramSettingsBackup.importFromJson(appCtx, raw.toString());
                            refreshSettings();
                            ColgramUiKit.showNotice(this, R.drawable.msg_archive, "Импортировано настроек: " + restored, false);
                        } else if (which == 7) {
                            promptBackupPassphrase(act, true, null);
                        } else if (which == 8) {
                            ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
                            CharSequence raw = (clip != null && clip.getItemCount() > 0) ? clip.getItemAt(0).coerceToText(appCtx) : null;
                            if (raw == null || raw.length() == 0) {
                                ColgramUiKit.showNotice(this, R.drawable.msg_info, "Буфер обмена пуст", true);
                                return;
                            }
                            promptBackupPassphrase(act, false, raw.toString());
                        }
                    } catch (Throwable error) {
                        ColgramUiKit.showNotice(this, R.drawable.msg_info, "Ошибка: " + error.getMessage(), true);
                    }
                }
        );
    }

    private void promptDocumentsBackupPassphrase(android.app.Activity act, Context appCtx) {
        if (isFinishing() || act == null || act.isFinishing() || act.isDestroyed()) return;
        ColgramUiKit.showInputSheet(
                act,
                resourceProvider,
                R.drawable.msg_archive,
                "Сохранить в Documents/ColgramBackup",
                "Зашифрованная AES-256 копия всех аккаунтов",
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
                        ColgramUiKit.showNotice(this, R.drawable.msg_info, "Минимум 4 символа", true);
                        return;
                    }
                    final char[] passChars = pass.toCharArray();
                    warpOperations.execute(() -> {
                        try {
                            for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                                if (org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                                    org.telegram.messenger.UserConfig.getInstance(a).saveConfig(true);
                                }
                            }
                            org.colgram.core.ColgramPersistentBackup.BackupResult res =
                                    org.colgram.core.ColgramPersistentBackup.backupNow(appCtx, passChars);
                            uiHandler.post(() -> ColgramUiKit.showNotice(
                                    this,
                                    res.success ? R.drawable.msg_archive : R.drawable.msg_info,
                                    res.success ? ("Сохранено (" + res.accountCount + " акк.) -> " + res.location)
                                            : ("Ошибка: " + res.error),
                                    !res.success
                            ));
                        } finally {
                            java.util.Arrays.fill(passChars, '\0');
                        }
                    });
                },
                null,
                null
        );
    }

    private void promptPortableVaultPassphrase(android.app.Activity act) {
        if (isFinishing() || act == null || act.isFinishing() || act.isDestroyed()) return;
        ColgramUiKit.showInputSheet(
                act,
                resourceProvider,
                R.drawable.msg_forward,
                "Перенос всех аккаунтов (.cgb)",
                "Создаст зашифрованный архив для переноса на новое устройство",
                "Придумайте пароль (AES-256)",
                null,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                true,
                !org.colgram.core.ColgramHookHandler.shouldBypassFlagSecure(),
                true,
                "Сохранить файл",
                rawPass -> {
                    String pass = rawPass != null ? rawPass : "";
                    if (pass.length() < 4) {
                        ColgramUiKit.showNotice(this, R.drawable.msg_info, "Минимум 4 символа", true);
                        return;
                    }
                    ColgramAccountSwitcherHelper.launchPersistentBackupExport(act, pass.toCharArray());
                },
                null,
                null
        );
    }

    private void promptBackupPassphrase(Context ctx, boolean exportMode, String clipboardPayload) {
        android.app.Activity act = getParentActivity();
        if (isFinishing() || act == null || act.isFinishing() || act.isDestroyed()) return;
        final Context appCtx = act.getApplicationContext();
        ColgramUiKit.showInputSheet(
                act,
                resourceProvider,
                R.drawable.msg_secret,
                exportMode ? "Зашифровать настройки (AES-256)" : "Расшифровать бэкап (AES-256)",
                exportMode ? "Зашифрованный AES-256-GCM JSON будет скопирован в буфер обмена" : "Введите пароль от зашифрованного бэкапа",
                "Пароль для шифрования AES-256-GCM",
                null,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                true,
                !org.colgram.core.ColgramHookHandler.shouldBypassFlagSecure(),
                true,
                "OK",
                rawPass -> {
                    String pass = rawPass != null ? rawPass : "";
                    if (pass.isEmpty()) {
                        ColgramUiKit.showNotice(this, R.drawable.msg_info, "Введите пароль", true);
                        return;
                    }
                    final char[] chars = pass.toCharArray();
                    warpOperations.execute(() -> {
                        try {
                            if (exportMode) {
                                String enc = ColgramSettingsBackup.exportEncrypted(appCtx, chars);
                                uiHandler.post(() -> {
                                    AndroidUtilities.addToClipboard(enc);
                                    ColgramUiKit.showNotice(this, R.drawable.msg_copy,
                                            "Зашифрованный AES-256 бэкап скопирован", false);
                                });
                            } else {
                                int n = ColgramSettingsBackup.importAuto(appCtx, clipboardPayload, chars);
                                uiHandler.post(() -> {
                                    if (!isFinishing()) refreshSettings();
                                    ColgramUiKit.showNotice(this, R.drawable.msg_archive,
                                            "Расшифровано и применено: " + n, false);
                                });
                            }
                        } catch (Throwable error) {
                            uiHandler.post(() -> ColgramUiKit.showNotice(this, R.drawable.msg_info,
                                    "Ошибка: " + error.getMessage(), true));
                        } finally {
                            java.util.Arrays.fill(chars, '\0');
                        }
                    });
                },
                null,
                null
        );
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
        ColgramUiKit.showChoiceSheet(
                act,
                resourceProvider,
                R.drawable.msg_media_gallery,
                "Живые обои чатов",
                "GPU-эффекты для фона переписок",
                names,
                ColgramConfig.getLiveWallpaperStyle(),
                which -> {
                    ColgramConfig.setLiveWallpaperStyle(which);
                    refreshSettings();
                    org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.didSetNewWallpapper);
                }
        );
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
            ColgramUiKit.showConfirmSheet(
                    act,
                    resourceProvider,
                    R.drawable.msg_online,
                    "Публичные прокси",
                    "В каталоге пока нет узлов с подтверждённым протоколом. Адрес сервера не подтверждает страну.",
                    "Проверить каталог",
                    false,
                    () -> {
                        Context context = getContext();
                        if (context != null) { cancelWarp(context); stopSubscriptionTunnel(context); }
                        ColgramProxyManager.selectPublicProxy(getContext());
                        refreshSettings();
                    },
                    "Отмена",
                    null
            );
            return;
        }
        java.util.List<ColgramUiKit.ChoiceItem> items = new java.util.ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            ColgramProxyManager.ProxyItem node = nodes.get(i);
            String protocol = node.type == 0 ? "SOCKS5" : node.type == 1 ? "MTProto" : "WebSocket";
            items.add(new ColgramUiKit.ChoiceItem(
                    protocol + " · " + node.address + ":" + node.port,
                    "Задержка " + node.pingMs + " мс",
                    R.drawable.msg_online,
                    0,
                    false,
                    false,
                    node.pingMs + "мс"
            ));
        }
        ColgramUiKit.showChoiceSheet(
                act,
                resourceProvider,
                R.drawable.msg_online,
                "Выбрать публичный узел",
                "Нажмите на узел для мгновенного переключения",
                items,
                which -> {
                    Context context = getContext();
                    if (context == null) return;
                    cancelWarp(context);
                    stopSubscriptionTunnel(context);
                    ColgramProxyManager.ProxyItem node = nodes.get(which);
                    ColgramProxyManager.selectUserProxy(context, node.address, node.port, node.secret,
                            node.type, node.user, node.password);
                    refreshSettings();
                }
        );
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
        Activity act = getParentActivity();
        if (act == null) return;
        final String[] labels = {
            "Google Pixel 8 Pro (Android 14)",
            "Google Pixel 9 Pro (Android 15)",
            "Samsung Galaxy S24 Ultra (Android 14)",
            "Xiaomi 14 Ultra (Android 14)",
            "Nothing Phone (2) (Android 14)",
            "OnePlus 12 (Android 14)",
            "Случайный профиль",
            "Своя модель вручную..."
        };
        final String[] models = {
            "Google Pixel 8 Pro",
            "Google Pixel 9 Pro",
            "Samsung Galaxy S24 Ultra",
            "Xiaomi 14 Ultra",
            "Nothing Phone (2)",
            "OnePlus 12"
        };
        String currentModel = ColgramCloak.getEffectiveDeviceModel();
        java.util.List<ColgramUiKit.ChoiceItem> items = new java.util.ArrayList<>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            boolean active = i < models.length && models[i].equals(currentModel);
            int ic = i == 6 ? R.drawable.msg_retry : (i == 7 ? R.drawable.msg_edit : R.drawable.msg_secret);
            items.add(new ColgramUiKit.ChoiceItem(labels[i], null, ic, 0, active, false, null));
        }
        ColgramUiKit.showChoiceSheet(
                act,
                resourceProvider,
                R.drawable.msg_secret,
                "Выберите профиль устройства",
                "MTProto Device Cloaking",
                items,
                which -> {
                    String selectedModel;
                    String selectedSys;
                    if (which == 6) {
                        ColgramCloak.DeviceProfile rnd = ColgramCloak.getRandomProfile();
                        selectedModel = rnd.model;
                        selectedSys = rnd.systemVersion;
                    } else if (which == 7) {
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
                }
        );
    }

    private void showCustomModelInput() {
        Activity act = getParentActivity();
        if (act == null) return;
        ColgramUiKit.showInputSheet(
                act,
                resourceProvider,
                R.drawable.msg_edit,
                "Своя модель устройства",
                null,
                "Например: Google Pixel 9 Pro",
                ColgramCloak.getEffectiveDeviceModel(),
                android.text.InputType.TYPE_CLASS_TEXT,
                false,
                false,
                true,
                "Сохранить",
                raw -> {
                    String model = raw == null ? "" : raw.trim();
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
                },
                null,
                null
        );
    }

    private void promptRestartForCloakChange(String message) {
        Activity act = getParentActivity();
        if (act == null) return;
        try {
            ColgramUiKit.showConfirmSheet(
                    act,
                    resourceProvider,
                    R.drawable.msg_secret,
                    "Маскировка устройства",
                    message,
                    "Перезапустить сейчас",
                    false,
                    () -> restartApplicationCleanly(act),
                    "Позже",
                    null
            );
        } catch (Throwable ignored) {
            ColgramUiKit.showNotice(this, R.drawable.msg_secret, message, false);
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
        Activity ctx = getParentActivity();
        if (ctx == null) return;
        ColgramUiKit.showInputSheet(
                ctx,
                resourceProvider,
                R.drawable.msg_link2,
                "Реле-адрес",
                "Через него идут Bot API, временные почты и списки прокси, когда их адреса блокируют по IP. Трафик самого Telegram он не заменяет — для него нужен прокси или обходчик.",
                "https://имя.workers.dev",
                ColgramConfig.getRelayUrl(),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI,
                false,
                false,
                true,
                "Сохранить",
                val -> {
                    ColgramConfig.setRelayUrl(val == null ? "" : val.trim());
                    if (listAdapter != null && relayUrlRow >= 0) listAdapter.notifyItemChanged(relayUrlRow);
                },
                "Сбросить",
                ignored -> {
                    ColgramConfig.setRelayUrl("");
                    if (listAdapter != null && relayUrlRow >= 0) listAdapter.notifyItemChanged(relayUrlRow);
                }
        );
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
            ColgramUiKit.showNotice(context, R.drawable.msg_info, "WARP-бэкенд отсутствует в этой сборке", true);
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
                    ColgramUiKit.showNotice(context, R.drawable.msg_info,
                            "WARP: " + (error == null ? "туннель не запущен" : error), true);
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
        Activity act = getParentActivity();
        if (act == null) return;
        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);

        android.widget.EditText address = relayField(layout, "Адрес релея",
                org.colgram.core.ColgramWarp.relayAddress());
        android.widget.EditText port = relayField(layout, "Порт",
                org.colgram.core.ColgramWarp.relayPort() > 0
                        ? String.valueOf(org.colgram.core.ColgramWarp.relayPort()) : "");
        android.widget.EditText publicKey = relayField(layout, "Публичный ключ релея",
                org.colgram.core.ColgramWarp.relayPublicKey());
        android.widget.EditText preshared = relayField(layout, "Preshared key (если есть)",
                org.colgram.core.ColgramWarp.relayPresharedKey());

        ColgramUiKit.showCustomViewSheet(
                act,
                resourceProvider,
                R.drawable.msg_link2,
                "Релей для WARP",
                "Нужен, если UDP Cloudflare заблокирован: релея держите сами, он принимает WireGuard по UDP и проксирует в Cloudflare. Запускайте его с ключом --udp-listen: порт отсюда должен быть UDP, иначе туннель не поднимется.",
                layout,
                "Сохранить",
                s -> {
                    int portNumber = 0;
                    try {
                        portNumber = Integer.parseInt(port.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    org.colgram.core.ColgramWarp.setRelay(address.getText().toString(), portNumber,
                            publicKey.getText().toString(), preshared.getText().toString());
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    ColgramUiKit.showNotice(this, R.drawable.msg_link2,
                            org.colgram.core.ColgramWarp.hasRelay() ? "Релей сохранён" : "Релей очищен", false);
                    return true;
                },
                null,
                null,
                LocaleController.getString(R.string.Cancel),
                true,
                false
        );
    }

    private android.widget.EditText relayField(LinearLayout layout, String hint, String value) {
        Activity act = getParentActivity();
        Context ctx = act != null ? act : getContext();
        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        EditTextBoldCursor input = ColgramUiKit.makeStyledEditText(
                ctx, resourceProvider, pal, hint, value, android.text.InputType.TYPE_CLASS_TEXT, true);
        LinearLayout box = ColgramUiKit.makeInputContainer(ctx, pal, input, false);
        layout.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) box.getLayoutParams()).bottomMargin = AndroidUtilities.dp(8);
        return input;
    }

    private void showStorageCapSelector() {
        Activity act = getParentActivity();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        java.util.List<ColgramUiKit.ChoiceItem> items = new java.util.ArrayList<>(5);
        items.add(ColgramUiKit.ChoiceItem.of("512 МБ (жёсткий эконом-лимит)", "Автоочистка при > 512 МБ", R.drawable.msg_clearcache));
        items.add(ColgramUiKit.ChoiceItem.of("1024 МБ / 1 ГБ (Рекомендуется)", "Жёсткий лимит кэша ≤ 1 ГБ на диске", R.drawable.outline_shield_check));
        items.add(ColgramUiKit.ChoiceItem.of("2048 МБ (2 ГБ)", "Повышенный физический предел", R.drawable.msg_folders));
        items.add(ColgramUiKit.ChoiceItem.of("Выключить лимит (∞)", "Отключает жёсткую квоту хранилища", R.drawable.msg_speed));
        ColgramUiKit.showChoiceSheet(
                act,
                resourceProvider,
                R.drawable.msg_clearcache,
                "Жёсткий лимит кэша на диске",
                "Бесконечное хранилище (≤ 1 ГБ на диске) держит индексы и стриминг-кэш в заданной квоте.",
                items,
                idx -> {
                    if (idx == 0) {
                        ColgramShieldConfig.setStorageCapEnabled(true);
                        ColgramShieldConfig.setStorageHardCapMb(512);
                    } else if (idx == 1) {
                        ColgramShieldConfig.setStorageCapEnabled(true);
                        ColgramShieldConfig.setStorageHardCapMb(1024);
                    } else if (idx == 2) {
                        ColgramShieldConfig.setStorageCapEnabled(true);
                        ColgramShieldConfig.setStorageHardCapMb(2048);
                    } else if (idx == 3) {
                        ColgramShieldConfig.setStorageCapEnabled(false);
                    }
                    if (listAdapter != null && storageCapRow >= 0) {
                        listAdapter.notifyItemChanged(storageCapRow);
                    }
                }
        );
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
                   || position == dataSettingsRow || position == cacheSettingsRow
                   || position == premiumRow || position == filesHubRow
                   || position == infiniteStorageRow || position == storageCapRow
                   || position == lazyShadowVaultRow || position == malwareShieldRow
                   || position == antiPingBigGroupsRow || position == collapseSpamPingsRow
                   || position == autoClearMentionsRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == heroBannerRow) return 5;
            if (position == liveTelemetryRow) return 6;
            if (position == dpiBypassRow) return 4; // Non-interactive TextInfoPrivacyCell, never a switch.
            if (position == cloakingHeaderRow || position == vaultHeaderRow || position == ghostHeaderRow ||
                position == networkHeaderRow || position == resourceHeaderRow || position == sandboxHeaderRow ||
                position == botsHeaderRow) {
                return 0; // HeaderCell
            } else if (position == cloakModelRow || position == currentProxyRow
                    || position == ownProxyRow || position == proxyStatusRow || position == relayUrlRow
                    || position == sandboxFilesRow || position == defenderRow || position == cacheSettingsRow
                    || position == directConnectionRow || position == publicProxyRow || position == cloudflareWarpRow
                    || position == subscriptionRow || position == subscriptionNodeRow
                    || position == callsRow || position == browserRow
                    || position == premiumRow || position == filesHubRow || position == storageCapRow) {
                return 7; // TextDetailSettingsCell (two-line wrap-safe layout)
            } else if (position == accountVaultRow || position == liveWallpaperRow || position == easterEggsRow
                    || position == batteryAutostartRow || position == settingsBackupRow
                    || position == pluginsRow || position == botTestRow
                    || position == antiSpamRow || position == autoReplyRow || position == tempMailRow || position == versionsRow
                    || position == dataSettingsRow) {
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
                case 7:
                    view = new TextDetailSettingsCell(mContext);
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
                case 6:
                    view = new ColgramUiKit.LiveTelemetryCardView(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 6: {
                    ColgramUiKit.LiveTelemetryCardView card = (ColgramUiKit.LiveTelemetryCardView) holder.itemView;
                    card.bindTheme();
                    card.refreshTelemetry();
                    break;
                }
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
                    if (position == infiniteStorageRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_EMERALD, R.drawable.msg_folders);
                        checkCell.setColgramWrapTitle(true);
                        checkCell.setTextAndValueAndCheck("Бесконечное хранилище (≤ 1 ГБ на диске)",
                                "Виртуальная память для фото, видео и голосовых без раздувания диска",
                                ColgramShieldConfig.isInfiniteVirtualStorageEnabled(), true, true);
                    } else if (position == lazyShadowVaultRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_INDIGO, R.drawable.msg_archive);
                        checkCell.setColgramWrapTitle(true);
                        checkCell.setTextAndValueAndCheck("Ленивый сейф анти-удаления (Shadow Vault)",
                                "Не скачивает тяжелые медиа заранее при получении сообщений",
                                ColgramShieldConfig.isLazyShadowVaultEnabled(), true, true);
                    } else if (position == malwareShieldRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_CRIMSON, R.drawable.outline_shield_check);
                        checkCell.setColgramWrapTitle(true);
                        checkCell.setTextAndValueAndCheck("Щит от вредоносных и тяжёлых APK",
                                "Предупреждает при загрузке APK / архивов и опасных вложений",
                                ColgramShieldConfig.isMalwareDownloadShieldEnabled(), true, false);
                    } else if (position == antiPingBigGroupsRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_VIOLET, R.drawable.msg_block);
                        checkCell.setColgramWrapTitle(true);
                        checkCell.setTextAndValueAndCheck("Анти-пинг незнакомцев в больших группах",
                                "Отключает уведомления об @упоминаниях от незнакомцев",
                                ColgramShieldConfig.isAntiPingStrangersBigGroupsEnabled(), true, true);
                    } else if (position == collapseSpamPingsRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_AMBER, R.drawable.msg_clear);
                        checkCell.setColgramWrapTitle(true);
                        checkCell.setTextAndValueAndCheck("Сворачивать спам-пинги и простыни в чатах",
                                "Авто-сворачивание длинных спам-сообщений с @упоминаниями",
                                ColgramShieldConfig.isCollapseSpamPingsEnabled(), true, true);
                    } else if (position == autoClearMentionsRow) {
                        ColgramMenuChrome.applyCheckIcon(checkCell, ColgramMenuChrome.COLOR_BLUE, R.drawable.msg_check_s);
                        checkCell.setColgramWrapTitle(true);
                        checkCell.setTextAndValueAndCheck("Авто-сброс серверных @упоминаний от спамеров",
                                "Молча снимает значок «@» после отфильтрованного пинга",
                                ColgramShieldConfig.isAntiPingAutoClearServerMentionsEnabled(), true, true);
                    } else if (position == cloakEnabledRow) {
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
                    if (position == accountVaultRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.settings_account, ColgramMenuChrome.COLOR_INDIGO);
                        settingsCell.setTextAndValue("Сейф аккаунтов (без лимита)",
                                "управление и пины", true);
                    } else if (position == liveWallpaperRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_theme, ColgramMenuChrome.COLOR_ROSE);
                        settingsCell.setTextAndValue("Живые обои и волны чатов",
                                org.colgram.core.ColgramLiveWallpaper.styleLabel(ColgramConfig.getLiveWallpaperStyle()), true);
                    } else if (position == easterEggsRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_reactions, ColgramMenuChrome.COLOR_CRIMSON);
                        settingsCell.setTextAndValue(
                                ColgramBuildFlags.KOLITI_ENABLED ? "Кола-горка Koliti и пасхалки" : "Кола-горка и пасхалки",
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
                    } else if (position == botTestRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.msg_bots, ColgramMenuChrome.COLOR_INDIGO);
                        settingsCell.setTextAndValue("Проверка Bot API (имя и описание)",
                                botTestSummary(), true);
                    } else if (position == dataSettingsRow) {
                        ColgramMenuChrome.applySettingIcon(settingsCell, R.drawable.settings_data, ColgramMenuChrome.COLOR_BLUE);
                        settingsCell.setTextAndValue("Экономия трафика и автозагрузка", "настроить", true);
                    }
                    break;
                }
                case 7: {
                    TextDetailSettingsCell detailCell = (TextDetailSettingsCell) holder.itemView;
                    detailCell.setMultilineDetail(true);
                    if (position == premiumRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Colgram Premium+",
                                "Теги в Избранном, 0 рекламы, локальный STT и переводчик",
                                R.drawable.msg_reactions, ColgramMenuChrome.COLOR_VIOLET, false);
                    } else if (position == filesHubRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Бесконечное хранилище (≤ 1 ГБ на диске)",
                                "Открыть диспетчер файлов, виртуальный стриминг и очистку",
                                R.drawable.msg_folders, ColgramMenuChrome.COLOR_EMERALD, true);
                    } else if (position == storageCapRow) {
                        String capText = ColgramShieldConfig.isStorageCapEnabled()
                                ? (ColgramShieldConfig.getStorageHardCapMb() + " МБ · автоматическая обрезка тяжёлого кэша")
                                : "Без жёсткой квоты · очистка только вручную";
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Жёсткий лимит кэша на диске",
                                capText, R.drawable.msg_clearcache, ColgramMenuChrome.COLOR_AMBER, true);
                    } else if (position == cloakModelRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Профиль устройства",
                                ColgramCloak.getEffectiveDeviceModel() + " · " + ColgramCloak.getEffectiveSystemVersion(),
                                R.drawable.settings_devices, ColgramMenuChrome.COLOR_VIOLET, false);
                    } else if (position == currentProxyRow) {
                        ColgramProxyManager.ProxyItem active = ColgramProxyManager.getCurrentActiveProxy();
                        // Automatic failover deliberately keeps Telegram's persisted proxy
                        // preference off. The live route still needs to be shown as active.
                        boolean proxyOn = ColgramProxyManager.isProxyEnabled(getContext())
                                || active != null;
                        String proxyStr = !proxyOn
                                ? "прокси выключен"
                                : (active != null ? active.toString() : "включено, узел не определён");
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Выбрать публичный прокси",
                                proxyStr, R.drawable.msg_speed, ColgramMenuChrome.COLOR_CYAN, true);
                    } else if (position == ownProxyRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Свой прокси: MTProto / WebSocket / SOCKS5",
                                (ColgramProxyManager.isProxyEnabled(getContext())
                                        || ColgramProxyManager.getCurrentActiveProxy() != null)
                                        ? "открыть экран Telegram" : "добавить и включить",
                                R.drawable.outline_shield_check, ColgramMenuChrome.COLOR_BLUE, true);
                    } else if (position == sandboxFilesRow) {
                        // Show the path that is actually in effect: scoped storage decides it, so
                        // "Documents/Colgram" is only true when the system let us write there.
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Папка Colgram",
                                ColgramStorageSandbox.getRootPath(getContext()),
                                R.drawable.settings_folders, ColgramMenuChrome.COLOR_AMBER, true);
                    } else if (position == defenderRow) {
                        org.colgram.core.ColgramSecurityDefender.AuditReport dr =
                                org.colgram.core.ColgramSecurityDefender.getLatestReport();
                        String dVal = dr != null
                                ? dr.scorePercent + "% · закрыто " + dr.closedCount + "/" + dr.findings.size()
                                : (ColgramConfig.isDefenderEnabled() ? "авто-защита активна" : "выключен");
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Бот-защитник Telegram (дыры и утечки)",
                                dVal, R.drawable.outline_shield_check, ColgramMenuChrome.COLOR_EMERALD, true);
                    } else if (position == relayUrlRow) {
                        String relay = ColgramConfig.getRelayUrl();
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Реле-адрес для Bot API и почты",
                                relay.isEmpty() ? "не задан" : relay,
                                R.drawable.msg_link, ColgramMenuChrome.COLOR_TEAL, true);
                    } else if (position == proxyStatusRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Состояние прокси (нажмите для проверки)",
                                ColgramProxyDoctor.getStatusSummary(),
                                R.drawable.msg_info, ColgramMenuChrome.COLOR_AMBER, true);
                    } else if (position == directConnectionRow) {
                        String subscriptionState = subscriptionTunnelStatus(getContext());
                        boolean vpnActive = isAndroidVpnActive();
                        ColgramProxyManager.ProxyItem route = ColgramProxyManager.getCurrentActiveProxy();
                        boolean direct = !ColgramConfig.isWarpEnabled() && route != null && route.isLocalDpi()
                                && !ColgramProxyManager.isDcRemapActive()
                                && !ColgramConfig.isIpv6BypassEnabled() && !ColgramConfig.isAutoProxyEnabled()
                                && !ColgramProxyManager.isProxySelectionPending() && !vpnActive
                                && !"running".equals(subscriptionState) && !"starting".equals(subscriptionState)
                                && !"stopping".equals(subscriptionState) && !"consent".equals(subscriptionState);
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Обычный интернет · локальный слой",
                                direct ? "выбран · " + dpiStatusLine() : vpnActive ? "активен Android VPN" : "нажмите для переключения",
                                R.drawable.outline_shield_plain_24, ColgramMenuChrome.COLOR_EMERALD, true);
                    } else if (position == publicProxyRow) {
                        String status = ColgramProxyManager.getProxySelectionStatus();
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Найти рабочий публичный прокси",
                                ColgramProxyManager.isProxySelectionPending() ? "проверяется · нажмите для отмены"
                                        : status == null || status.isEmpty() ? "проверить" : status,
                                R.drawable.msg_search, ColgramMenuChrome.COLOR_BLUE, true);
                    } else if (position == subscriptionNodeRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Узел / страна из подписки",
                                subscription.nodeLabel(),
                                R.drawable.msg_online, ColgramMenuChrome.COLOR_INDIGO, true);
                    } else if (position == callsRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Использовать для звонков",
                                ColgramCallRoutes.summary(),
                                R.drawable.settings_calls, ColgramMenuChrome.COLOR_EMERALD, true);
                    } else if (position == browserRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Использовать для браузера",
                                ColgramBrowserRoutes.summary(),
                                R.drawable.msg_link2, ColgramMenuChrome.COLOR_CYAN, false);
                    } else if (position == cacheSettingsRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Кэш: размер и срок хранения",
                                org.colgram.core.ColgramUnlimitedMemory.buildStatusSummary(mContext),
                                R.drawable.msg_clearcache, ColgramMenuChrome.COLOR_AMBER, true);
                    } else if (position == cloudflareWarpRow) {
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Cloudflare WARP (встроенный обход)",
                                warpStatusLine(),
                                R.drawable.msg_speed, ColgramMenuChrome.COLOR_CRIMSON, true);
                    } else if (position == subscriptionRow) {
                        // The row says what is actually stored, never what the user hopes for:
                        // a link that parsed to nothing shows as absent, not as a tunnel waiting
                        // to start.
                        ColgramMenuChrome.applyDetailIcon(detailCell, "Подписка (VPN на весь телефон)",
                                subscriptionStatusLine(),
                                R.drawable.msg_download, ColgramMenuChrome.COLOR_VIOLET, true);
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
