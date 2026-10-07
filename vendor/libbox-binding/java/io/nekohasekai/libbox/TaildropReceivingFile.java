package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class TaildropReceivingFile implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public TaildropReceivingFile() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof TaildropReceivingFile)) {
            return false;
        }
        TaildropReceivingFile taildropReceivingFile = (TaildropReceivingFile) obj;
        String name = getName();
        String name2 = taildropReceivingFile.getName();
        if (name == null) {
            if (name2 != null) {
                return false;
            }
        } else if (!name.equals(name2)) {
            return false;
        }
        if (getSize() != taildropReceivingFile.getSize() || getReceivedBytes() != taildropReceivingFile.getReceivedBytes()) {
            return false;
        }
        String senderID = getSenderID();
        String senderID2 = taildropReceivingFile.getSenderID();
        if (senderID == null) {
            if (senderID2 != null) {
                return false;
            }
        } else if (!senderID.equals(senderID2)) {
            return false;
        }
        String senderName = getSenderName();
        String senderName2 = taildropReceivingFile.getSenderName();
        if (senderName == null) {
            return senderName2 == null;
        }
        return senderName.equals(senderName2);
    }

    public final native String getName();

    public final native long getReceivedBytes();

    public final native String getSenderID();

    public final native String getSenderName();

    public final native long getSize();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getName(), Long.valueOf(getSize()), Long.valueOf(getReceivedBytes()), getSenderID(), getSenderName()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setName(String str);

    public final native void setReceivedBytes(long j);

    public final native void setSenderID(String str);

    public final native void setSenderName(String str);

    public final native void setSize(long j);

    public String toString() {
        return "TaildropReceivingFile{Name:" + getName() + ",Size:" + getSize() + ",ReceivedBytes:" + getReceivedBytes() + ",SenderID:" + getSenderID() + ",SenderName:" + getSenderName() + ",}";
    }

    public TaildropReceivingFile(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
