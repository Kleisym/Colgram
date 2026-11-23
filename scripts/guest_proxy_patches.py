"""Integrate an account-free proxy probe with the existing Telegram JNI callbacks."""
from pathlib import Path
import re
import shutil


def patch_application(source):
    guard = "        if (org.colgram.core.ColgramProcess.isMasqueProcess(this)) return;"
    marker = "// COLGRAM_GUEST_PROXY_BOOTSTRAP"
    if marker not in source:
        if source.count(guard) != 1:
            raise ValueError("Missing pinned early-process guard")
        source = source.replace(guard, guard + f'''
        {marker}
        if (org.colgram.core.ColgramProcess.isGuestProxyProcess(this)) {{
            org.telegram.tgnet.ColgramGuestProxyService.enterProbeProcess();
            return;
        }}''', 1)
    lifecycle_marker = "// COLGRAM_ISOLATED_PROCESS_LIFECYCLE"
    if lifecycle_marker not in source:
        cfg_anchor = "    @Override\n    public void onConfigurationChanged(Configuration newConfig) {\n        super.onConfigurationChanged(newConfig);"
        if cfg_anchor not in source:
            raise ValueError("Missing ApplicationLoader.onConfigurationChanged")
        source = source.replace(cfg_anchor, '''    private boolean isIsolatedHelperProcess() {
        return org.colgram.core.ColgramProcess.isMasqueProcess(this)
                || org.colgram.core.ColgramProcess.isGuestProxyProcess(this);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ''' + lifecycle_marker + '''
        if (isIsolatedHelperProcess()) return;''', 1)
        for cb_anchor in (
            "    @Override\n    public void onLowMemory() {\n        super.onLowMemory();",
            "    @Override\n    public void onTrimMemory(int level) {\n        super.onTrimMemory(level);",
        ):
            if cb_anchor not in source:
                raise ValueError("Missing ApplicationLoader lifecycle callback: " + cb_anchor)
            source = source.replace(cb_anchor, cb_anchor + "\n        if (isIsolatedHelperProcess()) return;", 1)
    return source


def patch_connections(source):
    marker = "// COLGRAM_GUEST_PROXY_CALLBACK"
    if marker not in source:

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

    init_marker = "// COLGRAM_GUEST_PROXY_INIT_GUARD"
    if init_marker not in source:
        ctor_anchor = "    public ConnectionsManager(int instance) {\n        super(instance);"
        if ctor_anchor not in source:
            raise ValueError("Missing ConnectionsManager(int) constructor")
        source = source.replace(
            ctor_anchor,
            ctor_anchor + "\n        " + init_marker + "\n        if (ColgramGuestProxyService.inProbeProcess()) return;",
            1,
        )
        init_anchor = "    public void init(int version, int layer, int apiId, String deviceModel, String systemVersion, String appVersion, String langCode, String systemLangCode, String configPath, String logPath, String regId, String cFingerprint, int timezoneOffset, long userId, boolean userPremium, boolean enablePushConnection) {"
        if init_anchor not in source:
            raise ValueError("Missing ConnectionsManager.init")
        source = source.replace(init_anchor, init_anchor + "\n        if (ColgramGuestProxyService.inProbeProcess()) return;", 1)
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
