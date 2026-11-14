import org.colgram.core.ColgramCallRoutePolicy;
import org.colgram.core.ColgramCallRoutePolicy.Mode;

public final class ColgramCallRoutePolicyTest {
    private static int checks;

    private static void check(boolean okay, String explanation) {
        checks++;
        if (!okay) throw new AssertionError(explanation);
    }

    public static void main(String[] args) {
        for (Mode mode : Mode.values()) {
            check(ColgramCallRoutePolicy.fromPreference(mode.name()) == mode, "stable persisted name " + mode);
            check(!mode.labelRu().isEmpty(), "visible label " + mode);
        }
        check(ColgramCallRoutePolicy.fromPreference(null) == Mode.CURRENT, "missing preference");
        check(ColgramCallRoutePolicy.fromPreference("not-a-route") == Mode.CURRENT, "invalid preference");
        check(ColgramCallRoutePolicy.fromPreference("  warp  ") == Mode.WARP, "normalize case/space");
        check(ColgramCallRoutePolicy.fromPreference("true") == Mode.CURRENT, "legacy on");
        check(ColgramCallRoutePolicy.fromPreference("false") == Mode.DIRECT, "legacy off");
        check(ColgramCallRoutePolicy.fromPreference(null, false) == Mode.DIRECT, "migrate stored legacy off");
        check(ColgramCallRoutePolicy.fromPreference("WARP", false) == Mode.WARP, "explicit preference wins migration");
        check(ColgramCallRoutePolicy.fromPreference("unknown", false) == Mode.CURRENT, "unknown explicit value defaults");

        for (Mode mode : Mode.values()) {
            for (int bits = 0; bits < 32; bits++) {
                boolean androidVpn = (bits & 1) != 0;
                boolean warp = (bits & 2) != 0;
                boolean subscription = (bits & 4) != 0;
                boolean socks = (bits & 8) != 0;
                boolean dpi = (bits & 16) != 0;
                boolean expected = switch (mode) {
                    case CURRENT -> true;
                    case DIRECT -> !androidVpn;
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
        check(ColgramCallRoutePolicy.evaluate(null, false, false, false, false, false).allowed,
                "null mode uses current route");
        System.out.println("ColgramCallRoutePolicyTest: " + checks + " checks PASS");
    }
}
