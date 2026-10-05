# Замена Telegram на Colgram в пользовательских строках

Дата: 2026-10-01. Скрипт: `scripts/rebrand_strings.py`.

## Что было

`AppName` был `Colgram` и в манифесте стояло `android:label="Colgram"`, но внутри строк бренд
оставался прежним. В базовом файле ресурсов «Telegram» встречался **540 раз**, и пользователь
видел «Welcome to Telegram», «Colgram Premium» рядом с «Telegram Stars», и так далее.

## Почему нельзя было заменить всё подряд

Три группы строк требуют разного обращения:

```
469  обычный текст            менять можно - это то, что читает пользователь
 26  с плейсхолдером %1$s     "Neither Telegram, nor %1$s will have access..."
                               Telegram здесь платформа, а %1$s - разработчик.
                               Заменить одно и не другое получится бессмыслица
 45  с telegram.org           настоящий URL. Внутри предложения менять можно,
                               сам URL - нельзя
 74  имя ключа ресурса        TelegramPassport, TelegramFAQ и подобные.
                               Это идентификаторы, а не текст: переименование
                               сломает R.string.* во всём коде
```

## Что сделано

```
values         changed=345   values-ru      changed= 6   values-ko      changed= 1
values-ar      changed=  0   values-de      changed=58   values-nl      changed=57
values-es      changed=69   values-it      changed=72   values-pt-rBR  changed=73
                                                   values-uk      changed=16

total changed=697  remaining=354
```

Остаток — это ключи ресурсов и предложения, где слово «Telegram» называет третью сторону или
остаётся внутри URL.

Примеры после замены:

```
<string name="NoChats">Welcome to Colgram</string>
<string name="AddGroupEmojiPackHint">... even if they don\'t have Colgram Premium.</string>
<string name="RevenueSharingAdsInfo3SubtitleBot">... subscribing to **Colgram Premium**.</string>
<string name="TelegramPassport">Colgram Passport</string>
<string name="SponsoredMessageAlertLearnMoreUrl">https://telegram.org</string>   <- не тронут
```

Все десять файлов локализаций парсятся после правки, APK собирается:

```
:TMessagesProj_AppStandalone:assembleAfatRelease
BUILD SUCCESSFUL in 1m 36s
408 actionable tasks: 21 executed, 387 up-to-date

application-label:'Colgram'
package: name='org.colgram.messenger'
```

Установлено и снято с устройства: заголовок онбординга — **Colgram**, тёмная тема читаемая.

## Дефект, который был в самом скрипте

Первая версия считала 500 изменённых строк и не меняла ничего. Причина в регулярке: она
захватывала значение атрибута, а имя атрибута `"` в начале строки текста элемента не имеет:

```
<string name="NoChats">Welcome to Telegram</string>
              \_____ совпало вместо текста
```

Имя ресурса никогда не содержит «Telegram», поэтому каждая замена была no-op, а счётчик врал.
Исправлено на разбор текста между тегами: `>([^<>]*)<`.

Замена скрипта идемпотентна: повторный прогон даёт `changed=0`.
