package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class ProfileContentRequest implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public ProfileContentRequest() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native byte[] encode();

    public boolean equals(Object obj) {
        return obj != null && (obj instanceof ProfileContentRequest) && getProfileID() == ((ProfileContentRequest) obj).getProfileID();
    }

    public final native long getProfileID();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Long.valueOf(getProfileID())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setProfileID(long j);

    public String toString() {
        return "ProfileContentRequest{ProfileID:" + getProfileID() + ",}";
    }

    public ProfileContentRequest(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
