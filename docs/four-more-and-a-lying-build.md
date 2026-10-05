# Four more, and a build that reported success while lying

2026-10-02. The four previous fixes were correct in the source, in the class file, and absent
from the installed APK. Gradle printed BUILD SUCCESSFUL in seconds and left a two-build-old
artifact because every task was UP-TO-DATE against a stale input snapshot.

```
source      FAST_NATIVE_PARALLEL = 8
class file  FAST_NATIVE_PARALLEL present
installed   FAST_NATIVE_PARALLEL absent
```

`--rerun-tasks` fixes it, and the daemon then died for want of memory: 4.2 GB free against a 4 GB
heap, with the emulator holding about five. gradle.properties already said so in a comment --
"With -Xmx8g + parallel workers the daemon gets OOM-killed" -- so the heap is 2560m now and
the build is --no-daemon.

## Eight was too many, and the file said why

I raised the parallel check budget from two to eight. Measured on the device it did not get
faster, and the constant above it explains why:

```
// tgnet owns only four proxy-check connections (PROXY_CONNECTIONS_COUNT). Keep two
// unoccupied ... Overfilling tgnet queue starts the timeout before queued
// checks even reach the network, which painted live candidates "dead".
```

Eight checks queued into four slots spend their whole budget waiting behind each other. Back to
two, with the four second deadline, which is the shape that measures well:

```
16:25:02  2.27.12.116:443        -> dead
16:25:05  rdp.nl.2.mtproto.ru:443  -> dead      3.2 s apart, was 7
16:25:09  welcome.kisex.top:443   -> dead
16:25:12  194.50.94.169:443       -> dead
16:25:16  edge.ehtemal.info:443    -> dead
```

## The local front was winning over a verified node

```
15:33:44  Applying proxy: ssh.meow0.co.uk:22 (type=1)   <- verified, 145 ms
15:33:47  Applying proxy: 127.0.0.1:9876 (type=0)      <- local front, replaced it
15:33:50  native check ssh.meow0.co.uk:22 -> 196 ms     <- still working, never used again
```

The local desync front was preferred whenever localBypassUsable() was true, and that only asks
whether a socket is bound. Bound is not working. It keeps priority only while no verified remote
node exists, and says so when it gives way up.

## A node that passes its checks and carries nothing was never rotated

Rotation was driven only by a node failing its own protocol check, so a node that answered the
check but could not carry a session was never replaced. Measured:

```
15:33:44  auto-connecting through ssh.meow0.co.uk:22
15:33:52  auto-connecting through ssh.meow0.co.uk:22
15:33:54  auto-connecting through ssh.meow0.co.uk:22
```

Four attempts on one node, and the node passing its check each time. Passing a check and carrying
a session are different facts, and only the second was being asked about, so the answer was
always yes. The watchdog now counts ticks where a node passes everything and still carries
nothing, and rotates on the third. Its own counter, because the one below it in the loop is
cleared by the re-dial branch and the two would have reset each other.

## After

```
16:24:03  no node to fall back to: pool=193
16:24:07  native check ssh.meow0.co.uk:22 -> 160 ms     <- four seconds in, was seventy
16:24:48  native check 127.0.0.1:19183   -> 407 ms
```
