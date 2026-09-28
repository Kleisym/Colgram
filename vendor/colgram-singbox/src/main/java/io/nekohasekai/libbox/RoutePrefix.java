package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class RoutePrefix implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public RoutePrefix() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native String address();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof RoutePrefix)) {
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

    public native String mask();

    public native int prefix();

    public native String string();

    public String toString() {
        return string();
    }

    public RoutePrefix(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
