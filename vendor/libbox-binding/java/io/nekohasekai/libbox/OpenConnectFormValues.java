package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenConnectFormValues implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenConnectFormValues() {
        int i__NewOpenConnectFormValues = __NewOpenConnectFormValues();
        this.refnum = i__NewOpenConnectFormValues;
        Seq.trackGoRef(i__NewOpenConnectFormValues, this);
    }

    private static native int __NewOpenConnectFormValues();

    public native void add(String str, String str2);

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenConnectFormValues)) {
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

    public String toString() {
        return "OpenConnectFormValues{}";
    }

    public OpenConnectFormValues(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
