package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class TailscaleSSHSession implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public TailscaleSSHSession() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native void close();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof TailscaleSSHSession)) {
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

    public native void sendInput(byte[] bArr);

    public native void sendResize(int i, int i2, int i3, int i4);

    public String toString() {
        return "TailscaleSSHSession{}";
    }

    public TailscaleSSHSession(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
