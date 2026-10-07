package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class SystemProxyStatus implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public SystemProxyStatus() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof SystemProxyStatus)) {
            return false;
        }
        SystemProxyStatus systemProxyStatus = (SystemProxyStatus) obj;
        return getAvailable() == systemProxyStatus.getAvailable() && getEnabled() == systemProxyStatus.getEnabled();
    }

    public final native boolean getAvailable();

    public final native boolean getEnabled();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Boolean.valueOf(getAvailable()), Boolean.valueOf(getEnabled())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setAvailable(boolean z);

    public final native void setEnabled(boolean z);

    public String toString() {
        return "SystemProxyStatus{Available:" + getAvailable() + ",Enabled:" + getEnabled() + ",}";
    }

    public SystemProxyStatus(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
