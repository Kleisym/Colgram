import org.colgram.core.ColgramWarpState;

public final class ColgramWarpStateTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("WARP check " + checks); }
    public static void main(String[] args) {
        ColgramWarpState state = new ColgramWarpState();
        check(!state.isUp(100));
        long first = state.begin();
        check(!state.isUp(100));
        check(state.accept(first, ColgramWarpState.UP, 100));
        check(state.isUp(100));
        long expiresAt = 100 + ColgramWarpState.HEARTBEAT_TTL_MS;
        check(ColgramWarpState.HEARTBEAT_TTL_MS == 65_000L);
        check(state.isUp(expiresAt));
        check(!state.isUp(expiresAt + 1));
        check(!state.isUp(99));
        check(state.accept(first, ColgramWarpState.UP, expiresAt + 1000));
        check(state.isUp(expiresAt + 1001));
        state.stop();
        check(!state.isUp(expiresAt + 1002));
        check(!state.accept(first, ColgramWarpState.UP, expiresAt + 1003));
        long second = state.begin();
        check(!state.accept(first, ColgramWarpState.FAILED, expiresAt + 2000));
        check(state.accept(second, ColgramWarpState.FAILED, expiresAt + 2000));
        check(!state.accept(second, ColgramWarpState.UP, expiresAt + 2001));
        check(!state.isUp(expiresAt + 2001));
        long third = state.begin();
        check(!state.accept(third, 99, expiresAt + 5000));
        check(state.accept(third, ColgramWarpState.UP, expiresAt + 5000));
        check(state.accept(third, ColgramWarpState.STOPPED, expiresAt + 5001));
        check(!state.accept(third, ColgramWarpState.UP, expiresAt + 5002));
        System.out.println("ColgramWarpStateTest: " + checks + " checks PASS");
    }
}
