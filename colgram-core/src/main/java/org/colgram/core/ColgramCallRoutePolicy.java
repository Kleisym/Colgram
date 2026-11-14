package org.colgram.core;

import java.util.Locale;

/** Decides whether a call may use the selected route without falling back to another one. */
public final class ColgramCallRoutePolicy {
    public enum Mode {
        CURRENT("Текущее подключение"),
        DIRECT("Напрямую"),
        LOCAL_DPI("Локальный обход"),
        SOCKS5("Прокси SOCKS5"),
        WARP("Cloudflare WARP"),
        VPN("VPN из подписки");

        public final String labelRu;

        Mode(String labelRu) {
            this.labelRu = labelRu;
        }

        public String labelRu() {
            return labelRu;
        }
    }

    public static final class Decision {
        public final boolean allowed;
        public final String failure;

        private Decision(boolean allowed, String failure) {
            this.allowed = allowed;
            this.failure = failure;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String failureReason() {
            return failure;
        }
    }

    private static final Decision ALLOWED = new Decision(true, "");

    private ColgramCallRoutePolicy() {}

    public static Mode fromPreference(String saved) {
        if (saved == null) return Mode.CURRENT;
        String name = saved.trim().toUpperCase(Locale.ROOT);
        if ("TRUE".equals(name)) return Mode.CURRENT;
        if ("FALSE".equals(name)) return Mode.DIRECT;
        try {
            return Mode.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Mode.CURRENT;
        }
    }

    /** Preserve the old explicit opt-out when the route picker has never been used. */
    public static Mode fromPreference(String saved, boolean legacyProxyForCalls) {
        return saved == null && !legacyProxyForCalls ? Mode.DIRECT : fromPreference(saved);
    }

    /** Tunnel flags must describe verified/established runtime state, not requested preferences. */
    public static Decision evaluate(Mode mode, boolean androidVpn, boolean warpVerified,
                                    boolean subscriptionTun, boolean socksTun, boolean dpiTun) {
        if (mode == null || mode == Mode.CURRENT) return ALLOWED;
        if (mode == Mode.DIRECT) {
            return androidVpn ? denied("Отключите активный Android VPN для прямого звонка.") : ALLOWED;
        }

        int ownedTunnels = (warpVerified ? 1 : 0) + (subscriptionTun ? 1 : 0)
                + (socksTun ? 1 : 0) + (dpiTun ? 1 : 0);
        boolean matches = switch (mode) {
            case WARP -> warpVerified;
            case VPN -> subscriptionTun;
            case SOCKS5 -> socksTun;
            case LOCAL_DPI -> dpiTun;
            default -> false;
        };
        if (androidVpn && matches && ownedTunnels == 1) return ALLOWED;
        if (androidVpn && (ownedTunnels > 1 || !matches)) {
            return denied("Для звонка выбран «" + mode.labelRu + "», но активен другой VPN.");
        }
        return denied("Маршрут «" + mode.labelRu + "» для звонка не подключён.");
    }

    private static Decision denied(String reason) {
        return new Decision(false, reason);
    }
}
