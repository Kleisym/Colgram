#!/usr/bin/env bash
# MuMu's adb transport drops constantly ("device offline"). Reconnect, then run.
ADB="C:/android-sdk/platform-tools/adb.exe"
D=emulator-5554
up() {
  for i in 1 2 3 4 5 6; do
    st=$("$ADB" devices 2>/dev/null | grep -a "^$D" | awk '{print $2}')
    [ "$st" = "device" ] && return 0
    "$ADB" kill-server >/dev/null 2>&1
    sleep 1
    "$ADB" start-server >/dev/null 2>&1
    "$ADB" connect 127.0.0.1:7555 >/dev/null 2>&1
    sleep 2
  done
  st=$("$ADB" devices 2>/dev/null | grep -a "^$D" | awk '{print $2}')
  [ "$st" = "device" ]
}
up || { echo "ADB: could not bring $D online"; exit 1; }
exec "$ADB" -s "$D" "$@"
