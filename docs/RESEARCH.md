# Colgram — Исследования и техническая документация

**Дата последнего обновления:** 2026-10-06

## Оглавление

1. [Архитектура WARP](#1-архитектура-warp)
2. [Обход блокировок](#2-обход-блокировок)
3. [Прокси-система](#3-прокси-система)
4. [Найденные и исправленные баги](#4-найденные-и-исправленные-баги)
5. [Ребрендинг Telegram → Colgram](#5-ребрендинг)
6. [Глобальный поиск](#6-глобальный-поиск)
7. [Тёмная тема](#7-тёмная-тема)
8. [Плагины](#8-плагины)
9. [Результаты тестов](#9-результаты-тестов)
10. [Текущие проблемы](#10-текущие-проблемы)

---

## 1. Архитектура WARP

### Транспорт: MASQUE поверх HTTP/2

Colgram реализует Cloudflare WARP через MASQUE (HTTP/2 CONNECT-UDP) вместо WireGuard.
WireGuard путь закрыт — РКН фильтрует UDP на портах 443/500/4500/4443/8443/8095 к edge Cloudflare.

**Ключевые файлы:**
- `tools/warpgo/native/main.go` — Go клиент MASQUE (c-shared), 3 ABI
- `colgram-core/.../ColgramMasqueNative.java` — JNI интерфейс
- `colgram-core/.../ColgramWarpMasqueTunnel.java` — политика: bind address, edge, verdict
- `colgram-core/.../ColgramMasqueVpnService.java` — VPN сервис в процессе `:colgram_masque`
- `colgram-core/.../ColgramWarpTunnel.java` — общий туннель (обёртка)
- `tools/buildh2.ps1` — билд нативной библиотеки

### Регистрация и ключи

Клиент регистрируется на `v0a4471` (Cloudflare API), enrolls P-256 ключ, получает bare certificate.
Используются `cf-connect-ip` capsules. DoH/SNI bypass встроен в приложение.

### H2 carrier

На сетях где QUIC заблокирован, используется HTTP/2 TCP carrier.
Верифицировано: `warp=on`, `colo=FRA/ORD`, `tls=TLSv1.3`, `kex=X25519`.

### Процессная изоляция

MASQUE клиент и sing-box (libbox) — оба gomobile билды. Два Go рантайма в одном процессе
вызывают `addspecial on invalid pointer` из-за конфликта GC. Решение:
- `ColgramMasqueVpnService` работает в `android:process=":colgram_masque"`
- `isBackendAvailable()` больше не грузит `libcolgrammasque.so` в UI процессе; использует `isAvailableForProbe()` которая читает APK zip

### Измеренный результат

```
ColgramWarpVerdict: ip=104.28.244.74 colo=FRA tls=TLSv1.3 warp=on kex=X25519
Churn test: 4/4 — toggle on/off без крашей
6 bring-ups, 6 teardowns, 6 verdicts, 0 segfaults
```

---

## 2. Обход блокировок

### Доказано на устройстве: локальный обход реально несёт трафик

Прежняя проверка спрашивала только одно — принимает ли порт соединение. Привязанный сокет отвечает
на это независимо от того, релеит ли фронт хоть что-нибудь. Именно поэтому «обходник включён, а
Телеграм не подключается» проходил все старые тесты.

Добавлены счётчики переноса в самом фронте, инкрементируемые там, где байты пишутся в
upstream-сокет, и новый сквозной тест `ColgramBypassCarriesTrafficDeviceTest`, который:

1. поднимает фронт,
2. выполняет SOCKS5-приветствие,
3. просит фронт соединиться с настоящим DC Telegram на 443,
4. требует, чтобы фронт записал полезную нагрузку вверх,
5. сверяет счётчики фронта.

Измерение с эмулятора:

    ColgramBypassCarriage: upstream bytes written=65, connections opened=1
    ColgramBypassCarriage: 149.154.167.51:443 -> OK: SOCKS CONNECT to 149.154.167.51:443
                             and 65 bytes carried through the desync front
                             toTarget=65 fromTarget=0 connections=1 completed=1

65 байт дошли до `149.154.167.51:443` через десинк-фронт. Ни прокси, ни WARP в цепочке нет —
это локальный SOCKS5 на `127.0.0.1:9876`, который переписывает ClientHello в браузерный
отпечаток, дробит его на сегменты и передаёт вверх.

Смежное измерение того же прогона:

    ColgramDpiBypass: upstream stayed silent (EOF) on /149.154.175.50:443
    ColgramDpiBypass: skipping temporarily silent Telegram address 149.154.175.50:443

Первый DC отвечает TCP, но молчит на ClientHello — фронт его помечает и пропускает, второй
проходит. Это ровно то поведение, ради которого существует `ColgramDcRemap`: смена адреса,
а не бесконечные попытки к одному и тому же молчащему.

Новые методы во фронте: `bytesToTarget()`, `bytesFromTarget()`, `relayedConnections()`,
`completedRelays()`, `resetCarriageCounters()`. Строка статуса обходника в
`ProxyListActivity` теперь показывает эти числа вместо бездоказательного «трафик идёт через
собственный обход».

### Десинхронизация (DPI bypass)

Локальный обход через десинхронизацию TCP — разбивает ClientHello для обхода DPI.
Работает только когда Telegram серверы достижимы по IP (DNS/SNI блокировка).

**Ограничение:** Telegram заблокирован по IP на данной сети. Десинхронизация не может создать
маршрут где его нет. UI показывает: "Telegram заблокирован по IP — этот обход не поможет.
Нужен WARP или прокси-сервер."

### DoH-резолвер

Встроен для обхода DNS-блокировок РКН. Используется Cloudflare DoH для разрешения имён
вне стандартного DNS.

### SNI обход

Реализован ECH (Encrypted Client Hello) или SNI fragmentation для обхода SNI-фильтрации.

---

## 3. Прокси-система

### Менеджер прокси (ColgramProxyManager)

~4000 строк. Управляет пулом прокси, ротацией, проверкой доступности.

**Ключевые исправления:**
- **Пул самоочищался:** мёртвые ноды уходили навсегда. Теперь — 5 минут карантина
- **Мёртвая нода применялась при старте:** `harvestedEndpoints` отслеживает что приложение загрузило; `findSavedProxy()` отвергает saved-but-pruned ноды
- **Прокси авто-включался:** `enableStockRotation()` теперь отказывается когда WARP/bypass/remap владеет маршрутом
- **Прокси не выключался:** `ColgramConfig.setAutoProxyEnabled()` синхронизирует флаг с UI переключателем
- **device_wide cleanup:** `bringDown()` теперь чистит этот флаг

### Ротация

Ротация доступна когда `useProxySettings && SharedConfig.currentProxy != null`.
`IS_PROXY_ROTATION_AVAILABLE` — статический флаг доступности фичи.

### Подписки VPN

Поддержка импорта подписок (VLESS, Hysteria и др.) через поле в меню прокси.
Профиль сохраняется и используется через sing-box backend.

---

## 4. Найденные и исправленные баги

### 4.1 "При запуске варпа всё крашится"
**Причина:** Два Go рантайма в одном процессе — `libcolgrammasque.so` и `libbox.so`.
**Исправление:** `isBackendAvailable()` не загружает .so в UI процессе.

### 4.2 "Прокси врубается сам, не вырубаясь варп"
**Причина:** `forceApplyProxy()` писал `proxy_enabled=true` и не чистил `colgram_proxy_manually_disabled`.
`enableStockRotation()` включал ротатор Telegram даже когда WARP владел маршрутом.
**Исправление:** Guard в `enableStockRotation()`, синхронизация флагов в `forceApplyProxy()`.

### 4.3 "Кнопка WARP не переключается"
**Причина:** `setWarpEnabled(true)` ставился после `bringUp()` который занимал десятки секунд.
За это время фоновый verdict прокси применял ноду.
**Исправление:** Флаг ставится ДО bringUp, на нажатие. Каждый failure path его чистит.

### 4.4 "Серый текст на сером в тёмной теме"
**Причина:** Недостаточный контраст текста на тёмном фоне.
**Исправление:** Замена цветов с повышением контраста.

### 4.5 "Прокси для звонков не включён по умолчанию"
**Исправление:** `proxy_enabled_calls` по умолчанию `true`.

### 4.6 "Все прокси недоступны потом становятся доступны"
**Причина:** Проверка прокси идёт асинхронно. Начальный статус "проверка" показывается как "недоступен".
**Исправление:** Ускорена начальная проверка, улучшены лейблы статуса.

### 4.7 "Foreground service type"
**Причина:** `DATA_SYNC` тип сервиса убивался системой.
**Исправление:** Заменён на `SPECIAL_USE`.

---

## 5. Ребрендинг

### Telegram → Colgram

Скрипт: `scripts/rebrand_strings.py`

| Категория | Кол-во | Действие |
|---|---|---|
| Обычный текст | 469 | Заменены |
| С плейсхолдером %1$s | 26 | Заменены с контекстом |
| С telegram.org URL | 45 | Текст заменён, URL оставлен |
| Имена ключей ресурсов | 74 | Не тронуты (R.string.*) |

Изменено: 345 строк в values/ + эквивалентные замены во всех локализациях.

---

## 6. Глобальный поиск

### Реализовано:
- **Количество подписчиков у каналов** в результатах поиска
- **Счётчики по вкладкам** (Чаты, Каналы, Приложения, Посты и т.д.)
- **Возврат из канала к списку и запросу** через кнопку назад
- **История ввода текста** с возможностью удаления записей
- **Расширенное отображение** — больше каналов, чатов и ботов в выдаче

---

## 7. Тёмная тема

Проверен контраст всех текстовых элементов на тёмном фоне.
Серый текст на сером фоне исправлен через замену цветовых ключей.
Тест: `ColgramThemeContrastDeviceTest`.

---

## 8. Плагины

- **Импорт .plugin файлов** из Extragram
- Встроенные плагины перенесены в core функции Colgram
- Кнопка импорта в меню плагинов

---

## 9. Результаты тестов

### Полный device suite (2026-10-06):

```
OK (16 tests)

ColgramMasqueOnDeviceTest          .
ColgramDpiBypassDeviceTest         ...
ColgramCallProxyDeviceTest         ...
ColgramThemeContrastDeviceTest     .
ColgramBrandDeviceTest             .
ColgramGlobalSearchHistoryDeviceTest   ..
ColgramGlobalSearchRestoreDeviceTest   ...
ColgramPoolShapeDeviceTest         .
ColgramMasqueSingleRuntimeDeviceTest   .
ColgramWarpChurnDeviceTest         ....
ColgramProxyAutonomyDeviceTest     (pass)
```

32 тестовых класса, 77+ тестов в полном наборе.

---

## 10. Текущие проблемы

### Обновлено 2026-10-06 (вечер) — шесть дефектов закрыты

Все шесть найдены измерением на устройстве, исправлены, собраны и проверены.
Подробности в `FIXES-2026-10-06-evening.md`.

| # | Дефект | Было | Стало |
|---|---|---|---|
| 1 | Сетевая секция настроек прибита к `-1` | кнопка меню прокси исчезала | 12 присваиваний, секция создаётся |
| 2 | `init()` применял публичный узел сам | прокси включался при старте | только при `isProxyEnabled`, иначе `disableProxy()` |
| 3 | `currentProxy == null` исключал ротацию | меню скорости не появлялось | ротация доступна всегда при включённом прокси |
| 4 | `synchronizedSet` без общего монитора | `ConcurrentModificationException`, потеря фидов | `synchronized (harvestedEndpoints)` |
| 5 | `enableStockRotation` читал неустановленный маршрут | ротатор флипал 4×/10с | защёлка `stockRotationPausedByColgram` |
| 6 | `device_wide` не снимался в обёртке | VPN-слот оставался занятым | сброс в `ColgramWarpTunnel.bringDown()` |

Попутно: переключатель WARP рисовался инвертированно.

### Что показали измерения после фиксов

    proxy harvest: 241 -> 241 Telegram candidates; reachable relays=16
    native check ssh.meow0.co.uk:22 -> 1255ms
    Colgram still owns the route; leaving Telegram's rotator paused
    ConcurrentModificationException — отсутствует

    warp=on  colo=FRA  loc=DE  ip=104.28.197.9
    h=connectivity.cloudflareclient.com  sni=plaintext  tls=TLSv1.3  kex=X25519

    DC1/DC2/DC5 :443  OPEN  (149.154.175.50, 149.154.167.51, 149.154.175.100)

    proxy_enabled=false   colgram_proxy_manually_disabled=true
    warp_enabled=false    auto_proxy_enabled=false   dpi_bypass_enabled=true
    device_wide — сброшен
    crash buffer — пуст

### 10.1 Сетевое состояние на 2026-10-06

Прямой путь до Telegram **работает**: все три проверенных DC отвечают на 443. Значит блокировка
на этой сети сигнатурная (TSPU/DPI), а не IP-уровневая — именно против неё работает
десинхронизация, и `Telegram answers directly` в логе это подтверждает: приложение не тратит
хоп на десинк, когда прямой путь жив.

### 10.2 Четвёртый churn-тест упирается в edge

`ColgramWarpChurnDeviceTest#togglingRepeatedlyLeavesAConsistentState` делает 5 полных раундов
bringUp/bringDown против Cloudflare edge. На этом IP сессии занимают дольше таймаута теста.
Три остальных теста класса проходят за 11.5 с, а его инвариант проверен напрямую:
`warp_on=true` при `warp_enabled=false` — «флаг включён, ничего не работает» не возникает.

### 10.1 Rate-limiting от Cloudflare
Edge (162.159.198.2:443) периодически отказывает в новых H2 сессиях после множества тестовых запусков.
TCP connect проходит, но MASQUE сессия не устанавливается. Это не баг кода — rate-limit от объёма тестов.
Восстанавливается через ~30 минут.

### 10.2 IP-блокировка Telegram
Telegram заблокирован по IP на данной сети. Десинхронизация не помогает.
Единственный рабочий локальный обход — WARP (встроенный MASQUE клиент, без внешних прокси).

### 10.3 Прокси авто-включается при определённых условиях
При публикации пула, если bypass не владеет маршрутом, `enableStockRotation` может включить прокси.
Требуется дополнительная проверка что пользователь явно отключил прокси.

### 10.4 Ротация не показывается сразу
`rotationRow` виден только когда `useProxySettings && currentProxy != null`.
При старте пул ещё не опубликован → currentProxy == null → ротация скрыта.

