import org.colgram.core.ColgramCallRoutePolicy;
import org.colgram.core.ColgramCallRoutePolicy.Mode;

public final class ColgramCallRoutePolicyTest {
    private static int checks;

    private static void check(boolean okay, String explanation) {
        checks++;
        if (!okay) throw new AssertionError(explanation);
    }

    public static void main(String[] args) {
        check(java.util.Arrays.stream(Mode.values()).noneMatch(mode -> mode.name().equals("DIRECT")),
                "calls picker must not expose a direct route");
        check(ColgramCallRoutePolicy.fromPreference("DIRECT") == Mode.CURRENT,
                "old Direct selections must migrate to an active VPN, not direct traffic");
        check(!ColgramCallRoutePolicy.evaluate(Mode.CURRENT, false, false, false, false, false).allowed,
                "current route must not silently allow a raw Internet call");
        check(ColgramCallRoutePolicy.evaluate(Mode.CURRENT, true, false, false, false, false).allowed,
                "an already active Android VPN can carry a call");
        for (Mode mode : Mode.values()) {
            check(ColgramCallRoutePolicy.fromPreference(mode.name()) == mode, "stable persisted name " + mode);
            check(!mode.labelRu().isEmpty(), "visible label " + mode);
        }
        check(ColgramCallRoutePolicy.fromPreference(null) == Mode.CURRENT, "missing preference");
        check(ColgramCallRoutePolicy.fromPreference("not-a-route") == Mode.CURRENT, "invalid preference");
        check(ColgramCallRoutePolicy.fromPreference("  warp  ") == Mode.WARP, "normalize case/space");
        check(ColgramCallRoutePolicy.fromPreference("true") == Mode.CURRENT, "legacy on");
        check(ColgramCallRoutePolicy.fromPreference("false") == Mode.CURRENT, "legacy off is not a direct route");
        check(ColgramCallRoutePolicy.fromPreference("WARP") == Mode.WARP, "explicit WARP choice persists");

        for (Mode mode : Mode.values()) {
            for (int bits = 0; bits < 32; bits++) {
                boolean androidVpn = (bits & 1) != 0;
                boolean warp = (bits & 2) != 0;
                boolean subscription = (bits & 4) != 0;
                boolean socks = (bits & 8) != 0;
                boolean dpi = (bits & 16) != 0;
                boolean expected = switch (mode) {
                    case CURRENT -> androidVpn;
                    case WARP -> bits == 3;
                    case VPN -> bits == 5;
                    case SOCKS5 -> bits == 9;
                    case LOCAL_DPI -> bits == 17;
                };
                ColgramCallRoutePolicy.Decision decision =
                        ColgramCallRoutePolicy.evaluate(mode, androidVpn, warp, subscription, socks, dpi);
                String input = mode + " VPN flags=" + bits;
                check(decision.allowed == expected, "unexpected route verdict: " + input);
                check(decision.isAllowed() == expected, "inconsistent verdict: " + input);
                check(decision.failure.isEmpty() == expected, "failure should explain rejected route: " + input);
            }
        }
        check(!ColgramCallRoutePolicy.evaluate(null, false, false, false, false, false).allowed,
                "missing route also requires an active VPN");
        System.out.println("ColgramCallRoutePolicyTest: " + checks + " checks PASS");
    }
}
