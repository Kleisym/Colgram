package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class Connections implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public Connections() {
        int i__NewConnections = __NewConnections();
        this.refnum = i__NewConnections;
        Seq.trackGoRef(i__NewConnections, this);
    }

    private static native int __NewConnections();

    public native void applyEvents(ConnectionEvents connectionEvents);

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof Connections)) {
            return false;
        }
        return true;
    }

    public native void filterState(int i);

    public int hashCode() {
        return Arrays.hashCode(new Object[0]);
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native ConnectionIterator iterator();

    public native void sortByDate();

    public native void sortByTraffic();

    public native void sortByTrafficTotal();

    public String toString() {
        return "Connections{}";
    }

    public Connections(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
