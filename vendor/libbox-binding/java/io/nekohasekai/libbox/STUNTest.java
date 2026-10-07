package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class STUNTest implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public STUNTest() {
        int i__NewSTUNTest = __NewSTUNTest();
        this.refnum = i__NewSTUNTest;
        Seq.trackGoRef(i__NewSTUNTest, this);
    }

    private static native int __NewSTUNTest();

    public native void cancel();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof STUNTest)) {
            return false;
        }
        return true;
    }

    public int hashCode() {
        return Arrays.hashCode(new Object[0]);
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native void start(String str, STUNTestHandler sTUNTestHandler);

    public String toString() {
        return "STUNTest{}";
    }

    public STUNTest(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
