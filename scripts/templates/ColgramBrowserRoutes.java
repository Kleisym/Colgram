package org.telegram.ui;

import android.app.Activity;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.colgram.core.ColgramBrowserRoutePolicy;
import org.colgram.core.ColgramBrowserRoutePolicy.Decision;
import org.colgram.core.ColgramBrowserRoutePolicy.Mode;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramDohResolver;
import org.colgram.core.ColgramDpiBypass;
import org.colgram.core.ColgramProxyChain;
import org.colgram.core.ColgramProxyChain.Relay;
import org.colgram.core.ColgramProxyManager;
import org.colgram.core.ColgramWarpTunnel;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.utils.proxy.ProxySettings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates Telegram in-app browser / WebView / HTTP resource routing with user preference.
 * Defaults to {@link Mode#LOCAL_DPI} ("Локальный обходник") using only the user's own connection.
 */
public final class ColgramBrowserRoutes {

    private static final class SocksEndpoint {
        final String host;
        final int port;
        final String username;
        final String password;

        SocksEndpoint(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
        }

        String identity() {
            return host + ":" + port;
        }

        boolean isThirdParty() {
            return !ColgramDohResolver.isLoopbackHost(host);
        }

        Relay toRelay() {
            return new Relay(host, port, true, username, password);
        }
    }

    private ColgramBrowserRoutes() {
    }

    public static Mode mode() {
        return ColgramBrowserRoutePolicy.fromPreference(ColgramConfig.getBrowserRouteMode());
    }

    public static String summary() {
        Mode selected = mode();
        if (selected == Mode.SOCKS5) {
            SocksEndpoint endpoint = resolveSelectedSocks();
            if (endpoint != null) {
                return "SOCKS5 · " + endpoint.identity();
            }
            return "Прокси SOCKS5 (не выбран)";
        }
        return selected.label;
    }

    private static boolean isSubscriptionRunning() {
        try {
            android.content.Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
            if (ctx == null) return false;
            return "running".equals(org.colgram.singbox.ColgramVpnService.status(
                    org.colgram.core.ColgramSubscriptionStore.profileLocation(ctx)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Decision evaluate(boolean startDpiIfNeeded) {
        Mode selected = mode();
        int dpiPort = 0;
        if (selected == Mode.LOCAL_DPI) {
            if (startDpiIfNeeded && !ColgramDpiBypass.isBound()) {
                try {
                    ColgramDpiBypass.startImmediately();
                } catch (Throwable ignored) {
                }
            }
            dpiPort = ColgramDpiBypass.isBound() ? ColgramDpiBypass.activePort() : 0;
        }
        SocksEndpoint socks = selected == Mode.SOCKS5 ? resolveSelectedSocks() : null;
        int frontPort = 0;
        if (socks != null) {
            frontPort = ColgramProxyChain.openSocks5Front(socks.toRelay());
        }
        return ColgramBrowserRoutePolicy.evaluate(
                selected,
                dpiPort,
                ColgramWarpTunnel.isConnected(),
                isSubscriptionRunning(),
                frontPort,
                socks != null,
                socks != null && socks.isThirdParty(),
                ColgramConfig.isStrictProxySecurityEnabled()
        );
    }

    public static void applyToWebView(WebView webView) {
        if (webView == null) return;
        Decision decision = evaluate(true);
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                if (!TextUtils.isEmpty(decision.proxyRule)) {
                    ProxyConfig config = new ProxyConfig.Builder()
                            .addProxyRule(decision.proxyRule)
                            .addBypassRule("127.0.0.1")
                            .addBypassRule("localhost")
                            .build();
                    ProxyController.getInstance().setProxyOverride(config, Runnable::run, () -> {
                    });
                } else {
                    ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> {
                    });
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            WebSettings settings = webView.getSettings();
            boolean harden = decision.strictHttpsOnly
                    || org.colgram.core.ColgramHookHandler.shouldHardenWebView()
                    || ColgramConfig.isMaxAnonymityEnabled();
            if (harden) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                settings.setAllowFileAccess(false);
                settings.setAllowFileAccessFromFileURLs(false);
                settings.setAllowUniversalAccessFromFileURLs(false);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    settings.setSafeBrowsingEnabled(true);
                }
            }
            if (org.colgram.core.ColgramHookHandler.shouldBlockBotLocation() || ColgramConfig.isMaxAnonymityEnabled()) {
                settings.setGeolocationEnabled(false);
            }
            if (harden || ColgramConfig.isMaxAnonymityEnabled()) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
            }
            if (org.colgram.core.ColgramConfig.isCloakEnabled()
                    || org.colgram.core.ColgramHookHandler.shouldHardenWebView()) {
                String currentUa = settings.getUserAgentString();
                if (currentUa != null && !currentUa.contains("Telegram-Android/")) {
                    settings.setUserAgentString(org.colgram.core.ColgramHookHandler.cloakWebViewUserAgent(
                            currentUa, false, org.telegram.messenger.BuildVars.BUILD_VERSION_STRING, "HIGH"));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Clears process-wide WebView proxy override safely when a proxied browser view closes. */
    public static void clearWebViewProxy() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> {
                });
            }
        } catch (Throwable ignored) {
        }
    }

    public static URLConnection openConnection(URL url) throws IOException {
        if (url == null) {
            throw new IOException("URL is null");
        }
        if (ColgramDohResolver.isLoopbackHost(url.getHost())) {
            return url.openConnection(Proxy.NO_PROXY);
        }
        Decision decision = evaluate(true);
        if (decision.mode == Mode.SOCKS5 && decision.strictHttpsOnly
                && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Blocked cleartext HTTP over third-party SOCKS5 proxy");
        }
        if (decision.mode == Mode.LOCAL_DPI || decision.mode == Mode.SOCKS5) {
            Relay relay = null;
            if (decision.mode == Mode.LOCAL_DPI && decision.localSocksPort > 0) {
                relay = new Relay("127.0.0.1", decision.localSocksPort, true);
            } else if (decision.mode == Mode.SOCKS5) {
                SocksEndpoint endpoint = resolveSelectedSocks();
                if (endpoint == null && ColgramConfig.isStrictProxySecurityEnabled()) {
                    throw new IOException("No usable SOCKS5 proxy configured for browser route");
                }
                if (endpoint != null) {
                    relay = endpoint.toRelay();
                }
            }
            if (relay != null) {
                if ("https".equalsIgnoreCase(url.getProtocol())) {
                    int connectPort = ColgramProxyChain.openProxyFront(relay);
                    if (connectPort > 0) {
                        return url.openConnection(new Proxy(Proxy.Type.HTTP,
                                new InetSocketAddress(InetAddress.getLoopbackAddress(), connectPort)));
                    }
                }
                if (decision.localSocksPort > 0) {
                    return url.openConnection(new Proxy(Proxy.Type.SOCKS,
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), decision.localSocksPort)));
                }
                if (decision.mode == Mode.SOCKS5 && ColgramConfig.isStrictProxySecurityEnabled()) {
                    throw new IOException("Failed to open loopback proxy front for SOCKS5");
                }
            }
        }
        return url.openConnection(Proxy.NO_PROXY);
    }

    public static void showPicker(Activity activity, Runnable onChanged) {
        if (activity == null) return;
        Mode current = mode();
        Mode[] modes = Mode.values();
        CharSequence[] items = new CharSequence[modes.length];
        for (int i = 0; i < modes.length; i++) {
            String suffix = statusSuffix(modes[i]);
            String mark = modes[i] == current ? "● " : "";
            items[i] = suffix.isEmpty() ? mark + modes[i].label : mark + modes[i].label + " (" + suffix + ")";
        }
        new AlertDialog.Builder(activity)
                .setTitle("Использовать для браузера")
                .setItems(items, (dialog, which) -> {
                    Mode chosen = modes[which];
                    if (chosen == Mode.SOCKS5) {
                        pickSocksEndpoint(activity, onChanged);
                        return;
                    }
                    ColgramConfig.setBrowserRouteMode(chosen.name());
                    Decision decision = evaluate(chosen == Mode.LOCAL_DPI);
                    if (decision.hint != null) {
                        Toast.makeText(activity, decision.hint, Toast.LENGTH_SHORT).show();
                    }
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void pickSocksEndpoint(Activity activity, Runnable onChanged) {
        List<SocksEndpoint> options = collectSocksEndpoints();
        if (options.isEmpty()) {
            Toast.makeText(activity, "Сначала добавьте рабочий SOCKS5-прокси в список", Toast.LENGTH_SHORT).show();
            return;
        }
        if (options.size() == 1) {
            ColgramConfig.setBrowserRouteSocks(options.get(0).identity());
            ColgramConfig.setBrowserRouteMode(Mode.SOCKS5.name());
            if (onChanged != null) onChanged.run();
            return;
        }
        CharSequence[] labels = new CharSequence[options.size()];
        String saved = ColgramConfig.getBrowserRouteSocks();
        for (int i = 0; i < options.size(); i++) {
            SocksEndpoint ep = options.get(i);
            labels[i] = (ep.identity().equals(saved) ? "● " : "") + ep.identity();
        }
        new AlertDialog.Builder(activity)
                .setTitle("Выберите SOCKS5 для браузера")
                .setItems(labels, (dialog, which) -> {
                    ColgramConfig.setBrowserRouteSocks(options.get(which).identity());
                    ColgramConfig.setBrowserRouteMode(Mode.SOCKS5.name());
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static String statusSuffix(Mode mode) {
        switch (mode) {
            case LOCAL_DPI:
                return ColgramDpiBypass.isBound() ? "активен" : "запустится авто";
            case WARP:
                return ColgramWarpTunnel.isConnected() ? "подключён" : "не подключён";
            case VPN:
                return isSubscriptionRunning() ? "подключён" : "не подключён";
            case SOCKS5:
                SocksEndpoint ep = resolveSelectedSocks();
                return ep != null ? ep.identity() : "не настроен";
            case CURRENT:
            default:
                return "напрямую / системный VPN";
        }
    }

    private static SocksEndpoint resolveSelectedSocks() {
        List<SocksEndpoint> endpoints = collectSocksEndpoints();
        if (endpoints.isEmpty()) return null;
        String saved = ColgramConfig.getBrowserRouteSocks();
        if (!TextUtils.isEmpty(saved)) {
            for (SocksEndpoint ep : endpoints) {
                if (saved.equals(ep.identity())) return ep;
            }
        }
        SharedConfig.ProxyInfo active = SharedConfig.currentProxy;
        if (active != null && active.settings != null && isPlainRemoteSocks(active.settings)) {
            String activeId = active.settings.getAddress() + ":" + active.settings.getPort();
            for (SocksEndpoint ep : endpoints) {
                if (activeId.equals(ep.identity())) return ep;
            }
        }
        return endpoints.get(0);
    }

    private static List<SocksEndpoint> collectSocksEndpoints() {
        Map<String, SocksEndpoint> unique = new LinkedHashMap<>();
        try {
            SharedConfig.loadProxyList();
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                if (info == null || info.settings == null) continue;
                if (!isPlainRemoteSocks(info.settings)) continue;
                SocksEndpoint ep = new SocksEndpoint(
                        info.settings.getAddress(),
                        info.settings.getPort(),
                        info.settings.getUser(),
                        info.settings.getPassword());
                unique.putIfAbsent(ep.identity(), ep);
            }
        } catch (Throwable ignored) {
        }
        try {
            for (ColgramProxyManager.ProxyItem item : ColgramProxyManager.getSelectableProxies()) {
                if (item == null || item.type != 0 || item.isLocalDpi()) continue;
                if (TextUtils.isEmpty(item.address) || item.port <= 0) continue;
                if (ColgramDohResolver.isLoopbackHost(item.address)) continue;
                SocksEndpoint ep = new SocksEndpoint(item.address, item.port, item.user, item.password);
                unique.putIfAbsent(ep.identity(), ep);
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>(unique.values());
    }

    private static boolean isPlainRemoteSocks(ProxySettings settings) {
        return settings.getType() == ProxySettings.Type.SOCKS5
                && TextUtils.isEmpty(settings.getSecret())
                && !TextUtils.isEmpty(settings.getAddress())
                && settings.getPort() > 0
                && !ColgramDohResolver.isLoopbackHost(settings.getAddress());
    }
}
