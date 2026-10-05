# Финальная проверка: APK собран со всеми исправлениями

Дата: 2026-10-01, 12:58–13:00. Устройство: MuMuPlayer, `127.0.0.1:16384`, x86_64.

## Что установлено

```
:TMessagesProj_AppStandalone:assembleAfatRelease
BUILD SUCCESSFUL in 5m 6s
408 actionable tasks: 13 executed, 395 up-to-date

app.apk   555671254   01.10.2026 12:56:13

pm install -r -> Success
dumpsys package org.colgram.messenger | grep lastUpdateTime
    lastUpdateTime=2026-10-01 12:58:49
```

В этот APK вошли все исправления этого и предыдущих кругов: брендинг 697 строк, обработчик
крашей, счётчики вкладок, возврат из канала с позицией прокрутки, история поиска, отправка
автоответа, правка сообщений, IPv4-фронт.

## Обработчик крашей встал в финальной сборке

```
I ColgramCrashGuard: native signal handler installed -> colgram_crash.log
I ColgramCrashGuard: crash reporting installed -> /data/user/0/org.colgram.messenger/files/colgram_crash.log

u0_a61  2299  636  16919620  216708  do_epoll_wait  0  S  org.colgram.messenger
```

Приложение живо, ни `FATAL`, ни `AndroidRuntime` в логе нет.

## warp=on из библиотеки, взятой из установленного APK

Библиотека извлечена из base.apk, который Android поставил, а не скопирована на устройство:

```
pm path org.colgram.messenger
/data/app/~~L6UXfy6GjODu27IQwC3rSQ==/org.colgram.messenger-hoOBmzqKNX5ZmiD8mSInvQ==/base.apk

unzip -j base.apk lib/x86_64/libcolgrammasque.so
```

И она даёт:

```
socks5 udp: dialling 10.0.2.2:15150
socks read 103 bytes, type=1, payload=93
socks read 1210 bytes, type=1, payload=1200
CONNECT status  : 200 OK
trace target    : connectivity.cloudflareclient.com 162.159.137.65
tcp handshake   : established through the tunnel
tls handshake   : complete
request         : GET /cdn-cgi/trace
ip packets      : sent=23 recv=15

ip=104.28.244.74   colo=FRA   loc=RU   tls=TLSv1.3   kex=X25519MLKEM768
warp=on
```

## Все проверки перед сборкой

```
python scripts/run_all_checks.py

 [ok] reflection in colgram-core  -  checked 103 lookups, 47 not statically resolvable, 0 unresolved
 [ok] reflection in the UI files  -  TMessagesProj/colgram: checked 6, skipped 12, unresolved 0
 [ok] auto-reply send path  -  resolves: SendMessagesHelper$SendMessageParams.of(String, long)
 [ok] packaged masque library  -  3 abis current, 0 stale, 0 missing
 [ok] brand strings  -  total changed=0

all checks passed
```

## Что осталось незакрытым

**Туннель на весь телефон.** `ColgramMasqueVpnService` написан и проверен на сборку, но TUN не
устанавливается: для этого нужен системный диалог согласия VpnService, и на этом устройстве его
нельзя пройти тапами - координаты ввода не совпадают с картинкой экрана (окно живёт на
`mDisplayId=12`, а `input` целится в дисплей 0, `screencap` без `-d` тоже снимает не тот).

**Собственный обход без внешнего egress.** Фронт внутри приложения работает - поднимается,
принимает сессию, кадрирует по TCP, отправляет UDP с IPv4-сокета. Дальше упирается в сеть
гостя: один адрес `10.0.2.15`, весь трафик через NAT `10.0.2.2`, и Cloudflare отфильтрован
целиком - весь диапазон `162.159.0.0/16` на 443 и 53, а IPv6 у гостя - уникальные локальные
адреса `fd17::`, маршрутизируемые в интернет. Измерено двадцатью адресами и семью SNI.

`warp=on` на этом устройстве получается с egress, который отвечает, - то есть с моста на хосте.

## Проверка «без внешнего релея», выполненная честно

Мост на хосте был **остановлен**, а фронт поднят из класса, взятого из установленного APK:

```
Get-Process bridge | Stop-Process        -> BRIDGE_DOWN

unzip -j base.apk classes*.dex
classes6.dex -> 2   (ColgramUdpTunnel)

app_process64 -cp .../classes6.dex org.colgram.core.ColgramUdpTunnel
FRONT_PORT=46197
FRONT_ADDR=127.0.0.1:46197
```

И клиент из того же APK:

```
WARP_SOCKS=127.0.0.1:46197 apkh fin/libcolgrammasque.so 10.0.2.15 '' 192.168.0.4

FAILED: no edge route answered: quic dial: timeout: no recent network activity
```

Лог фронта говорит, что именно произошло и что не произошло:

