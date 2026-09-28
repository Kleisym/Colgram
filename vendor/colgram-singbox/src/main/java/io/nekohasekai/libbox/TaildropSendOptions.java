package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class TaildropSendOptions implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public TaildropSendOptions() {
        int i__NewTaildropSendOptions = __NewTaildropSendOptions();
        this.refnum = i__NewTaildropSendOptions;
        Seq.trackGoRef(i__NewTaildropSendOptions, this);
    }

    private static native int __NewTaildropSendOptions();

    public native void addFile(String str, long j);

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof TaildropSendOptions)) {
            return false;
        }
        TaildropSendOptions taildropSendOptions = (TaildropSendOptions) obj;
        String endpointTag = getEndpointTag();
        String endpointTag2 = taildropSendOptions.getEndpointTag();
        if (endpointTag == null) {
            if (endpointTag2 != null) {
                return false;
            }
        } else if (!endpointTag.equals(endpointTag2)) {
            return false;
        }
        String peerStableID = getPeerStableID();
        String peerStableID2 = taildropSendOptions.getPeerStableID();
        if (peerStableID == null) {
            return peerStableID2 == null;
        }
        return peerStableID.equals(peerStableID2);
    }

    public final native String getEndpointTag();

    public final native String getPeerStableID();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getEndpointTag(), getPeerStableID()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setEndpointTag(String str);

    public final native void setPeerStableID(String str);

    public String toString() {
        return "TaildropSendOptions{EndpointTag:" + getEndpointTag() + ",PeerStableID:" + getPeerStableID() + ",}";
    }

    public TaildropSendOptions(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
