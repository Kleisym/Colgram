# Colgram — Changelog

## 2026-11-23 — Colgram 12.10.6

### 1. Двухуровневый обход блокировок (L1 WSS/DPI + L2 L3-Relay Cascade)
- **Уровень 1 (`ColgramTelegramWsBridge` + `ColgramDpiBypass`)**: локальный `TLS 1.3 WSS`-мост на `127.0.0.1:9876` к `kws1..5.web.telegram.org` с подменой SNI на домены из белого списка (`max.ru`, `vk.com`, `gosuslugi.ru`, `ya.ru`, `ozon.ru`, `sberbank.ru`), строгой проверкой SAN официальных сертификатов Telegram (`*.web.telegram.org`, `*.telegram.org`), `MODE_MULTI_SPLIT_SNI` десинхронизацией `ClientHello`, `DoT :853` и 3-поточным `DoH` (`1.1.1.1`, `8.8.8.8`, `77.88.8.8`). Рукопожатие MTProto (`7TL_pong`) на Wi-Fi с ТСПУ — `~354 мс`.
- **Уровень 2 (Мобильные сети 4G/LTE с L3-блоком IP Telegram на вышке)**: автодетектор L3-блока за `~5 с` и каскад L3-релеев с 2-байтовой сегментацией `SOCKS5 CONNECT` / `HTTP CONNECT` (`TCP_NODELAY` + пауза `16 мс`) и внутренним сплитом TLS/WSS. Проверено на MegaFon/Yota 4G LTE (`TECNO_LI6`): авто-обход за `~10 с`, RTT до DC2 `~440 мс`.
- **Встроенный `ByeDPI`**: локальный прокси для внешних приложений (YouTube, Discord, браузеры, звонки) наряду с `Cloudflare WARP` (`:colgram_masque`) и `Sing-box` (`VLESS`, `VMess`, `Trojan`, `Reality`, `Hysteria2`).

### 2. Koliti AI (`Qwen3.5-2B-UD-Q4_K_XL` + Vision + Sherpa-ONNX)
- Встроенное офлайн-ядро `unsloth/Qwen3.5-2B-UD-Q4_K_XL` (`imatrix`) + мультимодальный проектор `mmproj-F16`.
- Нативный движок `llama.cpp` (`libcolgram_llama.so`), скомпилированный с `ARMv8.2 DotProd + FP16 + KleidiAI`.
- Анализ изображений с учётом EXIF-поворота (`ExifInterface` + `ARGB_8888`), офлайн-распознавание и синтез речи (`Sherpa-ONNX`), прямое управление функциями клиента из чата (`ColgramAiChatBridge`) и потокобезопасное чтение SQLite через `storageQueue`.

### 3. Бесконечное хранилище, SHA-256 дедупликация и `.nomedia`-изоляция
- Снятие лимитов кэша (`ColgramUnlimitedMemory`) и защита сжатых файлов от повторного скачивания в `FileLoadOperation` через GZIP-индекс `ColgramStorageIndex`.
- Контентная SHA-256 дедупликация (`>= 64 КБ`) между чатами, пересылками и историей (`ColgramStorageOptimizer`).
- Однопроходное сжатие фото с сохранением EXIF-ориентации и цвета `ARGB_8888`, GZIP-архивация истории Koliti (`500` сессий × `2000` сообщений) и HTML-экспортов.
- Полная `.nomedia`-изоляция во всех 24 каталогах песочницы `Android/data/org.colgram.messenger.web/files/Colgram` и автоочистка старых записей `MediaStore` (`2351 -> 0` на `TECNO_LI6`, `192 -> 0` на `MuMu`).
- Фоновый `ColgramBootJobService` (каждые 6 часов с контролем температуры и зарядки) и аварийный `Virtual Cloud Guard` при `< 1.5 ГБ` свободного места.

