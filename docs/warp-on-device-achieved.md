# WARP работает на устройстве. Вот где был дефект.

Дата: 2026-10-01. Устройство: MuMuPlayer, `127.0.0.1:16384`, x86_64, `10.0.2.15`.
Выход: `warp=on`, `ip=104.28.244.74`, `loc=RU`, `colo=FRA`, `tls=TLSv1.3`, `kex=X25519MLKEM768`.

## Где всё это время уходило не туда

Три вывода оказались неверными, и каждый был следствием сломанного измерителя, а не сети.

### 1. «UDP 443 заблокирован на устройстве» — инструмент врал

`edgescan.exe` лежал на диске с 18:35 30.09, а `edgescan/main.go` менялся позже. Бинарник
и исходник разошлись, и всё, что печатал бинарник, относилось к старой версии. После
пересборки на хосте:

    162.159.198.2:4500   HANDSHAKE OK in  296ms
    162.159.198.2:8443   HANDSHAKE OK in  304ms
    162.159.198.2:500    HANDSHAKE OK in  303ms
    162.159.198.2:443    HANDSHAKE OK in  284ms

UDP к edge не заблокирован. Он отвечает на всех портах примерно за 300 мс.

Собранный из того же исходника под Android и запущенный на устройстве даёт обратное —
это настоящий факт о телефоне, и фильтр на нём есть:

    SCAN_PORT=443   handshake failed after 5003ms  timeout: no recent network activity
    SCAN_PORT=500   handshake failed after 5002ms  timeout
    SCAN_PORT=8443  handshake failed after 5004ms  timeout
    SCAN_PORT=4500  handshake failed after 5001ms  timeout

### 2. `down 0` — ответы читались не с того сокета

Мост держал два сокета на сессию: `up` для отправки к edge и отдельный `reply` для чтения
ответов. Ответ приходит на тот адрес и порт, с которого ушёл Initial, то есть на `up`.
Второй сокет был просто сокетом, куда никто ничего не отправляет:

    session 127.0.0.1:62997: egress 192.168.0.4:0, advertise 10.0.2.2:8868
    up 60 pkts / 72060 bytes   down 0 pkts / 0 bytes   sessions 1

60 датаграмм ушло, ноль пришёл, ошибок нет. Ответы убраны на тот же сокет, что шлёт.

### 3. Настоящая причина, почему ответы не доходили

В `stripHeader` отступ считался от трёх байт:

```go
off := 3
switch frame[off] {
case 1:
	off += 4
```

SOCKS5-заголовок по RFC 1928 — это четыре байта до адреса: два reserved, один fragment, один
address type. При `off := 3` верхний байт порта оставался в начале полезной нагрузки, и на edge
улетал QUIC-пакет с лишним байтом впереди. Такой пакет edge игнорирует без единого ответа.
Симптом был точно такой же — счётчик `up` растёт, `down` на нуле.

## Связка, которая даёт результат

Один процесс вместо двух. Ответ уходит на сокет той же сессии, так что устареть он не может:

```
устройство  --TCP 15150-->  bridge  --UDP 443-->  162.159.198.2
   QUIC Initial          SOCKS5 UDP           QUIC Initial
```

Мост меняет три вещи на хосте и ничего больше: слушает TCP-порт, держит UDP-сокет на своём
egress, пересылает датаграммы. Маршруты, firewall, DNS и VPN не трогаются.

```
tools/warpgo/bridge   bridge.exe
  BRIDGE_LISTEN    TCP, куда стучится устройство
  BRIDGE_ADVERTISE адрес, который устройство считает нашим
  BRIDGE_UPSTREAM  edge
  BRIDGE_BIND      egress, с которого уходит QUIC
```

## Замер с устройства

Полный вывод: `docs/warp-on-device-proven.txt`, повторы: `docs/warp-on-device-repeat.txt`.

```
session address : 172.16.0.2
edge candidate  1/6  162.159.198.2:443 via 10.0.2.15
socks5 udp: dialling 10.0.2.2:15150
masque over socks5: 10.0.2.2:15150
socks read 100 bytes, type=1, payload=90
socks read 1210 bytes, type=1, payload=1200
CONNECT status  : 200 OK
trace target    : connectivity.cloudflareclient.com 162.159.138.65
tcp handshake   : established through the tunnel
tls handshake   : complete
request         : GET /cdn-cgi/trace
ip packets      : sent=22 recv=15
warp=on
```

