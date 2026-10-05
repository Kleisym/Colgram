# Обработчик крашей был написан, но не установлен

Дата: 2026-10-01.

## Что было

`ColgramCrashGuard.install(Context)` и `NativeCrashSignal.install(File)` существовали, были
документированы, а `libcolgramcrash.so` лежал в трёх jniLibs. Ничего в дереве их не вызывало:

```
ColgramCrashGuard.install -> только объявление, вызова нет
NativeCrashSignal.install -> вызывается только изнутри ColgramCrashGuard.install
```

То есть обработчик не был установлен нигде. Каждый краш приложения был смертью процесса с пустым
tombstone - ровно то, что наблюдалось на устройстве:

```
/data/tombstones/app/org.colgram.messenger/10081.log   0 bytes
```

Именно поэтому «приложение часто крашится» невозможно было превратить в «приложение красится вот
здесь»: нечем было превращать.

## Где установлено

`ApplicationLoader.onCreate()`, рядом с остальной инициализацией Colgram и в её
`try/catch(Throwable)`:

```java
try {
    org.colgram.core.ColgramCrashGuard.install(applicationContext);
} catch (Throwable ignore) {

}
```

Собственный `try/catch` здесь обязателен: обработчик, который бросает исключение, обрабатывая
краш, заменяет краш другим. Потерять отчёт намного лучше, чем потерять процесс.

## Проверка на устройстве, а не по наличию кода

```
I ColgramCrashGuard: native signal handler installed -> colgram_crash.log
I ColgramCrashGuard: crash reporting installed -> /data/user/0/org.colgram.messenger/files/colgram_crash.log
```

Оба уровня встали: и Java-обработчик необработанных исключений, и нативный через `sigaction`.
Нативный нужен отдельно, потому что SIGSEGV убивает процесс, не порождая Throwable, - до Java-
обработчика дело не доходит.

Читать отчёты:

```
adb shell run-as org.colgram.messenger cat files/colgram_crash.log
```

## Почему это не было видно

Тот же класс дефекта, что и со счётчиками вкладок: класс на месте, документация подробная,
компиляция зелёная - а подключения нет. Наличие кода выглядит как работающая функция, пока не
спросишь, кто её вызывает.
