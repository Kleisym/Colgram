#!/usr/bin/env python3
"""Patch the shield's Connection Settings: proxies, WARP, local route and VPN subscriptions.

Idempotent, marker-based. Called from apply-patches.py and runnable standalone.
"""
import io
import os
import re
from pathlib import Path

MARKER = "REQ_WARP_CONSENT = 9182"
PATCH_VERSION = "COLGRAM_WARP_UI_PATCH = 5"


def upgrade_generated(s):
    """Apply the current state/route fixes to both fresh and already-patched trees."""
    if PATCH_VERSION in s:
        return s
    replacements = (
        ('if (context == null || getParentActivity() == null) return;',
         'if (context == null || getParentActivity() == null) {\n'
         '            org.colgram.core.ColgramConfig.setWarpEnabled(false);\n'
         '            refreshWarpState();\n            return;\n        }'),
        ('android.widget.Toast.makeText(context, "WireGuard-бэкенд недоступен", android.widget.Toast.LENGTH_LONG).show();\n            return;',
         'android.widget.Toast.makeText(context, "WireGuard-бэкенд недоступен", android.widget.Toast.LENGTH_LONG).show();\n'
         '            org.colgram.core.ColgramConfig.setWarpEnabled(false);\n'
         '            refreshWarpState();\n            return;'),
        ('if (getParentActivity() == null) return;',
         'if (getParentActivity() == null) {\n'
         '            org.colgram.core.ColgramConfig.setWarpEnabled(false);\n'
         '            refreshWarpState();\n            return;\n        }'),
        ('AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(\n'
         '                            context, "WARP: регистрация не удалась — " + reason,\n'
         '                            android.widget.Toast.LENGTH_LONG).show());',
         'failWarpStart(context, "WARP: регистрация не удалась — " + reason);'),
        ('android.widget.Toast.makeText(getContext(), "WARP: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show();',
         'failWarpStart(getContext(), "WARP: " + t.getMessage());'),
        ('org.colgram.core.ColgramWarpTunnel.bringUp(context);\n            } catch (Throwable t) {',
         'if (!org.colgram.core.ColgramConfig.isWarpEnabled()) return;\n'
         '                org.colgram.core.ColgramWarpTunnel.bringUp(context);\n'
         '                if (!org.colgram.core.ColgramConfig.isWarpEnabled()) {\n'
         '                    org.colgram.core.ColgramWarpTunnel.bringDown(context);\n'
         '                    return;\n'
         '                }\n'
         '                org.colgram.core.ColgramConfig.setWarpEnabled(true);\n'
         '                refreshWarpState();\n            } catch (Throwable t) {'),
        ('org.colgram.core.ColgramConfig.setWarpEnabled(false);\n'
         '                AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(\n'
         '                        context, "WARP не поднялся — " + reason, android.widget.Toast.LENGTH_LONG).show());',
         'org.colgram.core.ColgramConfig.setWarpEnabled(false);\n'
         '                org.colgram.core.ColgramWarpTunnel.bringDown(context);\n'
         '                failWarpStart(context, "WARP не поднялся — " + reason);'),
        ('if (requestCode == REQ_WARP_CONSENT && resultCode == android.app.Activity.RESULT_OK) {\n'
         '            bringWarpUp();\n        } else {',
         'if (requestCode == REQ_WARP_CONSENT && resultCode == android.app.Activity.RESULT_OK) {\n'
         '            bringWarpUp();\n'
         '        } else if (requestCode == REQ_WARP_CONSENT) {\n'
         '            org.colgram.core.ColgramConfig.setWarpEnabled(false);\n'
         '            org.colgram.core.ColgramWarpTunnel.bringDown(getContext());\n'
         '            refreshWarpState();\n        } else {'),
        ('useProxyForCalls = preferences.getBoolean("proxy_enabled_calls", false);',
         'useProxyForCalls = preferences.getBoolean("proxy_enabled_calls", true);'),
        ('useProxyForCalls = prefs.getBoolean("proxy_enabled_calls", false);',
         'useProxyForCalls = prefs.getBoolean("proxy_enabled_calls", true);'),
        ('                    useProxyForCalls = false;\n                }\n                if (useProxySettings) {',
         '                }\n                if (useProxySettings) {'),
        ('useProxySettings = false;\n                        useProxyForCalls = false;\n'
         '                        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();\n'
         '                        editor.putBoolean("proxy_enabled", false);\n'
         '                        editor.putBoolean("proxy_enabled_calls", false);',
         'useProxySettings = false;\n'
         '                        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();\n'
         '                        editor.putBoolean("proxy_enabled", false);'),
        ('org.colgram.core.ColgramWarpTunnel.isUp();\n                        boolean isRu',
         'org.colgram.core.ColgramConfig.isWarpEnabled();\n                        boolean isRu'),
        ('                            warpState = isRu ? "включен — обычный прокси выключен" : "on — regular proxy off";',
         '                            boolean warpConnected = org.colgram.core.ColgramWarpTunnel.isConnected();\n'
         '                            warpState = isRu\n'
         '                                    ? (warpConnected ? "подключен — обычный прокси выключен" : "подключается — обычный прокси выключен")\n'
         '                                    : (warpConnected ? "connected — regular proxy off" : "connecting — regular proxy off");'),
        ('warpState, org.colgram.core.ColgramWarpTunnel.isUp(), false, false)',
        # The row must show the pending start as well as the persisted flag, or the switch reads
        # "off" under the user's finger while the tunnel is actually coming up.
        'warpState, warpUp, false, false)'),
        ('checkCell.setChecked(org.colgram.core.ColgramWarpTunnel.isUp());',
         'checkCell.setChecked(org.colgram.core.ColgramConfig.isWarpEnabled());'),
        ('boolean wantWarp = !org.colgram.core.ColgramWarpTunnel.isUp();',
         # A start in flight is NOT reflected in the persisted flag - that is written only once
         # the tunnel is really up. Deriving the tap's intent from the flag alone therefore made
         # a second tap ask for WARP again instead of cancelling the start already running, and
         # the first start would then switch WARP back on after the user had turned it off.
         'boolean wantWarp = !org.colgram.core.ColgramConfig.isWarpEnabled() && !warpStartPending;'),
        ('if (useProxySettings) {\n                    // One default route at a time: the regular proxy replacing WARP.\n'
         '                    org.colgram.core.ColgramWarpTunnel.bringDown(getContext());',
         'if (useProxySettings) {\n                    // One default route at a time: the regular proxy replacing WARP.\n'
         '                    org.colgram.core.ColgramConfig.setWarpEnabled(false);\n'
         '                    org.colgram.core.ColgramWarpTunnel.bringDown(getContext());'),
        ('org.colgram.core.ColgramWarpTunnel.bringDown(getContext());\n'
         '                for (int a = proxyStartRow;',
         'org.colgram.core.ColgramConfig.setWarpEnabled(false);\n'
         '                org.colgram.core.ColgramWarpTunnel.bringDown(getContext());\n'
         '                for (int a = proxyStartRow;'),
    )
    for old, new in replacements:
        s = s.replace(old, new)
    # Selecting, removing, or disabling a proxy must not change the independent
    # default-on preference for routing calls through a configured proxy.
    s = re.sub(r'^[ \t]*useProxyForCalls = false;\s*\r?\n', '', s, flags=re.MULTILINE)
    s = re.sub(r'^[ \t]*editor\.putBoolean\("proxy_enabled_calls", false\);\s*\r?\n', '', s, flags=re.MULTILINE)
    s = re.sub(r'^[ \t]*if \(!info\.settings\.getSecret\(\)\.isEmpty\(\)\) \{\s*^[ \t]*\}\s*\r?\n', '', s, flags=re.MULTILINE)
    s = re.sub(r'(?m)^(?P<indent>[ \t]*)org\.colgram\.core\.ColgramConfig\.setWarpEnabled\(false\);\s*\r?\n(?:[ \t]*org\.colgram\.core\.ColgramConfig\.setWarpEnabled\(false\);\s*\r?\n)+',
               r'\g<indent>org.colgram.core.ColgramConfig.setWarpEnabled(false);\n', s)
    s = s.replace('                        textCheckCell.setChecked(false);\n'
                  '                    }\n                }\n                if (useProxySettings) {',
                  '                        textCheckCell.setChecked(useProxyForCalls);\n'
                  '                    }\n                }\n                if (useProxySettings) {')
    s = s.replace('org.colgram.core.ColgramWarpTunnel.bringDown(context);\n'
                  '                failWarpStart(context, "WARP не поднялся — " + reason);',
                  'failWarpStart(context, "WARP не поднялся — " + reason);')

    # A successful asynchronous start and every failure/cancel path must rebind the visible row.
    anchor = '    @Override\n    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {'
    if 'private void refreshWarpState()' not in s and anchor in s:
        helpers = '''    private void failWarpStart(android.content.Context context, String message) {
        org.colgram.core.ColgramConfig.setWarpEnabled(false);
        if (context != null) org.colgram.core.ColgramWarpTunnel.bringDown(context);
        AndroidUtilities.runOnUIThread(() -> {
            if (context != null) android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show();
            refreshWarpState();
        });
    }

    private void refreshWarpState() {
        AndroidUtilities.runOnUIThread(() -> {
            if (listAdapter != null) updateRows(true);
            org.colgram.core.ColgramProxyManager.notifyProxySettingsChanged();
        });
    }

'''
        s = s.replace(anchor, helpers + anchor, 1)

    marker_line = '    private static final int REQ_WARP_CONSENT = 9182;'
    if marker_line in s:
        s = re.sub(
            r'^\s*private static final int COLGRAM_WARP_UI_PATCH\s*=\s*\d+;\s*\r?\n',
            '', s, flags=re.MULTILINE)
        s = s.replace(marker_line, marker_line + '\n    private static final int ' + PATCH_VERSION + ';', 1)
    return upgrade_functional_controls(s)


