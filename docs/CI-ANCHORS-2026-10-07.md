# Почему CI падал и что с этим сделано, 2026-10-07

Run `37553518379` упал на шаге 7 «Apply Colgram Core & Surgical Patches»:

```
FileNotFoundError: [Errno 2] No such file or directory:
  '.../scripts/templates/ColgramBotProfileSave.java.inc'
```

Это была не поломка патчера, а последствие чистки репозитория. Ниже — что чистка убрала,
что оказалось не мусором, и четыре якоря, которые устарели к upstream 12.10.6 независимо от неё.

---

## Часть 1. Что чистка убрала, а сборке это нужно

### `scripts/templates/` — 53 файла

`apply-patches.py` открывает их по пути:

```python
with open(os.path.join(os.path.dirname(__file__), "templates",
                       "ColgramBotProfileSave.java.inc"), "r", encoding="utf-8") as profile_patch:
```

Плюс список из 25 device-тестов, которые зеркалятся в дерево сборки. На clean clone это
`FileNotFoundError` до первой строки патча.

Три `.inc` — не дубликаты: `ColgramBotInfoLocalized`, `ColgramBotProfileLoad`,
`ColgramBotProfileSave`. В дереве проекта их нет, они существуют только как шаблоны.

### `scripts/patch_proxylist_warp.py` — отдельный модуль

```python
import patch_proxylist_warp as _ppw
_ppw.apply(repo_path)
```

Импорт по имени. Без файла WARP-строка не добавлялась, и каждый прогон записывал
`ProxyListActivity WARP Row` в список пропусков.

### `colgram-core/` в корне — не устаревшая копия

Это было ошибкой в моём анализе. `inject_core()` зеркалит его в дерево сборки:

```python
core_dir = os.path.join(root_dir, "colgram-core")   # root_dir = репозиторий
inject_core(target_repo, core_dir)
```

То есть корневой каталог — **эталон**, а `Telegram-Src/colgram-core` — его зеркало. Я удалил
эталон, оставил зеркало, и оно отстало на восемь java-файлов: MASQUE-транспорт,
краш-гард, собранные `.so` — и все правки обходчика и прокси, которые существовали
только в зеркале.

Расхождение, измеренное перед синхронизацией:

```
root colgram-core: 57 java    Telegram-Src: 65 java
только в зеркале: ColgramMasqueNative, ColgramMasqueVpnService, ColgramWarpMasqueTunnel,
                   ColgramCrashGuard, ColgramUdpTunnel, NativeCrashSignal,
                   SocksUdpRelay, WgNoise
                   + 6 .so под три ABI
отличающихся файлов: 60
```

Синхронизирован: 447 файлов скопированы из зеркала в эталон, 454 = 454.

---

## Часть 2. Четыре якоря, устаревших к upstream 12.10.6

Клон `f2908b1` (12.10.6, build 7112).

### ChatMessageCell Anti-Delete Fade

```python
cell_bind_target = "        long deleteDialogId = messageObject.getDialogId();"
```

Upstream переименовал переменную — `deleteDialogId` в файле больше нет:

```
$ grep -c 'deleteDialogId' ChatMessageCell.java
0
```

Заменено на `lastDeleteDate = messageObject.messageOwner.destroyTime;` — та же строка в том же
блоке бинда, пережила обновление, и как якорь лучше: именно она устанавливает, какое
сообщение сейчас показывает ячейка, а к этому и привязан маркер анти-удаления.

### ChangeBioActivity Bot Description Null-Safe Callback

Патч переписывал бот-ветку, разыменовывающую `userFull` без проверки на null. Этой ветки нет
ни в upstream, ни после guard-патча прямо над ним — guard возвращается для бота раньше.
Якорь мог сработать только на дереве от старого патчера. Удалён; поведение теперь принадлежит
guard-у, который сам проверяет `userFull` в колбэке.

### ProxyListActivity WARP Row

