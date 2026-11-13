"""Versioned, idempotent UI transformations for the pinned Telegram onboarding."""
import re


LOGIN_MARKER = "// COLGRAM_LOGIN_NETWORK_MENU = 2"
INTRO_MARKER = "// COLGRAM_INTRO_BRANDING_MENU = 2"
HEADER_MARKER = "// COLGRAM_HEADER_NETWORK_MENU = 2"


CLOUD_TEXTURE = 'loadTexture(unused -> {\n                android.graphics.drawable.Drawable cloud = getParentActivity().getResources().getDrawable(R.drawable.colgram_cloud_logo).mutate();\n                Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);\n                cloud.setBounds(0, 0, 512, 512);\n                cloud.draw(new android.graphics.Canvas(bitmap));\n                return bitmap;\n            }, 21);'


def _migrate_menu_v1(content, marker, button):
    old = marker.replace(" = 2", " = 1")
    if old not in content:
        return None
    content = content.replace(old, marker, 1)
    before = button + ".setOnClickListener(v -> presentFragment(new ColgramSettingsActivity()));"
    after = button + ".setOnClickListener(v -> {\n            org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();\n            presentFragment(new ProxyListActivity());\n        });"
    return _replace_once(content, before, after, "v1 network-menu destination")


def _replace_once(content, before, after, label):
    if before not in content:
        raise ValueError("Missing pinned upstream anchor: " + label)
    return content.replace(before, after, 1)


def _method(content, signature, body):
    # These UI methods have four-space outer indentation in the pinned source;
    # nested closing braces have eight or more. Never consume the next method.
    start = content.find("    " + signature)
    if start < 0:
        raise ValueError("Missing pinned upstream method: " + signature)
    ending = re.search(r"\n    }", content[start:])
    if ending is None:
        raise ValueError("Missing method closing brace: " + signature)
    end = start + ending.end()
    return content[:start] + body + content[end:]


def patch_login_network_menu(content):
    if LOGIN_MARKER in content:
        return content
    migrated = _migrate_menu_v1(content, LOGIN_MARKER, "proxyButtonView")
    if migrated is not None:
        return migrated
    content = _replace_once(content, "proxyButtonView = new ImageView(context);", '''proxyButtonView = new ImageView(context);
        proxyButtonView.setTag("colgram_network_menu");
        proxyButtonView.setContentDescription("Colgram: " + getString(R.string.ProxySettings));''', "login network button")
    pattern = re.compile(r"proxyButtonView\.setOnClickListener\(v -> (?:\{.*?\n\s*}\);|presentFragment\(new ProxyListActivity\(\)\)\);)", re.S)
    click = '''proxyButtonView.setOnClickListener(v -> {
            org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
            presentFragment(new ProxyListActivity());
        });
        proxyButtonView.setOnLongClickListener(v -> {
            org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
            presentFragment(new ProxyListActivity());
            return true;
        });'''
    content, count = pattern.subn(click, content, count=1)
    if count != 1:
        raise ValueError("Missing pinned upstream login proxy click handler")
    content = _replace_once(content, "proxyButtonView.setAlpha(0f);", "proxyButtonView.setAlpha(1f);", "login initial alpha")
    content = _replace_once(content, "proxyButtonView.setVisibility(View.GONE);", "proxyButtonView.setVisibility(View.VISIBLE);", "login initial visibility")
    content = _replace_once(content,
        "sizeNotifierFrameLayout.addView(proxyButtonView, LayoutHelper.createFrame(32, 32, Gravity.RIGHT | Gravity.TOP, 16, 16, 16, 16));",
        "sizeNotifierFrameLayout.addView(proxyButtonView, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.TOP, 16, 8, 8, 8));",
        "login accessible touch target")
    body = '''    private void updateProxyButton(boolean animated, boolean force) {
        // COLGRAM_LOGIN_NETWORK_MENU = 2
        // This is navigation, not a warning shown only while the network fails.
        if (proxyDrawable == null || proxyButtonView == null) {
            return;
        }
        currentConnectionState = getConnectionsManager().getConnectionState();
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        String proxyAddress = preferences.getString("proxy_ip", "");
        final boolean proxyEnabled = (preferences.getBoolean("proxy_enabled", false) && !TextUtils.isEmpty(proxyAddress))
                || org.colgram.core.ColgramProxyManager.hasActiveRouteForUi();
        final boolean connected = currentConnectionState == ConnectionsManager.ConnectionStateConnected
                || currentConnectionState == ConnectionsManager.ConnectionStateUpdating;
        proxyDrawable.setConnected(proxyEnabled, connected, animated);
        if (showProxyButtonDelayed != null) {
            AndroidUtilities.cancelRunOnUIThread(showProxyButtonDelayed);
            showProxyButtonDelayed = null;
        }
        proxyButtonView.animate().cancel();
        proxyButtonVisible = true;
        proxyButtonView.setVisibility(View.VISIBLE);
        proxyButtonView.setAlpha(1f);
    }'''
    return _method(content, "private void updateProxyButton(boolean animated, boolean force) {", body)