def replace_section(source, start, end, replacement):
    begin = source.index(start)
    finish = source.index(end, begin + len(start))
    return source[:begin] + replacement + source[finish:]


def add_connection_source_details(s):
    """Make the catalog summary open a read-only list of per-feed verdicts."""
    marker = "                colgramShowSourceDiagnostics();"
    click = "            } else if (position == colgramDpiRow) {\n                colgramToggleDpi();"
    enabled = "            return position == colgramDpiRow || position == colgramSubscriptionImportRow"
    if marker not in s:
        for anchor in (click, enabled):
            if s.count(anchor) != 1:
                raise ValueError("Connection source details anchor is missing or ambiguous")
        s = s.replace(click, "            } else if (position == colgramBackgroundProxyRow) {\n"
                      + marker + "\n" + click, 1)
        s = s.replace(enabled, enabled.replace("return position", "return position == colgramBackgroundProxyRow || position"), 1)
    return s.replace("cell.setText(org.colgram.core.ColgramProxyManager.getBackgroundProxyStatus());",
                     'cell.setText(org.colgram.core.ColgramProxyManager.getBackgroundProxyStatus() + " · "'
                     '\n                                + ColgramSubscriptionControls.label("Подробнее", "Details"));', 1)


def add_connection_settings(s):
    """Add the route-specific rows without forking the Telegram proxy editor."""
    marker = "// COLGRAM_CONNECTION_SETTINGS = 1"
    if marker in s:
        return bind_source_details_click(make_local_base_read_only(add_connection_source_details(s)))

    def replace(old, new):
        nonlocal s
        if s.count(old) != 1:
            raise ValueError("Connection Settings anchor is missing or ambiguous: " + old.splitlines()[0])
        s = s.replace(old, new, 1)

    replace("        actionBar.setTitle(getString(R.string.ProxySettings));",
            f"        {marker}\n        actionBar.setTitle(ColgramSubscriptionControls.label(\"Настройки подключения\", \"Connection Settings\"));")
    for old in ('''        final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        useProxySettings = (preferences.getBoolean("proxy_enabled", false) && !SharedConfig.proxyList.isEmpty())
                || org.colgram.core.ColgramProxyManager.hasActiveRouteForUi();''', '''        final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        useProxySettings = preferences.getBoolean("proxy_enabled", false) && !SharedConfig.proxyList.isEmpty();'''):
        if old in s:
            replace(old, "        useProxySettings = colgramRemoteProxyEnabled();")
            break
    else:
        raise ValueError("Connection Settings remote proxy initialization anchor missing")
    replace("        if (waitingWarpConsent >= 0 && warpRequested(waitingWarpConsent)) cancelWarpFromProxyScreen();",
            "        colgramSubscription.abandonConsent();\n        if (waitingWarpConsent >= 0 && warpRequested(waitingWarpConsent)) cancelWarpFromProxyScreen();")
    replace('''                if (useProxySettings || org.colgram.core.ColgramProxyManager.isProxySelectionPending()
                        || org.colgram.core.ColgramProxyManager.hasActiveRouteForUi()) {
                    org.colgram.core.ColgramProxyManager.disableProxy(getContext());''', '''                if (colgramRemoteProxyEnabled() || org.colgram.core.ColgramProxyManager.isProxySelectionPending()) {''')
    replace('''                    cancelWarpFromProxyScreen();
                    org.colgram.core.ColgramProxyManager.selectPublicProxy(getContext());''', '''                    colgramSubscription.stopForAlternativeRoute(getContext());
                    cancelWarpFromProxyScreen();
                    org.colgram.core.ColgramProxyManager.selectPublicProxy(getContext());''')
    replace('''                SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                cancelWarpFromProxyScreen();''', '''                SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                colgramSubscription.stopForAlternativeRoute(getContext());
                cancelWarpFromProxyScreen();''')
    replace('''            } else if (position == proxyAddRow) {
                presentFragment(new ProxySettingsActivity());''', '''            } else if (position == colgramDpiRow) {
                colgramToggleDpi();
            } else if (position == colgramSubscriptionImportRow) {
                colgramSubscription.importLink();
            } else if (position == colgramSubscriptionNodeRow) {
                colgramSubscription.showNodes();
            } else if (position == colgramSubscriptionToggleRow) {
                colgramSubscription.toggle();
            } else if (position == colgramSubscriptionRemoveRow) {
                colgramSubscription.confirmRemove();
            } else if (position == proxyAddRow) {
                presentFragment(new ProxySettingsActivity());''')
    replace("        useProxyRow = rowCount++;", "        useProxyRow = rowCount++;\n        colgramBackgroundProxyRow = rowCount++;")
    replace('''        warpDetailRow = rowCount++;
        connectionsHeaderRow = rowCount++;''', '''        warpDetailRow = rowCount++;
        colgramDpiRow = rowCount++;
        colgramSubscriptionImportRow = rowCount++;
        colgramSubscriptionNodeRow = rowCount++;
        colgramSubscriptionToggleRow = rowCount++;
        colgramSubscriptionRemoveRow = rowCount++;
        connectionsHeaderRow = rowCount++;''')
    replace('''                    if (position == callsRow) {
                        textCell.setTextAndValue''', '''                    if (position == colgramSubscriptionImportRow) {
                        textCell.setText(ColgramSubscriptionControls.label("Импорт VPN-подписки или ключа", "Import VPN subscription or key"), true);
                    } else if (position == colgramSubscriptionNodeRow) {
                        textCell.setTextAndValue(ColgramSubscriptionControls.label("Узел VPN / страна", "VPN node / country"),
                                colgramSubscription.nodeLabel(), true);
                    } else if (position == colgramSubscriptionToggleRow) {
                        textCell.setTextAndValue(colgramSubscription.toggleLabel(), colgramSubscription.statusLine(), true);
                    } else if (position == colgramSubscriptionRemoveRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                        textCell.setText(ColgramSubscriptionControls.label("Удалить подписку", "Remove subscription"), true);
                    } else if (position == callsRow) {
                        textCell.setTextAndValue''')
    replace('''                    } else if (position == warpRow) {
                        checkCell.setTextAndValueAndCheck''', '''                    } else if (position == colgramDpiRow) {
                        checkCell.setTextAndValueAndCheck(ColgramSubscriptionControls.label("Локальный обход", "Local bypass"),
                                colgramDpiStatus(), org.colgram.core.ColgramConfig.isDpiBypassEnabled(), true, true);
                    } else if (position == warpRow) {
                        checkCell.setTextAndValueAndCheck''')
    replace('''                    if (position == callsDetailRow) {
                        cell.setText''', '''                    if (position == colgramBackgroundProxyRow) {
                        cell.setText(org.colgram.core.ColgramProxyManager.getBackgroundProxyStatus());
                    } else if (position == callsDetailRow) {
                        cell.setText''')
    for index in ("proxyIndex", "a"):
        anchor = f"            final SharedConfig.ProxyInfo proxyInfo = proxyList.get({index});\n            if (proxyInfo.checking"
        if anchor in s:
            replace(anchor, anchor.replace("            if (proxyInfo.checking",
                    "            if (org.colgram.core.ColgramProxyManager.usesCatalogVerifier(proxyInfo)) continue;\n"
                    "            if (proxyInfo.checking"))
            break
    else:
        raise ValueError("Connection Settings native checker anchor missing")
    replace('''            return position == useProxyRow ||''', '''            return position == colgramDpiRow || position == colgramSubscriptionImportRow
                    || position == colgramSubscriptionNodeRow || position == colgramSubscriptionToggleRow
                    || position == colgramSubscriptionRemoveRow
                    || position == useProxyRow ||''')
    replace('''            } else if (position == callsDetailRow) {
                return -15;''', '''            } else if (position == callsDetailRow) {
                return -15;
            } else if (position == colgramBackgroundProxyRow) {
                return -16;
            } else if (position == colgramDpiRow) {
                return -17;
            } else if (position == colgramSubscriptionImportRow) {
                return -18;
            } else if (position == colgramSubscriptionNodeRow) {
                return -19;
            } else if (position == colgramSubscriptionToggleRow) {
                return -20;
            } else if (position == colgramSubscriptionRemoveRow) {
                return -21;''')
    replace('''            } else if (position == callsRow || position == proxyAddRow || position == deleteAllRow) {
                return VIEW_TYPE_TEXT_SETTING;
            } else if (position == useProxyRow || position == rotationRow || position == warpRow) {''', '''            } else if (position == callsRow || position == proxyAddRow || position == deleteAllRow
                    || position == colgramSubscriptionImportRow || position == colgramSubscriptionNodeRow
                    || position == colgramSubscriptionToggleRow || position == colgramSubscriptionRemoveRow) {
                return VIEW_TYPE_TEXT_SETTING;
            } else if (position == useProxyRow || position == rotationRow || position == warpRow || position == colgramDpiRow) {''')
    return bind_source_details_click(make_local_base_read_only(add_connection_source_details(s)))


