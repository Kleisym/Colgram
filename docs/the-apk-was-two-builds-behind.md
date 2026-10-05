# The APK on the device was two builds behind, and Gradle would not say so

2026-10-02. After the four fixes were written the device still behaved exactly as it had before
them. Checksums agreed with the source, the class file carried every new constant, and the
installed APK did not:

```
source      FAST_NATIVE_PARALLEL = 8
class file  FAST_NATIVE_PARALLEL present
installed   FAST_NATIVE_PARALLEL absent
```

Worth naming because it is the same shape as the other defects in this project: a build that
reports success and is not. assembleAfatRelease printed BUILD SUCCESSFUL in seconds and left a
14:54 artifact, because every task was UP-TO-DATE against a stale input snapshot. The change had
been made to the source; nothing had invalidated the task that consumes it.

--rerun-tasks produced a fresh artifact in 11m47s, and the constants are in it:

```
FAST_NATIVE_PARALLEL       present
NATIVE_CHECK_TIMEOUT_MS    present
ROTATION_DEBOUNCE_MS       present
MAX_ROTATIONS_PER_WINDOW  present
```

## Behaviour after, on the device

The check interval halved and the rotation stopped stalling:

```
15:27:44  109.248.160.115:443    -> dead
15:27:47  saadi.masnavi.info:443  -> dead      3.5 s apart, was 7
15:27:51  sabadkala.imalz.info    -> dead
15:27:54  daemi.net.masnavi.info   -> dead
15:27:58  lowpressure.simbol.info  -> dead
15:28:02  symbol.simbol.info      -> dead
15:28:05  genuismind.info          -> dead
15:28:08  190.2.144.63:443        -> dead
15:28:33  auto-connecting through 127.0.0.1:16525
```

Eight entries in twenty-four seconds, and the rotator free to move again inside the window
instead of having spent it in the first four minutes of five.

The probe message stopped lying too:

```
15:28:32  socks probe closed before a request; expected for a liveness check
```

## What is still true, unchanged

Most of what the pools contain is dead, and that is not a defect:

```
mimic front skipped for 109.248.160.115:443 (no TCP route to the node itself)
mimic front skipped for box.lavazemi5.co.uk:443 (no TCP route to the node itself)
```

Those are nodes that do not answer at TCP level at all, which on a network that filters egress is
most of them. The ones that do answer are found in seconds rather than minutes now, and the
built-in bypass is on by default and one tap from the list that does not work.
