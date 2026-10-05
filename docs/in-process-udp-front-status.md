# Фронт внутри приложения: что работает и что упирается в сеть

Дата: 2026-10-01. Устройство: MuMuPlayer, `127.0.0.1:16384`, x86_64.

## Что сделано

`colgram-core/src/main/java/org/colgram/core/ColgramUdpTunnel.java` — фронт внутри процесса
приложения. Сессия поднимается из кода, который лежит в установленном APK:

```
pm path org.colgram.messenger
unzip -l base.apk | grep colgrammasque
  12996616  lib/x86_64/libcolgrammasque.so

grep -c ColgramUdpTunnel classes6.dex
  2
```

Запуск из класса в APK:

```
app_process64 -cp /data/local/uq/d4/classes6.dex /data/local/uq org.colgram.core.ColgramUdpTunnel
FRONT_PORT=45913
FRONT_ADDR=127.0.0.1:45913
```

## Два дефекта, найденных и исправленных при этом

### 1. Ответы читались не с того сокета

Первая версия держала два сокета на сессию: `up` для отправки к edge и отдельный `reply` для
чтения ответов. Ответ приходит на тот адрес и порт, с которого ушёл Initial, то есть на `up`.
Второй сокет был просто сокетом, куда никто ничего не отправляет.

### 2. Фронт вязал сокет на IPv6-wildcard

Вот это выдала добавленная диагностика, и она стоила больше всего остального:

```
associate on 127.0.0.1:40639, upstream bound to ::
first datagram: 1200 bytes to /162.159.198.2:443 from ::/::
```

Три записи подряд, и все три объясняют одно и то же: сокет создавался как `::`, датаграммы
уходили как IPv4-mapped IPv6, ядро их отбрасывало молча. Сессия ассоциировалась, счётчики двигались,
клиент таймаутился — и нигде не было ни одной строки, которая на это указывала.

Все три обычных способа указать wildcard дали IPv6-сокет на этом устройстве:

```go
new InetSocketAddress("0.0.0.0", 0)   // через резолвер -> ::
InetAddress.getByName("0.0.0.0")     // через резолвер -> ::
InetAddress.getByAddress(new byte[]{0,0,0,0})  // тоже ::
```

Помогло только binds на конкретный локальный IPv4:

```java
InetAddress local = localIPv4();   // первый не-loopback Inet4Address с интерфейсов
upstream = new DatagramSocket(new InetSocketAddress(local, 0));
```

После этого лог стал:

```
associate on 127.0.0.1:40639, upstream bound to 10.0.2.15
first datagram: 1200 bytes to /162.159.198.2:443 from /10.0.2.15
```

## Где это упирается

Фронт работает, сессии принимаются, датаграммы уходят корректно. Edge не отвечает — и это уже не
код, а свойство сети:

```
ip route get 162.159.198.2
  162.159.198.2 via 10.0.2.2 dev wlan0 table wlan0 src 10.0.2.15

ip addr show wlan0 | grep inet
  inet 10.0.2.15/24
```

У гостя ровно один адрес, и весь его трафик выходит через NAT `10.0.2.2`. Edge отвечает не на этот
egress. Это ровно то, что было измерено раньше прочим инструментом — прямым QUIC с устройства:

```
device, real QUIC stack, 162.159.198.2:
  :443   handshake failed after 5003ms  timeout: no recent network activity
  :500   handshake failed after 5002ms  timeout
  :8443  handshake failed after 5004ms  timeout
  :4500  handshake failed after 5001ms  timeout
```

Мост на хосте даёт `warp=on` именно потому, что привязывает свой upstream-сокет к `192.168.0.4` —
другому egress, с которого edge отвечает:

```
BRIDGE_BIND=192.168.0.4
```

У гостя такого адреса нет и не может быть: он за NAT, и выбрать ему egress нечем.

## Что это значит для цели

Обход внутри приложения сделан и работает настолько, насколько позволяет устройство: фронт
поднимается, принимает сессию, кадрирует по TCP, отправляет UDP с IPv4-сокета. Оставшаяся часть —
не код, а egress, которого у гостя нет.

Чтобы закрыть это целиком, нужно одно из:

- устройство, у которого есть собственный публичный адрес, с которого edge отвечает;
- либо транспорт, который доходит до edge по TCP — но edge не объявляет
  `SETTINGS_ENABLE_CONNECT_PROTOCOL`, и RFC 8441 отвечает на такой CONNECT `PROTOCOL_ERROR`
  (замерено: `GOAWAY last-stream-id=0 error=PROTOCOL_ERROR`, при `alpn=""`).

На этом устройстве, пока оно за NAT с одним адресом, `warp=on` получается только с внешним
egress — то есть с мостом на хосте.

## Как выглядит фильтр на устройстве, измерено

Полный проход UDP-пробером с привязкой к `10.0.2.15`:

