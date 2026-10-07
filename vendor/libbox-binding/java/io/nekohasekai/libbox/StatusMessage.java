package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class StatusMessage implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public StatusMessage() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof StatusMessage)) {
            return false;
        }
        StatusMessage statusMessage = (StatusMessage) obj;
        return getMemory() == statusMessage.getMemory() && getGoroutines() == statusMessage.getGoroutines() && getConnectionsIn() == statusMessage.getConnectionsIn() && getConnectionsOut() == statusMessage.getConnectionsOut() && getTrafficAvailable() == statusMessage.getTrafficAvailable() && getUplink() == statusMessage.getUplink() && getDownlink() == statusMessage.getDownlink() && getUplinkTotal() == statusMessage.getUplinkTotal() && getDownlinkTotal() == statusMessage.getDownlinkTotal();
    }

    public final native int getConnectionsIn();

    public final native int getConnectionsOut();

    public final native long getDownlink();

    public final native long getDownlinkTotal();

    public final native int getGoroutines();

    public final native long getMemory();

    public final native boolean getTrafficAvailable();

    public final native long getUplink();

    public final native long getUplinkTotal();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Long.valueOf(getMemory()), Integer.valueOf(getGoroutines()), Integer.valueOf(getConnectionsIn()), Integer.valueOf(getConnectionsOut()), Boolean.valueOf(getTrafficAvailable()), Long.valueOf(getUplink()), Long.valueOf(getDownlink()), Long.valueOf(getUplinkTotal()), Long.valueOf(getDownlinkTotal())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setConnectionsIn(int i);

    public final native void setConnectionsOut(int i);

    public final native void setDownlink(long j);

    public final native void setDownlinkTotal(long j);

    public final native void setGoroutines(int i);

    public final native void setMemory(long j);

    public final native void setTrafficAvailable(boolean z);

    public final native void setUplink(long j);

    public final native void setUplinkTotal(long j);

    public String toString() {
        return "StatusMessage{Memory:" + getMemory() + ",Goroutines:" + getGoroutines() + ",ConnectionsIn:" + getConnectionsIn() + ",ConnectionsOut:" + getConnectionsOut() + ",TrafficAvailable:" + getTrafficAvailable() + ",Uplink:" + getUplink() + ",Downlink:" + getDownlink() + ",UplinkTotal:" + getUplinkTotal() + ",DownlinkTotal:" + getDownlinkTotal() + ",}";
    }

    public StatusMessage(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
