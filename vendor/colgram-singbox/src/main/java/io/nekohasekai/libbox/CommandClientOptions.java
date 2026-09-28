package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class CommandClientOptions implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public CommandClientOptions() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native void addCommand(int i);

    public boolean equals(Object obj) {
        return obj != null && (obj instanceof CommandClientOptions) && getStatusInterval() == ((CommandClientOptions) obj).getStatusInterval();
    }

    public final native long getStatusInterval();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Long.valueOf(getStatusInterval())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setStatusInterval(long j);

    public String toString() {
        return "CommandClientOptions{StatusInterval:" + getStatusInterval() + ",}";
    }

    public CommandClientOptions(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
