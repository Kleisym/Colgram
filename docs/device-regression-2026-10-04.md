# Device regression, 2026-10-04

Everything below was run on the MuMu device `SM-A536E - 15` through
`:TMessagesProj_AppTests:connectedAfatDebugAndroidTest`, against the afatDebug build carrying the new
`libcolgrammasque.so`.

## The suite could not be built at all before this

Every androidTest variant failed to configure:

    A problem occurred evaluating project ':TMessagesProj'
    > Failed to apply plugin 'com.google.gms.google-services'.
      > No such property: libraryVariants for class: java.lang.String

`TMessagesProj_AppTests/build.gradle` reached for `android.applicationVariants`, and google-services reads
`libraryVariants` off the same object. The two together left the plugin resolving its own accessor against
a String. The block is now set on the packaging task instead, which is the last point at which either
property is read. The version code moved with it, and dropping it was a regression caught on the device:

    INSTALL_FAILED_VERSION_DOWNGRADE: Update version code 7089 is older than current 70899

## 30 of 32 pass

    ColgramThemeContrastDeviceTest            everyDarkThemeKeepsTextOffItsOwnBackground
    ColgramGlobalSearchHistoryDeviceTest       historyIsCappedAndOldestEntriesFallOff
    ColgramGlobalSearchHistoryDeviceTest       historyRoundTripsAndSurvivesAwkwardQueries
    ColgramGlobalSearchRestoreDeviceTest       openingAGlobalResultMustNotCloseTheSearch
    ColgramGlobalSearchRestoreDeviceTest       historyIsWrittenBeforeOpeningSoTheQuerySurvivesAReopen
    ColgramGlobalSearchRestoreDeviceTest       openingAResultPushesAFragmentSoBackHasSomewhereToReturn
    ColgramCallProxyDeviceTest                 aUserWhoNeverTouchedTheSwitchGetsCallProxying
    ColgramCallProxyDeviceTest                 theDefaultOnlyMattersWhenAPlainProxyIsActuallyConfigured
    ColgramCallProxyDeviceTest                 anExplicitOffIsStillHonoured
    ColgramDpiBypassDeviceTest                 theListenerReallyAcceptsAConnection
    ColgramDpiBypassDeviceTest                 stopLeavesNothingListeningAndRunning
    ColgramDpiBypassDeviceTest                 aDeadListenerIsReportedAndCanBeRestarted
    ColgramDohResolverDeviceTest               cloudflareAnswersInBothFormats
    ColgramDohResolverDeviceTest               aCutResolverIsRememberedRatherThanRetriedForever
    ColgramDohResolverDeviceTest               theReplyParserReadsARealCapturedAnswer
    ColgramDohProbeDeviceTest                  dohReachabilityIsMeasuredOnThisNetwork
    ColgramDohProbeDeviceTest                  aBareTcpConnectDistinguishesRstFromSilence
    ColgramDohCaptureDeviceTest                captureARealAnswer
    ColgramFrontCarriageDeviceTest             theFrontCarriesHttpsToTheEnrolmentApi
    ColgramUdpRelayDeviceTest                  findAProxyThatRelaysUdp
    ColgramUdpAssociateDeviceTest              theHeaderLengthFollowsTheAddressForm
    ColgramUdpAssociateDeviceTest              anAssociateHandshakeReturnsAReachableUdpPort
    ColgramUdpAssociateDeviceTest              aDatagramIsAcceptedByTheAssociateSocketAndItsHeaderIsReadCorrectly
    ColgramPoolShapeDeviceTest                 reportThePoolByTransport
    ColgramProxyAutonomyDeviceTest             callsDefaultToUsingTheProxy
    ColgramProxyAutonomyDeviceTest             manualOffSurvivesStartupAndIsNotUndoneByTheManager
    ColgramProxyAutonomyDeviceTest             aDeadProxyIsNeverPersistedAsSomethingToConnectTo
    ColgramSubscriptionShareDeviceTest         aBotMessageYieldsTheLinkAndNotTheCaption
    ColgramSubscriptionShareDeviceTest         aSharedLinkReachesAProfileTheEngineAccepts
    ColgramSubscriptionShareDeviceTest         aMessageWithNoSubscriptionIsRefusedRatherThanGuessedAt
    ColgramBrandDeviceTest                     theInstalledAppNamesItselfColgram
    ColgramMasqueOnDeviceTest                  shippedLibraryOpensOrNamesItsFailure

## Three real faults the suite found, all fixed

### The bypass port could not be rebound, and the retry called stop() from its own worker

    E ColgramDpiBypass: java.net.BindException: bind failed: EADDRINUSE (Address already in use)
    W ColgramDpiBypass: DPI listener not ready after 20000ms

The recovery path called `stop()` from inside the worker's bind failure. `stop()` clears `isRunning` and
nulls `serverSocket` - the two fields the same worker sets three lines later for the very socket it is
re-binding - and `stop()` is `synchronized` on the class monitor `startNow` already holds, so it could not
finish until the worker returned. The rebind could not produce a working listener, which is exactly the
reported "the bypass does not work".

It now binds a free loopback port instead of tearing anything down, and the port is carried on a new
`activePort` field. All eight consumers of the old constant - Telegram's proxy setting, the health check,
the bot sync, the webview proxy rule - read `activePort()` now, because a moved listener that everything
else still points at 9876 is unreachable in the same way a dead one is.

### A stopped bypass went on accepting for a second and a half

The accept loop called `serverSocket.accept()` on the field rather than on its local socket. A loop
started before a `stop()` could therefore be handed the socket a later start installed and began serving
it on the old thread. `handleClient` now refuses work when the listener is not running, so what a caller
sees after a stop is a refused connection rather than a fifteen-second socket timeout.

### Turning WARP off killed the process

    Zygote: Process 11658 exited due to signal 6 (Aborted)

`colgram_masque_close_session` guarded on `s.tun.conn`, which is nil by construction on the HTTP/2
carrier, and then closed it on the next line. The guard was supposed to catch exactly that and fell
through. The app calls close on every toggle and every service stop, so this was reachable from ordinary
use. It now tears down both carriers and cancels the context holding the session.

That third one is the same shape as "the app crashes in many places": a field that exists on one carrier
read as though it existed on both.

## The WARP tunnel opens on the phone

    native version: colgram-masque/1
    step 0 progress=open (carrying packets to 162.159.198.2:443)
           capsules=capsules=7 session=162.159.198.2:443 error=null

Seven capsules went out through the hand-framed HTTP/2 carrier on hardware. What is not yet `warp=on` is a
reply; see `docs/warp-h2-carrier-found-2026-10-04.md` for the four measurements that rule out framing,
destination, provisioning and port.

## One thing that still needs naming

`ColgramSubscriptionShareDeviceTest` passes alone:

    tests=3 failures=0 errors=0

and crashes when run after the rest of the suite:

    E Go: fatal error: slice bounds out of range
    runtime.tracebackPCs ...
    Zygote: Process 56788 exited due to signal 6 (Aborted)

The faulting frame is `runtime.tracebackPCs`, which means the fault is in the Go runtime walking a stack
through already-corrupted memory - the heap was damaged before the traceback itself ran. It is in
`libbox.so`, the prebuilt sing-box engine, and that binary is unmodified in this work. So it is real and it
is a cross-test interaction rather than anything this change introduced, but its cause has not been
traced to a specific call yet.

The suite name quoted above is the passing one; the failing case is the second test in that class,
`aSharedLinkReachesAProfileTheEngineAccepts`.
