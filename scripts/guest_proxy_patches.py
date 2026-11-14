"""Integrate an account-free proxy probe with the existing Telegram JNI callbacks."""
from pathlib import Path
import re
import shutil


def patch_application(source):
    guard = "        if (org.colgram.core.ColgramProcess.isMasqueProcess(this)) return;"
    marker = "// COLGRAM_GUEST_PROXY_BOOTSTRAP"
    if marker in source:
        return source
    if source.count(guard) != 1:
        raise ValueError("Missing pinned early-process guard")
    return source.replace(guard, guard + f'''
        {marker}
        if (org.colgram.core.ColgramProcess.isGuestProxyProcess(this)) {{
            org.telegram.tgnet.ColgramGuestProxyService.enterProbeProcess();
            return;
        }}''', 1)


def patch_connections(source):
    marker = "// COLGRAM_GUEST_PROXY_CALLBACK"
    if marker in source:
        return source

    def insert(name, code, output_type="void"):
        nonlocal source
        pattern = r"(?m)^    public static " + output_type + " " + name + r"\([^\n]*\) \{"
        matches = list(re.finditer(pattern, source))
        if len(matches) != 1:
            raise ValueError("Missing or ambiguous callback: " + name)
        end = matches[0].end()
        source = source[:end] + "\n        " + code + source[end:]

    insert("onRequestComplete", f'''{marker}
        if (ColgramGuestProxyService.inProbeProcess()) {{
            ColgramGuestProxyService.onRequestComplete(currentAccount, requestToken, response);
            return;
        }}''')
    for method in ("onRequestClear", "onRequestQuickAck", "onRequestWriteToSocket",
                   "onUnparsedMessageReceived", "onUpdate", "onSessionCreated",
                   "onConnectionStateChanged", "onLogout", "onInternalPushReceived",
                   "onUpdateConfig", "onBytesSent", "onBytesReceived", "onRequestNewServerIpAndPort",
                   "onProxyError", "onPremiumFloodWait", "onIntegrityCheckClassic", "onCaptchaCheck"):
        insert(method, "if (ColgramGuestProxyService.inProbeProcess()) return;")
    insert("getInitFlags", "if (ColgramGuestProxyService.inProbeProcess()) return 0;", "int")
    # The candidate is resolved before JNI. Do not invoke Telegram's app-wide DNS
    # fallback or initialize AndroidUtilities from this process.
    insert("getHostByName", '''if (ColgramGuestProxyService.inProbeProcess()) {
            native_onHostNameResolved(hostName, address, "");
            return;
        }''')
    return source


def patch_manifest(source):
    if "org.telegram.tgnet.ColgramGuestProxyService" in source:
        return source
    node = '''
        <service
            android:name="org.telegram.tgnet.ColgramGuestProxyService"
            android:exported="false"
            android:process=":colgram_proxy_probe" />
'''
    if source.count("</application>") != 1:
        raise ValueError("Missing application element")
    return source.replace("</application>", node + "    </application>", 1)


def patch_native_loader(source):
    if "public static void loadPackagedForGuestProbe()" in source:
        return source
    anchor = '    private final static String LOCALE_LIB_SO_NAME = "lib" + LIB_NAME + "loc.so";'
    if source.count(anchor) != 1:
        raise ValueError("Missing packaged Telegram native library name")
    return source.replace(anchor, anchor + '''

    public static void loadPackagedForGuestProbe() {
        // The probe has no access to the main process's legacy extracted-library cache.
        System.loadLibrary(LIB_NAME);
    }''', 1)


def apply(repo):
    main = Path(repo) / "TMessagesProj/src/main"
    telegram = main / "java/org/telegram"
    for path, update in (
        (telegram / "messenger/ApplicationLoader.java", patch_application),
        (telegram / "messenger/NativeLoader.java", patch_native_loader),
        (telegram / "tgnet/ConnectionsManager.java", patch_connections),
        (main / "AndroidManifest.xml", patch_manifest),
    ):
        before = path.read_text(encoding="utf-8")
        after = update(before)
        if after != before:
            path.write_text(after, encoding="utf-8", newline="\n")
    shutil.copyfile(Path(__file__).parent / "templates/ColgramGuestProxyService.java",
                    telegram / "tgnet/ColgramGuestProxyService.java")
