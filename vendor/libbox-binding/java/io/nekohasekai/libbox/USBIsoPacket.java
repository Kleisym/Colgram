package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class USBIsoPacket implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public USBIsoPacket() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof USBIsoPacket)) {
            return false;
        }
        USBIsoPacket uSBIsoPacket = (USBIsoPacket) obj;
        return getOffset() == uSBIsoPacket.getOffset() && getLength() == uSBIsoPacket.getLength() && getActualLength() == uSBIsoPacket.getActualLength() && getStatus() == uSBIsoPacket.getStatus();
    }

    public final native int getActualLength();

    public final native int getLength();

    public final native int getOffset();

    public final native int getStatus();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Integer.valueOf(getOffset()), Integer.valueOf(getLength()), Integer.valueOf(getActualLength()), Integer.valueOf(getStatus())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setActualLength(int i);

    public final native void setLength(int i);

    public final native void setOffset(int i);

    public final native void setStatus(int i);

    public String toString() {
        return "USBIsoPacket{Offset:" + getOffset() + ",Length:" + getLength() + ",ActualLength:" + getActualLength() + ",Status:" + getStatus() + ",}";
    }

    public USBIsoPacket(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
