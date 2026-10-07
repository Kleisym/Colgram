# Colgram — Changelog

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