```
1.1.1.1:53                ANSWERED 64 bytes in 80ms   <- настоящий DNS
1.0.0.1:53                ANSWERED 64 bytes in 80ms
8.8.8.8:53                ANSWERED 64 bytes in 92ms
9.9.9.9:53                ANSWERED 64 bytes in 81ms
94.140.14.14:53           ANSWERED 64 bytes in 102ms
149.112.112.112:53        ANSWERED 64 bytes in 79ms
208.67.222.222:53         ANSWERED 64 bytes in 100ms

1.1.1.1:443               no answer in 3000ms
162.159.192.1:443         no answer in 3000ms
162.159.192.2:443         no answer in 3000ms
162.159.195.1:443         no answer in 3000ms
162.159.197.4:443         no answer in 3000ms
162.159.198.2:443         no answer in 3000ms
188.114.96.1:443          no answer in 3000ms
188.114.97.1:443          no answer in 3000ms
104.16.0.1:443            no answer in 3000ms
104.16.0.1:53             no answer in 3001ms
162.159.198.2:53         no answer in 3000ms
```

Фильтр не по протоколу и не по порту целиком:

-   публичные DNS-резолверы отвечают с устройства на UDP 53, любые;
-   весь диапазон Cloudflare на UDP 53 молчит, и весь он же на 443 молчит.

То есть адреса Cloudflare отфильтрованы целиком, а не «QUIC запрещён».

## Инъекция DPI на UDP 443 — почему «12 байт ответа» это не ответ

На `208.67.222.222:443` приходит 12 байт, которые выглядят как ответ. Настоящим QUIC-стеком там
handshake не проходит:

```
SCAN_PORT=443 esdev 208.67.222.222
  handshake failed after 5018ms  timeout: no recent network activity
```

Что это на самом деле, показал payload:

```
PROBE_PAYLOAD='colgram-quic-probe'  -> ANSWERED 12 bytes: "co e8 04 00 00 00 00 00 00 00 00"
PROBE_PAYLOAD='AAAAAAAAAAAAAAAAAAAAAAAA' -> ANSWERED 12 bytes: "AA c1 04 00 00 00 00 00 00 00 00"
```

**Первые два байта ответа — это первые два байта моего же пакета.** Дальше идёт длина и нули.
Это подстановка DPI: оборудование переписывает заголовок чужого UDP-пакета так, чтобы клиент
решил, что на его запрос ответили. Портов с таким поведением нет — 4444 и 9999 на том же адресе
молчат:

```
208.67.222.222:443    ANSWERED 12 bytes in 299ms
208.67.222.222:4444   no answer in 3001ms
208.67.222.222:9999   no answer in 3000ms
```

Практический смысл: любой «ответ» на UDP 443 с этого устройства нельзя считать ответом, пока не
проверено, что он не начинается с наших собственных байтов. Именно поэтому наивная проверка
«есть ответ — путь живой» даёт ложное подтверждение, а настоящий QUIC-handshake честно молчит.

## SNI не является обходом — проверено семью именами

Клиентский бинарник WARP логирует два разных имени: `tunnel_sni` и `proxy_sni`, и прокси-путь в нём
идёт через собственный код. Если бы фильтр смотрел на имя в ClientHello, то незнакомое имя стало бы
обходом — тем более что устройство достаёт edge по TCP. Все имена проверены настоящим QUIC-стеком,
`tools/warpgo/sniprobe`, с привязкой `10.0.2.15`:

```
edge 162.159.198.2 port 443, bind "10.0.2.15"

  consumer-masque.cloudflareclient.com         no handshake in 4001ms
  consumer-masque-proxy.cloudflareclient.com   no handshake in 4000ms
  zt-masque-proxy.cloudflareclient.com         no handshake in 4000ms
  zt-masque.cloudflareclient.com               no handshake in 4000ms
  connectivity.cloudflareclient.com            no handshake in 4001ms
  engage.cloudflareclient.com                  no handshake in 4000ms
  api.cloudflareclient.com                     no handshake in 4000ms
```

Семь имён, одинаковый молчащий ответ. Фильтр не по SNI — он по адресу.

## DNS-туннель не проходит по размеру

Резолверы отвечают, но ответ — 64 байта:

```
PROBE_DNS=1 PROBE_TARGETS='1.1.1.1:53'
  ANSWERED 64 bytes in 80ms: "+<\x81\x80\x00\x01...cloudflare\x03com...h\x10\x85\xe5"
```

QUIC-Initial этого клиента — 1200 байт, и edge отвечает ровно на 1200 и молча роняет всё, что
больше. Ответ в 64 байта физически не может нести handshake, поэтому DNS-туннель закрыт
измерением, а не предположением.

## Реле приложения не подходят: это MTProto, а не SOCKS5

Приложение держит живой список узлов (`shared_prefs/colgram_proxies.xml`), и они действительно
доступны с устройства:

```
nc 154.86.119.143 443   RC=0        <- TCP соединяется
```

Но `tools/warpgo/tcpprobe`, спрошенный с устройства, говорит, что это не SOCKS5:

```
154.86.119.143:443   socks5   no method reply: read tcp 10.0.2.15:50166->...: i/o timeout
154.86.119.143:443   connect  no status: EOF
79.137.196.223:1443  socks5   method refused: 0x54
79.137.196.223:1443  connect  no status: i/o timeout
```