def bind_source_details_click(s):
    """Own summary-row touches instead of relying on a links/info cell's selection."""
    if "// COLGRAM_SOURCE_DETAILS_TOUCH = 1" in s:
        return s.replace("cell.getTextView().setClickable(false);",
                         "cell.getTextView().setClickable(position != colgramBackgroundProxyRow);")
    anchor = "                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;"
    if s.count(anchor) != 1:
        raise ValueError("Source details touch anchor missing or ambiguous")
    return s.replace(anchor, anchor + '''
                    // COLGRAM_SOURCE_DETAILS_TOUCH = 1
                    cell.setOnClickListener(position == colgramBackgroundProxyRow
                            ? v -> colgramShowSourceDiagnostics() : null);
                    cell.setClickable(position == colgramBackgroundProxyRow);
                    cell.getTextView().setMovementMethod(position == colgramBackgroundProxyRow
                            ? null : android.text.method.LinkMovementMethod.getInstance());
                    cell.getTextView().setClickable(position != colgramBackgroundProxyRow);
                    cell.getTextView().setLongClickable(false);''', 1)


def make_local_base_read_only(s):
    """Migrate the old checked base row to non-interactive, live status text."""
    if "// COLGRAM_LOCAL_BASE_READ_ONLY = 1" in s:
        return s
    changes = (
        ("            } else if (position == colgramDpiRow) {\n                colgramToggleDpi();\n", ""),
        (" || position == colgramDpiRow", ""),
        ('''                    } else if (position == colgramDpiRow) {
                        checkCell.setTextAndValueAndCheck(ColgramSubscriptionControls.label("Локальный обход", "Local bypass"),
                                colgramDpiStatus(), org.colgram.core.ColgramConfig.isDpiBypassEnabled(), true, true);
''', ""),
        ('''                    if (position == colgramBackgroundProxyRow) {
                        cell.setText''', '''                    // COLGRAM_LOCAL_BASE_READ_ONLY = 1
                    if (position == colgramDpiRow) {
                        cell.setText(ColgramSubscriptionControls.label("Локальный слой всегда включён", "Local base is always on")
                                + "\\n" + colgramDpiStatus());
                    } else if (position == colgramBackgroundProxyRow) {
                        cell.setText'''),
    )
    for old, new in changes:
        if old not in s:
            raise ValueError("Local base read-only migration anchor missing: " + old.splitlines()[0])
        s = s.replace(old, new)
    return s


