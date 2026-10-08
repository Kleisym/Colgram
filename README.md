# Colgram

> **Приватный, бронированный клиент Telegram с защитой от фингерпринтинга, встроенным обходом блокировок без VPN, изолированным хранилищем и сохранением удаленных сообщений.**

---

## ⚡ Ключевые возможности

### 1. Маскировка устройства и ОС (Hardware Cloaking)
- Сервер Telegram **не видит** реальный серийный номер, модель телефона или версию Android.
- При вызове MTProto `initConnection` модуль `ColgramCloak` подменяет параметры на выбранный профиль (по умолчанию `Google Pixel 8 Pro / Android 14`), либо рандомизирует их.
- Вырезаны системные разрешения на чтение состояния телефона (`READ_PHONE_STATE`), `android_id` и GSF ID.

### 2. Соединение без VPN (Censorship Circumvention)
- **Собственный транспорт**: локальный SOCKS5 на `127.0.0.1:9876` с адаптивной десинхронизацией TCP и переписыванием FakeTLS ClientHello в браузерный отпечаток. Никаких чужих серверов в цепочке.
- **Cloudflare WARP** встроен как MASQUE-клиент на Go (`libcolgrammasque.so`, три ABI), работает в отдельном процессе `:colgram_masque`. Измерено: `warp=on colo=FRA kex=X25519`.
- **Пул MTProto-прокси** с проверкой нативным хендшейком, карантином мёртвых нод и ротацией.
- **DoH-резолвер** для обхода DNS-блокировок.
- Никаких сторонних VPN-приложений или иконок в шторке Android.

### 3. Изоляция файлов (Storage Sandbox)
- Приложению закрыт доступ к общей галерее (`DCIM`), папке `Download` и корню карты памяти (`MANAGE_EXTERNAL_STORAGE` вырезан).
- Все медиафайлы, голосовые, документы и кэш живут строго в изолированной папке `Documents/Colgram/`.
- Файловый пикер физически не может просканировать другие документы на телефоне.

### 4. Анти-удаление и история правок (Message Vault)
- **Анти-удаление**: когда собеседник удаляет сообщение, `MessagesController` перехватывается: удаление из локальной SQLite базы блокируется, сообщение получает метку и окрашивается в серый цвет с пониженной прозрачностью.
- **История правок**: при изменении сообщения собеседником исходный текст и таймстемп архивируются в таблице `message_edits`. Доступен просмотр всех предыдущих версий.

### 5. 100% Чистый код (Zero Telemetry)
- Полностью удалены Google Play Services, Firebase Analytics, Crashlytics, Huawei HMS Push, Microsoft AppCenter и Sentry.
- Автоматический скрипт аудита `scripts/verify-privacy.py` проверяет каждый билд на отсутствие нежелательных зависимостей.

### 6. Автоматическое обновление (Upstream Sync)
- В отличие от монолитных форков (Ayugram, Nekogram), Colgram использует модульную архитектуру:
  - Код Telegram остается чистым апстримом.
  - Наша логика изолирована в модуле `colgram-core/`.
  - Внедрение происходит через 5 хирургических патчей (`patches/`).
- GitHub Actions ежедневно проверяет новые релизы в официальном репозитории Telegram-FOSS, накладывает патчи и собирает свежий APK без ручного разгребания мердж-конфликтов.

---

## 📁 Структура проекта

