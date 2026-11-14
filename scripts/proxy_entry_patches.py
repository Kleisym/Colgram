"""Keep pinned stock save/deep-link entry points behind protocol validation."""


def patch_saved_proxy_form(source):
    marker = "// COLGRAM_PROXY_SAVE_VALIDATE"
    if marker in source:
        return source
    start = source.index("                    SharedPreferences preferences = MessagesController.getGlobalMainSettings();")
    end = source.index("\n                    finishFragment();", start)
    replacement = f'''                    {marker}
                    SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                    final boolean reconnect = addingNewProxy || SharedConfig.currentProxy == currentProxyInfo
                            && preferences.getBoolean("proxy_enabled", false);
                    if (addingNewProxy) SharedConfig.addProxy(currentProxyInfo);
                    else SharedConfig.saveProxyList();
                    if (reconnect) {{
                        ProxySettings settings = currentProxyInfo.settings;
                        org.colgram.core.ColgramProxyManager.selectUserProxy(getParentActivity(),
                                settings.getAddress(), settings.getPort(), settings.getSecret(),
                                ProxySettings.typeToInt(settings.getType()), settings.getUser(), settings.getPassword());
                    }}
                    org.colgram.core.ColgramProxyManager.notifyProxySettingsChanged();
'''
    return source[:start] + replacement + source[end:]


def patch_proxy_link(source):
    marker = "// COLGRAM_PROXY_LINK_VALIDATE"
    if marker in source:
        return source
    anchor = '        buttonView.setText(getString(R.string.ConnectingConnectProxy));'
    start = source.index("            SharedPreferences.Editor editor", source.index(anchor))
    end = source.index("            if (activity instanceof LaunchActivity)", start)
    return source[:start] + f'''            {marker}
            SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
            org.colgram.core.ColgramProxyManager.selectUserProxy(activity,
                    settings.getAddress(), settings.getPort(), settings.getSecret(),
                    ProxySettings.typeToInt(settings.getType()), settings.getUser(), settings.getPassword());
''' + source[end:]


def patch_proxy_startup(source):
    marker = "// COLGRAM_PROXY_STARTUP_VALIDATE"
    if marker in source:
        return source
    start = source.index('        final SharedPreferences preferences', source.index('    public void init(int version,'))
    end = source.index('        String installer = "";', start)
    segment = source[start:end]
    if "ProxySettings.fromSharedPreferences" not in segment or "native_setProxySettings" not in segment:
        raise ValueError("Missing stock startup proxy install block")
    return source[:start] + f'''        {marker}: the route manager revalidates saved endpoints.
''' + source[end:]
