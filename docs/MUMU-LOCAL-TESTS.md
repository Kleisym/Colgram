# Локальная проверка Colgram в MuMuPlayer

## Что проверяется

Тесты работают без входа в Telegram и без отправки сообщений. Положительный результат
соединения означает ответ нативного MTProto-checker Telegram, а не только открытый TCP-порт.

- `ColgramDirectConnectionDeviceTest`: обычный Интернет, настоящий локальный DPI-путь,
  отрицательный SOCKS-контроль, явный выбор маршрута, сохранение выключенных настроек,
  production reflection bridge и 20 быстрых циклов start/stop.
- `ColgramDirectApiDeviceTest`: настоящий `help.getConfig` через Generic-соединение tgnet,
  без аккаунта и без тестового SOCKS-front; требуется реальный `TL_config` с DC options.
  На 30-й секунде зависшего Connecting вызывается штатное DNS-восстановление Telegram.
  Общий deadline — 90 секунд; TCP-open и отсутствие исключения не означают успех.
- `ColgramOnboardingDeviceTest`: видимость кнопки сети на welcome/login, реальное нажатие,
  открытие исходной панели прокси с Cloudflare WARP; портретная и альбомная раскладки.
- `ColgramSettingsRowsDeviceTest`, `ColgramDpiBypassDeviceTest`,
  `ColgramThemeContrastDeviceTest`: строки меню, настоящий listener и контраст темы.

Direct-тестовый SOCKS-front слушает только loopback и открывает ровно тот официальный адрес
Telegram, который запросил tgnet. Он не использует внешний прокси, WARP, remap или desync.
Второй положительный тест использует именно production `ColgramDpiBypass`.
Отрицательный front отвечает на TCP/SOCKS, принимает протокольные байты и не отправляет
ни одного байта ответа MTProto. Production checker обязан завершить его неуспехом за 12 секунд.

## MuMu и ADB

Использованный прогон: MuMuPlayer 6.8.9.0, отдельная новая VM №1, Android 15.
Существовавшие VM №0 и №4 не изменяются. Windows VPN и его исключения не изменялись.

Первый NAT-прогон использовал ADB `127.0.0.1:16416`. Его 15 PASS не доказывают
обычный Интернет: Windows выбирает `tun0` для адресов Telegram. Отсутствие VPN внутри
Android само по себе не исключает VPN хоста.

Для отдельного прогона MuMu переведён в сетевой мост на физический
`Intel(R) Ethernet Connection (2) I219-V`. В этой LAN проверен свободный адрес
`192.168.0.220/24`, шлюз/DNS `192.168.0.1`, второй DNS `1.1.1.1`.
ADB моста — `192.168.0.220:5555`; MAC гостя — `08:08:62:2d:8d:21`.
Эти статические адреса относятся к проверенной LAN, а не к произвольной сети.

```powershell
& 'C:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe' info -v all
& 'C:\android-sdk\platform-tools\adb.exe' connect 192.168.0.220:5555
& 'C:\android-sdk\platform-tools\adb.exe' -s 192.168.0.220:5555 shell getprop sys.boot_completed
& 'C:\android-sdk\platform-tools\adb.exe' -s 192.168.0.220:5555 shell ip route show table all
```

В мосту ADB подключается к LAN-адресу Android; NAT-port из `MuMuManager info`
не является адресом этого моста. В госте проверяются маршрут через физический
роутер, `http_proxy=null` и отсутствие сети `TRANSPORT_VPN`.

## Сборка

Исходники Telegram закреплены на `f2908b14133bbffbf7ab04f641ecb5bfaf533242` (12.10.6).
`config/telegram-native.json` закрепляет APK и SHA-256 каждой `libtmessages.49.so`.
Bootstrap сверяет commit, версию, Java JNI descriptor, APK SHA-256 и все ELF до замены файлов.
Подвижный URL загрузки последнего Telegram APK больше не служит источником этих библиотек.

```powershell
Set-Location F:\brom\Colgram
python -m unittest discover -s scripts -p 'test_*.py'
python scripts/host_verify.py --jvm-tests
.\scripts\build-apk.ps1 -Abi arm64-v8a -BuildStamp mumu-cloud-20261114 `
  -Output F:\brom\artifacts\mumu\Colgram-arm64-debug.apk
```

Instrumentation APK собирается из `TMessagesProj_AppTests` задачей
`:TMessagesProj_AppTests:assembleAfatDebugAndroidTest`. Зеркалирование тестов и нужные
`androidTestImplementation` выполняет `scripts/apply-patches.py`.

## Запуск и доказательства

```powershell
$classes = 'org.colgram.core.ColgramDirectConnectionDeviceTest,' +
  'org.colgram.core.ColgramDirectApiDeviceTest,' +
  'org.colgram.core.ColgramOnboardingDeviceTest,' +
  'org.colgram.core.ColgramSettingsRowsDeviceTest,' +
  'org.colgram.core.ColgramDpiBypassDeviceTest,' +
  'org.colgram.core.ColgramThemeContrastDeviceTest'
