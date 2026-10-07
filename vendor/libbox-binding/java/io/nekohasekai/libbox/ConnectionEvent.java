package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class ConnectionEvent implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public ConnectionEvent() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof ConnectionEvent)) {
            return false;
        }
        ConnectionEvent connectionEvent = (ConnectionEvent) obj;
        if (getType() != connectionEvent.getType()) {
            return false;
        }
        String id = getID();
        String id2 = connectionEvent.getID();
        if (id == null) {
            if (id2 != null) {
                return false;
            }
        } else if (!id.equals(id2)) {
            return false;
        }
        Connection connection = getConnection();
        Connection connection2 = connectionEvent.getConnection();
        if (connection == null) {
            if (connection2 != null) {
                return false;
            }
        } else if (!connection.equals(connection2)) {
            return false;
        }
        return getUplinkDelta() == connectionEvent.getUplinkDelta() && getDownlinkDelta() == connectionEvent.getDownlinkDelta() && getClosedAt() == connectionEvent.getClosedAt();
    }

    public final native long getClosedAt();

    public final native Connection getConnection();

    public final native long getDownlinkDelta();

    public final native String getID();

    public final native int getType();

    public final native long getUplinkDelta();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Integer.valueOf(getType()), getID(), getConnection(), Long.valueOf(getUplinkDelta()), Long.valueOf(getDownlinkDelta()), Long.valueOf(getClosedAt())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setClosedAt(long j);

    public final native void setConnection(Connection connection);

    public final native void setDownlinkDelta(long j);

    public final native void setID(String str);

    public final native void setType(int i);

    public final native void setUplinkDelta(long j);

    public String toString() {
        return "ConnectionEvent{Type:" + getType() + ",ID:" + getID() + ",Connection:" + getConnection() + ",UplinkDelta:" + getUplinkDelta() + ",DownlinkDelta:" + getDownlinkDelta() + ",ClosedAt:" + getClosedAt() + ",}";
    }

    public ConnectionEvent(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
