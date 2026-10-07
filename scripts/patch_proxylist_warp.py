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
    new_bind = """                    } else if (position == callsRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyForCalls), useProxyForCalls, false);
                    } else if (position == warpRow) {
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
