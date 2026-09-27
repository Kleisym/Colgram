#!/usr/bin/env python3
"""Patch ProxyListActivity: always-visible calls toggle + Cloudflare WARP row.

Idempotent, marker-based. Called from apply-patches.py and runnable standalone.
"""
import io
import os
import re

MARKER = "REQ_WARP_CONSENT = 9182"
PATCH_VERSION = "COLGRAM_WARP_UI_PATCH = 4"


def upgrade_generated(s):
    """Apply the current state/route fixes to both fresh and already-patched trees."""
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
         'warpState, org.colgram.core.ColgramConfig.isWarpEnabled(), false, false)'),
        ('checkCell.setChecked(org.colgram.core.ColgramWarpTunnel.isUp());',
         'checkCell.setChecked(org.colgram.core.ColgramConfig.isWarpEnabled());'),
        ('boolean wantWarp = !org.colgram.core.ColgramWarpTunnel.isUp();',
         'boolean wantWarp = !org.colgram.core.ColgramConfig.isWarpEnabled();'),
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
    return s


def apply(repo_path):
    p = os.path.join(repo_path, "TMessagesProj", "src", "main", "java",
                     "org", "telegram", "ui", "ProxyListActivity.java")
    if not os.path.exists(p):
        print(" [!] ProxyListActivity.java not found")
        return
    s = io.open(p, encoding="utf-8", errors="ignore").read()
    if MARKER in s:
        upgraded = upgrade_generated(s)
        if upgraded != s:
            io.open(p, "w", encoding="utf-8").write(upgraded)
            print(" [+] Updated ProxyListActivity WARP state handling")
        else:
            print(" [=] Already patched: ProxyListActivity WARP Row")
        apply_call_proxy_defaults(repo_path)
        return
    n_edits = 0

    def must_replace(old, new, what, count=1):
        nonlocal s, n_edits
        assert old in s, "anchor missing: " + what
        s = s.replace(old, new, count)
        n_edits += 1

    # --- E1. fields -----------------------------------------------------------
    must_replace(
        "    private int callsRow;",
        "    private int callsRow;\n"
        "    // Colgram: WARP toggle row, mutually exclusive with the regular proxy.\n"
        "    private int warpRow = -1;\n"
        "    private int warpDetailRow = -1;\n"
        "    private static final int REQ_WARP_CONSENT = 9182;",
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
        // Colgram: the calls toggle and the WARP toggle belong RIGHT under the proxy
        // switch, above the server list - below it they drowned under the published pool.
        callsRow = rowCount++;
        callsDetailRow = rowCount++;
        warpRow = rowCount++;
        warpDetailRow = rowCount++;
        connectionsHeaderRow = rowCount++;
"""
    must_replace(old_head, new_head, "updateRows head")

    # The upstream calls block further down must go, or the rows would exist twice.
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
    must_replace(old_calls_block, "", "upstream calls block removal")

    # --- E3. proxy-enable handler shuts WARP down ------------------------------
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
    must_replace(old_enable, new_enable, "proxy enable handler")

    # --- E4. click handler for warpRow -----------------------------------------
    old_calls_click = """            } else if (position == callsRow) {
                useProxyForCalls = !useProxyForCalls;
                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(useProxyForCalls);
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putBoolean("proxy_enabled_calls", useProxyForCalls);
                editor.commit();
            }"""
    new_calls_click = old_calls_click + """ else if (position == warpRow) {
                // Colgram: enabling WARP tears the regular proxy down and vice versa.
                TextCheckCell textCheckCell = (TextCheckCell) view;
                boolean wantWarp = !org.colgram.core.ColgramWarpTunnel.isUp();
                if (wantWarp) {
                    if (useProxySettings) {
                        useProxySettings = false;
                        useProxyForCalls = false;
                        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                        editor.putBoolean("proxy_enabled", false);
                        editor.putBoolean("proxy_enabled_calls", false);
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
            }"""
    must_replace(old_calls_click, new_calls_click, "calls click block")

    # --- E5. main bind: warp row text ------------------------------------------
    old_bind = """                    } else if (position == callsRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyForCalls), useProxyForCalls, false);
                    } else if (position == rotationRow) {"""
    new_bind = """                    } else if (position == callsRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyForCalls), useProxyForCalls, false);
                    } else if (position == warpRow) {
                        boolean warpUp = org.colgram.core.ColgramWarpTunnel.isUp();
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
                    } else if (position == rotationRow) {"""
    must_replace(old_bind, new_bind, "main bind")

    # --- E6. payload + attach binds: checked state ------------------------------
    old_state = """                } else if (position == callsRow) {
                    checkCell.setChecked(useProxyForCalls);
                } else if (position == rotationRow) {"""
    new_state = """                } else if (position == callsRow) {
                    checkCell.setChecked(useProxyForCalls);
                } else if (position == warpRow) {
                        checkCell.setChecked(org.colgram.core.ColgramConfig.isWarpEnabled());
                } else if (position == rotationRow) {"""
    must_replace(old_state, new_state, "state bind x2", count=2)

    # --- E7. isEnabled -----------------------------------------------------------
    must_replace(
        "return position == useProxyRow || position == rotationRow || position == callsRow ||",
        "return position == useProxyRow || position == rotationRow || position == callsRow || position == warpRow ||",
        "isEnabled")

    # --- E8. view types ----------------------------------------------------------
    must_replace(
        "} else if (position == useProxyRow || position == rotationRow || position == callsRow) {\n                return VIEW_TYPE_TEXT_CHECK;",
        "} else if (position == useProxyRow || position == rotationRow || position == callsRow || position == warpRow) {\n                return VIEW_TYPE_TEXT_CHECK;",
        "view types")

    # --- E9. info cell for warpDetailRow -----------------------------------------
    old_info = """                    if (position == callsDetailRow) {
                        cell.setText(getString(R.string.UseProxyForCallsInfo));
                    } else if (position == rotationTimeoutInfoRow) {"""
    new_info = """                    boolean isRuInfo = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
                    if (position == callsDetailRow) {
                        cell.setText(getString(R.string.UseProxyForCallsInfo));
                    } else if (position == warpDetailRow) {
                        cell.setText(isRuInfo
                                ? "Трафик пойдёт через встроенный Cloudflare WARP (WireGuard). Обычный прокси при этом выключается."
                                : "Traffic goes through the built-in Cloudflare WARP (WireGuard). The regular proxy is switched off.");
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

    s = upgrade_generated(s)
    io.open(p, "w", encoding="utf-8").write(s)
    print(" [+] ProxyListActivity: calls row unconditional + WARP toggle (" + str(n_edits) + " edits)")
    apply_call_proxy_defaults(repo_path)


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
        source = io.open(path, encoding="utf-8", errors="ignore").read()
        updated = source.replace(old, new)
        if updated != source:
            io.open(path, "w", encoding="utf-8", newline="").write(updated)


if __name__ == "__main__":
    apply(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "Telegram-Src"))