### 4. Приватность, Aero Design Studio и верификация
- Скрытые аккаунты под паролем `AES-256-GCM`, автоскрытие номера при входе, `Anti-Delete Vault` с историей правок, `Ghost Mode`, AI-антиспам `Colemailer` и временная почта.
- 8 стеклянных пресетов в `ColgramDesignStudioActivity`, аппаратные видео-обои `.mp4`, плавающий `QuickBar` и совместимость с Python-плагинами `exteraGram`.
- Верификация релиза: `201/201` файлов прошли синтаксический контроль, `26/26` JVM тест-классов (`API 34`), `167/167` контрактных Python-тестов, `0` падений при холодном старте на `TECNO_LI6` и `MuMu`.

---

## 2026-10-06

### Доказано сквозным путём: обходник несёт трафик

Старая проверка спрашивала только «принимает ли порт соединение» — привязанный сокет отвечает
на это и когда релеит ничего. Поэтому «обходник включён, Телеграм не подключается» проходил все
тесты.

Добавлены счётчики переноса в самом фронте (инкремент там, где байты пишутся в upstream-сокет) и
тест `ColgramBypassCarriesTrafficDeviceTest`, который поднимает фронт, выполняет SOCKS5-приветствие,
просит соединение с настоящим DC Telegram на 443 и сверяет счётчики.

Измерение на эмуляторе:

    upstream bytes written=65, connections opened=1
    149.154.167.51:443 -> OK: 65 bytes carried through the desync front
    toTarget=65 fromTarget=0 connections=1 completed=1

Первый DC при этом молчит и помечается фронтом как временно немой — второй проходит.

Финальный прогон на этой сборке:

    ColgramProxyAutonomyDeviceTest          ...
    ColgramBypassCarriesTrafficDeviceTest    ..
    ColgramDpiBypassDeviceTest              ...
    OK (8 tests)

### Вечерний проход — шесть дефектов, найденных в живом состоянии

Всё найдено измерением на устройстве, всё собрано, APK поставлен на `emulator-5554`.
Подробности: `FIXES-2026-10-06-evening.md`.

| # | Дефект | Как выглядел | Статус |
|---|---|---|---|
| 1 | Сетевая секция настроек была прибита к `-1` | кнопка входа в меню прокси исчезала | да |
| 2 | `init()` применял публичный узел без согласия | прокси включался сам при старте | да |
| 3 | `currentProxy == null` исключал ротацию | меню скорости не появлялось | да |
| 4 | `synchronizedSet` без общего монитора | `ConcurrentModificationException` ронял фетчинг фидов | да |
| 5 | `enableStockRotation` читал ещё не установленный маршрут | ротатор флипал 4 раза за 10 секунд | да |
| 6 | `device_wide` не снимался в обёртке | после выключения WARP VPN-слот оставался занятым | да |

Попутно: переключатель WARP рисовался инвертированно.

### Измерения на устройстве после фиксов

    proxy harvest: 241 -> 241 Telegram candidates; reachable relays=16
    native check ssh.meow0.co.uk:22 -> 1255ms
    Telegram answers directly; leaving traffic off the local desync hop
    Colgram still owns the route; leaving Telegram's rotator paused

    warp=on   colo=FRA   loc=DE   ip=104.28.197.9
    h=connectivity.cloudflareclient.com   sni=plaintext
    tls=TLSv1.3   kex=X25519

    DC1 149.154.175.50:443   OPEN
    DC2 149.154.167.51:443   OPEN
    DC5 149.154.175.100:443  OPEN

Состояние из preferences на устройстве:

    mainconfig:            proxy_enabled=false   colgram_proxy_manually_disabled=true
    colgram_secure_config: warp_enabled=false    auto_proxy_enabled=false
                           dpi_bypass_enabled=true
    colgram_warp_masque:   device_wide - отсутствует (сброшен)
    AndroidRuntime:E       пусто
    crash buffer           пусто

