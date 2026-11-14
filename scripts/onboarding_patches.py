"""Versioned, idempotent UI transformations for the pinned Telegram onboarding."""
import re


LOGIN_MARKER = "// COLGRAM_LOGIN_NETWORK_MENU = 2"
INTRO_MARKER = "// COLGRAM_INTRO_BRANDING_MENU = 2"
INTRO_ART_MARKER = "// COLGRAM_INTRO_ART = 2"
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


def _intro_block_end(content, start):
    """Find a Java block end without treating braces in literals/comments as code."""
    opening = content.find("{", start)
    if opening < 0:
        raise ValueError("Missing pinned intro block opening")
    depth = 0
    state = "code"
    i = opening
    while i < len(content):
        ch = content[i]
        following = content[i:i + 2]
        if state == "line":
            if ch == "\n":
                state = "code"
        elif state == "block":
            if following == "*/":
                state = "code"
                i += 1
        elif state in ("string", "char"):
            if ch == "\\":
                i += 1
            elif ch == ('"' if state == "string" else "'"):
                state = "code"
        elif following == "//":
            state = "line"
            i += 1
        elif following == "/*":
            state = "block"
            i += 1
        elif ch == '"':
            state = "string"
        elif ch == "'":
            state = "char"
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("Missing pinned intro block closing")


def _replace_intro_block(content, anchor, replacement):
    start = content.find(anchor)
    if start < 0:
        raise ValueError("Missing pinned upstream intro block: " + anchor)
    return content[:start] + replacement + content[_intro_block_end(content, start):]


def patch_intro_branding_and_menu(content):
    content = _patch_intro_network_menu(content)
    if INTRO_ART_MARKER in content:
        return content

    # Both pristine Telegram and the previous two-custom-page build converge.
    # This migration removes the renderer, not just its transparent surface:
    # no EGL thread, native texture allocation or native Intro call remains.
    if "    private Drawable logoDrawable;\n" in content:
        content = content.replace("    private Drawable logoDrawable;\n", "", 1)
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
    if 'LocaleController.getString(R.string.Page6Message).replace("Telegram", "Colgram")' not in content:
        content = _replace_once(content, "LocaleController.getString(R.string.Page6Message)",
            'LocaleController.getString(R.string.Page6Message).replace("Telegram", "Colgram")', "localized cloud slide text")

    surface_start = content.find("        TextureView textureView = new TextureView(context);")
    surface_end = content.find("        viewPager = new ViewPager(context);", surface_start)
    if surface_start < 0 or surface_end < 0:
        raise ValueError("Missing pinned upstream complete intro surface lifecycle")
    content = content[:surface_start] + '''        // COLGRAM_INTRO_ART = 2
        // Six custom Canvas scenes. The first avatar and final circuit cloud are unchanged assets.
        colgramIntroArt = new ColgramIntroArtView(context);
        colgramIntroArt.setTag("colgram_intro_art");
        frameLayout2.addView(colgramIntroArt, LayoutHelper.createFrame(240, 180, Gravity.CENTER));

''' + content[surface_end:]
    content = _replace_once(content, "    private CharSequence[] titles;",
        "    private ColgramIntroArtView colgramIntroArt;\n    private CharSequence[] titles;", "intro art field")
    content = _replace_intro_block(content,
        "            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {",
        '''            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                bottomPages.setPageOffset(position, positionOffset);
                colgramIntroArt.setPageProgress(position + positionOffset);
            }''')
    content = _replace_once(content, "                currentViewPagerPage = i;",
        "                currentViewPagerPage = i;\n                colgramIntroArt.setSelectedPage(i);", "intro selected page")
    scroll_state = "            public void onPageScrollStateChanged(int i) {"
    content = _replace_once(content, scroll_state, scroll_state +
        "\n                colgramIntroArt.setScrolling(i != ViewPager.SCROLL_STATE_IDLE);", "intro animation drag state")

    content = _replace_intro_block(content, "    public class EGLThread extends DispatchQueue {", "")
    if "    private int getNativeIntroPage() {" in content:
        content = _replace_intro_block(content, "    private int getNativeIntroPage() {", "")
    for field in ("    private EGLThread eglThread;\n", "    private long currentDate;\n"):
        content = content.replace(field, "", 1)
    if "            if (eglThread != null) {" in content:
        content = _replace_intro_block(content, "            if (eglThread != null) {", "")
    content = content.replace("} else Intro.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));", "}", 1)
    content = _replace_once(content, "    private void updateColors(boolean fromTheme) {",
        "    private void updateColors(boolean fromTheme) {\n        if (colgramIntroArt != null) {\n            colgramIntroArt.invalidate();\n        }", "intro live theme invalidation")

    # A ScrollView owns the page: a compact landscape viewport scrolls the full
    # composition instead of overlapping artwork, paragraphs, dots and buttons.
    content = content.replace("ICON_WIDTH_DP = 200, ICON_HEIGHT_DP = 150", "ICON_WIDTH_DP = 240, ICON_HEIGHT_DP = 180", 1)
    scroll_container = "        scrollView.addView(frameContainerView, LayoutHelper.createScroll("
    if scroll_container in content:
        content = _replace_once(content, scroll_container,
            "        frameContainerView.setMinimumHeight(dp(580) + AndroidUtilities.statusBarHeight);\n" + scroll_container,
            "intro landscape content minimum")
    content = content.replace("        AndroidUtilities.lockOrientation(getParentActivity(), ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);\n", "", 1)
    if "        super.onResume();" in content:
        content = content.replace("        super.onResume();",
            "        super.onResume();\n        if (colgramIntroArt != null) {\n            colgramIntroArt.setMotionEnabled(true);\n        }", 1)
    if "        super.onPause();" in content:
        content = content.replace("        super.onPause();",
            "        super.onPause();\n        if (colgramIntroArt != null) {\n            colgramIntroArt.setMotionEnabled(false);\n        }", 1)
    for name in ("android.text.SpannableStringBuilder", "android.text.Spanned", "android.text.style.ImageSpan",
                 "org.telegram.ui.ActionBar.ThemeColors", "android.graphics.Bitmap", "android.graphics.Paint",
                 "android.graphics.PorterDuffXfermode", "android.graphics.SurfaceTexture",
                 "android.graphics.drawable.BitmapDrawable", "android.opengl.GLES20", "android.opengl.GLUtils",
                 "android.os.Looper", "android.view.Display", "android.view.TextureView", "android.view.WindowManager",
                 "org.telegram.messenger.BuildVars", "org.telegram.messenger.DispatchQueue", "org.telegram.messenger.EmuDetector",
                 "org.telegram.messenger.FileLog", "org.telegram.messenger.GenericProvider", "org.telegram.messenger.Intro",
                 "javax.microedition.khronos.egl.EGL10", "javax.microedition.khronos.egl.EGLConfig",
                 "javax.microedition.khronos.egl.EGLContext", "javax.microedition.khronos.egl.EGLDisplay",
                 "javax.microedition.khronos.egl.EGLSurface", "javax.microedition.khronos.opengles.GL10"):
        content = content.replace("import " + name + ";\n", "", 1)
    if "Intro." in content or "eglThread" in content or "TextureView" in content:
        raise ValueError("Pinned intro migration left an unused native renderer reference")
    return content


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