Четыре якоря висели на `callsRow`, а upstream **удалил переключатель звонков целиком**:

```
$ grep -c 'callsRow' ProxyListActivity.java
0
```

Переведены на `rotationRow` и `proxyAddRow` — оба пережили. Удаление отсутствующего
upstream-блока сделано условным, а не безусловным.

### Colgram sing-box build.gradle anchor

```python
anchor = "    implementation project(':colgram-wireguard')"
```

Совпадало на LF и не совпадало на CRLF. Теперь regex по форме оператора.

---

## Часть 3. Два дефекта порядка, из-за которых всё выглядело случайным

### sync_singbox_module шёл до download_official_binaries

`download_official_binaries` **добавляет** строку `implementation project(':colgram-wireguard')`,
а `sync_singbox_module` вешает на неё зависимость sing-box. На чистом клоне строки ещё нет,
пропуск фиксируется, сборка падает. На **втором** прогоне по тому же дереву строка уже есть —
всё проходит. Отсюда ощущение нестабильности.

### Удаляющий патч шёл раньше вставляющего

`SettingsActivity Inject Native Colgram Section in List` пишет заголовок `asHeader("Colgram")`,
а `SettingsActivity Merges Colgram Rows Into Stock List` его удаляет. Вторая правка была на
~1400 строк раньше первой, то есть выполнялась раньше — ей нечего было удалять.

Перестановка вызова ломала отступы внутри вложенных функций, поэтому решено переносом строки
вставки в конец `inject_hooks`, где все вложенные `def` уже объявлены.

---

## Проверка

Шесть свежих клонов `DrKLO/Telegram` на `f2908b1`, оба подмодуля инициализированы,
`git status` перед патчем — 0 изменённых файлов:

```
[+] colgram-core synced (454 files)
[+] Colgram WireGuard module synced
[+] Colgram sing-box module synced, engine present for all four ABIs
[+] core device tests are mirrored from tracked templates
[+] Colgram setup complete! Ready to build APK.

exit: 0
```

Ни одного `UNEXPECTED`. В дереве клона после патча:

```
libcolgrammasque.so            arm64-v8a, x86, x86_64
libcolgramcrash.so             arm64-v8a, x86, x86_64
ColgramWarpMasqueTunnel.java   есть
ColgramCrashGuard.java         есть
ColgramDpiBypass.java          есть
project(':colgram-core')       подключён
project(':colgram-wireguard')  подключён
project(':colgram-singbox')    подключён
Colgram header удалён           да
WARP row                        есть
rotation row                    есть
device tests                    33 зеркалированы
```

---

## Отдельно про два коммита с GitHub

Пока шла диагностика, в `main` появились два коммита не через локальный git:

```
3185921ee  Delete AGENTS.md
150329aad  Update .gitignore
```

Второй **вернул** в `.gitignore` правила, которые чистка добавила специально:

```
vendor/libbox-binding/
vendor/colgram-singbox/src/main/jniLibs/
```

То есть ровно те, из-за которых движок sing-box не попадал в APK — подписки обещали VPN,
который не мог запуститься. Локальная ветка смержилась с remote, правила вернулись,
движок снова под контролем версий. Проверено после мержа:

```
$ git ls-files vendor/colgram-singbox/src/main/jniLibs
vendor/colgram-singbox/src/main/jniLibs/arm64-v8a/libbox.so
vendor/colgram-singbox/src/main/jniLibs/armeabi-v7a/libbox.so
vendor/colgram-singbox/src/main/jniLibs/x86/libbox.so
vendor/colgram-singbox/src/main/jniLibs/x86_64/libbox.so
```

GitHub предупреждает, что `x86/libbox.so` — 74.87 MB и `x86_64/libbox.so` — 81.57 MB,
больше рекомендуемых 50 MB. Это предупреждение, не ошибка: движок sing-box для четырёх
ABI иначе не попадёт в APK.