def copy_subscription_controls(proxy_source):
    for name in ("ColgramSubscriptionControls.java", "ColgramBrowserRoutes.java"):
        target = Path(proxy_source).with_name(name)
        template = Path(__file__).parent / ("templates/" + name)
        if not template.is_file():
            continue
        contents = template.read_text(encoding="utf-8")
        if not target.is_file() or target.read_text(encoding="utf-8") != contents:
            target.write_text(contents, encoding="utf-8", newline="\n")


def upgrade_functional_controls(s):
    """Canonical v5 controls, applied after the legacy v1-v4 structure has converged."""
    template = (Path(__file__).parent / "templates/ProxyListWarpControls.java.inc").read_text(encoding="utf-8")
    s = replace_section(s, "    // ---- Colgram WARP helpers", "    private int connectionsHeaderRow;", template + "\n")
    s = s.replace("        warpRow = rowCount++;", "        callsRow = rowCount++;\n        callsDetailRow = rowCount++;\n        warpRow = rowCount++;", 1)
    s = s.replace("        useProxyRow = rowCount++;", "        networkModesRow = rowCount++;\n        useProxyRow = rowCount++;", 1)
    s = s.replace("private final static boolean IS_PROXY_ROTATION_AVAILABLE = true;",
                  "private final static boolean IS_PROXY_ROTATION_AVAILABLE = false;", 1)
    s = s.replace("            proxyList.addAll(SharedConfig.proxyList);", '''            for (SharedConfig.ProxyInfo item : SharedConfig.proxyList) {
                if (org.colgram.core.ColgramProxyManager.isProxyCatalogVisible(item)) proxyList.add(item);
            }''', 1)

    # Validation owns route installation. No stock JNI call or currentProxy assignment occurs first.
    s = replace_section(s, "            if (position == useProxyRow) {", "            } else if (position == rotationRow) {", '''            if (position == useProxyRow) {
                if (useProxySettings || org.colgram.core.ColgramProxyManager.isProxySelectionPending()
                        || org.colgram.core.ColgramProxyManager.hasActiveRouteForUi()) {
                    org.colgram.core.ColgramProxyManager.disableProxy(getContext());
                    org.colgram.core.ColgramProxyManager.onStockProxyToggle(false);
                    useProxySettings = false;
                } else {
                    cancelWarpFromProxyScreen();
                    org.colgram.core.ColgramProxyManager.selectPublicProxy(getContext());
                }
                updateRows(true);
''')
    s = replace_section(s, "            } else if (position >= proxyStartRow && position < proxyEndRow) {", "            } else if (position == warpRow) {", '''            } else if (position >= proxyStartRow && position < proxyEndRow) {
                if (!selectedItems.isEmpty()) {
                    listAdapter.toggleSelected(position);
                    return;
                }
                SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                cancelWarpFromProxyScreen();
                ProxySettings settings = info.settings;
                org.colgram.core.ColgramProxyManager.selectUserProxy(getContext(),
                        settings.getAddress(), settings.getPort(), settings.getSecret(),
                        ProxySettings.typeToInt(settings.getType()), settings.getUser(), settings.getPassword());
                updateRows(true);
''')
    s = replace_section(s, "            } else if (position == warpRow) {", "            } else if (position == proxyAddRow) {", '''            } else if (position == callsRow) {
                boolean enabled = !callsPreference();
                MessagesController.getGlobalMainSettings().edit().putBoolean("proxy_enabled_calls", enabled).apply();
                ((TextCheckCell) view).setChecked(enabled);
                listAdapter.notifyItemChanged(callsDetailRow);
            } else if (position == warpRow) {
                if (org.colgram.core.ColgramConfig.isWarpEnabled() || warpOperationsGate.isPending())
                    cancelWarpFromProxyScreen();
                else startWarpFromProxyScreen();
''')
    s = s.replace("            } else if (position == proxyAddRow) {\n                presentFragment", '''            } else if (position == networkModesRow) {
                presentFragment(new ColgramSettingsActivity());
            } else if (position == proxyAddRow) {
                presentFragment''', 1)
    s = s.replace("position == rotationRow || position == warpRow", "position == rotationRow || position == warpRow || position == callsRow")
    s = s.replace("return position == useProxyRow ||", "return position == networkModesRow || position == useProxyRow ||", 1)
    s = s.replace("} else if (position == proxyAddRow || position == deleteAllRow)", "} else if (position == networkModesRow || position == proxyAddRow || position == deleteAllRow)", 1)
    s = s.replace("                    if (position == proxyAddRow) {\n                        textCell.setText", '''                    if (position == networkModesRow) {
                        textCell.setText("Режим подключения · Direct / DPI / VPN", true);
                    } else if (position == proxyAddRow) {
                        textCell.setText''', 1)
    s = s.replace("            } else if (position == useProxyRow) {\n                return -4;", '''            } else if (position == networkModesRow) {
                return -16;
            } else if (position == useProxyRow) {
                return -4;''', 1)
    s = s.replace("        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);",
                  "        org.colgram.core.ColgramProxyManager.onStockProxyCheckResult(proxyInfo, time);\n"
                  "        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);", 1)
    s = s.replace("checkCell.setTextAndCheck(getString(R.string.UseProxySettings), useProxySettings, rotationRow != -1);", '''checkCell.setTextAndValueAndCheck(getString(R.string.UseProxySettings),
                                org.colgram.core.ColgramProxyManager.getProxySelectionStatus(),
                                useProxySettings || org.colgram.core.ColgramProxyManager.isProxySelectionPending(), true, false);''')
    start = "                    } else if (position == warpRow) {"
    end = "                    } else if (position == rotationRow) {"
    s = replace_section(s, start, end, '''                    } else if (position == callsRow) {
                        checkCell.setTextAndCheck("Использовать для звонков", callsPreference(), true);
                    } else if (position == warpRow) {
                        checkCell.setTextAndValueAndCheck("Использовать Cloudflare WARP", warpStatusForProxyScreen(),
                                org.colgram.core.ColgramConfig.isWarpEnabled() || warpStartPending, true, false);
''')
    s = s.replace("                } else if (position == warpRow) {\n                    checkCell.setChecked", "                } else if (position == callsRow) {\n                    checkCell.setChecked(callsPreference());\n                } else if (position == warpRow) {\n                    checkCell.setChecked")
    s = s.replace("checkCell.setChecked(useProxySettings);", "checkCell.setChecked(useProxySettings || org.colgram.core.ColgramProxyManager.isProxySelectionPending());")
    s = s.replace("                    if (position == warpDetailRow) {", '''                    if (position == callsDetailRow) {
                        cell.setText("Для новых личных звонков через выбранный SOCKS5. MTProto / WebSocket не передают звонки. "
                                + "Локальный DPI-обходчик не является SOCKS5-прокси для звонков. "
                                + "WARP / VPN направляют звонки на уровне системы, независимо от этого переключателя.");
                    } else if (position == warpDetailRow) {''', 1)
    s = s.replace('            } else if (position == rotationTimeoutInfoRow) {\n                return -11;', '''            } else if (position == warpRow) {
                return -12;
            } else if (position == warpDetailRow) {
                return -13;
            } else if (position == callsRow) {
                return -14;
            } else if (position == callsDetailRow) {
                return -15;
            } else if (position == rotationTimeoutInfoRow) {
                return -11;''', 1)
    s = s.replace("    public void onResume() {\n        super.onResume();", "    public void onResume() {\n        super.onResume();\n        colgramRouteHandler.removeCallbacks(colgramRouteRefresh);\n        colgramRouteHandler.post(colgramRouteRefresh);", 1)
    s = s.replace("    public void onFragmentDestroy() {\n        super.onFragmentDestroy();", "    public void onFragmentDestroy() {\n        colgramRouteHandler.removeCallbacks(colgramRouteRefresh);\n        if (waitingWarpConsent >= 0 && warpRequested(waitingWarpConsent)) cancelWarpFromProxyScreen();\n        super.onFragmentDestroy();", 1)
    return s


