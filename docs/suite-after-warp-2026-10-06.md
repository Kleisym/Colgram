# The full suite, and the one crash in it

## warp=on on the device, unchanged

    ColgramWarpVerdict:   CF-RAY: a46034794ef67a9d-ORD
    ColgramWarpVerdict:   ip=104.28.227.110
    ColgramWarpVerdict:   uag=colgram-warp-on
    ColgramWarpVerdict:   colo=ORD
    ColgramWarpVerdict:   tls=TLSv1.3
    ColgramWarpVerdict:   warp=on
    ColgramWarpVerdict:   kex=X25519

    org.colgram.core.ColgramWarpVerdictDeviceTest:.
    Time: 55,844
    OK (1 test)

## The suite that covers the reported bugs: 16 tests, no failures

    OK (16 tests)

bypass bound and stopped, calls defaulting to the proxy, theme contrast, brand, search history with its
per-row delete, search restore, pool shape, the MASQUE client in its own process beside the engine.

## The crash, and what it is

Run as all twenty-eight classes together, one test crashes the process:

    org.colgram.singbox.LibboxPresenceDeviceTest:..
    org.colgram.core.ColgramBrandDeviceTest:.
    org.colgram.core.ColgramUdpRelayDeviceTest:.
    org.colgram.core.ColgramMasqueOnDeviceTest:INSTRUMENTATION_RESULT: shortMsg=Process crashed.

The two classes before it are the engine, and the crash is in this client's own code:

    TestRunner: started: theEngineLoadsAndExposesItsVersion(org.colgram.singbox.LibboxPresenceDeviceTest)
    TestRunner: started: theInstalledAppNamesItselfColgram(org.colgram.core.ColgramBrandDeviceTest)
    TestRunner: started: findAProxyThatRelaysUdp(org.colgram.core.ColgramUdpRelayDeviceTest)
    TestRunner: started: shippedLibraryOpensOrNamesItsFailure(org.colgram.core.ColgramMasqueOnDeviceTest)
    Go: fatal error: unknown caller pc
    Go:   runtime.(*unwinder).next
    Go:   runtime.copystack(...)
    Go:   runtime.newstack()
    Go:   runtime.morestack()
    Go:   C:/Colgram/tools/warpgo/native/main.go:2844    <- enrolFresh

`runtime.copystack` failing with an unknown caller is a runtime whose own bookkeeping has been written by
something else, and the two Go runtimes in this process are the engine's `libbox.so` and this client's
`libcolgrammasque.so`. This is the fault already recorded in docs/two-go-runtimes-2026-10-05.md, and the reason
the MASQUE client is declared in its own process in the manifest:

    android:process=":colgram_masque"

A test that calls the library directly does not go through that service - it calls into the cgo boundary in
whichever process the test runner is in - so in a suite where the engine has already been loaded into that
process, this client runs beside it and the runtimes collide. On its own the test passes:

    org.colgram.core.ColgramMasqueOnDeviceTest:.
    Time: 12,71
    OK (1 test)

and so does the whole set that excludes it from a process where the engine is loaded:

    OK (16 tests)

So this is a test that reaches past the process boundary the library is meant to live behind, not a fault in
the tunnel - the verdict is read through the service in the same APK and passes there. The fix is in the test:
have it ask the service, the way the app does, rather than calling the cgo entry point directly. It is
recorded here rather than as fixed because it has not been changed yet.
