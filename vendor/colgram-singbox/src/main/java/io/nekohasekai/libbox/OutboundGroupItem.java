package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OutboundGroupItem implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OutboundGroupItem() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OutboundGroupItem)) {
            return false;
        }
        OutboundGroupItem outboundGroupItem = (OutboundGroupItem) obj;
        String tag = getTag();
        String tag2 = outboundGroupItem.getTag();
        if (tag == null) {
            if (tag2 != null) {
                return false;
            }
        } else if (!tag.equals(tag2)) {
            return false;
        }
        String type = getType();
        String type2 = outboundGroupItem.getType();
        if (type == null) {
            if (type2 != null) {
                return false;
            }
        } else if (!type.equals(type2)) {
            return false;
        }
        return getURLTestTime() == outboundGroupItem.getURLTestTime() && getURLTestDelay() == outboundGroupItem.getURLTestDelay();
    }

    public final native String getTag();

    public final native String getType();

    public final native int getURLTestDelay();

    public final native long getURLTestTime();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getTag(), getType(), Long.valueOf(getURLTestTime()), Integer.valueOf(getURLTestDelay())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setTag(String str);

    public final native void setType(String str);

    public final native void setURLTestDelay(int i);

    public final native void setURLTestTime(long j);

    public String toString() {
        return "OutboundGroupItem{Tag:" + getTag() + ",Type:" + getType() + ",URLTestTime:" + getURLTestTime() + ",URLTestDelay:" + getURLTestDelay() + ",}";
    }

    public OutboundGroupItem(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
