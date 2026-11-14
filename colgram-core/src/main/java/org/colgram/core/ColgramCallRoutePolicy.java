package org.colgram.core;

import java.util.Locale;

/** Decides whether a call may use the selected route without falling back to another one. */
public final class ColgramCallRoutePolicy {
    public enum Mode {
        CURRENT("Активный Android VPN"),
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
        // "DIRECT" and the old on/off values all migrate to CURRENT. That
        // option requires a real active VPN; it is not a direct fallback.
        try {
            return Mode.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Mode.CURRENT;
        }
    }

    /** Tunnel flags must describe verified/established runtime state, not requested preferences. */
    public static Decision evaluate(Mode mode, boolean androidVpn, boolean warpVerified,
                                    boolean subscriptionTun, boolean socksTun, boolean dpiTun) {
        if (mode == null || mode == Mode.CURRENT) {
            return androidVpn ? ALLOWED : denied(
                    "Android VPN не подключён. Выберите маршрут в меню «Использовать для звонков».");
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
