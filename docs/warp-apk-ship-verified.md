# WARP в APK: библиотека пересобрана и проверена на устройстве

Дата: 2026-10-01. Устройство: MuMuPlayer, `127.0.0.1:16384`, x86_64, `10.0.2.15`.

## Что было не так

`warp=on` был получен, но из `.so`, скопированного на устройство вручную. В APK лежала другая —
собранная 30.09, до появления SOCKS-поддержки:

```
jniLibs/arm64-v8a/libcolgrammasque.so   12046184  30.09.2026 18:29:36
jniLibs/x86/libcolgrammasque.so         12122368  30.09.2026 18:30:04
jniLibs/x86_64/libcolgrammasque.so      12965024  30.09.2026 18:26:42
```

Проверка это вскрыла: библиотека из установленного APK не знала про `colgram_masque_set_socks`,
поэтому не брала обход и уходила в прямой QUIC, который на устройстве фильтруется:

```
library : /data/local/uq/apkso/libcolgrammasque.so
version : colgram-masque/1
socks   : (не напечатан — символа нет)
edge candidate 1/6  162.159.198.2:443 via 10.0.2.15
  failed : quic dial: timeout: no recent network activity
... ещё 5 кандидатов, тот же результат
FAILED: no edge route answered: quic dial: timeout: no recent network activity
```

Это ровно та ошибка, которую приложение показывало пользователю: тумблер включён, а туннель не
поднимается.

## Что пересобрано

Все три ABI из текущего `tools/warpgo/native/main.go`:

```
arm64-v8a/libcolgrammasque.so   12078256  01.10.2026 4:43:46
x86/libcolgrammasque.so         12153968  01.10.2026 4:47:01
x86_64/libcolgrammasque.so      12996616  01.10.2026 4:45:28
```

Символ на месте:

```
llvm-nm -D out_arm64.so | grep colgram_masque
T colgram_masque_measure
T colgram_masque_exchange
T colgram_masque_last_error
T colgram_masque_open_session
T colgram_masque_close_session
T colgram_masque_set_socks
T colgram_masque_version
```

## Замер новой библиотеки на устройстве

Библиотера залита отдельно и запущена тем же harness, что и раньше:

```
library : /data/local/uq/fresh.so
version : colgram-masque/1
socks   : 10.0.2.2:15150

socks read 103 bytes, type=1, payload=93
socks read 1210 bytes, type=1, payload=1200
CONNECT status  : 200 OK
tls handshake   : complete
ip packets      : sent=23 recv=16

ip=104.28.244.74
colo=FRA
loc=RU
warp=on
```

## Замер библиотеки, извлечённой из установленного APK

Это финальная проверка. `warp=on` получен библиотекой, взятой не из локальной копии, а прямо из
APK, который Android установил на устройство:

```
pm path org.colgram.messenger
/data/app/~~PcwAcqoDukHwkirRoEEfCQ==/org.colgram.messenger-9lCBNxfZlD9haOyWiFxMug==/base.apk

unzip -l base.apk | grep x86_64/libcolgrammasque
 12996616  1981-01-01 01:01   lib/x86_64/libcolgrammasque.so
```

И этот файл запущен на устройстве:

```
library : /data/local/uq/apkso2/libcolgrammasque.so
version : colgram-masque/1
socks   : 10.0.2.2:15150

socks5 udp: dialling 10.0.2.2:15150
socks read 109 bytes, type=1, payload=99
socks read 1210 bytes, type=1, payload=1200
CONNECT status  : 200 OK
tcp handshake   : established through the tunnel
tls handshake   : complete
request         : GET /cdn-cgi/trace
ip packets      : sent=23 recv=15

ip=104.28.244.74
colo=FRA
loc=RU
tls=TLSv1.3
kex=X25519MLKEM768
warp=on
```

Пять запусков подряд, все с тем же исходом:

```
run 1 : warp_on=1 ip=104.28.244.74 colo=FRA
run 2 : warp_on=1 ip=104.28.244.74 colo=FRA
run 3 : warp_on=1 ip=104.28.244.74 colo=FRA
run 4 : warp_on=1 ip=104.28.244.74 colo=FRA
run 5 : warp_on=1 ip=104.28.244.74 colo=FRA
```

Полные выводы: `warp-on-from-installed-apk.txt`, повторы: `warp-on-from-installed-apk-repeat.txt`.

## Сборка, которая дала этот APK

```
:TMessagesProj_AppStandalone:assembleAfatRelease
BUILD SUCCESSFUL in 3m 8s
408 actionable tasks: 15 executed, 393 up-to-date

app.apk   555671486   01.10.2026 4:50:55
```

Установлено и обновлено:

```
pm install -r /data/local/tmp/cgrel2.apk   -> Success
dumpsys package org.colgram.messenger | grep lastUpdateTime
    lastUpdateTime=2026-10-01 04:53:00
```

## Обвязка в приложении

`ColgramMasqueNative.setSocksFront(hostAndPort)` вызывает нативный
`colgram_masque_set_socks`, и `ColgramWarpMasqueTunnel.setSocksFront` хранит адрес и применяет его
перед каждой попыткой:

```java
public static void setSocksFront(String hostAndPort) {
    socksFront = (hostAndPort == null || hostAndPort.trim().isEmpty())
            ? null : hostAndPort.trim();
    ColgramMasqueNative.setSocksFront(socksFront);
}
```

Применяется перед каждой попыткой, а не один раз: фронт может подняться или упасть между попытками,
и устаревшее значение отправляет туннель в никуда.

## Как собрать библиотеку заново

```powershell
$ndk='C:\android-sdk\ndk\26.1.10909125'
$tc="$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin"
$env:GOCACHE='C:\Colgram\tools\gocache'
$env:CGO_ENABLED='1'; $env:GOOS='android'
cd C:\Colgram\tools\warpgo\native

# arm64
$env:GOARCH='arm64'; $env:CC="$tc\aarch64-linux-android24-clang.cmd"
go build -buildmode=c-shared -o out_arm64.so .

# x86_64
$env:GOARCH='amd64'; $env:CC="$tc\x86_64-linux-android24-clang.cmd"
go build -buildmode=c-shared -o out_x86_64.so .

# x86
$env:GOARCH='386'; $env:CC="$tc\i686-linux-android24-clang.cmd"
go build -buildmode=c-shared -o out_x86.so .
```

Три ошибки, на которые уходит время, если не знать:

-   `GOOS=linux` вместо `android` даёт `cannot use _Ctype_socklen_t ... in argument to
    getnameinfo` — net собирается системным clang вместо NDK.
-   `GOTOOLCHAIN=local` даёт `go.mod requires go >= 1.26.0`, потому что `go.mod` требует 1.26, а
    локальный go 1.23.4.
-   `GOOS=android GOARCH=arm64-v8a` даёт `-buildmode=c-shared not supported` — имя arch здесь
    `arm64`, а `arm64-v8a` это каталог в jniLibs.
