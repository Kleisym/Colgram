import org.colgram.core.ColgramAbiPolicy;
import java.util.Arrays;
import java.util.Collections;

public final class ColgramAbiPolicyTest {
    public static void main(String[] args) {
        if (!ColgramAbiPolicy.compatible(new String[]{"arm64-v8a", "armeabi-v7a"}, true).equals(Collections.singletonList("arm64-v8a"))) throw new AssertionError("ARM64");
        if (!ColgramAbiPolicy.compatible(new String[]{"arm64-v8a", "armeabi-v7a"}, false).equals(Collections.singletonList("armeabi-v7a"))) throw new AssertionError("ARM32 must not claim ARM64 library");
        if (!ColgramAbiPolicy.compatible(new String[]{"x86_64", "x86"}, true).equals(Collections.singletonList("x86_64"))) throw new AssertionError("x86_64");
        if (!ColgramAbiPolicy.compatible(new String[]{"x86_64", "x86"}, false).equals(Collections.singletonList("x86"))) throw new AssertionError("x86");
        if (!ColgramAbiPolicy.compatible(new String[]{"unknown", "arm64-v8a"}, false).isEmpty()) throw new AssertionError("unsupported");
        if (!ColgramAbiPolicy.compatible(null, true).isEmpty()) throw new AssertionError("null list");
        if (!ColgramAbiPolicy.compatible(new String[]{"x86", "x86", "armeabi-v7a"}, false).equals(Arrays.asList("x86", "armeabi-v7a"))) throw new AssertionError("order/dedup");
        System.out.println("ColgramAbiPolicyTest: 7 checks PASS");
    }
}