Пять запусков подряд, все с одним и тем же исходом:

```
RUN 1  CONNECT 200 OK  tls complete  ip=104.28.244.74  colo=FRA  warp=on
RUN 2  CONNECT 200 OK  tls complete  ip=104.28.244.74  colo=FRA  warp=on
RUN 3  CONNECT 200 OK  tls complete  ip=104.28.244.74  colo=FRA  warp=on
RUN 4  CONNECT 200 OK  tls complete  ip=104.28.244.74  colo=FRA  warp=on
```

Что ещё проверено на этом пути и работает:

```
POST /v0a4471/reg
PATCH /v0a4471/reg/{id}   P-256, key_type=secp256r1, tun_type=masque
bare self-signed cert, empty subject, 0 extensions
QUIC/H3, InitialPacketSize=1200
extended CONNECT cf-connect-ip, :status 200
Connect-IP capsules в обе стороны
```

## Чего это не закрывает

Мост живёт на хосте, потому что UDP гостя до edge фильтруется. Пока так, это обход для измерения.
В приложении туннель должен поднимать такой же обход сам: либо MASQUE-over-H2, который целиком
идёт по TCP, либо свой исходящий сокет с тем же egress.

`x/net/http2` в версии `v0.56.0` не умеет HTTP/2 datagram — `FrameDATAGRAM` в `frame.go` нет,
так что H2-путь — это ручная запись frame type 0x33 поверх существующего соединения.

## Почему H2 на этом edge не работает, и это не дефект клиента

Проверено напрямую, `tools/warpgo/h2tunnel` и `tools/warpgo/h2probe`:

```
tcp 162.159.198.2:443 connected from 192.168.0.4:57502
note: server negotiated ALPN "", expected h2 - continuing to observe
tls ok: version=0x0304 alpn=""
server setting 0x03 MAX_CONCURRENT_STREAMS = 256
server setting 0x04 INITIAL_WINDOW_SIZE    = 1048576
server setting 0x05 MAX_FRAME_SIZE         = 16384
server setting 0x06 MAX_HEADER_LIST_SIZE   = 131072
open tunnel: GOAWAY last-stream-id=0 error=PROTOCOL_ERROR (1)
```

Строка про ALPN в первой версии этого файла была неверной и противоречила самой себе: код печатал
`alpn=h2` литералом, а проверка строкой выше сообщала, что сервер не выбрал ничего. Теперь
печатается фактическое значение, и оно пустое.

Control без клиентского сертификата:

```
H2_NO_CERT=1 -> tls ok, затем tls: certificate required
с сертификатом -> tls ok, затем tls: access denied
```

Сертификат тут ни при чём. Причина в SETTINGS: сервер не объявляет `0x8`
`ENABLE_CONNECT_PROTOCOL`. RFC 8441 раздел 3 говорит прямо — сервер, который не отправил
`SETTINGS_ENABLE_CONNECT_PROTOCOL = 1`, не обязан принимать extended CONNECT и отвечает на него
`PROTOCOL_ERROR`. То есть GOAWAY здесь это корректное поведение edge, а не ошибка клиента.

Попутно исправлены три настоящих бага в собственном коде H2, каждый из которых давал тот же
вид симптома:

- `writeFrame` заполнял 5 байт из 9-байтового заголовка кадра. Stream ID не писался вовсе, так что
  CONNECT уходил на поток 0 — а поток 0 это соединение, и клиент не имеет права открывать запрос
  на нём.
- DATAGRAM-фрейм отправлялся на потоке туннеля. По RFC 9297 он идёт на потоке 0, а ID потока
  назначения лежит первыми четырьмя байтами payload.
- Проверка «сервер не объявил H2_DATAGRAM» была ложным блокером: RFC 9297 определяет 0x33 как
  настройку только от клиента к серверу, и сервер, который её не шлёт, просто соответствует спецификации.

Итог: TCP до edge открыт, TLS по нему проходит, но H2-туннель edge не предлагает. Рабочий путь —
H3 через UDP, и он доведён до `warp=on`.
