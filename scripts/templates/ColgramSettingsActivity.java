package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramBotSync;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramProxyDoctor;
import org.colgram.core.ColgramProxyManager;
import org.colgram.core.ColgramStorageSandbox;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
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

    private int networkHeaderRow;
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
            if (resultCode == android.app.Activity.RESULT_OK) {
                // The user granted VPN consent; the tunnel can actually come up now.
                startWarpTunnel();
            } else if (getContext() != null) {
                Toast.makeText(getContext(),
                        "Без разрешения на VPN WARP-туннель не включается", Toast.LENGTH_LONG).show();
            }
            return;
        }
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
        cloudflareWarpRow = rowCount++;

        networkHeaderRow = dpiBypassRow = dohRow = builtinProxyRow = proxyBrowserRow =
                currentProxyRow = ownProxyRow = proxyStatusRow = relayUrlRow = autoProxyRow =
                ipv6BypassRow = dcRemapRow = networkSectionRow = -1;

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
            if (position == cloudflareWarpRow) {
                openCloudflareWarp();
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
                // Persist + route + run, in one call. This row used to only start or stop the
                // listener, so the choice was lost on restart and the toggle could show
                // "running" while tgnet was not routed through it at all.
                ColgramProxyManager.setDpiBypassEnabled(getContext(),
                        !ColgramConfig.isDpiBypassEnabled());
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ColgramConfig.isDpiBypassEnabled());
                }
                // The listener binds on a background thread and the rows below describe it, so
                // refresh the whole list rather than leaving a stale "выключено" next to a
                // toggle that was just switched on.
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
                // force: the user asked for a different node, so the two-failure rule that
                // protects against flapping proxies must not hold them on the current one.
                ColgramProxyManager.switchToNextProxy(true);
                listAdapter.notifyDataSetChanged();
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

        return fragmentView;
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
        Context context = getContext();
        android.app.Activity activity = getParentActivity();
        if (context == null || activity == null) {
            // Returning here used to leave the persisted flag set with nothing running behind
            // it, which is exactly what the user saw: the switch on, the row saying connected,
            // and no traffic. Leave nothing half-applied on the way out.
            org.colgram.core.ColgramConfig.setWarpEnabled(false);
            return;
        }
        if (!org.colgram.core.ColgramWarpTunnel.isBackendAvailable()) {
            Toast.makeText(context, "WireGuard-бэкенд недоступен в этой сборке", Toast.LENGTH_LONG).show();
            org.colgram.core.ColgramConfig.setWarpEnabled(false);
            org.colgram.core.ColgramWarpTunnel.clearFailure();
            listAdapter.notifyDataSetChanged();
            return;
        }
        if (org.colgram.core.ColgramConfig.isWarpEnabled()) {
            org.colgram.core.ColgramConfig.setWarpEnabled(false);
            org.colgram.core.ColgramWarpTunnel.bringDown(context);
            Toast.makeText(context, "WARP отключён", Toast.LENGTH_SHORT).show();
            listAdapter.notifyDataSetChanged();
            return;
        }
        if (!org.colgram.core.ColgramWarp.isRegistered()) {
            Toast.makeText(context, "Регистрирую устройство в WARP…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String reason;
                try {
                    org.colgram.core.ColgramWarp.register(context);
                    reason = null;
                } catch (Throwable t) {
                    reason = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String failure = reason;
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> {
                    if (failure == null) {
                        Toast.makeText(context, "WARP: устройство зарегистрировано", Toast.LENGTH_SHORT).show();
                        if (getParentActivity() != null) prepareAndStartWarp();
                    } else {
                        org.colgram.core.ColgramConfig.setWarpEnabled(false);
                        Toast.makeText(context, "WARP: регистрация не удалась — " + failure,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }, "colgram-warp-reg").start();
            return;
        }
        prepareAndStartWarp();
    }

    /** Android requires the one-time VPN consent dialog before a tunnel can come up. */
    private void prepareAndStartWarp() {
        try {
            Intent prepare = android.net.VpnService.prepare(getParentActivity());
            if (prepare != null) {
                startActivityForResult(prepare, REQ_WARP_VPN);
            } else {
                startWarpTunnel();
            }
        } catch (Throwable t) {
            Toast.makeText(getContext(), "WARP: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startWarpTunnel() {
        final Context context = getContext();
        if (context == null) return;
        Toast.makeText(context, "Поднимаю WARP-туннель…", Toast.LENGTH_SHORT).show();
        // Watch the tunnel for the same verdict the watchdog records, so a route that cannot
        // carry traffic reports itself instead of leaving a toggle that silently switches off.
        watchWarpVerdict(context);
        new Thread(() -> {
            String failure;
            try {
                org.colgram.core.ColgramWarpTunnel.bringUp(context.getApplicationContext());
                failure = null;
            } catch (Throwable t) {
                failure = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final String result = failure;
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> {
                if (result == null) {
                    // The flag is written here, after the tunnel is actually up, rather than when
                    // the row was tapped. Written on tap, every refusal below - a declined VPN
                    // consent, a missing backend, a killed screen - left it on with nothing
                    // behind it, and that half-applied state is what a user cannot switch off.
                    org.colgram.core.ColgramConfig.setWarpEnabled(true);
                    Toast.makeText(context, "WARP запускается; проверяю связь (порт "
                            + org.colgram.core.ColgramWarp.currentEndpointPort() + ")",
                            Toast.LENGTH_SHORT).show();
                } else {
                    org.colgram.core.ColgramConfig.setWarpEnabled(false);
                    Toast.makeText(context, "WARP не поднялся — " + result, Toast.LENGTH_LONG).show();
                }
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
            });
        }, "colgram-warp-up").start();
    }

    /** Polls until WARP either starts carrying traffic or the watchdog gives up on the route. */
    private void watchWarpVerdict(final Context context) {
        new Thread(() -> {
            long deadline = android.os.SystemClock.elapsedRealtime()
                    + (org.colgram.core.ColgramWarp.endpointPortCount() * 8L + 20L) * 1000L;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (org.colgram.core.ColgramWarpTunnel.isConnected()) {
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(() -> {
                        Toast.makeText(context, "WARP подключён", Toast.LENGTH_SHORT).show();
                        if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    });
                    return;
                }
                String failure = org.colgram.core.ColgramWarpTunnel.lastFailureReason();
                if (failure != null && !failure.isEmpty()
                        && !org.colgram.core.ColgramWarpTunnel.isUp()) {
                    final String reason = failure;
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(() -> {
                        Toast.makeText(context, "WARP не работает: " + reason,
                                Toast.LENGTH_LONG).show();
                        if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    });
                    return;
                }
            }
        }, "colgram-warp-verdict").start();
    }

    private String warpStatusLine() {
        if (!org.colgram.core.ColgramWarpTunnel.isBackendAvailable()) return "бэкенд недоступен";
        String failure = org.colgram.core.ColgramWarpTunnel.lastFailureReason();
        if (failure != null && !failure.isEmpty()
                && !org.colgram.core.ColgramWarpTunnel.isUp()) {
            return "не работает: " + failure;
        }
        if (org.colgram.core.ColgramWarpTunnel.isUp()) {
            return (org.colgram.core.ColgramWarpTunnel.isConnected() ? "подключен · порт " : "подключается · порт ")
                    + org.colgram.core.ColgramWarp.currentEndpointPort();
        }
        if (org.colgram.core.ColgramWarp.isRegistered()) return "выключен · нажмите, чтобы включить";
        return "выключен · нажмите, чтобы подключить";
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
                   position == cloudflareWarpRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == cloakingHeaderRow || position == vaultHeaderRow || position == ghostHeaderRow ||
                position == networkHeaderRow || position == sandboxHeaderRow ||
                position == botsHeaderRow) {
                return 0; // HeaderCell
            } else if (position == cloakModelRow || position == currentProxyRow
                    || position == ownProxyRow || position == proxyStatusRow || position == relayUrlRow
                    || position == sandboxFilesRow || position == pluginsRow || position == botTestRow
                    || position == cloudflareWarpRow) {
                return 2; // TextSettingsCell
            } else if (position == cloakingSectionRow || position == vaultSectionRow || position == ghostSectionRow ||
                       position == networkSectionRow || position == sandboxSectionRow ||
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
                        checkCell.setTextAndCheck("Обходчик ТСПУ (TCP Desync / 127.0.0.1)",
                                ColgramConfig.isDpiBypassEnabled(), true);
                    } else if (position == dohRow) {
                        checkCell.setTextAndCheck("DNS-over-HTTPS (шифрованный DNS)", ColgramConfig.isDohEnabled(), true);
                    } else if (position == builtinProxyRow) {
                        checkCell.setTextAndCheck("Встроенный пул Fake-TLS MTProto", ColgramConfig.isBuiltinProxyEnabled(), true);
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
                                ? "выключено — прямое соединение"
                                : (active != null ? active.toString() : "включено, узел не определён");
                        settingsCell.setTextAndValue("Сменить узел обходчика", proxyStr, false);
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
                    } else if (position == cloudflareWarpRow) {
                        settingsCell.setTextAndValue("Cloudflare WARP (встроенный обход)",
                                warpStatusLine(), false);
                    }
                    break;
                }
            }
        }
    }
}
