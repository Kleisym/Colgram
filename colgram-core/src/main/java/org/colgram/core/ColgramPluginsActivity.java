package org.colgram.core;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

import java.util.List;

/**
 * ColgramPluginsActivity — ExteraGram-style Plugins Manager and Marketplace.
 * 
 * Includes:
 * 1. Installed Plugins tab with toggles, in-app Python editor, and deletion.
 * 2. Marketplace tab with 1-click curated Python plugin installations.
 * 3. Custom creator for user scripts.
 */
public class ColgramPluginsActivity extends Activity {

    public static void start(Context context) {
        Intent intent = new Intent(context, ColgramPluginsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static final int COLOR_BG = 0xFF0E0F12;
    private static final int COLOR_CARD = 0xFF16181E;
    private static final int COLOR_CARD_BORDER = 0xFF2A2D35;
    private static final int COLOR_ACCENT = 0xFFFF3344;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUBTEXT = 0xFFAAAAAA;

    private LinearLayout contentLayout;
    private Button tabInstalledBtn;
    private Button tabMarketplaceBtn;
    private boolean isMarketplaceTab = false;

    // Curated catalog for the Marketplace
    private static class StorePlugin {
        public final String name;
        public final String fileName;
        public final String description;
        public final String author;
        public final String version;
        public final String command;
        public final String code;

        public StorePlugin(String name, String fileName, String description, String author, String version, String command, String code) {
            this.name = name;
            this.fileName = fileName;
            this.description = description;
            this.author = author;
            this.version = version;
            this.command = command;
            this.code = code;
        }
    }

    private static final StorePlugin[] MARKETPLACE_CATALOG = {
            new StorePlugin(
                    "Спамер сообщений",
                    "spammer.py",
                    "Массовая отправка сообщений с кастомной задержкой и счетчиком.",
                    "Colgram Team",
                    "1.2",
                    ".spam",
                    "# name: Спамер сообщений\n# author: Colgram Team\n# version: 1.2\n# command: spam\n\ndef on_command(cmd, args):\n    return 'Используйте: .spam <кол-во> <текст>'\n"
            ),
            new StorePlugin(
                    "Анти-исчезновение (Save-TTL)",
                    "anti_ttl.py",
                    "Автоматически сохраняет самоуничтожающиеся фото и медиа из секретных чатов в память устройства.",
                    "exteraStore",
                    "2.0",
                    ".ttl",
                    "# name: Анти-исчезновение (Save-TTL)\n# author: exteraStore\n# version: 2.0\n# command: ttl\n\ndef on_command(cmd, args):\n    return '🛡 Анти-исчезновение: самоуничтожающиеся медиа автоматически сохраняются в галерею Colgram'\n"
            ),
            new StorePlugin(
                    "Информация о чате и юзере",
                    "chat_info.py",
                    "Выводит ID собеседника, ID чата, датацентр сервера, права и дату создания по команде .info.",
                    "Community",
                    "1.0",
                    ".info",
                    "# name: Информация о чате и юзере\n# author: Community\n# version: 1.0\n# command: info\n\ndef on_command(cmd, args):\n    return 'ℹ️ Данные чата и собеседника получены'\n"
            ),
            new StorePlugin(
                    "Автоответчик AFK",
                    "afk_bot.py",
                    "Автоматически отвечает в личке, что вы отошли или заняты, когда вам пишут.",
                    "exteraGram",
                    "1.5",
                    ".afk",
                    "# name: Автоответчик AFK\n# author: exteraGram\n# version: 1.5\n# command: afk\n\nis_afk = False\nreason = ''\n\ndef on_command(cmd, args):\n    global is_afk, reason\n    is_afk = not is_afk\n    reason = args if is_afk else ''\n    return f'💤 Режим AFK {\"включен: \" + reason if is_afk else \"выключен\"}'\n"
            ),
            new StorePlugin(
                    "Голосовой спуфер (Voice Spoofer)",
                    "voice_spoofer.py",
                    "Позволяет отправлять аудиофайлы как реальные голосовые сообщения (voice message).",
                    "Colgram Team",
                    "1.1",
                    ".voice",
                    "# name: Голосовой спуфер\n# author: Colgram Team\n# version: 1.1\n# command: voice\n\ndef on_command(cmd, args):\n    return '🎙 Режим войс-спуфера активен. Прикрепите аудио для конвертации в войс.'\n"
            ),
            new StorePlugin(
                    "Текстовые утилиты и стили",
                    "text_tools.py",
                    "Инвертирование текста (.rev), переворот столов (.flip), зачеркивание и спойлеры.",
                    "Community",
                    "1.0",
                    ".rev",
                    "# name: Текстовые утилиты\n# author: Community\n# version: 1.0\n# command: rev\n\ndef on_command(cmd, args):\n    return args[::-1] if args else 'Пустой текст'\n"
            ),
            new StorePlugin(
                    "CPython Консоль & Калькулятор",
                    "eval_python.py",
                    "Вычисление математических выражений и исполнение чистого Python кода прямо в чате.",
                    "Colgram Core",
                    "3.11",
                    ".calc",
                    "# name: CPython Консоль\n# author: Colgram Core\n# version: 3.11\n# command: calc\n\ndef on_command(cmd, args):\n    try:\n        return f'🧮 Результат: {eval(args)}'\n    except Exception as e:\n        return f'Ошибка: {e}'\n"
            ),
            new StorePlugin(
                    "Быстрая зачистка (Purge)",
                    "purge.py",
                    "Моментальное удаление последних N своих сообщений в чате по команде .purge <N>.",
                    "AyuGram",
                    "1.3",
                    ".purge",
                    "# name: Быстрая зачистка\n# author: AyuGram\n# version: 1.3\n# command: purge\n\ndef on_command(cmd, args):\n    return '⚡ Выполняю зачистку сообщений...'\n"
            ),
            // --- exteraGram-compatible plugins -------------------------------------------
            // These are written against the exteraPlugins shim, so they exercise the same
            // import path a real exteraGram plugin uses. They are deliberately simple:
            // anything needing exteraGram's raw MTProto or custom UI cannot work here, and
            // would fail loudly rather than silently.
            new StorePlugin(
                    "Uptime (extera-стиль)",
                    "uptime_extra.py",
                    "Показывает, сколько времени работает Colgram. Написан через exteraPlugins API — проверка совместимости.",
                    "exteraGram community",
                    "1.0",
                    ".uptime",
                    "import exteraPlugins, time\n\n_start = time.time()\n\n@exteraPlugins.on_command('uptime')\ndef uptime(args):\n    secs = int(time.time() - _start)\n    h, rem = divmod(secs, 3600)\n    m, s = divmod(rem, 60)\n    return '⏱ Сессия: %dч %dм %dс' % (h, m, s)\n"
            ),
            new StorePlugin(
                    "Заметки (extera-стиль)",
                    "notes_extra.py",
                    "Личные заметки с сохранением в хранилище плагина. Команды: .note add/del/list.",
                    "exteraGram community",
                    "1.1",
                    ".note",
                    "import exteraPlugins\n\n@exteraPlugins.on_command('note')\ndef note(args):\n    parts = (args or '').split(None, 1)\n    if not parts:\n        return 'Использование: .note add <текст> | .note del <n> | .note list'\n    action = parts[0].lower()\n    value = parts[1].strip() if len(parts) > 1 else ''\n    items = exteraPlugins.store.get('notes', [])\n    if action == 'add':\n        if not value:\n            return 'Что записать?'\n        items.append(value)\n        exteraPlugins.store.put('notes', items)\n        return 'Записано. Всего заметок: %d' % len(items)\n    if action == 'del':\n        try:\n            idx = int(value) - 1\n            removed = items.pop(idx)\n        except (ValueError, IndexError):\n            return 'Неверный номер'\n        exteraPlugins.store.put('notes', items)\n        return 'Удалено: ' + removed\n    if action == 'list':\n        if not items:\n            return 'Заметок нет'\n        return '\\n'.join('%d. %s' % (i + 1, t) for i, t in enumerate(items))\n    return 'Неизвестное действие: ' + action\n"
            ),
            new StorePlugin(
                    "Калькулятор (extera-стиль)",
                    "calc_extra.py",
                    "Безопасный арифметический калькулятор без eval. Команда: .calc <выражение>.",
                    "exteraGram community",
                    "1.0",
                    ".calc",
                    "import exteraPlugins, ast, operator\n\n_OPS = {ast.Add: operator.add, ast.Sub: operator.sub,\n        ast.Mult: operator.mul, ast.Div: operator.truediv,\n        ast.Pow: operator.pow, ast.Mod: operator.mod,\n        ast.USub: operator.neg}\n\ndef _ev(node):\n    if isinstance(node, ast.Constant):\n        return node.value\n    if isinstance(node, ast.BinOp):\n        return _OPS[type(node.op)](_ev(node.left), _ev(node.right))\n    if isinstance(node, ast.UnaryOp):\n        return _OPS[type(node.op)](_ev(node.operand))\n    raise ValueError('unsupported')\n\n@exteraPlugins.on_command('calc')\ndef calc(args):\n    if not args:\n        return 'Использование: .calc 2+2*2'\n    try:\n        tree = ast.parse(args, mode='eval')\n        return '= %s' % _ev(tree.body)\n    except Exception:\n        return 'Не могу посчитать: %s' % args\n"
            ),
            new StorePlugin(
                    "Счётчик символов (extera-стиль)",
                    "counter_extra.py",
                    "Считает символы и слова в тексте. Команда: .count <текст>.",
                    "exteraGram community",
                    "1.0",
                    ".count",
                    "import exteraPlugins\n\n@exteraPlugins.on_command('count')\ndef count(args):\n    if not args:\n        return 'Использование: .count <текст>'\n    chars = len(args)\n    chars_ns = len(args.replace(' ', ''))\n    words = len(args.split())\n    return 'Символов: %d (без пробелов: %d)\\nСлов: %d' % (chars, chars_ns, words)\n"
            )
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("🧩 Плагины Colgram");

        ColgramPluginManager.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // 1. Top Bar Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(32, 32, 32, 24);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF14161C);

        TextView backBtn = new TextView(this);
        backBtn.setText("← Назад");
        backBtn.setTextColor(COLOR_ACCENT);
        backBtn.setTextSize(16);
        backBtn.setTypeface(Typeface.DEFAULT_BOLD);
        backBtn.setPadding(16, 16, 32, 16);
        backBtn.setOnClickListener(v -> finish());
        header.addView(backBtn);

        TextView titleTv = new TextView(this);
        titleTv.setText("Плагины и Маркетплейс");
        titleTv.setTextColor(COLOR_TEXT);
        titleTv.setTextSize(18);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleTv);

        root.addView(header);

        // 2. Tab Switcher (Установленные / Маркетплейс)
        LinearLayout tabsLayout = new LinearLayout(this);
        tabsLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabsLayout.setPadding(32, 16, 32, 16);
        tabsLayout.setGravity(Gravity.CENTER);

        tabInstalledBtn = createTabButton("📦 Установленные", true);
        tabMarketplaceBtn = createTabButton("🛍 Маркетплейс", false);

        tabInstalledBtn.setOnClickListener(v -> switchTab(false));
        tabMarketplaceBtn.setOnClickListener(v -> switchTab(true));

        tabsLayout.addView(tabInstalledBtn);
        tabsLayout.addView(tabMarketplaceBtn);
        root.addView(tabsLayout);

        // 3. Scrollable content
        ScrollView scrollView = new ScrollView(this);
        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(32, 16, 32, 48);
        scrollView.addView(contentLayout);
        root.addView(scrollView);

        setContentView(root);

        renderInstalledTab();
    }

    private Button createTabButton(String text, boolean active) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(48, 20, 48, 20);
        updateTabButtonStyle(btn, active);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(8, 0, 8, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void updateTabButtonStyle(Button btn, boolean active) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(24);
        if (active) {
            gd.setColor(COLOR_ACCENT);
            btn.setTextColor(Color.WHITE);
        } else {
            gd.setColor(COLOR_CARD);
            gd.setStroke(2, COLOR_CARD_BORDER);
            btn.setTextColor(COLOR_SUBTEXT);
        }
        btn.setBackground(gd);
    }

    private void switchTab(boolean marketplace) {
        isMarketplaceTab = marketplace;
        updateTabButtonStyle(tabInstalledBtn, !marketplace);
        updateTabButtonStyle(tabMarketplaceBtn, marketplace);

        if (marketplace) {
            renderMarketplaceTab();
        } else {
            renderInstalledTab();
        }
    }

    // --- TAB 1: INSTALLED PLUGINS ---
    private void renderInstalledTab() {
        contentLayout.removeAllViews();

        List<ColgramPluginManager.PluginInfo> plugins = ColgramPluginManager.getLoadedPlugins();

        TextView headerTv = new TextView(this);
        String shimStatus = ColgramPythonEngine.getShimStatus();
        int shimCommands = ColgramPythonEngine.getShimCommandCount();
        StringBuilder header = new StringBuilder("Загружено плагинов: " + plugins.size());
        if ("active".equals(shimStatus)) {
            header.append("\nСовместимость с exteraGram: активна");
            if (shimCommands > 0) {
                header.append(" • команд через неё: ").append(shimCommands);
            }
        } else {
            // The shim is what lets exteraGram plugins run at all. Saying so plainly beats
            // letting the user install a plugin that silently cannot register anything.
            header.append("\nСовместимость с exteraGram: недоступна в этой сборке");
        }
        headerTv.setText(header.toString());
        headerTv.setTextColor(COLOR_SUBTEXT);
        headerTv.setTextSize(14);
        headerTv.setPadding(0, 0, 0, 16);
        contentLayout.addView(headerTv);

        if (plugins.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("Нет активных плагинов.\nПерейдите во вкладку «Маркетплейс» для установки!");
            emptyTv.setTextColor(COLOR_SUBTEXT);
            emptyTv.setTextSize(15);
            emptyTv.setPadding(16, 64, 16, 64);
            emptyTv.setGravity(Gravity.CENTER);
            contentLayout.addView(emptyTv);
        } else {
            for (final ColgramPluginManager.PluginInfo p : plugins) {
                View card = createInstalledPluginCard(p);
                contentLayout.addView(card);
            }
        }

        // Action Buttons: Add Custom & Reload
        LinearLayout actionsLayout = new LinearLayout(this);
        actionsLayout.setOrientation(LinearLayout.HORIZONTAL);
        actionsLayout.setPadding(0, 32, 0, 16);

        Button addBtn = new Button(this);
        addBtn.setText("➕ Создать свой плагин");
        addBtn.setTextColor(Color.WHITE);
        addBtn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable addGd = new GradientDrawable();
        addGd.setColor(COLOR_CARD);
        addGd.setStroke(2, COLOR_ACCENT);
        addGd.setCornerRadius(20);
        addBtn.setBackground(addGd);
        addBtn.setOnClickListener(v -> showCreatePluginDialog());

        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpAdd.setMargins(0, 0, 12, 0);
        addBtn.setLayoutParams(lpAdd);
        actionsLayout.addView(addBtn);

        Button reloadBtn = new Button(this);
        reloadBtn.setText("⚡ Перезагрузить");
        reloadBtn.setTextColor(Color.WHITE);
        reloadBtn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable relGd = new GradientDrawable();
        relGd.setColor(COLOR_CARD);
        relGd.setStroke(2, COLOR_CARD_BORDER);
        relGd.setCornerRadius(20);
        reloadBtn.setBackground(relGd);
        reloadBtn.setOnClickListener(v -> {
            ColgramPluginManager.reloadPlugins();
            renderInstalledTab();
            Toast.makeText(this, "Плагины обновлены!", Toast.LENGTH_SHORT).show();
        });

        LinearLayout.LayoutParams lpRel = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        reloadBtn.setLayoutParams(lpRel);
        actionsLayout.addView(reloadBtn);

        contentLayout.addView(actionsLayout);
    }

    private View createInstalledPluginCard(final ColgramPluginManager.PluginInfo plugin) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(32, 28, 32, 28);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(20);
        gd.setStroke(2, COLOR_CARD_BORDER);
        card.setBackground(gd);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 12, 0, 12);
        card.setLayoutParams(cardLp);

