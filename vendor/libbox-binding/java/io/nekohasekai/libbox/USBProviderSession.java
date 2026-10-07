package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class USBProviderSession implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public USBProviderSession() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native void attachDevice(USBDeviceDescriptor uSBDeviceDescriptor);

    public native void close();

    public native void detachDevice(String str);

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof USBProviderSession)) {
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

    public native void sendURBResponse(USBURBResponse uSBURBResponse);

    public String toString() {
        return "USBProviderSession{}";
    }

    public USBProviderSession(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