```
I ColgramUdpTunnel: associate on 127.0.0.1:37805, upstream bound to 10.0.2.15
I ColgramUdpTunnel: first datagram: 1200 bytes to /162.159.198.2:4443 from /10.0.2.15
```

Разбор:

- сессия принята, фронт жив;
- сокет **IPv4** и привязан к `10.0.2.15` - то есть исправление против `::` работает и в
  финальной сборке;
- датаграмма **уходит** к edge длиной 1200 байт;
- ответа нет.

То есть встроенный обход работает настолько, насколько позволяет сеть устройства, и упирается в
то, что для него неустранимо: единственный адрес гостя за NAT.

```
ip -br addr
lo      UNKNOWN  127.0.0.1/8 ::1/128
wlan0   UP       10.0.2.15/24 fd17:...:8865/64 fd17:...:703e/64 fe80::.../64
tunl0   DOWN
gre0    DOWN
gretap0 DOWN
sit0    DOWN
ip6tnl0 DOWN
```

Один глобальный адрес, один маршрут по умолчанию, остальные интерфейсы погашены. Выбрать другой
egress устройство физически не может - это не настройка, которая где-то выключена.

## Чистая сборка: тестовые точки убраны из production-кода

Проверка содержимого APK нашла то, чего не должно там быть:

```
FRONT_PORT        -> найден в classes6.dex
ACTION_SELF_TEST  -> найден в classes6.dex
```

`main(String[])`, который печатает `FRONT_PORT` для внешнего теста, попал в библиотеку, потому что
я добавил его в рабочий класс, а не в копию для измерений. Рядом стоял комментарий, прямо
утверждавший обратное:

```
* The class in the APK has no {@code main}, so this is a copy for measurement only. It is not
* shipped
```

Это тот же класс ошибок, что и с обработчиком крашей: комментарий описывает намерение, код делает
противоположное. Проверка содержимого APK ловит такое, а чтение комментария - нет.

Убраны `main` и неиспользуемая константа `ACTION_SELF_TEST`. Фронт проверен на устройстве после
удаления, из отдельной собранной копии:

```
FRONT_PORT=32813
FRONT_ADDR=127.0.0.1:32813
```

Пересобрано, установлено в 13:19:01, содержимое проверено:

```
FRONT_PORT        -> ABSENT
ACTION_SELF_TEST  -> ABSENT
localIPv4         -> classes6.dex (рабочий код на месте)
ColgramUdpTunnel  -> classes6.dex (рабочий код на месте)
```

И в этой сборке, без ничего тестового:

```
BUILD SUCCESSFUL in 1m 55s
408 actionable tasks: 8 executed, 400 up-to-date

I ColgramCrashGuard: native signal handler installed -> colgram_crash.log
I ColgramCrashGuard: crash reporting installed -> /data/user/0/org.colgram.messenger/files/colgram_crash.log

CONNECT status  : 200 OK
tls handshake   : complete
ip packets      : sent=23 recv=16
ip=104.28.244.74   colo=FRA   warp=on
```

## Содержимое установленного APK, проверено поимённо

Не «собралось», а именно «внутри стоит»:

```
localIPv4         -> PRESENT   привязка IPv4 вместо wildcard
updateTitlesFrom  -> PRESENT   точечное обновление подписей вкладок
ColgramCrashGuard -> PRESENT   обработчик крашей
SendMessageParams -> PRESENT   починенная отправка автоответа
Welcome to Colgram -> PRESENT  брендинг в ресурсах

FRONT_PORT        -> ABSENT    тестовая точка убрана
ACTION_SELF_TEST  -> ABSENT    тестовая константа убрана
```

## Где вход блокирует последнюю проверку

Настройки открываются только с главного экрана (`DialogsActivity:13870`,
`SettingsActivity:850`), а тот — за входом. Активности объявлены `android:exported="false"`, так что
прямой `am start` их не открывает:

```
am start -n org.colgram.messenger/org.colgram.core.ColgramSettingsActivity
  Starting: Intent ...
  -> mCurrentFocus=app.lawnchair/LawnchairLauncher
```

Поэтому тумблер WARP проверен по коду - что флаг пишется на тапе, до запуска туннеля, и что при
ошибке и отсутствии активности он сбрасывается, а не остаётся включённым:

```java
// startWarpTunnel() - флаг на тапе
org.colgram.core.ColgramConfig.setWarpEnabled(true);

// openCloudflareWarp() - откат
if (context == null || activity == null) {
    org.colgram.core.ColgramConfig.setWarpEnabled(false);
    return;
}
```

Ключа `warp_enabled` в `colgram_secure_config.xml` на устройстве нет, то есть переключатель там
не включали ни разу, и состояние после щелчка можно будет увидеть только после входа в аккаунт.