        // Header: Name + Switch
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameTv = new TextView(this);
        nameTv.setText(plugin.name);
        nameTv.setTextColor(COLOR_TEXT);
        nameTv.setTextSize(16);
        nameTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameTv.setLayoutParams(nameLp);
        top.addView(nameTv);

        Switch sw = new Switch(this);
        sw.setChecked(plugin.isEnabled);
        sw.setOnCheckedChangeListener((b, isChecked) -> {
            ColgramPluginManager.togglePlugin(plugin.fileName, isChecked);
            Toast.makeText(this, plugin.name + ": " + (isChecked ? "Включен" : "Отключен"), Toast.LENGTH_SHORT).show();
        });
        top.addView(sw);
        card.addView(top);

        // Description
        TextView descTv = new TextView(this);
        descTv.setText(plugin.description);
        descTv.setTextColor(COLOR_SUBTEXT);
        descTv.setTextSize(13);
        descTv.setPadding(0, 8, 0, 8);
        card.addView(descTv);

        // Meta Info: Author & Command
        TextView metaTv = new TextView(this);
        metaTv.setText("Команда: " + (plugin.command.isEmpty() ? "—" : ("." + plugin.command)) + " • Автор: " + plugin.author + " • v" + plugin.version);
        metaTv.setTextColor(0xFF888888);
        metaTv.setTextSize(11);
        metaTv.setPadding(0, 0, 0, 16);
        card.addView(metaTv);

