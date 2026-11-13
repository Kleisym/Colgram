# Локальная проверка Colgram в MuMuPlayer

## Что проверяется

Тесты работают без входа в Telegram и без отправки сообщений. Положительный результат
соединения означает ответ нативного MTProto-checker Telegram, а не только открытый TCP-порт.

- `ColgramDirectConnectionDeviceTest`: обычный Интернет, настоящий локальный DPI-путь,
  отрицательный SOCKS-контроль, явный выбор маршрута, сохранение выключенных настроек,
  production reflection bridge и 20 быстрых циклов start/stop.
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

Использованный прогон: MuMuPlayer 6.8.9.0, отдельная новая VM №1, Android 15,
ADB `127.0.0.1:16416`. Существовавшие VM №0 и №4 не изменяются.

```powershell
& 'C:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe' info -v all
& 'C:\android-sdk\platform-tools\adb.exe' connect 127.0.0.1:16416
& 'C:\android-sdk\platform-tools\adb.exe' -s 127.0.0.1:16416 shell getprop sys.boot_completed
```

ADB serial берётся из `MuMuManager info`; номер порта принадлежит конкретной VM.

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
  'org.colgram.core.ColgramOnboardingDeviceTest,' +
  'org.colgram.core.ColgramSettingsRowsDeviceTest,' +
  'org.colgram.core.ColgramDpiBypassDeviceTest,' +
  'org.colgram.core.ColgramThemeContrastDeviceTest'
python scripts/run-device-smoke.py --serial 127.0.0.1:16416 `
  --app-apk F:\brom\artifacts\mumu\Colgram-arm64-debug.apk `
  --test-apk F:\brom\artifacts\mumu\Colgram-device-tests.apk `
  --classes $classes --output F:\brom\artifacts\mumu\final-device
```

Runner сохраняет команды, SHA APK, полный instrumentation output, logcat, состояние сети и
скриншот. Нулевой exit ADB сам по себе не является PASS: runner проверяет каждый terminal
статус теста и завершение `OK (...)`; ошибка теста даёт exit 1.
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

Щит открывает `ProxyListActivity` (прокси, Cloudflare WARP, добавление серверов), а не общие настройки Colgram. Красное облако используется на welcome/splash и в заголовке панели прокси.
