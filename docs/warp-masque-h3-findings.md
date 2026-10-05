# WARP через MASQUE: что измерено, а что нет

Дата замеров: 2026-09-30. Хост: DESKTOP-RBTU54S, источник всех UDP/TCP проб — `192.168.0.4`
(LAN), потому что туннель `VPNUS` держит `0.0.0.0/1` и `128.0.0.0/1` с метрикой 0.

## Главное исправление прежнего вывода

Раньше записано: «весь Cloudflare UDP заблокирован, QUIC недоступен, H3 невозможен».
Это было обобщением с одного адреса на весь Cloudflare.

Замерено сейчас, QUIC Initial 1200 байт:

| Цель | Ответ |
|---|---|
| `162.159.198.2:443` (MASQUE edge) | **Retry, 90 мс** (первый байт `0xf0`) |
| `162.159.192.1:443` (engage / API) | тишина |
| `162.159.192.3:2408` (WireGuard endpoint) | тишина |
| `1.1.1.1:443` | тишина |

Блокировка адресная, а не тотальная. Edge MASQUE на `162.159.198.2` открыт по UDP.

## Где взяты адреса (не угаданы)

Из лога официального клиента `C:\ProgramData\Cloudflare\cfwarp_service_log.txt`:

```
warp_edge::h3_tun: Connecting to edge sni="consumer-masque.cloudflareclient.com"
perform_happy_eyeballs_race{endpoint=162.159.198.2:443}
warp_connection::tunnel: Connected to 162.159.198.2:443 @ 696f29 : FRA
start_tunnel_processing{protocol="masque"}
connect_with_protocol_racing{primary="masque" secondary="H2"}
```

Клиент на этом хосте подключался 2026-09-28, MASQUE, `winner=Some(Udp)`.

## Что работает

| Слой | Результат |
|---|---|
| Регистрация `POST /v0a2158/reg` | HTTP 200, `policy.tunnel_protocol = "masque"` |
| QUIC handshake к `162.159.198.2:443` | **ALPN `h3`, handshake completed** |
| H3 SETTINGS от edge | приходят |
| Клиентский сертификат на H3 | edge проверяет: без — 372, с самоподписанным — 305 |
| CONNECT-UDP (RFC 9298) | ответа нет, соединение закрывается |

Официальный клиент это умеет: `warp-cli tunnel protocol set MASQUE` и
`warp-cli tunnel masque-options set h2-only` существуют, то есть MASQUE над H2 — штатный режим.

## Почему H2-путь закрыт

Edge `162.159.198.2:443`, SNI `consumer-masque.cloudflareclient.com`, ALPN `h2`:

```
без клиентского сертификата   TLSV13_ALERT_CERTIFICATE_REQUIRED
с самоподписанным             TLSV1_ALERT_ACCESS_DENIED
```

`ACCESS_DENIED` означает, что сертификат распознан по форме, но не принят — то есть есть
проверка по списку доверия. Логи клиента: `pkix_config: None`, `ca_bundle` отсутствует,
в бинаре есть `FailedToCreateSelfSignedCertificate`. Значит официальный клиент делает
самоподписанный сертификат и объявляет ключ через `X-WARP-Api-Public-Key` /
`X-WARP-Api-Key-Type: 1`.

Все известные пути выдачи вернули 404 (Cloudflare отвечает на них с задержкой 5 с —
анти-зондирование):

```
/v0a2158/accounts/{account}/reg/client_certificates
/v0a2158/accounts/{account}/client_certificates
/v0a2158/accounts/{account}/reg/api-certificate/renew
/v0a2158/reg/client_certificates
/v0a2158/certificates
```

Регистрация с `mtls_csr` в теле проходит (HTTP 200), но сертификат не возвращает.

## Чего не хватает для победы

1. Клиентский сертификат, который примет edge. Без него MASQUE не поднимется ни по H3 (372/305),
   ни по H2 (`ACCESS_DENIED`).
2. После этого — CONNECT-UDP с `:status 200`, иначе заявку `warp=on` делать нельзя.

Ни один замер `warp=on` на устройстве не выполнен. Туннель не работает.

## Что НЕ надо делать

- Не включать WARP/VPN на ПК для проверок. Замеры идут с `192.168.0.4`.
- Не использовать `aioquic.connect()`: он не умеет выбрать локальный адрес и уходит в туннель.
  Нужен `create_datagram_endpoint` со своим сокетом, привязанным к LAN.

## Файлы

```
tools/masque/quic_reach.py       QUIC Initial по всем edge
tools/masque/quic_lan.py         QUIC handshake с привязкой к LAN
tools/masque/h3_diag.py          H3 + CONNECT-UDP диагностика
tools/masque/h3_auth.py          варианты авторизации в CONNECT-UDP
tools/masque/connect_udp.py      CONNECT-UDP над HTTP/2 (RFC 9298)
tools/masque/doh_resolve.py      DoH-резолвер, обход системного DNS
tools/masque/registration_probe.py  регистрация WARP
```