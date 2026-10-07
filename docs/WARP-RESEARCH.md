# WARP — Детальная история исследований

**Дата:** 2026-09-30 → 2026-10-06

## Хронология

### Этап 1: WireGuard (заблокирован)
- WireGuard endpoints (162.159.192.1:443, 162.159.198.2:*) — UDP заблокирован РКН
- Initiation packet не доходит или не получает ответ
- QUIC Initial получает Retry за 100ms → edge жив, блокируется только WG

### Этап 2: Исследование MASQUE
- Cloudflare WARP использует MASQUE (CONNECT-UDP over HTTP/2/3)
- H3 (QUIC) тоже заблокирован на этой сети
- HTTP/2 TCP carrier — единственный рабочий путь

### Этап 3: Реверс-инжиниринг протокола
- Регистрация на Cloudflare API (v0a4471)
- P-256 key enrollment
- Bare certificate (не X.509, а TLS CertificateVerify)
- cf-connect-ip capsules (RFC 9484)
- HPACK header compression для CONNECT requests

### Этап 4: Реализация Go клиента
- `tools/warpgo/native/main.go` — c-shared библиотека
- 3 ABI: arm64-v8a, armeabi-v7a, x86_64
- Билд: `tools/buildh2.ps1`

### Этап 5: Интеграция в приложение
- JNI обвязка: `ColgramMasqueNative.java`
- VPN сервис: `ColgramMasqueVpnService.java` (отдельный процесс)
- Туннель: `ColgramWarpMasqueTunnel.java`

### Этап 6: Отладка на устройстве
- Краш двух Go рантаймов → процессная изоляция
- VPN не маршрутизировал трафик → `deviceWideRequested()` по умолчанию false → исправлено
- Rate-limiting edge → ожидание 30 минут

## Сетевые измерения

### UDP к Cloudflare edge
```
162.159.192.1:443   — нет ответа на WG initiation
162.159.198.2:443   — QUIC Retry за 100ms, но WG молчит
162.159.198.2:500   — нет ответа
162.159.198.2:4500  — нет ответа
162.159.198.2:4443  — нет ответа
162.159.198.2:8443  — нет ответа
162.159.198.2:8095  — нет ответа
```

### TCP к Cloudflare edge
```
162.159.198.2:443   — TCP SYN-ACK за ~20ms
TLS handshake       — ServerHello, certificate chain OK
HTTP/2              — SETTINGS, WINDOW_UPDATE OK
CONNECT             — 200 OK
MASQUE session      — warp=on, colo=FRA
```

### Верифицированные результаты
```
Run 1: ip=104.28.244.74  colo=FRA  warp=on  kex=X25519
Run 2: ip=104.28.227.110 colo=ORD  warp=on  kex=X25519
Run 3: ip=104.28.244.74  colo=FRA  warp=on  tls=TLSv1.3
```

## Отвергнутые подходы

| Подход | Почему не работает |
|---|---|
| WireGuard | UDP заблокирован РКН |
| QUIC/H3 MASQUE | QUIC заблокирован на сети |
| Прямой TLS к 162.159.198.2 | Edge не отвечает данными |
| Relay через внешний сервер | Добавляет зависимость от внешнего сервера |
| Десинхронизация для WARP | WARP endpoint не заблокирован по SNI, проблема в UDP |

## Технические детали MASQUE

### Формат capsule
```
cf-connect-ip capsule (RFC 9484):
  Type: CONNECT-IP
  IP packet encapsulated in HTTP/2 DATA frame
  Checksum: оба (IP + transport) должны быть 0
  MTU: определяется при установке сессии
```

### Регистрация
```
1. POST /reg → получение device_id, token
2. PATCH /reg/{id} → отправка P-256 public key
3. Получение client_id, private key материал
4. Формирование bare certificate для mTLS
```

### Carrier
```
TCP → TLS 1.3 (SNI: engage.cloudflareclient.com)
→ HTTP/2 SETTINGS
→ CONNECT / HTTP/2
→ MASQUE CONNECT-UDP
→ WireGuard handshake inside
→ IP packets via capsules
```

