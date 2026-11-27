package org.telegram.ui;

import android.app.Activity;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewCompat;
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

    private static final class ProxyGateState {
        boolean ready;
        boolean failed;
        final java.util.List<Runnable> pending = new java.util.ArrayList<>();
    }

    private static final Object PROXY_GATE_LOCK = new Object();
    private static final java.util.WeakHashMap<WebView, ProxyGateState> PROXY_GATES = new java.util.WeakHashMap<>();

    private static ProxyGateState gateFor(WebView webView) {
        synchronized (PROXY_GATE_LOCK) {
            ProxyGateState state = PROXY_GATES.get(webView);
            if (state == null) {
                state = new ProxyGateState();
                PROXY_GATES.put(webView, state);
            }
            return state;
        }
    }

    private static void completeProxyGate(WebView webView, boolean ok) {
        if (webView == null) return;
        java.util.List<Runnable> toRun;
        synchronized (PROXY_GATE_LOCK) {
            ProxyGateState state = PROXY_GATES.get(webView);
            if (state == null) return;
            state.ready = ok;
            state.failed = !ok;
            toRun = new java.util.ArrayList<>(state.pending);
            state.pending.clear();
        }
        if (ok) {
            for (Runnable action : toRun) {
                if (action != null) {
                    webView.post(action);
                }
            }
        }
    }

    /** Executes {@code action} once {@link ProxyController#setProxyOverride} has finished configuring WebView. */
    public static void whenProxyReady(WebView webView, Runnable action) {
        if (webView == null || action == null) return;
        boolean runNow = false;
        synchronized (PROXY_GATE_LOCK) {
            ProxyGateState state = PROXY_GATES.get(webView);
            if (state == null || state.ready) {
                runNow = true;
            } else if (!state.failed) {
                state.pending.add(action);
            } else {
                // Fail closed when strict proxy setup failed: never issue raw direct WebView navigation.
                return;
            }
        }
        if (runNow) {
            action.run();
        }
    }

    /** Safe replacement for {@code webView.loadUrl(url)} queued until proxy override activation completes. */
    public static void loadUrl(WebView webView, String url) {
        if (webView == null || TextUtils.isEmpty(url)) return;
        whenProxyReady(webView, () -> webView.loadUrl(url));
    }

    /** Safe replacement for {@code webView.loadUrl(url, headers)} queued until proxy override activation completes. */
    public static void loadUrl(WebView webView, String url, java.util.Map<String, String> additionalHttpHeaders) {
        if (webView == null || TextUtils.isEmpty(url)) return;
        whenProxyReady(webView, () -> {
            if (additionalHttpHeaders != null) {
                webView.loadUrl(url, additionalHttpHeaders);
            } else {
                webView.loadUrl(url);
            }
        });
    }

    private static final java.util.concurrent.ExecutorService ROUTE_BIND_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "colgram-browser-route");
                t.setDaemon(true);
                return t;
            });

    public static void applyToWebView(WebView webView) {
        if (webView == null) return;
        Mode selected = mode();
        boolean strictOrAnon = ColgramConfig.isStrictProxySecurityEnabled() || ColgramConfig.isMaxAnonymityEnabled();
        boolean needsProxyRule = selected != Mode.CURRENT || ColgramConfig.isMaxAnonymityEnabled();
        ProxyGateState gate = gateFor(webView);
        synchronized (PROXY_GATE_LOCK) {
            gate.ready = false;
            gate.failed = false;
        }
        boolean needsAsyncBind = (selected == Mode.LOCAL_DPI && !ColgramDpiBypass.isBound())
                || selected == Mode.SOCKS5;
        if (needsAsyncBind) {
            ROUTE_BIND_EXECUTOR.execute(() -> {
                Decision decision = evaluate(true);
                webView.post(() -> applyWebViewProxyOverride(webView, decision, strictOrAnon, needsProxyRule));
            });
        } else {
            Decision decision = evaluate(false);
            applyWebViewProxyOverride(webView, decision, strictOrAnon, needsProxyRule);
        }
        applyWebViewHardening(webView, selected == Mode.SOCKS5 || strictOrAnon);
    }

    private static void applyWebViewProxyOverride(WebView webView, Decision decision,
                                                  boolean strictOrAnon, boolean needsProxyRule) {
        if (webView == null) return;
        boolean asyncCallbackScheduled = false;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                if (!TextUtils.isEmpty(decision.proxyRule)) {
                    ProxyConfig config = new ProxyConfig.Builder()
                            .addProxyRule(decision.proxyRule)
                            .addBypassRule("127.0.0.1")
                            .addBypassRule("localhost")
                            .build();
                    ProxyController.getInstance().setProxyOverride(config, Runnable::run,
                            () -> completeProxyGate(webView, true));
                    asyncCallbackScheduled = true;
                } else if (needsProxyRule && strictOrAnon) {
                    notifyProxyOverrideIssue(webView,
                            "Встроенный браузер заблокирован",
                            "Не удалось поднять прокси-маршрут WebView (" + decision.mode.label + ")",
                            true);
                    ProxyController.getInstance().clearProxyOverride(Runnable::run,
                            () -> completeProxyGate(webView, false));
                    asyncCallbackScheduled = true;
                } else {
                    ProxyController.getInstance().clearProxyOverride(Runnable::run,
                            () -> completeProxyGate(webView, true));
                    asyncCallbackScheduled = true;
                }
            } else {
                boolean failClosed = needsProxyRule && strictOrAnon;
                notifyProxyOverrideIssue(webView,
                        failClosed ? "Встроенный браузер заблокирован" : "Браузер открыт напрямую",
                        failClosed
                                ? "System WebView не поддерживает PROXY_OVERRIDE при строгой политике"
                                : "System WebView не поддерживает PROXY_OVERRIDE — прокси не применён",
                        failClosed);
                if (failClosed) {
                    completeProxyGate(webView, false);
                    asyncCallbackScheduled = true;
                }
            }
        } catch (Throwable t) {
            if (needsProxyRule && strictOrAnon) {
                notifyProxyOverrideIssue(webView,
                        "Ошибка прокси WebView",
                        t.getClass().getSimpleName(),
                        true);
                completeProxyGate(webView, false);
                asyncCallbackScheduled = true;
            }
        }
        if (!asyncCallbackScheduled) {
            completeProxyGate(webView, !(needsProxyRule && strictOrAnon));
        }
    }

    private static void notifyProxyOverrideIssue(WebView webView, String title, String subtitle, boolean isError) {
        try {
            org.colgram.core.ColgramUiBridge.postNotice(webView != null ? webView.getContext() : null,
                    title, subtitle, isError);
        } catch (Throwable ignored) {}
    }

    private static void applyWebViewHardening(WebView webView, boolean strictFallback) {
        try {
            WebSettings settings = webView.getSettings();
            boolean harden = strictFallback
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
                if (currentUa != null) {
                    boolean isBotUa = currentUa.contains("Telegram-Android/");
                    settings.setUserAgentString(org.colgram.core.ColgramHookHandler.cloakWebViewUserAgent(
                            currentUa, isBotUa, org.telegram.messenger.BuildVars.BUILD_VERSION_STRING, "HIGH"));
                }
                String spoofJs = org.colgram.core.ColgramHookHandler.buildWebViewSpoofJs();
                if (spoofJs != null && !spoofJs.isEmpty()
                        && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    java.util.HashSet<String> allOrigins = new java.util.HashSet<>();
                    allOrigins.add("*");
                    WebViewCompat.addDocumentStartJavaScript(webView, spoofJs, allOrigins);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Injects anti-fingerprint JS into a WebView on page load when DOCUMENT_START_SCRIPT is unavailable. */
    public static void injectSpoofScript(WebView webView) {
        if (webView == null) return;
        try {
            if (!org.colgram.core.ColgramConfig.isCloakEnabled()
                    && !org.colgram.core.ColgramHookHandler.shouldHardenWebView()) {
                return;
            }
            String js = org.colgram.core.ColgramHookHandler.buildWebViewSpoofJs();
            if (js != null && !js.isEmpty()) {
                webView.evaluateJavascript(js, null);
            }
        } catch (Throwable ignored) {}
    }

    /** Replaces/strips Chromium hardware Client Hint headers when forwarding WebView HTTP requests. */
    public static String filterRequestHeaderValue(String headerName, String headerValue) {
        if (headerName == null || !org.colgram.core.ColgramConfig.isCloakEnabled()) {
            return headerValue;
        }
        String lower = headerName.toLowerCase(java.util.Locale.US);
        if ("sec-ch-ua-model".equals(lower)) {
            return "\"" + org.colgram.core.ColgramCloak.getEffectiveDeviceModel().replace("\"", "") + "\"";
        }
        if ("x-requested-with".equals(lower)) {
            return null;
        }
        return headerValue;
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
            if (ColgramConfig.isStrictProxySecurityEnabled() || ColgramConfig.isMaxAnonymityEnabled()) {
                throw new IOException("Blocked direct WebView fallback under strict privacy policy for mode=" + decision.mode);
            }
        }
        if (ColgramConfig.isMaxAnonymityEnabled()) {
            java.net.Proxy relayProxy = org.colgram.core.ColgramHttp.pickRelayProxy();
            if (relayProxy != null) {
                return url.openConnection(relayProxy);
            }
            throw new IOException("Blocked direct WebView connection under max-anonymity policy");
        }
        return url.openConnection(Proxy.NO_PROXY);
    }

    public static void showPicker(Activity activity, Runnable onChanged) {
        if (activity == null) return;
        Mode current = mode();
        Mode[] modes = Mode.values();
        java.util.List<ColgramUiKit.ChoiceItem> items = new java.util.ArrayList<>(modes.length);
        for (int i = 0; i < modes.length; i++) {
            String suffix = statusSuffix(modes[i]);
            items.add(new ColgramUiKit.ChoiceItem(
                    modes[i].label,
                    suffix.isEmpty() ? null : suffix,
                    org.telegram.messenger.R.drawable.msg_link2,
                    0,
                    modes[i] == current,
                    false,
                    null
            ));
        }
        ColgramUiKit.showChoiceSheet(
                activity,
                null,
                org.telegram.messenger.R.drawable.msg_link2,
                "Использовать для браузера",
                "Транспорт для встроенного WebView",
                items,
                which -> {
                    Mode chosen = modes[which];
                    if (chosen == Mode.SOCKS5) {
                        pickSocksEndpoint(activity, onChanged);
                        return;
                    }
                    ColgramConfig.setBrowserRouteMode(chosen.name());
                    Decision decision = evaluate(chosen == Mode.LOCAL_DPI);
                    if (decision.hint != null) {
                        ColgramUiKit.showNotice(activity, org.telegram.messenger.R.drawable.msg_link2, decision.hint, false);
                    }
                    if (onChanged != null) onChanged.run();
                }
        );
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
        String saved = ColgramConfig.getBrowserRouteSocks();
        java.util.List<ColgramUiKit.ChoiceItem> choiceItems = new java.util.ArrayList<>(options.size());
        for (int i = 0; i < options.size(); i++) {
            SocksEndpoint ep = options.get(i);
            choiceItems.add(new ColgramUiKit.ChoiceItem(
                    ep.identity(),
                    "SOCKS5 узел",
                    org.telegram.messenger.R.drawable.msg_online,
                    0,
                    ep.identity().equals(saved),
                    false,
                    null
            ));
        }
        ColgramUiKit.showChoiceSheet(
                activity,
                null,
                org.telegram.messenger.R.drawable.msg_link2,
                "Выберите SOCKS5 для браузера",
                null,
                choiceItems,
                which -> {
                    ColgramConfig.setBrowserRouteSocks(options.get(which).identity());
                    ColgramConfig.setBrowserRouteMode(Mode.SOCKS5.name());
                    if (onChanged != null) onChanged.run();
                }
        );
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
