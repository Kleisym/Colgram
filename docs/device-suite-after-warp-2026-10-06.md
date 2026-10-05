# The device suite, after the tunnel started carrying

    OK (16 tests)

    ColgramMasqueOnDeviceTest          .
    ColgramDpiBypassDeviceTest         ...
    ColgramCallProxyDeviceTest        ...
    ColgramThemeContrastDeviceTest    .
    ColgramBrandDeviceTest            .
    ColgramGlobalSearchHistoryDeviceTest   ..
    ColgramGlobalSearchRestoreDeviceTest  ...
    ColgramPoolShapeDeviceTest        .
    ColgramMasqueSingleRuntimeDeviceTest   .

Sixteen device tests, no failures, with the WARP tunnel in the same APK reading warp=on from inside the app.
The transport work did not regress the reported bugs: the bypass still binds and stops, calls still default to
the proxy, the theme contrast still measures, the brand, the search history with its per-row delete, the
search restore path and the pool shape all still pass, and the MASQUE client still runs in its own process
beside the engine.

One crash was seen once, in a run that mixed the freshly installed APK with libraries from a build still in
flight, and it did not reproduce in either isolation or in the suite above. It is recorded rather than claimed
absent.
