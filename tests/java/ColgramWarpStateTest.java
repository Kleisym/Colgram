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
        check(state.isUp(35100));
        check(!state.isUp(35101));
        check(!state.isUp(99));
        check(state.accept(first, ColgramWarpState.UP, 36000));
        check(state.isUp(36001));
        state.stop();
        check(!state.isUp(36002));
        check(!state.accept(first, ColgramWarpState.UP, 36003));
        long second = state.begin();
        check(!state.accept(first, ColgramWarpState.FAILED, 37000));
        check(state.accept(second, ColgramWarpState.FAILED, 37000));
        check(!state.accept(second, ColgramWarpState.UP, 37001));
        check(!state.isUp(37001));
        long third = state.begin();
        check(!state.accept(third, 99, 40000));
        check(state.accept(third, ColgramWarpState.UP, 40000));
        check(state.accept(third, ColgramWarpState.STOPPED, 40001));
        check(!state.accept(third, ColgramWarpState.UP, 40002));
        System.out.println("ColgramWarpStateTest: " + checks + " checks PASS");
    }
}
