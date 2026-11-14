"""Versioned, idempotent UI transformations for the pinned Telegram onboarding."""
import re


LOGIN_MARKER = "// COLGRAM_LOGIN_NETWORK_MENU = 2"
INTRO_MARKER = "// COLGRAM_INTRO_BRANDING_MENU = 2"
INTRO_ART_MARKER = "// COLGRAM_INTRO_ART = 1"
HEADER_MARKER = "// COLGRAM_HEADER_NETWORK_MENU = 2"


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


def _patch_intro_network_menu(content):
    if INTRO_MARKER in content:
        return content
    migrated = _migrate_menu_v1(content, INTRO_MARKER, "colgramNetworkButton")
    if migrated is not None:
        return migrated
    content = _replace_once(content, "private Drawable logoDrawable;",
        "private Drawable logoDrawable;\n    private android.widget.ImageView colgramNetworkButton;", "intro field")
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


def patch_intro_branding_and_menu(content):
    content = _patch_intro_network_menu(content)
    if INTRO_ART_MARKER in content:
        return content

    # The title has a text-only version in older Colgram-patched checkouts.
    # Remove the obsolete Drawable/ImageSpan code from either starting point.
    content = _replace_once(content, "    private Drawable logoDrawable;\n", "", "intro title drawable field")
    title = re.compile(
        r"        logoDrawable = .*?;\n"
        r"        logoDrawable\.setBounds\(.*?\);\n"
        r"        SpannableStringBuilder ssb = .*?;\n"
        r"(?:        ssb\.setSpan\(.*?\);\n)?"
        r'        titles\[0\] = (?:ssb|"Colgram");'
    )
    content, count = title.subn('        titles[0] = "Colgram";', content, count=1)
    if count != 1:
        raise ValueError("Missing pinned upstream intro title")
    content = _replace_once(content,
        "        logoDrawable.setColorFilter(Theme.multAlpha(getThemedColor(Theme.key_actionBarDefaultTitle), 0.9f), PorterDuff.Mode.MULTIPLY);\n",
        "", "intro title drawable tint")
    for name in ("android.text.SpannableStringBuilder", "android.text.Spanned", "android.text.style.ImageSpan",
                 "org.telegram.ui.ActionBar.ThemeColors"):
        content = content.replace("import " + name + ";\n", "", 1)
    content = _replace_once(content, "LocaleController.getString(R.string.Page6Message)",
        'LocaleController.getString(R.string.Page6Message).replace("Telegram", "Colgram")', "localized cloud slide text")

    surface = "        frameLayout2.addView(textureView, LayoutHelper.createFrame(ICON_WIDTH_DP, ICON_HEIGHT_DP, Gravity.CENTER));"
    art = '''

        // COLGRAM_INTRO_ART = 1
        // These two pages use Android drawables; the middle four keep the native animations.
        final android.widget.ImageView colgramWelcomeAvatar = new android.widget.ImageView(context);
        colgramWelcomeAvatar.setImageResource(R.drawable.colgram_app_avatar);
        GradientDrawable avatarOutline = new GradientDrawable();
        avatarOutline.setColor(Color.BLACK);
        avatarOutline.setCornerRadius(dp(28));
        colgramWelcomeAvatar.setBackground(avatarOutline);
        colgramWelcomeAvatar.setClipToOutline(true);
        colgramWelcomeAvatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frameLayout2.addView(colgramWelcomeAvatar, LayoutHelper.createFrame(128, 128, Gravity.CENTER));

        final android.widget.ImageView colgramCloudArt = new android.widget.ImageView(context);
        colgramCloudArt.setImageResource(R.drawable.colgram_intro_cloud);
        colgramCloudArt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frameLayout2.addView(colgramCloudArt, LayoutHelper.createFrame(180, 126, Gravity.CENTER));
        colgramCloudArt.setAlpha(0f);
        textureView.setAlpha(0f);
'''
    content = _replace_once(content, surface, surface + art, "intro artwork frame")
    scroll = "                bottomPages.setPageOffset(position, positionOffset);"
    content = _replace_once(content, scroll, scroll + '''
                int cloudPage = titles.length - 1;
                float avatarAlpha = position == 0 ? 1f - positionOffset : 0f;
                float cloudAlpha = position == cloudPage ? 1f : position == cloudPage - 1 ? positionOffset : 0f;
                colgramWelcomeAvatar.setAlpha(avatarAlpha);
                colgramCloudArt.setAlpha(cloudAlpha);
                textureView.setAlpha(1f - avatarAlpha - cloudAlpha);''', "intro pager artwork progress")

    page = "Intro.setPage(currentViewPagerPage);"
    if content.count(page) != 2:
        raise ValueError("Missing pinned native intro page updates")
    content = content.replace(page, "Intro.setPage(getNativeIntroPage());")
    content = content.replace("private int currentViewPagerPage;", "private volatile int currentViewPagerPage;", 1)
    adapter = "    private class IntroAdapter extends PagerAdapter {"
    content = _replace_once(content, adapter, '''    private int getNativeIntroPage() {
        // Never expose the stock cloud or logo during a swipe. Keep EGL attached and drawing.
        return Math.max(1, Math.min(currentViewPagerPage, titles.length - 2));
    }

''' + adapter, "intro pager adapter")

    # The bundled native library still takes its fixed set of texture slots.
    # Bind blank logo textures for the native speed page's initial transition.
    private_texture = "loadTexture(R.drawable.intro_private_screw, 20);"
    mask_texture = "            loadTexture(telegramMaskProvider, 23);"
    start = content.find(private_texture)
    end = content.find(mask_texture, start)
    if start < 0 or end < 0 or "}, 22);" not in content[start:end]:
        raise ValueError("Missing pinned upstream intro GL logo textures")
    start += len(private_texture)
    return content[:start] + '''
            loadTexture(unused -> Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 21);
            loadTexture(unused -> Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 22);
''' + content[end:]


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
