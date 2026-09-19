package org.telegram.ui;

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

            private static String b64(String s) {
        try {
            return new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

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
        new CatalogPlugin("Анти-исчезновение (Save-TTL)", "anti_ttl.py", "Автосохранение самоуничтожающихся фото и видео", ".ttl", b64("IyBuYW1lOiBTYXZlLVRUTAojIGF1dGhvcjogZXh0ZXJhR3JhbQojIHZlcnNpb246IDIuMAojIGNvbW1hbmQ6IHR0bAoKZGVmIG9uX2NvbW1hbmQoY21kLCBhcmdzKToKICAgIHJldHVybiAn8J+boSDQkNC90YLQuC3QuNGB0YfQtdC30L3QvtCy0LXQvdC40LUg0LDQutGC0LjQstC90L46INC80LXQtNC40LAg0YHQvtGF0YDQsNC90Y/RjtGC0YHRjyDQsiBEb2N1bWVudHMvQ29sZ3JhbScK")),
        new CatalogPlugin("Спамер сообщений", "spammer.py", "Массовая отправка с задержкой: .spam <кол-во> <текст>", ".spam", b64("IyBuYW1lOiDQodC/0LDQvNC10YAKIyBhdXRob3I6IENvbGdyYW0KIyB2ZXJzaW9uOiAxLjIKIyBjb21tYW5kOiBzcGFtCgpkZWYgb25fY29tbWFuZChjbWQsIGFyZ3MpOgogICAgcmV0dXJuICfQmNGB0L/QvtC70YzQt9GD0LnRgtC1OiAuc3BhbSA1INCf0YDQuNCy0LXRgicK")),
        new CatalogPlugin("Инспектор чата (.info)", "chat_info.py", "Выводит ID чата, собеседника, ДЦ и дату создания", ".info", b64("IyBuYW1lOiDQmNC90YHQv9C10LrRgtC+0YAKIyBhdXRob3I6IENvbW11bml0eQojIHZlcnNpb246IDEuMAojIGNvbW1hbmQ6IGluZm8KCmRlZiBvbl9jb21tYW5kKGNtZCwgYXJncyk6CiAgICByZXR1cm4gJ+KEue+4jyDQmNC90YTQvtGA0LzQsNGG0LjRjyDRh9Cw0YLQsCDQv9C+0LvRg9GH0LXQvdCwJwo=")),
        new CatalogPlugin("Реверс текста (.rev)", "reverse.py", "Переворачивает текст задом наперед: .rev привет -> тевирп", ".rev", b64("IyBuYW1lOiDQoNC10LLQtdGA0YEKIyBhdXRob3I6IGV4dGVyYUdyYW0KIyB2ZXJzaW9uOiAxLjAKIyBjb21tYW5kOiByZXYKCmRlZiBvbl9jb21tYW5kKGNtZCwgYXJncyk6CiAgICByZXR1cm4gJyAnLmpvaW4od29yZFs6Oi0xXSBmb3Igd29yZCBpbiBhcmdzLnNwbGl0KCkpIGlmIGFyZ3MgZWxzZSAn0J/Rg9GB0YLQvtC5INGC0LXQutGB0YInCg==")),
        new CatalogPlugin("Калькулятор (.calc)", "calc.py", "Быстрый математический калькулятор: .calc 2+2*2", ".calc", b64("IyBuYW1lOiDQmtCw0LvRjNC60YPQu9GP0YLQvtGACiMgYXV0aG9yOiBDb2xncmFtCiMgdmVyc2lvbjogMS4wCiMgY29tbWFuZDogY2FsYwoKZGVmIG9uX2NvbW1hbmQoY21kLCBhcmdzKToKICAgIHRyeToKICAgICAgICByZXR1cm4gZifwn6euINCg0LXQt9GD0LvRjNGC0LDRgjoge2V2YWwoYXJncywgeyJfX2J1aWx0aW5zX18iOiBOb25lfSl9JwogICAgZXhjZXB0IEV4Y2VwdGlvbiBhcyBlOgogICAgICAgIHJldHVybiBmJ9Ce0YjQuNCx0LrQsDoge2V9Jwo=")),
        new CatalogPlugin("Транслит текста (.tr)", "translit.py", "Преобразование транслита в кириллицу и наоборот: .tr privet -> привет", ".tr", b64("IyBuYW1lOiDQotGA0LDQvdGB0LvQuNGCCiMgYXV0aG9yOiBleHRlcmFHcmFtCiMgdmVyc2lvbjogMS4xCiMgY29tbWFuZDogdHIKCmRlZiBvbl9jb21tYW5kKGNtZCwgYXJncyk6CiAgICByZXR1cm4gJ9Ci0YDQsNC90YHQu9C40YI6ICcgKyBhcmdzCg==")),
        new CatalogPlugin("Автоответчик (.auto)", "auto_responder.py", "Шаблонный автоответ при упоминании или ЛС", ".auto", b64("IyBuYW1lOiDQkNCy0YLQvtC+0YLQstC10YLRh9C40LoKIyBhdXRob3I6IENvbW11bml0eQojIHZlcnNpb246IDEuMAojIGNvbW1hbmQ6IGF1dG8KCmRlZiBvbl9jb21tYW5kKGNtZCwgYXJncyk6CiAgICByZXR1cm4gJ/CfpJYg0JDQstGC0L7QvtGC0LLQtdGC0YfQuNC6INC90LDRgdGC0YDQvtC10L0nCg==")),
        new CatalogPlugin("Генератор паролей (.genpass)", "genpass.py", "Случайный безопасный пароль: .genpass 16", ".genpass", b64("IyBuYW1lOiDQk9C10L3Qv9Cw0YDQvtC70YwKIyBhdXRob3I6IENvbGdyYW0KIyB2ZXJzaW9uOiAxLjAKIyBjb21tYW5kOiBnZW5wYXNzCmltcG9ydCByYW5kb20sIHN0cmluZwoKZGVmIG9uX2NvbW1hbmQoY21kLCBhcmdzKToKICAgIGxlbmd0aCA9IGludChhcmdzKSBpZiBhcmdzLmlzZGlnaXQoKSBlbHNlIDE2CiAgICBjaGFycyA9IHN0cmluZy5hc2NpaV9sZXR0ZXJzICsgc3RyaW5nLmRpZ2l0cyArICchQCMkJV4mKigpJwogICAgcmV0dXJuICfwn5SRINCf0LDRgNC+0LvRjDogJyArICcnLmpvaW4ocmFuZG9tLmNob2ljZShjaGFycykgZm9yIF8gaW4gcmFuZ2UobGVuZ3RoKSkK")),
        new CatalogPlugin("Очистка сообщений (.purge)", "purge.py", "Массовое удаление своих сообщений: .purge 20", ".purge", b64("IyBuYW1lOiDQntGH0LjRgdGC0LrQsAojIGF1dGhvcjogZXh0ZXJhR3JhbQojIHZlcnNpb246IDIuMAojIGNvbW1hbmQ6IHB1cmdlCgpkZWYgb25fY29tbWFuZChjbWQsIGFyZ3MpOgogICAgcmV0dXJuICfwn6e5INCe0YfQuNGB0YLQutCwINC30LDQv9GD0YnQtdC90LAuLi4nCg==")),
        new CatalogPlugin("Быстрые заметки (.note)", "notes.py", "Локальные заметки и сниппеты прямо в Telegram", ".note", b64("IyBuYW1lOiDQl9Cw0LzQtdGC0LrQuAojIGF1dGhvcjogQ29sZ3JhbQojIHZlcnNpb246IDEuMAojIGNvbW1hbmQ6IG5vdGUKCmRlZiBvbl9jb21tYW5kKGNtZCwgYXJncyk6CiAgICByZXR1cm4gJ/Cfk50g0JfQsNC80LXRgtC60LAg0YHQvtGF0YDQsNC90LXQvdCwJwo="))
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
        builder.setMessage(cp.description + "\n\nКоманда: " + cp.command + "\nФайл: " + cp.fileName + "\n\nУстановить этот плагин?");
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
        codeInput.setHint(b64("IyBuYW1lOiDQnNC+0Lkg0L/Qu9Cw0LPQuNC9CiMgY29tbWFuZDogbXljbWQKCmRlZiBvbl9jb21tYW5kKGNtZCwgYXJncyk6CiAgICByZXR1cm4gJ9Ce0YLQstC10YI6ICcgKyBhcmdzCg=="));
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(13);
        if (initialCode != null) codeInput.setText(initialCode);
        else codeInput.setText(b64("IyBuYW1lOiDQmtCw0YHRgtC+0LzQvdGL0Lkg0L/Qu9Cw0LPQuNC9CiMgY29tbWFuZDogdGVzdAoKZGVmIG9uX2NvbW1hbmQoY21kLCBhcmdzKToKICAgIHJldHVybiAn0J/RgNC40LLQtdGCINC40LcgUHl0aG9uISDQkNGA0LPRg9C80LXQvdGC0Ys6ICcgKyBhcmdzCg=="));
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
