package org.telegram.ui;

import android.content.Context;
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

    private int networkHeaderRow;
    private int dpiBypassRow;
    private int dohRow;
    private int builtinProxyRow;
    private int proxyBrowserRow;
    private int currentProxyRow;
    private int ownProxyRow;
    private int proxyStatusRow;
    private int networkSectionRow;

    private int sandboxHeaderRow;
    private int sandboxStorageRow;
    private int sandboxFilesRow;
    private int sandboxSectionRow;

    private int botsHeaderRow;
    private int botRealtimeRow;
    private int pluginsRow;
    private int botsSectionRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        ColgramConfig.init(getParentActivity());
        updateRows();
        return true;
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
        dpiBypassRow = rowCount++;
        dohRow = rowCount++;
        builtinProxyRow = rowCount++;
        proxyBrowserRow = rowCount++;
        currentProxyRow = rowCount++;
        ownProxyRow = rowCount++;
        proxyStatusRow = rowCount++;
        networkSectionRow = rowCount++;

        sandboxHeaderRow = rowCount++;
        sandboxStorageRow = rowCount++;
        sandboxFilesRow = rowCount++;
        sandboxSectionRow = rowCount++;

        botsHeaderRow = rowCount++;
        botRealtimeRow = rowCount++;
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
            if (position == cloakEnabledRow) {
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
                   position == dpiBypassRow || position == dohRow || position == builtinProxyRow || position == proxyBrowserRow || position == currentProxyRow ||
                   position == ownProxyRow || position == proxyStatusRow ||
                   position == sandboxStorageRow || position == sandboxFilesRow ||
                   position == botRealtimeRow || position == pluginsRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == cloakingHeaderRow || position == vaultHeaderRow || position == ghostHeaderRow ||
                position == networkHeaderRow || position == sandboxHeaderRow ||
                position == botsHeaderRow) {
                return 0; // HeaderCell
            } else if (position == cloakModelRow || position == currentProxyRow
                    || position == ownProxyRow || position == proxyStatusRow
                    || position == sandboxFilesRow || position == pluginsRow) {
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
                        checkCell.setTextAndCheck("Помечать удалённые значком 🗑", ColgramConfig.isAntiDeleteHighlightEnabled(), true);
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
                        boolean proxyOn = ColgramProxyManager.isProxyEnabled(getContext());
                        String proxyStr = !proxyOn
                                ? "выключено — прямое соединение"
                                : (active != null ? active.toString() : "включено, узел не определён");
                        settingsCell.setTextAndValue("Сменить узел обходчика", proxyStr, false);
                    } else if (position == ownProxyRow) {
                        settingsCell.setTextAndValue("Свой прокси: MTProto / WebSocket / SOCKS5",
                                ColgramProxyManager.isProxyEnabled(getContext())
                                        ? "открыть экран Telegram" : "добавить и включить", false);
                    } else if (position == sandboxFilesRow) {
                        // Show the path that is actually in effect: scoped storage decides it, so
                        // "Documents/Colgram" is only true when the system let us write there.
                        settingsCell.setTextAndValue("Папка Colgram",
                                ColgramStorageSandbox.getRootPath(getContext()), false);
                    } else if (position == pluginsRow) {
                        settingsCell.setTextAndValue("Плагины: автоответчик, алерты, лог",
                                "открыть", false);
                    } else if (position == proxyStatusRow) {
                        settingsCell.setTextAndValue("Состояние прокси (нажмите для проверки)",
                                ColgramProxyDoctor.getStatusSummary(), false);
                    }
                    break;
                }
            }
        }
    }
}