def upgrade_call_route_picker(s):
    """Replace the v5 calls switch and remove the detour into general settings."""
    marker = "// COLGRAM_CALL_ROUTE_PICKER = 1"
    if marker in s:
        return s.replace("return position == position == useProxyRow", "return position == useProxyRow")
    s = replace_section(s, "            } else if (position == callsRow) {",
                        "            } else if (position == warpRow) {", '''            } else if (position == callsRow) {
                // COLGRAM_CALL_ROUTE_PICKER = 1
                ColgramCallRoutes.showPicker(getParentActivity(), () -> listAdapter.notifyItemChanged(callsRow));
''')
    s = s.replace("        networkModesRow = rowCount++;\n", "")
    s = s.replace("    private int networkModesRow = -1;\n", "")
    s = s.replace("            } else if (position == networkModesRow) {\n                presentFragment(new ColgramSettingsActivity());\n", "")
    s = s.replace("return position == networkModesRow || ", "return ")
    s = s.replace("                    if (position == networkModesRow) {\n                        textCell.setText(\"Режим подключения · Direct / DPI / VPN\", true);\n                    } else if (position == proxyAddRow) {", '''                    if (position == callsRow) {
                        textCell.setTextAndValue("Использовать для звонков", ColgramCallRoutes.summary(), true);
                    } else if (position == proxyAddRow) {''')
    s = s.replace("            } else if (position == networkModesRow) {\n                return -16;\n", "")
    s = s.replace("position == networkModesRow || position == proxyAddRow", "position == callsRow || position == proxyAddRow")
    s = s.replace("} else if (position == useProxyRow || position == rotationRow || position == warpRow || position == callsRow)", "} else if (position == useProxyRow || position == rotationRow || position == warpRow)")
    s = s.replace('''                    } else if (position == callsRow) {
                        checkCell.setTextAndCheck("Использовать для звонков", callsPreference(), true);
''', "")
    s = s.replace('''                } else if (position == callsRow) {
                    checkCell.setChecked(callsPreference());
''', "")
    s = replace_section(s, "                    if (position == callsDetailRow) {",
                        "                    } else if (position == warpDetailRow) {", '''                    if (position == callsDetailRow) {
                        cell.setText("Маршрут применяется к новым личным звонкам. Если выбранный маршрут недоступен, звонок не уйдёт по другому.");
''')
    return s


