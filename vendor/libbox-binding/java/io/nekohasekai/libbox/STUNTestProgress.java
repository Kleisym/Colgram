package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class STUNTestProgress implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public STUNTestProgress() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof STUNTestProgress)) {
            return false;
        }
        STUNTestProgress sTUNTestProgress = (STUNTestProgress) obj;
        if (getPhase() != sTUNTestProgress.getPhase()) {
            return false;
        }
        String externalAddr = getExternalAddr();
        String externalAddr2 = sTUNTestProgress.getExternalAddr();
        if (externalAddr == null) {
            if (externalAddr2 != null) {
                return false;
            }
        } else if (!externalAddr.equals(externalAddr2)) {
            return false;
        }
        return getLatencyMs() == sTUNTestProgress.getLatencyMs() && getNATMapping() == sTUNTestProgress.getNATMapping() && getNATFiltering() == sTUNTestProgress.getNATFiltering();
    }

    public final native String getExternalAddr();

    public final native int getLatencyMs();

    public final native int getNATFiltering();

    public final native int getNATMapping();

    public final native int getPhase();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Integer.valueOf(getPhase()), getExternalAddr(), Integer.valueOf(getLatencyMs()), Integer.valueOf(getNATMapping()), Integer.valueOf(getNATFiltering())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setExternalAddr(String str);

    public final native void setLatencyMs(int i);

    public final native void setNATFiltering(int i);

    public final native void setNATMapping(int i);

    public final native void setPhase(int i);

    public String toString() {
        return "STUNTestProgress{Phase:" + getPhase() + ",ExternalAddr:" + getExternalAddr() + ",LatencyMs:" + getLatencyMs() + ",NATMapping:" + getNATMapping() + ",NATFiltering:" + getNATFiltering() + ",}";
    }

    public STUNTestProgress(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
