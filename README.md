# Colgram

> **Приватный, бронированный клиент Telegram с защитой от фингерпринтинга, встроенным обходом блокировок без VPN, изолированным хранилищем и сохранением удаленных сообщений.**

---

## ⚡ Ключевые возможности

### 1. Маскировка устройства и ОС (Hardware Cloaking)
- Сервер Telegram **не видит** реальный серийный номер, модель телефона или версию Android.
- При вызове MTProto `initConnection` модуль `ColgramCloak` подменяет параметры на выбранный профиль (по умолчанию `Google Pixel 8 Pro / Android 14`), либо рандомизирует их.
- Вырезаны системные разрешения на чтение состояния телефона (`READ_PHONE_STATE`), `android_id` и GSF ID.

### 2. Соединение без VPN (Censorship Circumvention)
- Встроенный пул **MTProto Proxy с Fake-TLS** (`ee...`), маскирующийся под обращения к CDN Google, Cloudflare и Microsoft.
- `ColgramProxyManager` автоматически измеряет задержку нод и держит активное соединение внутри процесса приложения.
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
c:\Colgram\
├── .github/
│   └── workflows/
│       └── build-colgram.yml        # Автоматический CI/CD сборщик релизов
├── colgram-core/                     # Изолированное ядро безопасности
│   └── src/main/java/org/colgram/core/
│       ├── ColgramConfig.java        # Управление настройками и флагами
│       ├── ColgramCloak.java         # Спуфер железа и параметров MTProto
│       ├── ColgramDatabase.java      # SQLite-хранилище удаленных сообщений и правок
│       ├── ColgramProxyManager.java  # Менеджер Fake-TLS прокси и авто-пинга
│       ├── ColgramStorageSandbox.java# Изоляция файловой системы
│       └── ColgramHookHandler.java   # Шлюз хуков из официального кода Telegram
├── patches/                          # Хирургические патчи для Telegram-FOSS
│   ├── 001-strip-trackers.patch      # Вырезание аналитики и сервисов
│   ├── 002-connections-cloak.patch   # Хук в ConnectionsManager (спуфинг)
│   ├── 003-messages-anti-delete.patch# Хук в MessagesController (анти-удаление)
│   ├── 004-ui-deleted-messages.patch # Отрисовка удаленных сообщений в UI
│   └── 005-storage-sandbox.patch     # Перенаправление путей сохранения
├── scripts/
│   ├── apply-patches.py              # Скрипт наложения патчей и внедрения ядра
│   └── verify-privacy.py             # Аудитор приватности и отсутствия трекеров
└── README.md
```

---

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
