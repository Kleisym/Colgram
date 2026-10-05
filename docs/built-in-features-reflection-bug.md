# Автоответчик и ключевые слова не работали: рефлексия без аргументов

Дата: 2026-10-01.

## Что было

Встроенные функции вынесены из Python-плагинов в Java - это было сделано верно: переключатели в
`ColgramPluginsActivity`, логика в `colgram-core`, интерпретатора в пути нет. Но проверка того, что
логика действительно **вызывается**, вскрыла дефект в общей точке отправки.

`ColgramHookHandler.colgramAutoReply()` вызывает `ColgramPythonEngine.sendMessage()`. То же идёт в
оповещения по ключевым словам - то есть в то, что пользователь считает встроенными функциями
приложения.

Метод искал перегрузку `SendMessagesHelper.sendMessage` с параметрами `(CharSequence, long)`:

```java
Object[] args = new Object[send.getParameterTypes().length];
args[0] = message;
args[1] = dialogId;
send.invoke(smh, args);          // остальные - null
```

**Такой перегрузки не существует.** Единственный публичный метод:

```java
// SendMessagesHelper.java:4259
public void sendMessage(SendMessageParams sendMessageParams) { ... }
```

Поэтому `send` оставался `null`, ветка просто не выполнялась, и **сообщение не уходило никогда, ни
в одной сборке**. Исключения не было - было условие, которое никогда не совпало, и оно молча
переходило дальше.

Два слоя дефекта, и первый выглядел правдоподобнее второго:

1. искался несуществующий метод - это и есть причина, почему ничего не отправлялось;
2. даже найди он что-нибудь, массив аргументов был заполнен на два слота из всех, а у
   `sendMessage` среди параметров есть `Runnable`.

## Что изменено

Вызов идёт через фабрику `SendMessageParams.of(String, long)`:

```java
Class<?> paramsClass = Class.forName(
        "org.telegram.messenger.SendMessagesHelper$SendMessageParams");
Method of = paramsClass.getMethod("of", String.class, long.class);
Object params = of.invoke(null, message, dialogId);
smhClass.getMethod("sendMessage", paramsClass).invoke(smh, params);
```

Фабрика, а не объект с полями, потому что у `SendMessageParams` около пятидесяти полей, часть из
них приватная, и среди них `searchLinks` и `notify`. Заполнить три поля рефлексией оставляет
остальные на Java-значениях по умолчанию - это не то, что ожидает путь отправки. `of()` ставит их
так же, как всё остальное приложение.

Плюс `of()` принимает идентификатор диалога аргументом, а не через поле: поля с таким именем в
классе нет, есть `peer`.

Промежуточная правка с `defaultFor` тоже удалена - она чинила второй слой, оставляя первый.

## Регрессия теперь ловится сборкой

`scripts/test_autoreply_path.py` проверяет, что путь отправки **разрешается**, против настоящих
классов, а не против текста комментария:

```
[ok] auto-reply send path resolves: SendMessagesHelper$SendMessageParams.of(String, long)
```

Проверяет пять вещей, каждая из которых означает тот самый молчаливый отказ:

```
SendMessageParams                          вложенный класс существует
of(String, long)                          фабрика существует с этой сигнатурой
нет поиска (CharSequence, long)            старый код возвращается
SendMessagesHelper$SendMessageParams       имя вложенного класса верно (в рантайме через $)
нет printStackTrace                        ошибка видна в logcat
```

Проверено в обе стороны: на исправленном коде даёт `EXIT=0`, а после возврата старого имени класса -

```
[!] engine does not name the nested params class as SendMessagesHelper$SendMessageParams
EXIT=1
```

Отправлять сообщение в проверке нельзя: нужен живой аккаунт, и это был бы реальный чат. Разрешение
метода - ровно та часть, которая падала.

```
:colgram-core:compileReleaseJavaWithJavac
BUILD SUCCESSFUL in 6s
```

## Почему это не нашлось раньше

Тот же класс дефекта в третий раз: код есть, документация подробная, компиляция зелёная. Отличается
только тем, что функция не была подключена вовсе - а здесь подключена, но вызывала чужой путь,
который был написан под Python-плагины и никогда не проверялся на вызванной из Java стороне.
