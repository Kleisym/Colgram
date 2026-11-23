package org.colgram.core;

/**
 * Route policy for Colgram's in-app WebViews, Instant View pages, mini-apps, and HTTP fetches.
 * Defaults to {@link Mode#LOCAL_DPI} ("Локальный обходник") using only the user's own connection.
 */
public final class ColgramBrowserRoutePolicy {

    public enum Mode {
        LOCAL_DPI("Локальный обход"),
        WARP("Cloudflare WARP"),
        VPN("VPN из подписки"),
        SOCKS5("Прокси SOCKS5"),
        CURRENT("Системный / напрямую");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    public static final class Decision {
        public final Mode mode;
        public final String proxyRule;
        public final int localSocksPort;
        public final boolean tunnelUp;
        public final boolean strictHttpsOnly;
        public final String hint;

        public Decision(Mode mode, String proxyRule, int localSocksPort, boolean tunnelUp,
                        boolean strictHttpsOnly, String hint) {
            this.mode = mode;
            this.proxyRule = proxyRule;
            this.localSocksPort = localSocksPort;
            this.tunnelUp = tunnelUp;
            this.strictHttpsOnly = strictHttpsOnly;
            this.hint = hint;
        }
    }

    private ColgramBrowserRoutePolicy() {
    }

    public static Mode fromPreference(String value) {
        if (value == null || value.isEmpty()) return Mode.LOCAL_DPI;
        for (Mode mode : Mode.values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) return mode;
        }
        return Mode.LOCAL_DPI;
    }

    public static Decision evaluate(Mode mode, int localDpiPort, boolean warpConnected,
                                    boolean subscriptionConnected, int socksFrontPort,
                                    boolean hasSocksConfigured, boolean thirdPartySocks,
                                    boolean strictProxySecurity) {
        Mode target = mode == null ? Mode.LOCAL_DPI : mode;
        switch (target) {
            case LOCAL_DPI:
                if (localDpiPort > 0) {
                    return new Decision(Mode.LOCAL_DPI, "socks5://127.0.0.1:" + localDpiPort,
                            localDpiPort, true, false, null);
                }
                return new Decision(Mode.LOCAL_DPI, null, 0, false, false,
                        "Локальный обходник не запущен");
            case WARP:
                if (warpConnected) {
                    return new Decision(Mode.WARP, null, 0, true, false, null);
                }
                return new Decision(Mode.WARP, null, 0, false, false,
                        "Cloudflare WARP не подключён — используется текущее подключение");
            case VPN:
                if (subscriptionConnected) {
                    return new Decision(Mode.VPN, null, 0, true, false, null);
                }
                return new Decision(Mode.VPN, null, 0, false, false,
                        "VPN из подписки не подключён — используется текущее подключение");
            case SOCKS5:
                boolean enforceHttps = thirdPartySocks && strictProxySecurity;
                if (socksFrontPort > 0) {
                    return new Decision(Mode.SOCKS5, "socks5://127.0.0.1:" + socksFrontPort,
                            socksFrontPort, true, enforceHttps, null);
                }
                String reason = hasSocksConfigured
                        ? "Не удалось открыть локальный SOCKS5-фронт"
                        : "Нет выбранного SOCKS5-прокси";
                return new Decision(Mode.SOCKS5, null, 0, false, enforceHttps, reason);
            case CURRENT:
            default:
                return new Decision(Mode.CURRENT, null, 0, true, false, null);
        }
    }
}
