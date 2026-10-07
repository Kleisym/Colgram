package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class USBIPServerStatus implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public USBIPServerStatus() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native USBSharedDeviceIterator devices();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof USBIPServerStatus)) {
            return false;
        }
        String serverTag = getServerTag();
        String serverTag2 = ((USBIPServerStatus) obj).getServerTag();
        if (serverTag == null) {
            return serverTag2 == null;
        }
        return serverTag.equals(serverTag2);
    }

    public final native String getServerTag();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getServerTag()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setServerTag(String str);

    public String toString() {
        return "USBIPServerStatus{ServerTag:" + getServerTag() + ",}";
    }

    public USBIPServerStatus(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
