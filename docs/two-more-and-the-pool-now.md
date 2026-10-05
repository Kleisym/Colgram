# Two more, and what the pool finally looks like

2026-10-02, late. Both found by reading the log rather than the screen.

## A loopback relay front was never pruned, so it was checked 38 times

```
16:36:45  native check 127.0.0.1:19183 -> dead (31)
16:36:49  native check 127.0.0.1:19183 -> dead (32)
...
16:37:13  native check 127.0.0.1:19183 -> dead (38)
```

prunePool() removes anything that has failed MAX_FAILED_VERDICTS times, but it skips isLocalDpi().
That test is exact:

```java
boolean isLocalDpi() {
    return "127.0.0.1".equals(address) && port == ColgramDpiBypass.LOCAL_PORT;
}
```

and LOCAL_PORT is 9876. A relay front binds an ephemeral port, so 127.0.0.1:19183 was an ordinary
pool entry, exempt from nothing, and after three failures it should have been removed -- except the
removal was skipped for the applied entry, and the rotation kept handing traffic to it. The count
climbed instead of the entry leaving.

Two fixes, because either alone leaves a case open: prunePool() no longer treats every loopback
address as exempt, and forceApplyProxy() resets the failure counters when it applies something
different. Counters belong to the last attempt, not to the node, and carrying them across a re-apply
meant a freshly rotated node was already on its way out of the pool.

## The fast sweep and the prober were fighting over the same four slots

finishSweep started both at once, so two loops queued into the four tgnet check connections and
the slower governed the pool. The measured interval was the sequential prober alone:

```
17:15:15  197.221.240.240:80 -> dead
17:15:23  45.74.31.50:5494    -> dead      7 s apart
17:15:30  8.213.128.6:808    -> dead
```

The prober is the steady re-check after a pass; starting it early only took slots from the pass
that had not happened yet. It now joins fifteen seconds in, once the sweep has drained.

## What the pool looks like now

```
17:53:45  ssh.meow0.co.uk:22        -> 784 ms
17:53:47  127.0.0.1:9876           -> dead
17:53:47  Applying proxy: haven.lite64.top:443
17:53:51  169.197.142.225:443      -> dead
17:53:55  95.182.91.199:443       -> dead
17:54:02  general.irancell-ir.cfd  -> dead
17:54:06  haven.lite64.top:443      -> 146 ms
17:54:13  saadi.masnavi.info      -> dead
17:54:21  daemi.net.masnavi.info     -> dead
17:54:32  haven.lite64.top:443      -> 128 ms
```

A verified node is applied within a second of being checked, dead entries drop out after three
failures instead of accumulating, and the rotation moves between live candidates rather than
retrying one. Three to four seconds between checks, against seven before, and against seventy when
this started.
