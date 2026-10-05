# «establish() вернул null» - это был не VPN-слот, а prepare()

Дата: 2026-10-01, 14:25.

## Что выдавала диагностика

```
E ColgramMasqueVpn: establish() returned no descriptor
```

И ничего больше: ни исключения, ни строки в системном логе, ни интерфейса tun после этого. Формулировка
указывала на слот или на сеть, и оба чтения были ложными.

## Что проверено до того, как причина нашлась

| Проверка | Результат |
|---|---|
| `android:permission="android.permission.BIND_VPN"` в манифесте | есть |
| `<action android:name="android.net.VpnService"/>` в intent-фильтре | есть |
| `exported="false"` | есть |
| Регистрация сервиса в `dumpsys package` | `filter 82ac45e permission android.permission.BIND_VPN` |
| VPN-слот (`dumpsys connectivity`) | свободен |
| Согласие: `appops get ACTIVATE_VPN` | `allow` |
| Приложение в фокусе | да |

Всё на месте, а `establish()` всё равно возвращал null.

## Настоящая причина

`Builder.establish()` возвращает дескриптор только тому приложению, **которое прошло через
`VpnService.prepare()`**. `prepare()` возвращает Intent, который надо показать пользователю, и запустить
сервис **этим** Intent:

```java
Intent prepared = VpnService.prepare(context);
if (prepared != null) {
    startActivity(prepared);          // диалог согласия
    return;                            // и продолжить позже
}
// только после этого:
context.startService(intent);
```

Было:

```java
context.startService(intent);          // всегда, без prepare()
```

И прямой запуск сервиса извне - `am startservice` - идёт мимо `prepare()` по определению, поэтому и не
работал.

## Что изменено

`start()` теперь спрашивает `prepare()` и отказывается стартовать без согласия, а `onStartCommand`
проверяет подготовленное состояние и говорит об этом прямо:

```java
// start()
Intent prepared = android.net.VpnService.prepare(context);
if (prepared != null) {
    Log.i(TAG, "VPN consent required; the caller must show prepare() and retry");
    return;
}
context.startService(intent);

// onStartCommand()
if (android.net.VpnService.prepare(this) != null) {
    Log.e(TAG, "service started without prepare(); Android would refuse the descriptor");
    stopSelf();
    return START_NOT_STICKY;
}
```

Вторая проверка важнее первой: она превращает «establish() вернул null» в сообщение с настоящей
причиной, даже если сервис подняли сторонним способом.

```
:colgram-core:compileReleaseJavaWithJavac
BUILD SUCCESSFUL in 13s
```

## Что осталось

Путь через `prepare()` живёт в настройках, а настройки - за входом в аккаунт. Так что довести туннель
на весь телефон до наблюдаемого состояния на этом устройстве нельзя, и причина не в коде: `prepare()`
нужно вызвать из UI, который за экраном авторизации.

Согласие на устройстве выдано (`ACTIVATE_VPN: allow`), так что осталось только дойти до
переключателя.

## Вторая половина: start() врал в лог

`start()` возвращал `void`, и вызывающий код писал:

```java
ColgramMasqueVpnService.start(context, bind, edge);
Log.i(TAG, "device-wide tunnel requested; VpnService started");
```

Строка печаталась **всегда**, независимо от того, стартовал сервис или нет. То есть при отсутствии
согласия лог рапортовал «started», переключатель оставался включённым, а трафик телефона шёл мимо
туннеля - и это выглядело как «туннель включён и ничего не меняет».

Теперь `start()` возвращает Intent из `prepare()`, когда диалог ещё не показан:

```java
public static android.content.Intent start(Context context, String bind, String edge) {
    ...
    Intent prepared = android.net.VpnService.prepare(context);
    if (prepared != null) {
        Log.i(TAG, "VPN consent required; the caller must show prepare() and retry");
        return prepared;
    }
    context.startService(intent);
    return null;
}
```

и вызывающий различает два случая:

```java
android.content.Intent consent =
        ColgramMasqueVpnService.start(ctx.getApplicationContext(), bind, edge);
if (consent == null) {
    Log.i(TAG, "device-wide tunnel requested; VpnService started");
} else {
    Log.i(TAG, "device-wide tunnel needs the VPN consent dialog before it can start");
}
```

## Согласованность путей

Путь подписки делал `prepare()` правильно с самого начала:

```java
android.content.Intent prepare = android.net.VpnService.prepare(getParentActivity());
if (prepare != null) {
    startActivityForResult(prepare, REQ_SUBSCRIPTION_VPN);
    return;
}
```

и обработчик результата есть для обоих:

```
if (requestCode == REQ_WARP_VPN) {
    if (resultCode == RESULT_OK) startWarpTunnel();
    else Toast("Без разрешения на VPN WARP-туннель не включается");
}
```

Так что через настройки путь согласован от начала до конца - расходилась только функция,
вызываемая из MASQUE-туннеля.
