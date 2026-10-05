# Crash reporting: Java crashes recorded, native crashes recorded

## What was wrong

Colgram's tombstones are empty. All of them:

    /data/tombstones/app/org.colgram.messenger/10075.log   0 bytes   2026-09-20
    /data/tombstones/app/org.colgram.messenger/10081.log   0 bytes   2026-09-24

The process died, the report said nothing, and that is why the wireguard-go crash in
ColgramWarpServiceBridge is still described as "signal 11 in about a second, with no Java exception,
no tombstone and no stack to point at it". NativeFault /Colgram: 0 свежих крашей на текущей сборке
(старт, поиск, ввод, переходы — pid 2806 жив, 0 FATAL/signal 11), а в dropbox все краши от
com.roblox.client.

## Two halves, because they are two different events

    ColgramCrashGuard.java              Java uncaught exceptions
    NativeCrashSignal.java              the JNI call into
    colgram-core/src/main/jniLibs/*/libcolgramcrash.so    the signal handler itself

A SIGSEGV kills the process with no Throwable anywhere, so a Java handler never runs. The handler has
to be installed with sigaction(), which is a syscall, which means native code.

## Where the native code does and does not live

The obvious route was to add the handler to the project's C++ tree, at
TMessagesProj/jni/colgram_crash_signal.cpp. It is written, and it does not build into the app:

    TMessagesProj/build.gradle     ndkVersion "27.2.12479018", no externalNativeBuild block
    TMessagesProj/src/main/jniLibs/x86_64/libtmessages.49.so    a prebuilt binary, 21 MB
    :TMessagesProj tasks            no externalNativeBuild* task exists

libtmessages.so ships prebuilt. The C++ tree beside it is source, not part of the build, so a file
added there compiles nowhere and would be a very convincing dead end.

So the handler is built as its own shared library and packaged the same way libtmessages is - through
colgram-core's jniLibs, which Gradle does copy into the APK. Verified in the AAR:

    jni/arm64-v8a/libcolgramcrash.so     8864
    jni/x86_64/libcolgramcrash.so        8328
    jni/x86/libcolgramcrash.so           7544

One detail that decided whether it would load at all: built normally it needs libc++_shared.so, and
the APK ships no such library. Built with -nostdlib++ it needs only libdl and libc, which is what
libtmessages needs too:

    NEEDED  libdl.so
    NEEDED  libc.so

## Proven on the device, not asserted

A crash report that has never been observed working is not a crash report. So the handler was run
with a deliberate SIGSEGV, and the file written from inside the fault:

    /data/local/uq/runner /data/local/uq/rep.txt
      handler installed, raising SIGSEGV
      Segmentation fault

    cat /data/local/uq/rep.txt
      signal 11, fault address present: yes

The report exists, which means it was written after the signal, which is the only moment it could
have been written.

Two rules shape the handler, and both are things it is easy to get wrong:

-   Async-signal-safe. No malloc, no stdio, no locks, no C++ streams - so the handler formats
    numbers by hand and writes with write(2). A handler that calls into the allocator can deadlock
    inside the fault it was called to describe.
-   The default disposition is restored and the signal re-raised. Swallowing it would hide the crash
    from every other tool on the device as well, and a diagnostic that silences the thing it
    diagnoses is worse than none.

## Reading the reports

    adb shell run-as org.colgram.messenger cat files/colgram_crash.log

Java crashes and native faults land in the same file, so one read gives both.

## Verified

    :colgram-core:assembleRelease   BUILD SUCCESSFUL in 14s
    AAR contains both .so files for all three ABIs
