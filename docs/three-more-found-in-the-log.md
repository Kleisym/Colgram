# Three more, found by reading the log rather than the screen, 2026-10-02

## 1. Proxies were checked in a queue one at a time

The pool holds thirty to two hundred and forty entries. Each one got its own protocol check, one
at a time, and a dead one held its slot for the full timeout:

```
native check 2.namenewok.info:2096    -> dead    14:25:04
native check f5fe36.proxyhub.co:443   -> dead    14:25:10
native check 154.86.119.143:443      -> 106ms   14:25:13
native check 176.57.69.182:53627    -> dead    14:25:22
```

Six to eight seconds between verdicts, and the node that answers in a hundred milliseconds is
not reached until the eighth dead one has taken its turn. The constant said why, in its own
comment: two in flight because the native side serialises them anyway, twelve seconds each.

Now eight in flight and four seconds per check. Same pool, same moment:

```
14:50:37  154.86.119.143:443      -> 263ms
14:50:37  ssh.meow0.co.uk:22     -> 121ms
14:50:38  mt1.kurduk.store:443    -> 122ms
14:50:38  px.cryptocurency.wiki  -> 35ms
```

Four verified proxies in two seconds instead of one in seventy.

## 2. A node was checked once, ever

```java
if (... || item.fastProbeAttempted || item.nativeProbeInFlight.get()) continue;
item.fastProbeAttempted = true;
```

The flag was set and never cleared, so every node was checkable exactly once in the life of the
process. Free public proxies fail and come back -- a node that timed out during the harvest is
frequently the one that answers ten minutes later -- and one marked dead in the first sweep stayed
dead in every later one. That is why the pool only ever shrank while the switch kept spinning. Reset
per sweep now, so the flag means checked in this pass rather than ever checked.

## 3. The rotation budget could not walk the pool

```java
ROTATION_DEBOUNCE_MS     = 30000L;   // 30 s between switches
MAX_ROTATIONS_PER_WINDOW = 8;        // per five minutes
```

Eight changes at thirty seconds each spend the whole budget in four minutes, the ninth is
refused, and the app then holds a dead route until the window rolls over. Measured:

```
14:50:37  auto-connecting through 154.86.119.143:443
14:50:37  auto-connecting through ssh.meow0.co.uk:22
14:50:38  auto-connecting through px.cryptocurency.wiki:443
14:51:37  auto-connecting through px.cryptocurency.wiki:443   <- the same one, a minute later
```

Four verified proxies were available at that moment and the app was cycling between two of them.
That is the servers-do-not-answer report exactly: they did answer, and the budget ran out before
it reached them.

2.5 s between switches, 24 per window now: enough to finish a pass, short enough that one dead
connect is not a tight loop. Measured after:

```
14:55:54  154.86.119.143:443
14:55:55  ssh.meow0.co.uk:22
14:55:56  mt1.kurduk.store:443
14:55:56  px.cryptocurency.wiki:443
```

Four distinct verified nodes in two seconds.

## And the WARP switch, which was a race

```java
} finally {
    warpStartPending = false;   // ran immediately after the success line
}
```

The flag was written on the tap, then cleared in the finally block one statement after the redraw
was posted -- so the row was rebuilt from a flag that had already gone off, and the switch read
off while the tunnel was genuinely up. Cleared on the UI thread, in order, before the redraw, on
both the success and the failure path. That was the cannot-switch-it-on: it did switch, and then
undid it.
