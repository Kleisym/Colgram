# The counter I added last session was the thing keeping dead nodes alive

2026-10-02, 18:38. The previous fix made things worse, and the log said so in one line:

```
100 checks, every one dead (1), pool down to 1 entry
```

## What I got wrong

I had forceApplyProxy() reset the failure counters on every apply, to stop a freshly rotated node
from inheriting the counts of whatever it was before. The order of events makes that useless:

```
forceApplyProxy(node)   ->  counters = 0
checkOne(node)         ->  verdict arrives
onVerdict(node, false)  ->  counters = 1
rotation reacts        ->  forceApplyProxy(node again)  ->  counters = 0
```

So a node that never worked was measured, found dead, had its count cleared by the very rotation
that reacted to the verdict, and was measured again from one. The count was the only thing standing
between a dead entry and a pool that never shrinks, and I reset it every pass. prunePool() removes
an entry at three failures; no entry could reach three.

```
18:11:10  native check 173.212.245.154:443 -> dead (1)
18:11:13  native check 217.144.187.230:443  -> dead (1)
... 59 distinct nodes, every one the first failure of a node, pool=1
```

The counters now reset exactly once per entry, on its first application, via an appliedOnce flag.
After that the count is the node own history and prunePool does what it was written to do.

## After

```
31 x dead (1)
 4 x dead (2)
 2 x dead (3)      <- removed from the pool

pool: 1 -> 193 -> 241
```

And the pool is made of things that answer:

```
18:38:46  t.meow-network.com:443     -> 247 ms
18:38:47  t.meow-network.com:443     -> 309 ms
18:38:48  ssh.meow0.co.uk:22        -> 581 ms
18:38:49  relay.surfvpn.app:443      -> 271 ms
18:38:51  ssh.meow0.co.uk:22        -> 214 ms
18:39:51  127.0.0.1:3655           -> 415 ms
```

Three or four checks a second through the parallel sweep, the pool holding two hundred and forty,
dead entries leaving after three failures. Against the state this started from: one node examined
per seventy seconds, a pool that only ever shrank, and every survivor getting the same retry
forever.

## The shape of the whole fix, in order

1. the checks were serial because two was the budget and the queue behind it was invisible
2. a node was checkable once in the life of the process
3. the rotation budget could not walk the pool: 8 changes at 30 s spends a five minute window
4. a node passing its checks while carrying nothing was never rotated
5. the fast sweep and the prober competed for the same four tgnet slots
6. a loopback relay front was exempt from pruning because only one loopback port was recognised
7. and then the fix for six cleared the counter that let anything be pruned at all

Seven defects, and the last one was introduced by fixing the sixth. The thing that found all of
them was reading the interval between two log lines, not looking at any of the code.