def add_browser_route_row(s):
    marker = "// COLGRAM_BROWSER_ROUTE_PICKER = 1"
    if marker in s:
        return s
    if "private int browserRow = -1;" not in s:
        s = s.replace(
            "    private int callsRow = -1;\n    private int callsDetailRow = -1;\n",
            "    private int callsRow = -1;\n    private int callsDetailRow = -1;\n    private int browserRow = -1;\n    private int browserDetailRow = -1;\n",
            1,
        )
    s = s.replace(
        "        callsRow = rowCount++;\n        callsDetailRow = rowCount++;\n",
        "        callsRow = rowCount++;\n        callsDetailRow = rowCount++;\n        browserRow = rowCount++;\n        browserDetailRow = rowCount++;\n",
        1,
    )
    s = s.replace(
        "            } else if (position == callsRow) {\n                // COLGRAM_CALL_ROUTE_PICKER = 1\n                ColgramCallRoutes.showPicker(getParentActivity(), () -> listAdapter.notifyItemChanged(callsRow));\n",
        "            } else if (position == callsRow) {\n                // COLGRAM_CALL_ROUTE_PICKER = 1\n                ColgramCallRoutes.showPicker(getParentActivity(), () -> listAdapter.notifyItemChanged(callsRow));\n            } else if (position == browserRow) {\n                // COLGRAM_BROWSER_ROUTE_PICKER = 1\n                ColgramBrowserRoutes.showPicker(getParentActivity(), () -> listAdapter.notifyItemChanged(browserRow));\n",
        1,
    )
    s = s.replace(
        "                    if (position == callsRow) {\n                        textCell.setTextAndValue(\"Использовать для звонков\", ColgramCallRoutes.summary(), true);\n",
        "                    if (position == callsRow) {\n                        textCell.setTextAndValue(\"Использовать для звонков\", ColgramCallRoutes.summary(), true);\n                    } else if (position == browserRow) {\n                        textCell.setTextAndValue(\"Использовать для браузера\", ColgramBrowserRoutes.summary(), true);\n",
        1,
    )
    s = s.replace(
        "                    } else if (position == callsDetailRow) {\n                        cell.setText(\"Маршрут применяется к новым личным звонкам. Если выбранный маршрут недоступен, звонок не уйдёт по другому.\");\n",
        "                    } else if (position == callsDetailRow) {\n                        cell.setText(\"Маршрут применяется к новым личным звонкам. Если выбранный маршрут недоступен, звонок не уйдёт по другому.\");\n                    } else if (position == browserDetailRow) {\n                        cell.setText(\"Маршрут применяется к встроенному браузеру, Instant View и веб-запросам. По умолчанию — локальный обходник на вашем интернете.\");\n",
        1,
    )
    s = s.replace("position == warpRow || position == callsRow || position == proxyAddRow",
                  "position == warpRow || position == callsRow || position == browserRow || position == proxyAddRow")
    s = s.replace("if (position == callsRow || position == proxyAddRow",
                  "if (position == callsRow || position == browserRow || position == proxyAddRow")
    s = s.replace("else if (position == callsRow || position == proxyAddRow",
                  "else if (position == callsRow || position == browserRow || position == proxyAddRow")
    s = s.replace(
        "            } else if (position == callsRow) {\n                return -14;\n            } else if (position == callsDetailRow) {\n                return -15;\n",
        "            } else if (position == callsRow) {\n                return -14;\n            } else if (position == callsDetailRow) {\n                return -15;\n            } else if (position == browserRow) {\n                return -22;\n            } else if (position == browserDetailRow) {\n                return -23;\n",
        1,
    )
    return s


