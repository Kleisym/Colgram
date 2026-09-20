package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramDpiBypass;
import org.colgram.core.ColgramProxyManager;
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

    private int themeHeaderRow;
    private int cyberThemeRow;
    private int themeSectionRow;

    private int networkHeaderRow;
    private int dpiBypassRow;
    private int dohRow;
    private int builtinProxyRow;
    private int proxyBrowserRow;
    private int currentProxyRow;
    private int networkSectionRow;

    private int sandboxHeaderRow;
    private int sandboxStorageRow;
    private int sandboxSectionRow;

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

        themeHeaderRow = rowCount++;
        cyberThemeRow = rowCount++;
        themeSectionRow = rowCount++;

        networkHeaderRow = rowCount++;
        dpiBypassRow = rowCount++;
        dohRow = rowCount++;
        builtinProxyRow = rowCount++;
        proxyBrowserRow = rowCount++;
        currentProxyRow = rowCount++;
        networkSectionRow = rowCount++;

        sandboxHeaderRow = rowCount++;
        sandboxStorageRow = rowCount++;
        sandboxSectionRow = rowCount++;

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
            } else if (position == cyberThemeRow) {
                boolean val = !ColgramConfig.isCyberThemeEnabled();
                ColgramConfig.setCyberThemeEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
                Toast.makeText(getParentActivity(), "Тема изменена. Перезайдите на экран для обновления.", Toast.LENGTH_SHORT).show();
            } else if (position == dpiBypassRow) {
                if (ColgramDpiBypass.isRunning()) {
                    ColgramDpiBypass.stop();
                    Toast.makeText(getParentActivity(), "Обходчик ТСПУ остановлен", Toast.LENGTH_SHORT).show();
                } else {
                    ColgramDpiBypass.start();
                    Toast.makeText(getParentActivity(), "Обходчик ТСПУ запущен (127.0.0.1:9876)", Toast.LENGTH_SHORT).show();
                }
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ColgramDpiBypass.isRunning());
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
                ColgramProxyManager.switchToNextProxy();
                listAdapter.notifyItemChanged(currentProxyRow);
            } else if (position == sandboxStorageRow) {
                boolean val = !ColgramConfig.isSandboxStorageEnabled();
                ColgramConfig.setSandboxStorageEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
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
                   position == cyberThemeRow || position == dpiBypassRow || position == dohRow || position == builtinProxyRow || position == proxyBrowserRow || position == currentProxyRow ||
                   position == sandboxStorageRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == cloakingHeaderRow || position == vaultHeaderRow || position == ghostHeaderRow ||
                position == themeHeaderRow || position == networkHeaderRow || position == sandboxHeaderRow) {
                return 0; // HeaderCell
            } else if (position == cloakModelRow || position == currentProxyRow) {
                return 2; // TextSettingsCell
            } else if (position == cloakingSectionRow || position == vaultSectionRow || position == ghostSectionRow ||
                       position == themeSectionRow || position == networkSectionRow || position == sandboxSectionRow) {
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
                    } else if (position == themeHeaderRow) {
                        headerCell.setText("Оформление");
                    } else if (position == networkHeaderRow) {
                        headerCell.setText("Сеть и анонимность (Анти-ТСПУ)");
                    } else if (position == sandboxHeaderRow) {
                        headerCell.setText("Песочница файлов (Sandbox)");
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
                    } else if (position == cyberThemeRow) {
                        checkCell.setTextAndCheck("Красно-чёрная тема Colgram Cyber", ColgramConfig.isCyberThemeEnabled(), false);
                    } else if (position == dpiBypassRow) {
                        checkCell.setTextAndCheck("Обходчик ТСПУ (TCP Desync / 127.0.0.1)", ColgramDpiBypass.isRunning(), true);
                    } else if (position == dohRow) {
                        checkCell.setTextAndCheck("DNS-over-HTTPS (шифрованный DNS)", ColgramConfig.isDohEnabled(), true);
                    } else if (position == builtinProxyRow) {
                        checkCell.setTextAndCheck("Встроенный пул Fake-TLS MTProto", ColgramConfig.isBuiltinProxyEnabled(), true);
                    } else if (position == proxyBrowserRow) {
                        checkCell.setTextAndCheck("Открывать ссылки в защищенном браузере", ColgramConfig.isProxyBrowserEnabled(), true);
                    } else if (position == sandboxStorageRow) {
                        checkCell.setTextAndCheck("Изолировать файлы в Documents/Colgram", ColgramConfig.isSandboxStorageEnabled(), false);
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                    if (position == cloakModelRow) {
                        settingsCell.setTextAndValue("Модель устройства", ColgramConfig.getSpoofDeviceModel(), false);
                    } else if (position == currentProxyRow) {
                        org.colgram.core.ColgramProxyManager.ProxyItem active = ColgramProxyManager.getCurrentActiveProxy();
                        String proxyStr = active != null ? active.address : "Автовыбор (Нажмите для смены)";
                        settingsCell.setTextAndValue("Сменить прокси / обходник", proxyStr, false);
                    }
                    break;
                }
            }
        }
    }
}