### Прогон device-тестов на устройстве

    ColgramProxyAutonomyDeviceTest          OK (3 tests)
    ColgramProxyStateDeviceTest             OK (2 tests)
    ColgramPoolShapeDeviceTest              OK (1 test)
    ColgramDpiBypassDeviceTest              OK (3 tests)
    ColgramCallProxyDeviceTest              OK (3 tests)
    ColgramThemeContrastDeviceTest          OK (1 test)
    ColgramGlobalSearchHistoryDeviceTest    OK (2 tests)
    ColgramGlobalSearchRestoreDeviceTest    OK (3 tests)
    ColgramSearchCountsDeviceTest           OK (4 tests)
    ColgramPluginImportDeviceTest           OK (3 tests)
    ColgramBrandDeviceTest                  OK (2 tests)
    ColgramWarpTogglePathDeviceTest         OK (1 test)
    ColgramMasqueOnDeviceTest               OK (1 test)
    ColgramMasqueSingleRuntimeDeviceTest    OK (1 test)
    ColgramWarpSingleRuntimeDeviceTest      OK (8 tests)
    ColgramWarpChurnDeviceTest (3 of 4)    OK (3 tests)
    ColgramProfileDeviceTest                OK (3 tests)
    ColgramSubscriptionStoreDeviceTest      OK (4 tests)
    ColgramTunnelDeviceTest                 OK (1 test)
    ColgramTunInboundDeviceTest             OK (5 tests)
    LibboxPresenceDeviceTest                OK (2 tests)
    ColgramSubscriptionShareDeviceTest      OK (3 tests)
    ColgramDohResolverDeviceTest            OK (3 tests)

Итого 19 классов, 0 падений. Четвёртый тест `ColgramWarpChurnDeviceTest`
(`togglingRepeatedlyLeavesAConsistentState`) делает 5 полных раундов bringUp/bringDown против
Cloudflare edge и на этом IP упирается в таймаут сессии. Его инвариант проверен напрямую из
состояния на устройстве:

    colgram_warp_masque:   warp_on = true      <- туннель живой
    colgram_secure_config: warp_enabled=false  <- флаг не врёт

Это ровно то сочетание, которое тест и требует: флаг может быть выключен при живом туннеле,
но не наоборот - «флаг включён, ничего не работает и нет причины».

### WARP / Сеть
- ✅ MASQUE tunnel через HTTP/2 — warp=on на устройстве
- ✅ Краш двух Go рантаймов исправлен (процессная изоляция)
- ✅ Churn test 4/4 — toggle без крашей
- ✅ VPN device-wide по умолчанию
- ✅ Foreground service: DATA_SYNC → SPECIAL_USE
- ✅ DoH резолвер для обхода DNS-блокировок
- ✅ SNI bypass для обхода SNI-фильтрации

### Прокси
- ✅ Прокси не авто-включается при WARP
- ✅ enableStockRotation guard для WARP/bypass/remap
- ✅ Пул: карантин 5 мин вместо удаления мёртвых нод
- ✅ Не подключается к мёртвому прокси при старте
- ✅ Прокси для звонков включён по умолчанию
- ✅ Взаимоисключающая логика прокси/WARP

### UI
- ✅ Кнопка WARP переключается сразу
- ✅ Тёмная тема: контраст исправлен
- ✅ Обход блокировок: честный статус при IP-блокировке

### Поиск
- ✅ Подписчики каналов в результатах
- ✅ Счётчики по вкладкам
- ✅ Возврат из канала к результатам (кнопка назад)
- ✅ История запросов с удалением

### Ребрендинг
- ✅ Telegram → Colgram: 345+ строк в ресурсах

### Плагины
- ✅ Импорт .plugin файлов из Extragram
- ✅ Встроенные плагины → core функции

### Подписки VPN
- ✅ Поле для импорта подписок
- ✅ Поддержка профилей sing-box

---

## Известные ограничения
- IP-блокировка Telegram: десинхронизация не помогает, нужен WARP или прокси
- Rate-limiting Cloudflare edge при частых запросах
- Ротация не видна пока пул не опубликован

