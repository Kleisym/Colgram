package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class ConnectionEvents implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public ConnectionEvents() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        return obj != null && (obj instanceof ConnectionEvents) && getReset() == ((ConnectionEvents) obj).getReset();
    }

    public final native boolean getReset();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Boolean.valueOf(getReset())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native ConnectionEventIterator iterator();

    public final native void setReset(boolean z);

    public String toString() {
        return "ConnectionEvents{Reset:" + getReset() + ",}";
    }

    public ConnectionEvents(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