`0x54` вместо SOCKS5-ответа — это `T`, то есть сервер сразу пишет свой протокол. Это MTProto-узлы,
они несут Telegram-трафик и не умеют ни SOCKS5, ни CONNECT. Годились бы только SOCKS5-реле с
UDP ASSOCIATE, и таких в списке нет.

## Итог по путям без внешнего релея

```
прямой UDP к edge с устройства      отфильтрован, весь диапазон Cloudflare
прямой TCP к edge с устройства      открыт, но MASQUE-over-H2 edge не предлагает
другой SNI                          не помогает, семь имён, все молчат
DNS-туннель                         ответ 64 байта, Initial 1200
реле приложения                     MTProto, не SOCKS5
```

Чтобы убрать зависимость от внешнего релея на этом устройстве, нужно устройство с собственным
публичным адресом. Иначе единственная конфигурация, дающая `warp=on`, — это egress с хоста, и
она проверена: `warp=on`, `ip=104.28.244.74`, `colo=FRA`, 3/3 и 5/5 повторов.

## IPv6 проверен отдельно и тоже закрыт

Все измерения выше — только `udp4`, и вывод «edge отфильтрован» на этом неполон: фильтр может
быть по семейству адресов. `tools/warpgo/udp6` спрашивает v6-путь, потому что у гостя есть глобальный
адрес и маршрут по умолчанию:

```
ip -6 route get 2606:4700:d0::a29f:c002
  via fe80::2 dev wlan0 table wlan0 proto ra src fd17:625c:f037:2:e8d3:4688:c22e:8865
```

С устройства, с привязкой к этому адресу:

```
[2606:4700:d0::a29f:c002]:443    sent 32 bytes, no answer in 3s     <- MASQUE edge
[2606:4700:102::4]:443           sent 32 bytes, no answer in 3s
[2606:4700:4700::1111]:443       sent 32 bytes, no answer in 3s
[2606:4700:4700::1001]:443       sent 32 bytes, no answer in 3s
[2606:4700:102::3]:443           sent 32 bytes, no answer in 3s

[2606:4700:4700::1111]:53        sent 32 bytes, no answer in 3s     <- контроль
[2001:4860:4860::8888]:53        sent 32 bytes, no answer in 3s
[2a00:1450:4009::200e]:53        sent 32 bytes, no answer in 3s

ping6 -c 2 -W 3 2606:4700:4700::1111
  2 packets transmitted, 0 received, +2 errors, 100% packet loss
```

Контроль тоже молчит, и это важно: дело не в том, что edge отвечает только по v4. Маршрут есть,
адрес есть, а пакеты не доходят — v6 у гостя не маршрутизируется вовне. То есть IPv6 не обход.

На хосте v6 тоже не глобальный, единственный адрес принадлежит Radmin VPN:

```
Get-NetIPAddress -AddressFamily IPv6 | Where-Object { $_.IPAddress -notlike 'fe80*' -and $_.IPAddress -ne '::1' }
  Radmin VPN   fdfd::1ae2:5e9e

udp6.exe -> dial udp6 :0->[2606:4700:d0::a29f:c002]:443: unreachable network
```

## Дополнительно: весь диапазон 162.159.0.0/16

Проверены адреса за пределами известного edge, все на порту 443:

```
162.159.193.10:443   no answer    162.159.199.10:443  no answer
162.159.193.11:443   no answer    162.159.200.1:443   no answer
162.159.199.1:443    no answer    162.159.201.1:443   no answer
                              162.159.202.1:443   no answer
                              162.159.203.1:443   no answer
```

Фильтр покрывает диапазон, а не один адрес. И вместе с IPv6 это означает, что на устройстве не
осталось ни одного сетевого пути до edge, кроме egress с хоста.

## Проверено, чтобы список закрытых путей был полным

Системный прокси устройства:

```
settings get global http_proxy           -> null
settings get global global_http_proxy_host -> null
```

Слушающие порты — ни одного прокси, только adb эмулятора:

```
netstat -ltn
  tcp6  [::]:5555  LISTEN     <- стандартный add эмулятора, не прокси
  tcp6  127.0.0.1:43961, 43517 LISTEN
```

Адреса IPv6 у гостя уникальные локальные, а не глобальные:

```
inet6 fd17:625c:f037:2:e8d3:4688:c22e:8865/64 scope global
inet6 fd17:625c:f037:2:a868:7f92:703e:de7b/64 scope global
inet6 fe80::9f36:35d:2fd9:496b/64 scope link
```

`fd00::/8` — это ULA. Префикс `fd17` не маршрутизируется в интернет, поэтому v6-адрес существует,
но исходящего пути на нём нет — и это согласуется с тем, что `ping6` до публичного адреса даёт
100% потерь при наличии маршрута по умолчанию.

sing-box в приложении не запущен и каталога `files/libbox` нет, так что его транспорт как готовая
инфраструктура не используется.
