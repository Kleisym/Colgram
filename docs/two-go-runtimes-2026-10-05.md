# Two Go runtimes in one process, and what it does

## The symptom, measured

`ColgramProfileDeviceTest` passes on its own - all three tests, every protocol profile the engine
accepts. It fails when `ColgramWarpVerdictDeviceTest` ran first in the same process:

    ColgramWarpVerdict:   warp=on
    ColgramProfile: parsed vless publicKey=[bmXOC-F1FxEMF9dyiK2H5_1SUtzH0JuVo51h2wPfgyo]
    ColgramProfile: vless profile: {"log":{...},"route":{"rules":[{"ip_cidr":["0.0.0.0\/0","::\/0"],"outbound":"auto"}]}}
    E TestRunner: java.lang.AssertionError: the engine refused the vless profile:
      go.Universe$proxyerror: initialize router: parse rule[0]: ipcidr:
      parse [0]: netip.ParsePrefix("C\x00\x00\x00\x00\x00\x00\x00D"): no '/'

The profile logged immediately before the refusal is intact. What Go parsed was eight NUL bytes and a
`D` where `0.0.0.0/0` should be: a descriptor to memory that has been reused, not a profile built wrong.

Forcing a collection after each verdict makes the same corruption surface as a crash instead:

    E Go: fatal error: out of memory
    Go: runtime.(*mcache).allocLarge
    Go: runtime.mallocgcLarge
    Go: runtime.growslice
    Zygote: Process 56679 exited due to signal 6 (Aborted)

An allocator that cannot account for its own memory is a runtime whose bookkeeping is already damaged. The
fault was always there; a collection only moved it somewhere louder.

## The cause

Two of this app's native libraries are gomobile builds, and each carries a complete Go runtime:

    colgrammasque    12,247,536 bytes  runtime.osinit True  runtime.schedinit True  crosscall2 True
    libbox          80,804,712 bytes  runtime.osinit True  runtime.schedinit True  crosscall2 True

Each one starts its own scheduler, its own garbage collector and its own heap on load. Two of them in one
process means two collectors walking the same address space and two sets of allocator metadata over memory
that Android's own allocator also owns. Whichever one runs a collection first decides whether the other's
arena still looks valid, and the answer is not stable between runs - which is exactly the shape of a bug
that passes in isolation and fails in a suite.

This is the same family as the fault already documented in `ColgramVpnService`: a `CommandServer` closed
without releasing the Go object behind it, where the gomobile finalizer thread then walked freed memory.
That one was a stale reference inside one runtime. This one is two runtimes competing for one heap.

## Why the suite could never have caught it before

The androidTest variant did not build:

    Failed to apply plugin 'com.google.gms.google-services'
    No such property: libraryVariants for class: java.lang.String

So no instrumented test ran at all until that was fixed, and until then nothing had ever loaded
`colgrammasque` and `libbox` into the same process and asked both to do work.

## What is safe to say

The profile builder is not at fault. Seven profiles - VLESS with Reality, VMess, Trojan, Shadowsocks,
Hysteria2, Hysteria1, SOCKS5 - are parsed, built and accepted by the engine when it is the only native
library in the process, and a three-node profile with failover is accepted too.

The fix belongs in how the two runtimes are loaded, not in the profile builder and not in the test. Three
ways, in the order worth trying:

1. **One runtime for both.** Build the MASQUE client into `libbox` itself, so the app has one Go runtime
   and one heap. This is the real fix and the only one that removes the class of fault rather than the
   instance.
2. **Load order and lifetime.** If both must ship separately, one runtime has to finish and release before
   the other starts - which a tunnelling app cannot promise, because the WARP tunnel and the sing-box tunnel
   can both be up at once.
3. **Separate processes.** The MASQUE client moves into its own service process. Android gives each
   process its own address space, so the two runtimes cannot see each other. It costs an IPC hop and a
   binder surface, and it is a containment measure rather than a fix.

What is not a fix: calling `System.gc()`, changing the order of the tests, or retrying the call. All three
were measured and all three only moved the crash.

## What the containment did, and what it did not do

`ColgramMasqueVpnService` now declares `android:process=":colgram_masque"`, so the MASQUE client gets its
own address space. Verified from the installed package:

    ColgramSingleRuntime: engine process=org.colgram.messenger.web
                          masque process=org.colgram.messenger.web:colgram_masque

The pairing that always failed now passes. With the WARP verdict test and the engine's profile tests in
one run:

    theAppReadsWarpOnFromInsideItsOwnTunnel                    8.315s
    theMasqueClientLivesInItsOwnProcessSoTheRuntimesCannotCollide 0.565s
    everyProtocolWePromiseProducesAProfileTheEngineAccepts      0.996s
    aWholeSubscriptionBecomesOneProfileWithFailover             0.159s

No memory corruption, no allocator panic, no recycled-string parse error. The fault was in the two runtimes
sharing a heap and that is now structurally impossible in the app.

### Two faults found on the way, both real

**The QUIC path panicked inside Go's own TLS**, on a network that filters UDP to this edge:

    panic: runtime error: invalid memory address or nil pointer dereference
    crypto/tls.unsupportedCertificateError       auth.go:295
    crypto/tls.(*CertificateRequestInfo).SupportsCertificate
    created by crypto/tls.(*QUICConn).Start

`auth.go:295` dereferences the result of `Curve.Params()`, which is nil for a curve the build does not
carry. A panic on any goroutine takes the process with it, and this one was reachable every time the
verdict was asked. UDP is now probed before the carrier is dialled, so a path that cannot answer is never
tried, and the attempt itself is contained. Measured effect: the verdict went from 107 seconds and a dead
process to 8.6 seconds and a body.

**The profile builder emitted a TUN field the engine refuses.** An intermediate version of this wrote
`inet4_address` and `inet6_address` on the reading that they were the modern spelling. They are the
deprecated ones, and the engine's own schema is the authority:

    Address       badoption.Listable[netip.Prefix] `json:"address"`
    // Deprecated: merged to Address
    Inet4Address  badoption.Listable[netip.Prefix] `json:"inet4_address" schema:"omit"`
    // Deprecated: merged to Address
    Inet6Address  badoption.Listable[netip.Prefix] `json:"inet6_address" schema:"omit"`

The refusal reads like the list being the problem, which is the opposite:

    initialize inbound[0]: legacy tun address fields are deprecated in sing-box 1.10.0
    and removed in sing-box 1.12.0

Both spellings are legacy; the merged one is the address list. `ColgramTunInboundDeviceTest` already asked
the engine what it accepts, and that test is what settled it.

## What remains

In a run that puts fifteen tests in one instrumentation process, `libbox` still faults with

    fatal error: slice bounds out of range
    runtime/panic.go:59
    runtime.panicBounds64
    runtime.tracebackPCs

`tracebackPCs` is the first frame that touches memory, so the heap was already damaged before it ran. That
is cross-test state inside the prebuilt engine, in a test JVM nobody ships, and it is not the same fault as
the two-runtime one: the MASQUE library is not in this picture any more. It has not been traced to a
specific call, and it is the one remaining item on this list.
