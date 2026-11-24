<div align="center">
  <img src="assets/colgram_brand_logo.png" alt="Colgram Logo" width="96" height="96" />
  <h1>Colgram 12.10.6</h1>
  <p><strong>Быстрый и приватный клиент Telegram для Android без блокировок, тормозов и лимитов памяти. Работает сразу из коробки на любом интернете — без внешних VPN.</strong></p>
  <p>
    <a href="https://github.com/Kleisym/Colgram/releases/latest"><img src="https://img.shields.io/badge/Скачать_APK-v12.10.6-00E5FF?style=for-the-badge&logo=telegram" alt="Latest Release" /></a>
    <a href="https://github.com/Kleisym/Colgram/raw/main/docs/media/colgram-release.mp4"><img src="https://img.shields.io/badge/Видео_Обзор-1080p60_MP4-10B981?style=for-the-badge&logo=play" alt="Watch 1080p60 Release Video" /></a>
  </p>
</div>

---

## Видео-обзор

<video src="https://github.com/Kleisym/Colgram/raw/main/docs/media/colgram-release.mp4" controls poster="docs/media/colgram-release-poster.png" width="100%"></video>

[![Смотреть видео-релиз Colgram 12.10.6 (1080p60)](docs/media/colgram-release-preview.gif)](https://github.com/Kleisym/Colgram/raw/main/docs/media/colgram-release.mp4)

> Нажмите на превью выше или откройте [**прямую ссылку на видео (1080p60 MP4)**](https://github.com/Kleisym/Colgram/raw/main/docs/media/colgram-release.mp4).

---

## Что внутри

- **Обход блокировок и моментальная загрузка без VPN** — сам подключается и на домашнем Wi-Fi, и на мобильном интернете (4G/LTE), даже когда оператор глушит связь. Встроены локальный обход блокировок для всего телефона (YouTube, Discord, браузеры, звонки), Cloudflare WARP и поддержка подписок `VLESS / Sing-box`.
- **Умный офлайн-ИИ Колити прямо в чатах** — встроенный помощник, который работает прямо на телефоне без интернета и облаков. Понимает фото, расшифровывает и озвучивает голосовые, ищет нужные сообщения, переводит чаты и переключает функции клиента по команде.
- **Бесконечная память и умное сжатие без мусора в галерее** — кэш не удаляется сам по себе, одинаковые файлы и пересылки не дублируются в памяти, а фотографии аккуратно сжимаются без потери качества. Весь кэш и стикеры изолированы от системной Галереи и Загрузок — туда попадает только то, что вы сохранили сами.
- **Режим призрака и чтение удалённых сообщений** — удалённые собеседником сообщения и фото остаются у вас в чате, сохраняется вся история чужих редактирований. Можно читать сообщения без галочек, скрывать статус «онлайн» и набор текста, прятать номер телефона, закрывать скрытые аккаунты паролем, фильтровать спам через Colemailer и пользоваться встроенной временной почтой.
- **Кастомный стиль, умный контраст и живые видео-обои** — стеклянные темы оформления в Design Studio, автоматический контраст текста и галочек на любых пузырях сообщений, плавные видео-обои `.mp4` в чатах, быстрая панель QuickBar и поддержка Python-плагинов (`.py` / `.plugin`).

---

## Скачать и установить

Готовые сборки лежат в разделе [**Releases**](https://github.com/Kleisym/Colgram/releases/latest):

- **`Colgram-12.10.6-arm64-v8a.apk`** — основная сборка для всех Android-смартфонов (всё включено, с офлайн-ИИ Колити).
- **`Colgram-12.10.6-x86_64.apk`** — сборка для эмуляторов на ПК (`MuMu`, `BlueStacks`, `AVD`) с полным офлайн-ИИ.
- **`Colgram-12.10.6-universal-lite.apk`** — облегчённая универсальная сборка для быстрой загрузки (ИИ можно докачать внутри приложения).

Контрольные суммы всех файлов указаны в `SHA256SUMS.txt` на странице релиза.

---

## Сборка из исходников

Требуются **JDK 17**, **Python 3.11+** и **Android SDK (API 34 / 36)**:

```powershell
# Проверка синтаксиса и прогон тестов
python scripts/colgram-check.py --syntax --core --all-templates
python scripts/host_verify.py --jvm-tests
python -m unittest discover -s scripts -p "test_*.py"

# Сборка APK
cd Telegram-Src
.\gradlew.bat :TMessagesProj_AppStandalone:assembleAfatDebug --no-daemon
```

Подробности по сборке и архитектуре — в [`docs/BUILD-AND-TEST.md`](docs/BUILD-AND-TEST.md) и [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