def apply(repo_path):
    p = os.path.join(repo_path, "TMessagesProj", "src", "main", "java",
                     "org", "telegram", "ui", "ProxyListActivity.java")
    if not os.path.exists(p):
        print(" [!] ProxyListActivity.java not found")
        return
    s = Path(p).read_text(encoding="utf-8", errors="ignore")
    copy_subscription_controls(p)
    # Refresh only the generated helper block when its template is updated.
    if PATCH_VERSION in s:
        template = (Path(__file__).parent / "templates/ProxyListWarpControls.java.inc").read_text(encoding="utf-8")
        updated = replace_section(s, "    // ---- Colgram WARP helpers", "    private int connectionsHeaderRow;", template + "\n")
        if "// COLGRAM_CALL_ROUTE_PICKER = 1" in updated:
            updated = updated.replace("    private int networkModesRow = -1;\n", "")
        updated = upgrade_call_route_picker(updated)
        updated = updated.replace('warpStatusForProxyScreen(),\n'
                                  '                                org.colgram.core.ColgramConfig.isWarpEnabled() || warpStartPending, false, false);',
                                  'warpStatusForProxyScreen(),\n'
                                  '                                org.colgram.core.ColgramConfig.isWarpEnabled() || warpStartPending, true, false);')
        updated = add_browser_route_row(add_connection_settings(updated))
        if updated != s:
            Path(p).write_text(updated, encoding="utf-8", newline="\n")
        apply_call_proxy_defaults(repo_path)
        apply_voip_call_proxy(repo_path)
        return
    if MARKER in s:
        upgraded = add_browser_route_row(add_connection_settings(upgrade_call_route_picker(upgrade_generated(s))))
        if upgraded != s:
            Path(p).write_text(upgraded, encoding="utf-8", newline="\n")
            print(" [+] Updated ProxyListActivity WARP state handling")
        else:
            print(" [=] Already patched: ProxyListActivity WARP Row")
        apply_call_proxy_defaults(repo_path)
        apply_voip_call_proxy(repo_path)
        return
    n_edits = 0

    def must_replace(old, new, what, count=1):
        nonlocal s, n_edits
        assert old in s, "anchor missing: " + what
        s = s.replace(old, new, count)
        n_edits += 1

    # --- E1. fields -----------------------------------------------------------
    #
    # Upstream 12.10.6 removed the calls toggle outright - there is no callsRow field left to
    # hang the WARP row off, and no UseProxyForCalls string either. The old anchor was
    # `private int callsRow;`, so it stopped matching the day that row was deleted upstream and
    # every CI run died here before reaching the WARP edit that follows. Anchor on a field that
    # is still there, and declare the WARP rows alongside it.
    must_replace(
        "    private int useProxyShadowRow;",
        "    private int useProxyShadowRow;\n"
        "    // Colgram: WARP toggle row, mutually exclusive with the regular proxy.\n"
        "    private int warpRow = -1;\n"
        "    private int warpDetailRow = -1;\n"
        "    private static final int REQ_WARP_CONSENT = 9182;\n"
        "    /**\n"
        "     * True while a WARP start is in flight, before the persisted flag says anything.\n"
        "     *\n"
        "     * <p>The flag is written on the tap, not after bringUp() succeeds, because the tunnel\n"
        "     * takes seconds to open a session and a background proxy verdict landing in that\n"
        "     * window would tear down the tunnel the user asked for. Until it is up there is\n"
        "     * therefore no flag to read, and the row has to read this instead - otherwise the switch\n"
        "     * renders off under the finger and looks dead.\n"
        "     */\n"
        "    private boolean warpStartPending;",
        "fields")

    # --- E2. updateRows: calls+warp rows right under the proxy switch -----------
    old_head = """        if (rotationTimeoutInfoRow == -1) {
            useProxyShadowRow = rowCount++;
        } else {
            useProxyShadowRow = -1;
        }
        connectionsHeaderRow = rowCount++;
"""
    new_head = """        if (rotationTimeoutInfoRow == -1) {
            useProxyShadowRow = rowCount++;
        } else {
            useProxyShadowRow = -1;
        }
        // Colgram: the WARP toggle belongs RIGHT under the proxy switch, above the server
        // list - below it it drowned under the published pool. The calls row that used to sit
        // here no longer exists upstream, so only the two WARP rows are allocated.
        warpRow = rowCount++;
        warpDetailRow = rowCount++;
        connectionsHeaderRow = rowCount++;
"""
    must_replace(old_head, new_head, "updateRows head")

    # The upstream calls block further down must go, or the rows would exist twice.
    #
    # It is gone from 12.10.6 along with the toggle itself, so this removal is a no-op there.
    # It stays, conditional, because the patch has to keep working on a tree where the toggle
    # is still present - removing it unconditionally would make must_replace raise on an anchor
    # that is legitimately absent.
    old_calls_block = """        if (SharedConfig.currentProxy == null || SharedConfig.currentProxy.settings.getSecret().isEmpty()) {
            boolean change = callsRow == -1;
            callsRow = rowCount++;
            callsDetailRow = rowCount++;
            if (!notify && change) {
                listAdapter.notifyItemChanged(proxyShadowRow);
                listAdapter.notifyItemRangeInserted(proxyShadowRow + 1, 2);
            }
        } else {
            boolean change = callsRow != -1;
            callsRow = -1;
            callsDetailRow = -1;
            if (!notify && change) {
                listAdapter.notifyItemChanged(proxyShadowRow);
                listAdapter.notifyItemRangeRemoved(proxyShadowRow + 1, 2);
            }
        }"""
    if old_calls_block in s:
        must_replace(old_calls_block, "", "upstream calls block removal")
    else:
        print(" [=] upstream no longer carries the calls rows; nothing to remove")

    # --- E3. proxy-enable handler shuts WARP down ------------------------------
    # Same story: the handler branch that unchecked the calls cell went with the toggle.
    old_enable = """                if (!useProxySettings) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(callsRow);
                    if (holder != null) {
                        textCheckCell = (TextCheckCell) holder.itemView;
                        textCheckCell.setChecked(false);
                    }
                    useProxyForCalls = false;
                }"""
    new_enable = old_enable + """
                if (useProxySettings) {
                    // One default route at a time: the regular proxy replacing WARP.
                    org.colgram.core.ColgramWarpTunnel.bringDown(getContext());
                }"""
    if old_enable in s:
        must_replace(old_enable, new_enable, "proxy enable handler")
    else:
        # The whole point of this edit is the WARP teardown, and it hangs off the proxy-enable
        # branch. Where upstream dropped the calls uncheck, anchor on the branch that remains.
        enable_only = """                if (useProxySettings) {
                    // One default route at a time: the regular proxy replacing WARP.
                    org.colgram.core.ColgramWarpTunnel.bringDown(getContext());
                }"""
        anchor = "                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();"
        must_replace(anchor, enable_only + "\n" + anchor, "proxy enable handler (no calls row)")

    # --- E4. click handler for warpRow -----------------------------------------
    #
    # This used to hang the WARP branch off the callsRow handler, because that branch sat right
    # next to it and appending to an existing `} else if` needed no new anchor. Upstream 12.10.6
    # deleted the calls toggle, so the branch it appended to is gone and the whole edit became
    # unappliable - the WARP row would have been declared by E2 and then handled by nothing,
    # which is a row that renders and does nothing on tap.
    #
    # proxyAddRow is the stable neighbour now: it has survived every upstream that moved the
    # calls block, and it is the next branch in the same chain.
    proxy_add_click = """            } else if (position == proxyAddRow) {
                presentFragment(new ProxySettingsActivity());"""
    warp_click = """            } else if (position == warpRow) {
                // Colgram: enabling WARP tears the regular proxy down and vice versa.
                TextCheckCell textCheckCell = (TextCheckCell) view;
                boolean wantWarp = !org.colgram.core.ColgramWarpTunnel.isUp();
                if (wantWarp) {
                    if (useProxySettings) {
                        useProxySettings = false;
                        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                        editor.putBoolean("proxy_enabled", false);
                        editor.commit();
                        ConnectionsManager.setProxySettings(false, null);
                        org.colgram.core.ColgramProxyManager.onStockProxyToggle(false);
                    }
                    textCheckCell.setChecked(true);
                    startWarpFromProxyScreen();
                } else {
                    textCheckCell.setChecked(false);
                    org.colgram.core.ColgramWarpTunnel.bringDown(getContext());
                }
                updateRows(true);
            } else if (position == proxyAddRow) {
                presentFragment(new ProxySettingsActivity());"""
    must_replace(proxy_add_click, warp_click, "warp click branch")

    # --- E5. main bind: warp row text ------------------------------------------
    #
    # Like E4, this hung off the callsRow branch. rotationRow is the stable neighbour now - it is
    # the branch immediately after in the same TextCheckCell chain and it is still present in
    # 12.10.6, whereas the calls branch is not.
    old_bind = """                    } else if (position == rotationRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyRotation), SharedConfig.proxyRotationEnabled, true);"""
    # The calls branch is deliberately NOT re-created here. It used to sit above the WARP branch
    # in this replacement, which meant the patch wrote `position == callsRow` and
    # `useProxyForCalls` into a file where upstream has deleted both - javac then stopped the
    # release with six errors, and the row it was adding could never have rendered. Upstream 12.10.6
    # has no calls UI at all, so the branch is gone rather than relocated.
    new_bind = """                    } else if (position == warpRow) {
                        // A start in flight is not yet in the persisted flag, so it has to be
                        // considered here or the switch would read "off" while it is coming up.
                        boolean warpPending = warpStartPending;
                        boolean warpUp = org.colgram.core.ColgramConfig.isWarpEnabled() || warpPending;
                        boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
                        String warpLabel = isRu ? "Использовать Cloudflare WARP" : "Use Cloudflare WARP";
                        String warpState;
                        if (warpUp) {
                            boolean warpConnected = org.colgram.core.ColgramWarpTunnel.isConnected();
                            warpState = isRu
                                    ? (warpConnected ? "подключен — обычный прокси выключен" : "подключается — обычный прокси выключен")
                                    : (warpConnected ? "connected — regular proxy off" : "connecting — regular proxy off");
                        } else {
                            warpState = isRu ? "выключен" : "off";
                        }
                        checkCell.setTextAndValueAndCheck(warpLabel, warpState, org.colgram.core.ColgramWarpTunnel.isUp(), false, false);
                    } else if (position == rotationRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyRotation), SharedConfig.proxyRotationEnabled, true);"""
    must_replace(old_bind, new_bind, "main bind")

    # --- E6. payload + attach binds: checked state ------------------------------
    #
    # Two places re-check a TextCheckCell by position, and both used to branch on callsRow.
    # rotationRow survives upstream in both, so it carries the WARP branch instead. The predicate
    # is the persisted flag OR a start in flight, because a row that reads off while the tunnel
    # is coming up is exactly the switch reported as not responding until the screen was reopened.
    state_bind = """                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);"""
    state_bind_new = """                } else if (position == warpRow) {
                    checkCell.setChecked(org.colgram.core.ColgramConfig.isWarpEnabled() || warpStartPending);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);"""
    found = s.count(state_bind)
    if found < 2:
        raise AssertionError("expected the rotation state bind twice, found %d" % found)
    must_replace(state_bind, state_bind_new, "state bind x2", count=2)

    # --- E7. isEnabled -----------------------------------------------------------
    #
    # The predicate used to name callsRow, and upstream dropped it from here as well, so the whole
    # comparison no longer matched. The two rows that are definitely present are enough to anchor
    # on: useProxyRow is the switch this screen exists for, proxyAddRow sits at the far end of the
    # same chain. warpRow has to be added or the new row renders grey and ignores every tap.
    must_replace(
        "return position == useProxyRow || position == rotationRow || position == proxyAddRow ||",
        "return position == useProxyRow || position == rotationRow || position == warpRow || position == proxyAddRow ||",
        "isEnabled")

    # --- E8. view types ----------------------------------------------------------
    #
    # Without this the adapter is asked for a type for warpRow and gets the default one, which is
    # not a TextCheckCell - the cast in onBindViewHolder then throws and the screen dies on open.
    # Same reason as E7: the anchor cannot name a row that upstream deleted.
    must_replace(
        "} else if (position == useProxyRow || position == rotationRow) {\n                return VIEW_TYPE_TEXT_CHECK;",
        "} else if (position == useProxyRow || position == rotationRow || position == warpRow) {\n                return VIEW_TYPE_TEXT_CHECK;",
        "view types")

    # --- E9. info cell for warpDetailRow -----------------------------------------
    #
    # The explanation row under the switch. It used to prepend itself to the callsDetailRow
    # branch, which upstream deleted along with the rest of the calls UI; rotationTimeoutInfoRow
    # is the first info branch still in the file.
    old_info = """                    if (position == rotationTimeoutInfoRow) {"""
    new_info = """                    if (position == warpDetailRow) {
                        boolean isRuInfo = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
                        cell.setText(isRuInfo
                                ? "Трафик пойдёт через встроенный Cloudflare WARP. Обычный прокси при этом выключается."
                                : "Traffic goes through the built-in Cloudflare WARP. The regular proxy is switched off.");
                    } else if (position == rotationTimeoutInfoRow) {"""
    must_replace(old_info, new_info, "info cells")

    # --- E10. helpers + consent result --------------------------------------------
    anchor_fields_end = "    private static final int REQ_WARP_CONSENT = 9182;"
    helpers = anchor_fields_end + """

    // ---- Colgram WARP helpers ----------------------------------------------------------

    private void startWarpFromProxyScreen() {
        android.content.Context context = getContext();
        if (context == null || getParentActivity() == null) return;
        if (!org.colgram.core.ColgramWarpTunnel.isBackendAvailable()) {
            android.widget.Toast.makeText(context, "WireGuard-бэкенд недоступен", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (!org.colgram.core.ColgramWarp.isRegistered()) {
            new Thread(() -> {
                try {
                    org.colgram.core.ColgramWarp.register(context);
                    AndroidUtilities.runOnUIThread(this::prepareAndStartWarp);
                } catch (Throwable t) {
                    String reason = t.getMessage() == null ? t.toString() : t.getMessage();
                    AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(
                            context, "WARP: регистрация не удалась — " + reason,
                            android.widget.Toast.LENGTH_LONG).show());
                }
            }, "colgram-warp-reg").start();
            return;
        }
        prepareAndStartWarp();
    }

    private void prepareAndStartWarp() {
        if (getParentActivity() == null) return;
        try {
            android.content.Intent prepare = android.net.VpnService.prepare(getParentActivity());
            if (prepare != null) {
                startActivityForResult(prepare, REQ_WARP_CONSENT);
            } else {
                bringWarpUp();
            }
        } catch (Throwable t) {
            android.widget.Toast.makeText(getContext(), "WARP: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void bringWarpUp() {
        final android.content.Context context = getContext() != null ? getContext().getApplicationContext() : null;
        if (context == null) return;
        new Thread(() -> {
            try {
                org.colgram.core.ColgramWarpTunnel.bringUp(context);
            } catch (Throwable t) {
                String reason = t.getMessage() == null ? t.toString() : t.getMessage();
                AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(
                        context, "WARP не поднялся — " + reason, android.widget.Toast.LENGTH_LONG).show());
            }
        }, "colgram-warp-up").start();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == REQ_WARP_CONSENT && resultCode == android.app.Activity.RESULT_OK) {
            bringWarpUp();
        } else {
            super.onActivityResultFragment(requestCode, resultCode, data);
        }
    }"""
    must_replace(anchor_fields_end, helpers, "helpers")

    s = add_browser_route_row(add_connection_settings(upgrade_call_route_picker(upgrade_generated(s))))
    Path(p).write_text(s, encoding="utf-8", newline="\n")
    print(" [+] ProxyListActivity: calls row unconditional + WARP toggle (" + str(n_edits) + " edits)")
    apply_call_proxy_defaults(repo_path)
    apply_voip_call_proxy(repo_path)


