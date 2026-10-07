# Фиксы 2026-10-06 (вечер) — четыре дефекта, найденные в текущем состоянии

Четыре правки, каждая — по измерению на устройстве, не по догадке.

---

## 1. Пропала кнопка входа в меню прокси и подключений

`ColgramSettingsActivity.fillItems()`:

    networkHeaderRow = dpiBypassRow = dohRow = builtinProxyRow = proxyBrowserRow =
            currentProxyRow = ownProxyRow = proxyStatusRow = relayUrlRow = autoProxyRow =
            ipv6BypassRow = dcRemapRow = networkSectionRow = -1;

Вся сетевая секция была прибита к `-1` и **никогда нигде не присваивалась**. Проверено
поиском: `currentProxyRow =` встречается в файле ровно один раз — в этой строке.

Следствие: ни одна из 12 строк не создавалась. Клик-обработчик и фабрика ячеек держали
ветки для строк, в которые нельзя было попасть. Это и есть «вышел с меню и пропала кнопка
для входа в меню прокси и подключений».

**Фикс:** секция снова занимает строки — 12 присваиваний вместо одного `-1`.

---

## 2. Прокси включался сам при каждом старте

`ColgramProxyManager.init()`, ветка встроенного обхода:

    boolean directDead = ColgramDcRemap.telegramDcProbe() < 0;
    if (local != null && verifiedBest != null && directDead) {
        forceApplyProxy(verifiedBest);   // proxy_enabled=true, без чьего-либо разрешения
    }

Ветка срабатывает на каждом старте, пока включён обходник, и `directDead` на этой сети
истинно. `forceApplyProxy` пишет `proxy_enabled=true` во все слоты `mainconfig`, то есть
переключатель «Использовать прокси» становится синим сам.

**Фикс:** запасной публичный узел применяется только если пользователь сам держал
переключатель прокси включённым. Иначе `disableProxy()`.

---

## 3. Меню ротации не появлялось

`ProxyListActivity.updateRows()` требовало `SharedConfig.currentProxy != null`. На холодном
старте пул ещё не опубликован, `currentProxy` == null, блок ротации не создаётся вообще —
вместе с полями скорости. Отсюда «не всегда появляется меню с выбором скорости ротации».

**Фикс:** `currentProxy == null` больше не исключает ротацию — она настраивает ротатор,
который сам выберет узел. Заодно `rotationTimeoutRow` добавлен в `isEnabled()` адаптера:
ползунок таймаута создавался, но не отвечал на тап.

---

## 4. ConcurrentModificationException ронял фетчинг фидов

Живой лог с устройства, сразу после установки:

    20:04:25.527  W ColgramProxyManager: proxy feed task failed
    20:04:25.540  W ColgramProxyManager: java.util.ConcurrentModificationException
    20:04:25.540  W ColgramProxyManager:     at java.util.LinkedHashMap$LinkedHashIterator.remove(LinkedHashMap.java:1074)
    20:04:25.540  W ColgramProxyManager:     at org.colgram.core.ColgramProxyManager.rememberHarvested(ColgramProxyManager.java:3491)
    20:04:25.540  W ColgramProxyManager:     at org.colgram.core.ColgramProxyManager.addCandidate(ColgramProxyManager.java:2814)
    20:04:25.540  W ColgramProxyManager:     at org.colgram.core.ColgramProxyManager.fetchSocksList(ColgramProxyManager.java:3031)

Два фид-треда доходят до `rememberHarvested()` одновременно. `Collections.synchronizedSet`
гарантирует атомарность отдельной операции, но не последовательности `add()` → `size()` →
`iterator().remove()`. Итог — исключение вылетает из обоих потоков, задача фетча роняется,
целый источник кандидатов теряется, пул остаётся почти пустым. Это и есть «прокси грузятся
супер долго и ничего не доступно».

**Фикс:** вся последовательность под одним монитором `synchronized (harvestedEndpoints)`.

---

## Попутно

Инвертированное состояние переключателя WARP в `ColgramSettingsActivity`:

    // было
    ((TextCheckCell) view).setChecked(!ColgramConfig.isWarpEnabled() && !warpStartPending);
    // стало
    ((TextCheckCell) view).setChecked(ColgramConfig.isWarpEnabled() || warpStartPending);

При выключенном WARP переключатель рисовался включённым.

---

## 5. Ротатор включался-выключался четыре раза за десять секунд

Живой лог с устройства после установки сборки с фиксами 1–4:

    20:09:30.175  Telegram stock rotation paused while a Colgram bypass route owns traffic
    20:09:30.186  Telegram's own proxy checker and rotator enabled
    20:09:30.357  Telegram stock rotation paused while a Colgram bypass route owns traffic
    20:09:30.358  Telegram's own proxy checker and rotator enabled
    20:09:34.602  Telegram stock rotation paused while a Colgram bypass route owns traffic
    20:09:34.727  Telegram's own proxy checker and rotator enabled

`disableStockRotation()` вызывается из пути старта обхода, `enableStockRotation()` — из пути
публикации пула, на несколько миллисекунд позже. Страховка в `enableStockRotation()` читает
`currentActiveProxy`, который в этот момент ещё `null` — `forceApplyProxy()` не успел его
поставить. Ротатор возвращался, и Telegram'ов собственный чекер выбирал узел сам.

**Фикс:** защёлка `stockRotationPausedByColgram` вместо опроса живого маршрута.

- взводится в `disableStockRotation()` и в `disableProxy()`
- снимается только в `onStockProxyToggle(true)` — то есть по нажатию самого переключателя
- `enableStockRotation()` при взведённой защёлке выходит сразу

Кто писал последним, тот и решал: теперь решает только рука пользователя.

---

## Что показал лог после фиксов 1–4

    20:09:27.351  socks source .../Socks5.txt added 24
    20:09:27.470  socks source .../socks5.txt added 24
    20:09:30.166  proxy harvest: 5 -> 241 Telegram candidates; reachable relays=0
    20:09:30.431  Telegram answers directly; leaving traffic off the local desync hop

`ConcurrentModificationException` исчез: раньше те же источники давали `added 0` и роняли
задачу, теперь каждый даёт 24 кандидата, пул вырос с 5 до 241.

Строка `Telegram answers directly` — это фикс №2 в действии: раньше эта же проверка приводила
к `forceApplyProxy(verifiedBest)` и переключатель «Использовать прокси» становился синим сам.

Состояние, прочитанное из preferences на устройстве после перезапуска:

    <boolean name="proxy_enabled" value="false" />
    <boolean name="colgram_proxy_manually_disabled" value="true" />

Оба флага согласованы, `Applying proxy` в логе нет, `AndroidRuntime:E` пуст, PID жив.

---

## 6. Флаг device-wide не снимался при выключении WARP

`ColgramWarpMasqueTunnel.bringDown()` сбрасывал `device_wide`, но `ColgramWarpTunnel.bringDown()`
— обёртка, которую зовут обе строки UI — нет. Путь выключения из экрана прокси шёл мимо
сброса, и на устройстве после одного цикла on/off оставалось:

    colgram_secure_config:  warp_enabled = false
    colgram_warp_masque:    device_wide  = true

Следующий старт находил флаг поднятым и ставил VPN-интерфейс для выключенного туннеля.

**Фикс:** `ColgramWarpTunnel.bringDown()` тоже снимает флаг, используя переданный `ctx`
вместо `appContext` из MASQUE-туннеля. Оба вызывающих пути теперь гарантированно чистят.
