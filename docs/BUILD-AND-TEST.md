# Проверенная локальная сборка

Рабочая ветка: `fix/network-proxy-resource-polish`.
Исходный Colgram: `89f223b9b455eb96d880e3e5c1efd3b27b91ccfa`.
База Telegram последнего опубликованного Colgram: `f2908b14133bbffbf7ab04f641ecb5bfaf533242` (12.10.6).
Версия базы закреплена: переход на произвольный новый Telegram требует отдельной проверки всех хуков.

## Материалы

- Windows, Git, Python 3.11, JDK 17.
- Android SDK: платформы 34 и 36, build-tools 36.0.0, принятые лицензии.
- Для пересборки WARP: Android NDK r27c и Go с автоматическим выбором toolchain из `go.mod`.
- Зависимости Gradle скачиваются при первой сборке; vendored-модули и нативные библиотеки входят в исходный архив.

## Сборка

Из корня распакованных исходников:

```powershell
$env:JAVA_HOME = 'C:\colgram-tools\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\android-sdk'
python scripts/host_verify.py --jvm-tests
powershell -ExecutionPolicy Bypass -File scripts/build-apk.ps1 -Abi arm64-v8a
```

`build-apk.ps1` подготавливает закреплённую базу при её отсутствии, проверяет SHA,
накладывает патчи и собирает APK. По умолчанию остаётся только ARM64, чтобы не
переносить на телефон библиотеки остальных архитектур. Для эмулятора подходит
`-Abi x86_64`, для полного набора — `-Abi universal`.

Полученный отладочный APK использует `org.colgram.messenger.web`. Он устанавливается
отдельно от опубликованного `org.colgram.messenger`, а не подменяет его сертификат
подписи. История аккаунта и локальные данные двух установок раздельны.

## Пересборка нативного WARP

```powershell
powershell -ExecutionPolicy Bypass -File tools/buildh2.ps1 `
  -NdkRoot 'C:\Colgram\tools\ndk\android-ndk-r27c' `
  -Go 'C:\Colgram\tools\go\bin\go.exe'
```

Скрипт останавливается на ошибке компилятора, проверяет ELF и только затем заменяет
библиотеки трёх поддержанных MASQUE ABI. Неудачная сборка не выдаёт старую `.so` за новую.

## Регрессии

```powershell
python scripts/test_device_test_runner.py
python scripts/test_app_regressions.py
python scripts/test_route_cancellation.py
python scripts/test_warp_regressions.py
python scripts/host_verify.py --jvm-tests
```

Нативные тесты не подключаются к публичным прокси или аккаунтам Cloudflare:

```powershell
Push-Location tools/warpgo/native
& 'C:\Colgram\tools\go\bin\go.exe' test -v packet_queue.go packet_queue_test.go
Pop-Location
```

Они проверяют целые IP-пакеты, границы очереди и отмену ожидания, отказ чужому TLS
сертификату и правильный/неправильный ключ самоподписанного MASQUE endpoint.
Проверки исходных контрактов дополняют компиляцию, но не заменяют Android-тестирование.

## Проверка на телефоне

Инструментальные тесты `ColgramSettingsRowsDeviceTest` проверяют позиции/типы меню
и сохранение явного выключения. Сетевой приёмочный прогон дополнительно включает:

1. Обычный интернет на Wi-Fi и мобильной IPv4-only сети после режима IPv6.
2. WARP: запуск, отмену во время регистрации, отказ VPN-согласия, включение/выключение 20 раз.
3. Переход подписка → обычный интернет и WARP → прокси во время незавершённого запуска.
4. Wi-Fi → мобильная сеть, сон, отзыв VPN-разрешения; строка состояния не должна выдавать старое подтверждение за подключение.
5. Отправку/приём файлов и звонок через выбранный маршрут, контроль ANR и батареи.

Подключённого Android-устройства во время локальной проверки нет. Телефонный
прогон и измерение расхода батареи отделены от результатов компиляции и host-тестов.

## Данные и шифрование

Неограниченный кэш теперь включается только явно. При переходе со старого
неявного значения снимается только прежний автоматический unlimited-sentinel;
конечные лимиты и явно выбранный unlimited сохраняются. Штатные экраны управляют
автозагрузкой и сроками кэша. Бесконечный цикл перекомпрессии не запускается.

SOCKS5 и HTTP CONNECT не добавляют собственного шифрования. Telegram использует
MTProto; WARP добавляет TLS-туннель. Нативный клиент проверяет публичный ключ MASQUE
из HTTPS-ответа регистрации, а API/DoH/HTTPS-проверка используют проверку сертификатов.
Связь сверена с [реализацией MASQUE в usque](https://github.com/Diniboy1123/usque/blob/main/api/masque.go)
и [реализацией pinning в Xray](https://github.com/XTLS/Xray-core/blob/main/transport/internet/masque/warp.go).

Все 24 предложенных каталога и найденные машиночитаемые адреса отражены в
`proxy-source-audit.json`. Статус HTTP 200 у каталога не означает, что его прокси
доступны с конкретного телефона: рабочим узлом считается результат Telegram-handshake.
