package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class TaildropFile implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public TaildropFile() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof TaildropFile)) {
            return false;
        }
        TaildropFile taildropFile = (TaildropFile) obj;
        String name = getName();
        String name2 = taildropFile.getName();
        if (name == null) {
            if (name2 != null) {
                return false;
            }
        } else if (!name.equals(name2)) {
            return false;
        }
        if (getSize() != taildropFile.getSize()) {
            return false;
        }
        String senderName = getSenderName();
        String senderName2 = taildropFile.getSenderName();
        if (senderName == null) {
            if (senderName2 != null) {
                return false;
            }
        } else if (!senderName.equals(senderName2)) {
            return false;
        }
        return getModifiedAt() == taildropFile.getModifiedAt();
    }

    public final native long getModifiedAt();

    public final native String getName();

    public final native String getSenderName();

    public final native long getSize();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getName(), Long.valueOf(getSize()), getSenderName(), Long.valueOf(getModifiedAt())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setModifiedAt(long j);

    public final native void setName(String str);

    public final native void setSenderName(String str);

    public final native void setSize(long j);

    public String toString() {
        return "TaildropFile{Name:" + getName() + ",Size:" + getSize() + ",SenderName:" + getSenderName() + ",ModifiedAt:" + getModifiedAt() + ",}";
    }

    public TaildropFile(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
