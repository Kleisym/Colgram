# Почему APK ставится как .web, а не .messenger — и почему это не поломка установки

Дата: 2026-10-01. Устройство: MuMuPlayer, `127.0.0.1:16384`, Android 15 (SDK 35), образ
`Samsung/a53x/a53x:15/V417IR/163`.

## Симптом

`pm install` отвечает `Success`, но `org.colgram.messenger` не появляется — только
`org.colgram.messenger.web`:

```
Success: streamed 564101314 bytes
Success

pm list packages | grep colgram
package:org.colgram.messenger.web
```

Собранный APK при этом корректен: `assembleAfatDebug` даёт BUILD SUCCESSFUL за 368 задач,
файл 564101314 байт, внутри есть `libcolgrammasque.so`.

## Первое, что было не так в диагнозе

Первый вывод был неверным, и это стоит записать прямо: «APK ставится как `.web`» — не поломка
установки, а так и называется этот вариант сборки.

```groovy
buildTypes {
    debug {
        applicationIdSuffix ".web"
    }
    standalone {
        applicationIdSuffix ".web"
    }
    release {
        signingConfig signingConfigs.release
    }
}
```

`defaultConfig.applicationId` — `org.colgram.messenger`, и суффикс `.web` добавляют только `debug`
и `standalone`. Поэтому `assembleAfatDebug` даёт пакет с id `org.colgram.messenger.web`, и он
устанавливается под своим именем. Чтобы получить `org.colgram.messenger`, нужен
`:TMessagesProj_AppStandalone:assembleAfatRelease`, у которого суффикса нет.

То, что сбило с толку, объясняется тем же: имя `.messenger` на устройстве принадлежало пакету от
28.09, а новый APK встал рядом под другим id. Рядом стоял и `.web.test` — тестовый вариант, который
тоже никто не трогал.

## Что всё-таки сломано на образе

Из logcat:

```
E SystemServiceRegistry: No service published for: persistent_data_block
E SystemServiceRegistry:   at android.app.SystemServiceRegistry$StaticServiceFetcher.getService(SystemServiceRegistry.java:2406)
E SystemServiceRegistry:   at android.content.Context.getSystemService(Context.java:4588)
E SystemServiceRegistry:   at com.android.server.pm.PackageInstallerSession.markAsSealed(PackageInstallerSession.java:2444)
E SystemServiceRegistry:   at com.android.server.pm.PackageInstallerSession.commit(PackageInstallerSession.java:2193)
E SystemServiceRegistry:   at com.android.server.pm.PackageManagerShellCommand.doCommitSession(PackageManagerShellCommand.java:4337)
```

`markAsSealed` обязан записать метку о переходе сессии в состояние `sealed`. Для этого он
запрашивает системный сервис `persistent_data_block`, который на этом образе не опубликован:

```
service check persistent_data_block
Service persistent_data_block: not found

ls /vendor/bin/hw/ | grep -i block
пусто

ls /system/lib64/libpersistent_data_block*
No such file or directory
```

Ни сервиса, ни библиотеки в образе нет. Из-за этого:

- `pm install -r` поверх старой версии — `Success`, но время обновления пакета не меняется: коммит
  сессии не завершается;
- после `pm uninstall` — `Success`, и пакет не появляется вовсе.

Это отдельная от `.web` проблема, и она в том, что `markAsSealed` не может записать состояние
сессии. На установку варианта `.web` она не влияет — тот ставится тем же вызовом и появляется.

## Что это не дефект APK

-   Ручной `mv` в `/data/system` работает — SELinux в permissive, права на файле корректны:

    ```
    cp /data/system/install_sessions.xml /data/system/tst.xml && echo COPY_OK   -> COPY_OK
    mv /data/system/tst.xml /data/system/tst2.xml && echo RENAME_OK             -> RENAME_OK
    ```

-   Файл сессий читается и содержит запись предыдущей установки — то есть состояние есть, а
  записи о новой коммите нет:

    ```
    /data/system/install_sessions.xml   733 bytes, system:system, u:object_r:system_data_file:s0
    ```

-   `/mnt/expand` вообще не смонтирован, из-за чего сессионный каталог не подготовить:

    ```
    pm install --force-uuid -r ...
    java.io.IOException: Failed to prepare session dir: /mnt/expand/-r/app/vmdl887314466.tmp
    ```

## Как проверить нативный клиент

Из apk нужен только нативный клиент, и он запускается отдельно от установки приложения:

```
adb push libcolgrammasque.so /data/local/uq/libcolgrammasque.so
adb push t8 /data/local/uq/t8
adb shell chmod 755 /data/local/uq/t8
adb shell WARP_SOCKS=10.0.2.2:15150 /data/local/uq/t8 '' '' 192.168.0.4
```

Именно так и получено `warp=on` с устройства — см. `warp-on-device-achieved.md`. Тот же самый
`.so` лежит в обоих собранных APK, то есть код, который даёт результат, в приложении есть.

## Что починить на стороне образа

Нужен `android.hardware.persistentstats@1.0-service` и его `.rc` в `/vendor/etc/init`, либо
сборка мука, где сервис присутствует. Это правка образа эмулятора, к приложению отношения не
имеющая.
