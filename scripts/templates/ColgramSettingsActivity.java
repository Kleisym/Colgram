package org.telegram.ui;

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
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramDpiBypass;
import org.colgram.core.ColgramOperationGate;
import org.colgram.core.ColgramRouteEpoch;
import org.colgram.core.ColgramProxyDoctor;
import org.colgram.core.ColgramProxyManager;
import org.colgram.core.ColgramStorageSandbox;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class ColgramSettingsActivity extends BaseFragment {

    private static final String CLOUDFLARE_WARP_PACKAGE = "com.cloudflare.onedotonedotonedotone";
    /** Request code for the system VPN consent dialog started by prepareAndStartWarp(). */
    private static final int REQ_WARP_VPN = 9181;
    /** Android's consent for the subscription tunnel, which routes the whole phone. */
    private static final int REQ_SUBSCRIPTION_VPN = 9183;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int cloakingHeaderRow;
    private int cloakEnabledRow;
    private int cloakModelRow;
    private int cloakingSectionRow;

    private int vaultHeaderRow;
    private int antiDeleteRow;
    private int antiDeleteHighlightRow;
    private int antiDeleteWipeRow;
    private int preserveMediaRow;
    private int editHistoryRow;
    private int autoHidePhoneRow;
    private int vaultSectionRow;

    private int ghostHeaderRow;
    private int ghostReadRow;
    private int ghostTypingRow;
    private int ghostOnlineRow;
    private int bypassFlagSecureRow;
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
                refreshSettings();
            }
            uiHandler.postDelayed(this, 1500L);
        }
    };

    private int networkHeaderRow;
    private int directConnectionRow;
    private int publicProxyRow;
    private int subscriptionNodeRow;
    private int callsRow;
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
    private int sandboxFilesRow;
    private int sandboxSectionRow;

    private int botsHeaderRow;
    private int botRealtimeRow;
    private int botTestRow;
    private String lastBotTestReport;
    private int pluginsRow;
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
        cloakingHeaderRow = rowCount++;
        cloakEnabledRow = rowCount++;
        cloakModelRow = rowCount++;
        cloakingSectionRow = rowCount++;

        vaultHeaderRow = rowCount++;
        antiDeleteRow = rowCount++;
        preserveMediaRow = rowCount++;
        editHistoryRow = rowCount++;
        antiDeleteHighlightRow = rowCount++;
        antiDeleteWipeRow = rowCount++;
        autoHidePhoneRow = rowCount++;
        vaultSectionRow = rowCount++;

        ghostHeaderRow = rowCount++;
        ghostReadRow = rowCount++;
        ghostTypingRow = rowCount++;
        ghostOnlineRow = rowCount++;
        bypassFlagSecureRow = rowCount++;
        ghostSectionRow = rowCount++;
        networkHeaderRow = rowCount++;
        directConnectionRow = rowCount++;
        publicProxyRow = rowCount++;
        cloudflareWarpRow = rowCount++;
        subscriptionRow = rowCount++;
        subscriptionNodeRow = rowCount++;
        callsRow = rowCount++;
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
        sandboxFilesRow = rowCount++;
        sandboxSectionRow = rowCount++;

        botsHeaderRow = rowCount++;
        botRealtimeRow = rowCount++;
        botTestRow = rowCount++;
        pluginsRow = rowCount++;
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
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position == dpiBypassRow || position == ipv6BypassRow || position == dcRemapRow) {
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
            } else if (position == autoHidePhoneRow) {
                boolean val = !ColgramConfig.isAutoHidePhoneEnabled();
                ColgramConfig.setAutoHidePhoneEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
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
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == bypassFlagSecureRow) {
                boolean val = !ColgramConfig.isBypassFlagSecureEnabled();
                ColgramConfig.setBypassFlagSecureEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == dpiBypassRow) {
                ColgramProxyManager.recheckBypassListener();
                listAdapter.notifyDataSetChanged();
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
                Toast.makeText(getParentActivity(),
                        "Проверяю прокси через нативный чекер Telegram…", Toast.LENGTH_SHORT).show();
                // A sweep is up to 8 handshakes, and an unreachable host only reports after the
                // checker's own timeout, so refreshing at 4 s used to show the old numbers.
                AndroidUtilities.runOnUIThread(() -> {
                    if (listAdapter != null) {
                        listAdapter.notifyDataSetChanged();
                    }
                }, 15000L);
            } else if (position == sandboxStorageRow) {
                boolean val = !ColgramConfig.isSandboxStorageEnabled();
                ColgramConfig.setSandboxStorageEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == sandboxFilesRow) {
                // The folder itself. Attaching a file should not require handing Telegram the
                // whole media library, so this is where files live and where they get picked up.
                presentFragment(new ColgramFilesActivity());
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
                Toast.makeText(getParentActivity(), "Проверяю Bot API…", Toast.LENGTH_SHORT).show();
                ColgramBotSync.selfTestBotApi(getContext(), currentAccount, text -> {
                    lastBotTestReport = text;
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    if (getParentActivity() == null) return;
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Bot API")
                            .setMessage(text)
                            .setPositiveButton("Ок", null)
                            .show();
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

    private void showPublicProxyNodes() {
        if (getParentActivity() == null) return;
        java.util.List<ColgramProxyManager.ProxyItem> nodes = ColgramProxyManager.getSelectableProxies();
        if (nodes.isEmpty()) {
            new AlertDialog.Builder(getParentActivity()).setTitle("Публичные прокси")
                    .setMessage("В каталоге пока нет узлов с подтверждённым протоколом. Адрес сервера не подтверждает страну.")
                    .setPositiveButton("Проверить каталог", (d, w) -> {
                        Context context = getContext();
                        if (context != null) { cancelWarp(context); stopSubscriptionTunnel(context); }
                        ColgramProxyManager.selectPublicProxy(getContext());
                        refreshSettings();
                    }).setNegativeButton("Отмена", null).show();
            return;
        }
        String[] labels = new String[nodes.size()];
        for (int i = 0; i < labels.length; i++) {
            ColgramProxyManager.ProxyItem node = nodes.get(i);
            String protocol = node.type == 0 ? "SOCKS5" : node.type == 1 ? "MTProto" : "WebSocket";
            labels[i] = protocol + " · " + node.address + ":" + node.port + " · " + node.pingMs + " мс";
        }
        new AlertDialog.Builder(getParentActivity()).setTitle("Выбрать публичный узел")
                .setItems(labels, (d, which) -> {
                    Context context = getContext();
                    if (context == null) return;
                    cancelWarp(context);
                    stopSubscriptionTunnel(context);
                    ColgramProxyManager.ProxyItem node = nodes.get(which);
                    ColgramProxyManager.selectUserProxy(context, node.address, node.port, node.secret,
                            node.type, node.user, node.password);
                    refreshSettings();
                }).setNegativeButton("Отмена", null).show();
    }

    private void selectDirectConnection() {
        final Context context = getContext();
        if (context == null) return;
        cancelWarp(context);
        ColgramProxyManager.cancelProxySelection();
        ColgramConfig.setAutoProxyEnabled(false);
        ColgramConfig.setDpiBypassEnabled(false);
        ColgramConfig.setDcRemapEnabled(false);
        ColgramConfig.setIpv6BypassEnabled(false);
        final long token = ColgramRouteEpoch.snapshot();
        stopSubscriptionTunnel(context);
        warpOperations.execute(() -> {
            if (!ColgramRouteEpoch.isCurrent(token)) return;
            ColgramProxyManager.setDpiBypassEnabled(context, false);
            ColgramProxyManager.applyDcRemap(false);
            ColgramProxyManager.disableProxy(context);
            ColgramProxyManager.recheckConnectionNow();
            uiHandler.post(this::refreshSettings);
        });
        refreshSettings();
    }

    private void showModelSelector() {
        if (getParentActivity() == null) return;
        final String[] models = {
            "Google Pixel 8 Pro",
            "Samsung Galaxy S24 Ultra",
            "iPhone 15 Pro Max",
            "Xiaomi 14 Ultra",
            "Nothing Phone (2)",
            "OnePlus 12"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Выберите профиль устройства");
        builder.setItems(models, (dialog, which) -> {
            ColgramConfig.setSpoofDeviceModel(models[which]);
            listAdapter.notifyItemChanged(cloakModelRow);
            Toast.makeText(getParentActivity(), "Профиль изменен: " + models[which], Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
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
        final android.widget.EditText input = new android.widget.EditText(ctx);
        input.setHint("https://имя.workers.dev");
        input.setText(ColgramConfig.getRelayUrl());
        input.setSingleLine(true);
        input.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8),
                AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        new org.telegram.ui.ActionBar.AlertDialog.Builder(ctx)
                .setTitle("Реле-адрес")
                .setMessage("Через него идут Bot API, временные почты и списки прокси, когда их "
                        + "адреса блокируют по IP. Трафик самого Telegram он не заменяет — "
                        + "для него нужен прокси или обходчик.")
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    ColgramConfig.setRelayUrl(input.getText().toString().trim());
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                })
                .setNeutralButton("Сбросить", (dialog, which) -> {
                    ColgramConfig.setRelayUrl("");
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                })
                .setNegativeButton("Отмена", null)
                .show();
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
                ColgramConfig.setDpiBypassEnabled(false);
                ColgramConfig.setDcRemapEnabled(false);
                ColgramConfig.setIpv6BypassEnabled(false);
                ColgramProxyManager.setDpiBypassEnabled(context, false);
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
            return (org.colgram.core.ColgramWarpTunnel.isConnected() ? "подключён · " : "подключается · ")
                    + org.colgram.core.ColgramWarpTunnel.transportDescription();
        }
        if (org.colgram.core.ColgramWarp.isRegistered()) return "выключен · нажмите, чтобы включить";
        return "выключен · нажмите, чтобы подключить";
    }

    private String dpiStatusLine() {
        if (!ColgramConfig.isDpiBypassEnabled()) return "выключен";
        if (!ColgramDpiBypass.isBound()) return "локальный транспорт ещё не запущен";
        ColgramProxyManager.ProxyItem route = ColgramProxyManager.getCurrentActiveProxy();
        if (route == null || !route.isLocalDpi()) return "локальный транспорт не выбран";
        if (ColgramDpiBypass.bytesFromTarget() > 0 && ConnectionsManager.getInstance(currentAccount).getConnectionState()
                == ConnectionsManager.ConnectionStateConnected) return "подключён к Telegram";
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
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle("Релей для WARP");
        builder.setMessage("Нужен, если UDP Cloudflare заблокирован: релея держите сами, "
                + "он принимает WireGuard по UDP и проксирует в Cloudflare. "
                + "Запускайте его с ключом --udp-listen: порт отсюда должен быть UDP, "
                + "иначе туннель не поднимется.");
        builder.setView(layout);
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
        builder.show();
    }

    private android.widget.EditText relayField(LinearLayout layout, String hint, String value) {
        android.widget.EditText input = new android.widget.EditText(getContext());
        input.setHint(hint);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
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
                   position == antiDeleteRow || position == antiDeleteHighlightRow || position == antiDeleteWipeRow || position == preserveMediaRow || position == editHistoryRow || position == autoHidePhoneRow ||
                   position == ghostReadRow || position == ghostTypingRow || position == ghostOnlineRow || position == bypassFlagSecureRow ||
                   position == dpiBypassRow || position == dohRow || position == builtinProxyRow || position == proxyBrowserRow || position == currentProxyRow || position == ipv6BypassRow || position == dcRemapRow ||
                   position == ownProxyRow || position == proxyStatusRow || position == relayUrlRow || position == autoProxyRow ||
                   position == sandboxStorageRow || position == sandboxFilesRow ||
                   position == botRealtimeRow || position == botTestRow || position == pluginsRow ||
                   position == cloudflareWarpRow || position == subscriptionRow || position == directConnectionRow
                   || position == publicProxyRow || position == subscriptionNodeRow || position == callsRow
                   || position == dataSettingsRow || position == cacheSettingsRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == cloakingHeaderRow || position == vaultHeaderRow || position == ghostHeaderRow ||
                position == networkHeaderRow || position == resourceHeaderRow || position == sandboxHeaderRow ||
                position == botsHeaderRow) {
                return 0; // HeaderCell
            } else if (position == cloakModelRow || position == currentProxyRow
                    || position == ownProxyRow || position == proxyStatusRow || position == relayUrlRow
                    || position == sandboxFilesRow || position == pluginsRow || position == botTestRow
                    || position == cloudflareWarpRow || position == subscriptionRow || position == directConnectionRow
                    || position == publicProxyRow || position == subscriptionNodeRow || position == callsRow
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
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == cloakingHeaderRow) {
                        headerCell.setText("Маскировка устройства (MTProto Cloaking)");
                    } else if (position == vaultHeaderRow) {
                        headerCell.setText("Сейф сообщений (Anti-Delete)");
                    } else if (position == ghostHeaderRow) {
                        headerCell.setText("Режим призрака (Ghost Mode)");
                    } else if (position == networkHeaderRow) {
                        headerCell.setText("Сеть и анонимность (Анти-ТСПУ)");
                    } else if (position == resourceHeaderRow) {
                        headerCell.setText("Трафик и память");
                    } else if (position == sandboxHeaderRow) {
                        headerCell.setText("Песочница файлов (Sandbox)");
                    } else if (position == botsHeaderRow) {
                        headerCell.setText("Бот-аккаунты");
                    }
                    break;
                }
                case 1: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == cloakEnabledRow) {
                        checkCell.setTextAndCheck("Маскировка модели и системы", ColgramConfig.isCloakEnabled(), true);
                    } else if (position == antiDeleteRow) {
                        checkCell.setTextAndCheck("Анти-удаление сообщений (значок 🗑)", ColgramConfig.isAntiDeleteEnabled(), true);
                    } else if (position == preserveMediaRow) {
                        checkCell.setTextAndCheck("Защита медиафайлов от удаления", ColgramConfig.isPreserveMediaEnabled(), true);
                    } else if (position == editHistoryRow) {
                        checkCell.setTextAndCheck("Сохранять историю редакций текста", ColgramConfig.isEditHistoryEnabled(), true);
                    } else if (position == antiDeleteHighlightRow) {
                        checkCell.setTextAndCheck("Показывать метку «Удалено»", ColgramConfig.isAntiDeleteHighlightEnabled(), true);
                    } else if (position == antiDeleteWipeRow) {
                        checkCell.setTextAndCheck("Стирать текст удалённых (иначе — архив)", ColgramConfig.isAntiDeleteWipeEnabled(), true);
                    } else if (position == autoHidePhoneRow) {
                        checkCell.setTextAndCheck("Скрывать номер телефона при входе", ColgramConfig.isAutoHidePhoneEnabled(), false);
                    } else if (position == ghostReadRow) {
                        checkCell.setTextAndCheck("Не отправлять отчет о прочтении", ColgramConfig.isGhostReadEnabled(), true);
                    } else if (position == ghostTypingRow) {
                        checkCell.setTextAndCheck("Скрывать «печатает...» и запись аудио", ColgramConfig.isGhostTypingEnabled(), true);
                    } else if (position == ghostOnlineRow) {
                        checkCell.setTextAndCheck("Скрывать онлайн статус", ColgramConfig.isGhostOnlineEnabled(), true);
                    } else if (position == bypassFlagSecureRow) {
                        checkCell.setTextAndCheck("Разрешить скриншоты везде (FLAG_SECURE)", ColgramConfig.isBypassFlagSecureEnabled(), false);
                    } else if (position == autoProxyRow) {
                        checkCell.setTextAndCheck("Самоподключение прокси, если Telegram не отвечает",
                                ColgramConfig.isAutoProxyEnabled(), true);
                    } else if (position == dpiBypassRow) {
                        // Read the persisted intent, not just whether a socket happens to be
                        // bound: a listener that failed to bind is a fault, not a setting.
                        checkCell.setTextAndValueAndCheck("Локальный обход", dpiStatusLine(),
                                ColgramConfig.isDpiBypassEnabled(), true, true);
                    } else if (position == dohRow) {
                        checkCell.setTextAndCheck("DNS-over-HTTPS (шифрованный DNS)", ColgramConfig.isDohEnabled(), true);
                    } else if (position == builtinProxyRow) {
                        checkCell.setTextAndCheck("Обновлять каталог MTProto / SOCKS5", ColgramConfig.isBuiltinProxyEnabled(), true);
                    } else if (position == proxyBrowserRow) {
                        checkCell.setTextAndCheck("Открывать ссылки в защищенном браузере", ColgramConfig.isProxyBrowserEnabled(), true);
                    } else if (position == sandboxStorageRow) {
                        checkCell.setTextAndCheck("Изолировать файлы в папке Colgram", ColgramConfig.isSandboxStorageEnabled(), false);
                    } else if (position == dcRemapRow) {
                        checkCell.setTextAndCheck(
                                "Обход без прокси: звонить на доступный адрес Telegram",
                                ColgramConfig.isDcRemapEnabled(), false);
                    } else if (position == ipv6BypassRow) {
                        checkCell.setTextAndCheck("Обход по IPv6 без прокси",
                                ColgramConfig.isIpv6BypassEnabled(), false);
                    } else if (position == botRealtimeRow) {
                        checkCell.setTextAndCheck("Получать сообщения бота в реальном времени",
                                !ColgramBotSync.isPassiveBotMode(getContext(), currentAccount), true);
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                    if (position == cloakModelRow) {
                        settingsCell.setTextAndValue("Модель устройства", ColgramConfig.getSpoofDeviceModel(), false);
                    } else if (position == currentProxyRow) {
                        ColgramProxyManager.ProxyItem active = ColgramProxyManager.getCurrentActiveProxy();
                        // Automatic failover deliberately keeps Telegram's persisted proxy
                        // preference off. The live route still needs to be shown as active.
                        boolean proxyOn = ColgramProxyManager.isProxyEnabled(getContext())
                                || active != null;
                        String proxyStr = !proxyOn
                                ? "прокси выключен"
                                : (active != null ? active.toString() : "включено, узел не определён");
                        settingsCell.setTextAndValue("Выбрать публичный прокси", proxyStr, false);
                    } else if (position == ownProxyRow) {
                        settingsCell.setTextAndValue("Свой прокси: MTProto / WebSocket / SOCKS5",
                                (ColgramProxyManager.isProxyEnabled(getContext())
                                        || ColgramProxyManager.getCurrentActiveProxy() != null)
                                        ? "открыть экран Telegram" : "добавить и включить", false);
                    } else if (position == sandboxFilesRow) {
                        // Show the path that is actually in effect: scoped storage decides it, so
                        // "Documents/Colgram" is only true when the system let us write there.
                        settingsCell.setTextAndValue("Папка Colgram",
                                ColgramStorageSandbox.getRootPath(getContext()), false);
                    } else if (position == pluginsRow) {
                        settingsCell.setTextAndValue("Плагины: автоответчик, алерты, лог",
                                "открыть", false);
                    } else if (position == relayUrlRow) {
                        String relay = ColgramConfig.getRelayUrl();
                        settingsCell.setTextAndValue("Реле-адрес для Bot API и почты",
                                relay.isEmpty() ? "не задан" : relay, true);
                    } else if (position == botTestRow) {
                        settingsCell.setTextAndValue("Проверка Bot API (имя и описание)",
                                botTestSummary(), true);
                    } else if (position == proxyStatusRow) {
                        settingsCell.setTextAndValue("Состояние прокси (нажмите для проверки)",
                                ColgramProxyDoctor.getStatusSummary(), false);
                    } else if (position == directConnectionRow) {
                        String subscriptionState = subscriptionTunnelStatus(getContext());
                        boolean vpnActive = isAndroidVpnActive();
                        boolean direct = !ColgramConfig.isWarpEnabled() && !ColgramConfig.isDpiBypassEnabled()
                                && !ColgramProxyManager.hasActiveRouteForUi() && !ColgramProxyManager.isDcRemapActive()
                                && !ColgramConfig.isIpv6BypassEnabled() && !ColgramConfig.isAutoProxyEnabled()
                                && !ColgramProxyManager.isProxySelectionPending() && !vpnActive
                                && !"running".equals(subscriptionState) && !"starting".equals(subscriptionState)
                                && !"stopping".equals(subscriptionState) && !"consent".equals(subscriptionState);
                        settingsCell.setTextAndValue("Обычный интернет (без прокси и VPN)",
                                direct ? "выбран" : vpnActive ? "активен Android VPN" : "нажмите для переключения", true);
                    } else if (position == publicProxyRow) {
                        String status = ColgramProxyManager.getProxySelectionStatus();
                        settingsCell.setTextAndValue("Найти рабочий публичный прокси",
                                ColgramProxyManager.isProxySelectionPending() ? "проверяется · нажмите для отмены"
                                        : status == null || status.isEmpty() ? "проверить" : status, true);
                    } else if (position == subscriptionNodeRow) {
                        settingsCell.setTextAndValue("Узел / страна из подписки",
                                subscription.nodeLabel(), true);
                    } else if (position == callsRow) {
                        settingsCell.setTextAndValue("Использовать для звонков", ColgramCallRoutes.summary(), true);
                    } else if (position == dataSettingsRow) {
                        settingsCell.setTextAndValue("Экономия трафика и автозагрузка", "настроить", true);
                    } else if (position == cacheSettingsRow) {
                        settingsCell.setTextAndValue("Кэш: размер и срок хранения", "настроить", false);
                    } else if (position == cloudflareWarpRow) {
                        settingsCell.setTextAndValue("Cloudflare WARP (встроенный обход)",
                                warpStatusLine(), false);
                    } else if (position == subscriptionRow) {
                        // The row says what is actually stored, never what the user hopes for:
                        // a link that parsed to nothing shows as absent, not as a tunnel waiting
                        // to start.
                        settingsCell.setTextAndValue("Подписка (VPN на весь телефон)",
                                subscriptionStatusLine(), false);
                    }
                    break;
                }
            }
        }
    }
}
