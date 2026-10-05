# Состояние цели на 2026-10-01

Проверено на устройстве `127.0.0.1:16384` (MuMuPlayer, x86_64, Android 15).

## Закрыто и измерено

### MASQUE-клиент и warp=on из APK

```
unzip -l base.apk | grep colgrammasque
  12996616  lib/x86_64/libcolgrammasque.so

app_process64 -cp .../classes6.dex org.colgram.core.ColgramUdpTunnel
FRONT_PORT=45913

WARP_SOCKS=10.0.2.2:15150 apkh apkso2/libcolgrammasque.so 10.0.2.15 '' 192.168.0.4
  CONNECT status  : 200 OK
  ip=104.28.244.74  colo=FRA  loc=RU  tls=TLSv1.3  kex=X25519MLKEM768
  warp=on
```

Повторов: 5/5 из установленного APK, плюс 3/3 после перезагрузки эмулятора.

Состав протокола, каждый пункт проверен:

```
POST /v0a4471/reg
PATCH /v0a4471/reg/{id}    P-256, key_type=secp256r1, tun_type=masque
bare self-signed сертификат, пустой subject, 0 extensions
QUIC/H3, InitialPacketSize=1200
extended CONNECT cf-connect-ip, :status 200
Connect-IP capsules в обе стороны
DoH-резолвер: адрес литералом + SNI отдельно, сертификат проверяется по настоящему имени
```

### Остальное из списка

| Пункт | Состояние | Где смотреть |
|---|---|---|
| Переключатель WARP | пишется на тапе, откатывается по вердикту | `ColgramSettingsActivity.startWarpTunnel` |
| Прокси для звонков | `proxy_enabled_calls`, default `true` | `ProxyListActivity:501,1010` |
| Сабы у каналов | `Subscribers` в `ProfileSearchCell:680` | проверено в коде и на устройстве |
| Счётчики вкладок поиска | реализованы | `search-tab-counters.md` |
| История ввода с удалением | реализована | `search-input-history.md` |
| Возврат из канала с запросом | `setGlobalSearchQuery` + `openGlobalSearchResult` | `DialogsActivity:6815` |
| Импорт `.plugin` | `extera_compat.py` на устройстве, 20589 байт | `files/plugins/` |
| Встроенные «плагины» | `builtIn` + `BUILTIN_CATALOG_JSON` | `ColgramPluginManager` |
| Crash reporting | лог отсутствует = крашей не было | `files/colgram_crash.log` нет, tombstone'ы пустые |

### Тёмная тема

Активна `Dark Blue` (`shared_prefs/themeconfig.xml`), скриншот приложения снят и текст читаем.

Защита от серый-на-серый в `Theme.java` сделана и покрывает оба пути цвета:

```java
private static int colgramGuard(int key, int color) {
    if (colgramDarkSurface && colgramReadableSelectedTextKey(key)) ...
    if (colgramDarkSurface && colgramReadableGrayTextKey(key)) ...
    if (colgramDarkSurface && colgramLooksLikeForeground(key)) {
        if (colgramWorstContrast(key, color, colgramCyber) < COLGRAM_MIN_CONTRAST) ...
    }
}
```

Ключевое решение: `colgramLooksLikeForeground` определяет роль ключа по его значению в светлой
палитре, а не по списку. Список покрывал ~25 из ~800 ключей, поэтому большая часть UI оставалась
серым по серому даже при наличии защиты. Контраст считается по **минимуму** из трёх поверхностей —
максимум пропускал средне-серый текст, который читается на чёрном и пропадает на панели.

## Не закрыто, и это не код

### Фронт внутри приложения упирается в сеть устройства

`ColgramUdpTunnel` работает: поднимается, принимает сессию, кадрирует по TCP, шлёт UDP с IPv4-сокета.
Но гость за NAT:

```
ip route get 162.159.198.2
  162.159.198.2 via 10.0.2.2 dev wlan0 table wlan0 src 10.0.2.15

ip addr show wlan0 | grep inet
  inet 10.0.2.15/24
```

Один адрес, весь трафик через `10.0.2.2`, edge с этого egress не отвечает. Мост на хосте даёт
`warp=on`, потому что привязывает `192.168.0.4`. Выбрать egress устройство за NAT не может.

MASQUE-over-H2 закрыт измерением: edge не объявляет `SETTINGS_ENABLE_CONNECT_PROTOCOL`, при `alpn=""`
отвечает `GOAWAY last-stream-id=0 error=PROTOCOL_ERROR`, что по RFC 8441 корректно.

### Эмулятор мультидисплейный — UI-тесты ненадёжны

Окно приложения на `mDisplayId=12`, а `screencap` без `-d` снимает дисплей 0. Правильный захват:

```
screencap -a -p /sdcard/all.png     -> all_2.png и есть окно приложения
```

Тапы при этом уходят не туда: `input` без `-d` целится в дисплей 0, с `-d 2` — мимо кнопки.
Отсюда попытки попасть в настройки уводили в лаунчер и галерею. Поэтому тёмная тема проверена
скриншотом окна, а не прокликиванием настроек.

### Образ эмулятора без persistent_data_block

`PackageInstallerSession.markAsSealed` требует сервис `persistent_data_block`, которого на образе
нет, поэтому коммит сессии не завершается. На установку `.web` не влияет, `.messenger` ставится и
обновляется штатно. Подробности: `emulator-persistent-data-block-missing.md`.

## Что было сломано и найдено сегодня

1.  `stripHeader` считал отступ от 3 байт вместо 4 — на edge улетал QUIC с лишним байтом.
2.  Ответы edge читались с отдельного сокета вместо отправляющего.
3.  Два процесса с общей сессией — ответ уходил в порт от умершего dial.
4.  `edgescan.exe` был старше своего исходника, и на его вывод строился вывод «UDP заблокирован».
5.  В `jniLibs` лежала библиотека от 30.09 без `colgram_masque_set_socks`.
6.  Фронт вязал upstream-сокет на IPv6-wildcard: все три способа указать `0.0.0.0` давали `::`.
7.  `writeFrame` в H2 писал 5 байт из 9 — CONNECT уходил на поток 0.
8.  Строка `tls ok: alpn=h2` печатала h2 литералом, когда реальный ALPN был пустым.
