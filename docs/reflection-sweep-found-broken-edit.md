# Проверка рефлексии нашла ещё один сломанный путь

Дата: 2026-10-01. Скрипт: `scripts/check_reflection.py`.

## Почему это пришлось проверять

Молчаливый отказ рефлексии обнаружился один раз - с автоответчиком. Но в `colgram-core` **137
вызовов `getMethod`**, и у каждого есть ровно тот же способ не сработать: вернуть `null`, после чего
код продолжает идти как ни в чём не бывало. Один найденный случай делает разумным спросить, сколько
их ещё.

## Что делает проверка

Каждый вызов разбирается как написан, класс определяется по переменной, которой присвоен
`Class.forName`, и сверяется с настоящим классом в `TMessagesProj`:

```
! класс существует
! метод с таким именем не объявлен
! число параметров не совпадает
```

Последнее — не формальность. `MessagesStorage` объявляет `getMessage(long, long)`, а сломанный
вызов просил `getMessage(int, long, int, boolean)`. Проверка только по имени прошла бы и пропустила
ровно тот дефект, ради которого написана.

```
checked 103 lookups, 47 not statically resolvable, 0 unresolved
EXIT=0
```

47 пропущенных - честный отказ, а не молчаливый успех: получатель не является результатом
`Class.forName` (параметр метода вроде `getThemeColor(Class<?> themeClass, ...)` или вызов на
`something.getClass()`), и по этому файлу класс не определяется.

## Что нашлось: editMessage не мог работать

```java
Object msg = mcClass.getMethod("getMessage", int.class, long.class, int.class, boolean.class)
        .invoke(mc, ...);
```

У `MessagesController` метода `getMessage` **нет вообще**. Он есть у `MessagesStorage` и принимает
два `long`:

```java
public TLRPC.Message getMessage(long dialogId, long msgId)   // MessagesStorage
```

Так что правка сообщения через плагин не работала никогда - ровно как автоответчик до этого.

Заодно исправлены ещё две неверные подписи в том же методе:

| было | на самом деле |
|---|---|
| `new MessageObject(int, Object, Object, boolean)` | такого конструктора нет; есть `(int, TLRPC.Message, LongSparseArray, boolean, boolean)` |
| `editMessage(MessageObject, String, boolean, BaseFragment, ArrayList, int, int)` | такого нет; есть `editMessage(MessageObject, TL_photo, VideoEditedInfo, TL_document, String, PhotoSize, HashMap, boolean, boolean, Object)` |

Текст при редактировании задаётся **на самом `MessageObject`** - параметром его не передать, и
это ещё одна причина, по которой исходный вызов не мог бы заработать даже с верными типами.

## Регрессия ловится

Вернуть сломанную сигнатуру - и проверка это видит:

```
ColgramPythonEngine.java:617  org.telegram.messenger.MessagesStorage.getMessage
    called with 4, declared [2]
EXIT=1
```

Вернуть правильную - и обе проверки зелёные:

```
checked 103 lookups, 47 not statically resolvable, 0 unresolved    EXIT=0
[ok] auto-reply send path resolves: SendMessagesHelper$SendMessageParams.of(String, long)  EXIT=0
```

## Что проверка НЕ ловит

Типы параметров не сверяются - для этого нужен настоящий classloader, а не разбор текста. Совпадение
имени и числа параметров при неверных типах рефлексия отвергнет точно так же молча, и такая ошибка
останется незамеченной. Типы сверяет только проверка автоответчика, и то для одного пути.

## Второй проход: файлы интерфейса

`scripts/check_reflection_ui.py` делает то же по файлам Colgram в `TMessagesProj` - там рефлексия
обращается к собственным классам: старт подписки, состояние хранилища, движок плагинов.

```
TMessagesProj/colgram: checked 6, skipped 12, unresolved 0
EXIT=0
```

## Граница проверена, а не предположена

Замена `"current", Context.class` на `"current", String.class` **не была поймана**, и это ровно тот
предел, о котором сказано выше: имя и число параметров совпадают, отличается только тип. Настоящая
проверка типов потребовала бы classloader.

Записано, потому что проверка без такой оговорки выглядит надёжнее, чем она есть: она ловит
исчезнувший метод и изменившуюся арность, но не подменённый тип.