python scripts/run-device-smoke.py --serial 192.168.0.220:5555 `
  --app-apk F:\brom\artifacts\mumu\Colgram-arm64-debug.apk `
  --test-apk F:\brom\artifacts\mumu\Colgram-device-tests.apk `
  --classes $classes --output F:\brom\artifacts\mumu\ethernet-final
```

Runner сохраняет команды, SHA APK, полный instrumentation output, logcat, состояние сети и
скриншот. Нулевой exit ADB сам по себе не является PASS: runner проверяет каждый terminal
статус теста и завершение `OK (...)`; ошибка теста даёт exit 1.
Отдельный `logcat-live.txt` непрерывно сохраняет тестовые verdicts: недоступный DC
способен переполнить кольцевой буфер Android раньше завершения теста.
Скриншоты каждого welcome/login layout сохраняются приложением в
`/sdcard/Android/data/org.colgram.messenger.web/files/smoke-*.png`.

## Причины исправленных сбоев

| Причина | Исправление |
|---|---|
| Новый `libtmessages.49.so` при старых Java-исходниках вызывал JNI SIGABRT | Версионный native pin и проверка JNI до сборки |
| Getter DPI переписывал `false` в `true`; remap setter всегда записывал `true` | Чтение без побочных эффектов, сохранение аргумента setter |
| Reflection ссылался на отсутствующий `org.telegram.proxy.ProxySettings` | Реальный пакет закреплённого upstream: `org.telegram.utils.proxy.ProxySettings` |
| TCP-open DC отменял явный выбор DPI | Явный маршрут применяется после bind; TCP не считается ответом MTProto |
| Отложенный bind поднимал listener после stop; старый accept сбрасывал новый `bound` | Синхронный loopback bind и владение listener по generation/socket |
| Native checker оставлял молчащий endpoint pending | Собственный 12-секундный deadline и владелец каждого запроса |
| Короткий marker ошибочно считал login visibility patch уже применённым | Отдельные версионные UI-transformers с проверкой anchors и идемпотентности |
| Welcome продолжал грузить Telegram GL texture | Собственное красное облако, отрисованное в GL из vector drawable |

Прогон соединения относится к фактически проверенной сети VM. Он не подменяет
проверку авторизованных чатов, звонков и медиа на телефоне.

## Результат чистого моста 14 ноября 2026

Обычный Интернет в мосту работает: TCP к `1.1.1.1:443`, `example.com:80`,
`google.com:443`, `api.github.com:443` открывается. При этом проверенные официальные
DC Telegram и его web-адреса завершаются TCP timeout. Полученный штатным DNS-bootstrap
адрес также не открыл TCP-соединение. Причину за пределами гостя этот тест не локализует.

Первый полный прогон моста дал 12 PASS из 15. Три FAIL относятся к положительному
Direct-handshake, положительному DPI-handshake и prerequisite TCP-open DC для
теста явного выбора DPI. Проверки меню, щита, выключенных флагов, listener lifecycle,
отрицательного SOCKS-контроля, production reflection bridge и темы прошли.
Отдельный Generic `help.getConfig` не получил ответа за 90 секунд, несмотря на
штатное DNS-восстановление. Это FAIL Direct, а не доказательство исправного соединения.
Повторный полный прогон с этим Generic-тестом дал 12 PASS из 16 и четыре FAIL:
три перечисленных выше и timeout `help.getConfig`. Финальная проверка маршрута
зафиксировала `ordinary=true vpn=false proxy=false warp=false dpi=false remap=false`.
Публичный прокси не подменял неуспешный Generic-запрос.

Независимая запись у сетевого адаптера VM (`ethernet-syn.pcap`) показывает:

| Назначение | Исходящие SYN | Входящие SYN/ACK | Входящие TCP-пакеты |
|---|---:|---:|---:|
| `149.154.167.41:443` | 3 | 0 | 0 |
| `1.1.1.1:443` — контроль | 1 | 1 | 3 |

SHA-256 записи: `7efddf5e156c830d750c9b153ffd8e6143a62025609018564dc48ff0dd8e8f7c`.
Трассировка после записи выключена. На этом маршруте трафик к проверенному DC
останавливается до MTProto payload, поэтому успешные тесты loopback или NAT не
подменяют положительный результат чистого Direct. Публичный прокси или WARP
не включаются скрытно ради зелёного Direct-теста.

Щит открывает `ProxyListActivity` (прокси, Cloudflare WARP, добавление серверов), а не общие настройки Colgram. Красное облако используется на welcome/splash и в заголовке панели прокси.