```
c:\Colgram\├── Telegram-Src/                     # Сборка Gradle
│   ├── TMessagesProj/                # Основной модуль (UI + патчи Telegram)
│   ├── colgram-core/                 # Изолированное ядро
│   │   └── src/main/java/org/colgram/core/
│   │       ├── ColgramConfig.java          # Настройки и флаги
│   │       ├── ColgramCloak.java           # Спуфер желера и параметров MTProto
│   │       ├── ColgramDatabase.java        # SQLite-хранилище удалённых сообщений
│   │       ├── ColgramProxyManager.java    # Пул прокси, ротация, применение маршрута
│   │       ├── ColgramDpiBypass.java       # Локальный SOCKS5 с десинхронизацией TCP
│   │       ├── ColgramTlsMimic.java        # Переписывание FakeTLS ClientHello
│   │       ├── ColgramMasqueVpnService.java # WARP/MASQUE в отдельном процессе
│   │       ├── ColgramStorageSandbox.java  # Изоляция файловой системы
│   │       └── ColgramHookHandler.java     # Шлюз хуков из кода Telegram
│   ├── colgram-singbox/              # libbox: VLESS, Hysteria, Shadowsocks
│   ├── colgram-wireguard/            # WireGuard-бэкенд
│   └── TMessagesProj_AppTests/       # Инструментальные тесты (77 классов)
├── vendor/                           # colgram-singbox, colgram-wireguard, libbox-binding
├── patches/                          # Хирургические патчи для Telegram-FOSS
│   ├── 001-strip-trackers.patch      # Вырезание аналитики и сервисов
│   ├── 002-connections-cloak.patch   # Хук в ConnectionsManager (спуфинг)
│   ├── 003-messages-anti-delete.patch# Хук в MessagesController (анти-удаление)
│   ├── 004-ui-deleted-messages.patch # Отрисовка удалённых сообщений в UI
│   ├── 005-storage-sandbox.patch     # Перенаправление путей сохранения
│   └── 006…009                       # Ghost-режим, флаг-секрет, меню, медиаблокировка
├── tools/                            # Toolchain и нативный MASQUE-клиент
│   ├── jdk17/  ndk/  go/             # JDK 17, Android NDK, Go toolchain
│   ├── warpgo/native/main.go         # MASQUE-клиент Cloudflare WARP
│   └── buildh2.ps1                   # Сборка libcolgrammasque.so под три ABI
├── scripts/
│   ├── apply-patches.py              # Наложение патчей и внедрение ядра
│   ├── build-local.sh                # Локальная сборка
│   ├── device-tests.py               # Прогон тестов на устройстве
│   └── verify-privacy.py             # Аудитор приватности
├── docs/                             # Исследования, архитектура, трекер багов
└── README.md
```

---

## 📚 Документация

Всё исследование, измерения и история решений лежат в `docs/`:

| Файл | О чём |
|---|---|
| [`RESEARCH.md`](docs/RESEARCH.md) | Обзор: WARP-транспорт, обход блокировок, прокси-система, поиск, тема, плагины |
| [`WARP-RESEARCH.md`](docs/WARP-RESEARCH.md) | Хронология MASQUE: от WireGuard до HTTP/2-носителя, с измерениями |
| [`ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Модули, процессная модель, команды сборки, список тестов |
| [`BUGS.md`](docs/BUGS.md) | Трекер: 20 дефектов, 15 закрыты |
| [`CHANGELOG.md`](docs/CHANGELOG.md) | Что вошло в сборку |
| [`FIXES-2026-10-06-evening.md`](docs/FIXES-2026-10-06-evening.md) | Шесть правок с измерениями до и после |
| [`rebrand-strings.md`](docs/rebrand-strings.md) | Правило замены Telegram → Colgram в ресурсах |
| [`CLEANUP-2026-10-07.md`](docs/CLEANUP-2026-10-07.md) | Что и почему было удалено из репозитория |
| [`CI-ANCHORS-2026-10-07.md`](docs/CI-ANCHORS-2026-10-07.md) | Почему патч перестал применяться к upstream 12.10.6 и что с этим сделано |
| [`CI-SIGNING-2026-10-07.md`](docs/CI-SIGNING-2026-10-07.md) | Двенадцать прогонов подписи: четыре ложные гипотезы, пятая ложная, шестая верная |
| [`CI-SIGNING-2026-10-08.md`](docs/CI-SIGNING-2026-10-08.md) | Окончательная причина: секрет был зашифрован вместе с JSON-обёрткой |

## 🚀 Как собрать

### Вариант 1: Через GitHub Actions (Рекомендуемый)
1. Запушь проект в свой репозиторий GitHub.
2. Перейди во вкладку **Actions** -> **Build Colgram**.
3. Нажми **Run workflow** (или дождись автоматической ночной сборки).
4. Скачай готовый подписанный `.apk` из раздела Releases или Artifacts.

### Вариант 2: Локальная сборка (Требуется Android SDK + NDK r25c)
```bash
# 1. Клонировать чистый Telegram-FOSS
git clone --depth 1 https://github.com/Telegram-FOSS/Telegram-FOSS.git Telegram-FOSS

# 2. Наложить ядро Colgram и патчи
python scripts/apply-patches.py Telegram-FOSS

# 3. Проверить чистоту сборки от трекеров
python scripts/verify-privacy.py Telegram-FOSS

# 4. Собрать APK
cd Telegram-FOSS
./gradlew assembleAfatRelease
```
Готовый файл будет в `Telegram-FOSS/TMessagesProj/build/outputs/apk/afat/release/`.