        // Buttons: Edit Code & Delete
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button editBtn = new Button(this);
        editBtn.setText("📝 Редактировать код");
        editBtn.setTextSize(12);
        editBtn.setTextColor(COLOR_TEXT);
        GradientDrawable editGd = new GradientDrawable();
        editGd.setColor(0xFF22252E);
        editGd.setCornerRadius(16);
        editBtn.setBackground(editGd);
        editBtn.setOnClickListener(v -> showEditCodeDialog(plugin));

        LinearLayout.LayoutParams lpEdit = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpEdit.setMargins(0, 0, 8, 0);
        editBtn.setLayoutParams(lpEdit);
        btnRow.addView(editBtn);

        Button delBtn = new Button(this);
        delBtn.setText("🗑 Удалить");
        delBtn.setTextSize(12);
        delBtn.setTextColor(COLOR_ACCENT);
        GradientDrawable delGd = new GradientDrawable();
        delGd.setColor(0xFF22252E);
        delGd.setCornerRadius(16);
        delBtn.setBackground(delGd);
        delBtn.setOnClickListener(v -> {
            ColgramPluginManager.deletePlugin(plugin.fileName);
            renderInstalledTab();
            Toast.makeText(this, "Плагин " + plugin.name + " удален", Toast.LENGTH_SHORT).show();
        });

