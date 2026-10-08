# Подпись релизов: окончательная причина и решение, 2026-10-08

Run `37709646721` — **success**, все четырнадцать шагов. Релиз `colgram-f2908b1-97`
опубликован, APK 527.9 MB.

| Шаг | Имя | Итог |
|---|---|---|
| 1 | Set up job | success |
| 2 | Checkout Colgram Mod Repository | success |
| 3 | Set up JDK 17 | success |
| 4 | Set up Python | success |
| 5 | Set up Gradle Cache | success |
| 6 | Clone Telegram Upstream | success |
| 7 | Apply Colgram Core & Patches | success |
| 8 | Audit Privacy & Check for Trackers | success |
| 9 | Build Colgram APK | success |
| 10 | Audit the built APK | success |
| 11 | Report the signing material | success |
| 12 | Sign APK | success |
| 13 | Upload APK Artifact | success |
| 14 | Publish GitHub Release | success |

Аннотации шага 11:

    length 5860 bytes, password 20 bytes, alias 'colgram'
    OK, 4394 bytes, first bytes 3082
    opens with the stored password, default type

`3082` — это ASN.1 SEQUENCE, то есть PKCS12. Keystore читается штатным keytool без
`--ks-type`.

---

## Настоящая причина: секрет был зашифрован вместе с JSON-обёрткой

Двенадцать прогонов подряд падало на шаге подписи. Пять гипотез до этого были неверными —
они в `CI-SIGNING-2026-10-07.md`. Шестая оказалась верной, и её показала диагностика,
которую я добавил после того, как логи шага перестали быть доступны.

Прогон `37650860310` отчитался:

    length 5272 bytes, password 76 bytes
    base64 rejected the secret

Пароль я отправлял длиной **20**. Раннер видел **76**. Разница — ровно длина JSON-обёртки:

    len(json.dumps({'encrypted_value': 'colgram-release-2026',
                    'key_id': '3380204578043523366'}))
    # 76

Я запечатывал **всю JSON-обёртку** внутрь libsodium SealedBox:

    payload = json.dumps({'encrypted_value': value, 'key_id': key_id}).encode()
    sealed = nacl.public.SealedBox(key).encrypt(payload)

API расшифровывает один слой SealedBox и сохраняет результат. То есть в секрет попадала
строка `{"encrypted_value": ..., "key_id": ...}`, а не само значение. Отсюда и 76 вместо 20,
и 5272 вместо 5860, и `base64 rejected the secret` — тело начиналось с фигурной скобки,
а не с буквы алфавита base64.

Правильная схема — шифровать **только значение**, `key_id` идёт рядом незашифрованным,
как и предполагает документация GitHub:

    def seal_value(value):
        return base64.b64encode(nacl.public.SealedBox(key).encrypt(value.encode())).decode()

    body = {'encrypted_value': seal_value('colgram-release-2026'),
            'key_id': pk['key_id']}

---

## Почему логи шага были недоступны

Семь попыток скачать лог упавшего шага: соединение с api.github.com рвётся на этой сети
(WinError 10054, SSL UNEXPECTED_EOF_WHILE_READING), а HTML-страница журнала подгружает его
отдельным запросом, который без браузерной сессии не отдаётся.

Поэтому шаг 11 «Report the signing material» пишет факты как **аннотации**
(`::notice title=...`), а не в лог: `/check-runs/{id}/annotations` возвращается всегда.
Именно это дало ответ — без них я бы продолжал гадать о base64, keytool и формате.

---

## Итог по двенадцати прогонам

| Что чинилось | Run | Как обнаружилось |
|---|---|---|
| удалённые шаблоны и WARP-модуль | 37553518379 | FileNotFoundError в логе шага |
| четыре якоря к upstream 12.10.6 | 37554558489 | список пропусков патчера |
| аудит APK до сборки | 37564091502 | No APK to audit |
| три не объявленных символа | 37565022037 | 9 ошибок javac |
| пересозданный calls-блок | 37567303275 | ещё 6 ошибок javac |
| отсутствие секретов | 37590905487 | COLGRAM_KEYSTORE_B64 is not set |
| маскирование значения | 37612275339 | echo со звёздочками в логе |
| схема шифрования секрета | 37708579006 | аннотация: password 76 bytes |

Первые пять — код, последние три — конфигурация репозитория. Ни одна из гипотез про
base64, переводы строк и обратные апострофы не подтвердилась, хотя каждая выглядела
правдоподобно и была проверена.