def apply_call_proxy_defaults(repo_path):
    """Do not let proxy lifecycle operations silently clear the calls preference."""
    edits = (
        ("TMessagesProj/src/main/java/org/telegram/messenger/ProxyRotationController.java",
         '            if (!info.settings.getSecret().isEmpty()) {\n'
         '                editor.putBoolean("proxy_enabled_calls", false);\n'
         '            }\n', ''),
        ("TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java",
         '            editor.putBoolean("proxy_enabled_calls", false);\n', ''),
        ("TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java",
         '                        editor.putBoolean("proxy_enabled_calls", false);\n', ''),
    )
    for relative, old, new in edits:
        path = os.path.join(repo_path, *relative.split('/'))
        if not os.path.isfile(path):
            continue
        source = Path(path).read_text(encoding="utf-8", errors="ignore")
        updated = source.replace(old, new)
        if updated != source:
            Path(path).write_text(updated, encoding="utf-8", newline="\n")


def apply_voip_call_proxy(repo_path):
    """Wire actual Android route preflight and fail closed in the private media path."""
    from call_route_patches import patch_call_activity_result, patch_call_helper, patch_private_call_service
    prefix = Path(repo_path) / "TMessagesProj/src/main/java/org/telegram"
    for relative, transform in (
        ("messenger/voip/VoIPService.java", patch_private_call_service),
        ("ui/Components/voip/VoIPHelper.java", patch_call_helper),
        ("ui/LaunchActivity.java", patch_call_activity_result),
    ):
        path = prefix / relative
        if not path.is_file():
            continue
        source = path.read_text(encoding="utf-8")
        updated = transform(source)
        if updated != source:
            path.write_text(updated, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    apply(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "Telegram-Src"))