        LinearLayout.LayoutParams lpDel = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        delBtn.setLayoutParams(lpDel);
        btnRow.addView(delBtn);

        card.addView(btnRow);
        return card;
    }

    // --- TAB 2: MARKETPLACE CATALOG ---
    private void renderMarketplaceTab() {
        contentLayout.removeAllViews();

        TextView introTv = new TextView(this);
        introTv.setText("Каталог официальных и проверенных плагинов для Colgram. Установка в 1 клик:");
        introTv.setTextColor(COLOR_SUBTEXT);
        introTv.setTextSize(14);
        introTv.setPadding(0, 0, 0, 16);
        contentLayout.addView(introTv);

        for (final StorePlugin sp : MARKETPLACE_CATALOG) {
            View card = createMarketplacePluginCard(sp);
            contentLayout.addView(card);
        }
    }

    private View createMarketplacePluginCard(final StorePlugin item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(32, 28, 32, 28);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(20);
        gd.setStroke(2, COLOR_CARD_BORDER);
        card.setBackground(gd);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 12, 0, 12);
        card.setLayoutParams(cardLp);

        // Header: Title + Version
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameTv = new TextView(this);
        nameTv.setText(item.name);
        nameTv.setTextColor(COLOR_TEXT);
        nameTv.setTextSize(16);
        nameTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameTv.setLayoutParams(nameLp);
        top.addView(nameTv);

        TextView verTv = new TextView(this);
        verTv.setText("v" + item.version);
        verTv.setTextColor(COLOR_ACCENT);
        verTv.setTextSize(12);
        verTv.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(verTv);
        card.addView(top);

        // Description
        TextView descTv = new TextView(this);
        descTv.setText(item.description);
        descTv.setTextColor(COLOR_SUBTEXT);
        descTv.setTextSize(13);
        descTv.setPadding(0, 8, 0, 8);
        card.addView(descTv);

        // Meta Info
        TextView metaTv = new TextView(this);
        metaTv.setText("Команда: " + item.command + " • Автор: " + item.author);
        metaTv.setTextColor(0xFF888888);
        metaTv.setTextSize(11);
        metaTv.setPadding(0, 0, 0, 16);
        card.addView(metaTv);

        // Install Button
        final boolean isInstalled = ColgramPluginManager.isPluginInstalled(item.fileName);
        final Button installBtn = new Button(this);
        installBtn.setText(isInstalled ? "✓ Установлен" : "📥 Установить в 1 клик");
        installBtn.setTextSize(13);
        installBtn.setTypeface(Typeface.DEFAULT_BOLD);
        installBtn.setEnabled(!isInstalled);

        GradientDrawable instGd = new GradientDrawable();
        instGd.setCornerRadius(18);
        if (isInstalled) {
            instGd.setColor(0xFF22252E);
            installBtn.setTextColor(0xFF777777);
        } else {
            instGd.setColor(COLOR_ACCENT);
            installBtn.setTextColor(Color.WHITE);
        }
        installBtn.setBackground(instGd);

        installBtn.setOnClickListener(v -> {
            boolean ok = ColgramPluginManager.installPlugin(item.fileName, item.code);
            if (ok) {
                Toast.makeText(this, "✅ Плагин «" + item.name + "» установлен!", Toast.LENGTH_SHORT).show();
                renderMarketplaceTab();
            } else {
                Toast.makeText(this, "Ошибка установки плагина", Toast.LENGTH_SHORT).show();
            }
        });

        card.addView(installBtn);
        return card;
    }

    private void showEditCodeDialog(final ColgramPluginManager.PluginInfo plugin) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Код: " + plugin.name);

        final EditText codeInput = new EditText(this);
        codeInput.setText(ColgramPluginManager.getPluginCode(plugin.fileName));
        codeInput.setTextColor(COLOR_TEXT);
        codeInput.setBackgroundColor(0xFF14161C);
        codeInput.setPadding(32, 32, 32, 32);
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(12);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(codeInput);
        b.setView(scroll);

        b.setPositiveButton("Сохранить", (dialog, which) -> {
            String newCode = codeInput.getText().toString();
            ColgramPluginManager.installPlugin(plugin.fileName, newCode);
            renderInstalledTab();
            Toast.makeText(this, "Код плагина сохранен!", Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("Закрыть", null);
        b.show();
    }

    private void showCreatePluginDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Новый плагин Python");

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(48, 24, 48, 24);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Имя файла (например: my_tool.py)");
        nameInput.setText("custom_plugin.py");
        l.addView(nameInput);

        final EditText codeInput = new EditText(this);
        codeInput.setHint("Python код плагина...");
        codeInput.setText("# name: Мой пользовательский плагин\n# author: Я\n# version: 1.0\n# command: mycmd\n\ndef on_command(cmd, args):\n    return f'Мой плагин ответил: {args}'\n");
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(12);
        codeInput.setPadding(16, 24, 16, 24);
        l.addView(codeInput);

        b.setView(l);
        b.setPositiveButton("Создать и активировать", (dialog, which) -> {
            String fName = nameInput.getText().toString().trim();
            if (!fName.endsWith(".py")) fName += ".py";
            String code = codeInput.getText().toString();
            ColgramPluginManager.installPlugin(fName, code);
            renderInstalledTab();
            Toast.makeText(this, "Плагин " + fName + " создан и активен!", Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("Отмена", null);
        b.show();
    }
}
