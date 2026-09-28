package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class StringBox implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public StringBox() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof StringBox)) {
            return false;
        }
        String value = getValue();
        String value2 = ((StringBox) obj).getValue();
        if (value == null) {
            return value2 == null;
        }
        return value.equals(value2);
    }

    public final native String getValue();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getValue()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setValue(String str);

    public String toString() {
        return "StringBox{Value:" + getValue() + ",}";
    }

    public StringBox(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