def patch_intro_branding_and_menu(content):
    if INTRO_MARKER in content:
        return content
    migrated = _migrate_menu_v1(content, INTRO_MARKER, "colgramNetworkButton")
    if migrated is not None:
        migrated = _replace_once(migrated, "getDrawable(R.drawable.colgram_plane_splash)",
                                 "getDrawable(R.drawable.colgram_cloud_logo)", "v1 intro logo")
        return _replace_once(migrated, "loadTexture(R.drawable.colgram_plane_splash, 21);",
                             CLOUD_TEXTURE, "v1 native cloud texture")
    content = _replace_once(content, "private Drawable logoDrawable;",
        "private Drawable logoDrawable;\n    private android.widget.ImageView colgramNetworkButton;", "intro field")
    content = _replace_once(content, "getDrawable(R.drawable.telegram_logo)",
        "getDrawable(R.drawable.colgram_cloud_logo)", "intro Java logo")
    content = _replace_once(content, "loadTexture(R.drawable.intro_tg_plane, 21);",
        CLOUD_TEXTURE, "intro native GL plane")
    content = _replace_once(content,
        "paint.setColor(ThemeColors.TELEGRAM_COLOR); // It's logo color, it should not be colored by the theme",
        "paint.setColor(android.graphics.Color.TRANSPARENT); // Colgram's red artwork has no Telegram-blue disc",
        "intro native GL disc")
    anchor = "frameContainerView.addView(themeFrameLayout, LayoutHelper.createFrame(64, 64, Gravity.TOP | Gravity.RIGHT, 0, themeMargin, themeMargin, 0));"
    menu = '''

        // COLGRAM_INTRO_BRANDING_MENU = 2
        // Account-free settings stay reachable on every welcome page and theme.
        colgramNetworkButton = new android.widget.ImageView(context);
        colgramNetworkButton.setTag("colgram_network_menu");
        colgramNetworkButton.setImageResource(R.drawable.outline_shield_plain_24);
        colgramNetworkButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        colgramNetworkButton.setContentDescription("Colgram: " + LocaleController.getString(R.string.ProxySettings));
        colgramNetworkButton.setOnClickListener(v -> {
            org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
            presentFragment(new ProxyListActivity());
        });
        colgramNetworkButton.setOnLongClickListener(v -> {
            org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
            presentFragment(new ProxyListActivity());
            return true;
        });
        float colgramNetworkTop = themeMargin + 8;
        if (!AndroidUtilities.isTablet()) {
            colgramNetworkTop += AndroidUtilities.statusBarHeight / AndroidUtilities.density;
        }
        frameContainerView.addView(colgramNetworkButton, LayoutHelper.createFrame(48, 48,
                Gravity.TOP | Gravity.LEFT, 12, colgramNetworkTop, 0, 0));'''
    content = _replace_once(content, anchor, anchor + menu, "intro account-free menu")
    color_anchor = "    private void updateColors(boolean fromTheme) {"
    color_code = '''
        if (colgramNetworkButton != null) {
            colgramNetworkButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            colgramNetworkButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        }
'''
    return _replace_once(content, color_anchor, color_anchor + color_code, "intro theme-aware icon")


def patch_dialogs_network_menu(content):
    if HEADER_MARKER in content:
        return content
    migrated = _migrate_menu_v1(content, HEADER_MARKER, "colgramProxyItem")
    if migrated is not None:
        return migrated
    header = '''            // COLGRAM_HEADER_NETWORK_MENU = 2
            org.telegram.ui.ActionBar.ActionBarMenuItem colgramProxyItem = menu.addItem(2, proxyDrawable);
            colgramProxyItem.setTag("colgram_network_menu");
            colgramProxyItem.setContentDescription("Colgram: " + getString(R.string.ProxySettings));
            colgramProxyItem.setOnClickListener(v -> {
            org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
            presentFragment(new ProxyListActivity());
        });
            colgramProxyItem.setOnLongClickListener(v -> {
                org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
                presentFragment(new ProxyListActivity());
                return true;
            });'''
    if "colgramProxyItem" in content:
        pattern = re.compile(r"            org\.telegram\.ui\.ActionBar\.ActionBarMenuItem colgramProxyItem = menu\.addItem\(2, proxyDrawable\);\n            if \(colgramProxyItem != null\) \{.*?\n            }", re.S)
        content, count = pattern.subn(header, content, count=1)
        if count != 1:
            raise ValueError("Missing legacy header network control block")
        return content
    anchor = "            downloadsItem.setVisibility(View.GONE);"
    return _replace_once(content, anchor, anchor + "\n\n" + header, "dialogs main header")


def patch_proxy_cloud_branding(content):
    marker = "// COLGRAM_PROXY_CLOUD_BRAND = 1"
    if marker in content:
        return content
    anchor = "headerCell.setText(getString(R.string.ProxyConnections));"
    return _replace_once(content, anchor, anchor + '''
                        // COLGRAM_PROXY_CLOUD_BRAND = 1
                        headerCell.getTextView().setCompoundDrawablesWithIntrinsicBounds(R.drawable.colgram_cloud_logo, 0, 0, 0);
                        headerCell.getTextView().setCompoundDrawablePadding(AndroidUtilities.dp(8));
                        headerCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));''', "proxy cloud section")
