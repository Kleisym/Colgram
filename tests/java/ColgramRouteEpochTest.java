import org.colgram.core.ColgramRouteEpoch;
import java.util.concurrent.atomic.AtomicInteger;

public final class ColgramRouteEpochTest {
    public static void main(String[] args) {
        AtomicInteger calls = new AtomicInteger();
        long first = ColgramRouteEpoch.next();
        if (!ColgramRouteEpoch.isCurrent(first) || ColgramRouteEpoch.snapshot() != first) throw new AssertionError("first request");
        if (!ColgramRouteEpoch.runIfCurrent(first, calls::incrementAndGet)) throw new AssertionError("current request skipped");
        long second = ColgramRouteEpoch.next();
        if (ColgramRouteEpoch.isCurrent(first) || !ColgramRouteEpoch.isCurrent(second)) throw new AssertionError("stale request survives");
        if (ColgramRouteEpoch.runIfCurrent(first, calls::incrementAndGet) || calls.get() != 1) throw new AssertionError("stale side effect");
        if (!ColgramRouteEpoch.runIfCurrent(second, calls::incrementAndGet) || calls.get() != 2) throw new AssertionError("replacement");
        System.out.println("ColgramRouteEpochTest: 5 checks PASS");
    }
}
