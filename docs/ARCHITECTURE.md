# Colgram — Архитектура

**Дата:** 2026-10-06

## Структура проекта

```
C:\Colgram\Telegram-Src/
├── TMessagesProj/                    # Основной Android модуль (UI)
│   └── src/main/java/org/telegram/
│       └── ui/
│           ├── ProxyListActivity.java       # UI настроек прокси/WARP
│           ├── ColgramSettingsActivity.java  # Настройки Colgram
│           └── ...
├── colgram-core/                     # Ядро Colgram
│   └── src/main/java/org/colgram/core/
│       ├── ColgramProxyManager.java         # Менеджер прокси (~4000 строк)
│       ├── ColgramWarpTunnel.java           # WARP туннель (обёртка)
│       ├── ColgramWarpMasqueTunnel.java     # MASQUE клиент (политика)
│       ├── ColgramMasqueVpnService.java     # VPN сервис (:colgram_masque)
│       ├── ColgramMasqueNative.java         # JNI обвязка MASQUE
│       ├── ColgramConfig.java               # Конфигурация
│       ├── ColgramWarp.java                 # WARP утилиты
│       ├── ColgramWarpProfileBuilder.java   # Построение WARP профиля
│       └── ColgramDpiBypass.java            # DPI обход
├── colgram-wireguard/                # WireGuard бэкенд (legacy)
├── TMessagesProj_AppTests/           # Инструментальные тесты
└── ...
```

## Ключевые компоненты

### 1. Прокси-система

```
ColgramProxyManager
├── init()                    # Инициализация при старте
├── publishPoolToStock()      # Публикация пула в SharedConfig
├── enableStockRotation()     # Включение ротатора Telegram
├── forceApplyProxy()         # Принудительное применение прокси
├── applyBestVerifiedNow()    # Выбор лучшего проверенного прокси
├── toggleProxy()             # Переключение прокси
├── onStockProxyToggle()      # Callback из UI
└── hasActiveRouteForUi()     # Есть ли активный маршрут
```

### 2. WARP/MASQUE

```
Процесс :colgram_masque
├── ColgramMasqueVpnService     # Android VpnService
├── libcolgrammasque.so         # Go MASQUE клиент (c-shared)
│   ├── StartTunnel()           # Запуск туннеля
│   ├── StopTunnel()            # Остановка
│   └── GetVerdict()            # Проверка warp=on
└── Отдельный Go runtime        # Изолирован от libbox

Процесс UI
├── ColgramWarpTunnel           # Управление туннелем
│   ├── bringUp()               # Запуск
│   ├── bringDown()             # Остановка
│   └── isUp()                  # Статус
├── ColgramWarpMasqueTunnel     # Политика MASQUE
│   ├── isDeviceWide()          # Режим device-wide
│   └── setDeviceWide()
└── ColgramConfig
    ├── isWarpEnabled()
    └── setWarpEnabled()
```

### 3. Обход блокировок

```
ColgramDpiBypass
├── Десинхронизация TCP         # Разбивка ClientHello
├── DoH резолвер                # DNS over HTTPS
└── SNI bypass                  # ECH / fragmentation

Ограничение: не работает при IP-блокировке
```

### 4. Глобальный поиск

```
Расширения стандартного поиска Telegram:
├── Подписчики каналов в выдаче
├── Счётчики по вкладкам
├── История запросов с удалением
└── Навигация назад к результатам
```

## Процессная модель

```
Приложение (основной процесс)
├── UI Thread
│   ├── ProxyListActivity       # Настройки прокси
│   └── ColgramSettingsActivity # Настройки Colgram
├── Background Threads
│   ├── Proxy checker           # Проверка доступности
│   ├── Pool publisher          # Публикация в SharedConfig
│   └── WARP bringUp            # Запуск WARP (colgram-warp-up)
└── SharedPreferences
    ├── proxy_enabled
    ├── proxy_enabled_calls
    ├── colgram_warp_enabled
    └── colgram_proxy_manually_disabled

Процесс :colgram_masque
├── VPN Service
│   ├── Go runtime #1 (MASQUE)
│   └── VPN interface (tun)
└── SharedPreferences
    └── colgram_warp_masque.xml  # Verdict данные
```

## Билд

```powershell
# JAVA_HOME
$env:JAVA_HOME = 'C:\Colgram\tools\jdk17\jdk-17.0.20.1+1'

# APK приложения
gradle.bat --project-dir C:\Colgram\Telegram-Src \
  :TMessagesProj_AppTests:assembleAfatDebug --no-daemon

# APK тестов
gradle.bat --project-dir C:\Colgram\Telegram-Src \
  :TMessagesProj_AppTests:assembleAfatDebugAndroidTest --no-daemon

# Нативная библиотека MASQUE
powershell -File C:\Colgram\tools\buildh2.ps1

# Проверка библиотеки в APK
powershell -File C:\Colgram\tools\check_apk_lib.ps1
```

## Тестирование

32 тестовых класса, 77+ тестов. Список: `C:\Colgram\tools\all_tests.txt`

Ключевые:
- `ColgramWarpChurnDeviceTest` — toggle WARP on/off
- `ColgramWarpVerdictDeviceTest` — проверка warp=on
- `ColgramProxyAutonomyDeviceTest` — прокси не включается сам
- `ColgramCallProxyDeviceTest` — прокси для звонков
- `ColgramDpiBypassDeviceTest` — DPI обход
- `ColgramThemeContrastDeviceTest` — контраст темы
- `ColgramGlobalSearchHistoryDeviceTest` — история поиска
- `ColgramBrandDeviceTest` — ребрендинг

