package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenConnectTunnelInfo implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenConnectTunnelInfo() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native StringIterator dns();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenConnectTunnelInfo)) {
            return false;
        }
        OpenConnectTunnelInfo openConnectTunnelInfo = (OpenConnectTunnelInfo) obj;
        String server = getServer();
        String server2 = openConnectTunnelInfo.getServer();
        if (server == null) {
            if (server2 != null) {
                return false;
            }
        } else if (!server.equals(server2)) {
            return false;
        }
        String flavor = getFlavor();
        String flavor2 = openConnectTunnelInfo.getFlavor();
        if (flavor == null) {
            if (flavor2 != null) {
                return false;
            }
        } else if (!flavor.equals(flavor2)) {
            return false;
        }
        String transport = getTransport();
        String transport2 = openConnectTunnelInfo.getTransport();
        if (transport == null) {
            if (transport2 != null) {
                return false;
            }
        } else if (!transport.equals(transport2)) {
            return false;
        }
        return getMTU() == openConnectTunnelInfo.getMTU() && getConnectedSince() == openConnectTunnelInfo.getConnectedSince();
    }

    public final native long getConnectedSince();

    public final native String getFlavor();

    public final native int getMTU();

    public final native String getServer();

    public final native String getTransport();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getServer(), getFlavor(), getTransport(), Integer.valueOf(getMTU()), Long.valueOf(getConnectedSince())});
    }

    public native StringIterator iPv4();

    public native StringIterator iPv6();

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setConnectedSince(long j);

    public final native void setFlavor(String str);

    public final native void setMTU(int i);

    public final native void setServer(String str);

    public final native void setTransport(String str);

    public String toString() {
        return "OpenConnectTunnelInfo{Server:" + getServer() + ",Flavor:" + getFlavor() + ",Transport:" + getTransport() + ",MTU:" + getMTU() + ",ConnectedSince:" + getConnectedSince() + ",}";
    }

    public OpenConnectTunnelInfo(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
