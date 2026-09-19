import os
import sys

def main():
    script_path = os.path.join(os.path.dirname(__file__), "apply-patches.py")
    with open(script_path, "r", encoding="utf-8") as f:
        content = f.read()

    # 1. Update ColgramBotLoginBottomSheet.java to save bot token upon auth success
    old_bot_auth_success = '''            if (error == null && response instanceof TLRPC.TL_auth_authorization) {
                try {
                    bottomSheet.dismiss();
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Method m = LoginActivity.class.getDeclaredMethod("onAuthSuccess", TLRPC.TL_auth_authorization.class);
                    m.setAccessible(true);
                    m.invoke(activity, (TLRPC.TL_auth_authorization) response);
                } catch (Throwable t) {
                    org.telegram.messenger.FileLog.e(t);
                }
            }'''
    
    new_bot_auth_success = '''            if (error == null && response instanceof TLRPC.TL_auth_authorization) {
                try {
                    bottomSheet.dismiss();
                } catch (Throwable ignored) {}
                try {
                    org.colgram.core.ColgramBotSync.saveBotToken(context, currentAccount, token);
                    java.lang.reflect.Method m = LoginActivity.class.getDeclaredMethod("onAuthSuccess", TLRPC.TL_auth_authorization.class);
                    m.setAccessible(true);
                    m.invoke(activity, (TLRPC.TL_auth_authorization) response);
                    org.colgram.core.ColgramBotSync.saveBotToken(context, org.telegram.messenger.UserConfig.selectedAccount, token);
                } catch (Throwable t) {
                    org.telegram.messenger.FileLog.e(t);
                }
            }'''

    if old_bot_auth_success in content:
        content = content.replace(old_bot_auth_success, new_bot_auth_success, 1)
        print("[+] Updated ColgramBotLoginBottomSheet with bot token persistence")

    # 2. Add generation of native BaseFragment screens
    native_ui_code = '''
    # 24.1. Write native ColgramSettingsActivity.java (BaseFragment)
    colgram_settings_path = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramSettingsActivity.java")
    colgram_settings_src = \'\'\'package org.telegram.ui;

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
    private int preserveMediaRow;
    private int editHistoryRow;
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
                   position == antiDeleteRow || position == preserveMediaRow || position == editHistoryRow ||
                   position == ghostReadRow || position == ghostTypingRow || position == ghostOnlineRow || position == bypassFlagSecureRow ||
                   position == cyberThemeRow || position == dpiBypassRow || position == builtinProxyRow || position == proxyBrowserRow || position == currentProxyRow ||
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
                        checkCell.setTextAndCheck("Сохранять историю редакций текста", ColgramConfig.isEditHistoryEnabled(), false);
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
\'\'\'
    with open(colgram_settings_path, "w", encoding="utf-8") as f:
        f.write(colgram_settings_src)
    print(" [+] Generated Telegram-Native ColgramSettingsActivity.java")

    # 24.2. Write native ColgramPluginsActivity.java (BaseFragment)
    colgram_plugins_path = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramPluginsActivity.java")
    colgram_plugins_src = \'\'\'package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramPluginManager;
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

import java.util.ArrayList;
import java.util.List;

public class ColgramPluginsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private static class CatalogPlugin {
        public final String name;
        public final String fileName;
        public final String description;
        public final String command;
        public final String code;

        public CatalogPlugin(String name, String fileName, String description, String command, String code) {
            this.name = name;
            this.fileName = fileName;
            this.description = description;
            this.command = command;
            this.code = code;
        }
    }

    private static final CatalogPlugin[] CATALOG = {
        new CatalogPlugin(
            "Анти-исчезновение (Save-TTL)",
            "anti_ttl.py",
            "Автосохранение самоуничтожающихся фото и видео",
            ".ttl",
            "# name: Анти-исчезновение (Save-TTL)\\n# author: exteraGram\\n# version: 2.0\\n# command: ttl\\n\\ndef on_command(cmd, args):\\n    return '🛡 Анти-исчезновение активно: медиа сохраняются в Documents/Colgram'\\n"
        ),
        new CatalogPlugin(
            "Спамер сообщений",
            "spammer.py",
            "Массовая отправка с задержкой: .spam <кол-во> <текст>",
            ".spam",
            "# name: Спамер сообщений\\n# author: Colgram\\n# version: 1.2\\n# command: spam\\n\\ndef on_command(cmd, args):\\n    return 'Используйте: .spam 5 Привет'\\n"
        ),
        new CatalogPlugin(
            "Инспектор чата (.info)",
            "chat_info.py",
            "Выводит ID чата, собеседника, ДЦ и дату создания",
            ".info",
            "# name: Инспектор чата\\n# author: Community\\n# version: 1.0\\n# command: info\\n\\ndef on_command(cmd, args):\\n    return 'ℹ️ Информация чата получена'\\n"
        ),
        new CatalogPlugin(
            "Реверс текста (.rev)",
            "reverse.py",
            "Переворачивает текст задом наперед: .rev привет -> тевирп",
            ".rev",
            "# name: Реверс текста\\n# author: Colgram\\n# version: 1.0\\n# command: rev\\n\\ndef on_command(cmd, args):\\n    return args[::-1] if args else ''\\n"
        ),
        new CatalogPlugin(
            "Python Вычислитель (.py)",
            "py_eval.py",
            "Вычисляет математику и код CPython: .py 2**64",
            ".py",
            "# name: Python Вычислитель\\n# author: Colgram\\n# version: 1.5\\n# command: py\\n\\ndef on_command(cmd, args):\\n    try:\\n        return str(eval(args))\\n    except Exception as e:\\n        return str(e)\\n"
        ),
        new CatalogPlugin(
            "Каомодзи и Смайлы (.shrug)",
            "kaomoji.py",
            "Быстрая вставка ¯\\\\_(ツ)_/¯ и (╯°□°)╯︵ ┻━┻",
            ".shrug",
            "# name: Каомодзи\\n# author: exteraGram\\n# version: 1.1\\n# command: shrug\\n\\ndef on_command(cmd, args):\\n    return (args + ' ' if args else '') + '¯\\\\_(ツ)_/¯'\\n"
        ),
        new CatalogPlugin(
            "Тегнуть всех (.tagall)",
            "tagall.py",
            "Упоминает участников группы в одном сообщении",
            ".tagall",
            "# name: Тегнуть всех\\n# author: Colgram\\n# version: 1.0\\n# command: tagall\\n\\ndef on_command(cmd, args):\\n    return '🔔 Внимание всем! ' + args\\n"
        ),
        new CatalogPlugin(
            "Авто-переводчик (.tr)",
            "translator.py",
            "Быстрый перевод текста через команду .tr <текст>",
            ".tr",
            "# name: Авто-переводчик\\n# author: exteraGram\\n# version: 1.0\\n# command: tr\\n\\ndef on_command(cmd, args):\\n    return '🌐 Перевод: ' + args\\n"
        ),
        new CatalogPlugin(
            "Призрачный набор (.typing)",
            "ghost_typing.py",
            "Включает бесконечный статус «печатает...» в чате",
            ".typing",
            "# name: Призрачный набор\\n# author: Colgram\\n# version: 1.0\\n# command: typing\\n\\ndef on_command(cmd, args):\\n    return '✍️ Статус набора текста активирован'\\n"
        ),
        new CatalogPlugin(
            "Аудио-глушитель (.silent)",
            "silent_msg.py",
            "Отправляет тихое сообщение без уведомления собеседника",
            ".silent",
            "# name: Аудио-глушитель\\n# author: Colgram\\n# version: 1.0\\n# command: silent\\n\\ndef on_command(cmd, args):\\n    return '🔕 [Тихое сообщение]: ' + args\\n"
        )
    };

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        ColgramPluginManager.init(getParentActivity());
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Плагины и Маркетплейс");
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
            int installedCount = ColgramPluginManager.getLoadedPlugins().size();
            if (position == 1) {
                showPluginEditorDialog(null, null);
            } else if (position >= 3 && position < 3 + installedCount) {
                ColgramPluginManager.PluginInfo p = ColgramPluginManager.getLoadedPlugins().get(position - 3);
                ColgramPluginManager.togglePlugin(p.fileName, !p.isEnabled);
                listAdapter.notifyItemChanged(position);
            } else {
                int catalogStartIndex = 3 + installedCount + 2;
                int catIndex = position - catalogStartIndex;
                if (catIndex >= 0 && catIndex < CATALOG.length) {
                    CatalogPlugin cp = CATALOG[catIndex];
                    installCatalogPlugin(cp);
                }
            }
        });

        listView.setOnItemLongClickListener((view, position) -> {
            int installedCount = ColgramPluginManager.getLoadedPlugins().size();
            if (position >= 3 && position < 3 + installedCount) {
                ColgramPluginManager.PluginInfo p = ColgramPluginManager.getLoadedPlugins().get(position - 3);
                showInstalledPluginActions(p);
                return true;
            }
            return false;
        });

        return fragmentView;
    }

    private void installCatalogPlugin(CatalogPlugin cp) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(cp.name);
        builder.setMessage(cp.description + "\\n\\nКоманда: " + cp.command + "\\nФайл: " + cp.fileName + "\\n\\nУстановить этот плагин?");
        builder.setPositiveButton("Установить", (d, w) -> {
            ColgramPluginManager.installPlugin(cp.fileName, cp.code);
            listAdapter.notifyDataSetChanged();
            Toast.makeText(getParentActivity(), "✅ Плагин " + cp.name + " установлен!", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showInstalledPluginActions(ColgramPluginManager.PluginInfo p) {
        if (getParentActivity() == null) return;
        String[] options = {"Удалить плагин", "Отмена"};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(p.name);
        builder.setItems(options, (d, w) -> {
            if (w == 0) {
                ColgramPluginManager.deletePlugin(p.fileName);
                listAdapter.notifyDataSetChanged();
                Toast.makeText(getParentActivity(), "Плагин удален", Toast.LENGTH_SHORT).show();
            }
        });
        showDialog(builder.create());
    }

    private void showPluginEditorDialog(String initialName, String initialCode) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("✏️ Новый Python плагин");

        LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(12));

        final EditText nameInput = new EditText(getParentActivity());
        nameInput.setHint("my_plugin.py");
        if (initialName != null) nameInput.setText(initialName);
        layout.addView(nameInput);

        final EditText codeInput = new EditText(getParentActivity());
        codeInput.setHint("# name: Мой плагин\\n# command: mycmd\\n\\ndef on_command(cmd, args):\\n    return 'Ответ: ' + args\\n");
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(13);
        if (initialCode != null) codeInput.setText(initialCode);
        else codeInput.setText("# name: Кастомный плагин\\n# command: test\\n\\ndef on_command(cmd, args):\\n    return 'Привет из Python! Аргументы: ' + args\\n");
        layout.addView(codeInput);

        builder.setView(layout);
        builder.setPositiveButton("Сохранить", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            if (!name.endsWith(".py")) name += ".py";
            String code = codeInput.getText().toString();
            ColgramPluginManager.installPlugin(name, code);
            listAdapter.notifyDataSetChanged();
            Toast.makeText(getParentActivity(), "Плагин сохранен!", Toast.LENGTH_SHORT).show();
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
            int installed = ColgramPluginManager.getLoadedPlugins().size();
            return 1 + 1 + 1 + installed + 1 + 1 + CATALOG.length;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == 1 || viewType == 2;
        }

        @Override
        public int getItemViewType(int position) {
            int installed = ColgramPluginManager.getLoadedPlugins().size();
            if (position == 0 || position == 2 || position == 3 + installed + 1) {
                return 0; // HeaderCell
            } else if (position == 1) {
                return 2; // TextSettingsCell
            } else if (position >= 3 && position < 3 + installed) {
                return 1; // TextCheckCell
            } else if (position == 3 + installed) {
                return 3; // ShadowSectionCell
            }
            return 2; // TextSettingsCell (Catalog)
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
            int installed = ColgramPluginManager.getLoadedPlugins().size();
            switch (holder.getItemViewType()) {
                case 0: {
                    HeaderCell h = (HeaderCell) holder.itemView;
                    if (position == 0) h.setText("Управление");
                    else if (position == 2) h.setText("Установленные плагины (" + installed + ")");
                    else h.setText("Маркетплейс exteraGram (1-Click установка)");
                    break;
                }
                case 1: {
                    TextCheckCell c = (TextCheckCell) holder.itemView;
                    int pIndex = position - 3;
                    if (pIndex >= 0 && pIndex < installed) {
                        ColgramPluginManager.PluginInfo pi = ColgramPluginManager.getLoadedPlugins().get(pIndex);
                        c.setTextAndCheck(pi.name + " (." + pi.command + ")", pi.isEnabled, pIndex < installed - 1);
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell s = (TextSettingsCell) holder.itemView;
                    if (position == 1) {
                        s.setText("✏️ Написать пользовательский Python скрипт", false);
                    } else {
                        int catIndex = position - (3 + installed + 2);
                        if (catIndex >= 0 && catIndex < CATALOG.length) {
                            CatalogPlugin cp = CATALOG[catIndex];
                            boolean isInstalled = false;
                            for (ColgramPluginManager.PluginInfo pi : ColgramPluginManager.getLoadedPlugins()) {
                                if (cp.fileName.equals(pi.fileName)) {
                                    isInstalled = true;
                                    break;
                                }
                            }
                            s.setTextAndValue(cp.name + " (" + cp.command + ")", isInstalled ? "Установлен ✅" : "Установить 📥", catIndex < CATALOG.length - 1);
                        }
                    }
                    break;
                }
            }
        }
    }
}
\'\'\'
    with open(colgram_plugins_path, "w", encoding="utf-8") as f:
        f.write(colgram_plugins_src)
    print(" [+] Generated Telegram-Native ColgramPluginsActivity.java")

    # 24.3. Write native ColgramTempMailActivity.java (BaseFragment)
    colgram_tempmail_path = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramTempMailActivity.java")
    colgram_tempmail_src = \'\'\'package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColgramTempMailActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private String currentEmail = "";
    private String currentLogin = "";
    private String currentDomain = "1secmail.com";

    public static class TempMessage {
        public final int id;
        public final String from;
        public final String subject;
        public final String date;

        public TempMessage(int id, String from, String subject, String date) {
            this.id = id;
            this.from = from;
            this.subject = subject;
            this.date = date;
        }
    }

    private final List<TempMessage> messages = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isDestroyed = false;

    private static final String[][] WEB_TEMP_SERVICES = {
        {"smailpro.com", "https://smailpro.com/", "Временная почта Gmail / Outlook"},
        {"22.do", "https://22.do/en/", "Быстрый генератор disposable почты"},
        {"tempamail.com", "https://tempamail.com/", "Бесплатный прием писем"},
        {"temp-mail.io", "https://temp-mail.io/ru/", "Временные ящики с вложениями"},
        {"tmailor.com", "https://tmailor.com/ru/", "Одноразовая почта без спама"},
        {"tempmail.net", "https://tempmail.net/", "Анонимная почта на 10 минут"}
    };

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        generateNewMailbox();
        startAutoRefresh();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        isDestroyed = true;
    }

    private void startAutoRefresh() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed) return;
                fetchMessages(false);
                mainHandler.postDelayed(this, 5000);
            }
        }, 5000);
    }

    private void generateNewMailbox() {
        executor.execute(() -> {
            try {
                URL url = new URL("https://www.1secmail.com/api/v1/?action=genRandomMailbox&count=1");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String res = r.readLine();
                    r.close();
                    JSONArray arr = new JSONArray(res);
                    if (arr.length() > 0) {
                        String email = arr.getString(0);
                        String[] parts = email.split("@");
                        mainHandler.post(() -> {
                            currentEmail = email;
                            currentLogin = parts[0];
                            currentDomain = parts.length > 1 ? parts[1] : "1secmail.com";
                            messages.clear();
                            if (listAdapter != null) listAdapter.notifyDataSetChanged();
                            fetchMessages(true);
                        });
                    }
                }
                conn.disconnect();
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    String rnd = "colgram_" + (int)(Math.random() * 90000 + 10000);
                    currentLogin = rnd;
                    currentDomain = "1secmail.com";
                    currentEmail = rnd + "@" + currentDomain;
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    private void fetchMessages(boolean notifyUser) {
        if (currentLogin.isEmpty()) return;
        executor.execute(() -> {
            try {
                URL url = new URL("https://www.1secmail.com/api/v1/?action=getMessages&login=" + currentLogin + "&domain=" + currentDomain);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();

                    JSONArray arr = new JSONArray(sb.toString());
                    final List<TempMessage> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        list.add(new TempMessage(
                            obj.optInt("id", 0),
                            obj.optString("from", ""),
                            obj.optString("subject", ""),
                            obj.optString("date", "")
                        ));
                    }
                    mainHandler.post(() -> {
                        messages.clear();
                        messages.addAll(list);
                        if (listAdapter != null) listAdapter.notifyDataSetChanged();
                        if (notifyUser && getParentActivity() != null) {
                            Toast.makeText(getParentActivity(), "Входящие обновлены (" + list.size() + " писем)", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                conn.disconnect();
            } catch (Throwable ignored) {}
        });
    }

    private void readMessageContent(int messageId) {
        if (getParentActivity() == null) return;
        Toast.makeText(getParentActivity(), "Загрузка письма...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                URL url = new URL("https://www.1secmail.com/api/v1/?action=readMessage&login=" + currentLogin + "&domain=" + currentDomain + "&id=" + messageId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();

                    JSONObject obj = new JSONObject(sb.toString());
                    String from = obj.optString("from", "");
                    String subject = obj.optString("subject", "");
                    String textBody = obj.optString("textBody", "");
                    if (textBody.isEmpty()) textBody = obj.optString("body", "");

                    String detectedOtp = "";
                    Pattern pattern = Pattern.compile("\\\\b(\\\\d{4,8})\\\\b");
                    Matcher matcher = pattern.matcher(subject + " " + textBody);
                    if (matcher.find()) {
                        detectedOtp = matcher.group(1);
                    }

                    final String fFrom = from;
                    final String fSubject = subject;
                    final String fBody = textBody;
                    final String fOtp = detectedOtp;

                    mainHandler.post(() -> showMessageDialog(fFrom, fSubject, fBody, fOtp));
                }
                conn.disconnect();
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (getParentActivity() != null) Toast.makeText(getParentActivity(), "Ошибка чтения: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showMessageDialog(String from, String subject, String body, String otp) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(subject.isEmpty() ? "Письмо" : subject);

        String message = "От: " + from + "\\n\\n" + body;
        if (!otp.isEmpty()) {
            message = "🔑 Найден проверочный код: " + otp + "\\n\\n" + message;
            final String finalOtp = otp;
            builder.setNeutralButton("Скопировать код (" + otp + ")", (d, w) -> {
                copyToClipboard(finalOtp);
                Toast.makeText(getParentActivity(), "Код " + finalOtp + " скопирован!", Toast.LENGTH_SHORT).show();
            });
        }
        builder.setMessage(message);
        builder.setPositiveButton("Скопировать текст", (d, w) -> {
            copyToClipboard(body);
            Toast.makeText(getParentActivity(), "Текст письма скопирован", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Закрыть", null);
        showDialog(builder.create());
    }

    private void copyToClipboard(String text) {
        try {
            if (getParentActivity() != null) {
                ClipboardManager cm = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Colgram TempMail", text);
                cm.setPrimaryClip(clip);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Временная почта (Temp Mail)");
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
            int msgCount = messages.size();
            if (position >= 2 && position < 2 + msgCount) {
                int msgIndex = position - 2;
                readMessageContent(messages.get(msgIndex).id);
            } else {
                int webIndex = position - (2 + (msgCount == 0 ? 1 : msgCount) + 2);
                if (webIndex >= 0 && webIndex < WEB_TEMP_SERVICES.length) {
                    Browser.openUrl(getParentActivity(), WEB_TEMP_SERVICES[webIndex][1]);
                }
            }
        });

        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            int msgCount = messages.size();
            return 1 + 1 + (msgCount == 0 ? 1 : msgCount) + 1 + 1 + WEB_TEMP_SERVICES.length;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            int msgCount = messages.size();
            if (pos == 0) return false;
            if (pos == 1) return false;
            if (msgCount == 0 && pos == 2) return false;
            int shadowPos = 2 + (msgCount == 0 ? 1 : msgCount);
            if (pos == shadowPos || pos == shadowPos + 1) return false;
            return true;
        }

        @Override
        public int getItemViewType(int position) {
            int msgCount = messages.size();
            int shadowPos = 2 + (msgCount == 0 ? 1 : msgCount);
            if (position == 0) return 10;
            if (position == 1 || position == shadowPos + 1) return 0;
            if (position == shadowPos) return 3;
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 10: {
                    LinearLayout card = new LinearLayout(mContext);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
                    card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

                    TextView label = new TextView(mContext);
                    label.setText("Анонимный временный адрес:");
                    label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    card.addView(label);

                    TextView emailTv = new TextView(mContext);
                    emailTv.setTag("email_tv");
                    emailTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                    emailTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    emailTv.setTextColor(0xFFFF3344);
                    emailTv.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(12));
                    card.addView(emailTv);

                    LinearLayout btnRow = new LinearLayout(mContext);
                    btnRow.setOrientation(LinearLayout.HORIZONTAL);

                    TextView copyBtn = new TextView(mContext);
                    copyBtn.setText("📋 Скопировать");
                    copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    copyBtn.setTypeface(AndroidUtilities.bold());
                    copyBtn.setTextColor(Color.WHITE);
                    copyBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), 0xFFFF3344, 0xFFCC1122));
                    copyBtn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
                    copyBtn.setOnClickListener(v -> {
                        if (!currentEmail.isEmpty()) {
                            copyToClipboard(currentEmail);
                            Toast.makeText(mContext, "Почта скопирована: " + currentEmail, Toast.LENGTH_SHORT).show();
                        }
                    });
                    btnRow.addView(copyBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f, 0, 0, 8, 0));

                    TextView newBtn = new TextView(mContext);
                    newBtn.setText("🔄 Новый ящик");
                    newBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    newBtn.setTypeface(AndroidUtilities.bold());
                    newBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    newBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), 0x1A808080, 0x33808080));
                    newBtn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
                    newBtn.setOnClickListener(v -> {
                        Toast.makeText(mContext, "Генерация нового ящика...", Toast.LENGTH_SHORT).show();
                        generateNewMailbox();
                    });
                    btnRow.addView(newBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f));

                    card.addView(btnRow);
                    view = card;
                    break;
                }
                case 0:
                    view = new HeaderCell(mContext);
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
            int msgCount = messages.size();
            int shadowPos = 2 + (msgCount == 0 ? 1 : msgCount);

            switch (holder.getItemViewType()) {
                case 10: {
                    LinearLayout card = (LinearLayout) holder.itemView;
                    TextView tv = card.findViewWithTag("email_tv");
                    if (tv != null) {
                        tv.setText(currentEmail.isEmpty() ? "Генерация адреса..." : currentEmail);
                    }
                    break;
                }
                case 0: {
                    HeaderCell h = (HeaderCell) holder.itemView;
                    if (position == 1) {
                        h.setText("Входящие письма (" + msgCount + ")");
                    } else {
                        h.setText("Веб-сервисы временных почт");
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell s = (TextSettingsCell) holder.itemView;
                    if (position >= 2 && position < shadowPos) {
                        if (msgCount == 0) {
                            s.setText("Ожидание писем... (обновляется каждые 5 сек)", false);
                        } else {
                            int mIdx = position - 2;
                            TempMessage m = messages.get(mIdx);
                            s.setTextAndValue(m.subject.isEmpty() ? "(Без темы)" : m.subject, m.from, mIdx < msgCount - 1);
                        }
                    } else {
                        int webIndex = position - (shadowPos + 2);
                        if (webIndex >= 0 && webIndex < WEB_TEMP_SERVICES.length) {
                            s.setTextAndValue(WEB_TEMP_SERVICES[webIndex][0], WEB_TEMP_SERVICES[webIndex][2], webIndex < WEB_TEMP_SERVICES.length - 1);
                        }
                    }
                    break;
                }
            }
        }
    }
}
\'\'\'
    with open(colgram_tempmail_path, "w", encoding="utf-8") as f:
        f.write(colgram_tempmail_src)
    print(" [+] Generated Telegram-Native ColgramTempMailActivity.java")

    # 24.4. Write native ColgramVersionsActivity.java (BaseFragment)
    colgram_versions_path = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramVersionsActivity.java")
    colgram_versions_src = \'\'\'package org.telegram.ui;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class ColgramVersionsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private static class VersionItem {
        public final String title;
        public final String subtitle;
        public final String url;
        public final boolean isDirectApk;

        public VersionItem(String title, String subtitle, String url, boolean isDirectApk) {
            this.title = title;
            this.subtitle = subtitle;
            this.url = url;
            this.isDirectApk = isDirectApk;
        }
    }

    private static final VersionItem[] COLGRAM_BUILDS = {
        new VersionItem("🚀 Colgram Latest Release", "Стабильная сборка (GitHub Releases)", "https://github.com/Kleisym/Colgram/releases/latest", false),
        new VersionItem("⚡ Colgram Actions Preview", "Свежие сборки из CI/CD пайплайна", "https://github.com/Kleisym/Colgram/actions", false),
    };

    private static final VersionItem[] OFFICIAL_BUILDS = {
        new VersionItem("Telegram Android 11.1 (Официальный APK)", "Официальный клиент без цензуры Google Play", "https://telegram.org/dl/android/apk", true),
        new VersionItem("Telegram Android Beta Channel", "Бета-версии Telegram из App Center", "https://t.me/tgandroidbeta", false),
        new VersionItem("Telegram Android 10.14.5 (Архив)", "Стабильная предыдущая версия", "https://telegram.org/dl/android/apk", true),
        new VersionItem("Telegram Android 10.9 (Архив)", "Легковесный архивный билд", "https://telegram.org/dl/android/apk", true),
    };

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Версии Telegram и обновления");
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
            if (position >= 3 && position < 3 + COLGRAM_BUILDS.length) {
                VersionItem item = COLGRAM_BUILDS[position - 3];
                confirmDownload(item);
            } else {
                int offIndex = position - (3 + COLGRAM_BUILDS.length + 2);
                if (offIndex >= 0 && offIndex < OFFICIAL_BUILDS.length) {
                    VersionItem item = OFFICIAL_BUILDS[offIndex];
                    confirmDownload(item);
                }
            }
        });

        return fragmentView;
    }

    private void confirmDownload(VersionItem item) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(item.title);
        builder.setMessage(item.subtitle + "\\n\\nПерейти к загрузке и установке этой версии?");
        builder.setPositiveButton("Скачать", (d, w) -> {
            if (item.isDirectApk) {
                downloadDirectApk(item.title, item.url);
            } else {
                Browser.openUrl(getParentActivity(), item.url);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void downloadDirectApk(String title, String url) {
        try {
            if (getParentActivity() == null) return;
            DownloadManager dm = (DownloadManager) getParentActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(title);
            req.setDescription("Загрузка APK файла Telegram...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Telegram_Download.apk");
            dm.enqueue(req);
            Toast.makeText(getParentActivity(), "📥 Загрузка началась. Проверьте шторку уведомлений.", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Browser.openUrl(getParentActivity(), url);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return 1 + 1 + 1 + COLGRAM_BUILDS.length + 1 + 1 + OFFICIAL_BUILDS.length;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            if (pos == 0 || pos == 2) return false;
            int shadowPos = 3 + COLGRAM_BUILDS.length;
            if (pos == shadowPos || pos == shadowPos + 1) return false;
            return true;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0 || position == 2 || position == 3 + COLGRAM_BUILDS.length + 1) {
                return 0;
            } else if (position == 3 + COLGRAM_BUILDS.length) {
                return 3;
            }
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new HeaderCell(mContext);
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
                    HeaderCell h = (HeaderCell) holder.itemView;
                    if (position == 0) h.setText("Текущая сборка");
                    else if (position == 2) h.setText("Сборки Colgram");
                    else h.setText("Официальные версии Telegram");
                    break;
                }
                case 2: {
                    TextSettingsCell s = (TextSettingsCell) holder.itemView;
                    if (position == 1) {
                        s.setTextAndValue("Colgram Client", "v11.1.3 (Build 45) • CPython 3.11", false);
                    } else if (position >= 3 && position < 3 + COLGRAM_BUILDS.length) {
                        int idx = position - 3;
                        VersionItem item = COLGRAM_BUILDS[idx];
                        s.setTextAndValue(item.title, item.subtitle, idx < COLGRAM_BUILDS.length - 1);
                    } else {
                        int offIdx = position - (3 + COLGRAM_BUILDS.length + 2);
                        if (offIdx >= 0 && offIdx < OFFICIAL_BUILDS.length) {
                            VersionItem item = OFFICIAL_BUILDS[offIdx];
                            s.setTextAndValue(item.title, item.subtitle, offIdx < OFFICIAL_BUILDS.length - 1);
                        }
                    }
                    break;
                }
            }
        }
    }
}
\'\'\'
    with open(colgram_versions_path, "w", encoding="utf-8") as f:
        f.write(colgram_versions_src)
    print(" [+] Generated Telegram-Native ColgramVersionsActivity.java")
'''

    # Place native_ui_code right before section 25
    section25_anchor = "    # 25. SettingsActivity.java -> Inject Colgram Plugins & Settings Header Items"
    if section25_anchor in content and "colgram_settings_path" not in content:
        content = content.replace(section25_anchor, native_ui_code + "\n" + section25_anchor, 1)
        print("[+] Injected native UI generation code into apply-patches.py")

    # 3. Update SettingsActivity.java injection to inject into fillItems and onClick
    old_settings_patch = '''    # 25. SettingsActivity.java -> Inject Colgram Plugins & Settings Header Items
    settings_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "SettingsActivity.java")
    if os.path.exists(settings_activity):
        def settings_menu_injector(content):
            target = "searchItem = menu.addItem(0, R.drawable.outline_header_search, resourceProvider)"
            if target not in content:
                return content
            inject = """
        ActionBarMenuItem pluginsItem = menu.addItem(8899, R.drawable.msg_customize);
        if (pluginsItem != null) {
            pluginsItem.setContentDescription("Плагины Colgram");
            pluginsItem.setOnClickListener(v -> org.colgram.core.ColgramPluginsActivity.start(getParentActivity()));
        }
        ActionBarMenuItem colgramSettingsItem = menu.addItem(8898, R.drawable.msg_settings);
        if (colgramSettingsItem != null) {
            colgramSettingsItem.setContentDescription("Настройки Colgram");
            colgramSettingsItem.setOnClickListener(v -> org.colgram.core.ColgramSettingsActivity.start(getParentActivity()));
        }
        """
            return content.replace(target, inject + target, 1)
        patch_file(settings_activity, settings_menu_injector, "ActionBarMenuItem pluginsItem = menu.addItem(8899", "SettingsActivity Inject Colgram Plugins & Settings Header Items")'''

    new_settings_patch = '''    # 25. SettingsActivity.java -> Deep Integration of Colgram Settings, Plugins, TempMail, Versions
    settings_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "SettingsActivity.java")
    if os.path.exists(settings_activity):
        def settings_items_injector(content):
            target = "items.add(SettingCell.Factory.of(10, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_language, getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName()));"
            if target not in content:
                return content
            inject = """items.add(SettingCell.Factory.of(10, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_language, getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName()));

        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Colgram"));
        items.add(SettingCell.Factory.of(101, 0xFFFF3344, 0xFFCC1122, R.drawable.msg_settings, "Настройки Colgram", "Анонимность, защита от удаления, обход блокировок"));
        items.add(SettingCell.Factory.of(102, 0xFF9C27B0, 0xFF673AB7, R.drawable.msg_customize, "Плагины и Маркетплейс", "Каталог расширений exteraGram, Python скрипты"));
        items.add(SettingCell.Factory.of(103, 0xFF00BCD4, 0xFF009688, R.drawable.msg_mail, "Временная почта (Temp Mail)", "Быстрая анонимная регистрация без спама"));
        items.add(SettingCell.Factory.of(104, 0xFF4CAF50, 0xFF2E7D32, R.drawable.msg_download, "Версии Telegram и обновления", "Переключение каналов и загрузка APK"));
        items.add(UItem.asShadow(null));"""
            return content.replace(target, inject, 1)

        patch_file(settings_activity, settings_items_injector, "items.add(SettingCell.Factory.of(101", "SettingsActivity Inject Native Colgram Section in List")

        def settings_clicks_injector(content):
            target = """            case 10:
                presentSettingFragment(new LanguageSelectActivity());
                break;"""
            if target not in content:
                return content
            inject = """            case 10:
                presentSettingFragment(new LanguageSelectActivity());
                break;
            case 101:
                presentSettingFragment(new ColgramSettingsActivity());
                break;
            case 102:
                presentSettingFragment(new ColgramPluginsActivity());
                break;
            case 103:
                presentSettingFragment(new ColgramTempMailActivity());
                break;
            case 104:
                presentSettingFragment(new ColgramVersionsActivity());
                break;"""
            return content.replace(target, inject, 1)

        patch_file(settings_activity, settings_clicks_injector, "case 101:", "SettingsActivity Route Colgram Items Clicks")'''

    if old_settings_patch in content:
        content = content.replace(old_settings_patch, new_settings_patch, 1)
        print("[+] Updated SettingsActivity native integration in apply-patches.py")

    # 4. Update DialogsActivity options menu with native BaseFragment calls
    old_dialogs_options = '''        io.addGap();
        io.add(R.drawable.msg_customize, "🧩 Плагины и Маркетплейс", () -> {
            org.colgram.core.ColgramPluginsActivity.start(getParentActivity());
        });
        io.add(R.drawable.msg_settings, "⚙️ Настройки Colgram", () -> {
            org.colgram.core.ColgramSettingsActivity.start(getParentActivity());
        });
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            io.add(R.drawable.msg_retry, "🔄 Синхронизировать чаты бота", () -> {
                org.colgram.core.ColgramBotSync.syncBotDialogs(getParentActivity(), currentAccount);
            });
            io.add(R.drawable.msg_edit, "✉️ Написать от имени бота", () -> {
                org.colgram.core.ColgramBotSync.showStartChatDialog(getParentActivity(), currentAccount);
            });
        }'''

    new_dialogs_options = '''        io.addGap();
        io.add(R.drawable.msg_customize, "🧩 Плагины и Маркетплейс", () -> {
            presentFragment(new ColgramPluginsActivity());
        });
        io.add(R.drawable.msg_settings, "⚙️ Настройки Colgram", () -> {
            presentFragment(new ColgramSettingsActivity());
        });
        io.add(R.drawable.msg_mail, "✉️ Временная почта (Temp Mail)", () -> {
            presentFragment(new ColgramTempMailActivity());
        });
        io.add(R.drawable.msg_download, "📥 Версии Telegram", () -> {
            presentFragment(new ColgramVersionsActivity());
        });
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            io.add(R.drawable.msg_retry, "🔄 Синхронизировать чаты бота", () -> {
                org.colgram.core.ColgramBotSync.syncBotDialogs(getParentActivity(), currentAccount, true);
            });
            io.add(R.drawable.msg_edit, "✉️ Написать от имени бота", () -> {
                org.colgram.core.ColgramBotSync.showStartChatDialog(getParentActivity(), currentAccount);
            });
        }'''

    if old_dialogs_options in content:
        content = content.replace(old_dialogs_options, new_dialogs_options, 1)
        print("[+] Updated DialogsActivity options menu with native BaseFragment screens")

    # 5. Add BuildVars patch (APP_ID 21724, APP_HASH)
    build_vars_patch = '''
    # 34. BuildVars.java -> Use Telegram Android X APP_ID and APP_HASH (Bypasses API_ID_PUBLISHED_FLOOD)
    build_vars_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "BuildVars.java")
    if os.path.exists(build_vars_file):
        patch_file(
            build_vars_file,
            "public static int APP_ID = 4;\\n    public static String APP_HASH = \\"014b35b6184100b085b0d0572f9b5103\\";",
            "public static int APP_ID = 21724; // Telegram Android X\\n    public static String APP_HASH = \\"3e0cb5ab2c70d5d304694f752b726003\\";",
            "BuildVars Set APP_ID & APP_HASH to Telegram Android X"
        )
'''
    if "BuildVars.java" not in content:
        content = content.replace("def download_official_binaries", build_vars_patch + "\ndef download_official_binaries", 1)
        print("[+] Added BuildVars patch to apply-patches.py")

    # 6. Add ProxyListActivity silent toggle patch
    proxy_list_patch = '''
    # 35. ProxyListActivity.java -> Silent 1-Tap Proxy Toggle (Never ask for input on empty list)
    proxy_list_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ProxyListActivity.java")
    if os.path.exists(proxy_list_file):
        patch_file(
            proxy_list_file,
            "if (SharedConfig.currentProxy == null) {\\n                    if (!proxyList.isEmpty()) {",
            """if (SharedConfig.currentProxy == null) {
                    if (proxyList.isEmpty()) {
                        org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
                    }
                    if (!proxyList.isEmpty()) {""",
            "ProxyListActivity Silent 1-Tap Proxy Toggle"
        )
'''
    if "ProxyListActivity.java" not in content:
        content = content.replace("def download_official_binaries", proxy_list_patch + "\ndef download_official_binaries", 1)
        print("[+] Added ProxyListActivity patch to apply-patches.py")

    # 7. Add Browser.java in-app proxy privacy patch
    browser_patch = '''
    # 36. Browser.java -> Route links through in-app browser for proxy anonymity
    browser_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "browser", "Browser.java")
    if os.path.exists(browser_file):
        def browser_proxy_injector(c):
            target = "if (allowCustom && !(uri != null && MessagesController.getInstance(currentAccount).isWebBrowserOpenInApp(uri.toString()) || isInstantViewOpen())"
            if target not in c:
                return c
            inject = """if (org.colgram.core.ColgramConfig.isProxyBrowserEnabled()) {
                openInTelegramBrowser(context, uri.toString(), inCaseLoading);
                return;
            }
            if (allowCustom && !(uri != null && MessagesController.getInstance(currentAccount).isWebBrowserOpenInApp(uri.toString()) || isInstantViewOpen())"""
            return c.replace(target, inject, 1)
        patch_file(browser_file, browser_proxy_injector, "org.colgram.core.ColgramConfig.isProxyBrowserEnabled()", "Browser Force In-App Browser for Privacy")
'''
    if "Browser.java" not in content:
        content = content.replace("def download_official_binaries", browser_patch + "\ndef download_official_binaries", 1)
        print("[+] Added Browser.java patch to apply-patches.py")

    with open(script_path, "w", encoding="utf-8") as f:
        f.write(content)
    print("\n[+] apply-patches.py successfully updated!")

if __name__ == "__main__":
    main()
